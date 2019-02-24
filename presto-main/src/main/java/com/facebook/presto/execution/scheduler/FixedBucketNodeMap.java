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
package com.facebook.presto.execution.scheduler;

import com.facebook.presto.metadata.Split;
import com.facebook.presto.spi.Node;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

import static java.util.Objects.requireNonNull;

// the bucket to node mapping is fixed and pre-assigned
public class FixedBucketNodeMap
        extends BucketNodeMap
{
    private final List<Node> bucketToNode;

    public FixedBucketNodeMap(ToIntFunction<Split> splitToBucket, List<Node> bucketToNode)
    {
        super(splitToBucket);
        this.bucketToNode = ImmutableList.copyOf(requireNonNull(bucketToNode, "bucketToNode is null"));
    }

    @Override
    public Optional<Node> getAssignedNode(int bucketedId)
    {
        return Optional.of(bucketToNode.get(bucketedId));
    }

    @Override
    public int getBucketCount()
    {
        return bucketToNode.size();
    }

    @Override
    public void assignBucketToNode(int bucketedId, Node node)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDynamic()
    {
        return false;
    }
}
