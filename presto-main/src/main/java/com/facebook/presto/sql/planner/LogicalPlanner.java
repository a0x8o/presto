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
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.cost.CachingCostProvider;
import com.facebook.presto.cost.CachingStatsProvider;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.CostProvider;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.cost.StatsProvider;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.NewTableLayout;
import com.facebook.presto.metadata.QualifiedObjectName;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.metadata.TableMetadata;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.statistics.TableStatisticsMetadata;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Field;
import com.facebook.presto.sql.analyzer.RelationId;
import com.facebook.presto.sql.analyzer.RelationType;
import com.facebook.presto.sql.analyzer.Scope;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.StatisticsAggregationPlanner.TableStatisticAggregation;
import com.facebook.presto.sql.planner.optimizations.PlanNodeSearcher;
import com.facebook.presto.sql.planner.optimizations.PlanOptimizer;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.DeleteNode;
import com.facebook.presto.sql.planner.plan.ExplainAnalyzeNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.StatisticAggregations;
import com.facebook.presto.sql.planner.plan.StatisticsWriterNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.planner.sanity.PlanSanityChecker;
import com.facebook.presto.sql.tree.Analyze;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.CreateTableAsSelect;
import com.facebook.presto.sql.tree.Delete;
import com.facebook.presto.sql.tree.Explain;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.Insert;
import com.facebook.presto.sql.tree.LambdaArgumentDeclaration;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static com.facebook.presto.SystemSessionProperties.isPrintStatsForNonJoinQuery;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.statistics.TableStatisticType.ROW_COUNT;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.sql.planner.plan.AggregationNode.singleGroupingSet;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.CreateName;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.InsertReference;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.WriterTarget;
import static com.facebook.presto.sql.planner.sanity.PlanSanityChecker.DISTRIBUTED_PLAN_SANITY_CHECKER;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Streams.zip;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class LogicalPlanner
{
    public enum Stage
    {
        CREATED, OPTIMIZED, OPTIMIZED_AND_VALIDATED
    }

    private final boolean explain;
    private final PlanNodeIdAllocator idAllocator;
    private final Session session;
    private final List<PlanOptimizer> planOptimizers;
    private final PlanSanityChecker planSanityChecker;
    private final SymbolAllocator symbolAllocator = new SymbolAllocator();
    private final Metadata metadata;
    private final SqlParser sqlParser;
    private final StatisticsAggregationPlanner statisticsAggregationPlanner;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final WarningCollector warningCollector;

    public LogicalPlanner(
            boolean explain,
            Session session,
            List<PlanOptimizer> planOptimizers,
            PlanNodeIdAllocator idAllocator,
            Metadata metadata,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            WarningCollector warningCollector)
    {
        this(explain, session, planOptimizers, DISTRIBUTED_PLAN_SANITY_CHECKER, idAllocator, metadata, sqlParser, statsCalculator, costCalculator, warningCollector);
    }

    public LogicalPlanner(
            boolean explain,
            Session session,
            List<PlanOptimizer> planOptimizers,
            PlanSanityChecker planSanityChecker,
            PlanNodeIdAllocator idAllocator,
            Metadata metadata,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            WarningCollector warningCollector)
    {
        this.explain = explain;
        this.session = requireNonNull(session, "session is null");
        this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
        this.planSanityChecker = requireNonNull(planSanityChecker, "planSanityChecker is null");
        this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.statisticsAggregationPlanner = new StatisticsAggregationPlanner(session, symbolAllocator, metadata);
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
    }

    public Plan plan(Analysis analysis)
    {
        return plan(analysis, Stage.OPTIMIZED_AND_VALIDATED);
    }

    public Plan plan(Analysis analysis, Stage stage)
    {
        PlanNode root = planStatement(analysis, analysis.getStatement());

        planSanityChecker.validateIntermediatePlan(root, session, metadata, sqlParser, symbolAllocator.getTypes(), warningCollector);

        if (stage.ordinal() >= Stage.OPTIMIZED.ordinal()) {
            for (PlanOptimizer optimizer : planOptimizers) {
                root = optimizer.optimize(root, session, symbolAllocator.getTypes(), symbolAllocator, idAllocator, warningCollector);
                requireNonNull(root, format("%s returned a null plan", optimizer.getClass().getName()));
            }
        }

        if (stage.ordinal() >= Stage.OPTIMIZED_AND_VALIDATED.ordinal()) {
            // make sure we produce a valid plan after optimizations run. This is mainly to catch programming errors
            planSanityChecker.validateFinalPlan(root, session, metadata, sqlParser, symbolAllocator.getTypes(), warningCollector);
        }

        TypeProvider types = symbolAllocator.getTypes();
        return new Plan(root, types, computeStats(root, types));
    }

    private StatsAndCosts computeStats(PlanNode root, TypeProvider types)
    {
        if (explain || isPrintStatsForNonJoinQuery(session) ||
                PlanNodeSearcher.searchFrom(root).where(node ->
                    (node instanceof JoinNode) || (node instanceof SemiJoinNode)).matches()) {
            StatsProvider statsProvider = new CachingStatsProvider(statsCalculator, session, types);
            CostProvider costProvider = new CachingCostProvider(costCalculator, statsProvider, Optional.empty(), session, types);
            return StatsAndCosts.create(root, statsProvider, costProvider);
        }
        return StatsAndCosts.empty();
    }

    public PlanNode planStatement(Analysis analysis, Statement statement)
    {
        if (statement instanceof CreateTableAsSelect && analysis.isCreateTableAsSelectNoOp()) {
            checkState(analysis.getCreateTableDestination().isPresent(), "Table destination is missing");
            Symbol symbol = symbolAllocator.newSymbol("rows", BIGINT);
            PlanNode source = new ValuesNode(idAllocator.getNextId(), ImmutableList.of(symbol), ImmutableList.of(ImmutableList.of(new LongLiteral("0"))));
            return new OutputNode(idAllocator.getNextId(), source, ImmutableList.of("rows"), ImmutableList.of(symbol));
        }
        return createOutputPlan(planStatementWithoutOutput(analysis, statement), analysis);
    }

    private RelationPlan planStatementWithoutOutput(Analysis analysis, Statement statement)
    {
        if (statement instanceof CreateTableAsSelect) {
            if (analysis.isCreateTableAsSelectNoOp()) {
                throw new PrestoException(NOT_SUPPORTED, "CREATE TABLE IF NOT EXISTS is not supported in this context " + statement.getClass().getSimpleName());
            }
            return createTableCreationPlan(analysis, ((CreateTableAsSelect) statement).getQuery());
        }
        else if (statement instanceof Analyze) {
            return createAnalyzePlan(analysis, (Analyze) statement);
        }
        else if (statement instanceof Insert) {
            checkState(analysis.getInsert().isPresent(), "Insert handle is missing");
            return createInsertPlan(analysis, (Insert) statement);
        }
        else if (statement instanceof Delete) {
            return createDeletePlan(analysis, (Delete) statement);
        }
        else if (statement instanceof Query) {
            return createRelationPlan(analysis, (Query) statement);
        }
        else if (statement instanceof Explain && ((Explain) statement).isAnalyze()) {
            return createExplainAnalyzePlan(analysis, (Explain) statement);
        }
        else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported statement type " + statement.getClass().getSimpleName());
        }
    }

    private RelationPlan createExplainAnalyzePlan(Analysis analysis, Explain statement)
    {
        RelationPlan underlyingPlan = planStatementWithoutOutput(analysis, statement.getStatement());
        PlanNode root = underlyingPlan.getRoot();
        Scope scope = analysis.getScope(statement);
        Symbol outputSymbol = symbolAllocator.newSymbol(scope.getRelationType().getFieldByIndex(0));
        root = new ExplainAnalyzeNode(idAllocator.getNextId(), root, outputSymbol, statement.isVerbose());
        return new RelationPlan(root, scope, ImmutableList.of(outputSymbol));
    }

    private RelationPlan createAnalyzePlan(Analysis analysis, Analyze analyzeStatement)
    {
        TableHandle targetTable = analysis.getAnalyzeTarget().get();

        // Plan table scan
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetTable);
        ImmutableList.Builder<Symbol> tableScanOutputs = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, ColumnHandle> symbolToColumnHandle = ImmutableMap.builder();
        ImmutableMap.Builder<String, Symbol> columnNameToSymbol = ImmutableMap.builder();
        TableMetadata tableMetadata = metadata.getTableMetadata(session, targetTable);
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            Symbol symbol = symbolAllocator.newSymbol(column.getName(), column.getType());
            tableScanOutputs.add(symbol);
            symbolToColumnHandle.put(symbol, columnHandles.get(column.getName()));
            columnNameToSymbol.put(column.getName(), symbol);
        }

        TableStatisticsMetadata tableStatisticsMetadata = metadata.getStatisticsCollectionMetadata(
                session,
                targetTable.getConnectorId().getCatalogName(),
                tableMetadata.getMetadata());

        TableStatisticAggregation tableStatisticAggregation = statisticsAggregationPlanner.createStatisticsAggregation(tableStatisticsMetadata, columnNameToSymbol.build());
        StatisticAggregations statisticAggregations = tableStatisticAggregation.getAggregations();
        List<Symbol> groupingSymbols = statisticAggregations.getGroupingSymbols();

        PlanNode planNode = new StatisticsWriterNode(
                idAllocator.getNextId(),
                new AggregationNode(
                        idAllocator.getNextId(),
                        new TableScanNode(idAllocator.getNextId(), targetTable, tableScanOutputs.build(), symbolToColumnHandle.build()),
                        statisticAggregations.getAggregations(),
                        singleGroupingSet(groupingSymbols),
                        ImmutableList.of(),
                        AggregationNode.Step.SINGLE,
                        Optional.empty(),
                        Optional.empty()),
                new StatisticsWriterNode.WriteStatisticsReference(targetTable),
                symbolAllocator.newSymbol("rows", BIGINT),
                tableStatisticsMetadata.getTableStatistics().contains(ROW_COUNT),
                tableStatisticAggregation.getDescriptor());
        return new RelationPlan(planNode, analysis.getScope(analyzeStatement), planNode.getOutputSymbols());
    }

    private RelationPlan createTableCreationPlan(Analysis analysis, Query query)
    {
        QualifiedObjectName destination = analysis.getCreateTableDestination().get();

        RelationPlan plan = createRelationPlan(analysis, query);

        ConnectorTableMetadata tableMetadata = createTableMetadata(
                destination,
                getOutputTableColumns(plan, analysis.getColumnAliases()),
                analysis.getCreateTableProperties(),
                analysis.getParameters(),
                analysis.getCreateTableComment());
        Optional<NewTableLayout> newTableLayout = metadata.getNewTableLayout(session, destination.getCatalogName(), tableMetadata);

        List<String> columnNames = tableMetadata.getColumns().stream()
                .filter(column -> !column.isHidden())
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, destination.getCatalogName(), tableMetadata);

        return createTableWriterPlan(
                analysis,
                plan,
                new CreateName(destination.getCatalogName(), tableMetadata, newTableLayout),
                columnNames,
                newTableLayout,
                statisticsMetadata);
    }

    private RelationPlan createInsertPlan(Analysis analysis, Insert insertStatement)
    {
        Analysis.Insert insert = analysis.getInsert().get();

        TableMetadata tableMetadata = metadata.getTableMetadata(session, insert.getTarget());

        List<ColumnMetadata> visibleTableColumns = tableMetadata.getColumns().stream()
                .filter(column -> !column.isHidden())
                .collect(toImmutableList());
        List<String> visibleTableColumnNames = visibleTableColumns.stream()
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        RelationPlan plan = createRelationPlan(analysis, insertStatement.getQuery());

        Map<String, ColumnHandle> columns = metadata.getColumnHandles(session, insert.getTarget());
        Assignments.Builder assignments = Assignments.builder();
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            if (column.isHidden()) {
                continue;
            }
            Symbol output = symbolAllocator.newSymbol(column.getName(), column.getType());
            int index = insert.getColumns().indexOf(columns.get(column.getName()));
            if (index < 0) {
                Expression cast = new Cast(new NullLiteral(), column.getType().getTypeSignature().toString());
                assignments.put(output, cast);
            }
            else {
                Symbol input = plan.getSymbol(index);
                Type tableType = column.getType();
                Type queryType = symbolAllocator.getTypes().get(input);

                if (queryType.equals(tableType) || metadata.getTypeManager().isTypeOnlyCoercion(queryType, tableType)) {
                    assignments.put(output, input.toSymbolReference());
                }
                else {
                    Expression cast = new Cast(input.toSymbolReference(), tableType.getTypeSignature().toString());
                    assignments.put(output, cast);
                }
            }
        }
        ProjectNode projectNode = new ProjectNode(idAllocator.getNextId(), plan.getRoot(), assignments.build());

        List<Field> fields = visibleTableColumns.stream()
                .map(column -> Field.newUnqualified(column.getName(), column.getType()))
                .collect(toImmutableList());
        Scope scope = Scope.builder().withRelationType(RelationId.anonymous(), new RelationType(fields)).build();

        plan = new RelationPlan(projectNode, scope, projectNode.getOutputSymbols());

        Optional<NewTableLayout> newTableLayout = metadata.getInsertLayout(session, insert.getTarget());
        String catalogName = insert.getTarget().getConnectorId().getCatalogName();
        TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, catalogName, tableMetadata.getMetadata());

        return createTableWriterPlan(
                analysis,
                plan,
                new InsertReference(insert.getTarget()),
                visibleTableColumnNames,
                newTableLayout,
                statisticsMetadata);
    }

    private RelationPlan createTableWriterPlan(
            Analysis analysis,
            RelationPlan plan,
            WriterTarget target,
            List<String> columnNames,
            Optional<NewTableLayout> writeTableLayout,
            TableStatisticsMetadata statisticsMetadata)
    {
        PlanNode source = plan.getRoot();

        if (!analysis.isCreateTableAsSelectWithData()) {
            source = new LimitNode(idAllocator.getNextId(), source, 0L, false);
        }

        // todo this should be checked in analysis
        writeTableLayout.ifPresent(layout -> {
            if (!ImmutableSet.copyOf(columnNames).containsAll(layout.getPartitionColumns())) {
                throw new PrestoException(NOT_SUPPORTED, "INSERT must write all distribution columns: " + layout.getPartitionColumns());
            }
        });

        List<Symbol> symbols = plan.getFieldMappings();

        Optional<PartitioningScheme> partitioningScheme = Optional.empty();
        if (writeTableLayout.isPresent()) {
            List<Symbol> partitionFunctionArguments = new ArrayList<>();
            writeTableLayout.get().getPartitionColumns().stream()
                    .mapToInt(columnNames::indexOf)
                    .mapToObj(symbols::get)
                    .forEach(partitionFunctionArguments::add);

            List<Symbol> outputLayout = new ArrayList<>(symbols);

            partitioningScheme = Optional.of(new PartitioningScheme(
                    Partitioning.create(writeTableLayout.get().getPartitioning(), partitionFunctionArguments),
                    outputLayout));
        }

        if (!statisticsMetadata.isEmpty()) {
            verify(columnNames.size() == symbols.size(), "columnNames.size() != symbols.size(): %s and %s", columnNames, symbols);
            Map<String, Symbol> columnToSymbolMap = zip(columnNames.stream(), symbols.stream(), SimpleImmutableEntry::new)
                    .collect(toImmutableMap(Entry::getKey, Entry::getValue));

            TableStatisticAggregation result = statisticsAggregationPlanner.createStatisticsAggregation(statisticsMetadata, columnToSymbolMap);

            StatisticAggregations.Parts aggregations = result.getAggregations().createPartialAggregations(symbolAllocator, metadata.getFunctionManager());

            // partial aggregation is run within the TableWriteOperator to calculate the statistics for
            // the data consumed by the TableWriteOperator
            // final aggregation is run within the TableFinishOperator to summarize collected statistics
            // by the partial aggregation from all of the writer nodes
            StatisticAggregations partialAggregation = aggregations.getPartialAggregation();

            PlanNode writerNode = new TableWriterNode(
                    idAllocator.getNextId(),
                    source,
                    target,
                    symbolAllocator.newSymbol("partialrows", BIGINT),
                    symbolAllocator.newSymbol("fragment", VARBINARY),
                    symbols,
                    columnNames,
                    partitioningScheme,
                    Optional.of(partialAggregation),
                    Optional.of(result.getDescriptor().map(aggregations.getMappings()::get)));

            TableFinishNode commitNode = new TableFinishNode(
                    idAllocator.getNextId(),
                    writerNode,
                    target,
                    symbolAllocator.newSymbol("rows", BIGINT),
                    Optional.of(aggregations.getFinalAggregation()),
                    Optional.of(result.getDescriptor()));

            return new RelationPlan(commitNode, analysis.getRootScope(), commitNode.getOutputSymbols());
        }

        TableFinishNode commitNode = new TableFinishNode(
                idAllocator.getNextId(),
                new TableWriterNode(
                        idAllocator.getNextId(),
                        source,
                        target,
                        symbolAllocator.newSymbol("partialrows", BIGINT),
                        symbolAllocator.newSymbol("fragment", VARBINARY),
                        symbols,
                        columnNames,
                        partitioningScheme,
                        Optional.empty(),
                        Optional.empty()),
                target,
                symbolAllocator.newSymbol("rows", BIGINT),
                Optional.empty(),
                Optional.empty());
        return new RelationPlan(commitNode, analysis.getRootScope(), commitNode.getOutputSymbols());
    }

    private RelationPlan createDeletePlan(Analysis analysis, Delete node)
    {
        DeleteNode deleteNode = new QueryPlanner(analysis, symbolAllocator, idAllocator, buildLambdaDeclarationToSymbolMap(analysis, symbolAllocator), metadata, session)
                .plan(node);

        TableFinishNode commitNode = new TableFinishNode(
                idAllocator.getNextId(),
                deleteNode,
                deleteNode.getTarget(),
                symbolAllocator.newSymbol("rows", BIGINT),
                Optional.empty(),
                Optional.empty());

        return new RelationPlan(commitNode, analysis.getScope(node), commitNode.getOutputSymbols());
    }

    private PlanNode createOutputPlan(RelationPlan plan, Analysis analysis)
    {
        ImmutableList.Builder<Symbol> outputs = ImmutableList.builder();
        ImmutableList.Builder<String> names = ImmutableList.builder();

        int columnNumber = 0;
        RelationType outputDescriptor = analysis.getOutputDescriptor();
        for (Field field : outputDescriptor.getVisibleFields()) {
            String name = field.getName().orElse("_col" + columnNumber);
            names.add(name);

            int fieldIndex = outputDescriptor.indexOf(field);
            Symbol symbol = plan.getSymbol(fieldIndex);
            outputs.add(symbol);

            columnNumber++;
        }

        return new OutputNode(idAllocator.getNextId(), plan.getRoot(), names.build(), outputs.build());
    }

    private RelationPlan createRelationPlan(Analysis analysis, Query query)
    {
        return new RelationPlanner(analysis, symbolAllocator, idAllocator, buildLambdaDeclarationToSymbolMap(analysis, symbolAllocator), metadata, session)
                .process(query, null);
    }

    private ConnectorTableMetadata createTableMetadata(QualifiedObjectName table, List<ColumnMetadata> columns, Map<String, Expression> propertyExpressions, List<Expression> parameters, Optional<String> comment)
    {
        ConnectorId connectorId = metadata.getCatalogHandle(session, table.getCatalogName())
                .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog does not exist: " + table.getCatalogName()));

        Map<String, Object> properties = metadata.getTablePropertyManager().getProperties(
                connectorId,
                table.getCatalogName(),
                propertyExpressions,
                session,
                metadata,
                parameters);

        return new ConnectorTableMetadata(table.asSchemaTableName(), columns, properties, comment);
    }

    private static List<ColumnMetadata> getOutputTableColumns(RelationPlan plan, Optional<List<Identifier>> columnAliases)
    {
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        int aliasPosition = 0;
        for (Field field : plan.getDescriptor().getVisibleFields()) {
            String columnName = columnAliases.isPresent() ? columnAliases.get().get(aliasPosition).getValue() : field.getName().get();
            columns.add(new ColumnMetadata(columnName, field.getType()));
            aliasPosition++;
        }
        return columns.build();
    }

    private static Map<NodeRef<LambdaArgumentDeclaration>, Symbol> buildLambdaDeclarationToSymbolMap(Analysis analysis, SymbolAllocator symbolAllocator)
    {
        Map<NodeRef<LambdaArgumentDeclaration>, Symbol> resultMap = new LinkedHashMap<>();
        for (Entry<NodeRef<Expression>, Type> entry : analysis.getTypes().entrySet()) {
            if (!(entry.getKey().getNode() instanceof LambdaArgumentDeclaration)) {
                continue;
            }
            NodeRef<LambdaArgumentDeclaration> lambdaArgumentDeclaration = NodeRef.of((LambdaArgumentDeclaration) entry.getKey().getNode());
            if (resultMap.containsKey(lambdaArgumentDeclaration)) {
                continue;
            }
            resultMap.put(lambdaArgumentDeclaration, symbolAllocator.newSymbol(lambdaArgumentDeclaration.getNode(), entry.getValue()));
        }
        return resultMap;
    }
}
