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
package io.prestosql.plugin.hive.metastore.alluxio;

import alluxio.client.table.TableMasterClient;
import alluxio.exception.status.AlluxioStatusException;
import alluxio.exception.status.NotFoundException;
import alluxio.grpc.table.ColumnStatisticsInfo;
import alluxio.grpc.table.Constraint;
import alluxio.grpc.table.TableInfo;
import alluxio.grpc.table.layout.hive.PartitionInfo;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.prestosql.plugin.hive.HiveBasicStatistics;
import io.prestosql.plugin.hive.HiveType;
import io.prestosql.plugin.hive.PartitionStatistics;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.Column;
import io.prestosql.plugin.hive.metastore.Database;
import io.prestosql.plugin.hive.metastore.HiveColumnStatistics;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.HivePrincipal;
import io.prestosql.plugin.hive.metastore.HivePrivilegeInfo;
import io.prestosql.plugin.hive.metastore.Partition;
import io.prestosql.plugin.hive.metastore.PartitionWithStatistics;
import io.prestosql.plugin.hive.metastore.PrincipalPrivileges;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.security.RoleGrant;
import io.prestosql.spi.statistics.ColumnStatisticType;
import io.prestosql.spi.type.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_METASTORE_ERROR;
import static io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreUtil.getHiveBasicStatistics;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.common.FileUtils.makePartName;

/**
 * Implementation of the {@link HiveMetastore} interface through Alluxio.
 */
public class AlluxioHiveMetastore
        implements HiveMetastore
{
    private TableMasterClient client;

    @Inject
    public AlluxioHiveMetastore(TableMasterClient client)
    {
        this.client = requireNonNull(client);
    }

    @Override
    public Optional<Database> getDatabase(String databaseName)
    {
        try {
            return Optional.of(ProtoUtils.fromProto(client.getDatabase(databaseName)));
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public List<String> getAllDatabases()
    {
        try {
            return client.getAllDatabases();
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public Optional<Table> getTable(HiveIdentity identity, String databaseName, String tableName)
    {
        try {
            return Optional.of(ProtoUtils.fromProto(client.getTable(databaseName, tableName)));
        }
        catch (NotFoundException e) {
            return Optional.empty();
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public Set<ColumnStatisticType> getSupportedColumnStatistics(Type type)
    {
        return ThriftMetastoreUtil.getSupportedColumnStatistics(type);
    }

    private Map<String, HiveColumnStatistics> groupStatisticsByColumn(List<ColumnStatisticsInfo> statistics, OptionalLong rowCount)
    {
        return statistics.stream()
                .collect(toImmutableMap(ColumnStatisticsInfo::getColName, statisticsObj -> ProtoUtils.fromProto(statisticsObj.getData(), rowCount)));
    }

    @Override
    public PartitionStatistics getTableStatistics(HiveIdentity identity, Table table)
    {
        try {
            HiveBasicStatistics basicStats = ThriftMetastoreUtil.getHiveBasicStatistics(table.getParameters());
            List<Column> columns = new ArrayList<>(table.getPartitionColumns());
            columns.addAll(table.getDataColumns());
            List<String> columnNames = columns.stream().map(Column::getName).collect(Collectors.toList());
            List<ColumnStatisticsInfo> colStatsList = client.getTableColumnStatistics(table.getDatabaseName(), table.getTableName(), columnNames);
            return new PartitionStatistics(basicStats, groupStatisticsByColumn(colStatsList, basicStats.getRowCount()));
        }
        catch (Exception e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public Map<String, PartitionStatistics> getPartitionStatistics(HiveIdentity identity, Table table, List<Partition> partitions)
    {
        try {
            List<String> dataColumns = table.getDataColumns().stream()
                    .map(Column::getName)
                    .collect(toImmutableList());
            List<String> partitionColumns = table.getPartitionColumns().stream()
                    .map(Column::getName)
                    .collect(toImmutableList());

            Map<String, HiveBasicStatistics> partitionBasicStatistics = partitions.stream()
                    .collect(toImmutableMap(
                            partition -> makePartName(partitionColumns, partition.getValues()),
                            partition -> getHiveBasicStatistics(partition.getParameters())));
            Map<String, OptionalLong> partitionRowCounts = partitionBasicStatistics.entrySet().stream()
                    .collect(toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().getRowCount()));

            Map<String, List<ColumnStatisticsInfo>> colStatsMap = client.getPartitionColumnStatistics(table.getDatabaseName(), table.getTableName(),
                    partitionBasicStatistics.keySet().stream().collect(toImmutableList()), dataColumns);
            Map<String, Map<String, HiveColumnStatistics>> partitionColumnStatistics = colStatsMap.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .collect(toImmutableMap(
                            Map.Entry::getKey,
                            entry -> groupStatisticsByColumn(entry.getValue(), partitionRowCounts.getOrDefault(entry.getKey(), OptionalLong.empty()))));
            ImmutableMap.Builder<String, PartitionStatistics> result = ImmutableMap.builder();
            for (String partitionName : partitionBasicStatistics.keySet()) {
                HiveBasicStatistics basicStatistics = partitionBasicStatistics.get(partitionName);
                Map<String, HiveColumnStatistics> columnStatistics = partitionColumnStatistics.getOrDefault(partitionName, ImmutableMap.of());
                result.put(partitionName, new PartitionStatistics(basicStatistics, columnStatistics));
            }
            return result.build();
        }
        catch (Exception e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public void updateTableStatistics(
            HiveIdentity identity,
            String databaseName,
            String tableName,
            Function<PartitionStatistics, PartitionStatistics> update)
    {
        throw new PrestoException(NOT_SUPPORTED, "updateTableStatistics");
    }

    @Override
    public void updatePartitionStatistics(
            HiveIdentity identity,
            Table table,
            String partitionName,
            Function<PartitionStatistics, PartitionStatistics> update)
    {
        throw new PrestoException(NOT_SUPPORTED, "updatePartitionStatistics");
    }

    @Override
    public List<String> getAllTables(String databaseName)
    {
        try {
            return client.getAllTables(databaseName);
        }
        catch (NotFoundException e) {
            return new ArrayList<>(0);
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public List<String> getTablesWithParameter(
            String databaseName,
            String parameterKey,
            String parameterValue)
    {
        try {
            return client.getAllTables(databaseName).stream()
                    .filter(tableName -> {
                        // TODO Is there a way to do a bulk RPC?
                        try {
                            TableInfo table = client.getTable(databaseName, tableName);
                            if (table == null) {
                                return false;
                            }
                            String value = table.getParametersMap().get(parameterKey);
                            return value != null && value.equals(parameterValue);
                        }
                        catch (AlluxioStatusException e) {
                            throw new PrestoException(HIVE_METASTORE_ERROR, "Failed to get info for table: " + tableName, e);
                        }
                    })
                    .collect(Collectors.toList());
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public List<String> getAllViews(String databaseName)
    {
        // TODO: Add views on the server side
        return Collections.emptyList();
    }

    @Override
    public void createDatabase(HiveIdentity identity, Database database)
    {
        throw new PrestoException(NOT_SUPPORTED, "createDatabase");
    }

    @Override
    public void dropDatabase(HiveIdentity identity, String databaseName)
    {
        throw new PrestoException(NOT_SUPPORTED, "dropDatabase");
    }

    @Override
    public void renameDatabase(HiveIdentity identity, String databaseName, String newDatabaseName)
    {
        throw new PrestoException(NOT_SUPPORTED, "renameDatabase");
    }

    @Override
    public void setDatabaseOwner(HiveIdentity identity, String databaseName, HivePrincipal principal)
    {
        throw new PrestoException(NOT_SUPPORTED, "setDatabaseOwner");
    }

    @Override
    public void createTable(HiveIdentity identity, Table table, PrincipalPrivileges principalPrivileges)
    {
        throw new PrestoException(NOT_SUPPORTED, "createTable");
    }

    @Override
    public void dropTable(HiveIdentity identity, String databaseName, String tableName, boolean deleteData)
    {
        throw new PrestoException(NOT_SUPPORTED, "dropTable");
    }

    @Override
    public void replaceTable(HiveIdentity identity, String databaseName, String tableName, Table newTable,
            PrincipalPrivileges principalPrivileges)
    {
        throw new PrestoException(NOT_SUPPORTED, "replaceTable");
    }

    @Override
    public void renameTable(HiveIdentity identity, String databaseName, String tableName, String newDatabaseName,
            String newTableName)
    {
        throw new PrestoException(NOT_SUPPORTED, "renameTable");
    }

    @Override
    public void commentTable(HiveIdentity identity, String databaseName, String tableName, Optional<String> comment)
    {
        throw new PrestoException(NOT_SUPPORTED, "commentTable");
    }

    @Override
    public void addColumn(HiveIdentity identity, String databaseName, String tableName, String columnName,
            HiveType columnType, String columnComment)
    {
        throw new PrestoException(NOT_SUPPORTED, "addColumn");
    }

    @Override
    public void renameColumn(HiveIdentity identity, String databaseName, String tableName, String oldColumnName,
            String newColumnName)
    {
        throw new PrestoException(NOT_SUPPORTED, "renameColumn");
    }

    @Override
    public void dropColumn(HiveIdentity identity, String databaseName, String tableName, String columnName)
    {
        throw new PrestoException(NOT_SUPPORTED, "dropColumn");
    }

    @Override
    public Optional<Partition> getPartition(HiveIdentity identity, Table table, List<String> partitionValues)
    {
        throw new PrestoException(NOT_SUPPORTED, "getPartition");
    }

    @Override
    public Optional<List<String>> getPartitionNames(HiveIdentity identity, String databaseName, String tableName)
    {
        throw new PrestoException(NOT_SUPPORTED, "getPartitionNames");
    }

    /**
     * return a list of partition names by which the values of each partition is at least
     * contained which the {@code parts} argument
     *
     * @param databaseName the name of the database
     * @param tableName    the name of the table
     * @param parts        list of values which returned partitions should contain
     * @return optionally, a list of strings where each entry is in the form of {key}={value}
     */
    @Override
    public Optional<List<String>> getPartitionNamesByParts(HiveIdentity identity, String databaseName, String tableName, List<String> parts)
    {
        try {
            List<PartitionInfo> partitionInfos = ProtoUtils.toPartitionInfoList(
                    client.readTable(databaseName, tableName, Constraint.getDefaultInstance()));
            // TODO also check for database name equality
            partitionInfos = partitionInfos.stream()
                    .filter(p -> p.getTableName().equals(tableName))
                    // Filter out any partitions which have values that don't match
                    .filter(partition -> {
                        List<String> values = partition.getValuesList();
                        if (values.size() != parts.size()) {
                            return false;
                        }
                        for (int i = 0; i < values.size(); i++) {
                            String constraintPart = parts.get(i);
                            if (!constraintPart.isEmpty() && !values.get(i).equals(constraintPart)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            List<String> partitionNames = partitionInfos.stream()
                    .map(PartitionInfo::getPartitionName)
                    .collect(Collectors.toList());
            return Optional.of(partitionNames);
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public Map<String, Optional<Partition>> getPartitionsByNames(HiveIdentity identity, Table table, List<String> partitionNames)
    {
        if (partitionNames.isEmpty()) {
            return Collections.emptyMap();
        }
        String databaseName = table.getDatabaseName();
        String tableName = table.getTableName();

        try {
            // Get all partitions
            List<PartitionInfo> partitionInfos = ProtoUtils.toPartitionInfoList(
                    client.readTable(databaseName, tableName, Constraint.getDefaultInstance()));
            // Check that table name is correct
            // TODO also check for database name equality
            partitionInfos = partitionInfos.stream()
                    .filter(partition -> partition.getTableName().equals(tableName))
                    .collect(Collectors.toList());
            Map<String, Optional<Partition>> result = partitionInfos.stream()
                    .filter(partitionName -> partitionNames.stream()
                            .anyMatch(partitionName.getPartitionName()::equals))
                    .collect(Collectors.toMap(
                            PartitionInfo::getPartitionName,
                            partitionInfo -> Optional.of(ProtoUtils.fromProto(partitionInfo))));
            return Collections.unmodifiableMap(result);
        }
        catch (AlluxioStatusException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public void addPartitions(HiveIdentity identity, String databaseName, String tableName,
            List<PartitionWithStatistics> partitions)
    {
        throw new PrestoException(NOT_SUPPORTED, "addPartitions");
    }

    @Override
    public void dropPartition(HiveIdentity identity, String databaseName, String tableName, List<String> parts,
            boolean deleteData)
    {
        throw new PrestoException(NOT_SUPPORTED, "dropPartition");
    }

    @Override
    public void alterPartition(HiveIdentity identity, String databaseName, String tableName,
            PartitionWithStatistics partition)
    {
        throw new PrestoException(NOT_SUPPORTED, "alterPartition");
    }

    @Override
    public void createRole(String role, String grantor)
    {
        throw new PrestoException(NOT_SUPPORTED, "createRole");
    }

    @Override
    public void dropRole(String role)
    {
        throw new PrestoException(NOT_SUPPORTED, "dropRole");
    }

    @Override
    public Set<String> listRoles()
    {
        throw new PrestoException(NOT_SUPPORTED, "listRoles");
    }

    @Override
    public void grantRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean withAdminOption,
            HivePrincipal grantor)
    {
        throw new PrestoException(NOT_SUPPORTED, "grantRoles");
    }

    @Override
    public void revokeRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOptionFor,
            HivePrincipal grantor)
    {
        throw new PrestoException(NOT_SUPPORTED, "revokeRoles");
    }

    @Override
    public Set<RoleGrant> listRoleGrants(HivePrincipal principal)
    {
        throw new PrestoException(NOT_SUPPORTED, "listRoleGrants");
    }

    @Override
    public void grantTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee,
            Set<HivePrivilegeInfo> privileges)
    {
        throw new PrestoException(NOT_SUPPORTED, "grantTablePrivileges");
    }

    @Override
    public void revokeTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee,
            Set<HivePrivilegeInfo> privileges)
    {
        throw new PrestoException(NOT_SUPPORTED, "revokeTablePrivileges");
    }

    @Override
    public Set<HivePrivilegeInfo> listTablePrivileges(String databaseName, String tableName, String tableOwner, Optional<HivePrincipal> principal)
    {
        throw new PrestoException(NOT_SUPPORTED, "listTablePrivileges");
    }

    @Override
    public boolean isImpersonationEnabled()
    {
        return false;
    }
}
