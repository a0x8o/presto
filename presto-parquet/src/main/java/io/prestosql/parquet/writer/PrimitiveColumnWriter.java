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
package io.prestosql.parquet.writer;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.prestosql.parquet.writer.repdef.DefLevelIterable;
import io.prestosql.parquet.writer.repdef.DefLevelIterables;
import io.prestosql.parquet.writer.repdef.RepLevelIterable;
import io.prestosql.parquet.writer.repdef.RepLevelIterables;
import io.prestosql.parquet.writer.valuewriter.PrimitiveValueWriter;
import io.prestosql.spi.type.Type;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.page.DictionaryPage;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.column.values.rle.RunLengthBitPackingHybridEncoder;
import org.apache.parquet.format.ColumnMetaData;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.parquet.writer.ParquetCompressor.getCompressor;
import static io.prestosql.parquet.writer.ParquetDataOutput.createDataOutput;
import static io.prestosql.parquet.writer.repdef.RepLevelIterables.getIterator;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static org.apache.parquet.bytes.BytesInput.copy;

public class PrimitiveColumnWriter
        implements ColumnWriter
{
    private final Type type;
    private final ColumnDescriptor columnDescriptor;
    private final CompressionCodecName compressionCodec;

    private final PrimitiveValueWriter primitiveValueWriter;
    private final RunLengthBitPackingHybridEncoder definitionLevelEncoder;
    private final RunLengthBitPackingHybridEncoder repetitionLevelEncoder;

    private final ParquetMetadataConverter parquetMetadataConverter = new ParquetMetadataConverter();

    private boolean closed;
    private boolean getDataStreamsCalled;

    // current page stats
    private int currentPageRows;
    private int currentPageNullCounts;
    private int currentPageRowCount;

    // column meta data stats
    private final Set<Encoding> encodings;
    private long totalCompressedSize;
    private long totalUnCompressedSize;
    private long totalRows;

    private final int maxDefinitionLevel;

    private final List<ParquetDataOutput> pageBuffer = new ArrayList<>();

    @Nullable
    private final ParquetCompressor compressor;

    private final int pageSizeThreshold;

    public PrimitiveColumnWriter(Type type, ColumnDescriptor columnDescriptor, PrimitiveValueWriter primitiveValueWriter, RunLengthBitPackingHybridEncoder definitionLevelEncoder, RunLengthBitPackingHybridEncoder repetitionLevelEncoder, CompressionCodecName compressionCodecName, int pageSizeThreshold)
    {
        this.type = requireNonNull(type, "type is null");
        this.columnDescriptor = requireNonNull(columnDescriptor, "columnDescriptor is null");
        this.maxDefinitionLevel = columnDescriptor.getMaxDefinitionLevel();

        this.definitionLevelEncoder = requireNonNull(definitionLevelEncoder, "definitionLevelEncoder is null");
        this.repetitionLevelEncoder = requireNonNull(repetitionLevelEncoder, "repetitionLevelEncoder is null");
        this.primitiveValueWriter = requireNonNull(primitiveValueWriter, "primitiveValueWriter is null");
        this.encodings = new HashSet<>();
        this.compressionCodec = requireNonNull(compressionCodecName, "compressionCodecName is null");
        this.compressor = getCompressor(compressionCodecName);
        this.pageSizeThreshold = pageSizeThreshold;
    }

    @Override
    public void writeBlock(ColumnChunk columnChunk)
            throws IOException
    {
        checkState(!closed);

        ColumnChunk current = new ColumnChunk(columnChunk.getBlock(),
                ImmutableList.<DefLevelIterable>builder()
                        .addAll(columnChunk.getDefLevelIterables())
                        .add(DefLevelIterables.of(columnChunk.getBlock(), maxDefinitionLevel))
                        .build(),
                ImmutableList.<RepLevelIterable>builder()
                        .addAll(columnChunk.getRepLevelIterables())
                        .add(RepLevelIterables.of(columnChunk.getBlock()))
                        .build());

        // write values
        primitiveValueWriter.write(columnChunk.getBlock());
        encodings.add(primitiveValueWriter.getEncoding());

        // write definition levels
        Iterator<Integer> defIterator = DefLevelIterables.getIterator(current.getDefLevelIterables());
        while (defIterator.hasNext()) {
            int next = defIterator.next();
            definitionLevelEncoder.writeInt(next);
            if (next != maxDefinitionLevel) {
                currentPageNullCounts++;
            }
            currentPageRows++;
        }

        // write repetition levels
        Iterator<Integer> repIterator = getIterator(current.getRepLevelIterables());
        while (repIterator.hasNext()) {
            int next = repIterator.next();
            repetitionLevelEncoder.writeInt(next);
            if (next == 0) {
                currentPageRowCount++;
            }
        }

        if (getBufferedBytes() >= pageSizeThreshold) {
            flushCurrentPageToBuffer();
        }
    }

    @Override
    public void close()
    {
        closed = true;
    }

    @Override
    public List<BufferData> getBuffer()
            throws IOException
    {
        checkState(closed);
        return ImmutableList.of(new BufferData(getDataStreams(), getColumnMetaData()));
    }

    // Returns ColumnMetaData that offset is invalid
    private ColumnMetaData getColumnMetaData()
    {
        checkState(getDataStreamsCalled);

        return new ColumnMetaData(
                ParquetTypeConverter.getType(columnDescriptor.getPrimitiveType().getPrimitiveTypeName()),
                encodings.stream().map(parquetMetadataConverter::getEncoding).collect(toImmutableList()),
                ImmutableList.copyOf(columnDescriptor.getPath()),
                compressionCodec.getParquetCompressionCodec(),
                totalRows,
                totalUnCompressedSize,
                totalCompressedSize,
                -1);
    }

    // page header
    // repetition levels
    // definition levels
    // data
    private void flushCurrentPageToBuffer()
            throws IOException
    {
        ImmutableList.Builder<ParquetDataOutput> outputDataStreams = ImmutableList.builder();

        BytesInput bytes = primitiveValueWriter.getBytes();
        ParquetDataOutput repetitions = createDataOutput(copy(repetitionLevelEncoder.toBytes()));
        ParquetDataOutput definitions = createDataOutput(copy(definitionLevelEncoder.toBytes()));

        long uncompressedSize = bytes.size() + repetitions.size() + definitions.size();

        ParquetDataOutput data;
        long compressedSize;
        if (compressor != null) {
            data = compressor.compress(bytes);
            compressedSize = data.size() + repetitions.size() + definitions.size();
        }
        else {
            data = createDataOutput(copy(bytes));
            compressedSize = uncompressedSize;
        }

        ByteArrayOutputStream pageHeaderOutputStream = new ByteArrayOutputStream();
        Statistics<?> statistics = primitiveValueWriter.getStatistics();
        statistics.incrementNumNulls(currentPageNullCounts);

        parquetMetadataConverter.writeDataPageV2Header((int) uncompressedSize,
                (int) compressedSize,
                currentPageRows,
                currentPageNullCounts,
                currentPageRowCount,
                statistics,
                primitiveValueWriter.getEncoding(),
                (int) repetitions.size(),
                (int) definitions.size(),
                pageHeaderOutputStream);

        ParquetDataOutput pageHeader = createDataOutput(Slices.wrappedBuffer(pageHeaderOutputStream.toByteArray()));
        outputDataStreams.add(pageHeader);
        outputDataStreams.add(repetitions);
        outputDataStreams.add(definitions);
        outputDataStreams.add(data);

        List<ParquetDataOutput> dataOutputs = outputDataStreams.build();

        // update total stats
        totalCompressedSize += pageHeader.size() + compressedSize;
        totalUnCompressedSize += pageHeader.size() + uncompressedSize;
        totalRows += currentPageRows;

        pageBuffer.addAll(dataOutputs);

        // reset page stats
        currentPageRows = 0;
        currentPageNullCounts = 0;
        currentPageRowCount = 0;

        definitionLevelEncoder.reset();
        repetitionLevelEncoder.reset();
        primitiveValueWriter.reset();
    }

    private List<ParquetDataOutput> getDataStreams()
            throws IOException
    {
        List<ParquetDataOutput> dictPage = new ArrayList<>();
        if (currentPageRows > 0) {
            flushCurrentPageToBuffer();
        }
        // write dict page if possible
        DictionaryPage dictionaryPage = primitiveValueWriter.toDictPageAndClose();
        if (dictionaryPage != null) {
            BytesInput pageBytes = copy(dictionaryPage.getBytes());
            long uncompressedSize = dictionaryPage.getUncompressedSize();

            ParquetDataOutput pageData = createDataOutput(pageBytes);
            if (compressor != null) {
                pageData = compressor.compress(pageBytes);
            }
            long compressedSize = pageData.size();

            ByteArrayOutputStream dictStream = new ByteArrayOutputStream();
            parquetMetadataConverter.writeDictionaryPageHeader(toIntExact(uncompressedSize),
                    toIntExact(compressedSize),
                    dictionaryPage.getDictionarySize(),
                    dictionaryPage.getEncoding(),
                    dictStream);
            ParquetDataOutput pageHeader = createDataOutput(Slices.wrappedBuffer(dictStream.toByteArray()));
            dictPage.add(pageHeader);
            dictPage.add(pageData);
            totalCompressedSize += pageHeader.size() + compressedSize;
            totalUnCompressedSize += pageHeader.size() + uncompressedSize;
        }

        getDataStreamsCalled = true;

        return ImmutableList.<ParquetDataOutput>builder()
                .addAll(dictPage)
                .addAll(pageBuffer)
                .build();
    }

    @Override
    public long getBufferedBytes()
    {
        return pageBuffer.stream().mapToLong(ParquetDataOutput::size).sum() + definitionLevelEncoder.getBufferedSize() + repetitionLevelEncoder.getBufferedSize();
    }

    @Override
    public void reset()
    {
        primitiveValueWriter.reset();
        primitiveValueWriter.resetDictionary();

        pageBuffer.clear();
        closed = false;

        totalCompressedSize = 0;
        totalUnCompressedSize = 0;
        totalRows = 0;
        encodings.clear();

        getDataStreamsCalled = false;
    }
}
