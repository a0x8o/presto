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
package io.trino.plugin.iceberg;

import com.google.common.base.Suppliers;
import com.google.common.base.VerifyException;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Traverser;
import com.google.inject.Inject;
import io.airlift.slice.Slice;
import io.trino.annotation.NotThreadSafe;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.TrinoInputFile;
import io.trino.memory.context.AggregatedMemoryContext;
import io.trino.orc.OrcColumn;
import io.trino.orc.OrcCorruptionException;
import io.trino.orc.OrcDataSource;
import io.trino.orc.OrcDataSourceId;
import io.trino.orc.OrcReader;
import io.trino.orc.OrcReaderOptions;
import io.trino.orc.OrcRecordReader;
import io.trino.orc.TupleDomainOrcPredicate;
import io.trino.orc.TupleDomainOrcPredicate.TupleDomainOrcPredicateBuilder;
import io.trino.orc.metadata.OrcType;
import io.trino.parquet.BloomFilterStore;
import io.trino.parquet.Field;
import io.trino.parquet.ParquetCorruptionException;
import io.trino.parquet.ParquetDataSource;
import io.trino.parquet.ParquetDataSourceId;
import io.trino.parquet.ParquetReaderOptions;
import io.trino.parquet.predicate.TupleDomainParquetPredicate;
import io.trino.parquet.reader.MetadataReader;
import io.trino.parquet.reader.ParquetReader;
import io.trino.plugin.hive.FileFormatDataSourceStats;
import io.trino.plugin.hive.ReaderColumns;
import io.trino.plugin.hive.ReaderPageSource;
import io.trino.plugin.hive.ReaderProjectionsAdapter;
import io.trino.plugin.hive.orc.OrcPageSource;
import io.trino.plugin.hive.orc.OrcPageSource.ColumnAdaptation;
import io.trino.plugin.hive.orc.OrcReaderConfig;
import io.trino.plugin.hive.parquet.ParquetPageSource;
import io.trino.plugin.hive.parquet.ParquetReaderConfig;
import io.trino.plugin.iceberg.IcebergParquetColumnIOConverter.FieldContext;
import io.trino.plugin.iceberg.delete.DeleteFile;
import io.trino.plugin.iceberg.delete.DeleteFilter;
import io.trino.plugin.iceberg.delete.EqualityDeleteFilter;
import io.trino.plugin.iceberg.delete.PositionDeleteFilter;
import io.trino.plugin.iceberg.delete.RowPredicate;
import io.trino.plugin.iceberg.fileio.ForwardingInputFile;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.EmptyPageSource;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.mapping.MappedField;
import org.apache.iceberg.mapping.MappedFields;
import org.apache.iceberg.mapping.NameMapping;
import org.apache.iceberg.mapping.NameMappingParser;
import org.apache.iceberg.parquet.ParquetSchemaUtil;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.util.StructLikeSet;
import org.apache.iceberg.util.StructProjection;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.ColumnIO;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.roaringbitmap.longlong.LongBitmapDataProvider;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.uniqueIndex;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.trino.orc.OrcReader.INITIAL_BATCH_SIZE;
import static io.trino.orc.OrcReader.ProjectedLayout;
import static io.trino.orc.OrcReader.fullyProjectedLayout;
import static io.trino.parquet.BloomFilterStore.getBloomFilterStore;
import static io.trino.parquet.ParquetTypeUtils.getColumnIO;
import static io.trino.parquet.ParquetTypeUtils.getDescriptors;
import static io.trino.parquet.predicate.PredicateUtils.buildPredicate;
import static io.trino.parquet.predicate.PredicateUtils.predicateMatches;
import static io.trino.plugin.hive.parquet.ParquetPageSourceFactory.createDataSource;
import static io.trino.plugin.iceberg.IcebergColumnHandle.TRINO_MERGE_PARTITION_DATA;
import static io.trino.plugin.iceberg.IcebergColumnHandle.TRINO_MERGE_PARTITION_SPEC_ID;
import static io.trino.plugin.iceberg.IcebergErrorCode.ICEBERG_BAD_DATA;
import static io.trino.plugin.iceberg.IcebergErrorCode.ICEBERG_CANNOT_OPEN_SPLIT;
import static io.trino.plugin.iceberg.IcebergErrorCode.ICEBERG_CURSOR_ERROR;
import static io.trino.plugin.iceberg.IcebergMetadataColumn.FILE_MODIFIED_TIME;
import static io.trino.plugin.iceberg.IcebergMetadataColumn.FILE_PATH;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getOrcLazyReadSmallRanges;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getOrcMaxBufferSize;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getOrcMaxMergeDistance;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getOrcMaxReadBlockSize;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getOrcStreamBufferSize;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getOrcTinyStripeThreshold;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getParquetMaxReadBlockRowCount;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getParquetMaxReadBlockSize;
import static io.trino.plugin.iceberg.IcebergSessionProperties.getParquetSmallFileThreshold;
import static io.trino.plugin.iceberg.IcebergSessionProperties.isOrcBloomFiltersEnabled;
import static io.trino.plugin.iceberg.IcebergSessionProperties.isOrcNestedLazy;
import static io.trino.plugin.iceberg.IcebergSessionProperties.isUseFileSizeFromMetadata;
import static io.trino.plugin.iceberg.IcebergSessionProperties.useParquetBloomFilter;
import static io.trino.plugin.iceberg.IcebergSplitManager.ICEBERG_DOMAIN_COMPACTION_THRESHOLD;
import static io.trino.plugin.iceberg.IcebergUtil.deserializePartitionValue;
import static io.trino.plugin.iceberg.IcebergUtil.getColumnHandle;
import static io.trino.plugin.iceberg.IcebergUtil.getPartitionKeys;
import static io.trino.plugin.iceberg.IcebergUtil.schemaFromHandles;
import static io.trino.plugin.iceberg.TypeConverter.ICEBERG_BINARY_TYPE;
import static io.trino.plugin.iceberg.TypeConverter.ORC_ICEBERG_ID_KEY;
import static io.trino.plugin.iceberg.delete.EqualityDeleteFilter.readEqualityDeletes;
import static io.trino.plugin.iceberg.delete.PositionDeleteFilter.readPositionDeletes;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.predicate.Utils.nativeValueToBlock;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.UuidType.UUID;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.apache.iceberg.FileContent.EQUALITY_DELETES;
import static org.apache.iceberg.FileContent.POSITION_DELETES;
import static org.apache.iceberg.MetadataColumns.DELETE_FILE_PATH;
import static org.apache.iceberg.MetadataColumns.DELETE_FILE_POS;
import static org.apache.iceberg.MetadataColumns.ROW_POSITION;
import static org.joda.time.DateTimeZone.UTC;

public class IcebergPageSourceProvider
        implements ConnectorPageSourceProvider
{
    private static final String AVRO_FIELD_ID = "field-id";

    private final TrinoFileSystemFactory fileSystemFactory;
    private final FileFormatDataSourceStats fileFormatDataSourceStats;
    private final OrcReaderOptions orcReaderOptions;
    private final ParquetReaderOptions parquetReaderOptions;
    private final TypeManager typeManager;

    @Inject
    public IcebergPageSourceProvider(
            TrinoFileSystemFactory fileSystemFactory,
            FileFormatDataSourceStats fileFormatDataSourceStats,
            OrcReaderConfig orcReaderConfig,
            ParquetReaderConfig parquetReaderConfig,
            TypeManager typeManager)
    {
        this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        this.fileFormatDataSourceStats = requireNonNull(fileFormatDataSourceStats, "fileFormatDataSourceStats is null");
        this.orcReaderOptions = orcReaderConfig.toOrcReaderOptions();
        this.parquetReaderOptions = parquetReaderConfig.toParquetReaderOptions();
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    @Override
    public ConnectorPageSource createPageSource(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorSplit connectorSplit,
            ConnectorTableHandle connectorTable,
            List<ColumnHandle> columns,
            DynamicFilter dynamicFilter)
    {
        IcebergSplit split = (IcebergSplit) connectorSplit;
        List<IcebergColumnHandle> icebergColumns = columns.stream()
                .map(IcebergColumnHandle.class::cast)
                .collect(toImmutableList());
        IcebergTableHandle tableHandle = (IcebergTableHandle) connectorTable;
        Schema schema = SchemaParser.fromJson(tableHandle.getTableSchemaJson());
        PartitionSpec partitionSpec = PartitionSpecParser.fromJson(schema, split.getPartitionSpecJson());
        org.apache.iceberg.types.Type[] partitionColumnTypes = partitionSpec.fields().stream()
                .map(field -> field.transform().getResultType(schema.findType(field.sourceId())))
                .toArray(org.apache.iceberg.types.Type[]::new);

        return createPageSource(
                session,
                icebergColumns,
                schema,
                partitionSpec,
                PartitionData.fromJson(split.getPartitionDataJson(), partitionColumnTypes),
                split.getDeletes(),
                dynamicFilter,
                tableHandle.getUnenforcedPredicate(),
                split.getPath(),
                split.getStart(),
                split.getLength(),
                split.getFileSize(),
                split.getPartitionDataJson(),
                split.getFileFormat(),
                tableHandle.getNameMappingJson().map(NameMappingParser::fromJson));
    }

    public ConnectorPageSource createPageSource(
            ConnectorSession session,
            List<IcebergColumnHandle> icebergColumns,
            Schema tableSchema,
            PartitionSpec partitionSpec,
            PartitionData partitionData,
            List<DeleteFile> deletes,
            DynamicFilter dynamicFilter,
            TupleDomain<IcebergColumnHandle> unenforcedPredicate,
            String path,
            long start,
            long length,
            long fileSize,
            String partitionDataJson,
            IcebergFileFormat fileFormat,
            Optional<NameMapping> nameMapping)
    {
        Set<IcebergColumnHandle> deleteFilterRequiredColumns = requiredColumnsForDeletes(tableSchema, deletes);
        Map<Integer, Optional<String>> partitionKeys = getPartitionKeys(partitionData, partitionSpec);

        List<IcebergColumnHandle> requiredColumns = new ArrayList<>(icebergColumns);

        deleteFilterRequiredColumns.stream()
                .filter(not(icebergColumns::contains))
                .forEach(requiredColumns::add);

        icebergColumns.stream()
                .filter(column -> column.isUpdateRowIdColumn() || column.isMergeRowIdColumn())
                .findFirst().ifPresent(rowIdColumn -> {
                    Set<Integer> alreadyRequiredColumnIds = requiredColumns.stream()
                            .map(IcebergColumnHandle::getId)
                            .collect(toImmutableSet());
                    for (ColumnIdentity identity : rowIdColumn.getColumnIdentity().getChildren()) {
                        if (alreadyRequiredColumnIds.contains(identity.getId())) {
                            // ignore
                        }
                        else if (identity.getId() == MetadataColumns.FILE_PATH.fieldId()) {
                            requiredColumns.add(new IcebergColumnHandle(identity, VARCHAR, ImmutableList.of(), VARCHAR, Optional.empty()));
                        }
                        else if (identity.getId() == ROW_POSITION.fieldId()) {
                            requiredColumns.add(new IcebergColumnHandle(identity, BIGINT, ImmutableList.of(), BIGINT, Optional.empty()));
                        }
                        else if (identity.getId() == TRINO_MERGE_PARTITION_SPEC_ID) {
                            requiredColumns.add(new IcebergColumnHandle(identity, INTEGER, ImmutableList.of(), INTEGER, Optional.empty()));
                        }
                        else if (identity.getId() == TRINO_MERGE_PARTITION_DATA) {
                            requiredColumns.add(new IcebergColumnHandle(identity, VARCHAR, ImmutableList.of(), VARCHAR, Optional.empty()));
                        }
                        else {
                            requiredColumns.add(getColumnHandle(tableSchema.findField(identity.getId()), typeManager));
                        }
                    }
                });

        TupleDomain<IcebergColumnHandle> effectivePredicate = unenforcedPredicate
                .intersect(dynamicFilter.getCurrentPredicate().transformKeys(IcebergColumnHandle.class::cast))
                .simplify(ICEBERG_DOMAIN_COMPACTION_THRESHOLD);
        if (effectivePredicate.isNone()) {
            return new EmptyPageSource();
        }

        TrinoFileSystem fileSystem = fileSystemFactory.create(session);
        TrinoInputFile inputfile = isUseFileSizeFromMetadata(session)
                ? fileSystem.newInputFile(Location.of(path), fileSize)
                : fileSystem.newInputFile(Location.of(path));

        ReaderPageSourceWithRowPositions readerPageSourceWithRowPositions = createDataPageSource(
                session,
                inputfile,
                start,
                length,
                fileSize,
                partitionSpec.specId(),
                partitionDataJson,
                fileFormat,
                tableSchema,
                requiredColumns,
                effectivePredicate,
                nameMapping,
                partitionKeys);
        ReaderPageSource dataPageSource = readerPageSourceWithRowPositions.getReaderPageSource();

        Optional<ReaderProjectionsAdapter> projectionsAdapter = dataPageSource.getReaderColumns().map(readerColumns ->
                new ReaderProjectionsAdapter(
                        requiredColumns,
                        readerColumns,
                        column -> ((IcebergColumnHandle) column).getType(),
                        IcebergPageSourceProvider::applyProjection));

        List<IcebergColumnHandle> readColumns = dataPageSource.getReaderColumns()
                .map(readerColumns -> readerColumns.get().stream().map(IcebergColumnHandle.class::cast).collect(toList()))
                .orElse(requiredColumns);

        Supplier<Optional<RowPredicate>> deletePredicate = Suppliers.memoize(() -> {
            List<DeleteFilter> deleteFilters = readDeletes(
                    session,
                    tableSchema,
                    readColumns,
                    path,
                    deletes,
                    readerPageSourceWithRowPositions.getStartRowPosition(),
                    readerPageSourceWithRowPositions.getEndRowPosition());
            return deleteFilters.stream()
                    .map(filter -> filter.createPredicate(readColumns))
                    .reduce(RowPredicate::and);
        });

        return new IcebergPageSource(
                icebergColumns,
                requiredColumns,
                dataPageSource.get(),
                projectionsAdapter,
                deletePredicate);
    }

    private Set<IcebergColumnHandle> requiredColumnsForDeletes(Schema schema, List<DeleteFile> deletes)
    {
        ImmutableSet.Builder<IcebergColumnHandle> requiredColumns = ImmutableSet.builder();
        for (DeleteFile deleteFile : deletes) {
            if (deleteFile.content() == POSITION_DELETES) {
                requiredColumns.add(getColumnHandle(ROW_POSITION, typeManager));
            }
            else if (deleteFile.content() == EQUALITY_DELETES) {
                deleteFile.equalityFieldIds().stream()
                        .map(id -> getColumnHandle(schema.findField(id), typeManager))
                        .forEach(requiredColumns::add);
            }
        }

        return requiredColumns.build();
    }

    private List<DeleteFilter> readDeletes(
            ConnectorSession session,
            Schema schema,
            List<IcebergColumnHandle> readColumns,
            String dataFilePath,
            List<DeleteFile> deleteFiles,
            Optional<Long> startRowPosition,
            Optional<Long> endRowPosition)
    {
        verify(startRowPosition.isPresent() == endRowPosition.isPresent(), "startRowPosition and endRowPosition must be specified together");

        Slice targetPath = utf8Slice(dataFilePath);
        List<DeleteFilter> filters = new ArrayList<>();
        LongBitmapDataProvider deletedRows = new Roaring64Bitmap();
        Map<Set<Integer>, EqualityDeleteSet> deletesSetByFieldIds = new HashMap<>();

        IcebergColumnHandle deleteFilePath = getColumnHandle(DELETE_FILE_PATH, typeManager);
        IcebergColumnHandle deleteFilePos = getColumnHandle(DELETE_FILE_POS, typeManager);
        List<IcebergColumnHandle> deleteColumns = ImmutableList.of(deleteFilePath, deleteFilePos);
        TupleDomain<IcebergColumnHandle> deleteDomain = TupleDomain.fromFixedValues(ImmutableMap.of(deleteFilePath, NullableValue.of(VARCHAR, targetPath)));
        if (startRowPosition.isPresent()) {
            Range positionRange = Range.range(deleteFilePos.getType(), startRowPosition.get(), true, endRowPosition.get(), true);
            TupleDomain<IcebergColumnHandle> positionDomain = TupleDomain.withColumnDomains(ImmutableMap.of(deleteFilePos, Domain.create(ValueSet.ofRanges(positionRange), false)));
            deleteDomain = deleteDomain.intersect(positionDomain);
        }

        for (DeleteFile delete : deleteFiles) {
            if (delete.content() == POSITION_DELETES) {
                if (startRowPosition.isPresent()) {
                    byte[] lowerBoundBytes = delete.getLowerBounds().get(DELETE_FILE_POS.fieldId());
                    Optional<Long> positionLowerBound = Optional.ofNullable(lowerBoundBytes)
                            .map(bytes -> Conversions.fromByteBuffer(DELETE_FILE_POS.type(), ByteBuffer.wrap(bytes)));

                    byte[] upperBoundBytes = delete.getUpperBounds().get(DELETE_FILE_POS.fieldId());
                    Optional<Long> positionUpperBound = Optional.ofNullable(upperBoundBytes)
                            .map(bytes -> Conversions.fromByteBuffer(DELETE_FILE_POS.type(), ByteBuffer.wrap(bytes)));

                    if ((positionLowerBound.isPresent() && positionLowerBound.get() > endRowPosition.get()) ||
                            (positionUpperBound.isPresent() && positionUpperBound.get() < startRowPosition.get())) {
                        continue;
                    }
                }

                try (ConnectorPageSource pageSource = openDeletes(session, delete, deleteColumns, deleteDomain)) {
                    readPositionDeletes(pageSource, targetPath, deletedRows);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            else if (delete.content() == EQUALITY_DELETES) {
                Set<Integer> fieldIds = ImmutableSet.copyOf(delete.equalityFieldIds());
                verify(!fieldIds.isEmpty(), "equality field IDs are missing");
                Schema deleteSchema = TypeUtil.select(schema, fieldIds);
                List<IcebergColumnHandle> columns = deleteSchema.columns().stream()
                        .map(column -> getColumnHandle(column, typeManager))
                        .collect(toImmutableList());

                EqualityDeleteSet equalityDeleteSet = deletesSetByFieldIds.computeIfAbsent(fieldIds, key -> new EqualityDeleteSet(deleteSchema, schemaFromHandles(readColumns)));

                try (ConnectorPageSource pageSource = openDeletes(session, delete, columns, TupleDomain.all())) {
                    readEqualityDeletes(pageSource, columns, equalityDeleteSet::add);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            else {
                throw new VerifyException("Unknown delete content: " + delete.content());
            }
        }

        if (!deletedRows.isEmpty()) {
            filters.add(new PositionDeleteFilter(deletedRows));
        }

        for (EqualityDeleteSet equalityDeleteSet : deletesSetByFieldIds.values()) {
            filters.add(new EqualityDeleteFilter(equalityDeleteSet::contains));
        }

        return filters;
    }

    private ConnectorPageSource openDeletes(
            ConnectorSession session,
            DeleteFile delete,
            List<IcebergColumnHandle> columns,
            TupleDomain<IcebergColumnHandle> tupleDomain)
    {
        TrinoFileSystem fileSystem = fileSystemFactory.create(session);
        return createDataPageSource(
                session,
                fileSystem.newInputFile(Location.of(delete.path()), delete.fileSizeInBytes()),
                0,
                delete.fileSizeInBytes(),
                delete.fileSizeInBytes(),
                0,
                "",
                IcebergFileFormat.fromIceberg(delete.format()),
                schemaFromHandles(columns),
                columns,
                tupleDomain,
                Optional.empty(),
                ImmutableMap.of())
                .getReaderPageSource()
                .get();
    }

    public ReaderPageSourceWithRowPositions createDataPageSource(
            ConnectorSession session,
            TrinoInputFile inputFile,
            long start,
            long length,
            long fileSize,
            int partitionSpecId,
            String partitionData,
            IcebergFileFormat fileFormat,
            Schema fileSchema,
            List<IcebergColumnHandle> dataColumns,
            TupleDomain<IcebergColumnHandle> predicate,
            Optional<NameMapping> nameMapping,
            Map<Integer, Optional<String>> partitionKeys)
    {
        switch (fileFormat) {
            case ORC:
                return createOrcPageSource(
                        inputFile,
                        start,
                        length,
                        partitionSpecId,
                        partitionData,
                        dataColumns,
                        predicate,
                        orcReaderOptions
                                .withMaxMergeDistance(getOrcMaxMergeDistance(session))
                                .withMaxBufferSize(getOrcMaxBufferSize(session))
                                .withStreamBufferSize(getOrcStreamBufferSize(session))
                                .withTinyStripeThreshold(getOrcTinyStripeThreshold(session))
                                .withMaxReadBlockSize(getOrcMaxReadBlockSize(session))
                                .withLazyReadSmallRanges(getOrcLazyReadSmallRanges(session))
                                .withNestedLazy(isOrcNestedLazy(session))
                                .withBloomFiltersEnabled(isOrcBloomFiltersEnabled(session)),
                        fileFormatDataSourceStats,
                        typeManager,
                        nameMapping,
                        partitionKeys);
            case PARQUET:
                return createParquetPageSource(
                        inputFile,
                        start,
                        length,
                        fileSize,
                        partitionSpecId,
                        partitionData,
                        dataColumns,
                        parquetReaderOptions
                                .withMaxReadBlockSize(getParquetMaxReadBlockSize(session))
                                .withMaxReadBlockRowCount(getParquetMaxReadBlockRowCount(session))
                                .withSmallFileThreshold(getParquetSmallFileThreshold(session))
                                .withBloomFilter(useParquetBloomFilter(session)),
                        predicate,
                        fileFormatDataSourceStats,
                        nameMapping,
                        partitionKeys);
            case AVRO:
                return createAvroPageSource(
                        inputFile,
                        start,
                        length,
                        partitionSpecId,
                        partitionData,
                        fileSchema,
                        nameMapping,
                        dataColumns);
            default:
                throw new TrinoException(NOT_SUPPORTED, "File format not supported for Iceberg: " + fileFormat);
        }
    }

    private static ReaderPageSourceWithRowPositions createOrcPageSource(
            TrinoInputFile inputFile,
            long start,
            long length,
            int partitionSpecId,
            String partitionData,
            List<IcebergColumnHandle> columns,
            TupleDomain<IcebergColumnHandle> effectivePredicate,
            OrcReaderOptions options,
            FileFormatDataSourceStats stats,
            TypeManager typeManager,
            Optional<NameMapping> nameMapping,
            Map<Integer, Optional<String>> partitionKeys)
    {
        OrcDataSource orcDataSource = null;
        try {
            orcDataSource = new TrinoOrcDataSource(inputFile, options, stats);

            OrcReader reader = OrcReader.createOrcReader(orcDataSource, options)
                    .orElseThrow(() -> new TrinoException(ICEBERG_BAD_DATA, "ORC file is zero length"));

            List<OrcColumn> fileColumns = reader.getRootColumn().getNestedColumns();
            if (nameMapping.isPresent() && !hasIds(reader.getRootColumn())) {
                fileColumns = fileColumns.stream()
                        .map(orcColumn -> setMissingFieldIds(orcColumn, nameMapping.get(), ImmutableList.of(orcColumn.getColumnName())))
                        .collect(toImmutableList());
            }

            Map<Integer, OrcColumn> fileColumnsByIcebergId = mapIdsToOrcFileColumns(fileColumns);

            TupleDomainOrcPredicateBuilder predicateBuilder = TupleDomainOrcPredicate.builder()
                    .setBloomFiltersEnabled(options.isBloomFiltersEnabled());
            Map<IcebergColumnHandle, Domain> effectivePredicateDomains = effectivePredicate.getDomains()
                    .orElseThrow(() -> new IllegalArgumentException("Effective predicate is none"));

            Optional<ReaderColumns> baseColumnProjections = projectBaseColumns(columns);
            Map<Integer, List<List<Integer>>> projectionsByFieldId = columns.stream()
                    .collect(groupingBy(
                            column -> column.getBaseColumnIdentity().getId(),
                            mapping(IcebergColumnHandle::getPath, toUnmodifiableList())));

            List<IcebergColumnHandle> readBaseColumns = baseColumnProjections
                    .map(readerColumns -> (List<IcebergColumnHandle>) readerColumns.get().stream().map(IcebergColumnHandle.class::cast).collect(toImmutableList()))
                    .orElse(columns);
            List<OrcColumn> fileReadColumns = new ArrayList<>(readBaseColumns.size());
            List<Type> fileReadTypes = new ArrayList<>(readBaseColumns.size());
            List<ProjectedLayout> projectedLayouts = new ArrayList<>(readBaseColumns.size());
            List<ColumnAdaptation> columnAdaptations = new ArrayList<>(readBaseColumns.size());

            for (IcebergColumnHandle column : readBaseColumns) {
                verify(column.isBaseColumn(), "Column projections must be based from a root column");
                OrcColumn orcColumn = fileColumnsByIcebergId.get(column.getId());

                if (column.isIsDeletedColumn()) {
                    columnAdaptations.add(ColumnAdaptation.constantColumn(nativeValueToBlock(BOOLEAN, false)));
                }
                else if (partitionKeys.containsKey(column.getId())) {
                    Type trinoType = column.getType();
                    columnAdaptations.add(ColumnAdaptation.constantColumn(nativeValueToBlock(
                            trinoType,
                            deserializePartitionValue(trinoType, partitionKeys.get(column.getId()).orElse(null), column.getName()))));
                }
                else if (column.isPathColumn()) {
                    columnAdaptations.add(ColumnAdaptation.constantColumn(nativeValueToBlock(FILE_PATH.getType(), utf8Slice(inputFile.location().toString()))));
                }
                else if (column.isFileModifiedTimeColumn()) {
                    columnAdaptations.add(ColumnAdaptation.constantColumn(nativeValueToBlock(FILE_MODIFIED_TIME.getType(), packDateTimeWithZone(inputFile.lastModified().toEpochMilli(), UTC_KEY))));
                }
                else if (column.isUpdateRowIdColumn() || column.isMergeRowIdColumn()) {
                    // $row_id is a composite of multiple physical columns. It is assembled by the IcebergPageSource
                    columnAdaptations.add(ColumnAdaptation.nullColumn(column.getType()));
                }
                else if (column.isRowPositionColumn()) {
                    columnAdaptations.add(ColumnAdaptation.positionColumn());
                }
                else if (column.getId() == TRINO_MERGE_PARTITION_SPEC_ID) {
                    columnAdaptations.add(ColumnAdaptation.constantColumn(nativeValueToBlock(column.getType(), (long) partitionSpecId)));
                }
                else if (column.getId() == TRINO_MERGE_PARTITION_DATA) {
                    columnAdaptations.add(ColumnAdaptation.constantColumn(nativeValueToBlock(column.getType(), utf8Slice(partitionData))));
                }
                else if (orcColumn != null) {
                    Type readType = getOrcReadType(column.getType(), typeManager);

                    if (column.getType() == UUID && !"UUID".equals(orcColumn.getAttributes().get(ICEBERG_BINARY_TYPE))) {
                        throw new TrinoException(ICEBERG_BAD_DATA, format("Expected ORC column for UUID data to be annotated with %s=UUID: %s", ICEBERG_BINARY_TYPE, orcColumn));
                    }

                    List<List<Integer>> fieldIdProjections = projectionsByFieldId.get(column.getId());
                    ProjectedLayout projectedLayout = IcebergOrcProjectedLayout.createProjectedLayout(orcColumn, fieldIdProjections);

                    int sourceIndex = fileReadColumns.size();
                    columnAdaptations.add(ColumnAdaptation.sourceColumn(sourceIndex));
                    fileReadColumns.add(orcColumn);
                    fileReadTypes.add(readType);
                    projectedLayouts.add(projectedLayout);

                    for (Map.Entry<IcebergColumnHandle, Domain> domainEntry : effectivePredicateDomains.entrySet()) {
                        IcebergColumnHandle predicateColumn = domainEntry.getKey();
                        OrcColumn predicateOrcColumn = fileColumnsByIcebergId.get(predicateColumn.getId());
                        if (predicateOrcColumn != null && column.getColumnIdentity().equals(predicateColumn.getBaseColumnIdentity())) {
                            predicateBuilder.addColumn(predicateOrcColumn.getColumnId(), domainEntry.getValue());
                        }
                    }
                }
                else {
                    columnAdaptations.add(ColumnAdaptation.nullColumn(column.getType()));
                }
            }

            AggregatedMemoryContext memoryUsage = newSimpleAggregatedMemoryContext();
            OrcDataSourceId orcDataSourceId = orcDataSource.getId();
            OrcRecordReader recordReader = reader.createRecordReader(
                    fileReadColumns,
                    fileReadTypes,
                    projectedLayouts,
                    predicateBuilder.build(),
                    start,
                    length,
                    UTC,
                    memoryUsage,
                    INITIAL_BATCH_SIZE,
                    exception -> handleException(orcDataSourceId, exception),
                    new IdBasedFieldMapperFactory(readBaseColumns));

            return new ReaderPageSourceWithRowPositions(
                    new ReaderPageSource(
                            new OrcPageSource(
                                    recordReader,
                                    columnAdaptations,
                                    orcDataSource,
                                    Optional.empty(),
                                    Optional.empty(),
                                    memoryUsage,
                                    stats,
                                    reader.getCompressionKind()),
                            baseColumnProjections),
                    recordReader.getStartRowPosition(),
                    recordReader.getEndRowPosition());
        }
        catch (IOException | RuntimeException e) {
            if (orcDataSource != null) {
                try {
                    orcDataSource.close();
                }
                catch (IOException ex) {
                    if (!e.equals(ex)) {
                        e.addSuppressed(ex);
                    }
                }
            }
            if (e instanceof TrinoException) {
                throw (TrinoException) e;
            }
            if (e instanceof OrcCorruptionException) {
                throw new TrinoException(ICEBERG_BAD_DATA, e);
            }
            String message = "Error opening Iceberg split %s (offset=%s, length=%s): %s".formatted(inputFile.location(), start, length, e.getMessage());
            throw new TrinoException(ICEBERG_CANNOT_OPEN_SPLIT, message, e);
        }
    }

    private static boolean hasIds(OrcColumn column)
    {
        if (column.getAttributes().containsKey(ORC_ICEBERG_ID_KEY)) {
            return true;
        }

        return column.getNestedColumns().stream().anyMatch(IcebergPageSourceProvider::hasIds);
    }

    private static OrcColumn setMissingFieldIds(OrcColumn column, NameMapping nameMapping, List<String> qualifiedPath)
    {
        MappedField mappedField = nameMapping.find(qualifiedPath);

        ImmutableMap.Builder<String, String> attributes = ImmutableMap.<String, String>builder()
                .putAll(column.getAttributes());
        if (mappedField != null && mappedField.id() != null) {
            attributes.put(ORC_ICEBERG_ID_KEY, String.valueOf(mappedField.id()));
        }

        return new OrcColumn(
                column.getPath(),
                column.getColumnId(),
                column.getColumnName(),
                column.getColumnType(),
                column.getOrcDataSourceId(),
                column.getNestedColumns().stream()
                        .map(nestedColumn -> {
                            ImmutableList.Builder<String> nextQualifiedPath = ImmutableList.<String>builder()
                                    .addAll(qualifiedPath);
                            if (column.getColumnType() == OrcType.OrcTypeKind.LIST) {
                                // The Trino ORC reader uses "item" for list element names, but the NameMapper expects "element"
                                nextQualifiedPath.add("element");
                            }
                            else {
                                nextQualifiedPath.add(nestedColumn.getColumnName());
                            }
                            return setMissingFieldIds(nestedColumn, nameMapping, nextQualifiedPath.build());
                        })
                        .collect(toImmutableList()),
                attributes.buildOrThrow());
    }

    /**
     * Gets the index based dereference chain to get from the readColumnHandle to the expectedColumnHandle
     */
    private static List<Integer> applyProjection(ColumnHandle expectedColumnHandle, ColumnHandle readColumnHandle)
    {
        IcebergColumnHandle expectedColumn = (IcebergColumnHandle) expectedColumnHandle;
        IcebergColumnHandle readColumn = (IcebergColumnHandle) readColumnHandle;
        checkState(readColumn.isBaseColumn(), "Read column path must be a base column");

        ImmutableList.Builder<Integer> dereferenceChain = ImmutableList.builder();
        ColumnIdentity columnIdentity = readColumn.getColumnIdentity();
        for (Integer fieldId : expectedColumn.getPath()) {
            ColumnIdentity nextChild = columnIdentity.getChildByFieldId(fieldId);
            dereferenceChain.add(columnIdentity.getChildIndexByFieldId(fieldId));
            columnIdentity = nextChild;
        }

        return dereferenceChain.build();
    }

    private static Map<Integer, OrcColumn> mapIdsToOrcFileColumns(List<OrcColumn> columns)
    {
        ImmutableMap.Builder<Integer, OrcColumn> columnsById = ImmutableMap.builder();
        Traverser.forTree(OrcColumn::getNestedColumns)
                .depthFirstPreOrder(columns)
                .forEach(column -> {
                    String fieldId = column.getAttributes().get(ORC_ICEBERG_ID_KEY);
                    if (fieldId != null) {
                        columnsById.put(Integer.parseInt(fieldId), column);
                    }
                });
        return columnsById.buildOrThrow();
    }

    private static Integer getIcebergFieldId(OrcColumn column)
    {
        String icebergId = column.getAttributes().get(ORC_ICEBERG_ID_KEY);
        verify(icebergId != null, format("column %s does not have %s property", column, ORC_ICEBERG_ID_KEY));
        return Integer.valueOf(icebergId);
    }

    private static Type getOrcReadType(Type columnType, TypeManager typeManager)
    {
        if (columnType instanceof ArrayType) {
            return new ArrayType(getOrcReadType(((ArrayType) columnType).getElementType(), typeManager));
        }
        if (columnType instanceof MapType mapType) {
            Type keyType = getOrcReadType(mapType.getKeyType(), typeManager);
            Type valueType = getOrcReadType(mapType.getValueType(), typeManager);
            return new MapType(keyType, valueType, typeManager.getTypeOperators());
        }
        if (columnType instanceof RowType) {
            return RowType.from(((RowType) columnType).getFields().stream()
                    .map(field -> new RowType.Field(field.getName(), getOrcReadType(field.getType(), typeManager)))
                    .collect(toImmutableList()));
        }

        return columnType;
    }

    private static class IdBasedFieldMapperFactory
            implements OrcReader.FieldMapperFactory
    {
        // Stores a mapping between subfield names and ids for every top-level/nested column id
        private final Map<Integer, Map<String, Integer>> fieldNameToIdMappingForTableColumns;

        public IdBasedFieldMapperFactory(List<IcebergColumnHandle> columns)
        {
            requireNonNull(columns, "columns is null");

            ImmutableMap.Builder<Integer, Map<String, Integer>> mapping = ImmutableMap.builder();
            for (IcebergColumnHandle column : columns) {
                if (column.isUpdateRowIdColumn() || column.isMergeRowIdColumn()) {
                    // The update $row_id column contains fields which should not be accounted for in the mapping.
                    continue;
                }

                // Recursively compute subfield name to id mapping for every column
                populateMapping(column.getColumnIdentity(), mapping);
            }

            this.fieldNameToIdMappingForTableColumns = mapping.buildOrThrow();
        }

        @Override
        public OrcReader.FieldMapper create(OrcColumn column)
        {
            Map<Integer, OrcColumn> nestedColumns = uniqueIndex(
                    column.getNestedColumns(),
                    IcebergPageSourceProvider::getIcebergFieldId);

            int icebergId = getIcebergFieldId(column);
            return new IdBasedFieldMapper(nestedColumns, fieldNameToIdMappingForTableColumns.get(icebergId));
        }

        private static void populateMapping(
                ColumnIdentity identity,
                ImmutableMap.Builder<Integer, Map<String, Integer>> fieldNameToIdMappingForTableColumns)
        {
            List<ColumnIdentity> children = identity.getChildren();
            fieldNameToIdMappingForTableColumns.put(
                    identity.getId(),
                    children.stream()
                            // Lower casing is required here because ORC StructColumnReader does the same before mapping
                            .collect(toImmutableMap(child -> child.getName().toLowerCase(ENGLISH), ColumnIdentity::getId)));

            for (ColumnIdentity child : children) {
                populateMapping(child, fieldNameToIdMappingForTableColumns);
            }
        }
    }

    private static class IdBasedFieldMapper
            implements OrcReader.FieldMapper
    {
        private final Map<Integer, OrcColumn> idToColumnMappingForFile;
        private final Map<String, Integer> nameToIdMappingForTableColumns;

        public IdBasedFieldMapper(Map<Integer, OrcColumn> idToColumnMappingForFile, Map<String, Integer> nameToIdMappingForTableColumns)
        {
            this.idToColumnMappingForFile = requireNonNull(idToColumnMappingForFile, "idToColumnMappingForFile is null");
            this.nameToIdMappingForTableColumns = requireNonNull(nameToIdMappingForTableColumns, "nameToIdMappingForTableColumns is null");
        }

        @Override
        public OrcColumn get(String fieldName)
        {
            int fieldId = requireNonNull(
                    nameToIdMappingForTableColumns.get(fieldName),
                    () -> format("Id mapping for field %s not found", fieldName));
            return idToColumnMappingForFile.get(fieldId);
        }
    }

    private static ReaderPageSourceWithRowPositions createParquetPageSource(
            TrinoInputFile inputFile,
            long start,
            long length,
            long fileSize,
            int partitionSpecId,
            String partitionData,
            List<IcebergColumnHandle> regularColumns,
            ParquetReaderOptions options,
            TupleDomain<IcebergColumnHandle> effectivePredicate,
            FileFormatDataSourceStats fileFormatDataSourceStats,
            Optional<NameMapping> nameMapping,
            Map<Integer, Optional<String>> partitionKeys)
    {
        AggregatedMemoryContext memoryContext = newSimpleAggregatedMemoryContext();

        ParquetDataSource dataSource = null;
        try {
            dataSource = createDataSource(inputFile, OptionalLong.of(fileSize), options, memoryContext, fileFormatDataSourceStats);
            ParquetMetadata parquetMetadata = MetadataReader.readFooter(dataSource, Optional.empty());
            FileMetaData fileMetaData = parquetMetadata.getFileMetaData();
            MessageType fileSchema = fileMetaData.getSchema();
            if (nameMapping.isPresent() && !ParquetSchemaUtil.hasIds(fileSchema)) {
                // NameMapping conversion is necessary because MetadataReader converts all column names to lowercase and NameMapping is case sensitive
                fileSchema = ParquetSchemaUtil.applyNameMapping(fileSchema, convertToLowercase(nameMapping.get()));
            }

            // Mapping from Iceberg field ID to Parquet fields.
            Map<Integer, org.apache.parquet.schema.Type> parquetIdToField = createParquetIdToFieldMapping(fileSchema);

            Optional<ReaderColumns> baseColumnProjections = projectBaseColumns(regularColumns);
            List<IcebergColumnHandle> readBaseColumns = baseColumnProjections
                    .map(readerColumns -> (List<IcebergColumnHandle>) readerColumns.get().stream().map(IcebergColumnHandle.class::cast).collect(toImmutableList()))
                    .orElse(regularColumns);

            List<org.apache.parquet.schema.Type> parquetFields = readBaseColumns.stream()
                    .map(column -> parquetIdToField.get(column.getId()))
                    .collect(toList());

            MessageType requestedSchema = getMessageType(regularColumns, fileSchema.getName(), parquetIdToField);
            Map<List<String>, ColumnDescriptor> descriptorsByPath = getDescriptors(fileSchema, requestedSchema);
            TupleDomain<ColumnDescriptor> parquetTupleDomain = getParquetTupleDomain(descriptorsByPath, effectivePredicate);
            TupleDomainParquetPredicate parquetPredicate = buildPredicate(requestedSchema, parquetTupleDomain, descriptorsByPath, UTC);

            long nextStart = 0;
            Optional<Long> startRowPosition = Optional.empty();
            Optional<Long> endRowPosition = Optional.empty();
            ImmutableList.Builder<Long> blockStarts = ImmutableList.builder();
            List<BlockMetaData> blocks = new ArrayList<>();
            for (BlockMetaData block : parquetMetadata.getBlocks()) {
                long firstDataPage = block.getColumns().get(0).getFirstDataPageOffset();
                Optional<BloomFilterStore> bloomFilterStore = getBloomFilterStore(dataSource, block, parquetTupleDomain, options);

                if (start <= firstDataPage && firstDataPage < start + length &&
                        predicateMatches(parquetPredicate, block, dataSource, descriptorsByPath, parquetTupleDomain, Optional.empty(), bloomFilterStore, UTC, ICEBERG_DOMAIN_COMPACTION_THRESHOLD)) {
                    blocks.add(block);
                    blockStarts.add(nextStart);
                    if (startRowPosition.isEmpty()) {
                        startRowPosition = Optional.of(nextStart);
                    }
                    endRowPosition = Optional.of(nextStart + block.getRowCount());
                }
                nextStart += block.getRowCount();
            }

            MessageColumnIO messageColumnIO = getColumnIO(fileSchema, requestedSchema);

            ParquetPageSource.Builder pageSourceBuilder = ParquetPageSource.builder();
            int parquetSourceChannel = 0;

            ImmutableList.Builder<Field> parquetColumnFieldsBuilder = ImmutableList.builder();
            for (int columnIndex = 0; columnIndex < readBaseColumns.size(); columnIndex++) {
                IcebergColumnHandle column = readBaseColumns.get(columnIndex);
                if (column.isIsDeletedColumn()) {
                    pageSourceBuilder.addConstantColumn(nativeValueToBlock(BOOLEAN, false));
                }
                else if (partitionKeys.containsKey(column.getId())) {
                    Type trinoType = column.getType();
                    pageSourceBuilder.addConstantColumn(nativeValueToBlock(
                            trinoType,
                            deserializePartitionValue(trinoType, partitionKeys.get(column.getId()).orElse(null), column.getName())));
                }
                else if (column.isPathColumn()) {
                    pageSourceBuilder.addConstantColumn(nativeValueToBlock(FILE_PATH.getType(), utf8Slice(inputFile.location().toString())));
                }
                else if (column.isFileModifiedTimeColumn()) {
                    pageSourceBuilder.addConstantColumn(nativeValueToBlock(FILE_MODIFIED_TIME.getType(), packDateTimeWithZone(inputFile.lastModified().toEpochMilli(), UTC_KEY)));
                }
                else if (column.isUpdateRowIdColumn() || column.isMergeRowIdColumn()) {
                    // $row_id is a composite of multiple physical columns, it is assembled by the IcebergPageSource
                    pageSourceBuilder.addNullColumn(column.getType());
                }
                else if (column.isRowPositionColumn()) {
                    pageSourceBuilder.addRowIndexColumn();
                }
                else if (column.getId() == TRINO_MERGE_PARTITION_SPEC_ID) {
                    pageSourceBuilder.addConstantColumn(nativeValueToBlock(column.getType(), (long) partitionSpecId));
                }
                else if (column.getId() == TRINO_MERGE_PARTITION_DATA) {
                    pageSourceBuilder.addConstantColumn(nativeValueToBlock(column.getType(), utf8Slice(partitionData)));
                }
                else {
                    org.apache.parquet.schema.Type parquetField = parquetFields.get(columnIndex);
                    Type trinoType = column.getBaseType();
                    if (parquetField == null) {
                        pageSourceBuilder.addNullColumn(trinoType);
                        continue;
                    }
                    // The top level columns are already mapped by name/id appropriately.
                    ColumnIO columnIO = messageColumnIO.getChild(parquetField.getName());
                    Optional<Field> field = IcebergParquetColumnIOConverter.constructField(new FieldContext(trinoType, column.getColumnIdentity()), columnIO);
                    if (field.isEmpty()) {
                        pageSourceBuilder.addNullColumn(trinoType);
                        continue;
                    }
                    parquetColumnFieldsBuilder.add(field.get());
                    pageSourceBuilder.addSourceColumn(parquetSourceChannel);
                    parquetSourceChannel++;
                }
            }

            ParquetDataSourceId dataSourceId = dataSource.getId();
            ParquetReader parquetReader = new ParquetReader(
                    Optional.ofNullable(fileMetaData.getCreatedBy()),
                    parquetColumnFieldsBuilder.build(),
                    blocks,
                    blockStarts.build(),
                    dataSource,
                    UTC,
                    memoryContext,
                    options,
                    exception -> handleException(dataSourceId, exception));
            return new ReaderPageSourceWithRowPositions(
                    new ReaderPageSource(
                            pageSourceBuilder.build(parquetReader),
                            baseColumnProjections),
                    startRowPosition,
                    endRowPosition);
        }
        catch (IOException | RuntimeException e) {
            try {
                if (dataSource != null) {
                    dataSource.close();
                }
            }
            catch (IOException ex) {
                if (!e.equals(ex)) {
                    e.addSuppressed(ex);
                }
            }
            if (e instanceof TrinoException) {
                throw (TrinoException) e;
            }
            if (e instanceof ParquetCorruptionException) {
                throw new TrinoException(ICEBERG_BAD_DATA, e);
            }
            String message = "Error opening Iceberg split %s (offset=%s, length=%s): %s".formatted(inputFile.location(), start, length, e.getMessage());
            throw new TrinoException(ICEBERG_CANNOT_OPEN_SPLIT, message, e);
        }
    }

    private static Map<Integer, org.apache.parquet.schema.Type> createParquetIdToFieldMapping(MessageType fileSchema)
    {
        ImmutableMap.Builder<Integer, org.apache.parquet.schema.Type> builder = ImmutableMap.builder();
        addParquetIdToFieldMapping(fileSchema, builder);
        return builder.buildOrThrow();
    }

    private static void addParquetIdToFieldMapping(org.apache.parquet.schema.Type type, ImmutableMap.Builder<Integer, org.apache.parquet.schema.Type> builder)
    {
        if (type.getId() != null) {
            builder.put(type.getId().intValue(), type);
        }
        if (type instanceof PrimitiveType) {
            // Nothing else to do
        }
        else if (type instanceof GroupType groupType) {
            for (org.apache.parquet.schema.Type field : groupType.getFields()) {
                addParquetIdToFieldMapping(field, builder);
            }
        }
        else {
            throw new IllegalStateException("Unsupported field type: " + type);
        }
    }

    private static MessageType getMessageType(List<IcebergColumnHandle> regularColumns, String fileSchemaName, Map<Integer, org.apache.parquet.schema.Type> parquetIdToField)
    {
        return projectSufficientColumns(regularColumns)
                .map(readerColumns -> readerColumns.get().stream().map(IcebergColumnHandle.class::cast).collect(toUnmodifiableList()))
                .orElse(regularColumns)
                .stream()
                .map(column -> getColumnType(column, parquetIdToField))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(type -> new MessageType(fileSchemaName, type))
                .reduce(MessageType::union)
                .orElse(new MessageType(fileSchemaName, ImmutableList.of()));
    }

    private static ReaderPageSourceWithRowPositions createAvroPageSource(
            TrinoInputFile inputFile,
            long start,
            long length,
            int partitionSpecId,
            String partitionData,
            Schema fileSchema,
            Optional<NameMapping> nameMapping,
            List<IcebergColumnHandle> columns)
    {
        ConstantPopulatingPageSource.Builder constantPopulatingPageSourceBuilder = ConstantPopulatingPageSource.builder();
        int avroSourceChannel = 0;

        Optional<ReaderColumns> baseColumnProjections = projectBaseColumns(columns);

        List<IcebergColumnHandle> readBaseColumns = baseColumnProjections
                .map(readerColumns -> (List<IcebergColumnHandle>) readerColumns.get().stream().map(IcebergColumnHandle.class::cast).collect(toImmutableList()))
                .orElse(columns);

        InputFile file = new ForwardingInputFile(inputFile);
        OptionalLong fileModifiedTime = OptionalLong.empty();
        try {
            if (readBaseColumns.stream().anyMatch(IcebergColumnHandle::isFileModifiedTimeColumn)) {
                fileModifiedTime = OptionalLong.of(inputFile.lastModified().toEpochMilli());
            }
        }
        catch (IOException e) {
            throw new TrinoException(ICEBERG_CANNOT_OPEN_SPLIT, e);
        }

        // The column orders in the generated schema might be different from the original order
        try (DataFileStream<?> avroFileReader = new DataFileStream<>(file.newStream(), new GenericDatumReader<>())) {
            org.apache.avro.Schema avroSchema = avroFileReader.getSchema();
            List<org.apache.avro.Schema.Field> fileFields = avroSchema.getFields();
            if (nameMapping.isPresent() && fileFields.stream().noneMatch(IcebergPageSourceProvider::hasId)) {
                fileFields = fileFields.stream()
                        .map(field -> setMissingFieldId(field, nameMapping.get(), ImmutableList.of(field.name())))
                        .collect(toImmutableList());
            }

            Map<Integer, org.apache.avro.Schema.Field> fileColumnsByIcebergId = mapIdsToAvroFields(fileFields);

            ImmutableList.Builder<String> columnNames = ImmutableList.builder();
            ImmutableList.Builder<Type> columnTypes = ImmutableList.builder();
            ImmutableList.Builder<Boolean> rowIndexChannels = ImmutableList.builder();

            for (IcebergColumnHandle column : readBaseColumns) {
                verify(column.isBaseColumn(), "Column projections must be based from a root column");
                org.apache.avro.Schema.Field field = fileColumnsByIcebergId.get(column.getId());

                if (column.isPathColumn()) {
                    constantPopulatingPageSourceBuilder.addConstantColumn(nativeValueToBlock(FILE_PATH.getType(), utf8Slice(file.location())));
                }
                else if (column.isFileModifiedTimeColumn()) {
                    constantPopulatingPageSourceBuilder.addConstantColumn(nativeValueToBlock(FILE_MODIFIED_TIME.getType(), packDateTimeWithZone(fileModifiedTime.orElseThrow(), UTC_KEY)));
                }
                // For delete
                else if (column.isRowPositionColumn()) {
                    rowIndexChannels.add(true);
                    columnNames.add(ROW_POSITION.name());
                    columnTypes.add(BIGINT);
                    constantPopulatingPageSourceBuilder.addDelegateColumn(avroSourceChannel);
                    avroSourceChannel++;
                }
                else if (column.getId() == TRINO_MERGE_PARTITION_SPEC_ID) {
                    constantPopulatingPageSourceBuilder.addConstantColumn(nativeValueToBlock(column.getType(), (long) partitionSpecId));
                }
                else if (column.getId() == TRINO_MERGE_PARTITION_DATA) {
                    constantPopulatingPageSourceBuilder.addConstantColumn(nativeValueToBlock(column.getType(), utf8Slice(partitionData)));
                }
                else if (field == null) {
                    constantPopulatingPageSourceBuilder.addConstantColumn(nativeValueToBlock(column.getType(), null));
                }
                else {
                    rowIndexChannels.add(false);
                    columnNames.add(column.getName());
                    columnTypes.add(column.getType());
                    constantPopulatingPageSourceBuilder.addDelegateColumn(avroSourceChannel);
                    avroSourceChannel++;
                }
            }

            return new ReaderPageSourceWithRowPositions(
                    new ReaderPageSource(
                            constantPopulatingPageSourceBuilder.build(new IcebergAvroPageSource(
                            file,
                                    start,
                                    length,
                                    fileSchema,
                                    nameMapping,
                                    columnNames.build(),
                                    columnTypes.build(),
                                    rowIndexChannels.build(),
                                    newSimpleAggregatedMemoryContext())),
                            baseColumnProjections),
                    Optional.empty(),
                    Optional.empty());
        }
        catch (IOException e) {
            throw new TrinoException(ICEBERG_CANNOT_OPEN_SPLIT, e);
        }
    }

    private static boolean hasId(org.apache.avro.Schema.Field field)
    {
        return AvroSchemaUtil.hasFieldId(field);
    }

    private static org.apache.avro.Schema.Field setMissingFieldId(org.apache.avro.Schema.Field field, NameMapping nameMapping, List<String> qualifiedPath)
    {
        MappedField mappedField = nameMapping.find(qualifiedPath);

        org.apache.avro.Schema schema = field.schema();
        if (mappedField != null && mappedField.id() != null) {
            field.addProp(AVRO_FIELD_ID, mappedField.id());
        }

        return new org.apache.avro.Schema.Field(field, schema);
    }

    private static Map<Integer, org.apache.avro.Schema.Field> mapIdsToAvroFields(List<org.apache.avro.Schema.Field> fields)
    {
        ImmutableMap.Builder<Integer, org.apache.avro.Schema.Field> fieldsById = ImmutableMap.builder();
        for (org.apache.avro.Schema.Field field : fields) {
            if (AvroSchemaUtil.hasFieldId(field)) {
                fieldsById.put(AvroSchemaUtil.getFieldId(field), field);
            }
        }
        return fieldsById.buildOrThrow();
    }

    /**
     * Create a new NameMapping with the same names but converted to lowercase.
     *
     * @param nameMapping The original NameMapping, potentially containing non-lowercase characters
     */
    private static NameMapping convertToLowercase(NameMapping nameMapping)
    {
        return NameMapping.of(convertToLowercase(nameMapping.asMappedFields().fields()));
    }

    private static MappedFields convertToLowercase(MappedFields mappedFields)
    {
        if (mappedFields == null) {
            return null;
        }
        return MappedFields.of(convertToLowercase(mappedFields.fields()));
    }

    private static List<MappedField> convertToLowercase(List<MappedField> fields)
    {
        return fields.stream()
                .map(mappedField -> {
                    Set<String> lowercaseNames = mappedField.names().stream().map(name -> name.toLowerCase(ENGLISH)).collect(toImmutableSet());
                    return MappedField.of(mappedField.id(), lowercaseNames, convertToLowercase(mappedField.nestedMapping()));
                })
                .collect(toImmutableList());
    }

    private static class IcebergOrcProjectedLayout
            implements ProjectedLayout
    {
        private final Map<Integer, ProjectedLayout> projectedLayoutForFieldId;

        private IcebergOrcProjectedLayout(Map<Integer, ProjectedLayout> projectedLayoutForFieldId)
        {
            this.projectedLayoutForFieldId = ImmutableMap.copyOf(requireNonNull(projectedLayoutForFieldId, "projectedLayoutForFieldId is null"));
        }

        public static ProjectedLayout createProjectedLayout(OrcColumn root, List<List<Integer>> fieldIdDereferences)
        {
            if (fieldIdDereferences.stream().anyMatch(List::isEmpty)) {
                return fullyProjectedLayout();
            }

            Map<Integer, List<List<Integer>>> dereferencesByField = fieldIdDereferences.stream()
                    .collect(groupingBy(
                            sequence -> sequence.get(0),
                            mapping(sequence -> sequence.subList(1, sequence.size()), toUnmodifiableList())));

            ImmutableMap.Builder<Integer, ProjectedLayout> fieldLayouts = ImmutableMap.builder();
            for (OrcColumn nestedColumn : root.getNestedColumns()) {
                Integer fieldId = getIcebergFieldId(nestedColumn);
                if (dereferencesByField.containsKey(fieldId)) {
                    fieldLayouts.put(fieldId, createProjectedLayout(nestedColumn, dereferencesByField.get(fieldId)));
                }
            }

            return new IcebergOrcProjectedLayout(fieldLayouts.buildOrThrow());
        }

        @Override
        public ProjectedLayout getFieldLayout(OrcColumn orcColumn)
        {
            int fieldId = getIcebergFieldId(orcColumn);
            return projectedLayoutForFieldId.getOrDefault(fieldId, fullyProjectedLayout());
        }
    }

    /**
     * Creates a mapping between the input {@code columns} and base columns if required.
     */
    public static Optional<ReaderColumns> projectBaseColumns(List<IcebergColumnHandle> columns)
    {
        requireNonNull(columns, "columns is null");

        // No projection is required if all columns are base columns
        if (columns.stream().allMatch(IcebergColumnHandle::isBaseColumn)) {
            return Optional.empty();
        }

        ImmutableList.Builder<ColumnHandle> projectedColumns = ImmutableList.builder();
        ImmutableList.Builder<Integer> outputColumnMapping = ImmutableList.builder();
        Map<Integer, Integer> mappedFieldIds = new HashMap<>();
        int projectedColumnCount = 0;

        for (IcebergColumnHandle column : columns) {
            int baseColumnId = column.getBaseColumnIdentity().getId();
            Integer mapped = mappedFieldIds.get(baseColumnId);

            if (mapped == null) {
                projectedColumns.add(column.getBaseColumn());
                mappedFieldIds.put(baseColumnId, projectedColumnCount);
                outputColumnMapping.add(projectedColumnCount);
                projectedColumnCount++;
            }
            else {
                outputColumnMapping.add(mapped);
            }
        }

        return Optional.of(new ReaderColumns(projectedColumns.build(), outputColumnMapping.build()));
    }

    /**
     * Creates a set of sufficient columns for the input projected columns and prepares a mapping between the two.
     * For example, if input {@param columns} include columns "a.b" and "a.b.c", then they will be projected
     * from a single column "a.b".
     */
    private static Optional<ReaderColumns> projectSufficientColumns(List<IcebergColumnHandle> columns)
    {
        requireNonNull(columns, "columns is null");

        if (columns.stream().allMatch(IcebergColumnHandle::isBaseColumn)) {
            return Optional.empty();
        }

        ImmutableBiMap.Builder<DereferenceChain, IcebergColumnHandle> dereferenceChainsBuilder = ImmutableBiMap.builder();

        for (IcebergColumnHandle column : columns) {
            DereferenceChain dereferenceChain = new DereferenceChain(column.getBaseColumnIdentity(), column.getPath());
            dereferenceChainsBuilder.put(dereferenceChain, column);
        }

        BiMap<DereferenceChain, IcebergColumnHandle> dereferenceChains = dereferenceChainsBuilder.build();

        List<ColumnHandle> sufficientColumns = new ArrayList<>();
        ImmutableList.Builder<Integer> outputColumnMapping = ImmutableList.builder();

        Map<DereferenceChain, Integer> pickedColumns = new HashMap<>();

        // Pick a covering column for every column
        for (IcebergColumnHandle columnHandle : columns) {
            DereferenceChain dereferenceChain = dereferenceChains.inverse().get(columnHandle);
            DereferenceChain chosenColumn = null;

            // Shortest existing prefix is chosen as the input.
            for (DereferenceChain prefix : dereferenceChain.orderedPrefixes()) {
                if (dereferenceChains.containsKey(prefix)) {
                    chosenColumn = prefix;
                    break;
                }
            }

            checkState(chosenColumn != null, "chosenColumn is null");
            int inputBlockIndex;

            if (pickedColumns.containsKey(chosenColumn)) {
                // Use already picked column
                inputBlockIndex = pickedColumns.get(chosenColumn);
            }
            else {
                // Add a new column for the reader
                sufficientColumns.add(dereferenceChains.get(chosenColumn));
                pickedColumns.put(chosenColumn, sufficientColumns.size() - 1);
                inputBlockIndex = sufficientColumns.size() - 1;
            }

            outputColumnMapping.add(inputBlockIndex);
        }

        return Optional.of(new ReaderColumns(sufficientColumns, outputColumnMapping.build()));
    }

    private static Optional<org.apache.parquet.schema.Type> getColumnType(IcebergColumnHandle column, Map<Integer, org.apache.parquet.schema.Type> parquetIdToField)
    {
        Optional<org.apache.parquet.schema.Type> baseColumnType = Optional.ofNullable(parquetIdToField.get(column.getBaseColumn().getId()));
        if (baseColumnType.isEmpty() || column.getPath().isEmpty()) {
            return baseColumnType;
        }
        GroupType baseType = baseColumnType.get().asGroupType();

        List<org.apache.parquet.schema.Type> subfieldTypes = column.getPath().stream()
                .filter(parquetIdToField::containsKey)
                .map(parquetIdToField::get)
                .collect(toImmutableList());

        // if there is a mismatch between parquet schema and the Iceberg schema the column cannot be dereferenced
        if (subfieldTypes.isEmpty()) {
            return Optional.empty();
        }

        // Construct a stripped version of the original column type containing only the selected field and the hierarchy of its parents
        org.apache.parquet.schema.Type type = subfieldTypes.get(subfieldTypes.size() - 1);
        for (int i = subfieldTypes.size() - 2; i >= 0; --i) {
            GroupType groupType = subfieldTypes.get(i).asGroupType();
            type = new GroupType(groupType.getRepetition(), groupType.getName(), ImmutableList.of(type));
        }
        return Optional.of(new GroupType(baseType.getRepetition(), baseType.getName(), ImmutableList.of(type)));
    }

    private static TupleDomain<ColumnDescriptor> getParquetTupleDomain(Map<List<String>, ColumnDescriptor> descriptorsByPath, TupleDomain<IcebergColumnHandle> effectivePredicate)
    {
        if (effectivePredicate.isNone()) {
            return TupleDomain.none();
        }

        ImmutableMap.Builder<ColumnDescriptor, Domain> predicate = ImmutableMap.builder();
        effectivePredicate.getDomains().orElseThrow().forEach((columnHandle, domain) -> {
            String baseType = columnHandle.getType().getTypeSignature().getBase();
            // skip looking up predicates for complex types as Parquet only stores stats for primitives
            if (columnHandle.isBaseColumn() && (!baseType.equals(StandardTypes.MAP) && !baseType.equals(StandardTypes.ARRAY) && !baseType.equals(StandardTypes.ROW))) {
                ColumnDescriptor descriptor = descriptorsByPath.get(ImmutableList.of(columnHandle.getName()));
                if (descriptor != null) {
                    predicate.put(descriptor, domain);
                }
            }
        });
        return TupleDomain.withColumnDomains(predicate.buildOrThrow());
    }

    private static TrinoException handleException(OrcDataSourceId dataSourceId, Exception exception)
    {
        if (exception instanceof TrinoException) {
            return (TrinoException) exception;
        }
        if (exception instanceof OrcCorruptionException) {
            return new TrinoException(ICEBERG_BAD_DATA, exception);
        }
        return new TrinoException(ICEBERG_CURSOR_ERROR, format("Failed to read ORC file: %s", dataSourceId), exception);
    }

    private static TrinoException handleException(ParquetDataSourceId dataSourceId, Exception exception)
    {
        if (exception instanceof TrinoException) {
            return (TrinoException) exception;
        }
        if (exception instanceof ParquetCorruptionException) {
            return new TrinoException(ICEBERG_BAD_DATA, exception);
        }
        return new TrinoException(ICEBERG_CURSOR_ERROR, format("Failed to read Parquet file: %s", dataSourceId), exception);
    }

    public static final class ReaderPageSourceWithRowPositions
    {
        private final ReaderPageSource readerPageSource;
        private final Optional<Long> startRowPosition;
        private final Optional<Long> endRowPosition;

        public ReaderPageSourceWithRowPositions(
                ReaderPageSource readerPageSource,
                Optional<Long> startRowPosition,
                Optional<Long> endRowPosition)
        {
            this.readerPageSource = requireNonNull(readerPageSource, "readerPageSource is null");
            this.startRowPosition = requireNonNull(startRowPosition, "startRowPosition is null");
            this.endRowPosition = requireNonNull(endRowPosition, "endRowPosition is null");
        }

        public ReaderPageSource getReaderPageSource()
        {
            return readerPageSource;
        }

        public Optional<Long> getStartRowPosition()
        {
            return startRowPosition;
        }

        public Optional<Long> getEndRowPosition()
        {
            return endRowPosition;
        }
    }

    private static class DereferenceChain
    {
        private final ColumnIdentity baseColumnIdentity;
        private final List<Integer> path;

        public DereferenceChain(ColumnIdentity baseColumnIdentity, List<Integer> path)
        {
            this.baseColumnIdentity = requireNonNull(baseColumnIdentity, "baseColumnIdentity is null");
            this.path = ImmutableList.copyOf(requireNonNull(path, "path is null"));
        }

        /**
         * Get prefixes of this Dereference chain in increasing order of lengths.
         */
        public Iterable<DereferenceChain> orderedPrefixes()
        {
            return () -> new AbstractIterator<>()
            {
                private int prefixLength;

                @Override
                public DereferenceChain computeNext()
                {
                    if (prefixLength > path.size()) {
                        return endOfData();
                    }
                    return new DereferenceChain(baseColumnIdentity, path.subList(0, prefixLength++));
                }
            };
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

            DereferenceChain that = (DereferenceChain) o;
            return Objects.equals(baseColumnIdentity, that.baseColumnIdentity) &&
                    Objects.equals(path, that.path);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(baseColumnIdentity, path);
        }
    }

    @NotThreadSafe
    private static class EqualityDeleteSet
    {
        private final StructLikeSet deleteSet;
        private final StructProjection projection;

        public EqualityDeleteSet(Schema deleteSchema, Schema dataSchema)
        {
            this.deleteSet = StructLikeSet.create(deleteSchema.asStruct());
            this.projection = StructProjection.create(dataSchema, deleteSchema);
        }

        public void add(StructLike row)
        {
            deleteSet.add(row);
        }

        public boolean contains(StructLike row)
        {
            return deleteSet.contains(projection.wrap(row));
        }
    }
}
