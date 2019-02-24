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
package com.facebook.presto.operator;

import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.operator.AggregationOperator.AggregationOperatorFactory;
import com.facebook.presto.operator.aggregation.InternalAggregationFunction;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.sql.planner.plan.AggregationNode.Step;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.testing.MaterializedResult;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.facebook.presto.RowPagesBuilder.rowPagesBuilder;
import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static com.facebook.presto.metadata.FunctionKind.AGGREGATE;
import static com.facebook.presto.operator.OperatorAssertion.assertOperatorEquals;
import static com.facebook.presto.operator.OperatorAssertion.toPages;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.testing.MaterializedResult.resultBuilder;
import static com.facebook.presto.testing.TestingTaskContext.createTaskContext;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.Collections.emptyIterator;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestAggregationOperator
{
    private static final MetadataManager metadata = MetadataManager.createTestMetadataManager();

    private static final InternalAggregationFunction LONG_AVERAGE = metadata.getFunctionManager().getAggregateFunctionImplementation(
            new Signature("avg", AGGREGATE, DOUBLE.getTypeSignature(), BIGINT.getTypeSignature()));
    private static final InternalAggregationFunction DOUBLE_SUM = metadata.getFunctionManager().getAggregateFunctionImplementation(
            new Signature("sum", AGGREGATE, DOUBLE.getTypeSignature(), DOUBLE.getTypeSignature()));
    private static final InternalAggregationFunction LONG_SUM = metadata.getFunctionManager().getAggregateFunctionImplementation(
            new Signature("sum", AGGREGATE, BIGINT.getTypeSignature(), BIGINT.getTypeSignature()));
    private static final InternalAggregationFunction REAL_SUM = metadata.getFunctionManager().getAggregateFunctionImplementation(
            new Signature("sum", AGGREGATE, REAL.getTypeSignature(), REAL.getTypeSignature()));
    private static final InternalAggregationFunction COUNT = metadata.getFunctionManager().getAggregateFunctionImplementation(
            new Signature("count", AGGREGATE, BIGINT.getTypeSignature()));

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
    }

    @AfterMethod
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testAggregation()
    {
        MetadataManager metadata = MetadataManager.createTestMetadataManager();
        InternalAggregationFunction countVarcharColumn = metadata.getFunctionManager().getAggregateFunctionImplementation(
                new Signature("count", AGGREGATE, parseTypeSignature(StandardTypes.BIGINT), parseTypeSignature(StandardTypes.VARCHAR)));
        InternalAggregationFunction maxVarcharColumn = metadata.getFunctionManager().getAggregateFunctionImplementation(
                new Signature("max", AGGREGATE, parseTypeSignature(StandardTypes.VARCHAR), parseTypeSignature(StandardTypes.VARCHAR)));
        List<Page> input = rowPagesBuilder(VARCHAR, BIGINT, VARCHAR, BIGINT, REAL, DOUBLE, VARCHAR)
                .addSequencePage(100, 0, 0, 300, 500, 400, 500, 500)
                .build();

        OperatorFactory operatorFactory = new AggregationOperatorFactory(
                0,
                new PlanNodeId("test"),
                Step.SINGLE,
                ImmutableList.of(COUNT.bind(ImmutableList.of(0), Optional.empty()),
                        LONG_SUM.bind(ImmutableList.of(1), Optional.empty()),
                        LONG_AVERAGE.bind(ImmutableList.of(1), Optional.empty()),
                        maxVarcharColumn.bind(ImmutableList.of(2), Optional.empty()),
                        countVarcharColumn.bind(ImmutableList.of(0), Optional.empty()),
                        LONG_SUM.bind(ImmutableList.of(3), Optional.empty()),
                        REAL_SUM.bind(ImmutableList.of(4), Optional.empty()),
                        DOUBLE_SUM.bind(ImmutableList.of(5), Optional.empty()),
                        maxVarcharColumn.bind(ImmutableList.of(6), Optional.empty())),
                false);

        DriverContext driverContext = createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT, BIGINT, DOUBLE, VARCHAR, BIGINT, BIGINT, REAL, DOUBLE, VARCHAR)
                .row(100L, 4950L, 49.5, "399", 100L, 54950L, 44950.0f, 54950.0, "599")
                .build();

        assertOperatorEquals(operatorFactory, driverContext, input, expected);
        assertEquals(driverContext.getSystemMemoryUsage(), 0);
        assertEquals(driverContext.getMemoryUsage(), 0);
    }

    @Test
    public void testMemoryTracking()
            throws Exception
    {
        testMemoryTracking(false);
        testMemoryTracking(true);
    }

    private void testMemoryTracking(boolean useSystemMemory)
            throws Exception
    {
        Page input = getOnlyElement(rowPagesBuilder(BIGINT).addSequencePage(100, 0).build());

        OperatorFactory operatorFactory = new AggregationOperatorFactory(
                0,
                new PlanNodeId("test"),
                Step.SINGLE,
                ImmutableList.of(LONG_SUM.bind(ImmutableList.of(0), Optional.empty())),
                useSystemMemory);

        DriverContext driverContext = createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();

        try (Operator operator = operatorFactory.createOperator(driverContext)) {
            assertTrue(operator.needsInput());
            operator.addInput(input);

            if (useSystemMemory) {
                assertThat(driverContext.getSystemMemoryUsage()).isGreaterThan(0);
                assertEquals(driverContext.getMemoryUsage(), 0);
            }
            else {
                assertEquals(driverContext.getSystemMemoryUsage(), 0);
                assertThat(driverContext.getMemoryUsage()).isGreaterThan(0);
            }

            toPages(operator, emptyIterator());
        }

        assertEquals(driverContext.getSystemMemoryUsage(), 0);
        assertEquals(driverContext.getMemoryUsage(), 0);
    }
}
