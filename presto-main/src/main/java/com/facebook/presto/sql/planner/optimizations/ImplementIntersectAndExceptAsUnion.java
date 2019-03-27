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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.ExceptNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.IntersectNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SetOperationNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.GenericLiteral;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;
import static com.facebook.presto.sql.planner.plan.AggregationNode.Step;
import static com.facebook.presto.sql.planner.plan.AggregationNode.singleGroupingSet;
import static com.facebook.presto.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.concat;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * Converts INTERSECT and EXCEPT queries into UNION ALL..GROUP BY...WHERE
 * Eg:  SELECT a FROM foo INTERSECT SELECT x FROM bar
 * <p/>
 * =>
 * <p/>
 * SELECT a
 * FROM
 * (SELECT a,
 * COUNT(foo_marker) AS foo_cnt,
 * COUNT(bar_marker) AS bar_cnt
 * FROM
 * (
 * SELECT a, true as foo_marker, null as bar_marker FROM foo
 * UNION ALL
 * SELECT x, null as foo_marker, true as bar_marker FROM bar
 * ) T1
 * GROUP BY a) T2
 * WHERE foo_cnt >= 1 AND bar_cnt >= 1;
 * <p>
 * Eg:  SELECT a FROM foo EXCEPT SELECT x FROM bar
 * <p/>
 * =>
 * <p/>
 * SELECT a
 * FROM
 * (SELECT a,
 * COUNT(foo_marker) AS foo_cnt,
 * COUNT(bar_marker) AS bar_cnt
 * FROM
 * (
 * SELECT a, true as foo_marker, null as bar_marker FROM foo
 * UNION ALL
 * SELECT x, null as foo_marker, true as bar_marker FROM bar
 * ) T1
 * GROUP BY a) T2
 * WHERE foo_cnt >= 1 AND bar_cnt = 0;
 */
public class ImplementIntersectAndExceptAsUnion
        implements PlanOptimizer
{
    private final FunctionManager functionManager;

    public ImplementIntersectAndExceptAsUnion(FunctionManager functionManager)
    {
        this.functionManager = requireNonNull(functionManager, "functionManager is null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        return SimplePlanRewriter.rewriteWith(new Rewriter(session, functionManager, idAllocator, symbolAllocator), plan);
    }

    private static class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private static final String MARKER = "marker";

        private final Session session;
        private final FunctionManager functionManager;
        private final PlanNodeIdAllocator idAllocator;
        private final SymbolAllocator symbolAllocator;

        private Rewriter(Session session, FunctionManager functionManager, PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator)
        {
            this.session = requireNonNull(session, "session is null");
            this.functionManager = requireNonNull(functionManager, "functionManager is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.symbolAllocator = requireNonNull(symbolAllocator, "symbolAllocator is null");
        }

        @Override
        public PlanNode visitIntersect(IntersectNode node, RewriteContext<Void> rewriteContext)
        {
            List<PlanNode> sources = node.getSources().stream()
                    .map(rewriteContext::rewrite)
                    .collect(toList());

            List<Symbol> markers = allocateSymbols(sources.size(), MARKER, BOOLEAN);

            // identity projection for all the fields in each of the sources plus marker columns
            List<PlanNode> withMarkers = appendMarkers(markers, sources, node);

            // add a union over all the rewritten sources. The outputs of the union have the same name as the
            // original intersect node
            List<Symbol> outputs = node.getOutputSymbols();
            UnionNode union = union(withMarkers, ImmutableList.copyOf(concat(outputs, markers)));

            // add count aggregations and filter rows where any of the counts is >= 1
            List<Symbol> aggregationOutputs = allocateSymbols(markers.size(), "count", BIGINT);
            AggregationNode aggregation = computeCounts(union, outputs, markers, aggregationOutputs);
            FilterNode filterNode = addFilterForIntersect(aggregation);

            return project(filterNode, outputs);
        }

        @Override
        public PlanNode visitExcept(ExceptNode node, RewriteContext<Void> rewriteContext)
        {
            List<PlanNode> sources = node.getSources().stream()
                    .map(rewriteContext::rewrite)
                    .collect(toList());

            List<Symbol> markers = allocateSymbols(sources.size(), MARKER, BOOLEAN);

            // identity projection for all the fields in each of the sources plus marker columns
            List<PlanNode> withMarkers = appendMarkers(markers, sources, node);

            // add a union over all the rewritten sources. The outputs of the union have the same name as the
            // original except node
            List<Symbol> outputs = node.getOutputSymbols();
            UnionNode union = union(withMarkers, ImmutableList.copyOf(concat(outputs, markers)));

            // add count aggregations and filter rows where count for the first source is >= 1 and all others are 0
            List<Symbol> aggregationOutputs = allocateSymbols(markers.size(), "count", BIGINT);
            AggregationNode aggregation = computeCounts(union, outputs, markers, aggregationOutputs);
            FilterNode filterNode = addFilterForExcept(aggregation, aggregationOutputs.get(0), aggregationOutputs.subList(1, aggregationOutputs.size()));

            return project(filterNode, outputs);
        }

        private List<Symbol> allocateSymbols(int count, String nameHint, Type type)
        {
            ImmutableList.Builder<Symbol> symbolsBuilder = ImmutableList.builder();
            for (int i = 0; i < count; i++) {
                symbolsBuilder.add(symbolAllocator.newSymbol(nameHint, type));
            }
            return symbolsBuilder.build();
        }

        private List<PlanNode> appendMarkers(List<Symbol> markers, List<PlanNode> nodes, SetOperationNode node)
        {
            ImmutableList.Builder<PlanNode> result = ImmutableList.builder();
            for (int i = 0; i < nodes.size(); i++) {
                result.add(appendMarkers(nodes.get(i), i, markers, node.sourceSymbolMap(i)));
            }
            return result.build();
        }

        private PlanNode appendMarkers(PlanNode source, int markerIndex, List<Symbol> markers, Map<Symbol, SymbolReference> projections)
        {
            Assignments.Builder assignments = Assignments.builder();
            // add existing intersect symbols to projection
            for (Map.Entry<Symbol, SymbolReference> entry : projections.entrySet()) {
                Symbol symbol = symbolAllocator.newSymbol(entry.getKey().getName(), symbolAllocator.getTypes().get(entry.getKey()));
                assignments.put(symbol, entry.getValue());
            }

            // add extra marker fields to the projection
            for (int i = 0; i < markers.size(); ++i) {
                Expression expression = (i == markerIndex) ? TRUE_LITERAL : new Cast(new NullLiteral(), StandardTypes.BOOLEAN);
                assignments.put(symbolAllocator.newSymbol(markers.get(i).getName(), BOOLEAN), expression);
            }

            return new ProjectNode(idAllocator.getNextId(), source, assignments.build());
        }

        private UnionNode union(List<PlanNode> nodes, List<Symbol> outputs)
        {
            ImmutableListMultimap.Builder<Symbol, Symbol> outputsToInputs = ImmutableListMultimap.builder();
            for (PlanNode source : nodes) {
                for (int i = 0; i < source.getOutputSymbols().size(); i++) {
                    outputsToInputs.put(outputs.get(i), source.getOutputSymbols().get(i));
                }
            }

            return new UnionNode(idAllocator.getNextId(), nodes, outputsToInputs.build(), outputs);
        }

        private AggregationNode computeCounts(UnionNode sourceNode, List<Symbol> originalColumns, List<Symbol> markers, List<Symbol> aggregationOutputs)
        {
            ImmutableMap.Builder<Symbol, Aggregation> aggregations = ImmutableMap.builder();

            for (int i = 0; i < markers.size(); i++) {
                Symbol output = aggregationOutputs.get(i);
                QualifiedName name = QualifiedName.of("count");
                aggregations.put(output, new Aggregation(
                        new FunctionCall(name, ImmutableList.of(markers.get(i).toSymbolReference())),
                        functionManager.lookupFunction(name, fromTypes(BIGINT)),
                        Optional.empty()));
            }

            return new AggregationNode(idAllocator.getNextId(),
                    sourceNode,
                    aggregations.build(),
                    singleGroupingSet(originalColumns),
                    ImmutableList.of(),
                    Step.SINGLE,
                    Optional.empty(),
                    Optional.empty());
        }

        private FilterNode addFilterForIntersect(AggregationNode aggregation)
        {
            ImmutableList<Expression> predicates = aggregation.getAggregations().keySet().stream()
                    .map(column -> new ComparisonExpression(GREATER_THAN_OR_EQUAL, column.toSymbolReference(), new GenericLiteral("BIGINT", "1")))
                    .collect(toImmutableList());
            return new FilterNode(idAllocator.getNextId(), aggregation, ExpressionUtils.and(predicates));
        }

        private FilterNode addFilterForExcept(AggregationNode aggregation, Symbol firstSource, List<Symbol> remainingSources)
        {
            ImmutableList.Builder<Expression> predicatesBuilder = ImmutableList.builder();
            predicatesBuilder.add(new ComparisonExpression(GREATER_THAN_OR_EQUAL, firstSource.toSymbolReference(), new GenericLiteral("BIGINT", "1")));
            for (Symbol symbol : remainingSources) {
                predicatesBuilder.add(new ComparisonExpression(EQUAL, symbol.toSymbolReference(), new GenericLiteral("BIGINT", "0")));
            }

            return new FilterNode(idAllocator.getNextId(), aggregation, ExpressionUtils.and(predicatesBuilder.build()));
        }

        private ProjectNode project(PlanNode node, List<Symbol> columns)
        {
            return new ProjectNode(
                    idAllocator.getNextId(),
                    node,
                    Assignments.identity(columns));
        }
    }
}
