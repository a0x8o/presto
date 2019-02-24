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

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AssignUniqueId;
import com.facebook.presto.sql.planner.plan.DistinctLimitNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanVisitor;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.SpatialJoinNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.expressionOrNullSymbols;
import static com.facebook.presto.sql.ExpressionUtils.extractConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.filterDeterministicConjuncts;
import static com.facebook.presto.sql.planner.EqualityInference.createEqualityInference;
import static com.facebook.presto.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static com.google.common.base.Predicates.in;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Computes the effective predicate at the top of the specified PlanNode
 * <p>
 * Note: non-deterministic predicates can not be pulled up (so they will be ignored)
 */
public class EffectivePredicateExtractor
{
    private static final Predicate<Map.Entry<Symbol, ? extends Expression>> SYMBOL_MATCHES_EXPRESSION =
            entry -> entry.getValue().equals(entry.getKey().toSymbolReference());

    private static final Function<Map.Entry<Symbol, ? extends Expression>, Expression> ENTRY_TO_EQUALITY =
            entry -> {
                SymbolReference reference = entry.getKey().toSymbolReference();
                Expression expression = entry.getValue();
                // TODO: this is not correct with respect to NULLs ('reference IS NULL' would be correct, rather than 'reference = NULL')
                // TODO: switch this to 'IS NOT DISTINCT FROM' syntax when EqualityInference properly supports it
                return new ComparisonExpression(ComparisonExpression.Operator.EQUAL, reference, expression);
            };

    private final DomainTranslator domainTranslator;

    public EffectivePredicateExtractor(DomainTranslator domainTranslator)
    {
        this.domainTranslator = requireNonNull(domainTranslator, "domainTranslator is null");
    }

    public Expression extract(PlanNode node)
    {
        return node.accept(new Visitor(domainTranslator), null);
    }

    private static class Visitor
            extends PlanVisitor<Expression, Void>
    {
        private final DomainTranslator domainTranslator;

        public Visitor(DomainTranslator domainTranslator)
        {
            this.domainTranslator = requireNonNull(domainTranslator, "domainTranslator is null");
        }

        @Override
        protected Expression visitPlan(PlanNode node, Void context)
        {
            return TRUE_LITERAL;
        }

        @Override
        public Expression visitAggregation(AggregationNode node, Void context)
        {
            // GROUP BY () always produces a group, regardless of whether there's any
            // input (unlike the case where there are group by keys, which produce
            // no output if there's no input).
            // Therefore, we can't say anything about the effective predicate of the
            // output of such an aggregation.
            if (node.getGroupingKeys().isEmpty()) {
                return TRUE_LITERAL;
            }

            Expression underlyingPredicate = node.getSource().accept(this, context);

            return pullExpressionThroughSymbols(underlyingPredicate, node.getGroupingKeys());
        }

        @Override
        public Expression visitFilter(FilterNode node, Void context)
        {
            Expression underlyingPredicate = node.getSource().accept(this, context);

            Expression predicate = node.getPredicate();

            // Remove non-deterministic conjuncts
            predicate = filterDeterministicConjuncts(predicate);

            return combineConjuncts(predicate, underlyingPredicate);
        }

        @Override
        public Expression visitExchange(ExchangeNode node, Void context)
        {
            return deriveCommonPredicates(node, source -> {
                Map<Symbol, SymbolReference> mappings = new HashMap<>();
                for (int i = 0; i < node.getInputs().get(source).size(); i++) {
                    mappings.put(
                            node.getOutputSymbols().get(i),
                            node.getInputs().get(source).get(i).toSymbolReference());
                }
                return mappings.entrySet();
            });
        }

        @Override
        public Expression visitProject(ProjectNode node, Void context)
        {
            // TODO: add simple algebraic solver for projection translation (right now only considers identity projections)

            Expression underlyingPredicate = node.getSource().accept(this, context);

            List<Expression> projectionEqualities = node.getAssignments().entrySet().stream()
                    .filter(SYMBOL_MATCHES_EXPRESSION.negate())
                    .map(ENTRY_TO_EQUALITY)
                    .collect(toImmutableList());

            return pullExpressionThroughSymbols(combineConjuncts(
                    ImmutableList.<Expression>builder()
                            .addAll(projectionEqualities)
                            .add(underlyingPredicate)
                            .build()),
                    node.getOutputSymbols());
        }

        @Override
        public Expression visitTopN(TopNNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitLimit(LimitNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitAssignUniqueId(AssignUniqueId node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitDistinctLimit(DistinctLimitNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitTableScan(TableScanNode node, Void context)
        {
            Map<ColumnHandle, Symbol> assignments = ImmutableBiMap.copyOf(node.getAssignments()).inverse();
            return domainTranslator.toPredicate(node.getCurrentConstraint().simplify().transform(assignments::get));
        }

        @Override
        public Expression visitSort(SortNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitWindow(WindowNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitUnion(UnionNode node, Void context)
        {
            return deriveCommonPredicates(node, source -> node.outputSymbolMap(source).entries());
        }

        @Override
        public Expression visitJoin(JoinNode node, Void context)
        {
            Expression leftPredicate = node.getLeft().accept(this, context);
            Expression rightPredicate = node.getRight().accept(this, context);

            List<Expression> joinConjuncts = node.getCriteria().stream()
                    .map(JoinNode.EquiJoinClause::toExpression)
                    .collect(toImmutableList());

            switch (node.getType()) {
                case INNER:
                    return pullExpressionThroughSymbols(combineConjuncts(ImmutableList.<Expression>builder()
                            .add(leftPredicate)
                            .add(rightPredicate)
                            .add(combineConjuncts(joinConjuncts))
                            .add(node.getFilter().orElse(TRUE_LITERAL))
                            .build()), node.getOutputSymbols());
                case LEFT:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(leftPredicate, node.getOutputSymbols()))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(rightPredicate), node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(joinConjuncts, node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .build());
                case RIGHT:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(rightPredicate, node.getOutputSymbols()))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(leftPredicate), node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(joinConjuncts, node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains))
                            .build());
                case FULL:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(leftPredicate), node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(rightPredicate), node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(joinConjuncts, node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains, node.getRight().getOutputSymbols()::contains))
                            .build());
                default:
                    throw new UnsupportedOperationException("Unknown join type: " + node.getType());
            }
        }

        private static Iterable<Expression> pullNullableConjunctsThroughOuterJoin(List<Expression> conjuncts, Collection<Symbol> outputSymbols, Predicate<Symbol>... nullSymbolScopes)
        {
            // Conjuncts without any symbol dependencies cannot be applied to the effective predicate (e.g. FALSE literal)
            return conjuncts.stream()
                    .map(expression -> pullExpressionThroughSymbols(expression, outputSymbols))
                    .map(expression -> SymbolsExtractor.extractAll(expression).isEmpty() ? TRUE_LITERAL : expression)
                    .map(expressionOrNullSymbols(nullSymbolScopes))
                    .collect(toImmutableList());
        }

        @Override
        public Expression visitSemiJoin(SemiJoinNode node, Void context)
        {
            // Filtering source does not change the effective predicate over the output symbols
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitSpatialJoin(SpatialJoinNode node, Void context)
        {
            Expression leftPredicate = node.getLeft().accept(this, context);
            Expression rightPredicate = node.getRight().accept(this, context);

            switch (node.getType()) {
                case INNER:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(leftPredicate, node.getOutputSymbols()))
                            .add(pullExpressionThroughSymbols(rightPredicate, node.getOutputSymbols()))
                            .build());
                case LEFT:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(leftPredicate, node.getOutputSymbols()))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(rightPredicate), node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .build());
                default:
                    throw new IllegalArgumentException("Unsupported spatial join type: " + node.getType());
            }
        }

        private Expression deriveCommonPredicates(PlanNode node, Function<Integer, Collection<Map.Entry<Symbol, SymbolReference>>> mapping)
        {
            // Find the predicates that can be pulled up from each source
            List<Set<Expression>> sourceOutputConjuncts = new ArrayList<>();
            for (int i = 0; i < node.getSources().size(); i++) {
                Expression underlyingPredicate = node.getSources().get(i).accept(this, null);

                List<Expression> equalities = mapping.apply(i).stream()
                        .filter(SYMBOL_MATCHES_EXPRESSION.negate())
                        .map(ENTRY_TO_EQUALITY)
                        .collect(toImmutableList());

                sourceOutputConjuncts.add(ImmutableSet.copyOf(extractConjuncts(pullExpressionThroughSymbols(combineConjuncts(
                        ImmutableList.<Expression>builder()
                                .addAll(equalities)
                                .add(underlyingPredicate)
                                .build()),
                        node.getOutputSymbols()))));
            }

            // Find the intersection of predicates across all sources
            // TODO: use a more precise way to determine overlapping conjuncts (e.g. commutative predicates)
            Iterator<Set<Expression>> iterator = sourceOutputConjuncts.iterator();
            Set<Expression> potentialOutputConjuncts = iterator.next();
            while (iterator.hasNext()) {
                potentialOutputConjuncts = Sets.intersection(potentialOutputConjuncts, iterator.next());
            }

            return combineConjuncts(potentialOutputConjuncts);
        }

        private static List<Expression> pullExpressionsThroughSymbols(List<Expression> expressions, Collection<Symbol> symbols)
        {
            return expressions.stream()
                    .map(expression -> pullExpressionThroughSymbols(expression, symbols))
                    .collect(toImmutableList());
        }

        private static Expression pullExpressionThroughSymbols(Expression expression, Collection<Symbol> symbols)
        {
            EqualityInference equalityInference = createEqualityInference(expression);

            ImmutableList.Builder<Expression> effectiveConjuncts = ImmutableList.builder();
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(expression)) {
                if (DeterminismEvaluator.isDeterministic(conjunct)) {
                    Expression rewritten = equalityInference.rewriteExpression(conjunct, in(symbols));
                    if (rewritten != null) {
                        effectiveConjuncts.add(rewritten);
                    }
                }
            }

            effectiveConjuncts.addAll(equalityInference.generateEqualitiesPartitionedBy(in(symbols)).getScopeEqualities());

            return combineConjuncts(effectiveConjuncts.build());
        }
    }
}
