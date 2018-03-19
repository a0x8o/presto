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

import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.session.PropertyMetadata;
import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;

import javax.inject.Inject;

import java.util.List;

import static com.facebook.presto.spi.session.PropertyMetadata.booleanSessionProperty;
import static com.facebook.presto.spi.session.PropertyMetadata.stringSessionProperty;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static java.util.Locale.ENGLISH;

public final class HiveSessionProperties
{
    private static final String BUCKET_EXECUTION_ENABLED = "bucket_execution_enabled";
    private static final String FORCE_LOCAL_SCHEDULING = "force_local_scheduling";
    private static final String ORC_BLOOM_FILTERS_ENABLED = "orc_bloom_filters_enabled";
    private static final String ORC_MAX_MERGE_DISTANCE = "orc_max_merge_distance";
    private static final String ORC_MAX_BUFFER_SIZE = "orc_max_buffer_size";
    private static final String ORC_STREAM_BUFFER_SIZE = "orc_stream_buffer_size";
    private static final String ORC_MAX_READ_BLOCK_SIZE = "orc_max_read_block_size";
    private static final String ORC_LAZY_READ_SMALL_RANGES = "orc_lazy_read_small_ranges";
    private static final String ORC_STRING_STATISTICS_LIMIT = "orc_string_statistics_limit";
    private static final String ORC_OPTIMIZED_WRITER_ENABLED = "orc_optimized_writer_enabled";
    private static final String ORC_OPTIMIZED_WRITER_VALIDATE = "orc_optimized_writer_validate";
    private static final String ORC_OPTIMIZED_WRITER_MAX_STRIPE_SIZE = "orc_optimized_writer_max_stripe_size";
    private static final String HIVE_STORAGE_FORMAT = "hive_storage_format";
    private static final String RESPECT_TABLE_FORMAT = "respect_table_format";
    private static final String PARQUET_PREDICATE_PUSHDOWN_ENABLED = "parquet_predicate_pushdown_enabled";
    private static final String PARQUET_OPTIMIZED_READER_ENABLED = "parquet_optimized_reader_enabled";
    private static final String MAX_SPLIT_SIZE = "max_split_size";
    private static final String MAX_INITIAL_SPLIT_SIZE = "max_initial_split_size";
    public static final String RCFILE_OPTIMIZED_WRITER_ENABLED = "rcfile_optimized_writer_enabled";
    private static final String RCFILE_OPTIMIZED_WRITER_VALIDATE = "rcfile_optimized_writer_validate";
    private static final String STATISTICS_ENABLED = "statistics_enabled";

    private final List<PropertyMetadata<?>> sessionProperties;

    @Inject
    public HiveSessionProperties(HiveClientConfig hiveClientConfig, OrcFileWriterConfig orcFileWriterConfig)
    {
        sessionProperties = ImmutableList.of(
                booleanSessionProperty(
                        BUCKET_EXECUTION_ENABLED,
                        "Enable bucket-aware execution: only use a single worker per bucket",
                        hiveClientConfig.isBucketExecutionEnabled(),
                        false),
                booleanSessionProperty(
                        FORCE_LOCAL_SCHEDULING,
                        "Only schedule splits on workers colocated with data node",
                        hiveClientConfig.isForceLocalScheduling(),
                        false),
                booleanSessionProperty(
                        ORC_BLOOM_FILTERS_ENABLED,
                        "ORC: Enable bloom filters for predicate pushdown",
                        hiveClientConfig.isOrcBloomFiltersEnabled(),
                        false),
                dataSizeSessionProperty(
                        ORC_MAX_MERGE_DISTANCE,
                        "ORC: Maximum size of gap between two reads to merge into a single read",
                        hiveClientConfig.getOrcMaxMergeDistance(),
                        false),
                dataSizeSessionProperty(
                        ORC_MAX_BUFFER_SIZE,
                        "ORC: Maximum size of a single read",
                        hiveClientConfig.getOrcMaxBufferSize(),
                        false),
                dataSizeSessionProperty(
                        ORC_STREAM_BUFFER_SIZE,
                        "ORC: Size of buffer for streaming reads",
                        hiveClientConfig.getOrcStreamBufferSize(),
                        false),
                dataSizeSessionProperty(
                        ORC_MAX_READ_BLOCK_SIZE,
                        "ORC: Maximum size of a block to read",
                        hiveClientConfig.getOrcMaxReadBlockSize(),
                        false),
                booleanSessionProperty(
                        ORC_LAZY_READ_SMALL_RANGES,
                        "Experimental: ORC: Read small file segments lazily",
                        hiveClientConfig.isOrcLazyReadSmallRanges(),
                        false),
                dataSizeSessionProperty(
                        ORC_STRING_STATISTICS_LIMIT,
                        "ORC: Maximum size of string statistics; drop if exceeding",
                        orcFileWriterConfig.getStringStatisticsLimit(),
                        false),
                booleanSessionProperty(
                        ORC_OPTIMIZED_WRITER_ENABLED,
                        "Experimental: ORC: Enable optimized writer",
                        hiveClientConfig.isOrcOptimizedWriterEnabled(),
                        false),
                booleanSessionProperty(
                        ORC_OPTIMIZED_WRITER_VALIDATE,
                        "Experimental: ORC: Validate writer files",
                        hiveClientConfig.isOrcWriterValidate(),
                        false),
                dataSizeSessionProperty(
                        ORC_OPTIMIZED_WRITER_MAX_STRIPE_SIZE,
                        "Experimental: ORC: Max stripe size",
                        orcFileWriterConfig.getStripeMaxSize(),
                        false),
                stringSessionProperty(
                        HIVE_STORAGE_FORMAT,
                        "Default storage format for new tables or partitions",
                        hiveClientConfig.getHiveStorageFormat().toString(),
                        false),
                booleanSessionProperty(
                        RESPECT_TABLE_FORMAT,
                        "Write new partitions using table format rather than default storage format",
                        hiveClientConfig.isRespectTableFormat(),
                        false),
                booleanSessionProperty(
                        PARQUET_OPTIMIZED_READER_ENABLED,
                        "Experimental: Parquet: Enable optimized reader",
                        hiveClientConfig.isParquetOptimizedReaderEnabled(),
                        false),
                booleanSessionProperty(
                        PARQUET_PREDICATE_PUSHDOWN_ENABLED,
                        "Experimental: Parquet: Enable predicate pushdown for Parquet",
                        hiveClientConfig.isParquetPredicatePushdownEnabled(),
                        false),
                dataSizeSessionProperty(
                        MAX_SPLIT_SIZE,
                        "Max split size",
                        hiveClientConfig.getMaxSplitSize(),
                        true),
                dataSizeSessionProperty(
                        MAX_INITIAL_SPLIT_SIZE,
                        "Max initial split size",
                        hiveClientConfig.getMaxInitialSplitSize(),
                        true),
                booleanSessionProperty(
                        RCFILE_OPTIMIZED_WRITER_ENABLED,
                        "Experimental: RCFile: Enable optimized writer",
                        hiveClientConfig.isRcfileOptimizedWriterEnabled(),
                        false),
                booleanSessionProperty(
                        RCFILE_OPTIMIZED_WRITER_VALIDATE,
                        "Experimental: RCFile: Validate writer files",
                        hiveClientConfig.isRcfileWriterValidate(),
                        false),
                booleanSessionProperty(
                        STATISTICS_ENABLED,
                        "Experimental: Expose table statistics",
                        hiveClientConfig.isTableStatisticsEnabled(),
                        false));
    }

    public List<PropertyMetadata<?>> getSessionProperties()
    {
        return sessionProperties;
    }

    public static boolean isBucketExecutionEnabled(ConnectorSession session)
    {
        return session.getProperty(BUCKET_EXECUTION_ENABLED, Boolean.class);
    }

    public static boolean isForceLocalScheduling(ConnectorSession session)
    {
        return session.getProperty(FORCE_LOCAL_SCHEDULING, Boolean.class);
    }

    public static boolean isParquetOptimizedReaderEnabled(ConnectorSession session)
    {
        return session.getProperty(PARQUET_OPTIMIZED_READER_ENABLED, Boolean.class);
    }

    public static boolean isOrcBloomFiltersEnabled(ConnectorSession session)
    {
        return session.getProperty(ORC_BLOOM_FILTERS_ENABLED, Boolean.class);
    }

    public static DataSize getOrcMaxMergeDistance(ConnectorSession session)
    {
        return session.getProperty(ORC_MAX_MERGE_DISTANCE, DataSize.class);
    }

    public static DataSize getOrcMaxBufferSize(ConnectorSession session)
    {
        return session.getProperty(ORC_MAX_BUFFER_SIZE, DataSize.class);
    }

    public static DataSize getOrcStreamBufferSize(ConnectorSession session)
    {
        return session.getProperty(ORC_STREAM_BUFFER_SIZE, DataSize.class);
    }

    public static DataSize getOrcMaxReadBlockSize(ConnectorSession session)
    {
        return session.getProperty(ORC_MAX_READ_BLOCK_SIZE, DataSize.class);
    }

    public static boolean getOrcLazyReadSmallRanges(ConnectorSession session)
    {
        return session.getProperty(ORC_LAZY_READ_SMALL_RANGES, Boolean.class);
    }

    public static DataSize getOrcStringStatisticsLimit(ConnectorSession session)
    {
        return session.getProperty(ORC_STRING_STATISTICS_LIMIT, DataSize.class);
    }

    public static boolean isOrcOptimizedWriterEnabled(ConnectorSession session)
    {
        return session.getProperty(ORC_OPTIMIZED_WRITER_ENABLED, Boolean.class);
    }

    public static boolean isOrcOptimizedWriterValidate(ConnectorSession session)
    {
        return session.getProperty(ORC_OPTIMIZED_WRITER_VALIDATE, Boolean.class);
    }

    public static DataSize getOrcOptimizedWriterMaxStripeSize(ConnectorSession session)
    {
        return session.getProperty(ORC_OPTIMIZED_WRITER_MAX_STRIPE_SIZE, DataSize.class);
    }

    public static HiveStorageFormat getHiveStorageFormat(ConnectorSession session)
    {
        return HiveStorageFormat.valueOf(session.getProperty(HIVE_STORAGE_FORMAT, String.class).toUpperCase(ENGLISH));
    }

    public static boolean isRespectTableFormat(ConnectorSession session)
    {
        return session.getProperty(RESPECT_TABLE_FORMAT, Boolean.class);
    }

    public static boolean isParquetPredicatePushdownEnabled(ConnectorSession session)
    {
        return session.getProperty(PARQUET_PREDICATE_PUSHDOWN_ENABLED, Boolean.class);
    }

    public static DataSize getMaxSplitSize(ConnectorSession session)
    {
        return session.getProperty(MAX_SPLIT_SIZE, DataSize.class);
    }

    public static DataSize getMaxInitialSplitSize(ConnectorSession session)
    {
        return session.getProperty(MAX_INITIAL_SPLIT_SIZE, DataSize.class);
    }

    public static boolean isRcfileOptimizedWriterEnabled(ConnectorSession session)
    {
        return session.getProperty(RCFILE_OPTIMIZED_WRITER_ENABLED, Boolean.class);
    }

    public static boolean isRcfileOptimizedWriterValidate(ConnectorSession session)
    {
        return session.getProperty(RCFILE_OPTIMIZED_WRITER_VALIDATE, Boolean.class);
    }

    public static boolean isStatisticsEnabled(ConnectorSession session)
    {
        return session.getProperty(STATISTICS_ENABLED, Boolean.class);
    }

    public static PropertyMetadata<DataSize> dataSizeSessionProperty(String name, String description, DataSize defaultValue, boolean hidden)
    {
        return new PropertyMetadata<>(
                name,
                description,
                createUnboundedVarcharType(),
                DataSize.class,
                defaultValue,
                hidden,
                value -> DataSize.valueOf((String) value),
                DataSize::toString);
    }
}
