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
package com.facebook.presto.tpch;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.ConnectorTablePartitioning;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.SortingProperty;
import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.statistics.ColumnStatistics;
import com.facebook.presto.spi.statistics.Estimate;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.VarcharType;
import com.facebook.presto.tpch.statistics.ColumnStatisticsData;
import com.facebook.presto.tpch.statistics.StatisticsEstimator;
import com.facebook.presto.tpch.statistics.TableStatisticsData;
import com.facebook.presto.tpch.statistics.TableStatisticsDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.tpch.Distributions;
import io.airlift.tpch.LineItemColumn;
import io.airlift.tpch.OrderColumn;
import io.airlift.tpch.OrderGenerator;
import io.airlift.tpch.PartColumn;
import io.airlift.tpch.TpchColumn;
import io.airlift.tpch.TpchColumnType;
import io.airlift.tpch.TpchEntity;
import io.airlift.tpch.TpchTable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.VarcharType.createVarcharType;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.asMap;
import static io.airlift.tpch.OrderColumn.ORDER_STATUS;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class TpchMetadata
        implements ConnectorMetadata
{
    public static final String TINY_SCHEMA_NAME = "tiny";
    public static final double TINY_SCALE_FACTOR = 0.01;

    public static final List<String> SCHEMA_NAMES = ImmutableList.of(
            TINY_SCHEMA_NAME, "sf1", "sf100", "sf300", "sf1000", "sf3000", "sf10000", "sf30000", "sf100000");

    public static final String ROW_NUMBER_COLUMN_NAME = "row_number";

    private static final Set<Slice> ORDER_STATUS_VALUES = ImmutableSet.of("F", "O", "P").stream()
            .map(Slices::utf8Slice)
            .collect(toImmutableSet());
    private static final Set<NullableValue> ORDER_STATUS_NULLABLE_VALUES = ORDER_STATUS_VALUES.stream()
            .map(value -> new NullableValue(getPrestoType(OrderColumn.ORDER_STATUS), value))
            .collect(toSet());

    private static final Set<Slice> PART_TYPE_VALUES = Distributions.getDefaultDistributions().getPartTypes().getValues().stream()
            .map(Slices::utf8Slice)
            .collect(toImmutableSet());
    private static final Set<NullableValue> PART_TYPE_NULLABLE_VALUES = PART_TYPE_VALUES.stream()
            .map(value -> new NullableValue(getPrestoType(PartColumn.TYPE), value))
            .collect(toSet());

    private static final Set<Slice> PART_CONTAINER_VALUES = Distributions.getDefaultDistributions().getPartContainers().getValues().stream()
            .map(Slices::utf8Slice)
            .collect(toImmutableSet());
    private static final Set<NullableValue> PART_CONTAINER_NULLABLE_VALUES = PART_CONTAINER_VALUES.stream()
            .map(value -> new NullableValue(getPrestoType(PartColumn.CONTAINER), value))
            .collect(toSet());

    private final String connectorId;
    private final Set<String> tableNames;
    private final ColumnNaming columnNaming;
    private final StatisticsEstimator statisticsEstimator;

    public TpchMetadata(String connectorId)
    {
        this(connectorId, ColumnNaming.SIMPLIFIED);
    }

    public TpchMetadata(String connectorId, ColumnNaming columnNaming)
    {
        ImmutableSet.Builder<String> tableNames = ImmutableSet.builder();
        for (TpchTable<?> tpchTable : TpchTable.getTables()) {
            tableNames.add(tpchTable.getTableName());
        }
        this.tableNames = tableNames.build();
        this.connectorId = connectorId;
        this.columnNaming = columnNaming;
        this.statisticsEstimator = createStatisticsEstimator();
    }

    private static StatisticsEstimator createStatisticsEstimator()
    {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new Jdk8Module());
        TableStatisticsDataRepository tableStatisticsDataRepository = new TableStatisticsDataRepository(objectMapper);
        return new StatisticsEstimator(tableStatisticsDataRepository);
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return SCHEMA_NAMES;
    }

    @Override
    public TpchTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        if (!tableNames.contains(tableName.getTableName())) {
            return null;
        }

        // parse the scale factor
        double scaleFactor = schemaNameToScaleFactor(tableName.getSchemaName());
        if (scaleFactor < 0) {
            return null;
        }

        return new TpchTableHandle(connectorId, tableName.getTableName(), scaleFactor);
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint<ColumnHandle> constraint,
            Optional<Set<ColumnHandle>> desiredColumns)
    {
        TpchTableHandle tableHandle = (TpchTableHandle) table;

        Optional<ConnectorTablePartitioning> nodePartition = Optional.empty();
        Optional<Set<ColumnHandle>> partitioningColumns = Optional.empty();
        List<LocalProperty<ColumnHandle>> localProperties = ImmutableList.of();

        TupleDomain<ColumnHandle> predicate = TupleDomain.all();
        TupleDomain<ColumnHandle> unenforcedConstraint = constraint.getSummary();
        Map<String, ColumnHandle> columns = getColumnHandles(session, tableHandle);
        if (tableHandle.getTableName().equals(TpchTable.ORDERS.getTableName())) {
            ColumnHandle orderKeyColumn = columns.get(columnNaming.getName(OrderColumn.ORDER_KEY));
            nodePartition = Optional.of(new ConnectorTablePartitioning(
                    new TpchPartitioningHandle(
                            TpchTable.ORDERS.getTableName(),
                            calculateTotalRows(OrderGenerator.SCALE_BASE, tableHandle.getScaleFactor())),
                    ImmutableList.of(orderKeyColumn)));
            partitioningColumns = Optional.of(ImmutableSet.of(orderKeyColumn));
            localProperties = ImmutableList.of(new SortingProperty<>(orderKeyColumn, SortOrder.ASC_NULLS_FIRST));
            predicate = toTupleDomain(ImmutableMap.of(
                    toColumnHandle(OrderColumn.ORDER_STATUS),
                    filterValues(ORDER_STATUS_NULLABLE_VALUES, OrderColumn.ORDER_STATUS, constraint)));
            unenforcedConstraint = filterOutColumnFromPredicate(constraint.getSummary(), OrderColumn.ORDER_STATUS);
        }
        else if (tableHandle.getTableName().equals(TpchTable.PART.getTableName())) {
            predicate = toTupleDomain(ImmutableMap.of(
                    toColumnHandle(PartColumn.CONTAINER),
                    filterValues(PART_CONTAINER_NULLABLE_VALUES, PartColumn.CONTAINER, constraint),
                    toColumnHandle(PartColumn.TYPE),
                    filterValues(PART_TYPE_NULLABLE_VALUES, PartColumn.TYPE, constraint)));
            unenforcedConstraint = filterOutColumnFromPredicate(constraint.getSummary(), PartColumn.CONTAINER);
            unenforcedConstraint = filterOutColumnFromPredicate(unenforcedConstraint, PartColumn.TYPE);
        }
        else if (tableHandle.getTableName().equals(TpchTable.LINE_ITEM.getTableName())) {
            ColumnHandle orderKeyColumn = columns.get(columnNaming.getName(LineItemColumn.ORDER_KEY));
            nodePartition = Optional.of(new ConnectorTablePartitioning(
                    new TpchPartitioningHandle(
                            TpchTable.ORDERS.getTableName(),
                            calculateTotalRows(OrderGenerator.SCALE_BASE, tableHandle.getScaleFactor())),
                    ImmutableList.of(orderKeyColumn)));
            partitioningColumns = Optional.of(ImmutableSet.of(orderKeyColumn));
            localProperties = ImmutableList.of(
                    new SortingProperty<>(orderKeyColumn, SortOrder.ASC_NULLS_FIRST),
                    new SortingProperty<>(columns.get(columnNaming.getName(LineItemColumn.LINE_NUMBER)), SortOrder.ASC_NULLS_FIRST));
        }

        ConnectorTableLayout layout = new ConnectorTableLayout(
                new TpchTableLayoutHandle(tableHandle, predicate),
                Optional.empty(),
                predicate, // TODO: return well-known properties (e.g., orderkey > 0, etc)
                nodePartition,
                partitioningColumns,
                Optional.empty(),
                localProperties);

        return ImmutableList.of(new ConnectorTableLayoutResult(layout, unenforcedConstraint));
    }

    private Set<NullableValue> filterValues(Set<NullableValue> nullableValues, TpchColumn<?> column, Constraint<ColumnHandle> constraint)
    {
        return nullableValues.stream()
                .filter(convertToPredicate(constraint.getSummary(), column))
                .filter(value -> constraint.predicate().test(ImmutableMap.of(toColumnHandle(column), value)))
                .collect(toSet());
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle)
    {
        TpchTableLayoutHandle layout = (TpchTableLayoutHandle) handle;

        // tables in this connector have a single layout
        return getTableLayouts(session, layout.getTable(), Constraint.alwaysTrue(), Optional.empty())
                .get(0)
                .getTableLayout();
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        TpchTableHandle tpchTableHandle = (TpchTableHandle) tableHandle;

        TpchTable<?> tpchTable = TpchTable.getTable(tpchTableHandle.getTableName());
        String schemaName = scaleFactorSchemaName(tpchTableHandle.getScaleFactor());

        return getTableMetadata(schemaName, tpchTable, columnNaming);
    }

    private static ConnectorTableMetadata getTableMetadata(String schemaName, TpchTable<?> tpchTable, ColumnNaming columnNaming)
    {
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        for (TpchColumn<? extends TpchEntity> column : tpchTable.getColumns()) {
            columns.add(new ColumnMetadata(columnNaming.getName(column), getPrestoType(column)));
        }
        columns.add(new ColumnMetadata(ROW_NUMBER_COLUMN_NAME, BIGINT, null, true));

        SchemaTableName tableName = new SchemaTableName(schemaName, tpchTable.getTableName());
        return new ConnectorTableMetadata(tableName, columns.build());
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ImmutableMap.Builder<String, ColumnHandle> builder = ImmutableMap.builder();
        for (ColumnMetadata columnMetadata : getTableMetadata(session, tableHandle).getColumns()) {
            builder.put(columnMetadata.getName(), new TpchColumnHandle(columnMetadata.getName(), columnMetadata.getType()));
        }
        return builder.build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> tableColumns = ImmutableMap.builder();
        for (String schemaName : getSchemaNames(session, prefix.getSchemaName())) {
            for (TpchTable<?> tpchTable : TpchTable.getTables()) {
                if (prefix.getTableName() == null || tpchTable.getTableName().equals(prefix.getTableName())) {
                    ConnectorTableMetadata tableMetadata = getTableMetadata(schemaName, tpchTable, columnNaming);
                    tableColumns.put(new SchemaTableName(schemaName, tpchTable.getTableName()), tableMetadata.getColumns());
                }
            }
        }
        return tableColumns.build();
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint<ColumnHandle> constraint)
    {
        TpchTableHandle tpchTableHandle = (TpchTableHandle) tableHandle;
        String tableName = tpchTableHandle.getTableName();
        TpchTable<?> tpchTable = TpchTable.getTable(tableName);
        Map<TpchColumn<?>, List<Object>> columnValuesRestrictions = getColumnValuesRestrictions(tpchTable, constraint);
        Optional<TableStatisticsData> optionalTableStatisticsData = statisticsEstimator.estimateStats(tpchTable, columnValuesRestrictions, tpchTableHandle.getScaleFactor());

        Map<String, ColumnHandle> columnHandles = getColumnHandles(session, tpchTableHandle);
        return optionalTableStatisticsData
                .map(tableStatisticsData -> toTableStatistics(optionalTableStatisticsData.get(), tpchTableHandle, columnHandles))
                .orElse(TableStatistics.EMPTY_STATISTICS);
    }

    private Map<TpchColumn<?>, List<Object>> getColumnValuesRestrictions(TpchTable<?> tpchTable, Constraint<ColumnHandle> constraint)
    {
        TupleDomain<ColumnHandle> constraintSummary = constraint.getSummary();
        if (constraintSummary.isAll()) {
            return emptyMap();
        }
        else if (constraintSummary.isNone()) {
            Set<TpchColumn<?>> columns = ImmutableSet.copyOf(tpchTable.getColumns());
            return asMap(columns, key -> emptyList());
        }
        else {
            Map<ColumnHandle, Domain> domains = constraintSummary.getDomains().get();
            Optional<Domain> orderStatusDomain = Optional.ofNullable(domains.get(toColumnHandle(ORDER_STATUS)));
            Optional<Map<TpchColumn<?>, List<Object>>> allowedColumnValues = orderStatusDomain.map(domain -> {
                List<Object> allowedValues = ORDER_STATUS_VALUES.stream()
                        .filter(domain::includesNullableValue)
                        .collect(toList());
                return avoidTrivialOrderStatusRestriction(allowedValues);
            });
            return allowedColumnValues.orElse(emptyMap());
        }
    }

    private Map<TpchColumn<?>, List<Object>> avoidTrivialOrderStatusRestriction(List<Object> allowedValues)
    {
        if (allowedValues.containsAll(ORDER_STATUS_VALUES)) {
            return emptyMap();
        }
        else {
            return ImmutableMap.of(ORDER_STATUS, allowedValues);
        }
    }

    private TableStatistics toTableStatistics(TableStatisticsData tableStatisticsData, TpchTableHandle tpchTableHandle, Map<String, ColumnHandle> columnHandles)
    {
        TableStatistics.Builder builder = TableStatistics.builder()
                .setRowCount(new Estimate(tableStatisticsData.getRowCount()));
        tableStatisticsData.getColumns().forEach((columnName, stats) -> {
            TpchColumnHandle columnHandle = (TpchColumnHandle) getColumnHandle(tpchTableHandle, columnHandles, columnName);
            builder.setColumnStatistics(columnHandle, toColumnStatistics(stats, columnHandle.getType()));
        });
        return builder.build();
    }

    private ColumnHandle getColumnHandle(TpchTableHandle tpchTableHandle, Map<String, ColumnHandle> columnHandles, String columnName)
    {
        TpchTable<?> table = TpchTable.getTable(tpchTableHandle.getTableName());
        return columnHandles.get(columnNaming.getName(table.getColumn(columnName)));
    }

    private ColumnStatistics toColumnStatistics(ColumnStatisticsData stats, Type columnType)
    {
        return ColumnStatistics.builder()
                .addRange(rangeBuilder -> rangeBuilder
                        .setDistinctValuesCount(stats.getDistinctValuesCount().map(Estimate::new).orElse(Estimate.unknownValue()))
                        .setLowValue(stats.getMin().map(value -> toPrestoValue(value, columnType)))
                        .setHighValue(stats.getMax().map(value -> toPrestoValue(value, columnType)))
                        .setFraction(new Estimate((1))))
                .setNullsFraction(Estimate.zeroValue())
                .build();
    }

    private Object toPrestoValue(Object tpchValue, Type columnType)
    {
        if (columnType instanceof VarcharType) {
            return Slices.utf8Slice((String) tpchValue);
        }
        if (columnType.equals(BIGINT) || columnType.equals(INTEGER) || columnType.equals(DATE)) {
            return ((Number) tpchValue).longValue();
        }
        if (columnType.equals(DOUBLE)) {
            return ((Number) tpchValue).doubleValue();
        }
        throw new IllegalArgumentException("unsupported column type " + columnType);
    }

    @VisibleForTesting
    TpchColumnHandle toColumnHandle(TpchColumn<?> column)
    {
        return new TpchColumnHandle(columnNaming.getName(column), getPrestoType(column));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        ConnectorTableMetadata tableMetadata = getTableMetadata(session, tableHandle);
        String columnName = ((TpchColumnHandle) columnHandle).getColumnName();

        for (ColumnMetadata column : tableMetadata.getColumns()) {
            if (column.getName().equals(columnName)) {
                return column;
            }
        }
        throw new IllegalArgumentException(String.format("Table %s does not have column %s", tableMetadata.getTable(), columnName));
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (String schemaName : getSchemaNames(session, schemaNameOrNull)) {
            for (TpchTable<?> tpchTable : TpchTable.getTables()) {
                builder.add(new SchemaTableName(schemaName, tpchTable.getTableName()));
            }
        }
        return builder.build();
    }

    private TupleDomain<ColumnHandle> toTupleDomain(Map<TpchColumnHandle, Set<NullableValue>> predicate)
    {
        return TupleDomain.withColumnDomains(predicate.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    Type type = entry.getKey().getType();
                    return entry.getValue().stream()
                            .map(nullableValue -> Domain.singleValue(type, nullableValue.getValue()))
                            .reduce((Domain::union))
                            .orElse(Domain.none(type));
                })));
    }

    @VisibleForTesting
    Predicate<NullableValue> convertToPredicate(TupleDomain<ColumnHandle> predicate, TpchColumn column)
    {
        TpchColumnHandle columnHandle = toColumnHandle(column);
        TupleDomain<ColumnHandle> columnPredicate = filterColumns(predicate, columnHandle::equals);
        return nullableValue -> columnPredicate.contains(TupleDomain.fromFixedValues(ImmutableMap.of(columnHandle, nullableValue)));
    }

    @VisibleForTesting
    TupleDomain<ColumnHandle> filterOutColumnFromPredicate(TupleDomain<ColumnHandle> predicate, TpchColumn column)
    {
        return filterColumns(predicate, tpchColumnHandle -> !tpchColumnHandle.equals(toColumnHandle(column)));
    }

    private TupleDomain<ColumnHandle> filterColumns(TupleDomain<ColumnHandle> predicate, Predicate<TpchColumnHandle> filterPredicate)
    {
        return predicate.transform(columnHandle -> {
            TpchColumnHandle tpchColumnHandle = (TpchColumnHandle) columnHandle;
            if (filterPredicate.test(tpchColumnHandle)) {
                return tpchColumnHandle;
            }

            return null;
        });
    }

    private List<String> getSchemaNames(ConnectorSession session, String schemaNameOrNull)
    {
        List<String> schemaNames;
        if (schemaNameOrNull == null) {
            schemaNames = listSchemaNames(session);
        }
        else if (schemaNameToScaleFactor(schemaNameOrNull) > 0) {
            schemaNames = ImmutableList.of(schemaNameOrNull);
        }
        else {
            schemaNames = ImmutableList.of();
        }
        return schemaNames;
    }

    private static String scaleFactorSchemaName(double scaleFactor)
    {
        return "sf" + scaleFactor;
    }

    public static double schemaNameToScaleFactor(String schemaName)
    {
        if (TINY_SCHEMA_NAME.equals(schemaName)) {
            return TINY_SCALE_FACTOR;
        }

        if (!schemaName.startsWith("sf")) {
            return -1;
        }

        try {
            return Double.parseDouble(schemaName.substring(2));
        }
        catch (Exception ignored) {
            return -1;
        }
    }

    public static Type getPrestoType(TpchColumn<?> column)
    {
        TpchColumnType tpchType = column.getType();
        switch (tpchType.getBase()) {
            case IDENTIFIER:
                return BIGINT;
            case INTEGER:
                return INTEGER;
            case DATE:
                return DATE;
            case DOUBLE:
                return DOUBLE;
            case VARCHAR:
                return createVarcharType((int) (long) tpchType.getPrecision().get());
        }
        throw new IllegalArgumentException("Unsupported type " + tpchType);
    }

    private long calculateTotalRows(int scaleBase, double scaleFactor)
    {
        double totalRows = scaleBase * scaleFactor;
        if (totalRows > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Total rows is larger than 2^64");
        }
        return (long) totalRows;
    }
}
