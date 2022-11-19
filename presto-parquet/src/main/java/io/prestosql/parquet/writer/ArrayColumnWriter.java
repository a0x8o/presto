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
import io.prestosql.spi.block.ColumnarArray;

import java.io.IOException;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class ArrayColumnWriter
        implements ColumnWriter
{
    private final ColumnWriter elementWriter;
    private final int maxDefinitionLevel;
    private final int maxRepetitionLevel;

    public ArrayColumnWriter(ColumnWriter elementWriter, int maxDefinitionLevel, int maxRepetitionLevel)
    {
        this.elementWriter = requireNonNull(elementWriter, "elementWriter is null");
        this.maxDefinitionLevel = maxDefinitionLevel;
        this.maxRepetitionLevel = maxRepetitionLevel;
    }

    @Override
    public void writeBlock(ColumnChunk columnChunk)
            throws IOException
    {
        ColumnarArray columnarArray = ColumnarArray.toColumnarArray(columnChunk.getBlock());
        elementWriter.writeBlock(
                new ColumnChunk(columnarArray.getElementsBlock(),
                        ImmutableList.<DefLevelIterable>builder()
                                .addAll(columnChunk.getDefLevelIterables())
                                .add(DefLevelIterables.of(columnarArray, maxDefinitionLevel))
                                .build(),
                        ImmutableList.<RepLevelIterable>builder()
                                .addAll(columnChunk.getRepLevelIterables())
                                .add(RepLevelIterables.of(columnarArray, maxRepetitionLevel))
                                .build()));
    }

    @Override
    public void close()
    {
        elementWriter.close();
    }

    @Override
    public List<BufferData> getBuffer()
            throws IOException
    {
        return ImmutableList.copyOf(elementWriter.getBuffer());
    }

    @Override
    public long getBufferedBytes()
    {
        return elementWriter.getBufferedBytes();
    }

    @Override
    public void reset()
    {
        elementWriter.reset();
    }
}
