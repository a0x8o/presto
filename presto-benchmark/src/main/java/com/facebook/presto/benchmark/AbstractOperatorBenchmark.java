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
package com.facebook.presto.benchmark;

import com.facebook.presto.Session;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskStateMachine;
import com.facebook.presto.memory.MemoryPool;
import com.facebook.presto.memory.QueryContext;
import com.facebook.presto.operator.Driver;
import com.facebook.presto.operator.OperatorFactory;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.security.AllowAllAccessControl;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.memory.MemoryPoolId;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spiller.SpillSpaceTracker;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.transaction.TransactionId;
import com.google.common.collect.ImmutableMap;
import io.airlift.stats.CpuTimer;
import io.airlift.stats.TestingGcMonitor;
import io.airlift.units.DataSize;

import java.util.List;
import java.util.Map;

import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static io.airlift.stats.CpuTimer.CpuDuration;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Abstract template for benchmarks that want to test the performance of an Operator.
 */
public abstract class AbstractOperatorBenchmark
        extends AbstractBenchmark
{
    protected final LocalQueryRunner localQueryRunner;
    protected final Session session;

    protected AbstractOperatorBenchmark(
            LocalQueryRunner localQueryRunner,
            String benchmarkName,
            int warmupIterations,
            int measuredIterations)
    {
        this(localQueryRunner.getDefaultSession(), localQueryRunner, benchmarkName, warmupIterations, measuredIterations);
    }

    protected AbstractOperatorBenchmark(
            Session session,
            LocalQueryRunner localQueryRunner,
            String benchmarkName,
            int warmupIterations,
            int measuredIterations)
    {
        super(benchmarkName, warmupIterations, measuredIterations);
        this.localQueryRunner = requireNonNull(localQueryRunner, "localQueryRunner is null");

        TransactionId transactionId = localQueryRunner.getTransactionManager().beginTransaction(false);
        this.session = session.beginTransactionId(
                transactionId,
                localQueryRunner.getTransactionManager(),
                new AllowAllAccessControl());
    }

    @Override
    protected void tearDown()
    {
        localQueryRunner.getTransactionManager().asyncAbort(session.getRequiredTransactionId());
        super.tearDown();
    }

    protected OperatorFactory createTableScanOperator(int operatorId, PlanNodeId planNodeId, String tableName, String... columnNames)
    {
        return localQueryRunner.createTableScanOperator(session, operatorId, planNodeId, tableName, columnNames);
    }

    protected OperatorFactory createHashProjectOperator(int operatorId, PlanNodeId planNodeId, List<Type> types)
    {
        return localQueryRunner.createHashProjectOperator(session, operatorId, planNodeId, types);
    }

    protected abstract List<Driver> createDrivers(TaskContext taskContext);

    protected Map<String, Long> execute(TaskContext taskContext)
    {
        List<Driver> drivers = createDrivers(taskContext);

        long peakMemory = 0;
        boolean done = false;
        while (!done) {
            boolean processed = false;
            for (Driver driver : drivers) {
                if (!driver.isFinished()) {
                    driver.process();
                    long lastPeakMemory = peakMemory;
                    peakMemory = (long) taskContext.getTaskStats().getUserMemoryReservation().getValue(BYTE);
                    if (peakMemory <= lastPeakMemory) {
                        peakMemory = lastPeakMemory;
                    }
                    processed = true;
                }
            }
            done = !processed;
        }
        return ImmutableMap.of("peak_memory", peakMemory);
    }

    @Override
    protected Map<String, Long> runOnce()
    {
        Session session = testSessionBuilder()
                .setSystemProperty("optimizer.optimize-hash-generation", "true")
                .build();
        MemoryPool memoryPool = new MemoryPool(new MemoryPoolId("test"), new DataSize(1, GIGABYTE));
        MemoryPool systemMemoryPool = new MemoryPool(new MemoryPoolId("testSystem"), new DataSize(1, GIGABYTE));
        SpillSpaceTracker spillSpaceTracker = new SpillSpaceTracker(new DataSize(1, GIGABYTE));

        TaskContext taskContext = new QueryContext(
                new QueryId("test"),
                new DataSize(256, MEGABYTE),
                memoryPool,
                systemMemoryPool,
                new TestingGcMonitor(),
                localQueryRunner.getExecutor(),
                localQueryRunner.getScheduler(),
                new DataSize(256, MEGABYTE),
                spillSpaceTracker)
                .addTaskContext(new TaskStateMachine(new TaskId("query", 0, 0), localQueryRunner.getExecutor()),
                        session,
                        false,
                        false);

        CpuTimer cpuTimer = new CpuTimer();
        Map<String, Long> executionStats = execute(taskContext);
        CpuDuration executionTime = cpuTimer.elapsedTime();

        TaskStats taskStats = taskContext.getTaskStats();
        long inputRows = taskStats.getRawInputPositions();
        long inputBytes = taskStats.getRawInputDataSize().toBytes();
        long outputRows = taskStats.getOutputPositions();
        long outputBytes = taskStats.getOutputDataSize().toBytes();

        double inputMegaBytes = new DataSize(inputBytes, BYTE).getValue(MEGABYTE);

        return ImmutableMap.<String, Long>builder()
                // legacy computed values
                .putAll(executionStats)
                .put("elapsed_millis", executionTime.getWall().toMillis())
                .put("input_rows_per_second", (long) (inputRows / executionTime.getWall().getValue(SECONDS)))
                .put("output_rows_per_second", (long) (outputRows / executionTime.getWall().getValue(SECONDS)))
                .put("input_megabytes", (long) inputMegaBytes)
                .put("input_megabytes_per_second", (long) (inputMegaBytes / executionTime.getWall().getValue(SECONDS)))

                .put("wall_nanos", executionTime.getWall().roundTo(NANOSECONDS))
                .put("cpu_nanos", executionTime.getCpu().roundTo(NANOSECONDS))
                .put("user_nanos", executionTime.getUser().roundTo(NANOSECONDS))
                .put("input_rows", inputRows)
                .put("input_bytes", inputBytes)
                .put("output_rows", outputRows)
                .put("output_bytes", outputBytes)

                .build();
    }
}
