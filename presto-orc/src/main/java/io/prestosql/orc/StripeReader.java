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
package io.prestosql.orc;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import io.airlift.slice.Slice;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.orc.checkpoint.InvalidCheckpointException;
import io.prestosql.orc.checkpoint.StreamCheckpoint;
import io.prestosql.orc.metadata.ColumnEncoding;
import io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind;
import io.prestosql.orc.metadata.ColumnMetadata;
import io.prestosql.orc.metadata.MetadataReader;
import io.prestosql.orc.metadata.OrcColumnId;
import io.prestosql.orc.metadata.OrcType;
import io.prestosql.orc.metadata.OrcType.OrcTypeKind;
import io.prestosql.orc.metadata.PostScript.HiveWriterVersion;
import io.prestosql.orc.metadata.RowGroupIndex;
import io.prestosql.orc.metadata.Stream;
import io.prestosql.orc.metadata.StripeFooter;
import io.prestosql.orc.metadata.StripeInformation;
import io.prestosql.orc.metadata.statistics.BloomFilter;
import io.prestosql.orc.metadata.statistics.ColumnStatistics;
import io.prestosql.orc.stream.InputStreamSource;
import io.prestosql.orc.stream.InputStreamSources;
import io.prestosql.orc.stream.OrcChunkLoader;
import io.prestosql.orc.stream.OrcDataReader;
import io.prestosql.orc.stream.OrcInputStream;
import io.prestosql.orc.stream.ValueInputStream;
import io.prestosql.orc.stream.ValueInputStreamSource;
import io.prestosql.orc.stream.ValueStreams;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.prestosql.orc.checkpoint.Checkpoints.getDictionaryStreamCheckpoint;
import static io.prestosql.orc.checkpoint.Checkpoints.getStreamCheckpoints;
import static io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY;
import static io.prestosql.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY_V2;
import static io.prestosql.orc.metadata.Stream.StreamKind.BLOOM_FILTER;
import static io.prestosql.orc.metadata.Stream.StreamKind.BLOOM_FILTER_UTF8;
import static io.prestosql.orc.metadata.Stream.StreamKind.DICTIONARY_COUNT;
import static io.prestosql.orc.metadata.Stream.StreamKind.DICTIONARY_DATA;
import static io.prestosql.orc.metadata.Stream.StreamKind.LENGTH;
import static io.prestosql.orc.metadata.Stream.StreamKind.ROW_INDEX;
import static io.prestosql.orc.stream.CheckpointInputStreamSource.createCheckpointStreamSource;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class StripeReader
{
    private final OrcDataSource orcDataSource;
    private final ZoneId storageTimeZone;
    private final Optional<OrcDecompressor> decompressor;
    private final ColumnMetadata<OrcType> types;
    private final HiveWriterVersion hiveWriterVersion;
    private final Set<OrcColumnId> includedOrcColumnIds;
    private final int rowsInRowGroup;
    private final OrcPredicate predicate;
    private final MetadataReader metadataReader;
    private final Optional<OrcWriteValidation> writeValidation;

    public StripeReader(
            OrcDataSource orcDataSource,
            ZoneId storageTimeZone,
            Optional<OrcDecompressor> decompressor,
            ColumnMetadata<OrcType> types,
            Set<OrcColumn> readColumns,
            int rowsInRowGroup,
            OrcPredicate predicate,
            HiveWriterVersion hiveWriterVersion,
            MetadataReader metadataReader,
            Optional<OrcWriteValidation> writeValidation)
    {
        this.orcDataSource = requireNonNull(orcDataSource, "orcDataSource is null");
        this.storageTimeZone = requireNonNull(storageTimeZone, "storageTimeZone is null");
        this.decompressor = requireNonNull(decompressor, "decompressor is null");
        this.types = requireNonNull(types, "types is null");
        this.includedOrcColumnIds = getIncludeColumns(requireNonNull(readColumns, "readColumns is null"));
        this.rowsInRowGroup = rowsInRowGroup;
        this.predicate = requireNonNull(predicate, "predicate is null");
        this.hiveWriterVersion = requireNonNull(hiveWriterVersion, "hiveWriterVersion is null");
        this.metadataReader = requireNonNull(metadataReader, "metadataReader is null");
        this.writeValidation = requireNonNull(writeValidation, "writeValidation is null");
    }

    public Stripe readStripe(StripeInformation stripe, AggregatedMemoryContext systemMemoryUsage)
            throws IOException
    {
        // read the stripe footer
        StripeFooter stripeFooter = readStripeFooter(stripe, systemMemoryUsage);
        ColumnMetadata<ColumnEncoding> columnEncodings = stripeFooter.getColumnEncodings();
        if (writeValidation.isPresent()) {
            writeValidation.get().validateTimeZone(orcDataSource.getId(), stripeFooter.getTimeZone().orElse(null));
        }
        ZoneId fileTimeZone = stripeFooter.getTimeZone().orElse(storageTimeZone);

        // get streams for selected columns
        Map<StreamId, Stream> streams = new HashMap<>();
        for (Stream stream : stripeFooter.getStreams()) {
            if (includedOrcColumnIds.contains(stream.getColumnId()) && isSupportedStreamType(stream, types.get(stream.getColumnId()).getOrcTypeKind())) {
                streams.put(new StreamId(stream), stream);
            }
        }

        // handle stripes with more than one row group
        boolean invalidCheckPoint = false;
        if (stripe.getNumberOfRows() > rowsInRowGroup) {
            // determine ranges of the stripe to read
            Map<StreamId, DiskRange> diskRanges = getDiskRanges(stripeFooter.getStreams());
            diskRanges = Maps.filterKeys(diskRanges, Predicates.in(streams.keySet()));

            // read the file regions
            Map<StreamId, OrcChunkLoader> streamsData = readDiskRanges(stripe.getOffset(), diskRanges, systemMemoryUsage);

            // read the bloom filter for each column
            Map<OrcColumnId, List<BloomFilter>> bloomFilterIndexes = readBloomFilterIndexes(streams, streamsData);

            // read the row index for each column
            Map<StreamId, List<RowGroupIndex>> columnIndexes = readColumnIndexes(streams, streamsData, bloomFilterIndexes);
            if (writeValidation.isPresent()) {
                writeValidation.get().validateRowGroupStatistics(orcDataSource.getId(), stripe.getOffset(), columnIndexes);
            }

            // select the row groups matching the tuple domain
            Set<Integer> selectedRowGroups = selectRowGroups(stripe, columnIndexes);

            // if all row groups are skipped, return null
            if (selectedRowGroups.isEmpty()) {
                // set accounted memory usage to zero
                systemMemoryUsage.close();
                return null;
            }

            // value streams
            Map<StreamId, ValueInputStream<?>> valueStreams = createValueStreams(streams, streamsData, columnEncodings);

            // build the dictionary streams
            InputStreamSources dictionaryStreamSources = createDictionaryStreamSources(streams, valueStreams, columnEncodings);

            // build the row groups
            try {
                List<RowGroup> rowGroups = createRowGroups(
                        stripe.getNumberOfRows(),
                        streams,
                        valueStreams,
                        columnIndexes,
                        selectedRowGroups,
                        columnEncodings);

                return new Stripe(stripe.getNumberOfRows(), fileTimeZone, storageTimeZone, columnEncodings, rowGroups, dictionaryStreamSources);
            }
            catch (InvalidCheckpointException e) {
                // The ORC file contains a corrupt checkpoint stream treat the stripe as a single row group.
                invalidCheckPoint = true;
            }
        }

        // stripe only has one row group
        ImmutableMap.Builder<StreamId, DiskRange> diskRangesBuilder = ImmutableMap.builder();
        for (Entry<StreamId, DiskRange> entry : getDiskRanges(stripeFooter.getStreams()).entrySet()) {
            StreamId streamId = entry.getKey();
            if (streams.containsKey(streamId)) {
                diskRangesBuilder.put(entry);
            }
        }
        ImmutableMap<StreamId, DiskRange> diskRanges = diskRangesBuilder.build();

        // read the file regions
        Map<StreamId, OrcChunkLoader> streamsData = readDiskRanges(stripe.getOffset(), diskRanges, systemMemoryUsage);

        long minAverageRowBytes = 0;
        for (Entry<StreamId, Stream> entry : streams.entrySet()) {
            if (entry.getKey().getStreamKind() == ROW_INDEX) {
                List<RowGroupIndex> rowGroupIndexes = metadataReader.readRowIndexes(hiveWriterVersion, new OrcInputStream(streamsData.get(entry.getKey())));
                checkState(rowGroupIndexes.size() == 1 || invalidCheckPoint, "expect a single row group or an invalid check point");
                long totalBytes = 0;
                long totalRows = 0;
                for (RowGroupIndex rowGroupIndex : rowGroupIndexes) {
                    ColumnStatistics columnStatistics = rowGroupIndex.getColumnStatistics();
                    if (columnStatistics.hasMinAverageValueSizeInBytes()) {
                        totalBytes += columnStatistics.getMinAverageValueSizeInBytes() * columnStatistics.getNumberOfValues();
                        totalRows += columnStatistics.getNumberOfValues();
                    }
                }
                if (totalRows > 0) {
                    minAverageRowBytes += totalBytes / totalRows;
                }
            }
        }

        // value streams
        Map<StreamId, ValueInputStream<?>> valueStreams = createValueStreams(streams, streamsData, columnEncodings);

        // build the dictionary streams
        InputStreamSources dictionaryStreamSources = createDictionaryStreamSources(streams, valueStreams, columnEncodings);

        // build the row group
        ImmutableMap.Builder<StreamId, InputStreamSource<?>> builder = ImmutableMap.builder();
        for (Entry<StreamId, ValueInputStream<?>> entry : valueStreams.entrySet()) {
            builder.put(entry.getKey(), new ValueInputStreamSource<>(entry.getValue()));
        }
        RowGroup rowGroup = new RowGroup(0, 0, stripe.getNumberOfRows(), minAverageRowBytes, new InputStreamSources(builder.build()));

        return new Stripe(stripe.getNumberOfRows(), fileTimeZone, storageTimeZone, columnEncodings, ImmutableList.of(rowGroup), dictionaryStreamSources);
    }

    private static boolean isSupportedStreamType(Stream stream, OrcTypeKind orcTypeKind)
    {
        if (stream.getStreamKind() == BLOOM_FILTER) {
            // non-utf8 bloom filters are not allowed for character types
            // non-utf8 bloom filters are not supported for timestamp
            return orcTypeKind != OrcTypeKind.STRING && orcTypeKind != OrcTypeKind.VARCHAR && orcTypeKind != OrcTypeKind.CHAR && orcTypeKind != OrcTypeKind.TIMESTAMP;
        }
        if (stream.getStreamKind() == BLOOM_FILTER_UTF8) {
            // char types require padding for bloom filters, which is not supported
            return orcTypeKind != OrcTypeKind.CHAR;
        }
        return true;
    }

    private Map<StreamId, OrcChunkLoader> readDiskRanges(long stripeOffset, Map<StreamId, DiskRange> diskRanges, AggregatedMemoryContext systemMemoryUsage)
            throws IOException
    {
        //
        // Note: this code does not use the Java 8 stream APIs to avoid any extra object allocation
        //

        // transform ranges to have an absolute offset in file
        ImmutableMap.Builder<StreamId, DiskRange> diskRangesBuilder = ImmutableMap.builder();
        for (Entry<StreamId, DiskRange> entry : diskRanges.entrySet()) {
            DiskRange diskRange = entry.getValue();
            diskRangesBuilder.put(entry.getKey(), new DiskRange(stripeOffset + diskRange.getOffset(), diskRange.getLength()));
        }
        diskRanges = diskRangesBuilder.build();

        // read ranges
        Map<StreamId, OrcDataReader> streamsData = orcDataSource.readFully(diskRanges);

        // transform streams to OrcInputStream
        ImmutableMap.Builder<StreamId, OrcChunkLoader> dataBuilder = ImmutableMap.builder();
        for (Entry<StreamId, OrcDataReader> entry : streamsData.entrySet()) {
            dataBuilder.put(entry.getKey(), OrcChunkLoader.create(entry.getValue(), decompressor, systemMemoryUsage));
        }
        return dataBuilder.build();
    }

    private Map<StreamId, ValueInputStream<?>> createValueStreams(Map<StreamId, Stream> streams, Map<StreamId, OrcChunkLoader> streamsData, ColumnMetadata<ColumnEncoding> columnEncodings)
    {
        ImmutableMap.Builder<StreamId, ValueInputStream<?>> valueStreams = ImmutableMap.builder();
        for (Entry<StreamId, Stream> entry : streams.entrySet()) {
            StreamId streamId = entry.getKey();
            Stream stream = entry.getValue();
            ColumnEncodingKind columnEncoding = columnEncodings.get(stream.getColumnId()).getColumnEncodingKind();

            // skip index and empty streams
            if (isIndexStream(stream) || stream.getLength() == 0) {
                continue;
            }

            OrcChunkLoader chunkLoader = streamsData.get(streamId);
            OrcTypeKind columnType = types.get(stream.getColumnId()).getOrcTypeKind();

            valueStreams.put(streamId, ValueStreams.createValueStreams(streamId, chunkLoader, columnType, columnEncoding));
        }
        return valueStreams.build();
    }

    private InputStreamSources createDictionaryStreamSources(Map<StreamId, Stream> streams, Map<StreamId, ValueInputStream<?>> valueStreams, ColumnMetadata<ColumnEncoding> columnEncodings)
    {
        ImmutableMap.Builder<StreamId, InputStreamSource<?>> dictionaryStreamBuilder = ImmutableMap.builder();
        for (Entry<StreamId, Stream> entry : streams.entrySet()) {
            StreamId streamId = entry.getKey();
            Stream stream = entry.getValue();
            OrcColumnId column = stream.getColumnId();

            // only process dictionary streams
            ColumnEncodingKind columnEncoding = columnEncodings.get(column).getColumnEncodingKind();
            if (!isDictionary(stream, columnEncoding)) {
                continue;
            }

            // skip streams without data
            ValueInputStream<?> valueStream = valueStreams.get(streamId);
            if (valueStream == null) {
                continue;
            }

            OrcTypeKind columnType = types.get(stream.getColumnId()).getOrcTypeKind();
            StreamCheckpoint streamCheckpoint = getDictionaryStreamCheckpoint(streamId, columnType, columnEncoding);

            InputStreamSource<?> streamSource = createCheckpointStreamSource(valueStream, streamCheckpoint);
            dictionaryStreamBuilder.put(streamId, streamSource);
        }
        return new InputStreamSources(dictionaryStreamBuilder.build());
    }

    private List<RowGroup> createRowGroups(
            int rowsInStripe,
            Map<StreamId, Stream> streams,
            Map<StreamId, ValueInputStream<?>> valueStreams,
            Map<StreamId, List<RowGroupIndex>> columnIndexes,
            Set<Integer> selectedRowGroups,
            ColumnMetadata<ColumnEncoding> encodings)
            throws InvalidCheckpointException
    {
        ImmutableList.Builder<RowGroup> rowGroupBuilder = ImmutableList.builder();

        for (int rowGroupId : selectedRowGroups) {
            Map<StreamId, StreamCheckpoint> checkpoints = getStreamCheckpoints(includedOrcColumnIds, types, decompressor.isPresent(), rowGroupId, encodings, streams, columnIndexes);
            int rowOffset = rowGroupId * rowsInRowGroup;
            int rowsInGroup = Math.min(rowsInStripe - rowOffset, rowsInRowGroup);
            long minAverageRowBytes = columnIndexes
                    .entrySet()
                    .stream()
                    .mapToLong(e -> e.getValue()
                            .get(rowGroupId)
                            .getColumnStatistics()
                            .getMinAverageValueSizeInBytes())
                    .sum();
            rowGroupBuilder.add(createRowGroup(rowGroupId, rowOffset, rowsInGroup, minAverageRowBytes, valueStreams, checkpoints));
        }

        return rowGroupBuilder.build();
    }

    private static RowGroup createRowGroup(int groupId, int rowOffset, int rowCount, long minAverageRowBytes, Map<StreamId, ValueInputStream<?>> valueStreams, Map<StreamId, StreamCheckpoint> checkpoints)
    {
        ImmutableMap.Builder<StreamId, InputStreamSource<?>> builder = ImmutableMap.builder();
        for (Entry<StreamId, StreamCheckpoint> entry : checkpoints.entrySet()) {
            StreamId streamId = entry.getKey();
            StreamCheckpoint checkpoint = entry.getValue();

            // skip streams without data
            ValueInputStream<?> valueStream = valueStreams.get(streamId);
            if (valueStream == null) {
                continue;
            }

            builder.put(streamId, createCheckpointStreamSource(valueStream, checkpoint));
        }
        InputStreamSources rowGroupStreams = new InputStreamSources(builder.build());
        return new RowGroup(groupId, rowOffset, rowCount, minAverageRowBytes, rowGroupStreams);
    }

    private StripeFooter readStripeFooter(StripeInformation stripe, AggregatedMemoryContext systemMemoryUsage)
            throws IOException
    {
        long offset = stripe.getOffset() + stripe.getIndexLength() + stripe.getDataLength();
        int tailLength = toIntExact(stripe.getFooterLength());

        // read the footer
        Slice tailBuffer = orcDataSource.readFully(offset, tailLength);
        try (InputStream inputStream = new OrcInputStream(OrcChunkLoader.create(orcDataSource.getId(), tailBuffer, decompressor, systemMemoryUsage))) {
            return metadataReader.readStripeFooter(types, inputStream);
        }
    }

    static boolean isIndexStream(Stream stream)
    {
        return stream.getStreamKind() == ROW_INDEX || stream.getStreamKind() == DICTIONARY_COUNT || stream.getStreamKind() == BLOOM_FILTER || stream.getStreamKind() == BLOOM_FILTER_UTF8;
    }

    private Map<OrcColumnId, List<BloomFilter>> readBloomFilterIndexes(Map<StreamId, Stream> streams, Map<StreamId, OrcChunkLoader> streamsData)
            throws IOException
    {
        HashMap<OrcColumnId, List<BloomFilter>> bloomFilters = new HashMap<>();
        for (Entry<StreamId, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            if (stream.getStreamKind() == BLOOM_FILTER_UTF8) {
                OrcInputStream inputStream = new OrcInputStream(streamsData.get(entry.getKey()));
                bloomFilters.put(stream.getColumnId(), metadataReader.readBloomFilterIndexes(inputStream));
            }
        }
        for (Entry<StreamId, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            if (stream.getStreamKind() == BLOOM_FILTER && !bloomFilters.containsKey(stream.getColumnId())) {
                OrcInputStream inputStream = new OrcInputStream(streamsData.get(entry.getKey()));
                bloomFilters.put(entry.getKey().getColumnId(), metadataReader.readBloomFilterIndexes(inputStream));
            }
        }
        return ImmutableMap.copyOf(bloomFilters);
    }

    private Map<StreamId, List<RowGroupIndex>> readColumnIndexes(Map<StreamId, Stream> streams, Map<StreamId, OrcChunkLoader> streamsData, Map<OrcColumnId, List<BloomFilter>> bloomFilterIndexes)
            throws IOException
    {
        ImmutableMap.Builder<StreamId, List<RowGroupIndex>> columnIndexes = ImmutableMap.builder();
        for (Entry<StreamId, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            if (stream.getStreamKind() == ROW_INDEX) {
                OrcInputStream inputStream = new OrcInputStream(streamsData.get(entry.getKey()));
                List<BloomFilter> bloomFilters = bloomFilterIndexes.get(entry.getKey().getColumnId());
                List<RowGroupIndex> rowGroupIndexes = metadataReader.readRowIndexes(hiveWriterVersion, inputStream);
                if (bloomFilters != null && !bloomFilters.isEmpty()) {
                    ImmutableList.Builder<RowGroupIndex> newRowGroupIndexes = ImmutableList.builder();
                    for (int i = 0; i < rowGroupIndexes.size(); i++) {
                        RowGroupIndex rowGroupIndex = rowGroupIndexes.get(i);
                        ColumnStatistics columnStatistics = rowGroupIndex.getColumnStatistics()
                                .withBloomFilter(bloomFilters.get(i));
                        newRowGroupIndexes.add(new RowGroupIndex(rowGroupIndex.getPositions(), columnStatistics));
                    }
                    rowGroupIndexes = newRowGroupIndexes.build();
                }
                columnIndexes.put(entry.getKey(), rowGroupIndexes);
            }
        }
        return columnIndexes.build();
    }

    private Set<Integer> selectRowGroups(StripeInformation stripe, Map<StreamId, List<RowGroupIndex>> columnIndexes)
    {
        int rowsInStripe = stripe.getNumberOfRows();
        int groupsInStripe = ceil(rowsInStripe, rowsInRowGroup);

        ImmutableSet.Builder<Integer> selectedRowGroups = ImmutableSet.builder();
        int remainingRows = rowsInStripe;
        for (int rowGroup = 0; rowGroup < groupsInStripe; ++rowGroup) {
            int rows = Math.min(remainingRows, rowsInRowGroup);
            ColumnMetadata<ColumnStatistics> statistics = getRowGroupStatistics(types, columnIndexes, rowGroup);
            if (predicate.matches(rows, statistics)) {
                selectedRowGroups.add(rowGroup);
            }
            remainingRows -= rows;
        }
        return selectedRowGroups.build();
    }

    private static ColumnMetadata<ColumnStatistics> getRowGroupStatistics(ColumnMetadata<OrcType> types, Map<StreamId, List<RowGroupIndex>> columnIndexes, int rowGroup)
    {
        requireNonNull(columnIndexes, "columnIndexes is null");
        checkArgument(rowGroup >= 0, "rowGroup is negative");

        Map<Integer, List<RowGroupIndex>> rowGroupIndexesByColumn = columnIndexes.entrySet().stream()
                .collect(toImmutableMap(entry -> entry.getKey().getColumnId().getId(), Entry::getValue));

        List<ColumnStatistics> statistics = new ArrayList<>(types.size());
        for (int columnIndex = 0; columnIndex < types.size(); columnIndex++) {
            List<RowGroupIndex> rowGroupIndexes = rowGroupIndexesByColumn.get(columnIndex);
            if (rowGroupIndexes != null) {
                statistics.add(rowGroupIndexes.get(rowGroup).getColumnStatistics());
            }
            else {
                statistics.add(null);
            }
        }
        return new ColumnMetadata<>(statistics);
    }

    private static boolean isDictionary(Stream stream, ColumnEncodingKind columnEncoding)
    {
        return stream.getStreamKind() == DICTIONARY_DATA || (stream.getStreamKind() == LENGTH && (columnEncoding == DICTIONARY || columnEncoding == DICTIONARY_V2));
    }

    private static Map<StreamId, DiskRange> getDiskRanges(List<Stream> streams)
    {
        ImmutableMap.Builder<StreamId, DiskRange> streamDiskRanges = ImmutableMap.builder();
        long stripeOffset = 0;
        for (Stream stream : streams) {
            int streamLength = stream.getLength();
            // ignore zero byte streams
            if (streamLength > 0) {
                streamDiskRanges.put(new StreamId(stream), new DiskRange(stripeOffset, streamLength));
            }
            stripeOffset += streamLength;
        }
        return streamDiskRanges.build();
    }

    private static Set<OrcColumnId> getIncludeColumns(Set<OrcColumn> includedColumns)
    {
        Set<OrcColumnId> result = new LinkedHashSet<>();
        includeColumnsRecursive(result, includedColumns);
        return result;
    }

    private static void includeColumnsRecursive(Set<OrcColumnId> result, Collection<OrcColumn> readColumns)
    {
        for (OrcColumn column : readColumns) {
            result.add(column.getColumnId());
            includeColumnsRecursive(result, column.getNestedColumns());
        }
    }

    /**
     * Ceiling of integer division
     */
    private static int ceil(int dividend, int divisor)
    {
        return ((dividend + divisor) - 1) / divisor;
    }
}
