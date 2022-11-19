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
package io.prestosql.plugin.hive.orc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.orc.OrcColumn;
import io.prestosql.orc.OrcDataSource;
import io.prestosql.orc.OrcDataSourceId;
import io.prestosql.orc.OrcReader;
import io.prestosql.orc.OrcReaderOptions;
import io.prestosql.orc.OrcRecordReader;
import io.prestosql.orc.TupleDomainOrcPredicate;
import io.prestosql.orc.TupleDomainOrcPredicate.TupleDomainOrcPredicateBuilder;
import io.prestosql.orc.metadata.OrcType.OrcTypeKind;
import io.prestosql.plugin.hive.DeleteDeltaLocations;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HivePageSourceFactory;
import io.prestosql.plugin.hive.orc.OrcPageSource.ColumnAdaptation;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.FixedPageSource;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockMissingException;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Maps.uniqueIndex;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.orc.OrcReader.INITIAL_BATCH_SIZE;
import static io.prestosql.orc.metadata.OrcType.OrcTypeKind.INT;
import static io.prestosql.orc.metadata.OrcType.OrcTypeKind.LONG;
import static io.prestosql.orc.metadata.OrcType.OrcTypeKind.STRUCT;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_FILE_MISSING_COLUMN_NAMES;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_MISSING_DATA;
import static io.prestosql.plugin.hive.HiveSessionProperties.getOrcLazyReadSmallRanges;
import static io.prestosql.plugin.hive.HiveSessionProperties.getOrcMaxBufferSize;
import static io.prestosql.plugin.hive.HiveSessionProperties.getOrcMaxMergeDistance;
import static io.prestosql.plugin.hive.HiveSessionProperties.getOrcMaxReadBlockSize;
import static io.prestosql.plugin.hive.HiveSessionProperties.getOrcStreamBufferSize;
import static io.prestosql.plugin.hive.HiveSessionProperties.getOrcTinyStripeThreshold;
import static io.prestosql.plugin.hive.HiveSessionProperties.isOrcBloomFiltersEnabled;
import static io.prestosql.plugin.hive.HiveSessionProperties.isOrcNestedLazy;
import static io.prestosql.plugin.hive.HiveSessionProperties.isUseOrcColumnNames;
import static io.prestosql.plugin.hive.orc.OrcPageSource.handleException;
import static io.prestosql.plugin.hive.util.HiveUtil.isDeserializerClass;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.ql.io.AcidUtils.isFullAcidTable;

public class OrcPageSourceFactory
        implements HivePageSourceFactory
{
    // ACID format column names
    public static final String ACID_COLUMN_OPERATION = "operation";
    public static final String ACID_COLUMN_ORIGINAL_TRANSACTION = "originalTransaction";
    public static final String ACID_COLUMN_BUCKET = "bucket";
    public static final String ACID_COLUMN_ROW_ID = "rowId";
    public static final String ACID_COLUMN_CURRENT_TRANSACTION = "currentTransaction";
    public static final String ACID_COLUMN_ROW_STRUCT = "row";

    private static final Pattern DEFAULT_HIVE_COLUMN_NAME_PATTERN = Pattern.compile("_col\\d+");
    private final OrcReaderOptions orcReaderOptions;
    private final HdfsEnvironment hdfsEnvironment;
    private final FileFormatDataSourceStats stats;

    @Inject
    public OrcPageSourceFactory(OrcReaderConfig config, HdfsEnvironment hdfsEnvironment, FileFormatDataSourceStats stats)
    {
        this(config.toOrcReaderOptions(), hdfsEnvironment, stats);
    }

    public OrcPageSourceFactory(
            OrcReaderOptions orcReaderOptions,
            HdfsEnvironment hdfsEnvironment,
            FileFormatDataSourceStats stats)
    {
        this.orcReaderOptions = requireNonNull(orcReaderOptions, "orcReaderOptions is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.stats = requireNonNull(stats, "stats is null");
    }

    @Override
    public Optional<? extends ConnectorPageSource> createPageSource(
            Configuration configuration,
            ConnectorSession session,
            Path path,
            long start,
            long length,
            long fileSize,
            Properties schema,
            List<HiveColumnHandle> columns,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            DateTimeZone hiveStorageTimeZone,
            Optional<DeleteDeltaLocations> deleteDeltaLocations)
    {
        if (!isDeserializerClass(schema, OrcSerde.class)) {
            return Optional.empty();
        }

        // per HIVE-13040 and ORC-162, empty files are allowed
        if (fileSize == 0) {
            return Optional.of(new FixedPageSource(ImmutableList.of()));
        }

        return Optional.of(createOrcPageSource(
                hdfsEnvironment,
                session.getUser(),
                configuration,
                path,
                start,
                length,
                fileSize,
                columns,
                isUseOrcColumnNames(session),
                isFullAcidTable(Maps.fromProperties(schema)),
                effectivePredicate,
                hiveStorageTimeZone,
                orcReaderOptions
                        .withMaxMergeDistance(getOrcMaxMergeDistance(session))
                        .withMaxBufferSize(getOrcMaxBufferSize(session))
                        .withStreamBufferSize(getOrcStreamBufferSize(session))
                        .withTinyStripeThreshold(getOrcTinyStripeThreshold(session))
                        .withMaxReadBlockSize(getOrcMaxReadBlockSize(session))
                        .withLazyReadSmallRanges(getOrcLazyReadSmallRanges(session))
                        .withNestedLazy(isOrcNestedLazy(session))
                        .withBloomFiltersEnabled(isOrcBloomFiltersEnabled(session)),
                deleteDeltaLocations,
                stats));
    }

    private static OrcPageSource createOrcPageSource(
            HdfsEnvironment hdfsEnvironment,
            String sessionUser,
            Configuration configuration,
            Path path,
            long start,
            long length,
            long fileSize,
            List<HiveColumnHandle> columns,
            boolean useOrcColumnNames,
            boolean isFullAcid,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            DateTimeZone hiveStorageTimeZone,
            OrcReaderOptions options,
            Optional<DeleteDeltaLocations> deleteDeltaLocations,
            FileFormatDataSourceStats stats)
    {
        for (HiveColumnHandle column : columns) {
            checkArgument(column.getColumnType() == REGULAR, "column type must be regular: %s", column);
        }
        checkArgument(!effectivePredicate.isNone());

        OrcDataSource orcDataSource;
        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(sessionUser, path, configuration);
            FSDataInputStream inputStream = hdfsEnvironment.doAs(sessionUser, () -> fileSystem.open(path));
            orcDataSource = new HdfsOrcDataSource(
                    new OrcDataSourceId(path.toString()),
                    fileSize,
                    options,
                    inputStream,
                    stats);
        }
        catch (Exception e) {
            if (nullToEmpty(e.getMessage()).trim().equals("Filesystem closed") ||
                    e instanceof FileNotFoundException) {
                throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, splitError(e, path, start, length), e);
        }

        AggregatedMemoryContext systemMemoryUsage = newSimpleAggregatedMemoryContext();
        try {
            OrcReader reader = new OrcReader(orcDataSource, options);

            List<OrcColumn> fileColumns = reader.getRootColumn().getNestedColumns();
            List<OrcColumn> fileReadColumns = new ArrayList<>(columns.size() + (isFullAcid ? 3 : 0));
            List<Type> fileReadTypes = new ArrayList<>(columns.size() + (isFullAcid ? 3 : 0));
            if (isFullAcid) {
                verifyAcidSchema(reader, path);
                Map<String, OrcColumn> acidColumnsByName = uniqueIndex(fileColumns, orcColumn -> orcColumn.getColumnName().toLowerCase(ENGLISH));
                fileColumns = acidColumnsByName.get(ACID_COLUMN_ROW_STRUCT.toLowerCase(ENGLISH)).getNestedColumns();

                fileReadColumns.add(acidColumnsByName.get(ACID_COLUMN_ORIGINAL_TRANSACTION.toLowerCase(ENGLISH)));
                fileReadTypes.add(BIGINT);
                fileReadColumns.add(acidColumnsByName.get(ACID_COLUMN_BUCKET.toLowerCase(ENGLISH)));
                fileReadTypes.add(INTEGER);
                fileReadColumns.add(acidColumnsByName.get(ACID_COLUMN_ROW_ID.toLowerCase(ENGLISH)));
                fileReadTypes.add(BIGINT);
            }

            Map<String, OrcColumn> fileColumnsByName = ImmutableMap.of();
            if (useOrcColumnNames || isFullAcid) {
                verifyFileHasColumnNames(fileColumns, path);

                // Convert column names read from ORC files to lower case to be consistent with those stored in Hive Metastore
                fileColumnsByName = uniqueIndex(fileColumns, orcColumn -> orcColumn.getColumnName().toLowerCase(ENGLISH));
            }

            TupleDomainOrcPredicateBuilder predicateBuilder = TupleDomainOrcPredicate.builder()
                    .setBloomFiltersEnabled(options.isBloomFiltersEnabled());
            Map<HiveColumnHandle, Domain> effectivePredicateDomains = effectivePredicate.getDomains()
                    .orElseThrow(() -> new IllegalArgumentException("Effective predicate is none"));
            List<ColumnAdaptation> columnAdaptations = new ArrayList<>(columns.size());
            for (HiveColumnHandle column : columns) {
                OrcColumn orcColumn = null;
                if (useOrcColumnNames || isFullAcid) {
                    orcColumn = fileColumnsByName.get(column.getName().toLowerCase(ENGLISH));
                }
                else if (column.getHiveColumnIndex() < fileColumns.size()) {
                    orcColumn = fileColumns.get(column.getHiveColumnIndex());
                }

                Type readType = column.getType();
                if (orcColumn != null) {
                    int sourceIndex = fileReadColumns.size();
                    columnAdaptations.add(ColumnAdaptation.sourceColumn(sourceIndex));
                    fileReadColumns.add(orcColumn);
                    fileReadTypes.add(readType);

                    Domain domain = effectivePredicateDomains.get(column);
                    if (domain != null) {
                        predicateBuilder.addColumn(orcColumn.getColumnId(), domain);
                    }
                }
                else {
                    columnAdaptations.add(ColumnAdaptation.nullColumn(readType));
                }
            }

            OrcRecordReader recordReader = reader.createRecordReader(
                    fileReadColumns,
                    fileReadTypes,
                    predicateBuilder.build(),
                    start,
                    length,
                    hiveStorageTimeZone,
                    systemMemoryUsage,
                    INITIAL_BATCH_SIZE,
                    exception -> handleException(orcDataSource.getId(), exception));

            Optional<OrcDeletedRows> deletedRows = deleteDeltaLocations.map(locations ->
                    new OrcDeletedRows(
                            path.getName(),
                            new OrcDeleteDeltaPageSourceFactory(options, sessionUser, configuration, hdfsEnvironment, stats),
                            sessionUser,
                            configuration,
                            hdfsEnvironment,
                            locations));

            return new OrcPageSource(
                    recordReader,
                    columnAdaptations,
                    orcDataSource,
                    deletedRows,
                    systemMemoryUsage,
                    stats);
        }
        catch (Exception e) {
            try {
                orcDataSource.close();
            }
            catch (IOException ignored) {
            }
            if (e instanceof PrestoException) {
                throw (PrestoException) e;
            }
            String message = splitError(e, path, start, length);
            if (e instanceof BlockMissingException) {
                throw new PrestoException(HIVE_MISSING_DATA, message, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, message, e);
        }
    }

    private static String splitError(Throwable t, Path path, long start, long length)
    {
        return format("Error opening Hive split %s (offset=%s, length=%s): %s", path, start, length, t.getMessage());
    }

    private static void verifyFileHasColumnNames(List<OrcColumn> columns, Path path)
    {
        if (!columns.isEmpty() && columns.stream().map(OrcColumn::getColumnName).allMatch(physicalColumnName -> DEFAULT_HIVE_COLUMN_NAME_PATTERN.matcher(physicalColumnName).matches())) {
            throw new PrestoException(
                    HIVE_FILE_MISSING_COLUMN_NAMES,
                    "ORC file does not contain column names in the footer: " + path);
        }
    }

    static void verifyAcidSchema(OrcReader orcReader, Path path)
    {
        OrcColumn rootColumn = orcReader.getRootColumn();
        if (rootColumn.getNestedColumns().size() != 6) {
            throw new PrestoException(HIVE_BAD_DATA, "ORC ACID file should have 6 columns: " + path);
        }
        verifyAcidColumn(orcReader, 0, ACID_COLUMN_OPERATION, INT, path);
        verifyAcidColumn(orcReader, 1, ACID_COLUMN_ORIGINAL_TRANSACTION, LONG, path);
        verifyAcidColumn(orcReader, 2, ACID_COLUMN_BUCKET, INT, path);
        verifyAcidColumn(orcReader, 3, ACID_COLUMN_ROW_ID, LONG, path);
        verifyAcidColumn(orcReader, 4, ACID_COLUMN_CURRENT_TRANSACTION, LONG, path);
        verifyAcidColumn(orcReader, 5, ACID_COLUMN_ROW_STRUCT, STRUCT, path);
    }

    private static void verifyAcidColumn(OrcReader orcReader, int columnIndex, String columnName, OrcTypeKind columnType, Path path)
    {
        OrcColumn column = orcReader.getRootColumn().getNestedColumns().get(columnIndex);
        if (!column.getColumnName().toLowerCase(ENGLISH).equals(columnName.toLowerCase(ENGLISH))) {
            throw new PrestoException(HIVE_BAD_DATA, format("ORC ACID file column %s should be named %s: %s", columnIndex, columnName, path));
        }
        if (column.getColumnType() != columnType) {
            throw new PrestoException(HIVE_BAD_DATA, format("ORC ACID file %s column should be type %s: %s", columnName, columnType, path));
        }
    }
}
