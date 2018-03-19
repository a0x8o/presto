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
package com.facebook.presto.hive.metastore;

import com.facebook.presto.hive.ForCachingHiveMetastore;
import com.facebook.presto.hive.HiveClientConfig;
import com.facebook.presto.hive.HiveType;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.airlift.units.Duration;
import org.weakref.jmx.Managed;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.facebook.presto.hive.HiveUtil.toPartitionValues;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.cache.CacheLoader.asyncReloading;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Streams.stream;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Hive Metastore Cache
 */
@ThreadSafe
public class CachingHiveMetastore
        implements ExtendedHiveMetastore
{
    protected final ExtendedHiveMetastore delegate;
    private final LoadingCache<String, Optional<Database>> databaseCache;
    private final LoadingCache<String, List<String>> databaseNamesCache;
    private final LoadingCache<HiveTableName, Optional<Table>> tableCache;
    private final LoadingCache<String, Optional<List<String>>> tableNamesCache;
    private final LoadingCache<TableColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> tableColumnStatisticsCache;
    private final LoadingCache<PartitionColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> partitionColumnStatisticsCache;
    private final LoadingCache<String, Optional<List<String>>> viewNamesCache;
    private final LoadingCache<HivePartitionName, Optional<Partition>> partitionCache;
    private final LoadingCache<PartitionFilter, Optional<List<String>>> partitionFilterCache;
    private final LoadingCache<HiveTableName, Optional<List<String>>> partitionNamesCache;
    private final LoadingCache<String, Set<String>> userRolesCache;
    private final LoadingCache<UserTableKey, Set<HivePrivilegeInfo>> userTablePrivileges;

    @Inject
    public CachingHiveMetastore(@ForCachingHiveMetastore ExtendedHiveMetastore delegate, @ForCachingHiveMetastore ExecutorService executor, HiveClientConfig hiveClientConfig)
    {
        this(
                delegate,
                executor,
                hiveClientConfig.getMetastoreCacheTtl(),
                hiveClientConfig.getMetastoreRefreshInterval(),
                hiveClientConfig.getMetastoreCacheMaximumSize());
    }

    public CachingHiveMetastore(ExtendedHiveMetastore delegate, ExecutorService executor, Duration cacheTtl, Duration refreshInterval, long maximumSize)
    {
        this(
                delegate,
                executor,
                OptionalLong.of(cacheTtl.toMillis()),
                refreshInterval.toMillis() >= cacheTtl.toMillis() ? OptionalLong.empty() : OptionalLong.of(refreshInterval.toMillis()),
                maximumSize);
    }

    public static CachingHiveMetastore memoizeMetastore(ExtendedHiveMetastore delegate, long maximumSize)
    {
        return new CachingHiveMetastore(
                delegate,
                newDirectExecutorService(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                maximumSize);
    }

    private CachingHiveMetastore(ExtendedHiveMetastore delegate, ExecutorService executor, OptionalLong expiresAfterWriteMillis, OptionalLong refreshMills, long maximumSize)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
        requireNonNull(executor, "executor is null");

        databaseNamesCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadAllDatabases), executor));

        databaseCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadDatabase), executor));

        tableNamesCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadAllTables), executor));

        tableColumnStatisticsCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(new CacheLoader<TableColumnStatisticsCacheKey, Optional<HiveColumnStatistics>>()
                {
                    @Override
                    public Optional<HiveColumnStatistics> load(TableColumnStatisticsCacheKey key)
                    {
                        return loadAll(ImmutableList.of(key)).get(key);
                    }

                    @Override
                    public Map<TableColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> loadAll(Iterable<? extends TableColumnStatisticsCacheKey> keys)
                    {
                        return loadColumnStatistics(keys);
                    }
                }, executor));

        partitionColumnStatisticsCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(new CacheLoader<PartitionColumnStatisticsCacheKey, Optional<HiveColumnStatistics>>()
                {
                    @Override
                    public Optional<HiveColumnStatistics> load(PartitionColumnStatisticsCacheKey key)
                    {
                        return loadAll(ImmutableList.of(key)).get(key);
                    }

                    @Override
                    public Map<PartitionColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> loadAll(Iterable<? extends PartitionColumnStatisticsCacheKey> keys)
                    {
                        return loadPartitionColumnStatistics(keys);
                    }
                }, executor));

        tableCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadTable), executor));

        viewNamesCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadAllViews), executor));

        partitionNamesCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadPartitionNames), executor));

        partitionFilterCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadPartitionNamesByParts), executor));

        partitionCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(new CacheLoader<HivePartitionName, Optional<Partition>>()
                {
                    @Override
                    public Optional<Partition> load(HivePartitionName partitionName)
                    {
                        return loadPartitionByName(partitionName);
                    }

                    @Override
                    public Map<HivePartitionName, Optional<Partition>> loadAll(Iterable<? extends HivePartitionName> partitionNames)
                    {
                        return loadPartitionsByNames(partitionNames);
                    }
                }, executor));

        userRolesCache = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(this::loadRoles), executor));

        userTablePrivileges = newCacheBuilder(expiresAfterWriteMillis, refreshMills, maximumSize)
                .build(asyncReloading(CacheLoader.from(key -> loadTablePrivileges(key.getUser(), key.getDatabase(), key.getTable())), executor));
    }

    @Managed
    public void flushCache()
    {
        databaseNamesCache.invalidateAll();
        tableNamesCache.invalidateAll();
        viewNamesCache.invalidateAll();
        partitionNamesCache.invalidateAll();
        databaseCache.invalidateAll();
        tableCache.invalidateAll();
        partitionCache.invalidateAll();
        partitionFilterCache.invalidateAll();
        userTablePrivileges.invalidateAll();
    }

    private static <K, V> V get(LoadingCache<K, V> cache, K key)
    {
        try {
            return cache.get(key);
        }
        catch (ExecutionException | UncheckedExecutionException | ExecutionError e) {
            throw Throwables.propagate(e.getCause());
        }
    }

    private static <K, V> Map<K, V> getAll(LoadingCache<K, V> cache, Iterable<K> keys)
    {
        try {
            return cache.getAll(keys);
        }
        catch (ExecutionException | UncheckedExecutionException | ExecutionError e) {
            throw Throwables.propagate(e.getCause());
        }
    }

    @Override
    public Optional<Database> getDatabase(String databaseName)
    {
        return get(databaseCache, databaseName);
    }

    private Optional<Database> loadDatabase(String databaseName)
    {
        return delegate.getDatabase(databaseName);
    }

    @Override
    public List<String> getAllDatabases()
    {
        return get(databaseNamesCache, "");
    }

    private List<String> loadAllDatabases()
    {
        return delegate.getAllDatabases();
    }

    @Override
    public Optional<Table> getTable(String databaseName, String tableName)
    {
        return get(tableCache, HiveTableName.table(databaseName, tableName));
    }

    private Optional<Table> loadTable(HiveTableName hiveTableName)
    {
        return delegate.getTable(hiveTableName.getDatabaseName(), hiveTableName.getTableName());
    }

    @Override
    public Optional<Map<String, HiveColumnStatistics>> getTableColumnStatistics(String databaseName, String tableName, Set<String> columnNames)
    {
        Map<TableColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> cacheValues =
                getAll(tableColumnStatisticsCache, columnNames.stream()
                        .map(columnName -> new TableColumnStatisticsCacheKey(databaseName, tableName, columnName))
                        .collect(toList()));

        return Optional.of(
                ImmutableMap.copyOf(
                        cacheValues.entrySet().stream()
                                .filter(entry -> entry.getValue().isPresent())
                                .collect(toMap(
                                        entry -> entry.getKey().getColumnName(),
                                        entry -> entry.getValue().get()))));
    }

    private Map<TableColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> loadColumnStatistics(Iterable<? extends TableColumnStatisticsCacheKey> keys)
    {
        if (Iterables.isEmpty(keys)) {
            return ImmutableMap.of();
        }

        HiveTableName hiveTableName = stream(keys).findFirst().get().getHiveTableName();
        checkArgument(stream(keys).allMatch(key -> key.getHiveTableName().equals(hiveTableName)), "all keys must relate to same hive table");

        Set<String> columnNames = stream(keys).map(TableColumnStatisticsCacheKey::getColumnName).collect(Collectors.toSet());

        Optional<Map<String, HiveColumnStatistics>> columnStatistics = delegate.getTableColumnStatistics(hiveTableName.getDatabaseName(), hiveTableName.getTableName(), columnNames);

        ImmutableMap.Builder<TableColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> resultMap = ImmutableMap.builder();
        for (TableColumnStatisticsCacheKey key : keys) {
            if (!columnStatistics.isPresent() || !columnStatistics.get().containsKey(key.getColumnName())) {
                resultMap.put(key, Optional.empty());
            }
            else {
                resultMap.put(key, Optional.of(columnStatistics.get().get(key.getColumnName())));
            }
        }
        return resultMap.build();
    }

    @Override
    public Optional<Map<String, Map<String, HiveColumnStatistics>>> getPartitionColumnStatistics(String databaseName, String tableName, Set<String> partitionNames, Set<String> columnNames)
    {
        List<PartitionColumnStatisticsCacheKey> cacheKeys = partitionNames.stream()
                .flatMap(
                        partitionName -> columnNames.stream().map(
                                columnName -> new PartitionColumnStatisticsCacheKey(databaseName, tableName, partitionName, columnName)))
                .collect(toList());
        Map<PartitionColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> cacheValues = getAll(partitionColumnStatisticsCache, cacheKeys);

        ImmutableMap.Builder<String, Map<String, HiveColumnStatistics>> partitionsMap = ImmutableMap.builder();
        for (String partitionName : partitionNames) {
            ImmutableMap.Builder<String, HiveColumnStatistics> columnsMap = ImmutableMap.builder();
            for (String columnName : columnNames) {
                Optional<HiveColumnStatistics> cacheValue = cacheValues.get(new PartitionColumnStatisticsCacheKey(databaseName, tableName, partitionName, columnName));
                if (cacheValue.isPresent()) {
                    columnsMap.put(columnName, cacheValue.get());
                }
            }
            partitionsMap.put(partitionName, columnsMap.build());
        }
        return Optional.of(partitionsMap.build());
    }

    private Map<PartitionColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> loadPartitionColumnStatistics(Iterable<? extends PartitionColumnStatisticsCacheKey> keys)
    {
        if (Iterables.isEmpty(keys)) {
            return ImmutableMap.of();
        }
        PartitionColumnStatisticsCacheKey firstKey = Iterables.getFirst(keys, null);
        HiveTableName hiveTableName = firstKey.getHivePartitionName().getHiveTableName();
        checkArgument(stream(keys).allMatch(key -> key.getHivePartitionName().getHiveTableName().equals(hiveTableName)), "all keys must relate to same hive table");
        Set<String> partitionNames = stream(keys).map(key -> key.getHivePartitionName().getPartitionName()).collect(Collectors.toSet());
        Set<String> columnNames = stream(keys).map(PartitionColumnStatisticsCacheKey::getColumnName).collect(Collectors.toSet());

        Optional<Map<String, Map<String, HiveColumnStatistics>>> columnStatistics = delegate.getPartitionColumnStatistics(
                hiveTableName.getDatabaseName(),
                hiveTableName.getTableName(),
                partitionNames,
                columnNames);

        ImmutableMap.Builder<PartitionColumnStatisticsCacheKey, Optional<HiveColumnStatistics>> resultMap = ImmutableMap.builder();
        for (PartitionColumnStatisticsCacheKey key : keys) {
            if (columnStatistics.isPresent()
                    && columnStatistics.get().containsKey(key.getHivePartitionName().getPartitionName())
                    && columnStatistics.get().get(key.getHivePartitionName().getPartitionName()).containsKey(key.getColumnName())) {
                resultMap.put(key, Optional.of(columnStatistics.get().get(key.getHivePartitionName().getPartitionName()).get(key.getColumnName())));
            }
            else {
                resultMap.put(key, Optional.empty());
            }
        }
        return resultMap.build();
    }

    @Override
    public Optional<List<String>> getAllTables(String databaseName)
    {
        return get(tableNamesCache, databaseName);
    }

    private Optional<List<String>> loadAllTables(String databaseName)
    {
        return delegate.getAllTables(databaseName);
    }

    @Override
    public Optional<List<String>> getAllViews(String databaseName)
    {
        return get(viewNamesCache, databaseName);
    }

    private Optional<List<String>> loadAllViews(String databaseName)
    {
        return delegate.getAllViews(databaseName);
    }

    @Override
    public void createDatabase(Database database)
    {
        try {
            delegate.createDatabase(database);
        }
        finally {
            invalidateDatabase(database.getDatabaseName());
        }
    }

    @Override
    public void dropDatabase(String databaseName)
    {
        try {
            delegate.dropDatabase(databaseName);
        }
        finally {
            invalidateDatabase(databaseName);
        }
    }

    @Override
    public void renameDatabase(String databaseName, String newDatabaseName)
    {
        try {
            delegate.renameDatabase(databaseName, newDatabaseName);
        }
        finally {
            invalidateDatabase(databaseName);
            invalidateDatabase(newDatabaseName);
        }
    }

    protected void invalidateDatabase(String databaseName)
    {
        databaseCache.invalidate(databaseName);
        databaseNamesCache.invalidateAll();
    }

    @Override
    public void createTable(Table table, PrincipalPrivileges principalPrivileges)
    {
        try {
            delegate.createTable(table, principalPrivileges);
        }
        finally {
            invalidateTable(table.getDatabaseName(), table.getTableName());
        }
    }

    @Override
    public void dropTable(String databaseName, String tableName, boolean deleteData)
    {
        try {
            delegate.dropTable(databaseName, tableName, deleteData);
        }
        finally {
            invalidateTable(databaseName, tableName);
        }
    }

    @Override
    public void replaceTable(String databaseName, String tableName, Table newTable, PrincipalPrivileges principalPrivileges)
    {
        try {
            delegate.replaceTable(databaseName, tableName, newTable, principalPrivileges);
        }
        finally {
            invalidateTable(databaseName, tableName);
            invalidateTable(newTable.getDatabaseName(), newTable.getTableName());
        }
    }

    @Override
    public void renameTable(String databaseName, String tableName, String newDatabaseName, String newTableName)
    {
        try {
            delegate.renameTable(databaseName, tableName, newDatabaseName, newTableName);
        }
        finally {
            invalidateTable(databaseName, tableName);
            invalidateTable(newDatabaseName, newTableName);
        }
    }

    @Override
    public void addColumn(String databaseName, String tableName, String columnName, HiveType columnType, String columnComment)
    {
        try {
            delegate.addColumn(databaseName, tableName, columnName, columnType, columnComment);
        }
        finally {
            invalidateTable(databaseName, tableName);
        }
    }

    @Override
    public void renameColumn(String databaseName, String tableName, String oldColumnName, String newColumnName)
    {
        try {
            delegate.renameColumn(databaseName, tableName, oldColumnName, newColumnName);
        }
        finally {
            invalidateTable(databaseName, tableName);
        }
    }

    @Override
    public void dropColumn(String databaseName, String tableName, String columnName)
    {
        try {
            delegate.dropColumn(databaseName, tableName, columnName);
        }
        finally {
            invalidateTable(databaseName, tableName);
        }
    }

    protected void invalidateTable(String databaseName, String tableName)
    {
        tableCache.invalidate(new HiveTableName(databaseName, tableName));
        tableNamesCache.invalidate(databaseName);
        viewNamesCache.invalidate(databaseName);
        userTablePrivileges.asMap().keySet().stream()
                .filter(userTableKey -> userTableKey.matches(databaseName, tableName))
                .forEach(userTablePrivileges::invalidate);
        invalidatePartitionCache(databaseName, tableName);
    }

    @Override
    public Optional<Partition> getPartition(String databaseName, String tableName, List<String> partitionValues)
    {
        HivePartitionName name = HivePartitionName.partition(databaseName, tableName, partitionValues);
        return get(partitionCache, name);
    }

    @Override
    public Optional<List<String>> getPartitionNames(String databaseName, String tableName)
    {
        return get(partitionNamesCache, HiveTableName.table(databaseName, tableName));
    }

    private Optional<List<String>> loadPartitionNames(HiveTableName hiveTableName)
    {
        return delegate.getPartitionNames(hiveTableName.getDatabaseName(), hiveTableName.getTableName());
    }

    @Override
    public Optional<List<String>> getPartitionNamesByParts(String databaseName, String tableName, List<String> parts)
    {
        return get(partitionFilterCache, PartitionFilter.partitionFilter(databaseName, tableName, parts));
    }

    private Optional<List<String>> loadPartitionNamesByParts(PartitionFilter partitionFilter)
    {
        return delegate.getPartitionNamesByParts(
                partitionFilter.getHiveTableName().getDatabaseName(),
                partitionFilter.getHiveTableName().getTableName(),
                partitionFilter.getParts());
    }

    @Override
    public Map<String, Optional<Partition>> getPartitionsByNames(String databaseName, String tableName, List<String> partitionNames)
    {
        Iterable<HivePartitionName> names = transform(partitionNames, name -> HivePartitionName.partition(databaseName, tableName, name));

        Map<HivePartitionName, Optional<Partition>> all = getAll(partitionCache, names);
        ImmutableMap.Builder<String, Optional<Partition>> partitionsByName = ImmutableMap.builder();
        for (Entry<HivePartitionName, Optional<Partition>> entry : all.entrySet()) {
            partitionsByName.put(entry.getKey().getPartitionName(), entry.getValue());
        }
        return partitionsByName.build();
    }

    private Optional<Partition> loadPartitionByName(HivePartitionName partitionName)
    {
        return delegate.getPartition(
                partitionName.getHiveTableName().getDatabaseName(),
                partitionName.getHiveTableName().getTableName(),
                partitionName.getPartitionValues());
    }

    private Map<HivePartitionName, Optional<Partition>> loadPartitionsByNames(Iterable<? extends HivePartitionName> partitionNames)
    {
        requireNonNull(partitionNames, "partitionNames is null");
        checkArgument(!Iterables.isEmpty(partitionNames), "partitionNames is empty");

        HivePartitionName firstPartition = Iterables.get(partitionNames, 0);

        HiveTableName hiveTableName = firstPartition.getHiveTableName();
        String databaseName = hiveTableName.getDatabaseName();
        String tableName = hiveTableName.getTableName();

        List<String> partitionsToFetch = new ArrayList<>();
        for (HivePartitionName partitionName : partitionNames) {
            checkArgument(partitionName.getHiveTableName().equals(hiveTableName), "Expected table name %s but got %s", hiveTableName, partitionName.getHiveTableName());
            partitionsToFetch.add(partitionName.getPartitionName());
        }

        ImmutableMap.Builder<HivePartitionName, Optional<Partition>> partitions = ImmutableMap.builder();
        Map<String, Optional<Partition>> partitionsByNames = delegate.getPartitionsByNames(databaseName, tableName, partitionsToFetch);
        for (Entry<String, Optional<Partition>> entry : partitionsByNames.entrySet()) {
            partitions.put(HivePartitionName.partition(hiveTableName, entry.getKey()), entry.getValue());
        }
        return partitions.build();
    }

    @Override
    public void addPartitions(String databaseName, String tableName, List<Partition> partitions)
    {
        try {
            delegate.addPartitions(databaseName, tableName, partitions);
        }
        finally {
            // todo do we need to invalidate all partitions?
            invalidatePartitionCache(databaseName, tableName);
        }
    }

    @Override
    public void dropPartition(String databaseName, String tableName, List<String> parts, boolean deleteData)
    {
        try {
            delegate.dropPartition(databaseName, tableName, parts, deleteData);
        }
        finally {
            invalidatePartitionCache(databaseName, tableName);
        }
    }

    @Override
    public void alterPartition(String databaseName, String tableName, Partition partition)
    {
        try {
            delegate.alterPartition(databaseName, tableName, partition);
        }
        finally {
            invalidatePartitionCache(databaseName, tableName);
        }
    }

    private void invalidatePartitionCache(String databaseName, String tableName)
    {
        HiveTableName hiveTableName = HiveTableName.table(databaseName, tableName);
        partitionNamesCache.invalidate(hiveTableName);
        partitionCache.asMap().keySet().stream()
                .filter(partitionName -> partitionName.getHiveTableName().equals(hiveTableName))
                .forEach(partitionCache::invalidate);
        partitionFilterCache.asMap().keySet().stream()
                .filter(partitionFilter -> partitionFilter.getHiveTableName().equals(hiveTableName))
                .forEach(partitionFilterCache::invalidate);
    }

    @Override
    public Set<String> getRoles(String user)
    {
        return get(userRolesCache, user);
    }

    private Set<String> loadRoles(String user)
    {
        return delegate.getRoles(user);
    }

    @Override
    public Set<HivePrivilegeInfo> getDatabasePrivileges(String user, String databaseName)
    {
        return delegate.getDatabasePrivileges(user, databaseName);
    }

    @Override
    public Set<HivePrivilegeInfo> getTablePrivileges(String user, String databaseName, String tableName)
    {
        return get(userTablePrivileges, new UserTableKey(user, tableName, databaseName));
    }

    private Set<HivePrivilegeInfo> loadTablePrivileges(String user, String databaseName, String tableName)
    {
        return delegate.getTablePrivileges(user, databaseName, tableName);
    }

    @Override
    public void grantTablePrivileges(String databaseName, String tableName, String grantee, Set<HivePrivilegeInfo> privileges)
    {
        try {
            delegate.grantTablePrivileges(databaseName, tableName, grantee, privileges);
        }
        finally {
            userTablePrivileges.invalidate(new UserTableKey(grantee, tableName, databaseName));
        }
    }

    @Override
    public void revokeTablePrivileges(String databaseName, String tableName, String grantee, Set<HivePrivilegeInfo> privileges)
    {
        try {
            delegate.revokeTablePrivileges(databaseName, tableName, grantee, privileges);
        }
        finally {
            userTablePrivileges.invalidate(new UserTableKey(grantee, tableName, databaseName));
        }
    }

    private static CacheBuilder<Object, Object> newCacheBuilder(OptionalLong expiresAfterWriteMillis, OptionalLong refreshMillis, long maximumSize)
    {
        CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder();
        if (expiresAfterWriteMillis.isPresent()) {
            cacheBuilder = cacheBuilder.expireAfterWrite(expiresAfterWriteMillis.getAsLong(), MILLISECONDS);
        }
        if (refreshMillis.isPresent() && (!expiresAfterWriteMillis.isPresent() || expiresAfterWriteMillis.getAsLong() > refreshMillis.getAsLong())) {
            cacheBuilder = cacheBuilder.refreshAfterWrite(refreshMillis.getAsLong(), MILLISECONDS);
        }
        cacheBuilder = cacheBuilder.maximumSize(maximumSize);
        return cacheBuilder;
    }

    private static class HiveTableName
    {
        private final String databaseName;
        private final String tableName;

        private HiveTableName(String databaseName, String tableName)
        {
            this.databaseName = databaseName;
            this.tableName = tableName;
        }

        public static HiveTableName table(String databaseName, String tableName)
        {
            return new HiveTableName(databaseName, tableName);
        }

        public String getDatabaseName()
        {
            return databaseName;
        }

        public String getTableName()
        {
            return tableName;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("databaseName", databaseName)
                    .add("tableName", tableName)
                    .toString();
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

            HiveTableName other = (HiveTableName) o;
            return Objects.equals(databaseName, other.databaseName) &&
                    Objects.equals(tableName, other.tableName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(databaseName, tableName);
        }
    }

    private static class HivePartitionName
    {
        private final HiveTableName hiveTableName;
        private final List<String> partitionValues;
        private final String partitionName; // does not participate in hashCode/equals

        private HivePartitionName(HiveTableName hiveTableName, List<String> partitionValues, String partitionName)
        {
            this.hiveTableName = requireNonNull(hiveTableName, "hiveTableName is null");
            this.partitionValues = requireNonNull(partitionValues, "partitionValues is null");
            this.partitionName = partitionName;
        }

        public static HivePartitionName partition(HiveTableName hiveTableName, String partitionName)
        {
            return new HivePartitionName(hiveTableName, toPartitionValues(partitionName), partitionName);
        }

        public static HivePartitionName partition(String databaseName, String tableName, String partitionName)
        {
            return partition(HiveTableName.table(databaseName, tableName), partitionName);
        }

        public static HivePartitionName partition(String databaseName, String tableName, List<String> partitionValues)
        {
            return new HivePartitionName(HiveTableName.table(databaseName, tableName), partitionValues, null);
        }

        public HiveTableName getHiveTableName()
        {
            return hiveTableName;
        }

        public List<String> getPartitionValues()
        {
            return partitionValues;
        }

        public String getPartitionName()
        {
            return requireNonNull(partitionName, "partitionName is null");
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("hiveTableName", hiveTableName)
                    .add("partitionValues", partitionValues)
                    .add("partitionName", partitionName)
                    .toString();
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

            HivePartitionName other = (HivePartitionName) o;
            return Objects.equals(hiveTableName, other.hiveTableName) &&
                    Objects.equals(partitionValues, other.partitionValues);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(hiveTableName, partitionValues);
        }
    }

    private static class PartitionFilter
    {
        private final HiveTableName hiveTableName;
        private final List<String> parts;

        private PartitionFilter(HiveTableName hiveTableName, List<String> parts)
        {
            this.hiveTableName = hiveTableName;
            this.parts = ImmutableList.copyOf(parts);
        }

        public static PartitionFilter partitionFilter(String databaseName, String tableName, List<String> parts)
        {
            return new PartitionFilter(HiveTableName.table(databaseName, tableName), parts);
        }

        public HiveTableName getHiveTableName()
        {
            return hiveTableName;
        }

        public List<String> getParts()
        {
            return parts;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("hiveTableName", hiveTableName)
                    .add("parts", parts)
                    .toString();
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

            PartitionFilter other = (PartitionFilter) o;
            return Objects.equals(hiveTableName, other.hiveTableName) &&
                    Objects.equals(parts, other.parts);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(hiveTableName, parts);
        }
    }

    private static class UserTableKey
    {
        private final String user;
        private final String database;
        private final String table;

        public UserTableKey(String user, String table, String database)
        {
            this.user = requireNonNull(user, "principalName is null");
            this.table = requireNonNull(table, "table is null");
            this.database = requireNonNull(database, "database is null");
        }

        public String getUser()
        {
            return user;
        }

        public String getDatabase()
        {
            return database;
        }

        public String getTable()
        {
            return table;
        }

        public boolean matches(String databaseName, String tableName)
        {
            return this.database.equals(databaseName) && this.table.equals(tableName);
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
            UserTableKey that = (UserTableKey) o;
            return Objects.equals(user, that.user) &&
                    Objects.equals(table, that.table) &&
                    Objects.equals(database, that.database);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(user, table, database);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("principalName", user)
                    .add("table", table)
                    .add("database", database)
                    .toString();
        }
    }

    private static final class TableColumnStatisticsCacheKey
    {
        private final HiveTableName hiveTableName;
        private final String columnName;

        public TableColumnStatisticsCacheKey(String databaseName, String tableName, String columnName)
        {
            this.hiveTableName = HiveTableName.table(
                    requireNonNull(databaseName, "databaseName is null"),
                    requireNonNull(tableName, "tableName can not be null"));
            this.columnName = requireNonNull(columnName, "columnName can not be null");
        }

        public HiveTableName getHiveTableName()
        {
            return hiveTableName;
        }

        public String getColumnName()
        {
            return columnName;
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
            TableColumnStatisticsCacheKey that = (TableColumnStatisticsCacheKey) o;
            return Objects.equals(hiveTableName, that.hiveTableName) &&
                    Objects.equals(columnName, that.columnName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(hiveTableName, columnName);
        }
    }

    private static final class PartitionColumnStatisticsCacheKey
    {
        private final HivePartitionName hivePartitionName;
        private final String columnName;

        public PartitionColumnStatisticsCacheKey(String databaseName, String tableName, String partitionName, String columnName)
        {
            this.hivePartitionName = HivePartitionName.partition(
                    requireNonNull(databaseName, "databaseName is null"),
                    requireNonNull(tableName, "tableName can not be null"),
                    requireNonNull(partitionName, "partitionName can not be null"));
            this.columnName = requireNonNull(columnName, "columnName can not be null");
        }

        public HivePartitionName getHivePartitionName()
        {
            return hivePartitionName;
        }

        public String getColumnName()
        {
            return columnName;
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
            PartitionColumnStatisticsCacheKey that = (PartitionColumnStatisticsCacheKey) o;
            return Objects.equals(hivePartitionName, that.hivePartitionName) &&
                    Objects.equals(columnName, that.columnName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(hivePartitionName, columnName);
        }
    }
}
