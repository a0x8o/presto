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
package com.facebook.presto.metadata;

import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.DiscretePredicates;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.sql.planner.PartitioningHandle;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class TableLayout
{
    private final TableLayoutHandle handle;
    private final ConnectorTableLayout layout;

    public TableLayout(TableLayoutHandle handle, ConnectorTableLayout layout)
    {
        requireNonNull(handle, "handle is null");
        requireNonNull(layout, "layout is null");

        this.handle = handle;
        this.layout = layout;
    }

    public ConnectorId getConnectorId()
    {
        return handle.getConnectorId();
    }

    public Optional<List<ColumnHandle>> getColumns()
    {
        return layout.getColumns();
    }

    public TupleDomain<ColumnHandle> getPredicate()
    {
        return layout.getPredicate();
    }

    public List<LocalProperty<ColumnHandle>> getLocalProperties()
    {
        return layout.getLocalProperties();
    }

    public TableLayoutHandle getHandle()
    {
        return handle;
    }

    public Optional<TablePartitioning> getTablePartitioning()
    {
        return layout.getTablePartitioning()
                .map(nodePartitioning -> new TablePartitioning(
                        new PartitioningHandle(
                                Optional.of(handle.getConnectorId()),
                                Optional.of(handle.getTransactionHandle()),
                                nodePartitioning.getPartitioningHandle()),
                        nodePartitioning.getPartitioningColumns()));
    }

    public Optional<Set<ColumnHandle>> getStreamPartitioningColumns()
    {
        return layout.getStreamPartitioningColumns();
    }

    public Optional<DiscretePredicates> getDiscretePredicates()
    {
        return layout.getDiscretePredicates();
    }

    public static TableLayout fromConnectorLayout(ConnectorId connectorId, ConnectorTransactionHandle transactionHandle, ConnectorTableLayout layout)
    {
        return new TableLayout(new TableLayoutHandle(connectorId, transactionHandle, layout.getHandle()), layout);
    }

    public static class TablePartitioning
    {
        private final PartitioningHandle partitioningHandle;
        private final List<ColumnHandle> partitioningColumns;

        public TablePartitioning(PartitioningHandle partitioningHandle, List<ColumnHandle> partitioningColumns)
        {
            this.partitioningHandle = requireNonNull(partitioningHandle, "partitioningHandle is null");
            this.partitioningColumns = ImmutableList.copyOf(requireNonNull(partitioningColumns, "partitioningColumns is null"));
        }

        public PartitioningHandle getPartitioningHandle()
        {
            return partitioningHandle;
        }

        public List<ColumnHandle> getPartitioningColumns()
        {
            return partitioningColumns;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TablePartitioning that = (TablePartitioning) o;
            return Objects.equals(partitioningHandle, that.partitioningHandle) &&
                    Objects.equals(partitioningColumns, that.partitioningColumns);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(partitioningHandle, partitioningColumns);
        }
    }
}
