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
package io.prestosql.plugin.phoenix;

import io.airlift.bootstrap.LifeCycleManager;
import io.prestosql.plugin.jdbc.JdbcTransactionHandle;
import io.prestosql.plugin.jdbc.SessionPropertiesProvider;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorPageSinkProvider;
import io.prestosql.spi.connector.ConnectorRecordSetProvider;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.transaction.IsolationLevel;

import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class PhoenixConnector
        implements Connector
{
    private final LifeCycleManager lifeCycleManager;
    private final ConnectorMetadata metadata;
    private final ConnectorSplitManager splitManager;
    private final ConnectorRecordSetProvider recordSetProvider;
    private final ConnectorPageSinkProvider pageSinkProvider;
    private final PhoenixTableProperties tableProperties;
    private final PhoenixColumnProperties columnProperties;
    private final List<PropertyMetadata<?>> sessionProperties;

    public PhoenixConnector(
            LifeCycleManager lifeCycleManager,
            ConnectorMetadata metadata,
            ConnectorSplitManager splitManager,
            ConnectorRecordSetProvider recordSetProvider,
            ConnectorPageSinkProvider pageSinkProvider,
            PhoenixTableProperties tableProperties,
            PhoenixColumnProperties columnProperties,
            Set<SessionPropertiesProvider> sessionProperties)
    {
        this.lifeCycleManager = requireNonNull(lifeCycleManager, "lifeCycleManager is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.splitManager = requireNonNull(splitManager, "splitManager is null");
        this.recordSetProvider = requireNonNull(recordSetProvider, "recordSetProvider is null");
        this.pageSinkProvider = requireNonNull(pageSinkProvider, "pageSinkProvider is null");
        this.tableProperties = requireNonNull(tableProperties, "tableProperties is null");
        this.columnProperties = requireNonNull(columnProperties, "columnProperties is null");
        this.sessionProperties = requireNonNull(sessionProperties, "sessionProperties is null").stream()
                .flatMap(sessionPropertiesProvider -> sessionPropertiesProvider.getSessionProperties().stream())
                .collect(toImmutableList());
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly)
    {
        return new JdbcTransactionHandle();
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorTransactionHandle transaction)
    {
        return metadata;
    }

    @Override
    public ConnectorSplitManager getSplitManager()
    {
        return splitManager;
    }

    @Override
    public ConnectorRecordSetProvider getRecordSetProvider()
    {
        return recordSetProvider;
    }

    @Override
    public ConnectorPageSinkProvider getPageSinkProvider()
    {
        return pageSinkProvider;
    }

    @Override
    public List<PropertyMetadata<?>> getTableProperties()
    {
        return tableProperties.getTableProperties();
    }

    @Override
    public List<PropertyMetadata<?>> getColumnProperties()
    {
        return columnProperties.getColumnProperties();
    }

    @Override
    public List<PropertyMetadata<?>> getSessionProperties()
    {
        return sessionProperties;
    }

    @Override
    public final void shutdown()
    {
        lifeCycleManager.stop();
    }
}
