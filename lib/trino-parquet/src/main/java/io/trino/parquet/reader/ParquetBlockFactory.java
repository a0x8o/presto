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
package io.trino.parquet.reader;

import io.trino.spi.block.Block;
import io.trino.spi.block.LazyBlock;
import io.trino.spi.block.LazyBlockLoader;

import java.io.IOException;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class ParquetBlockFactory
{
    private final Function<Exception, RuntimeException> exceptionTransform;
    private int currentPageId;

    public ParquetBlockFactory(Function<Exception, RuntimeException> exceptionTransform)
    {
        this.exceptionTransform = requireNonNull(exceptionTransform, "exceptionTransform is null");
    }

    public void nextPage()
    {
        currentPageId++;
    }

    public Block createBlock(int positionCount, ParquetBlockReader reader)
    {
        return new LazyBlock(positionCount, new ParquetBlockLoader(reader));
    }

    public interface ParquetBlockReader
    {
        Block readBlock()
                throws IOException;
    }

    private final class ParquetBlockLoader
            implements LazyBlockLoader
    {
        private final int expectedPageId = currentPageId;
        private final ParquetBlockReader blockReader;
        private boolean loaded;

        public ParquetBlockLoader(ParquetBlockReader blockReader)
        {
            this.blockReader = requireNonNull(blockReader, "blockReader is null");
        }

        @Override
        public Block load()
        {
            checkState(!loaded, "Already loaded");
            checkState(currentPageId == expectedPageId, "Parquet reader has been advanced beyond block");

            loaded = true;
            try {
                return blockReader.readBlock();
            }
            catch (IOException | RuntimeException e) {
                throw exceptionTransform.apply(e);
            }
        }
    }
}
