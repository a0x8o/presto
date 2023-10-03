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
package io.trino.plugin.deltalake.transactionlog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.Inject;
import io.airlift.jmx.CacheStatsMBean;
import io.trino.cache.EvictableCacheBuilder;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.TrinoInputFile;
import io.trino.parquet.ParquetReaderOptions;
import io.trino.plugin.deltalake.DeltaLakeColumnMetadata;
import io.trino.plugin.deltalake.DeltaLakeConfig;
import io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator;
import io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointSchemaManager;
import io.trino.plugin.deltalake.transactionlog.checkpoint.LastCheckpoint;
import io.trino.plugin.deltalake.transactionlog.checkpoint.TransactionLogTail;
import io.trino.plugin.hive.FileFormatDataSourceStats;
import io.trino.plugin.hive.parquet.ParquetReaderConfig;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.VarbinaryType;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static io.airlift.slice.SizeOf.instanceSize;
import static io.trino.cache.CacheUtils.invalidateAllIf;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_INVALID_SCHEMA;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogParser.readLastCheckpoint;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogUtil.getTransactionLogJsonEntryPath;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.ADD;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.COMMIT;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.METADATA;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.PROTOCOL;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointEntryIterator.EntryType.REMOVE;
import static io.trino.plugin.deltalake.transactionlog.checkpoint.TransactionLogTail.getEntriesFromJson;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class TransactionLogAccess
{
    private final TypeManager typeManager;
    private final CheckpointSchemaManager checkpointSchemaManager;
    private final FileFormatDataSourceStats fileFormatDataSourceStats;
    private final TrinoFileSystemFactory fileSystemFactory;
    private final ParquetReaderOptions parquetReaderOptions;
    private final boolean checkpointRowStatisticsWritingEnabled;
    private final int domainCompactionThreshold;

    private final Cache<TableLocation, TableSnapshot> tableSnapshots;
    private final Cache<TableVersion, DeltaLakeDataFileCacheEntry> activeDataFileCache;

    // TODO move to query-level state
    private final Map<QueriedLocation, Long> queriedLocations = new ConcurrentHashMap<>();
    private final Map<QueriedTable, TableSnapshot> queriedTables = new ConcurrentHashMap<>();

    @Inject
    public TransactionLogAccess(
            TypeManager typeManager,
            CheckpointSchemaManager checkpointSchemaManager,
            DeltaLakeConfig deltaLakeConfig,
            FileFormatDataSourceStats fileFormatDataSourceStats,
            TrinoFileSystemFactory fileSystemFactory,
            ParquetReaderConfig parquetReaderConfig)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.checkpointSchemaManager = requireNonNull(checkpointSchemaManager, "checkpointSchemaManager is null");
        this.fileFormatDataSourceStats = requireNonNull(fileFormatDataSourceStats, "fileFormatDataSourceStats is null");
        this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        this.parquetReaderOptions = parquetReaderConfig.toParquetReaderOptions().withBloomFilter(false);
        this.checkpointRowStatisticsWritingEnabled = deltaLakeConfig.isCheckpointRowStatisticsWritingEnabled();
        this.domainCompactionThreshold = deltaLakeConfig.getDomainCompactionThreshold();

        tableSnapshots = EvictableCacheBuilder.newBuilder()
                .expireAfterWrite(deltaLakeConfig.getMetadataCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                .maximumSize(deltaLakeConfig.getMetadataCacheMaxSize())
                .shareNothingWhenDisabled()
                .recordStats()
                .build();
        activeDataFileCache = EvictableCacheBuilder.newBuilder()
                .weigher((Weigher<TableVersion, DeltaLakeDataFileCacheEntry>) (key, value) -> Ints.saturatedCast(key.getRetainedSizeInBytes() + value.getRetainedSizeInBytes()))
                .maximumWeight(deltaLakeConfig.getDataFileCacheSize().toBytes())
                .expireAfterWrite(deltaLakeConfig.getDataFileCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                .shareNothingWhenDisabled()
                .recordStats()
                .build();
    }

    @Managed
    @Nested
    public CacheStatsMBean getDataFileMetadataCacheStats()
    {
        return new CacheStatsMBean(activeDataFileCache);
    }

    @Managed
    @Nested
    public CacheStatsMBean getMetadataCacheStats()
    {
        return new CacheStatsMBean(tableSnapshots);
    }

    public TableSnapshot getSnapshot(ConnectorSession session, SchemaTableName table, String tableLocation, Optional<Long> atVersion)
            throws IOException
    {
        String queryId = session.getQueryId();
        QueriedLocation queriedLocation = new QueriedLocation(session.getQueryId(), tableLocation);
        if (atVersion.isEmpty()) {
            atVersion = Optional.ofNullable(queriedLocations.get(queriedLocation));
        }
        if (atVersion.isPresent()) {
            long version = atVersion.get();
            TableSnapshot snapshot = queriedTables.get(new QueriedTable(queriedLocation, version));
            checkState(snapshot != null, "No previously loaded snapshot found for query %s, table %s [%s] at version %s", queryId, table, tableLocation, version);
            return snapshot;
        }

        TableSnapshot snapshot = loadSnapshot(session, table, tableLocation);
        // Lack of concurrency for given query is currently guaranteed by DeltaLakeMetadata
        checkState(queriedLocations.put(queriedLocation, snapshot.getVersion()) == null, "queriedLocations changed concurrently for %s", queriedLocation);
        queriedTables.put(new QueriedTable(queriedLocation, snapshot.getVersion()), snapshot);
        return snapshot;
    }

    public void cleanupQuery(ConnectorSession session)
    {
        String queryId = session.getQueryId();
        queriedLocations.keySet().removeIf(queriedLocation -> queriedLocation.queryId().equals(queryId));
        queriedTables.keySet().removeIf(queriedTable -> queriedTable.queriedLocation().queryId().equals(queryId));
    }

    @VisibleForTesting
    protected TableSnapshot loadSnapshot(ConnectorSession session, SchemaTableName table, String tableLocation)
            throws IOException
    {
        TableLocation cacheKey = new TableLocation(table, tableLocation);
        TableSnapshot cachedSnapshot = tableSnapshots.getIfPresent(cacheKey);
        TableSnapshot snapshot;
        TrinoFileSystem fileSystem = fileSystemFactory.create(session);
        if (cachedSnapshot == null) {
            try {
                Optional<LastCheckpoint> lastCheckpoint = readLastCheckpoint(fileSystem, tableLocation);
                snapshot = tableSnapshots.get(cacheKey, () ->
                        TableSnapshot.load(
                                table,
                                lastCheckpoint,
                                fileSystem,
                                tableLocation,
                                parquetReaderOptions,
                                checkpointRowStatisticsWritingEnabled,
                                domainCompactionThreshold));
            }
            catch (UncheckedExecutionException | ExecutionException e) {
                throwIfUnchecked(e.getCause());
                throw new RuntimeException(e);
            }
        }
        else {
            Optional<TableSnapshot> updatedSnapshot = cachedSnapshot.getUpdatedSnapshot(fileSystem, Optional.empty());
            if (updatedSnapshot.isPresent()) {
                snapshot = updatedSnapshot.get();
                tableSnapshots.asMap().replace(cacheKey, cachedSnapshot, snapshot);
            }
            else {
                snapshot = cachedSnapshot;
            }
        }
        return snapshot;
    }

    public void flushCache()
    {
        tableSnapshots.invalidateAll();
        activeDataFileCache.invalidateAll();
    }

    public void invalidateCache(SchemaTableName schemaTableName, Optional<String> tableLocation)
    {
        requireNonNull(schemaTableName, "schemaTableName is null");
        // Invalidate by location in case one table (location) unregistered and re-register under different name
        tableLocation.ifPresent(location -> {
            invalidateAllIf(tableSnapshots, cacheKey -> cacheKey.location().equals(location));
            invalidateAllIf(activeDataFileCache, cacheKey -> cacheKey.tableLocation().location().equals(location));
        });
        invalidateAllIf(tableSnapshots, cacheKey -> cacheKey.tableName().equals(schemaTableName));
        invalidateAllIf(activeDataFileCache, cacheKey -> cacheKey.tableLocation().tableName().equals(schemaTableName));
    }

    public MetadataEntry getMetadataEntry(TableSnapshot tableSnapshot, ConnectorSession session)
    {
        if (tableSnapshot.getCachedMetadata().isEmpty()) {
            try (Stream<MetadataEntry> metadataEntries = getEntries(
                    tableSnapshot,
                    METADATA,
                    entryStream -> entryStream.map(DeltaLakeTransactionLogEntry::getMetaData).filter(Objects::nonNull),
                    session,
                    fileSystemFactory.create(session),
                    fileFormatDataSourceStats)) {
                // Get last entry in the stream
                tableSnapshot.setCachedMetadata(metadataEntries.reduce((first, second) -> second));
            }
        }
        return tableSnapshot.getCachedMetadata()
                .orElseThrow(() -> new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Metadata not found in transaction log for " + tableSnapshot.getTable()));
    }

    public List<AddFileEntry> getActiveFiles(TableSnapshot tableSnapshot, ConnectorSession session)
    {
        try {
            TableVersion tableVersion = new TableVersion(new TableLocation(tableSnapshot.getTable(), tableSnapshot.getTableLocation()), tableSnapshot.getVersion());

            DeltaLakeDataFileCacheEntry cacheEntry = activeDataFileCache.get(tableVersion, () -> {
                DeltaLakeDataFileCacheEntry oldCached = activeDataFileCache.asMap().keySet().stream()
                        .filter(key -> key.tableLocation().equals(tableVersion.tableLocation()) &&
                                key.version() < tableVersion.version())
                        .flatMap(key -> Optional.ofNullable(activeDataFileCache.getIfPresent(key))
                                .map(value -> Map.entry(key, value))
                                .stream())
                        .max(Comparator.comparing(entry -> entry.getKey().version()))
                        .map(Map.Entry::getValue)
                        .orElse(null);
                if (oldCached != null) {
                    try {
                        List<DeltaLakeTransactionLogEntry> newEntries = getJsonEntries(
                                oldCached.getVersion(),
                                tableSnapshot.getVersion(),
                                tableSnapshot,
                                fileSystemFactory.create(session));
                        return oldCached.withUpdatesApplied(newEntries, tableSnapshot.getVersion());
                    }
                    catch (MissingTransactionLogException e) {
                        // The cached state cannot be used to calculate current state, as some
                        // intermediate transaction files are expired.
                    }
                }

                List<AddFileEntry> activeFiles = loadActiveFiles(tableSnapshot, session);
                return new DeltaLakeDataFileCacheEntry(tableSnapshot.getVersion(), activeFiles);
            });
            return cacheEntry.getActiveFiles();
        }
        catch (ExecutionException | UncheckedExecutionException e) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Failed accessing transaction log for table: " + tableSnapshot.getTable(), e);
        }
    }

    private List<AddFileEntry> loadActiveFiles(TableSnapshot tableSnapshot, ConnectorSession session)
    {
        try (Stream<AddFileEntry> entries = getEntries(
                tableSnapshot,
                ImmutableSet.of(ADD),
                this::activeAddEntries,
                session,
                fileSystemFactory.create(session),
                fileFormatDataSourceStats)) {
            List<AddFileEntry> activeFiles = entries.collect(toImmutableList());
            return activeFiles;
        }
    }

    public static List<DeltaLakeColumnMetadata> columnsWithStats(MetadataEntry metadataEntry, ProtocolEntry protocolEntry, TypeManager typeManager)
    {
        return columnsWithStats(DeltaLakeSchemaSupport.extractSchema(metadataEntry, protocolEntry, typeManager), metadataEntry.getOriginalPartitionColumns());
    }

    public static ImmutableList<DeltaLakeColumnMetadata> columnsWithStats(List<DeltaLakeColumnMetadata> schema, List<String> partitionColumns)
    {
        return schema.stream()
                .filter(column -> !partitionColumns.contains(column.getName()))
                .filter(column -> {
                    Type type = column.getType();
                    return !(type instanceof MapType || type instanceof ArrayType || type.equals(BooleanType.BOOLEAN) || type.equals(VarbinaryType.VARBINARY));
                })
                .collect(toImmutableList());
    }

    private Stream<AddFileEntry> activeAddEntries(Stream<DeltaLakeTransactionLogEntry> checkpointEntries, List<Transaction> transactions)
    {
        Map<String, AddFileEntry> activeJsonEntries = new LinkedHashMap<>();
        HashSet<String> removedFiles = new HashSet<>();

        // The json entries containing the last few entries in the log need to be applied on top of the parquet snapshot:
        // - Any files which have been removed need to be excluded
        // - Any files with newer add actions need to be updated with the most recent metadata
        transactions.forEach(transaction -> {
            Map<String, AddFileEntry> addFilesInTransaction = new LinkedHashMap<>();
            Set<String> removedFilesInTransaction = new HashSet<>();
            transaction.transactionEntries().forEach(deltaLakeTransactionLogEntry -> {
                if (deltaLakeTransactionLogEntry.getAdd() != null) {
                    addFilesInTransaction.put(deltaLakeTransactionLogEntry.getAdd().getPath(), deltaLakeTransactionLogEntry.getAdd());
                }
                else if (deltaLakeTransactionLogEntry.getRemove() != null) {
                    removedFilesInTransaction.add(deltaLakeTransactionLogEntry.getRemove().getPath());
                }
            });

            // Process 'remove' entries first because deletion vectors register both 'add' and 'remove' entries and the 'add' entry should be kept
            removedFiles.addAll(removedFilesInTransaction);
            removedFilesInTransaction.forEach(activeJsonEntries::remove);
            activeJsonEntries.putAll(addFilesInTransaction);
        });

        Stream<AddFileEntry> filteredCheckpointEntries = checkpointEntries
                .map(DeltaLakeTransactionLogEntry::getAdd)
                .filter(Objects::nonNull)
                .filter(addEntry -> !removedFiles.contains(addEntry.getPath()) && !activeJsonEntries.containsKey(addEntry.getPath()));

        return Stream.concat(filteredCheckpointEntries, activeJsonEntries.values().stream());
    }

    public Stream<RemoveFileEntry> getRemoveEntries(TableSnapshot tableSnapshot, ConnectorSession session)
    {
        return getEntries(
                tableSnapshot,
                REMOVE,
                entryStream -> entryStream.map(DeltaLakeTransactionLogEntry::getRemove).filter(Objects::nonNull),
                session,
                fileSystemFactory.create(session),
                fileFormatDataSourceStats);
    }

    public Map<Class<?>, Object> getTransactionLogEntries(
            ConnectorSession session,
            TableSnapshot tableSnapshot,
            Set<CheckpointEntryIterator.EntryType> entryTypes,
            Function<Stream<DeltaLakeTransactionLogEntry>, Stream<Object>> entryMapper)
    {
        Stream<Object> entries = getEntries(
                tableSnapshot,
                entryTypes,
                (checkpointStream, jsonStream) -> entryMapper.apply(Stream.concat(checkpointStream, jsonStream.stream().map(Transaction::transactionEntries).flatMap(Collection::stream))),
                session,
                fileSystemFactory.create(session),
                fileFormatDataSourceStats);

        return entries.collect(toImmutableMap(Object::getClass, Function.identity(), (first, second) -> second));
    }

    public ProtocolEntry getProtocolEntry(ConnectorSession session, TableSnapshot tableSnapshot)
    {
        return getProtocolEntries(tableSnapshot, session)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Protocol entry not found in transaction log for table " + tableSnapshot.getTable()));
    }

    public Stream<ProtocolEntry> getProtocolEntries(TableSnapshot tableSnapshot, ConnectorSession session)
    {
        return getEntries(
                tableSnapshot,
                PROTOCOL,
                entryStream -> entryStream.map(DeltaLakeTransactionLogEntry::getProtocol).filter(Objects::nonNull),
                session,
                fileSystemFactory.create(session),
                fileFormatDataSourceStats);
    }

    public Stream<CommitInfoEntry> getCommitInfoEntries(TableSnapshot tableSnapshot, ConnectorSession session)
    {
        return getEntries(
                tableSnapshot,
                COMMIT,
                entryStream -> entryStream.map(DeltaLakeTransactionLogEntry::getCommitInfo).filter(Objects::nonNull),
                session,
                fileSystemFactory.create(session),
                fileFormatDataSourceStats);
    }

    /**
     * Produces a stream of actions from the transaction log, combining the Parquet checkpoint and the JSON transactions.
     * While {@link DeltaLakeTransactionLogEntry} has fields for multiple actions, only one can be populated in any instance.
     * We take advantage of that by only reading one column from the Parquet checkpoint.
     *
     * @param entryTypes A set of transaction log entry types to retrieve
     * @param entryMapper extracts and filters out the required elements from the TransactionLogEntries. Receives the actions from the checkpoint file, and the actions
     * from the JSON commits in chronological order
     * @param session the current session
     * @param <T> the type of the action
     * @return an object that encapsulates a stream of actions
     */
    private <T> Stream<T> getEntries(
            TableSnapshot tableSnapshot,
            Set<CheckpointEntryIterator.EntryType> entryTypes,
            BiFunction<Stream<DeltaLakeTransactionLogEntry>, List<Transaction>, Stream<T>> entryMapper,
            ConnectorSession session,
            TrinoFileSystem fileSystem,
            FileFormatDataSourceStats stats)
    {
        try {
            List<Transaction> transactions = tableSnapshot.getTransactions();
            Stream<DeltaLakeTransactionLogEntry> checkpointEntries = tableSnapshot.getCheckpointTransactionLogEntries(
                    session, entryTypes, checkpointSchemaManager, typeManager, fileSystem, stats);

            return entryMapper.apply(
                    checkpointEntries,
                    transactions);
        }
        catch (IOException e) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Error reading transaction log for " + tableSnapshot.getTable(), e);
        }
    }

    /**
     * Convenience method for accessors which don't need to separate out the checkpoint entries from the json entries.
     */
    private <T> Stream<T> getEntries(
            TableSnapshot tableSnapshot,
            CheckpointEntryIterator.EntryType entryType,
            Function<Stream<DeltaLakeTransactionLogEntry>, Stream<T>> entryMapper,
            ConnectorSession session,
            TrinoFileSystem fileSystem,
            FileFormatDataSourceStats stats)
    {
        return getEntries(
                tableSnapshot,
                ImmutableSet.of(entryType),
                (checkpointStream, jsonStream) -> entryMapper.apply(Stream.concat(checkpointStream, jsonStream.stream().map(Transaction::transactionEntries).flatMap(Collection::stream))),
                session,
                fileSystem,
                stats);
    }

    public Stream<DeltaLakeTransactionLogEntry> getJsonEntries(TrinoFileSystem fileSystem, String transactionLogDir, List<Long> forVersions)
    {
        return forVersions.stream()
                .flatMap(version -> {
                    try {
                        Optional<List<DeltaLakeTransactionLogEntry>> entriesFromJson = getEntriesFromJson(version, transactionLogDir, fileSystem);
                        //noinspection SimplifyOptionalCallChains
                        return entriesFromJson.map(List::stream)
                                // transaction log does not exist. Might have been expired.
                                .orElseGet(Stream::of);
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Returns a stream of transaction log versions between {@code startAt} point in time and {@code lastVersion}.
     */
    public List<Long> getPastTableVersions(TrinoFileSystem fileSystem, String transactionLogDir, Instant startAt, long lastVersion)
    {
        ImmutableList.Builder<Long> result = ImmutableList.builder();
        for (long version = lastVersion; version >= 0; version--) {
            Location entryPath = getTransactionLogJsonEntryPath(transactionLogDir, version);
            TrinoInputFile inputFile = fileSystem.newInputFile(entryPath);
            try {
                if (inputFile.lastModified().isBefore(startAt)) {
                    // already too old
                    break;
                }
            }
            catch (FileNotFoundException e) {
                // no longer exists, break iteration
                return null;
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            result.add(version);
        }
        return result.build();
    }

    private static List<DeltaLakeTransactionLogEntry> getJsonEntries(long startVersion, long endVersion, TableSnapshot tableSnapshot, TrinoFileSystem fileSystem)
            throws IOException
    {
        Optional<Long> lastCheckpointVersion = tableSnapshot.getLastCheckpointVersion();
        if (lastCheckpointVersion.isPresent() && startVersion < lastCheckpointVersion.get()) {
            return ImmutableList.<DeltaLakeTransactionLogEntry>builder()
                    .addAll(TransactionLogTail.loadNewTail(fileSystem, tableSnapshot.getTableLocation(), Optional.of(startVersion), lastCheckpointVersion).getFileEntries())
                    .addAll(tableSnapshot.getJsonTransactionLogEntries())
                    .build();
        }
        return TransactionLogTail.loadNewTail(fileSystem, tableSnapshot.getTableLocation(), Optional.of(startVersion), Optional.of(endVersion)).getFileEntries();
    }

    public static String canonicalizeColumnName(String columnName)
    {
        return columnName.toLowerCase(Locale.ENGLISH);
    }

    public static <T> Map<CanonicalColumnName, T> toCanonicalNameKeyedMap(Map<String, T> map, Map<String, CanonicalColumnName> canonicalColumnNames)
    {
        return map.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(toImmutableMap(
                        entry -> requireNonNull(
                                canonicalColumnNames.get(entry.getKey()),
                                format("Did not find CanonicalColumnName for %s", entry.getKey())),
                        Map.Entry::getValue));
    }

    @Deprecated
    // we should migrate codebase to use maps keyed on CanonicalColumnName all over the place.
    public static <T> Map<String, T> toOriginalNameKeyedMap(Map<CanonicalColumnName, T> map)
    {
        return map.entrySet().stream()
                .collect(toImmutableMap(
                        entry -> entry.getKey().getOriginalName(),
                        Map.Entry::getValue));
    }

    private record TableLocation(SchemaTableName tableName, String location)
    {
        private static final int INSTANCE_SIZE = instanceSize(TableLocation.class);

        TableLocation
        {
            requireNonNull(tableName, "tableName is null");
            requireNonNull(location, "location is null");
        }

        long getRetainedSizeInBytes()
        {
            return INSTANCE_SIZE +
                    tableName.getRetainedSizeInBytes() +
                    estimatedSizeOf(location);
        }
    }

    private record TableVersion(TableLocation tableLocation, long version)
    {
        private static final int INSTANCE_SIZE = instanceSize(TableVersion.class);

        TableVersion
        {
            requireNonNull(tableLocation, "tableLocation is null");
        }

        long getRetainedSizeInBytes()
        {
            return INSTANCE_SIZE +
                    tableLocation.getRetainedSizeInBytes();
        }
    }

    private record QueriedLocation(String queryId, String tableLocation)
    {
        QueriedLocation
        {
            requireNonNull(queryId, "queryId is null");
            requireNonNull(tableLocation, "tableLocation is null");
        }
    }

    private record QueriedTable(QueriedLocation queriedLocation, long version)
    {
        QueriedTable
        {
            requireNonNull(queriedLocation, "queriedLocation is null");
        }
    }
}
