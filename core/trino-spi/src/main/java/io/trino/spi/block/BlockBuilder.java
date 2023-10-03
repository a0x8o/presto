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
package io.trino.spi.block;

import static io.trino.spi.block.BlockUtil.calculateBlockResetSize;

public interface BlockBuilder
{
    /**
     * Returns the number of positions in this block builder.
     */
    int getPositionCount();

    /**
     * Returns the size of this block as if it was compacted, ignoring any over-allocations
     * and any unloaded nested blocks.
     * For example, in dictionary blocks, this only counts each dictionary entry once,
     * rather than each time a value is referenced.
     */
    long getSizeInBytes();

    /**
     * Returns the retained size of this block in memory, including over-allocations.
     * This method is called from the innermost execution loop and must be fast.
     */
    long getRetainedSizeInBytes();

    /**
     * Appends a null value to the block.
     */
    BlockBuilder appendNull();

    /**
     * Builds the block. This method can be called multiple times.
     */
    Block build();

    /**
     * Creates a new block builder of the same type based on the current usage statistics of this block builder.
     */
    BlockBuilder newBlockBuilderLike(int expectedEntries, BlockBuilderStatus blockBuilderStatus);

    default BlockBuilder newBlockBuilderLike(BlockBuilderStatus blockBuilderStatus)
    {
        return newBlockBuilderLike(calculateBlockResetSize(getPositionCount()), blockBuilderStatus);
    }
}
