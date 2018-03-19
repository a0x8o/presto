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

package com.facebook.presto.hive.statistics;

import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.HivePartition;
import com.facebook.presto.hive.HiveTableHandle;
import com.facebook.presto.hive.PartitionStatistics;
import com.facebook.presto.hive.metastore.HiveColumnStatistics;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.statistics.ColumnStatistics;
import com.facebook.presto.spi.statistics.Estimate;
import com.facebook.presto.spi.statistics.RangeColumnStatistics;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.Decimals;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTimeZone;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import static com.facebook.presto.hive.HiveSessionProperties.isStatisticsEnabled;
import static com.facebook.presto.spi.predicate.Utils.nativeValueToBlock;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class MetastoreHiveStatisticsProvider
        implements HiveStatisticsProvider
{
    private final TypeManager typeManager;
    private final SemiTransactionalHiveMetastore metastore;
    private final DateTimeZone timeZone;

    public MetastoreHiveStatisticsProvider(TypeManager typeManager, SemiTransactionalHiveMetastore metastore, DateTimeZone timeZone)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.metastore = requireNonNull(metastore, "metastore is null");
        this.timeZone = timeZone;
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, List<HivePartition> hivePartitions, Map<String, ColumnHandle> tableColumns)
    {
        if (!isStatisticsEnabled(session)) {
            return TableStatistics.EMPTY_STATISTICS;
        }

        Map<String, PartitionStatistics> partitionStatistics = getPartitionsStatistics((HiveTableHandle) tableHandle, hivePartitions, tableColumns);

        TableStatistics.Builder tableStatistics = TableStatistics.builder();
        Estimate rowCount = calculateRowsCount(partitionStatistics);
        tableStatistics.setRowCount(rowCount);
        for (Map.Entry<String, ColumnHandle> columnEntry : tableColumns.entrySet()) {
            String columnName = columnEntry.getKey();
            HiveColumnHandle hiveColumnHandle = (HiveColumnHandle) columnEntry.getValue();
            RangeColumnStatistics.Builder rangeStatistics = RangeColumnStatistics.builder();

            List<Object> lowValueCandidates = ImmutableList.of();
            List<Object> highValueCandidates = ImmutableList.of();

            Type prestoType = typeManager.getType(hiveColumnHandle.getTypeSignature());
            Estimate nullsFraction;
            if (hiveColumnHandle.isPartitionKey()) {
                rangeStatistics.setDistinctValuesCount(countDistinctPartitionKeys(hiveColumnHandle, hivePartitions));
                nullsFraction = calculateNullsFractionForPartitioningKey(hiveColumnHandle, hivePartitions, partitionStatistics);
                if (isLowHighSupportedForType(prestoType)) {
                    lowValueCandidates = hivePartitions.stream()
                            .map(HivePartition::getKeys)
                            .map(keys -> keys.get(hiveColumnHandle))
                            .filter(value -> !value.isNull())
                            .map(NullableValue::getValue)
                            .collect(toImmutableList());
                    highValueCandidates = lowValueCandidates;
                }
            }
            else {
                rangeStatistics.setDistinctValuesCount(calculateDistinctValuesCount(partitionStatistics, columnName));
                nullsFraction = calculateNullsFraction(partitionStatistics, columnName, rowCount);

                // TODO[lo] Maybe we do not want to expose high/low value if it is based on too small fraction of
                //          partitions. And return unknown if most of the partitions we are working with do not have
                //          statistics computed.

                if (isLowHighSupportedForType(prestoType)) {
                    lowValueCandidates = partitionStatistics.values().stream()
                            .map(PartitionStatistics::getColumnStatistics)
                            .filter(stats -> stats.containsKey(columnName))
                            .map(stats -> stats.get(columnName))
                            .map(HiveColumnStatistics::getLowValue)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(value -> lowHighValueAsPrestoType(value, prestoType))
                            .collect(toImmutableList());

                    highValueCandidates = partitionStatistics.values().stream()
                            .map(PartitionStatistics::getColumnStatistics)
                            .filter(stats -> stats.containsKey(columnName))
                            .map(stats -> stats.get(columnName))
                            .map(HiveColumnStatistics::getHighValue)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(value -> lowHighValueAsPrestoType(value, prestoType))
                            .collect(toImmutableList());
                }
            }
            rangeStatistics.setFraction(nullsFraction.map(value -> 1.0 - value));

            Comparator<Object> comparator = (leftValue, rightValue) -> {
                Block leftBlock = nativeValueToBlock(prestoType, leftValue);
                Block rightBlock = nativeValueToBlock(prestoType, rightValue);
                return prestoType.compareTo(leftBlock, 0, rightBlock, 0);
            };
            rangeStatistics.setLowValue(lowValueCandidates.stream().min(comparator));
            rangeStatistics.setHighValue(highValueCandidates.stream().max(comparator));

            ColumnStatistics.Builder columnStatistics = ColumnStatistics.builder();
            columnStatistics.setNullsFraction(nullsFraction);
            columnStatistics.addRange(rangeStatistics.build());
            tableStatistics.setColumnStatistics(hiveColumnHandle, columnStatistics.build());
        }
        return tableStatistics.build();
    }

    private boolean isLowHighSupportedForType(Type type)
    {
        if (type instanceof DecimalType) {
            return true;
        }
        if (type.equals(TINYINT)
                || type.equals(SMALLINT)
                || type.equals(INTEGER)
                || type.equals(BIGINT)
                || type.equals(REAL)
                || type.equals(DOUBLE)
                || type.equals(DATE)
                || type.equals(TIMESTAMP)) {
            return true;
        }
        return false;
    }

    private Object lowHighValueAsPrestoType(Object value, Type prestoType)
    {
        checkArgument(isLowHighSupportedForType(prestoType), "Unsupported type " + prestoType);
        requireNonNull(value, "high/low value connot be null");

        if (prestoType.equals(BIGINT)
                || prestoType.equals(INTEGER)
                || prestoType.equals(SMALLINT)
                || prestoType.equals(TINYINT)) {
            checkArgument(value instanceof Long, "expected Long value but got " + value.getClass());
            return value;
        }
        else if (prestoType.equals(DOUBLE)) {
            checkArgument(value instanceof Double, "expected Double value but got " + value.getClass());
            return value;
        }
        else if (prestoType.equals(REAL)) {
            checkArgument(value instanceof Double, "expected Double value but got " + value.getClass());
            return floatToRawIntBits((float) (double) value);
        }
        else if (prestoType.equals(DATE)) {
            checkArgument(value instanceof LocalDate, "expected LocalDate value but got " + value.getClass());
            return ((LocalDate) value).toEpochDay();
        }
        else if (prestoType.equals(TIMESTAMP)) {
            checkArgument(value instanceof Long, "expected Long value but got " + value.getClass());
            return timeZone.convertLocalToUTC((long) value * 1000, false);
        }
        else if (prestoType instanceof DecimalType) {
            checkArgument(value instanceof BigDecimal, "expected BigDecimal value but got " + value.getClass());
            BigInteger unscaled = Decimals.rescale((BigDecimal) value, (DecimalType) prestoType).unscaledValue();
            if (Decimals.isShortDecimal(prestoType)) {
                return unscaled.longValueExact();
            }
            else {
                return Decimals.encodeUnscaledValue(unscaled);
            }
        }
        else {
            throw new IllegalArgumentException("Unsupported presto type " + prestoType);
        }
    }

    private Estimate calculateRowsCount(Map<String, PartitionStatistics> partitionStatistics)
    {
        List<Long> knownPartitionRowCounts = partitionStatistics.values().stream()
                .map(PartitionStatistics::getRowCount)
                .filter(OptionalLong::isPresent)
                .map(OptionalLong::getAsLong)
                .collect(toList());

        long knownPartitionRowCountsSum = knownPartitionRowCounts.stream().mapToLong(a -> a).sum();
        long partitionsWithStatsCount = knownPartitionRowCounts.size();
        long allPartitionsCount = partitionStatistics.size();

        if (partitionsWithStatsCount == 0) {
            return Estimate.unknownValue();
        }
        return new Estimate(1.0 * knownPartitionRowCountsSum / partitionsWithStatsCount * allPartitionsCount);
    }

    private Estimate calculateDistinctValuesCount(Map<String, PartitionStatistics> statisticsByPartitionName, String column)
    {
        return summarizePartitionStatistics(
                statisticsByPartitionName.values(),
                column,
                columnStatistics -> {
                    if (columnStatistics.getDistinctValuesCount().isPresent()) {
                        return OptionalDouble.of(columnStatistics.getDistinctValuesCount().getAsLong());
                    }
                    else {
                        return OptionalDouble.empty();
                    }
                },
                DoubleStream::max);
    }

    private Estimate calculateNullsFraction(Map<String, PartitionStatistics> statisticsByPartitionName, String column, Estimate totalRowsCount)
    {
        Estimate totalNullsCount = summarizePartitionStatistics(
                statisticsByPartitionName.values(),
                column,
                columnStatistics -> {
                    if (columnStatistics.getNullsCount().isPresent()) {
                        return OptionalDouble.of(columnStatistics.getNullsCount().getAsLong());
                    }
                    else {
                        return OptionalDouble.empty();
                    }
                },
                nullsCountStream -> {
                    double nullsCount = 0;
                    long partitionsWithStatisticsCount = 0;
                    for (PrimitiveIterator.OfDouble nullsCountIterator = nullsCountStream.iterator(); nullsCountIterator.hasNext(); ) {
                        nullsCount += nullsCountIterator.nextDouble();
                        partitionsWithStatisticsCount++;
                    }

                    if (partitionsWithStatisticsCount == 0) {
                        return OptionalDouble.empty();
                    }
                    else {
                        int allPartitionsCount = statisticsByPartitionName.size();
                        return OptionalDouble.of(allPartitionsCount / partitionsWithStatisticsCount * nullsCount);
                    }
                });

        if (totalNullsCount.isValueUnknown() || totalRowsCount.isValueUnknown()) {
            return Estimate.unknownValue();
        }
        if (totalRowsCount.getValue() == 0.0) {
            return Estimate.zeroValue();
        }

        return new Estimate(totalNullsCount.getValue() / totalRowsCount.getValue());
    }

    private Estimate countDistinctPartitionKeys(HiveColumnHandle partitionColumn, List<HivePartition> partitions)
    {
        return new Estimate(partitions.stream()
                .map(HivePartition::getKeys)
                .map(keys -> keys.get(partitionColumn))
                .distinct()
                .count());
    }

    private Estimate calculateNullsFractionForPartitioningKey(HiveColumnHandle partitionColumn, List<HivePartition> partitions, Map<String, PartitionStatistics> partitionStatistics)
    {
        OptionalDouble rowsPerPartition = partitionStatistics.values().stream()
                .map(PartitionStatistics::getRowCount)
                .filter(OptionalLong::isPresent)
                .mapToLong(OptionalLong::getAsLong)
                .average();

        if (!rowsPerPartition.isPresent()) {
            return Estimate.unknownValue();
        }

        double estimatedTotalRowsCount = rowsPerPartition.getAsDouble() * partitions.size();
        if (estimatedTotalRowsCount == 0.0) {
            return Estimate.zeroValue();
        }
        double estimatedNullsCount = partitions.stream()
                .filter(partition -> partition.getKeys().get(partitionColumn).isNull())
                .map(HivePartition::getPartitionId)
                .mapToLong(partitionId -> partitionStatistics.get(partitionId).getRowCount().orElse((long) rowsPerPartition.getAsDouble()))
                .sum();
        return new Estimate(estimatedNullsCount / estimatedTotalRowsCount);
    }

    private Estimate summarizePartitionStatistics(
            Collection<PartitionStatistics> partitionStatistics,
            String column,
            Function<HiveColumnStatistics, OptionalDouble> valueExtractFunction,
            Function<DoubleStream, OptionalDouble> valueAggregateFunction)
    {
        DoubleStream intermediateStream = partitionStatistics.stream()
                .map(PartitionStatistics::getColumnStatistics)
                .filter(stats -> stats.containsKey(column))
                .map(stats -> stats.get(column))
                .map(valueExtractFunction)
                .filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble);

        OptionalDouble statisticsValue = valueAggregateFunction.apply(intermediateStream);

        if (statisticsValue.isPresent()) {
            return new Estimate(statisticsValue.getAsDouble());
        }
        else {
            return Estimate.unknownValue();
        }
    }

    private Map<String, PartitionStatistics> getPartitionsStatistics(HiveTableHandle tableHandle, List<HivePartition> hivePartitions, Map<String, ColumnHandle> tableColumns)
    {
        if (hivePartitions.isEmpty()) {
            return ImmutableMap.of();
        }
        boolean unpartitioned = hivePartitions.stream().anyMatch(partition -> partition.getPartitionId().equals(HivePartition.UNPARTITIONED_ID));
        if (unpartitioned) {
            checkArgument(hivePartitions.size() == 1, "expected only one hive partition");
        }

        if (unpartitioned) {
            return ImmutableMap.of(HivePartition.UNPARTITIONED_ID, getTableStatistics(tableHandle.getSchemaTableName(), tableColumns.keySet()));
        }
        else {
            return getPartitionsStatistics(tableHandle.getSchemaTableName(), hivePartitions, listNonPartitioningColumns(tableColumns));
        }
    }

    private static Set<String> listNonPartitioningColumns(Map<String, ColumnHandle> tableColumns)
    {
        return tableColumns.entrySet().stream()
                .filter(entry -> !((HiveColumnHandle) entry.getValue()).isPartitionKey())
                .map(Map.Entry::getKey)
                .collect(toImmutableSet());
    }

    private Map<String, PartitionStatistics> getPartitionsStatistics(SchemaTableName schemaTableName, List<HivePartition> hivePartitions, Set<String> tableColumns)
    {
        String databaseName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        ImmutableMap.Builder<String, PartitionStatistics> resultMap = ImmutableMap.builder();

        List<String> partitionNames = hivePartitions.stream().map(HivePartition::getPartitionId).collect(Collectors.toList());
        Map<String, Map<String, HiveColumnStatistics>> partitionColumnStatisticsMap =
                metastore.getPartitionColumnStatistics(databaseName, tableName, new HashSet<>(partitionNames), tableColumns)
                        .orElse(ImmutableMap.of());

        Map<String, Optional<Partition>> partitionsByNames = metastore.getPartitionsByNames(databaseName, tableName, partitionNames);
        for (String partitionName : partitionNames) {
            Map<String, String> partitionParameters = partitionsByNames.get(partitionName)
                    .map(Partition::getParameters)
                    .orElseThrow(() -> new IllegalArgumentException(format("Could not get metadata for partition %s.%s.%s", databaseName, tableName, partitionName)));
            Map<String, HiveColumnStatistics> partitionColumnStatistics = partitionColumnStatisticsMap.getOrDefault(partitionName, ImmutableMap.of());
            resultMap.put(partitionName, readStatisticsFromParameters(partitionParameters, partitionColumnStatistics));
        }

        return resultMap.build();
    }

    private PartitionStatistics getTableStatistics(SchemaTableName schemaTableName, Set<String> tableColumns)
    {
        String databaseName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();
        Table table = metastore.getTable(databaseName, tableName)
                .orElseThrow(() -> new IllegalArgumentException(format("Could not get metadata for table %s.%s", databaseName, tableName)));

        Map<String, HiveColumnStatistics> tableColumnStatistics = metastore.getTableColumnStatistics(databaseName, tableName, tableColumns).orElse(ImmutableMap.of());

        return readStatisticsFromParameters(table.getParameters(), tableColumnStatistics);
    }

    private PartitionStatistics readStatisticsFromParameters(Map<String, String> parameters, Map<String, HiveColumnStatistics> columnStatistics)
    {
        boolean columnStatsAcurate = Boolean.valueOf(Optional.ofNullable(parameters.get("COLUMN_STATS_ACCURATE")).orElse("false"));
        OptionalLong numFiles = convertStringParameter(parameters.get("numFiles"));
        OptionalLong numRows = convertStringParameter(parameters.get("numRows"));
        OptionalLong rawDataSize = convertStringParameter(parameters.get("rawDataSize"));
        OptionalLong totalSize = convertStringParameter(parameters.get("totalSize"));
        return new PartitionStatistics(columnStatsAcurate, numFiles, numRows, rawDataSize, totalSize, columnStatistics);
    }

    private OptionalLong convertStringParameter(@Nullable String parameterValue)
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

    private ColumnMetadata getColumnMetadata(ColumnHandle columnHandle)
    {
        return ((HiveColumnHandle) columnHandle).getColumnMetadata(typeManager);
    }
}
