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
package io.prestosql.plugin.hive.procedure;

import com.google.common.collect.ImmutableList;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveMetastoreClosure;
import io.prestosql.plugin.hive.HiveTableHandle;
import io.prestosql.plugin.hive.PartitionStatistics;
import io.prestosql.plugin.hive.TransactionalMetadata;
import io.prestosql.plugin.hive.TransactionalMetadataFactory;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.classloader.ThreadContextClassLoader;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.procedure.Procedure;
import io.prestosql.spi.procedure.Procedure.Argument;
import io.prestosql.spi.type.ArrayType;

import javax.inject.Inject;
import javax.inject.Provider;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.StandardErrorCode.INVALID_PROCEDURE_ARGUMENT;
import static io.prestosql.spi.block.MethodHandleUtil.methodHandle;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.metastore.utils.FileUtils.makePartName;

/**
 * A procedure that drops statistics.  It can be invoked for a subset of partitions (e.g.
 * {@code CALL system.drop_stats('system', 'some_table', ARRAY[ARRAY['x', '7']])}) or
 * for the entire table ({@code CALL system.drop_stats('system', 'some_table', NULL)})).
 */
public class DropStatsProcedure
        implements Provider<Procedure>
{
    private static final MethodHandle DROP_STATS = methodHandle(
            DropStatsProcedure.class,
            "dropStats",
            ConnectorSession.class,
            String.class,
            String.class,
            List.class);

    private final TransactionalMetadataFactory hiveMetadataFactory;
    private final HiveMetastoreClosure metastore;

    @Inject
    public DropStatsProcedure(TransactionalMetadataFactory hiveMetadataFactory, HiveMetastore metastore)
    {
        this.hiveMetadataFactory = requireNonNull(hiveMetadataFactory, "hiveMetadataFactory is null");
        this.metastore = new HiveMetastoreClosure(requireNonNull(metastore, "metastore is null"));
    }

    @Override
    public Procedure get()
    {
        return new Procedure(
                "system",
                "drop_stats",
                ImmutableList.of(
                        new Argument("schema_name", VARCHAR),
                        new Argument("table_name", VARCHAR),
                        new Argument("partition_values", new ArrayType(new ArrayType(VARCHAR)))),
                DROP_STATS.bindTo(this));
    }

    public void dropStats(ConnectorSession session, String schema, String table, List<?> partitionValues)
    {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(getClass().getClassLoader())) {
            doDropStats(session, schema, table, partitionValues);
        }
    }

    private void doDropStats(ConnectorSession session, String schema, String table, List<?> partitionValues)
    {
        TransactionalMetadata hiveMetadata = hiveMetadataFactory.create();
        HiveTableHandle handle = (HiveTableHandle) hiveMetadata.getTableHandle(session, new SchemaTableName(schema, table));
        if (handle == null) {
            throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, format("Table '%s' does not exist", new SchemaTableName(schema, table)));
        }
        Map<String, ColumnHandle> columns = hiveMetadata.getColumnHandles(session, handle);
        List<String> partitionColumns = columns.values().stream()
                .map(HiveColumnHandle.class::cast)
                .filter(HiveColumnHandle::isPartitionKey)
                .map(HiveColumnHandle::getName)
                .collect(toImmutableList());

        if (partitionValues != null) {
            // drop stats for specified partitions
            List<List<String>> partitionStringValues = partitionValues.stream()
                    .map(DropStatsProcedure::validateParameterType)
                    .collect(toImmutableList());
            validatePartitions(partitionStringValues, partitionColumns);

            partitionStringValues.forEach(values -> metastore.updatePartitionStatistics(
                    new HiveIdentity(session.getIdentity()),
                    schema,
                    table,
                    makePartName(partitionColumns, values),
                    stats -> PartitionStatistics.empty()));
        }
        else {
            // no partition specified, so drop stats for the entire table
            if (partitionColumns.isEmpty()) {
                // for non-partitioned tables, just wipe table stats
                metastore.updateTableStatistics(
                        new HiveIdentity(session.getIdentity()),
                        schema,
                        table,
                        stats -> PartitionStatistics.empty());
            }
            else {
                // the table is partitioned; remove stats for every partition
                metastore.getPartitionNames(new HiveIdentity(session.getIdentity()), handle.getSchemaName(), handle.getTableName())
                        .ifPresent(partitions -> partitions.forEach(partitionName -> metastore.updatePartitionStatistics(
                                new HiveIdentity(session.getIdentity()),
                                schema,
                                table,
                                partitionName,
                                stats -> PartitionStatistics.empty())));
            }
        }

        hiveMetadata.commit();
    }

    private static List<String> validateParameterType(Object param)
    {
        if (param == null) {
            throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Null partition value");
        }

        if (param instanceof List) {
            return ((List<?>) param)
                    .stream()
                    .map(String.class::cast)
                    .collect(toImmutableList());
        }

        throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Partition value must be an array");
    }

    private static void validatePartitions(List<List<String>> partitionValues, List<String> partitionColumns)
    {
        if (partitionValues.isEmpty()) {
            throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "No partitions provided");
        }

        if (partitionColumns.isEmpty()) {
            throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Cannot specify partition values for an unpartitioned table");
        }

        partitionValues.forEach(value -> {
            if (value.size() != partitionColumns.size()) {
                throw new PrestoException(
                        INVALID_PROCEDURE_ARGUMENT,
                        format("Partition values %s don't match the number of partition columns (%s)", value, partitionColumns.size()));
            }
        });
    }
}
