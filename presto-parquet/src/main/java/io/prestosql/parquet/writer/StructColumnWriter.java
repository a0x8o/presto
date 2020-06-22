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
import io.prestosql.parquet.writer.repdef.DefLevelIterable;
import io.prestosql.parquet.writer.repdef.DefLevelIterables;
import io.prestosql.parquet.writer.repdef.RepLevelIterable;
import io.prestosql.parquet.writer.repdef.RepLevelIterables;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.ColumnarRow;

import java.io.IOException;
import java.util.List;

import static io.prestosql.spi.block.ColumnarRow.toColumnarRow;
import static java.util.Objects.requireNonNull;
import static org.apache.parquet.Preconditions.checkArgument;

public class StructColumnWriter
        implements ColumnWriter
{
    private final List<ColumnWriter> columnWriters;
    private final int maxDefinitionLevel;
    private final int maxRepetitionLevel;

    public StructColumnWriter(List<ColumnWriter> columnWriters, int maxDefinitionLevel, int maxRepetitionLevel)
    {
        this.columnWriters = requireNonNull(columnWriters, "columnWriters is null");
        this.maxDefinitionLevel = maxDefinitionLevel;
        this.maxRepetitionLevel = maxRepetitionLevel;
    }

    @Override
    public void writeBlock(ColumnChunk columnChunk)
            throws IOException
    {
        ColumnarRow columnarRow = toColumnarRow(columnChunk.getBlock());
        checkArgument(columnarRow.getFieldCount() == columnWriters.size(), "ColumnarRow field size %s is not equal to columnWriters size %s", columnarRow.getFieldCount(), columnWriters.size());

        List<DefLevelIterable> defLevelIterables = ImmutableList.<DefLevelIterable>builder()
                .addAll(columnChunk.getDefLevelIterables())
                .add(DefLevelIterables.of(columnarRow, maxDefinitionLevel))
                .build();
        List<RepLevelIterable> repLevelIterables = ImmutableList.<RepLevelIterable>builder()
                .addAll(columnChunk.getRepLevelIterables())
                .add(RepLevelIterables.of(columnChunk.getBlock()))
                .build();

        for (int i = 0; i < columnWriters.size(); ++i) {
            ColumnWriter columnWriter = columnWriters.get(i);
            Block block = columnarRow.getField(i);
            columnWriter.writeBlock(new ColumnChunk(block, defLevelIterables, repLevelIterables));
        }
    }

    @Override
    public void close()
    {
        columnWriters.forEach(ColumnWriter::close);
    }

    @Override
    public List<BufferData> getBuffer()
            throws IOException
    {
        ImmutableList.Builder<BufferData> builder = ImmutableList.builder();
        for (ColumnWriter columnWriter : columnWriters) {
            builder.addAll(columnWriter.getBuffer());
        }
        return builder.build();
    }

    @Override
    public long getBufferedBytes()
    {
        return columnWriters.stream().mapToLong(ColumnWriter::getBufferedBytes).sum();
    }

    @Override
    public void reset()
    {
        columnWriters.forEach(ColumnWriter::reset);
    }
}
