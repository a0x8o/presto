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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.memory.context.MemoryTrackingContext;
import io.prestosql.operator.BasicWorkProcessorOperatorAdapter.BasicAdapterWorkProcessorOperatorFactory;
import io.prestosql.operator.SetBuilderOperator.SetSupplier;
import io.prestosql.operator.WorkProcessor.TransformationState;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.PlanNodeId;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.concurrent.MoreFutures.checkSuccess;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.prestosql.operator.BasicWorkProcessorOperatorAdapter.createAdapterOperatorFactory;
import static io.prestosql.operator.WorkProcessor.TransformationState.blocked;
import static io.prestosql.operator.WorkProcessor.TransformationState.finished;
import static io.prestosql.operator.WorkProcessor.TransformationState.ofResult;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static java.util.Objects.requireNonNull;

public class HashSemiJoinOperator
        implements WorkProcessorOperator
{
    public static OperatorFactory createOperatorFactory(
            int operatorId,
            PlanNodeId planNodeId,
            SetSupplier setSupplier,
            List<? extends Type> probeTypes,
            int probeJoinChannel,
            Optional<Integer> probeJoinHashChannel)
    {
        return createAdapterOperatorFactory(new Factory(operatorId, planNodeId, setSupplier, probeTypes, probeJoinChannel, probeJoinHashChannel));
    }

    private static class Factory
            implements BasicAdapterWorkProcessorOperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final SetSupplier setSupplier;
        private final List<Type> probeTypes;
        private final int probeJoinChannel;
        private final Optional<Integer> probeJoinHashChannel;
        private boolean closed;

        private Factory(int operatorId, PlanNodeId planNodeId, SetSupplier setSupplier, List<? extends Type> probeTypes, int probeJoinChannel, Optional<Integer> probeJoinHashChannel)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.setSupplier = setSupplier;
            this.probeTypes = ImmutableList.copyOf(probeTypes);
            checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");
            this.probeJoinChannel = probeJoinChannel;
            this.probeJoinHashChannel = probeJoinHashChannel;
        }

        @Override
        public WorkProcessorOperator create(ProcessorContext processorContext, WorkProcessor<Page> sourcePages)
        {
            checkState(!closed, "Factory is already closed");
            return new HashSemiJoinOperator(sourcePages, setSupplier, probeJoinChannel, probeJoinHashChannel, processorContext.getMemoryTrackingContext());
        }

        @Override
        public int getOperatorId()
        {
            return operatorId;
        }

        @Override
        public PlanNodeId getPlanNodeId()
        {
            return planNodeId;
        }

        @Override
        public String getOperatorType()
        {
            return HashSemiJoinOperator.class.getSimpleName();
        }

        @Override
        public void close()
        {
            closed = true;
        }

        @Override
        public Factory duplicate()
        {
            return new Factory(operatorId, planNodeId, setSupplier, probeTypes, probeJoinChannel, probeJoinHashChannel);
        }
    }

    private final WorkProcessor<Page> pages;

    private HashSemiJoinOperator(
            WorkProcessor<Page> sourcePages,
            SetSupplier channelSetFuture,
            int probeJoinChannel,
            Optional<Integer> probeHashChannel,
            MemoryTrackingContext memoryTrackingContext)
    {
        pages = sourcePages
                .transform(new SemiJoinPages(
                        channelSetFuture,
                        probeJoinChannel,
                        probeHashChannel,
                        requireNonNull(memoryTrackingContext, "memoryTrackingContext is null").aggregateUserMemoryContext()));
    }

    @Override
    public WorkProcessor<Page> getOutputPages()
    {
        return pages;
    }

    private static class SemiJoinPages
            implements WorkProcessor.Transformation<Page, Page>
    {
        private final int probeJoinChannel;
        private final ListenableFuture<ChannelSet> channelSetFuture;
        private final Optional<Integer> probeHashChannel;
        private final LocalMemoryContext localMemoryContext;

        @Nullable
        private ChannelSet channelSet;

        public SemiJoinPages(SetSupplier channelSetFuture, int probeJoinChannel, Optional<Integer> probeHashChannel, AggregatedMemoryContext aggregatedMemoryContext)
        {
            checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");

            this.channelSetFuture = requireNonNull(channelSetFuture, "hashProvider is null").getChannelSet();
            this.probeJoinChannel = probeJoinChannel;
            this.probeHashChannel = requireNonNull(probeHashChannel, "hashChannel is null");
            this.localMemoryContext = requireNonNull(aggregatedMemoryContext, "aggregatedMemoryContext is null").newLocalMemoryContext(SemiJoinPages.class.getSimpleName());
        }

        @Override
        public TransformationState<Page> process(Page inputPage)
        {
            if (inputPage == null) {
                return finished();
            }

            if (channelSet == null) {
                if (!channelSetFuture.isDone()) {
                    // This will materialize page but it shouldn't matter for the first page
                    localMemoryContext.setBytes(inputPage.getSizeInBytes());
                    return blocked(channelSetFuture);
                }
                checkSuccess(channelSetFuture, "ChannelSet building failed");
                channelSet = getFutureValue(channelSetFuture);
                localMemoryContext.setBytes(0);
            }

            // create the block builder for the new boolean column
            // we know the exact size required for the block
            BlockBuilder blockBuilder = BOOLEAN.createFixedSizeBlockBuilder(inputPage.getPositionCount());

            Page probeJoinPage = new Page(inputPage.getBlock(probeJoinChannel));
            Optional<Block> hashBlock = probeHashChannel.map(inputPage::getBlock);

            // update hashing strategy to use probe cursor
            for (int position = 0; position < inputPage.getPositionCount(); position++) {
                if (probeJoinPage.getBlock(0).isNull(position)) {
                    if (channelSet.isEmpty()) {
                        BOOLEAN.writeBoolean(blockBuilder, false);
                    }
                    else {
                        blockBuilder.appendNull();
                    }
                }
                else {
                    boolean contains;
                    if (hashBlock.isPresent()) {
                        long rawHash = BIGINT.getLong(hashBlock.get(), position);
                        contains = channelSet.contains(position, probeJoinPage, rawHash);
                    }
                    else {
                        contains = channelSet.contains(position, probeJoinPage);
                    }
                    if (!contains && channelSet.containsNull()) {
                        blockBuilder.appendNull();
                    }
                    else {
                        BOOLEAN.writeBoolean(blockBuilder, contains);
                    }
                }
            }
            // add the new boolean column to the page
            return ofResult(inputPage.appendColumn(blockBuilder.build()));
        }
    }
}
