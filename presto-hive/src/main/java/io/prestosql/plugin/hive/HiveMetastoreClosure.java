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
package io.prestosql.plugin.hive;

import com.google.common.collect.ImmutableList;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.Database;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.HivePrincipal;
import io.prestosql.plugin.hive.metastore.HivePrivilegeInfo;
import io.prestosql.plugin.hive.metastore.Partition;
import io.prestosql.plugin.hive.metastore.PartitionWithStatistics;
import io.prestosql.plugin.hive.metastore.PrincipalPrivileges;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.security.RoleGrant;
import io.prestosql.spi.statistics.ColumnStatisticType;
import io.prestosql.spi.type.Type;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Maps.immutableEntry;
import static io.prestosql.plugin.hive.HivePartitionManager.extractPartitionValues;
import static java.util.Objects.requireNonNull;

public class HiveMetastoreClosure
{
    private final HiveMetastore delegate;

    public HiveMetastoreClosure(HiveMetastore delegate)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
    }

    public Optional<Database> getDatabase(String databaseName)
    {
        return delegate.getDatabase(databaseName);
    }

    public List<String> getAllDatabases()
    {
        return delegate.getAllDatabases();
    }

    private Table getExistingTable(HiveIdentity identity, String databaseName, String tableName)
    {
        return delegate.getTable(identity, databaseName, tableName)
                .orElseThrow(() -> new TableNotFoundException(new SchemaTableName(databaseName, tableName)));
    }

    public Optional<Table> getTable(HiveIdentity identity, String databaseName, String tableName)
    {
        return delegate.getTable(identity, databaseName, tableName);
    }

    public Set<ColumnStatisticType> getSupportedColumnStatistics(Type type)
    {
        return delegate.getSupportedColumnStatistics(type);
    }

    public PartitionStatistics getTableStatistics(HiveIdentity identity, String databaseName, String tableName)
    {
        return delegate.getTableStatistics(identity, getExistingTable(identity, databaseName, tableName));
    }

    public Map<String, PartitionStatistics> getPartitionStatistics(HiveIdentity identity, String databaseName, String tableName, Set<String> partitionNames)
    {
        Table table = getExistingTable(identity, databaseName, tableName);
        List<Partition> partitions = getExistingPartitionsByNames(identity, table, ImmutableList.copyOf(partitionNames));
        return delegate.getPartitionStatistics(identity, table, partitions);
    }

    public void updateTableStatistics(HiveIdentity identity, String databaseName, String tableName, Function<PartitionStatistics, PartitionStatistics> update)
    {
        delegate.updateTableStatistics(identity, databaseName, tableName, update);
    }

    public void updatePartitionStatistics(HiveIdentity identity, String databaseName, String tableName, String partitionName, Function<PartitionStatistics, PartitionStatistics> update)
    {
        Table table = getExistingTable(identity, databaseName, tableName);
        delegate.updatePartitionStatistics(identity, table, partitionName, update);
    }

    public List<String> getAllTables(String databaseName)
    {
        return delegate.getAllTables(databaseName);
    }

    public List<String> getTablesWithParameter(String databaseName, String parameterKey, String parameterValue)
    {
        return delegate.getTablesWithParameter(databaseName, parameterKey, parameterValue);
    }

    public List<String> getAllViews(String databaseName)
    {
        return delegate.getAllViews(databaseName);
    }

    public void createDatabase(HiveIdentity identity, Database database)
    {
        delegate.createDatabase(identity, database);
    }

    public void dropDatabase(HiveIdentity identity, String databaseName)
    {
        delegate.dropDatabase(identity, databaseName);
    }

    public void renameDatabase(HiveIdentity identity, String databaseName, String newDatabaseName)
    {
        delegate.renameDatabase(identity, databaseName, newDatabaseName);
    }

    public void setDatabaseOwner(HiveIdentity identity, String databaseName, HivePrincipal principal)
    {
        delegate.setDatabaseOwner(identity, databaseName, principal);
    }

    public void createTable(HiveIdentity identity, Table table, PrincipalPrivileges principalPrivileges)
    {
        delegate.createTable(identity, table, principalPrivileges);
    }

    public void dropTable(HiveIdentity identity, String databaseName, String tableName, boolean deleteData)
    {
        delegate.dropTable(identity, databaseName, tableName, deleteData);
    }

    public void replaceTable(HiveIdentity identity, String databaseName, String tableName, Table newTable, PrincipalPrivileges principalPrivileges)
    {
        delegate.replaceTable(identity, databaseName, tableName, newTable, principalPrivileges);
    }

    public void renameTable(HiveIdentity identity, String databaseName, String tableName, String newDatabaseName, String newTableName)
    {
        delegate.renameTable(identity, databaseName, tableName, newDatabaseName, newTableName);
    }

    public void commentTable(HiveIdentity identity, String databaseName, String tableName, Optional<String> comment)
    {
        delegate.commentTable(identity, databaseName, tableName, comment);
    }

    public void addColumn(HiveIdentity identity, String databaseName, String tableName, String columnName, HiveType columnType, String columnComment)
    {
        delegate.addColumn(identity, databaseName, tableName, columnName, columnType, columnComment);
    }

    public void renameColumn(HiveIdentity identity, String databaseName, String tableName, String oldColumnName, String newColumnName)
    {
        delegate.renameColumn(identity, databaseName, tableName, oldColumnName, newColumnName);
    }

    public void dropColumn(HiveIdentity identity, String databaseName, String tableName, String columnName)
    {
        delegate.dropColumn(identity, databaseName, tableName, columnName);
    }

    public Optional<Partition> getPartition(HiveIdentity identity, String databaseName, String tableName, List<String> partitionValues)
    {
        return delegate.getTable(identity, databaseName, tableName)
                .flatMap(table -> delegate.getPartition(identity, table, partitionValues));
    }

    public Optional<List<String>> getPartitionNames(HiveIdentity identity, String databaseName, String tableName)
    {
        return delegate.getPartitionNames(identity, databaseName, tableName);
    }

    public Optional<List<String>> getPartitionNamesByParts(HiveIdentity identity, String databaseName, String tableName, List<String> parts)
    {
        return delegate.getPartitionNamesByParts(identity, databaseName, tableName, parts);
    }

    private List<Partition> getExistingPartitionsByNames(HiveIdentity identity, Table table, List<String> partitionNames)
    {
        Map<String, Partition> partitions = delegate.getPartitionsByNames(identity, table, partitionNames).entrySet().stream()
                .map(entry -> immutableEntry(entry.getKey(), entry.getValue().orElseThrow(() ->
                        new PartitionNotFoundException(table.getSchemaTableName(), extractPartitionValues(entry.getKey())))))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

        return partitionNames.stream()
                .map(partitions::get)
                .collect(toImmutableList());
    }

    public Map<String, Optional<Partition>> getPartitionsByNames(HiveIdentity identity, String databaseName, String tableName, List<String> partitionNames)
    {
        return delegate.getTable(identity, databaseName, tableName)
                .map(table -> delegate.getPartitionsByNames(identity, table, partitionNames))
                .orElseGet(() -> partitionNames.stream()
                        .collect(toImmutableMap(name -> name, name -> Optional.empty())));
    }

    public void addPartitions(HiveIdentity identity, String databaseName, String tableName, List<PartitionWithStatistics> partitions)
    {
        delegate.addPartitions(identity, databaseName, tableName, partitions);
    }

    public void dropPartition(HiveIdentity identity, String databaseName, String tableName, List<String> parts, boolean deleteData)
    {
        delegate.dropPartition(identity, databaseName, tableName, parts, deleteData);
    }

    public void alterPartition(HiveIdentity identity, String databaseName, String tableName, PartitionWithStatistics partition)
    {
        delegate.alterPartition(identity, databaseName, tableName, partition);
    }

    public void createRole(String role, String grantor)
    {
        delegate.createRole(role, grantor);
    }

    public void dropRole(String role)
    {
        delegate.dropRole(role);
    }

    public Set<String> listRoles()
    {
        return delegate.listRoles();
    }

    public void grantRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        delegate.grantRoles(roles, grantees, adminOption, grantor);
    }

    public void revokeRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        delegate.revokeRoles(roles, grantees, adminOption, grantor);
    }

    public Set<RoleGrant> listRoleGrants(HivePrincipal principal)
    {
        return delegate.listRoleGrants(principal);
    }

    public void grantTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, Set<HivePrivilegeInfo> privileges)
    {
        delegate.grantTablePrivileges(databaseName, tableName, tableOwner, grantee, privileges);
    }

    public void revokeTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, Set<HivePrivilegeInfo> privileges)
    {
        delegate.revokeTablePrivileges(databaseName, tableName, tableOwner, grantee, privileges);
    }

    public Set<HivePrivilegeInfo> listTablePrivileges(String databaseName, String tableName, String tableOwner, Optional<HivePrincipal> principal)
    {
        return delegate.listTablePrivileges(databaseName, tableName, tableOwner, principal);
    }

    public boolean isImpersonationEnabled()
    {
        return delegate.isImpersonationEnabled();
    }

    public long openTransaction(HiveIdentity identity)
    {
        return delegate.openTransaction(identity);
    }

    public void commitTransaction(HiveIdentity identity, long transactionId)
    {
        delegate.commitTransaction(identity, transactionId);
    }

    public void sendTransactionHeartbeat(HiveIdentity identity, long transactionId)
    {
        delegate.sendTransactionHeartbeat(identity, transactionId);
    }

    public void acquireSharedReadLock(HiveIdentity identity, String queryId, long transactionId, List<SchemaTableName> fullTables, List<HivePartition> partitions)
    {
        delegate.acquireSharedReadLock(identity, queryId, transactionId, fullTables, partitions);
    }

    public String getValidWriteIds(HiveIdentity identity, List<SchemaTableName> tables, long currentTransactionId)
    {
        return delegate.getValidWriteIds(identity, tables, currentTransactionId);
    }

    public Optional<String> getConfigValue(String name)
    {
        return delegate.getConfigValue(name);
    }
}
