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
package io.trino.plugin.hudi.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HudiReplaceCommitMetadata
{
    private final Map<String, List<String>> partitionToReplaceFileIds;
    private final boolean compacted;

    @JsonCreator
    public HudiReplaceCommitMetadata(
            @JsonProperty("partitionToReplaceFileIds") Map<String, List<String>> partitionToReplaceFileIds,
            @JsonProperty("compacted") boolean compacted)
    {
        this.partitionToReplaceFileIds = ImmutableMap.copyOf(requireNonNull(partitionToReplaceFileIds, "partitionToReplaceFileIds is null"));
        this.compacted = compacted;
    }

    public Map<String, List<String>> getPartitionToReplaceFileIds()
    {
        return partitionToReplaceFileIds;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        HudiReplaceCommitMetadata that = (HudiReplaceCommitMetadata) o;

        return partitionToReplaceFileIds.equals(that.partitionToReplaceFileIds) &&
               compacted == that.compacted;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(partitionToReplaceFileIds, compacted);
    }

    public static <T> T fromBytes(byte[] bytes, ObjectMapper objectMapper, Class<T> clazz)
            throws IOException
    {
        try {
            String jsonStr = new String(bytes, StandardCharsets.UTF_8);
            if (jsonStr == null || jsonStr.isEmpty()) {
                return clazz.getConstructor().newInstance();
            }
            return objectMapper.readValue(jsonStr, clazz);
        }
        catch (Exception e) {
            throw new IOException("unable to read commit metadata", e);
        }
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("partitionToReplaceFileIds", partitionToReplaceFileIds)
                .add("compacted", compacted)
                .toString();
    }
}
