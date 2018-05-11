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
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.memory.VersionedMemoryPoolId;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.resourceGroups.ResourceGroupId;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.Duration;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;

public class DataDefinitionExecution<T extends Statement>
        implements QueryExecution
{
    private final DataDefinitionTask<T> task;
    private final T statement;
    private final TransactionManager transactionManager;
    private final Metadata metadata;
    private final AccessControl accessControl;
    private final QueryStateMachine stateMachine;
    private final List<Expression> parameters;

    private DataDefinitionExecution(
            DataDefinitionTask<T> task,
            T statement,
            TransactionManager transactionManager,
            Metadata metadata,
            AccessControl accessControl,
            QueryStateMachine stateMachine,
            List<Expression> parameters)
    {
        this.task = requireNonNull(task, "task is null");
        this.statement = requireNonNull(statement, "statement is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.stateMachine = requireNonNull(stateMachine, "stateMachine is null");
        this.parameters = parameters;
    }

    @Override
    public VersionedMemoryPoolId getMemoryPool()
    {
        return stateMachine.getMemoryPool();
    }

    @Override
    public void setMemoryPool(VersionedMemoryPoolId poolId)
    {
        stateMachine.setMemoryPool(poolId);
    }

    @Override
    public long getUserMemoryReservation()
    {
        return 0;
    }

    @Override
    public long getTotalMemoryReservation()
    {
        return 0;
    }

    @Override
    public Duration getTotalCpuTime()
    {
        return new Duration(0, TimeUnit.SECONDS);
    }

    @Override
    public Session getSession()
    {
        return stateMachine.getSession();
    }

    @Override
    public void start()
    {
        try {
            // transition to running
            if (!stateMachine.transitionToRunning()) {
                // query already running or finished
                return;
            }

            ListenableFuture<?> future = task.execute(statement, transactionManager, metadata, accessControl, stateMachine, parameters);
            Futures.addCallback(future, new FutureCallback<Object>()
            {
                @Override
                public void onSuccess(@Nullable Object result)
                {
                    stateMachine.transitionToFinishing();
                }

                @Override
                public void onFailure(Throwable throwable)
                {
                    fail(throwable);
                }
            }, directExecutor());
        }
        catch (Throwable e) {
            fail(e);
            throwIfInstanceOf(e, Error.class);
        }
    }

    @Override
    public void addOutputInfoListener(Consumer<QueryOutputInfo> listener)
    {
        // DDL does not have an output
    }

    @Override
    public ListenableFuture<QueryState> getStateChange(QueryState currentState)
    {
        return stateMachine.getStateChange(currentState);
    }

    @Override
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        stateMachine.addStateChangeListener(stateChangeListener);
    }

    @Override
    public void addFinalQueryInfoListener(StateChangeListener<QueryInfo> stateChangeListener)
    {
        stateMachine.addQueryInfoStateChangeListener(stateChangeListener);
    }

    @Override
    public void fail(Throwable cause)
    {
        stateMachine.transitionToFailed(cause);
    }

    @Override
    public void cancelQuery()
    {
        stateMachine.transitionToCanceled();
    }

    @Override
    public void cancelStage(StageId stageId)
    {
        // no-op
    }

    @Override
    public void recordHeartbeat()
    {
        stateMachine.recordHeartbeat();
    }

    @Override
    public void pruneInfo()
    {
        // no-op
    }

    @Override
    public QueryId getQueryId()
    {
        return stateMachine.getQueryId();
    }

    @Override
    public QueryInfo getQueryInfo()
    {
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get();
        }
        return stateMachine.updateQueryInfo(Optional.empty());
    }

    @Override
    public Plan getQueryPlan()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public QueryState getState()
    {
        return stateMachine.getQueryState();
    }

    @Override
    public Optional<ResourceGroupId> getResourceGroup()
    {
        return stateMachine.getResourceGroup();
    }

    @Override
    public void setResourceGroup(ResourceGroupId resourceGroupId)
    {
        stateMachine.setResourceGroup(resourceGroupId);
    }

    public List<Expression> getParameters()
    {
        return parameters;
    }

    public static class DataDefinitionExecutionFactory
            implements QueryExecutionFactory<DataDefinitionExecution<?>>
    {
        private final LocationFactory locationFactory;
        private final TransactionManager transactionManager;
        private final Metadata metadata;
        private final AccessControl accessControl;
        private final ExecutorService executor;
        private final Map<Class<? extends Statement>, DataDefinitionTask<?>> tasks;

        @Inject
        public DataDefinitionExecutionFactory(
                LocationFactory locationFactory,
                TransactionManager transactionManager,
                MetadataManager metadata,
                AccessControl accessControl,
                @ForQueryExecution ExecutorService executor,
                Map<Class<? extends Statement>, DataDefinitionTask<?>> tasks)
        {
            this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
            this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.accessControl = requireNonNull(accessControl, "accessControl is null");
            this.executor = requireNonNull(executor, "executor is null");
            this.tasks = requireNonNull(tasks, "tasks is null");
        }

        public String explain(Statement statement, List<Expression> parameters)
        {
            DataDefinitionTask<Statement> task = getTask(statement);
            checkArgument(task != null, "no task for statement: %s", statement.getClass().getSimpleName());
            return task.explain(statement, parameters);
        }

        @Override
        public DataDefinitionExecution<?> createQueryExecution(
                QueryId queryId,
                String query,
                Session session,
                Statement statement,
                List<Expression> parameters)
        {
            URI self = locationFactory.createQueryLocation(queryId);

            DataDefinitionTask<Statement> task = getTask(statement);
            checkArgument(task != null, "no task for statement: %s", statement.getClass().getSimpleName());

            QueryStateMachine stateMachine = QueryStateMachine.begin(queryId, query, session, self, task.isTransactionControl(), transactionManager, accessControl, executor, metadata);
            stateMachine.setUpdateType(task.getName());
            return new DataDefinitionExecution<>(task, statement, transactionManager, metadata, accessControl, stateMachine, parameters);
        }

        @SuppressWarnings("unchecked")
        private <T extends Statement> DataDefinitionTask<T> getTask(T statement)
        {
            return (DataDefinitionTask<T>) tasks.get(statement.getClass());
        }
    }
}
