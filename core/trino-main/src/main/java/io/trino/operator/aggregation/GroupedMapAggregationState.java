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
package io.trino.operator.aggregation;

import io.trino.spi.block.Block;
import io.trino.spi.block.MapBlockBuilder;
import io.trino.spi.function.GroupedAccumulatorState;
import io.trino.spi.type.Type;

import java.lang.invoke.MethodHandle;

import static java.lang.Math.toIntExact;

public class GroupedMapAggregationState
        extends AbstractMapAggregationState
        implements GroupedAccumulatorState
{
    private int groupId;

    public GroupedMapAggregationState(
            Type keyType,
            MethodHandle keyReadFlat,
            MethodHandle keyWriteFlat,
            MethodHandle hashFlat,
            MethodHandle distinctFlatBlock,
            MethodHandle keyHashBlock,
            Type valueType,
            MethodHandle valueReadFlat,
            MethodHandle valueWriteFlat)
    {
        super(
                keyType,
                keyReadFlat,
                keyWriteFlat,
                hashFlat,
                distinctFlatBlock,
                keyHashBlock,
                valueType,
                valueReadFlat,
                valueWriteFlat,
                true);
    }

    @Override
    public void setGroupId(long groupId)
    {
        this.groupId = toIntExact(groupId);
    }

    @Override
    public void ensureCapacity(long size)
    {
        setMaxGroupId(toIntExact(size));
    }

    @Override
    public void add(Block keyBlock, int keyPosition, Block valueBlock, int valuePosition)
    {
        add(groupId, keyBlock, keyPosition, valueBlock, valuePosition);
    }

    @Override
    public void writeAll(MapBlockBuilder out)
    {
        serialize(groupId, out);
    }
}
