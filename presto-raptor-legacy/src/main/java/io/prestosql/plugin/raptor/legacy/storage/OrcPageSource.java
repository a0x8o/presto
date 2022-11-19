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
package io.prestosql.plugin.raptor.legacy.storage;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.orc.OrcDataSource;
import io.prestosql.orc.OrcRecordReader;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.connector.UpdatablePageSource;
import io.prestosql.spi.type.Type;

import java.io.IOException;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.plugin.raptor.legacy.RaptorColumnHandle.SHARD_UUID_COLUMN_TYPE;
import static io.prestosql.plugin.raptor.legacy.RaptorErrorCode.RAPTOR_ERROR;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class OrcPageSource
        implements UpdatablePageSource
{
    private final Optional<ShardRewriter> shardRewriter;

    private final OrcRecordReader recordReader;
    private final List<ColumnAdaptation> columnAdaptations;
    private final OrcDataSource orcDataSource;

    private final BitSet rowsToDelete;

    private final AggregatedMemoryContext systemMemoryContext;

    private boolean closed;

    public OrcPageSource(
            Optional<ShardRewriter> shardRewriter,
            OrcRecordReader recordReader,
            List<ColumnAdaptation> columnAdaptations,
            OrcDataSource orcDataSource,
            AggregatedMemoryContext systemMemoryContext)
    {
        this.shardRewriter = requireNonNull(shardRewriter, "shardRewriter is null");
        this.recordReader = requireNonNull(recordReader, "recordReader is null");
        this.columnAdaptations = ImmutableList.copyOf(requireNonNull(columnAdaptations, "columnAdaptations is null"));
        this.orcDataSource = requireNonNull(orcDataSource, "orcDataSource is null");

        this.rowsToDelete = new BitSet(toIntExact(recordReader.getFileRowCount()));

        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }

    @Override
    public long getCompletedBytes()
    {
        return orcDataSource.getReadBytes();
    }

    @Override
    public long getReadTimeNanos()
    {
        return orcDataSource.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return closed;
    }

    @Override
    public Page getNextPage()
    {
        Page page;
        try {
            page = recordReader.nextPage();
        }
        catch (IOException | RuntimeException e) {
            closeWithSuppression(e);
            throw handleException(e);
        }

        if (page == null) {
            close();
            return null;
        }

        long filePosition = recordReader.getFilePosition();
        Block[] blocks = new Block[columnAdaptations.size()];
        for (int i = 0; i < columnAdaptations.size(); i++) {
            blocks[i] = columnAdaptations.get(i).block(page, filePosition);
        }
        return new Page(page.getPositionCount(), blocks);
    }

    static PrestoException handleException(Exception exception)
    {
        if (exception instanceof PrestoException) {
            return (PrestoException) exception;
        }
        throw new PrestoException(RAPTOR_ERROR, exception);
    }

    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;

        try {
            recordReader.close();
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, e);
        }
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("columns", columnAdaptations)
                .toString();
    }

    @Override
    public void deleteRows(Block rowIds)
    {
        for (int i = 0; i < rowIds.getPositionCount(); i++) {
            long rowId = BIGINT.getLong(rowIds, i);
            rowsToDelete.set(toIntExact(rowId));
        }
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        checkState(shardRewriter.isPresent(), "shardRewriter is missing");
        return shardRewriter.get().rewrite(rowsToDelete);
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return systemMemoryContext.getBytes();
    }

    private void closeWithSuppression(Throwable throwable)
    {
        requireNonNull(throwable, "throwable is null");
        try {
            close();
        }
        catch (RuntimeException e) {
            // Self-suppression not permitted
            if (throwable != e) {
                throwable.addSuppressed(e);
            }
        }
    }

    public interface ColumnAdaptation
    {
        Block block(Page sourcePage, long filePosition);

        static ColumnAdaptation nullColumn(Type type)
        {
            return new NullColumn(type);
        }

        static ColumnAdaptation shardUuidColumn(UUID shardUuid)
        {
            return new ShardUuidAdaptation(shardUuid);
        }

        static ColumnAdaptation bucketNumberColumn(OptionalInt bucketNumber)
        {
            if (!bucketNumber.isPresent()) {
                return nullColumn(INTEGER);
            }
            return new BucketNumberColumn(bucketNumber.getAsInt());
        }

        static ColumnAdaptation rowIdColumn()
        {
            return new RowIdColumn();
        }

        static ColumnAdaptation sourceColumn(int index)
        {
            return new SourceColumn(index);
        }
    }

    private static class ShardUuidAdaptation
            implements ColumnAdaptation
    {
        private final Block shardUuidBlock;

        public ShardUuidAdaptation(UUID shardUuid)
        {
            Slice slice = utf8Slice(shardUuid.toString());
            BlockBuilder blockBuilder = SHARD_UUID_COLUMN_TYPE.createBlockBuilder(null, 1, slice.length());
            SHARD_UUID_COLUMN_TYPE.writeSlice(blockBuilder, slice);
            this.shardUuidBlock = blockBuilder.build();
        }

        @Override
        public Block block(Page sourcePage, long filePosition)
        {
            return new RunLengthEncodedBlock(shardUuidBlock, sourcePage.getPositionCount());
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .toString();
        }
    }

    private static class RowIdColumn
            implements ColumnAdaptation
    {
        @Override
        public Block block(Page sourcePage, long filePosition)
        {
            int count = sourcePage.getPositionCount();
            BlockBuilder builder = BIGINT.createFixedSizeBlockBuilder(count);
            for (int i = 0; i < count; i++) {
                BIGINT.writeLong(builder, filePosition + i);
            }
            return builder.build();
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .toString();
        }
    }

    private static class NullColumn
            implements ColumnAdaptation
    {
        private final Type type;
        private final Block nullBlock;

        public NullColumn(Type type)
        {
            this.type = requireNonNull(type, "type is null");
            this.nullBlock = type.createBlockBuilder(null, 1, 0)
                    .appendNull()
                    .build();
        }

        @Override
        public Block block(Page sourcePage, long filePosition)
        {
            return new RunLengthEncodedBlock(nullBlock, sourcePage.getPositionCount());
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("type", type)
                    .toString();
        }
    }

    private static class BucketNumberColumn
            implements ColumnAdaptation
    {
        private final Block bucketNumberBlock;

        public BucketNumberColumn(int bucketNumber)
        {
            BlockBuilder blockBuilder = INTEGER.createFixedSizeBlockBuilder(1);
            INTEGER.writeLong(blockBuilder, bucketNumber);
            this.bucketNumberBlock = blockBuilder.build();
        }

        @Override
        public Block block(Page sourcePage, long filePosition)
        {
            return new RunLengthEncodedBlock(bucketNumberBlock, sourcePage.getPositionCount());
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .toString();
        }
    }

    private static class SourceColumn
            implements ColumnAdaptation
    {
        private final int index;

        public SourceColumn(int index)
        {
            checkArgument(index >= 0, "index is negative");
            this.index = index;
        }

        @Override
        public Block block(Page sourcePage, long filePosition)
        {
            return sourcePage.getBlock(index);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("index", index)
                    .toString();
        }
    }
}
