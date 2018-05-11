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
package com.facebook.presto.hive;

import com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class HiveBasicStatistics
{
    private static final String NUM_FILES = "numFiles";
    private static final String NUM_ROWS = "numRows";
    private static final String RAW_DATA_SIZE = "rawDataSize";
    private static final String TOTAL_SIZE = "totalSize";

    private final OptionalLong fileCount;
    private final OptionalLong rowCount;
    private final OptionalLong inMemoryDataSizeInBytes;
    private final OptionalLong onDiskDataSizeInBytes;

    public static HiveBasicStatistics createEmptyStatistics()
    {
        return new HiveBasicStatistics(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
    }

    public static HiveBasicStatistics createZeroStatistics()
    {
        return new HiveBasicStatistics(0, 0, 0, 0);
    }

    public HiveBasicStatistics(long fileCount, long rowCount, long inMemoryDataSizeInBytes, long onDiskDataSizeInBytes)
    {
        this(OptionalLong.of(fileCount), OptionalLong.of(rowCount), OptionalLong.of(inMemoryDataSizeInBytes), OptionalLong.of(onDiskDataSizeInBytes));
    }

    public HiveBasicStatistics(
            OptionalLong fileCount,
            OptionalLong rowCount,
            OptionalLong inMemoryDataSizeInBytes,
            OptionalLong onDiskDataSizeInBytes)
    {
        this.fileCount = requireNonNull(fileCount, "fileCount is null");
        fileCount.ifPresent(count -> checkArgument(count >= 0, "fileCount is negative: %d", count));
        this.rowCount = requireNonNull(rowCount, "rowCount is null");
        rowCount.ifPresent(count -> checkArgument(count >= 0, "rowCount is negative: %d", count));
        this.inMemoryDataSizeInBytes = requireNonNull(inMemoryDataSizeInBytes, "inMemoryDataSizeInBytes is null");
        inMemoryDataSizeInBytes.ifPresent(size -> checkArgument(size >= 0, "inMemoryDataSizeInBytes is negative: %d", size));
        this.onDiskDataSizeInBytes = requireNonNull(onDiskDataSizeInBytes, "onDiskDataSizeInBytes is null");
        onDiskDataSizeInBytes.ifPresent(size -> checkArgument(size >= 0, "onDiskDataSizeInBytes is negative: %d", size));
    }

    public OptionalLong getFileCount()
    {
        return fileCount;
    }

    public OptionalLong getRowCount()
    {
        return rowCount;
    }

    public OptionalLong getInMemoryDataSizeInBytes()
    {
        return inMemoryDataSizeInBytes;
    }

    public OptionalLong getOnDiskDataSizeInBytes()
    {
        return onDiskDataSizeInBytes;
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
        HiveBasicStatistics that = (HiveBasicStatistics) o;
        return Objects.equals(fileCount, that.fileCount) &&
                Objects.equals(rowCount, that.rowCount) &&
                Objects.equals(inMemoryDataSizeInBytes, that.inMemoryDataSizeInBytes) &&
                Objects.equals(onDiskDataSizeInBytes, that.onDiskDataSizeInBytes);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(fileCount, rowCount, inMemoryDataSizeInBytes, onDiskDataSizeInBytes);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("fileCount", fileCount)
                .add("rowCount", rowCount)
                .add("inMemoryDataSizeInBytes", inMemoryDataSizeInBytes)
                .add("onDiskDataSizeInBytes", onDiskDataSizeInBytes)
                .toString();
    }

    public Map<String, String> toPartitionParameters()
    {
        ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();
        fileCount.ifPresent(count -> properties.put(NUM_FILES, Long.toString(count)));
        rowCount.ifPresent(count -> properties.put(NUM_ROWS, Long.toString(count)));
        inMemoryDataSizeInBytes.ifPresent(size -> properties.put(RAW_DATA_SIZE, Long.toString(size)));
        onDiskDataSizeInBytes.ifPresent(size -> properties.put(TOTAL_SIZE, Long.toString(size)));
        return properties.build();
    }

    public static HiveBasicStatistics createFromPartitionParameters(Map<String, String> parameters)
    {
        OptionalLong numFiles = parse(parameters.get(NUM_FILES));
        OptionalLong numRows = parse(parameters.get(NUM_ROWS));
        OptionalLong inMemoryDataSizeInBytes = parse(parameters.get(RAW_DATA_SIZE));
        OptionalLong onDiskDataSizeInBytes = parse(parameters.get(TOTAL_SIZE));
        return new HiveBasicStatistics(numFiles, numRows, inMemoryDataSizeInBytes, onDiskDataSizeInBytes);
    }

    private static OptionalLong parse(@Nullable String parameterValue)
    {
        if (parameterValue == null) {
            return OptionalLong.empty();
        }
        try {
            long longValue = Long.parseLong(parameterValue);
            if (longValue < 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(longValue);
        }
        catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
