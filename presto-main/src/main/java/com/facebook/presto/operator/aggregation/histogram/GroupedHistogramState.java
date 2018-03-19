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

package com.facebook.presto.operator.aggregation.histogram;

import com.facebook.presto.operator.aggregation.state.AbstractGroupedAccumulatorState;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;
import org.openjdk.jol.info.ClassLayout;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * state object that uses a single histogram for all groups. See {@link GroupedTypedHistogram}
 */
public class GroupedHistogramState
        extends AbstractGroupedAccumulatorState
        implements HistogramState
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(GroupedHistogramState.class).instanceSize();
    private TypedHistogram typedHistogram;
    private long size;

    public GroupedHistogramState(Type keyType, int expectedEntriesCount)
    {
        typedHistogram = new GroupedTypedHistogram(keyType, expectedEntriesCount);
    }

    @Override
    public void ensureCapacity(long size)
    {
        typedHistogram.ensureCapacity(size);
    }

    @Override
    public TypedHistogram get()
    {
        return typedHistogram.setGroupId(getGroupId());
    }

    @Override
    public void set(TypedHistogram value)
    {
        checkArgument(value instanceof GroupedTypedHistogram, "only class %s supported, passed %s", GroupedTypedHistogram.class, typedHistogram.getClass());
        // mostly a check to make sure no one breaks things in the constructor since we can't make this final
        requireNonNull(typedHistogram != null, "this.typedHistogram should always be non-null");
        // directly setting means we need to adjust size based on delta, effectively. size shrinks by old, goes up by new
        size -= typedHistogram.getEstimatedSize();
        size += value.getEstimatedSize();
        typedHistogram = value;
    }

    @Override
    public void deserialize(Block block, Type type, int expectedSize)
    {
        typedHistogram = new GroupedTypedHistogram(getGroupId(), block, type, expectedSize);
    }

    @Override
    public void addMemoryUsage(long memory)
    {
        size += memory;
    }

    @Override
    public long getEstimatedSize()
    {
        return INSTANCE_SIZE + size + typedHistogram.getEstimatedSize();
    }
}
