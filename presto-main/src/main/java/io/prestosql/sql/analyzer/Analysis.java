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
package io.prestosql.sql.analyzer;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import io.prestosql.metadata.NewTableLayout;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.ResolvedFunction;
import io.prestosql.metadata.TableHandle;
import io.prestosql.security.AccessControl;
import io.prestosql.security.SecurityContext;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.eventlistener.ColumnInfo;
import io.prestosql.spi.eventlistener.RoutineInfo;
import io.prestosql.spi.eventlistener.TableInfo;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.tree.AllColumns;
import io.prestosql.sql.tree.ExistsPredicate;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GroupingOperation;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.Join;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Offset;
import io.prestosql.sql.tree.OrderBy;
import io.prestosql.sql.tree.Parameter;
import io.prestosql.sql.tree.QuantifiedComparisonExpression;
import io.prestosql.sql.tree.Query;
import io.prestosql.sql.tree.QuerySpecification;
import io.prestosql.sql.tree.Relation;
import io.prestosql.sql.tree.SampledRelation;
import io.prestosql.sql.tree.Statement;
import io.prestosql.sql.tree.SubqueryExpression;
import io.prestosql.sql.tree.Table;
import io.prestosql.transaction.TransactionId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

public class Analysis
{
    @Nullable
    private final Statement root;
    private final Map<NodeRef<Parameter>, Expression> parameters;
    private String updateType;
    private Optional<QualifiedObjectName> target = Optional.empty();

    private final Map<NodeRef<Table>, Query> namedQueries = new LinkedHashMap<>();

    private final Map<NodeRef<Node>, Scope> scopes = new LinkedHashMap<>();
    private final Map<NodeRef<Expression>, FieldId> columnReferences = new LinkedHashMap<>();

    // a map of users to the columns per table that they access
    private final Map<AccessControlInfo, Map<QualifiedObjectName, Set<String>>> tableColumnReferences = new LinkedHashMap<>();

    // Track referenced fields from source relation node
    private final Multimap<NodeRef<? extends Node>, Field> referencedFields = HashMultimap.create();

    private final Map<NodeRef<QuerySpecification>, List<FunctionCall>> aggregates = new LinkedHashMap<>();
    private final Map<NodeRef<OrderBy>, List<Expression>> orderByAggregates = new LinkedHashMap<>();
    private final Map<NodeRef<QuerySpecification>, List<Expression>> groupByExpressions = new LinkedHashMap<>();
    private final Map<NodeRef<QuerySpecification>, GroupingSetAnalysis> groupingSets = new LinkedHashMap<>();

    private final Map<NodeRef<Node>, Expression> where = new LinkedHashMap<>();
    private final Map<NodeRef<QuerySpecification>, Expression> having = new LinkedHashMap<>();
    private final Map<NodeRef<Node>, List<Expression>> orderByExpressions = new LinkedHashMap<>();
    private final Set<NodeRef<OrderBy>> redundantOrderBy = new HashSet<>();
    private final Map<NodeRef<Node>, List<SelectExpression>> selectExpressions = new LinkedHashMap<>();
    private final Map<NodeRef<QuerySpecification>, List<FunctionCall>> windowFunctions = new LinkedHashMap<>();
    private final Map<NodeRef<OrderBy>, List<FunctionCall>> orderByWindowFunctions = new LinkedHashMap<>();
    private final Map<NodeRef<Offset>, Long> offset = new LinkedHashMap<>();
    private final Map<NodeRef<Node>, OptionalLong> limit = new LinkedHashMap<>();
    private final Map<NodeRef<AllColumns>, List<Field>> selectAllResultFields = new LinkedHashMap<>();

    private final Map<NodeRef<Join>, Expression> joins = new LinkedHashMap<>();
    private final Map<NodeRef<Join>, JoinUsingAnalysis> joinUsing = new LinkedHashMap<>();

    private final ListMultimap<NodeRef<Node>, InPredicate> inPredicatesSubqueries = ArrayListMultimap.create();
    private final ListMultimap<NodeRef<Node>, SubqueryExpression> scalarSubqueries = ArrayListMultimap.create();
    private final ListMultimap<NodeRef<Node>, ExistsPredicate> existsSubqueries = ArrayListMultimap.create();
    private final ListMultimap<NodeRef<Node>, QuantifiedComparisonExpression> quantifiedComparisonSubqueries = ArrayListMultimap.create();

    private final Map<NodeRef<Table>, TableEntry> tables = new LinkedHashMap<>();

    private final Map<NodeRef<Expression>, Type> types = new LinkedHashMap<>();
    private final Map<NodeRef<Expression>, Type> coercions = new LinkedHashMap<>();
    private final Set<NodeRef<Expression>> typeOnlyCoercions = new LinkedHashSet<>();
    private final Map<NodeRef<Relation>, List<Type>> relationCoercions = new LinkedHashMap<>();
    private final Map<NodeRef<FunctionCall>, RoutineEntry> resolvedFunctions = new LinkedHashMap<>();
    private final Map<NodeRef<Identifier>, LambdaArgumentDeclaration> lambdaArgumentReferences = new LinkedHashMap<>();

    private final Map<Field, ColumnHandle> columns = new LinkedHashMap<>();

    private final Map<NodeRef<SampledRelation>, Double> sampleRatios = new LinkedHashMap<>();

    private final Map<NodeRef<QuerySpecification>, List<GroupingOperation>> groupingOperations = new LinkedHashMap<>();

    private final Multiset<RowFilterScopeEntry> rowFilterScopes = HashMultiset.create();
    private final Map<NodeRef<Table>, List<Expression>> rowFilters = new LinkedHashMap<>();

    private final Multiset<ColumnMaskScopeEntry> columnMaskScopes = HashMultiset.create();
    private final Map<NodeRef<Table>, Map<String, List<Expression>>> columnMasks = new LinkedHashMap<>();

    private Optional<Create> create = Optional.empty();
    private Optional<Insert> insert = Optional.empty();
    private Optional<TableHandle> analyzeTarget = Optional.empty();

    // for describe input and describe output
    private final boolean isDescribe;

    // for recursive view detection
    private final Deque<Table> tablesForView = new ArrayDeque<>();

    public Analysis(@Nullable Statement root, Map<NodeRef<Parameter>, Expression> parameters, boolean isDescribe)
    {
        this.root = root;
        this.parameters = ImmutableMap.copyOf(requireNonNull(parameters, "parameterMap is null"));
        this.isDescribe = isDescribe;
    }

    public Statement getStatement()
    {
        return root;
    }

    public String getUpdateType()
    {
        return updateType;
    }

    public Optional<Output> getTarget()
    {
        return target.map(table -> new Output(table.getCatalogName(), table.getSchemaName(), table.getObjectName()));
    }

    public void setUpdateType(String updateType, QualifiedObjectName target)
    {
        this.updateType = updateType;
        this.target = Optional.of(target);
    }

    public void resetUpdateType()
    {
        this.updateType = null;
        this.target = Optional.empty();
    }

    public void setAggregates(QuerySpecification node, List<FunctionCall> aggregates)
    {
        this.aggregates.put(NodeRef.of(node), ImmutableList.copyOf(aggregates));
    }

    public List<FunctionCall> getAggregates(QuerySpecification query)
    {
        return aggregates.get(NodeRef.of(query));
    }

    public void setOrderByAggregates(OrderBy node, List<Expression> aggregates)
    {
        this.orderByAggregates.put(NodeRef.of(node), ImmutableList.copyOf(aggregates));
    }

    public List<Expression> getOrderByAggregates(OrderBy node)
    {
        return orderByAggregates.get(NodeRef.of(node));
    }

    public Map<NodeRef<Expression>, Type> getTypes()
    {
        return unmodifiableMap(types);
    }

    public Type getType(Expression expression)
    {
        Type type = types.get(NodeRef.of(expression));
        checkArgument(type != null, "Expression not analyzed: %s", expression);
        return type;
    }

    public Type getTypeWithCoercions(Expression expression)
    {
        NodeRef<Expression> key = NodeRef.of(expression);
        checkArgument(types.containsKey(key), "Expression not analyzed: %s", expression);
        if (coercions.containsKey(key)) {
            return coercions.get(key);
        }
        return types.get(key);
    }

    public Type[] getRelationCoercion(Relation relation)
    {
        return Optional.ofNullable(relationCoercions.get(NodeRef.of(relation)))
                .map(types -> types.stream().toArray(Type[]::new))
                .orElse(null);
    }

    public void addRelationCoercion(Relation relation, Type[] types)
    {
        relationCoercions.put(NodeRef.of(relation), ImmutableList.copyOf(types));
    }

    public Map<NodeRef<Expression>, Type> getCoercions()
    {
        return unmodifiableMap(coercions);
    }

    public Set<NodeRef<Expression>> getTypeOnlyCoercions()
    {
        return unmodifiableSet(typeOnlyCoercions);
    }

    public Type getCoercion(Expression expression)
    {
        return coercions.get(NodeRef.of(expression));
    }

    public void addLambdaArgumentReferences(Map<NodeRef<Identifier>, LambdaArgumentDeclaration> lambdaArgumentReferences)
    {
        this.lambdaArgumentReferences.putAll(lambdaArgumentReferences);
    }

    public LambdaArgumentDeclaration getLambdaArgumentReference(Identifier identifier)
    {
        return lambdaArgumentReferences.get(NodeRef.of(identifier));
    }

    public Map<NodeRef<Identifier>, LambdaArgumentDeclaration> getLambdaArgumentReferences()
    {
        return unmodifiableMap(lambdaArgumentReferences);
    }

    public void setGroupingSets(QuerySpecification node, GroupingSetAnalysis groupingSets)
    {
        this.groupingSets.put(NodeRef.of(node), groupingSets);
    }

    public void setGroupByExpressions(QuerySpecification node, List<Expression> expressions)
    {
        groupByExpressions.put(NodeRef.of(node), expressions);
    }

    public boolean isAggregation(QuerySpecification node)
    {
        return groupByExpressions.containsKey(NodeRef.of(node));
    }

    public boolean isTypeOnlyCoercion(Expression expression)
    {
        return typeOnlyCoercions.contains(NodeRef.of(expression));
    }

    public GroupingSetAnalysis getGroupingSets(QuerySpecification node)
    {
        return groupingSets.get(NodeRef.of(node));
    }

    public List<Expression> getGroupByExpressions(QuerySpecification node)
    {
        return groupByExpressions.get(NodeRef.of(node));
    }

    public void setWhere(Node node, Expression expression)
    {
        where.put(NodeRef.of(node), expression);
    }

    public Expression getWhere(QuerySpecification node)
    {
        return where.get(NodeRef.<Node>of(node));
    }

    public void setOrderByExpressions(Node node, List<Expression> items)
    {
        orderByExpressions.put(NodeRef.of(node), ImmutableList.copyOf(items));
    }

    public List<Expression> getOrderByExpressions(Node node)
    {
        return orderByExpressions.get(NodeRef.of(node));
    }

    public void setOffset(Offset node, long rowCount)
    {
        offset.put(NodeRef.of(node), rowCount);
    }

    public long getOffset(Offset node)
    {
        checkState(offset.containsKey(NodeRef.of(node)), "missing OFFSET value for node %s", node);
        return offset.get(NodeRef.of(node));
    }

    public void setLimit(Node node, OptionalLong rowCount)
    {
        limit.put(NodeRef.of(node), rowCount);
    }

    public void setLimit(Node node, long rowCount)
    {
        limit.put(NodeRef.of(node), OptionalLong.of(rowCount));
    }

    public OptionalLong getLimit(Node node)
    {
        checkState(limit.containsKey(NodeRef.of(node)), "missing LIMIT value for node %s", node);
        return limit.get(NodeRef.of(node));
    }

    public void setSelectAllResultFields(AllColumns node, List<Field> expressions)
    {
        selectAllResultFields.put(NodeRef.of(node), ImmutableList.copyOf(expressions));
    }

    public List<Field> getSelectAllResultFields(AllColumns node)
    {
        return selectAllResultFields.get(NodeRef.of(node));
    }

    public void setSelectExpressions(Node node, List<SelectExpression> expressions)
    {
        selectExpressions.put(NodeRef.of(node), ImmutableList.copyOf(expressions));
    }

    public List<SelectExpression> getSelectExpressions(Node node)
    {
        return selectExpressions.get(NodeRef.of(node));
    }

    public void setHaving(QuerySpecification node, Expression expression)
    {
        having.put(NodeRef.of(node), expression);
    }

    public void setJoinCriteria(Join node, Expression criteria)
    {
        joins.put(NodeRef.of(node), criteria);
    }

    public Expression getJoinCriteria(Join join)
    {
        return joins.get(NodeRef.of(join));
    }

    public void recordSubqueries(Node node, ExpressionAnalysis expressionAnalysis)
    {
        NodeRef<Node> key = NodeRef.of(node);
        this.inPredicatesSubqueries.putAll(key, dereference(expressionAnalysis.getSubqueryInPredicates()));
        this.scalarSubqueries.putAll(key, dereference(expressionAnalysis.getScalarSubqueries()));
        this.existsSubqueries.putAll(key, dereference(expressionAnalysis.getExistsSubqueries()));
        this.quantifiedComparisonSubqueries.putAll(key, dereference(expressionAnalysis.getQuantifiedComparisons()));
    }

    private <T extends Node> List<T> dereference(Collection<NodeRef<T>> nodeRefs)
    {
        return nodeRefs.stream()
                .map(NodeRef::getNode)
                .collect(toImmutableList());
    }

    public List<InPredicate> getInPredicateSubqueries(Node node)
    {
        return ImmutableList.copyOf(inPredicatesSubqueries.get(NodeRef.of(node)));
    }

    public List<SubqueryExpression> getScalarSubqueries(Node node)
    {
        return ImmutableList.copyOf(scalarSubqueries.get(NodeRef.of(node)));
    }

    public List<ExistsPredicate> getExistsSubqueries(Node node)
    {
        return ImmutableList.copyOf(existsSubqueries.get(NodeRef.of(node)));
    }

    public List<QuantifiedComparisonExpression> getQuantifiedComparisonSubqueries(Node node)
    {
        return unmodifiableList(quantifiedComparisonSubqueries.get(NodeRef.of(node)));
    }

    public void setWindowFunctions(QuerySpecification node, List<FunctionCall> functions)
    {
        windowFunctions.put(NodeRef.of(node), ImmutableList.copyOf(functions));
    }

    public List<FunctionCall> getWindowFunctions(QuerySpecification query)
    {
        return windowFunctions.get(NodeRef.of(query));
    }

    public void setOrderByWindowFunctions(OrderBy node, List<FunctionCall> functions)
    {
        orderByWindowFunctions.put(NodeRef.of(node), ImmutableList.copyOf(functions));
    }

    public List<FunctionCall> getOrderByWindowFunctions(OrderBy query)
    {
        return orderByWindowFunctions.get(NodeRef.of(query));
    }

    public void addColumnReferences(Map<NodeRef<Expression>, FieldId> columnReferences)
    {
        this.columnReferences.putAll(columnReferences);
    }

    public Scope getScope(Node node)
    {
        return tryGetScope(node).orElseThrow(() -> new IllegalArgumentException(format("Analysis does not contain information for node: %s", node)));
    }

    public Optional<Scope> tryGetScope(Node node)
    {
        NodeRef<Node> key = NodeRef.of(node);
        if (scopes.containsKey(key)) {
            return Optional.of(scopes.get(key));
        }

        return Optional.empty();
    }

    public Scope getRootScope()
    {
        return getScope(root);
    }

    public void setScope(Node node, Scope scope)
    {
        scopes.put(NodeRef.of(node), scope);
    }

    public RelationType getOutputDescriptor()
    {
        return getOutputDescriptor(root);
    }

    public RelationType getOutputDescriptor(Node node)
    {
        return getScope(node).getRelationType();
    }

    public TableHandle getTableHandle(Table table)
    {
        return tables.get(NodeRef.of(table))
                .getHandle()
                .orElseThrow(() -> new IllegalArgumentException(format("%s is not a table reference", table)));
    }

    public Collection<TableHandle> getTables()
    {
        return tables.values().stream()
                .map(TableEntry::getHandle)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toImmutableList());
    }

    public void registerTable(
            Table table,
            Optional<TableHandle> handle,
            QualifiedObjectName name,
            List<ViewExpression> filters,
            Map<Field, List<ViewExpression>> columnMasks,
            String authorization)
    {
        tables.put(NodeRef.of(table), new TableEntry(handle, name, filters, columnMasks, authorization));
    }

    public ResolvedFunction getResolvedFunction(FunctionCall function)
    {
        return resolvedFunctions.get(NodeRef.of(function)).getFunction();
    }

    public void addResolvedFunction(FunctionCall node, ResolvedFunction function, String authorization)
    {
        resolvedFunctions.put(NodeRef.of(node), new RoutineEntry(function, authorization));
    }

    public Set<NodeRef<Expression>> getColumnReferences()
    {
        return unmodifiableSet(columnReferences.keySet());
    }

    public Map<NodeRef<Expression>, FieldId> getColumnReferenceFields()
    {
        return unmodifiableMap(columnReferences);
    }

    public boolean isColumnReference(Expression expression)
    {
        requireNonNull(expression, "expression is null");
        checkArgument(getType(expression) != null, "expression %s has not been analyzed", expression);
        return columnReferences.containsKey(NodeRef.of(expression));
    }

    public void addTypes(Map<NodeRef<Expression>, Type> types)
    {
        this.types.putAll(types);
    }

    public void addCoercion(Expression expression, Type type, boolean isTypeOnlyCoercion)
    {
        this.coercions.put(NodeRef.of(expression), type);
        if (isTypeOnlyCoercion) {
            this.typeOnlyCoercions.add(NodeRef.of(expression));
        }
    }

    public void addCoercions(Map<NodeRef<Expression>, Type> coercions, Set<NodeRef<Expression>> typeOnlyCoercions)
    {
        this.coercions.putAll(coercions);
        this.typeOnlyCoercions.addAll(typeOnlyCoercions);
    }

    public Expression getHaving(QuerySpecification query)
    {
        return having.get(NodeRef.of(query));
    }

    public void setColumn(Field field, ColumnHandle handle)
    {
        columns.put(field, handle);
    }

    public ColumnHandle getColumn(Field field)
    {
        return columns.get(field);
    }

    public Optional<TableHandle> getAnalyzeTarget()
    {
        return analyzeTarget;
    }

    public void setAnalyzeTarget(TableHandle analyzeTarget)
    {
        this.analyzeTarget = Optional.of(analyzeTarget);
    }

    public void setCreate(Create create)
    {
        this.create = Optional.of(create);
    }

    public Optional<Create> getCreate()
    {
        return create;
    }

    public void setInsert(Insert insert)
    {
        this.insert = Optional.of(insert);
    }

    public Optional<Insert> getInsert()
    {
        return insert;
    }

    public Query getNamedQuery(Table table)
    {
        return namedQueries.get(NodeRef.of(table));
    }

    public void registerNamedQuery(Table tableReference, Query query)
    {
        requireNonNull(tableReference, "tableReference is null");
        requireNonNull(query, "query is null");

        namedQueries.put(NodeRef.of(tableReference), query);
    }

    public void registerTableForView(Table tableReference)
    {
        tablesForView.push(requireNonNull(tableReference, "table is null"));
    }

    public void unregisterTableForView()
    {
        tablesForView.pop();
    }

    public boolean hasTableInView(Table tableReference)
    {
        return tablesForView.contains(tableReference);
    }

    public void setSampleRatio(SampledRelation relation, double ratio)
    {
        sampleRatios.put(NodeRef.of(relation), ratio);
    }

    public double getSampleRatio(SampledRelation relation)
    {
        NodeRef<SampledRelation> key = NodeRef.of(relation);
        checkState(sampleRatios.containsKey(key), "Sample ratio missing for %s. Broken analysis?", relation);
        return sampleRatios.get(key);
    }

    public void setGroupingOperations(QuerySpecification querySpecification, List<GroupingOperation> groupingOperations)
    {
        this.groupingOperations.put(NodeRef.of(querySpecification), ImmutableList.copyOf(groupingOperations));
    }

    public List<GroupingOperation> getGroupingOperations(QuerySpecification querySpecification)
    {
        return Optional.ofNullable(groupingOperations.get(NodeRef.of(querySpecification)))
                .orElse(emptyList());
    }

    public Map<NodeRef<Parameter>, Expression> getParameters()
    {
        return parameters;
    }

    public boolean isDescribe()
    {
        return isDescribe;
    }

    public void setJoinUsing(Join node, JoinUsingAnalysis analysis)
    {
        joinUsing.put(NodeRef.of(node), analysis);
    }

    public JoinUsingAnalysis getJoinUsing(Join node)
    {
        return joinUsing.get(NodeRef.of(node));
    }

    public void addTableColumnReferences(AccessControl accessControl, Identity identity, Multimap<QualifiedObjectName, String> tableColumnMap)
    {
        AccessControlInfo accessControlInfo = new AccessControlInfo(accessControl, identity);
        Map<QualifiedObjectName, Set<String>> references = tableColumnReferences.computeIfAbsent(accessControlInfo, k -> new LinkedHashMap<>());
        tableColumnMap.asMap()
                .forEach((key, value) -> references.computeIfAbsent(key, k -> new HashSet<>()).addAll(value));
    }

    public void addEmptyColumnReferencesForTable(AccessControl accessControl, Identity identity, QualifiedObjectName table)
    {
        AccessControlInfo accessControlInfo = new AccessControlInfo(accessControl, identity);
        tableColumnReferences.computeIfAbsent(accessControlInfo, k -> new LinkedHashMap<>()).computeIfAbsent(table, k -> new HashSet<>());
    }

    public void addReferencedFields(Multimap<NodeRef<Node>, Field> references)
    {
        referencedFields.putAll(references);
    }

    public Map<AccessControlInfo, Map<QualifiedObjectName, Set<String>>> getTableColumnReferences()
    {
        return tableColumnReferences;
    }

    public void markRedundantOrderBy(OrderBy orderBy)
    {
        redundantOrderBy.add(NodeRef.of(orderBy));
    }

    public boolean isOrderByRedundant(OrderBy orderBy)
    {
        return redundantOrderBy.contains(NodeRef.of(orderBy));
    }

    public boolean hasRowFilter(QualifiedObjectName table, String identity)
    {
        return rowFilterScopes.contains(new RowFilterScopeEntry(table, identity));
    }

    public void registerTableForRowFiltering(QualifiedObjectName table, String identity)
    {
        rowFilterScopes.add(new RowFilterScopeEntry(table, identity));
    }

    public void unregisterTableForRowFiltering(QualifiedObjectName table, String identity)
    {
        rowFilterScopes.remove(new RowFilterScopeEntry(table, identity));
    }

    public void addRowFilter(Table table, Expression filter)
    {
        rowFilters.computeIfAbsent(NodeRef.of(table), node -> new ArrayList<>())
                .add(filter);
    }

    public List<Expression> getRowFilters(Table node)
    {
        return rowFilters.getOrDefault(NodeRef.of(node), ImmutableList.of());
    }

    public boolean hasColumnMask(QualifiedObjectName table, String column, String identity)
    {
        return columnMaskScopes.contains(new ColumnMaskScopeEntry(table, column, identity));
    }

    public void registerTableForColumnMasking(QualifiedObjectName table, String column, String identity)
    {
        columnMaskScopes.add(new ColumnMaskScopeEntry(table, column, identity));
    }

    public void unregisterTableForColumnMasking(QualifiedObjectName table, String column, String identity)
    {
        columnMaskScopes.remove(new ColumnMaskScopeEntry(table, column, identity));
    }

    public void addColumnMask(Table table, String column, Expression mask)
    {
        Map<String, List<Expression>> masks = columnMasks.computeIfAbsent(NodeRef.of(table), node -> new LinkedHashMap<>());
        masks.computeIfAbsent(column, name -> new ArrayList<>())
                .add(mask);
    }

    public Map<String, List<Expression>> getColumnMasks(Table table)
    {
        return columnMasks.getOrDefault(NodeRef.of(table), ImmutableMap.of());
    }

    public List<TableInfo> getReferencedTables()
    {
        return tables.entrySet().stream()
                .map(entry -> {
                    NodeRef<Table> table = entry.getKey();

                    List<ColumnInfo> columns = referencedFields.get(table).stream()
                            .map(field -> {
                                String fieldName = field.getName().get();

                                return new ColumnInfo(
                                        fieldName,
                                        columnMasks.getOrDefault(table, ImmutableMap.of())
                                                .getOrDefault(fieldName, ImmutableList.of()).stream()
                                                .map(Expression::toString)
                                                .collect(toImmutableList()));
                            })
                            .collect(toImmutableList());

                    TableEntry info = entry.getValue();
                    return new TableInfo(
                            info.getName().getCatalogName(),
                            info.getName().getSchemaName(),
                            info.getName().getObjectName(),
                            info.getAuthorization(),
                            rowFilters.getOrDefault(table, ImmutableList.of()).stream()
                                    .map(Expression::toString)
                                    .collect(toImmutableList()),
                            columns);
                })
                .collect(toImmutableList());
    }

    public List<RoutineInfo> getRoutines()
    {
        return resolvedFunctions.entrySet().stream()
                .map(entry -> new RoutineInfo(entry.getValue().function.getSignature().getName(), entry.getValue().getAuthorization()))
                .collect(toImmutableList());
    }

    @Immutable
    public static final class SelectExpression
    {
        // expression refers to a select item, either to be returned directly, or unfolded by all-fields reference
        // unfoldedExpressions applies to the latter case, and is a list of subscript expressions
        // referencing each field of the row.
        private final Expression expression;
        private final Optional<List<Expression>> unfoldedExpressions;

        public SelectExpression(Expression expression, Optional<List<Expression>> unfoldedExpressions)
        {
            this.expression = requireNonNull(expression, "expression is null");
            this.unfoldedExpressions = requireNonNull(unfoldedExpressions);
        }

        public Expression getExpression()
        {
            return expression;
        }

        public Optional<List<Expression>> getUnfoldedExpressions()
        {
            return unfoldedExpressions;
        }
    }

    @Immutable
    public static final class Create
    {
        private final Optional<QualifiedObjectName> destination;
        private final Optional<ConnectorTableMetadata> metadata;
        private final Optional<NewTableLayout> layout;
        private final boolean createTableAsSelectWithData;
        private final boolean createTableAsSelectNoOp;

        public Create(
                Optional<QualifiedObjectName> destination,
                Optional<ConnectorTableMetadata> metadata,
                Optional<NewTableLayout> layout,
                boolean createTableAsSelectWithData,
                boolean createTableAsSelectNoOp)
        {
            this.destination = requireNonNull(destination, "destination is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.layout = requireNonNull(layout, "layout is null");
            this.createTableAsSelectWithData = createTableAsSelectWithData;
            this.createTableAsSelectNoOp = createTableAsSelectNoOp;
        }

        public Optional<QualifiedObjectName> getDestination()
        {
            return destination;
        }

        public Optional<ConnectorTableMetadata> getMetadata()
        {
            return metadata;
        }

        public Optional<NewTableLayout> getLayout()
        {
            return layout;
        }

        public boolean isCreateTableAsSelectWithData()
        {
            return createTableAsSelectWithData;
        }

        public boolean isCreateTableAsSelectNoOp()
        {
            return createTableAsSelectNoOp;
        }
    }

    @Immutable
    public static final class Insert
    {
        private final TableHandle target;
        private final List<ColumnHandle> columns;
        private final Optional<NewTableLayout> newTableLayout;

        public Insert(TableHandle target, List<ColumnHandle> columns, Optional<NewTableLayout> newTableLayout)
        {
            this.target = requireNonNull(target, "target is null");
            this.columns = requireNonNull(columns, "columns is null");
            checkArgument(columns.size() > 0, "No columns given to insert");
            this.newTableLayout = requireNonNull(newTableLayout, "newTableLayout is null");
        }

        public List<ColumnHandle> getColumns()
        {
            return columns;
        }

        public TableHandle getTarget()
        {
            return target;
        }

        public Optional<NewTableLayout> getNewTableLayout()
        {
            return newTableLayout;
        }
    }

    public static final class JoinUsingAnalysis
    {
        private final List<Integer> leftJoinFields;
        private final List<Integer> rightJoinFields;
        private final List<Integer> otherLeftFields;
        private final List<Integer> otherRightFields;

        JoinUsingAnalysis(List<Integer> leftJoinFields, List<Integer> rightJoinFields, List<Integer> otherLeftFields, List<Integer> otherRightFields)
        {
            this.leftJoinFields = ImmutableList.copyOf(leftJoinFields);
            this.rightJoinFields = ImmutableList.copyOf(rightJoinFields);
            this.otherLeftFields = ImmutableList.copyOf(otherLeftFields);
            this.otherRightFields = ImmutableList.copyOf(otherRightFields);

            checkArgument(leftJoinFields.size() == rightJoinFields.size(), "Expected join fields for left and right to have the same size");
        }

        public List<Integer> getLeftJoinFields()
        {
            return leftJoinFields;
        }

        public List<Integer> getRightJoinFields()
        {
            return rightJoinFields;
        }

        public List<Integer> getOtherLeftFields()
        {
            return otherLeftFields;
        }

        public List<Integer> getOtherRightFields()
        {
            return otherRightFields;
        }
    }

    public static class GroupingSetAnalysis
    {
        private final List<Set<FieldId>> cubes;
        private final List<List<FieldId>> rollups;
        private final List<List<Set<FieldId>>> ordinarySets;
        private final List<Expression> complexExpressions;

        public GroupingSetAnalysis(
                List<Set<FieldId>> cubes,
                List<List<FieldId>> rollups,
                List<List<Set<FieldId>>> ordinarySets,
                List<Expression> complexExpressions)
        {
            this.cubes = ImmutableList.copyOf(cubes);
            this.rollups = ImmutableList.copyOf(rollups);
            this.ordinarySets = ImmutableList.copyOf(ordinarySets);
            this.complexExpressions = ImmutableList.copyOf(complexExpressions);
        }

        public List<Set<FieldId>> getCubes()
        {
            return cubes;
        }

        public List<List<FieldId>> getRollups()
        {
            return rollups;
        }

        public List<List<Set<FieldId>>> getOrdinarySets()
        {
            return ordinarySets;
        }

        public List<Expression> getComplexExpressions()
        {
            return complexExpressions;
        }
    }

    public static final class AccessControlInfo
    {
        private final AccessControl accessControl;
        private final Identity identity;

        public AccessControlInfo(AccessControl accessControl, Identity identity)
        {
            this.accessControl = requireNonNull(accessControl, "accessControl is null");
            this.identity = requireNonNull(identity, "identity is null");
        }

        public AccessControl getAccessControl()
        {
            return accessControl;
        }

        public SecurityContext getSecurityContext(TransactionId transactionId)
        {
            return new SecurityContext(transactionId, identity);
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

            AccessControlInfo that = (AccessControlInfo) o;
            return Objects.equals(accessControl, that.accessControl) &&
                    Objects.equals(identity, that.identity);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(accessControl, identity);
        }

        @Override
        public String toString()
        {
            return format("AccessControl: %s, Identity: %s", accessControl.getClass(), identity);
        }
    }

    private static class RowFilterScopeEntry
    {
        private final QualifiedObjectName table;
        private final String identity;

        public RowFilterScopeEntry(QualifiedObjectName table, String identity)
        {
            this.table = requireNonNull(table, "table is null");
            this.identity = requireNonNull(identity, "identity is null");
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
            RowFilterScopeEntry that = (RowFilterScopeEntry) o;
            return table.equals(that.table) &&
                    identity.equals(that.identity);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(table, identity);
        }
    }

    private static class ColumnMaskScopeEntry
    {
        private final QualifiedObjectName table;
        private final String column;
        private final String identity;

        public ColumnMaskScopeEntry(QualifiedObjectName table, String column, String identity)
        {
            this.table = requireNonNull(table, "table is null");
            this.column = requireNonNull(column, "column is null");
            this.identity = requireNonNull(identity, "identity is null");
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
            ColumnMaskScopeEntry that = (ColumnMaskScopeEntry) o;
            return table.equals(that.table) &&
                    column.equals(that.column) &&
                    identity.equals(that.identity);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(table, column, identity);
        }
    }

    private static class TableEntry
    {
        private final Optional<TableHandle> handle;
        private final QualifiedObjectName name;
        private final List<ViewExpression> filters;
        private final Map<Field, List<ViewExpression>> columnMasks;
        private final String authorization;

        public TableEntry(Optional<TableHandle> handle, QualifiedObjectName name, List<ViewExpression> filters, Map<Field, List<ViewExpression>> columnMasks, String authorization)
        {
            this.handle = requireNonNull(handle, "handle is null");
            this.name = requireNonNull(name, "name is null");
            this.filters = requireNonNull(filters, "filters is null");
            this.columnMasks = requireNonNull(columnMasks, "columnMasks is null");
            this.authorization = requireNonNull(authorization, "authorization is null");
        }

        public Optional<TableHandle> getHandle()
        {
            return handle;
        }

        public QualifiedObjectName getName()
        {
            return name;
        }

        public List<ViewExpression> getFilters()
        {
            return filters;
        }

        public Map<Field, List<ViewExpression>> getColumnMasks()
        {
            return columnMasks;
        }

        public String getAuthorization()
        {
            return authorization;
        }
    }

    private static class RoutineEntry
    {
        private final ResolvedFunction function;
        private final String authorization;

        public RoutineEntry(ResolvedFunction function, String authorization)
        {
            this.function = requireNonNull(function, "function is null");
            this.authorization = requireNonNull(authorization, "authorization is null");
        }

        public ResolvedFunction getFunction()
        {
            return function;
        }

        public String getAuthorization()
        {
            return authorization;
        }
    }
}
