/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.geospatial;

import com.esri.core.geometry.ogc.OGCGeometry;
import com.facebook.presto.RowPagesBuilder;
import com.facebook.presto.operator.Driver;
import com.facebook.presto.operator.DriverContext;
import com.facebook.presto.operator.InternalJoinFilterFunction;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.operator.OperatorFactory;
import com.facebook.presto.operator.PagesIndex.TestingFactory;
import com.facebook.presto.operator.PagesSpatialIndex;
import com.facebook.presto.operator.PagesSpatialIndexFactory;
import com.facebook.presto.operator.SpatialIndexBuilderOperator.SpatialIndexBuilderOperatorFactory;
import com.facebook.presto.operator.SpatialJoinOperator.SpatialJoinOperatorFactory;
import com.facebook.presto.operator.StandardJoinFilterFunction;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.operator.ValuesOperator;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.sql.gen.JoinFilterFunctionCompiler;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.testing.TestingTaskContext;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static com.facebook.presto.RowPagesBuilder.rowPagesBuilder;
import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static com.facebook.presto.operator.OperatorAssertion.assertOperatorEquals;
import static com.facebook.presto.plugin.geospatial.GeoFunctions.stGeometryFromText;
import static com.facebook.presto.plugin.geospatial.GeoFunctions.stPoint;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.testing.MaterializedResult.resultBuilder;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestSpatialJoinOperator
{
    //  2 intersecting polygons: A and B
    private static final Slice POLYGON_A = stGeometryFromText(Slices.utf8Slice("POLYGON ((0 0, -0.5 2.5, 0 5, 2.5 5.5, 5 5, 5.5 2.5, 5 0, 2.5 -0.5, 0 0))"));
    private static final Slice POLYGON_B = stGeometryFromText(Slices.utf8Slice("POLYGON ((4 4, 3.5 7, 4 10, 7 10.5, 10 10, 10.5 7, 10 4, 7 3.5, 4 4))"));

    // A set of points: X in A, Y in A and B, Z in B, W outside of A and B
    private static final Slice POINT_X = stPoint(1, 1);
    private static final Slice POINT_Y = stPoint(4.5, 4.5);
    private static final Slice POINT_Z = stPoint(6, 6);
    private static final Slice POINT_W = stPoint(20, 20);

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @BeforeMethod
    public void setUp()
    {
        // Before/AfterMethod is chosen here because the executor needs to be shutdown
        // after every single test case to terminate outstanding threads, if any.

        // The line below is the same as newCachedThreadPool(daemonThreadsNamed(...)) except RejectionExecutionHandler.
        // RejectionExecutionHandler is set to DiscardPolicy (instead of the default AbortPolicy) here.
        // Otherwise, a large number of RejectedExecutionException will flood logging, resulting in Travis failure.
        executor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                SECONDS,
                new SynchronousQueue<Runnable>(),
                daemonThreadsNamed("test-executor-%s"),
                new ThreadPoolExecutor.DiscardPolicy());
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testSpatialJoin()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe) -> build.contains(probe), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), probePages.getTypes(), Ints.asList(1), 0, pagesSpatialIndexFactory);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .build();

        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testEmptyBuild()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe) -> build.contains(probe), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), probePages.getTypes(), Ints.asList(1), 0, pagesSpatialIndexFactory);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR)).build();

        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testEmptyProbe()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe) -> build.contains(probe), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), probePages.getTypes(), Ints.asList(1), 0, pagesSpatialIndexFactory);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR)).build();

        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testYield()
    {
        // create a filter function that yields for every probe match
        // verify we will yield #match times totally

        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true).addDriverContext();

        // force a yield for every match
        AtomicInteger filterFunctionCalls = new AtomicInteger();
        InternalJoinFilterFunction filterFunction = new TestInternalJoinFilterFunction((
                (leftPosition, leftBlocks, rightPosition, rightBlocks) -> {
                    filterFunctionCalls.incrementAndGet();
                    driverContext.getYieldSignal().forceYieldForTesting();
                    return true;
                }));

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(POLYGON_B, "B");
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe) -> build.contains(probe), Optional.of(filterFunction), buildPages);

        // 10 points in polygon A (x0...x9)
        // 10 points in polygons A and B (y0...y9)
        // 10 points in polygon B (z0...z9)
        // 40 total matches
        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(1 + 0.1 * i, 1 + 0.1 * i), "x" + i);
        }
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(4.5 + 0.01 * i, 4.5 + 0.01 * i), "y" + i);
        }
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(6 + 0.1 * i, 6 + 0.1 * i), "z" + i);
        }
        List<Page> probeInput = probePages.build();

        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), probePages.getTypes(), Ints.asList(1), 0, pagesSpatialIndexFactory);

        Operator operator = joinOperatorFactory.createOperator(driverContext);
        assertTrue(operator.needsInput());
        operator.addInput(probeInput.get(0));
        operator.finish();

        // we will yield 40 times due to filterFunction
        for (int i = 0; i < 40; i++) {
            driverContext.getYieldSignal().setWithDelay(5 * SECONDS.toNanos(1), driverContext.getYieldExecutor());
            assertNull(operator.getOutput());
            assertEquals(filterFunctionCalls.get(), i + 1, "Expected join to stop processing (yield) after calling filter function once");
            driverContext.getYieldSignal().reset();
        }
        // delayed yield is not going to prevent operator from producing a page now (yield won't be forced because filter function won't be called anymore)
        driverContext.getYieldSignal().setWithDelay(5 * SECONDS.toNanos(1), driverContext.getYieldExecutor());
        Page output = operator.getOutput();
        assertNotNull(output);

        // make sure we have 40 matches
        assertEquals(output.getPositionCount(), 40);
    }

    private PagesSpatialIndexFactory buildIndex(DriverContext driverContext, BiPredicate<OGCGeometry, OGCGeometry> spatialRelationshipTest, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        Optional<JoinFilterFunctionCompiler.JoinFilterFunctionFactory> filterFunctionFactory = filterFunction
                .map(function -> (session, addresses, channels) -> new StandardJoinFilterFunction(function, addresses, channels));

        ValuesOperator.ValuesOperatorFactory valuesOperatorFactory = new ValuesOperator.ValuesOperatorFactory(0, new PlanNodeId("test"), buildPages.getTypes(), buildPages.build());
        SpatialIndexBuilderOperatorFactory buildOperatorFactory = new SpatialIndexBuilderOperatorFactory(
                1,
                new PlanNodeId("test"),
                buildPages.getTypes(),
                Ints.asList(1),
                0,
                spatialRelationshipTest,
                filterFunctionFactory,
                10_000,
                new TestingFactory(false));

        Driver driver = Driver.createDriver(driverContext,
                valuesOperatorFactory.createOperator(driverContext),
                buildOperatorFactory.createOperator(driverContext));

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildOperatorFactory.getPagesSpatialIndexFactory();
        ListenableFuture<PagesSpatialIndex> pagesSpatialIndex = pagesSpatialIndexFactory.createPagesSpatialIndex();

        while (!pagesSpatialIndex.isDone()) {
            driver.process();
        }

        runDriverInThread(executor, driver);
        return pagesSpatialIndexFactory;
    }

    /**
     * Runs Driver in another thread until it is finished
     */
    private static void runDriverInThread(ExecutorService executor, Driver driver)
    {
        executor.execute(() -> {
            if (!driver.isFinished()) {
                try {
                    driver.process();
                }
                catch (PrestoException e) {
                    driver.getDriverContext().failed(e);
                    throw e;
                }
                runDriverInThread(executor, driver);
            }
        });
    }

    private TaskContext createTaskContext()
    {
        return TestingTaskContext.createTaskContext(executor, scheduledExecutor, TEST_SESSION);
    }

    private static class TestInternalJoinFilterFunction
            implements InternalJoinFilterFunction
    {
        public interface Lambda
        {
            boolean filter(int leftPosition, Block[] leftBlocks, int rightPosition, Block[] rightBlocks);
        }

        private final TestInternalJoinFilterFunction.Lambda lambda;

        private TestInternalJoinFilterFunction(TestInternalJoinFilterFunction.Lambda lambda)
        {
            this.lambda = lambda;
        }

        @Override
        public boolean filter(int leftPosition, Block[] leftBlocks, int rightPosition, Block[] rightBlocks)
        {
            return lambda.filter(leftPosition, leftBlocks, rightPosition, rightBlocks);
        }
    }
}
