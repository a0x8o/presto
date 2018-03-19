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
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.TableLayoutResult;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.GroupingProperty;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.SortingProperty;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.DomainTranslator;
import com.facebook.presto.sql.planner.ExpressionInterpreter;
import com.facebook.presto.sql.planner.LookupSymbolResolver;
import com.facebook.presto.sql.planner.Partitioning;
import com.facebook.presto.sql.planner.PartitioningScheme;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.ApplyNode;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.ChildReplacer;
import com.facebook.presto.sql.planner.plan.DistinctLimitNode;
import com.facebook.presto.sql.planner.plan.EnforceSingleRowNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.ExplainAnalyzeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.GroupIdNode;
import com.facebook.presto.sql.planner.plan.IndexJoinNode;
import com.facebook.presto.sql.planner.plan.IndexSourceNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LateralJoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanVisitor;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.RowNumberNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.TopNRowNumberNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.UnnestNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.facebook.presto.SystemSessionProperties.isColocatedJoinEnabled;
import static com.facebook.presto.SystemSessionProperties.isForceSingleNodeOutput;
import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.filterConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.filterDeterministicConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.filterNonDeterministicConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.referencesAny;
import static com.facebook.presto.sql.analyzer.ExpressionAnalyzer.getExpressionTypes;
import static com.facebook.presto.sql.planner.FragmentTableScanCounter.countSources;
import static com.facebook.presto.sql.planner.FragmentTableScanCounter.hasMultipleSources;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.FIXED_ARBITRARY_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.FIXED_HASH_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SCALED_WRITER_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static com.facebook.presto.sql.planner.optimizations.ActualProperties.Global.partitionedOn;
import static com.facebook.presto.sql.planner.optimizations.ActualProperties.Global.singleStreamPartition;
import static com.facebook.presto.sql.planner.optimizations.LocalProperties.grouped;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Scope.REMOTE;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Type.GATHER;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.gatheringExchange;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.partitionedExchange;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.replicatedExchange;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class AddExchanges
        implements PlanOptimizer
{
    private final SqlParser parser;
    private final Metadata metadata;

    public AddExchanges(Metadata metadata, SqlParser parser)
    {
        this.metadata = metadata;
        this.parser = parser;
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, Map<Symbol, Type> types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator)
    {
        Context context = new Context(PreferredProperties.any(), ImmutableList.of());
        PlanWithProperties result = plan.accept(new Rewriter(idAllocator, symbolAllocator, session), context);
        return result.getNode();
    }

    private static class Context
    {
        private final PreferredProperties preferredProperties;
        private final List<Symbol> correlations;

        Context(PreferredProperties preferredProperties, List<Symbol> correlations)
        {
            this.preferredProperties = preferredProperties;
            this.correlations = ImmutableList.copyOf(requireNonNull(correlations, "correlations is null"));
        }

        Context withPreferredProperties(PreferredProperties preferredProperties)
        {
            return new Context(preferredProperties, correlations);
        }

        PreferredProperties getPreferredProperties()
        {
            return preferredProperties;
        }

        List<Symbol> getCorrelations()
        {
            return correlations;
        }
    }

    private class Rewriter
            extends PlanVisitor<PlanWithProperties, Context>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final SymbolAllocator symbolAllocator;
        private final Map<Symbol, Type> types;
        private final Session session;
        private final boolean distributedIndexJoins;
        private final boolean preferStreamingOperators;
        private final boolean redistributeWrites;
        private final boolean scaleWriters;

        public Rewriter(PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator, Session session)
        {
            this.idAllocator = idAllocator;
            this.symbolAllocator = symbolAllocator;
            this.types = ImmutableMap.copyOf(symbolAllocator.getTypes());
            this.session = session;
            this.distributedIndexJoins = SystemSessionProperties.isDistributedIndexJoinEnabled(session);
            this.redistributeWrites = SystemSessionProperties.isRedistributeWrites(session);
            this.scaleWriters = SystemSessionProperties.isScaleWriters(session);
            this.preferStreamingOperators = SystemSessionProperties.preferStreamingOperators(session);
        }

        @Override
        protected PlanWithProperties visitPlan(PlanNode node, Context context)
        {
            return rebaseAndDeriveProperties(node, planChild(node, context));
        }

        @Override
        public PlanWithProperties visitProject(ProjectNode node, Context context)
        {
            Map<Symbol, Symbol> identities = computeIdentityTranslations(node.getAssignments());
            PreferredProperties translatedPreferred = context.getPreferredProperties().translate(symbol -> Optional.ofNullable(identities.get(symbol)));

            return rebaseAndDeriveProperties(node, planChild(node, context.withPreferredProperties(translatedPreferred)));
        }

        @Override
        public PlanWithProperties visitOutput(OutputNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.undistributed()));

            if (!child.getProperties().isSingleNode() && isForceSingleNodeOutput(session)) {
                child = withDerivedProperties(
                        gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                        child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitEnforceSingleRow(EnforceSingleRowNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.any()));

            if (!child.getProperties().isSingleNode()) {
                child = withDerivedProperties(
                        gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                        child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitAggregation(AggregationNode node, Context context)
        {
            Set<Symbol> partitioningRequirement = ImmutableSet.copyOf(node.getGroupingKeys());

            boolean preferSingleNode = (node.hasEmptyGroupingSet() && !node.hasNonEmptyGroupingSet()) ||
                    (node.hasDefaultOutput() && !node.isDecomposable(metadata.getFunctionRegistry()));

            PreferredProperties preferredProperties = preferSingleNode ? PreferredProperties.undistributed() : PreferredProperties.any();

            if (!node.getGroupingKeys().isEmpty()) {
                preferredProperties = PreferredProperties.partitionedWithLocal(partitioningRequirement, grouped(node.getGroupingKeys()))
                        .mergeWithParent(context.getPreferredProperties());
            }

            PlanWithProperties child = planChild(node, context.withPreferredProperties(preferredProperties));

            if (child.getProperties().isSingleNode()) {
                // If already unpartitioned, just drop the single aggregation back on
                return rebaseAndDeriveProperties(node, child);
            }

            if (preferSingleNode) {
                // For queries with only empty grouping sets like
                //
                // SELECT count(*) FROM lineitem;
                //
                // there is no need for distributed aggregation. Single node FINAL aggregation will suffice,
                // since all input have to be aggregated into one line output.
                //
                // If aggregation must produce default output and it is not decomposable, we can not distribute it
                child = withDerivedProperties(
                        gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                        child.getProperties());
            }
            else if (!child.getProperties().isStreamPartitionedOn(partitioningRequirement) && !child.getProperties().isNodePartitionedOn(partitioningRequirement)) {
                child = withDerivedProperties(
                        partitionedExchange(idAllocator.getNextId(), REMOTE, child.getNode(), node.getGroupingKeys(), node.getHashSymbol()),
                        child.getProperties());
            }
            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitGroupId(GroupIdNode node, Context context)
        {
            PreferredProperties childPreference = context.getPreferredProperties().translate(translateGroupIdSymbols(node));
            PlanWithProperties child = planChild(node, context.withPreferredProperties(childPreference));
            return rebaseAndDeriveProperties(node, child);
        }

        private Function<Symbol, Optional<Symbol>> translateGroupIdSymbols(GroupIdNode node)
        {
            return symbol -> {
                if (node.getArgumentMappings().containsKey(symbol)) {
                    return Optional.of(node.getArgumentMappings().get(symbol));
                }

                if (node.getCommonGroupingColumns().contains(symbol)) {
                    return Optional.of(node.getGroupingSetMappings().get(symbol));
                }

                return Optional.empty();
            };
        }

        @Override
        public PlanWithProperties visitMarkDistinct(MarkDistinctNode node, Context context)
        {
            PreferredProperties preferredChildProperties = PreferredProperties.partitionedWithLocal(ImmutableSet.copyOf(node.getDistinctSymbols()), grouped(node.getDistinctSymbols()))
                    .mergeWithParent(context.getPreferredProperties());
            PlanWithProperties child = node.getSource().accept(this, context.withPreferredProperties(preferredChildProperties));

            if (child.getProperties().isSingleNode() ||
                    !child.getProperties().isStreamPartitionedOn(node.getDistinctSymbols())) {
                child = withDerivedProperties(
                        partitionedExchange(
                                idAllocator.getNextId(),
                                REMOTE,
                                child.getNode(),
                                node.getDistinctSymbols(),
                                node.getHashSymbol()),
                        child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitWindow(WindowNode node, Context context)
        {
            List<LocalProperty<Symbol>> desiredProperties = new ArrayList<>();
            if (!node.getPartitionBy().isEmpty()) {
                desiredProperties.add(new GroupingProperty<>(node.getPartitionBy()));
            }
            node.getOrderingScheme().ifPresent(orderingScheme ->
                    orderingScheme.getOrderBy().stream()
                            .map(symbol -> new SortingProperty<>(symbol, orderingScheme.getOrdering(symbol)))
                            .forEach(desiredProperties::add));

            PlanWithProperties child = planChild(
                    node,
                    context.withPreferredProperties(
                            PreferredProperties.partitionedWithLocal(ImmutableSet.copyOf(node.getPartitionBy()), desiredProperties)
                                    .mergeWithParent(context.getPreferredProperties())));

            if (!child.getProperties().isStreamPartitionedOn(node.getPartitionBy())) {
                if (node.getPartitionBy().isEmpty()) {
                    child = withDerivedProperties(
                            gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                            child.getProperties());
                }
                else {
                    child = withDerivedProperties(
                            partitionedExchange(idAllocator.getNextId(), REMOTE, child.getNode(), node.getPartitionBy(), node.getHashSymbol()),
                            child.getProperties());
                }
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitRowNumber(RowNumberNode node, Context context)
        {
            if (node.getPartitionBy().isEmpty()) {
                PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.undistributed()));

                if (!child.getProperties().isSingleNode()) {
                    child = withDerivedProperties(
                            gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                            child.getProperties());
                }

                return rebaseAndDeriveProperties(node, child);
            }

            PlanWithProperties child = planChild(node, context.withPreferredProperties(
                    PreferredProperties.partitionedWithLocal(ImmutableSet.copyOf(node.getPartitionBy()), grouped(node.getPartitionBy()))
                            .mergeWithParent(context.getPreferredProperties())));

            // TODO: add config option/session property to force parallel plan if child is unpartitioned and window has a PARTITION BY clause
            if (!child.getProperties().isStreamPartitionedOn(node.getPartitionBy())) {
                child = withDerivedProperties(
                        partitionedExchange(
                                idAllocator.getNextId(),
                                REMOTE,
                                child.getNode(),
                                node.getPartitionBy(),
                                node.getHashSymbol()),
                        child.getProperties());
            }

            // TODO: streaming

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitTopNRowNumber(TopNRowNumberNode node, Context context)
        {
            PreferredProperties preferredChildProperties;
            Function<PlanNode, PlanNode> addExchange;

            if (node.getPartitionBy().isEmpty()) {
                preferredChildProperties = PreferredProperties.any();
                addExchange = partial -> gatheringExchange(idAllocator.getNextId(), REMOTE, partial);
            }
            else {
                preferredChildProperties = PreferredProperties.partitionedWithLocal(ImmutableSet.copyOf(node.getPartitionBy()), grouped(node.getPartitionBy()))
                        .mergeWithParent(context.getPreferredProperties());
                addExchange = partial -> partitionedExchange(idAllocator.getNextId(), REMOTE, partial, node.getPartitionBy(), node.getHashSymbol());
            }

            PlanWithProperties child = planChild(node, context.withPreferredProperties(preferredChildProperties));
            if (!child.getProperties().isStreamPartitionedOn(node.getPartitionBy())) {
                // add exchange + push function to child
                child = withDerivedProperties(
                        new TopNRowNumberNode(
                                idAllocator.getNextId(),
                                child.getNode(),
                                node.getSpecification(),
                                node.getRowNumberSymbol(),
                                node.getMaxRowCountPerPartition(),
                                true,
                                node.getHashSymbol()),
                        child.getProperties());

                child = withDerivedProperties(addExchange.apply(child.getNode()), child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitTopN(TopNNode node, Context context)
        {
            PlanWithProperties child;
            switch (node.getStep()) {
                case SINGLE:
                case FINAL:
                    child = planChild(node, context.withPreferredProperties(PreferredProperties.undistributed()));
                    if (!child.getProperties().isSingleNode()) {
                        child = withDerivedProperties(
                                gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                                child.getProperties());
                    }
                    break;
                case PARTIAL:
                    child = planChild(node, context.withPreferredProperties(PreferredProperties.any()));
                    break;
                default:
                    throw new UnsupportedOperationException(format("Unsupported step for TopN [%s]", node.getStep()));
            }
            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitSort(SortNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.undistributed()));

            if (!child.getProperties().isSingleNode()) {
                child = withDerivedProperties(
                        gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                        child.getProperties());
            }
            else {
                // current plan so far is single node, so local properties are effectively global properties
                // skip the SortNode if the local properties guarantee ordering on Sort keys
                // TODO: This should be extracted as a separate optimizer once the planner is able to reason about the ordering of each operator
                List<LocalProperty<Symbol>> desiredProperties = new ArrayList<>();
                for (Symbol symbol : node.getOrderingScheme().getOrderBy()) {
                    desiredProperties.add(new SortingProperty<>(symbol, node.getOrderingScheme().getOrdering(symbol)));
                }

                if (LocalProperties.match(child.getProperties().getLocalProperties(), desiredProperties).stream()
                        .noneMatch(Optional::isPresent)) {
                    return child;
                }
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitLimit(LimitNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.any()));

            if (!child.getProperties().isSingleNode()) {
                child = withDerivedProperties(
                        new LimitNode(idAllocator.getNextId(), child.getNode(), node.getCount(), true),
                        child.getProperties());

                child = withDerivedProperties(
                        gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                        child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitDistinctLimit(DistinctLimitNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.any()));

            if (!child.getProperties().isSingleNode()) {
                child = withDerivedProperties(
                        gatheringExchange(
                                idAllocator.getNextId(),
                                REMOTE,
                                new DistinctLimitNode(idAllocator.getNextId(), child.getNode(), node.getLimit(), true, node.getDistinctSymbols(), node.getHashSymbol())),
                        child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitFilter(FilterNode node, Context context)
        {
            if (node.getSource() instanceof TableScanNode) {
                return planTableScan((TableScanNode) node.getSource(), node.getPredicate(), context);
            }

            return rebaseAndDeriveProperties(node, planChild(node, context));
        }

        @Override
        public PlanWithProperties visitTableScan(TableScanNode node, Context context)
        {
            return planTableScan(node, BooleanLiteral.TRUE_LITERAL, context);
        }

        @Override
        public PlanWithProperties visitTableWriter(TableWriterNode node, Context context)
        {
            PlanWithProperties source = node.getSource().accept(this, context);

            Optional<PartitioningScheme> partitioningScheme = node.getPartitioningScheme();
            if (!partitioningScheme.isPresent()) {
                if (scaleWriters) {
                    partitioningScheme = Optional.of(new PartitioningScheme(Partitioning.create(SCALED_WRITER_DISTRIBUTION, ImmutableList.of()), source.getNode().getOutputSymbols()));
                }
                else if (redistributeWrites) {
                    partitioningScheme = Optional.of(new PartitioningScheme(Partitioning.create(FIXED_ARBITRARY_DISTRIBUTION, ImmutableList.of()), source.getNode().getOutputSymbols()));
                }
            }

            if (partitioningScheme.isPresent() && !source.getProperties().isNodePartitionedOn(partitioningScheme.get().getPartitioning(), false)) {
                source = withDerivedProperties(
                        partitionedExchange(
                                idAllocator.getNextId(),
                                REMOTE,
                                source.getNode(),
                                partitioningScheme.get()),
                        source.getProperties());
            }
            return rebaseAndDeriveProperties(node, source);
        }

        private PlanWithProperties planTableScan(TableScanNode node, Expression predicate, Context context)
        {
            // don't include non-deterministic predicates
            Expression deterministicPredicate = filterDeterministicConjuncts(predicate);

            DomainTranslator.ExtractionResult decomposedPredicate = DomainTranslator.fromPredicate(
                    metadata,
                    session,
                    deterministicPredicate,
                    types);

            TupleDomain<ColumnHandle> newDomain = decomposedPredicate.getTupleDomain()
                    .transform(node.getAssignments()::get)
                    .intersect(node.getCurrentConstraint());

            Map<ColumnHandle, Symbol> assignments = ImmutableBiMap.copyOf(node.getAssignments()).inverse();

            // Simplify the tuple domain to avoid creating an expression with too many nodes that's
            // expensive to evaluate in the call to shouldPrune below.
            Expression constraint = combineConjuncts(
                    deterministicPredicate,
                    DomainTranslator.toPredicate(newDomain.simplify().transform(assignments::get)));

            LayoutConstraintEvaluator evaluator = new LayoutConstraintEvaluator(
                    session,
                    symbolAllocator.getTypes(),
                    node.getAssignments(),
                    filterConjuncts(constraint, conjunct -> !referencesAny(conjunct, context.getCorrelations())));

            // Layouts will be returned in order of the connector's preference
            List<TableLayoutResult> layouts = metadata.getLayouts(
                    session, node.getTable(),
                    new Constraint<>(newDomain, evaluator::isCandidate),
                    Optional.of(node.getOutputSymbols().stream()
                            .map(node.getAssignments()::get)
                            .collect(toImmutableSet())));

            if (layouts.isEmpty()) {
                return new PlanWithProperties(
                        new ValuesNode(idAllocator.getNextId(), node.getOutputSymbols(), ImmutableList.of()),
                        ActualProperties.builder()
                                .global(singleStreamPartition())
                                .build());
            }

            // Filter out layouts that cannot supply all the required columns
            layouts = layouts.stream()
                    .filter(layout -> layout.hasAllOutputs(node))
                    .collect(toList());
            checkState(!layouts.isEmpty(), "No usable layouts for %s", node);

            List<PlanWithProperties> possiblePlans = layouts.stream()
                    .map(layout -> {
                        TableScanNode tableScan = new TableScanNode(
                                node.getId(),
                                node.getTable(),
                                node.getOutputSymbols(),
                                node.getAssignments(),
                                Optional.of(layout.getLayout().getHandle()),
                                newDomain.intersect(layout.getLayout().getPredicate()),
                                Optional.ofNullable(node.getOriginalConstraint()).orElse(predicate));

                        PlanWithProperties result = new PlanWithProperties(tableScan, deriveProperties(tableScan, ImmutableList.of()));

                        Expression resultingPredicate = combineConjuncts(
                                DomainTranslator.toPredicate(layout.getUnenforcedConstraint().transform(assignments::get)),
                                filterNonDeterministicConjuncts(predicate),
                                decomposedPredicate.getRemainingExpression());

                        if (!BooleanLiteral.TRUE_LITERAL.equals(resultingPredicate)) {
                            return withDerivedProperties(
                                    new FilterNode(idAllocator.getNextId(), result.getNode(), resultingPredicate),
                                    deriveProperties(tableScan, ImmutableList.of()));
                        }

                        return result;
                    })
                    .collect(toList());

            return pickPlan(possiblePlans, context);
        }

        /**
         * possiblePlans should be provided in layout preference order
         */
        private PlanWithProperties pickPlan(List<PlanWithProperties> possiblePlans, Context context)
        {
            checkArgument(!possiblePlans.isEmpty());

            if (preferStreamingOperators) {
                possiblePlans = new ArrayList<>(possiblePlans);
                Collections.sort(possiblePlans, Comparator.comparing(PlanWithProperties::getProperties, streamingExecutionPreference(context.getPreferredProperties()))); // stable sort; is Collections.min() guaranteed to be stable?
            }

            return possiblePlans.get(0);
        }

        @Override
        public PlanWithProperties visitValues(ValuesNode node, Context context)
        {
            return new PlanWithProperties(
                    node,
                    ActualProperties.builder()
                            .global(singleStreamPartition())
                            .build());
        }

        @Override
        public PlanWithProperties visitExplainAnalyze(ExplainAnalyzeNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.any()));

            // if the child is already a gathering exchange, don't add another
            if ((child.getNode() instanceof ExchangeNode) && ((ExchangeNode) child.getNode()).getType() == ExchangeNode.Type.GATHER) {
                return rebaseAndDeriveProperties(node, child);
            }

            // Always add an exchange because ExplainAnalyze should be in its own stage
            child = withDerivedProperties(
                    gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                    child.getProperties());

            return rebaseAndDeriveProperties(node, child);
        }

        @Override
        public PlanWithProperties visitTableFinish(TableFinishNode node, Context context)
        {
            PlanWithProperties child = planChild(node, context.withPreferredProperties(PreferredProperties.any()));

            // if the child is already a gathering exchange, don't add another
            if ((child.getNode() instanceof ExchangeNode) && ((ExchangeNode) child.getNode()).getType().equals(GATHER)) {
                return rebaseAndDeriveProperties(node, child);
            }

            if (!child.getProperties().isSingleNode() || !child.getProperties().isCoordinatorOnly()) {
                child = withDerivedProperties(
                        gatheringExchange(idAllocator.getNextId(), REMOTE, child.getNode()),
                        child.getProperties());
            }

            return rebaseAndDeriveProperties(node, child);
        }

        private <T> SetMultimap<T, T> createMapping(List<T> keys, List<T> values)
        {
            checkArgument(keys.size() == values.size(), "Inputs must have the same size");
            ImmutableSetMultimap.Builder<T, T> builder = ImmutableSetMultimap.builder();
            for (int i = 0; i < keys.size(); i++) {
                builder.put(keys.get(i), values.get(i));
            }
            return builder.build();
        }

        private <T> Function<T, Optional<T>> createTranslator(SetMultimap<T, T> inputToOutput)
        {
            return input -> inputToOutput.get(input).stream().findAny();
        }

        private <T> Function<T, T> createDirectTranslator(SetMultimap<T, T> inputToOutput)
        {
            return input -> inputToOutput.get(input).iterator().next();
        }

        @Override
        public PlanWithProperties visitJoin(JoinNode node, Context context)
        {
            List<Symbol> leftSymbols = node.getCriteria().stream()
                    .map(JoinNode.EquiJoinClause::getLeft)
                    .collect(toImmutableList());
            List<Symbol> rightSymbols = node.getCriteria().stream()
                    .map(JoinNode.EquiJoinClause::getRight)
                    .collect(toImmutableList());
            JoinNode.Type type = node.getType();

            PlanWithProperties left;
            PlanWithProperties right;

            JoinNode.DistributionType distributionType = node.getDistributionType().orElseThrow(() -> new IllegalArgumentException("distributionType not yet set"));
            if (distributionType == JoinNode.DistributionType.PARTITIONED) {
                SetMultimap<Symbol, Symbol> rightToLeft = createMapping(rightSymbols, leftSymbols);
                SetMultimap<Symbol, Symbol> leftToRight = createMapping(leftSymbols, rightSymbols);

                left = node.getLeft().accept(this, context.withPreferredProperties(PreferredProperties.partitioned(ImmutableSet.copyOf(leftSymbols))));

                if (left.getProperties().isNodePartitionedOn(leftSymbols) && !left.getProperties().isSingleNode()) {
                    Partitioning rightPartitioning = left.getProperties().translate(createTranslator(leftToRight)).getNodePartitioning().get();
                    right = node.getRight().accept(this, context.withPreferredProperties(PreferredProperties.partitioned(rightPartitioning)));
                    if (!right.getProperties().isNodePartitionedWith(left.getProperties(), rightToLeft::get)) {
                        right = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, right.getNode(), new PartitioningScheme(rightPartitioning, right.getNode().getOutputSymbols())),
                                right.getProperties());
                    }
                }
                else {
                    right = node.getRight().accept(this, context.withPreferredProperties(PreferredProperties.partitioned(ImmutableSet.copyOf(rightSymbols))));

                    if (right.getProperties().isNodePartitionedOn(rightSymbols) && !right.getProperties().isSingleNode()) {
                        Partitioning leftPartitioning = right.getProperties().translate(createTranslator(rightToLeft)).getNodePartitioning().get();
                        left = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, left.getNode(), new PartitioningScheme(leftPartitioning, left.getNode().getOutputSymbols())),
                                left.getProperties());
                    }
                    else {
                        left = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, left.getNode(), leftSymbols, Optional.empty()),
                                left.getProperties());
                        right = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, right.getNode(), rightSymbols, Optional.empty()),
                                right.getProperties());
                    }
                }

                verify(left.getProperties().isNodePartitionedWith(right.getProperties(), leftToRight::get));

                // if colocated joins are disabled, force redistribute when using a custom partitioning
                if (!isColocatedJoinEnabled(session) && hasMultipleSources(left.getNode(), right.getNode())) {
                    Partitioning rightPartitioning = left.getProperties().translate(createTranslator(leftToRight)).getNodePartitioning().get();
                    right = withDerivedProperties(
                            partitionedExchange(idAllocator.getNextId(), REMOTE, right.getNode(), new PartitioningScheme(rightPartitioning, right.getNode().getOutputSymbols())),
                            right.getProperties());
                }
            }
            else {
                // Broadcast Join
                left = node.getLeft().accept(this, context.withPreferredProperties(PreferredProperties.any()));
                right = node.getRight().accept(this, context.withPreferredProperties(PreferredProperties.any()));

                if (left.getProperties().isSingleNode()) {
                    if (!right.getProperties().isSingleNode() ||
                            (!isColocatedJoinEnabled(session) && hasMultipleSources(left.getNode(), right.getNode()))) {
                        right = withDerivedProperties(
                                gatheringExchange(idAllocator.getNextId(), REMOTE, right.getNode()),
                                right.getProperties());
                    }
                }
                else {
                    right = withDerivedProperties(
                            replicatedExchange(idAllocator.getNextId(), REMOTE, right.getNode()),
                            right.getProperties());
                }
            }

            JoinNode result = new JoinNode(node.getId(),
                    type,
                    left.getNode(),
                    right.getNode(),
                    node.getCriteria(),
                    node.getOutputSymbols(),
                    node.getFilter(),
                    node.getLeftHashSymbol(),
                    node.getRightHashSymbol(),
                    node.getDistributionType());

            return new PlanWithProperties(result, deriveProperties(result, ImmutableList.of(left.getProperties(), right.getProperties())));
        }

        @Override
        public PlanWithProperties visitUnnest(UnnestNode node, Context context)
        {
            PreferredProperties translatedPreferred = context.getPreferredProperties().translate(symbol -> node.getReplicateSymbols().contains(symbol) ? Optional.of(symbol) : Optional.empty());

            return rebaseAndDeriveProperties(node, planChild(node, context.withPreferredProperties(translatedPreferred)));
        }

        @Override
        public PlanWithProperties visitSemiJoin(SemiJoinNode node, Context context)
        {
            PlanWithProperties source;
            PlanWithProperties filteringSource;

            SemiJoinNode.DistributionType distributionType = node.getDistributionType().orElseThrow(() -> new IllegalArgumentException("distributionType not yet set"));
            if (distributionType == SemiJoinNode.DistributionType.PARTITIONED) {
                List<Symbol> sourceSymbols = ImmutableList.of(node.getSourceJoinSymbol());
                List<Symbol> filteringSourceSymbols = ImmutableList.of(node.getFilteringSourceJoinSymbol());

                SetMultimap<Symbol, Symbol> sourceToFiltering = createMapping(sourceSymbols, filteringSourceSymbols);
                SetMultimap<Symbol, Symbol> filteringToSource = createMapping(filteringSourceSymbols, sourceSymbols);

                source = node.getSource().accept(this, context.withPreferredProperties(PreferredProperties.partitioned(ImmutableSet.copyOf(sourceSymbols))));

                if (source.getProperties().isNodePartitionedOn(sourceSymbols) && !source.getProperties().isSingleNode()) {
                    Partitioning filteringPartitioning = source.getProperties().translate(createTranslator(sourceToFiltering)).getNodePartitioning().get();
                    filteringSource = node.getFilteringSource().accept(this, context.withPreferredProperties(PreferredProperties.partitionedWithNullsAndAnyReplicated(filteringPartitioning)));
                    if (!source.getProperties().withReplicatedNulls(true).isNodePartitionedWith(filteringSource.getProperties(), sourceToFiltering::get)) {
                        filteringSource = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, filteringSource.getNode(), new PartitioningScheme(
                                        filteringPartitioning,
                                        filteringSource.getNode().getOutputSymbols(),
                                        Optional.empty(),
                                        true,
                                        Optional.empty())),
                                filteringSource.getProperties());
                    }
                }
                else {
                    filteringSource = node.getFilteringSource().accept(this, context.withPreferredProperties(PreferredProperties.partitionedWithNullsAndAnyReplicated(ImmutableSet.copyOf(filteringSourceSymbols))));

                    if (filteringSource.getProperties().isNodePartitionedOn(filteringSourceSymbols, true) && !filteringSource.getProperties().isSingleNode()) {
                        Partitioning sourcePartitioning = filteringSource.getProperties().translate(createTranslator(filteringToSource)).getNodePartitioning().get();
                        source = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, source.getNode(), new PartitioningScheme(sourcePartitioning, source.getNode().getOutputSymbols())),
                                source.getProperties());
                    }
                    else {
                        source = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, source.getNode(), sourceSymbols, Optional.empty()),
                                source.getProperties());
                        filteringSource = withDerivedProperties(
                                partitionedExchange(idAllocator.getNextId(), REMOTE, filteringSource.getNode(), filteringSourceSymbols, Optional.empty(), true),
                                filteringSource.getProperties());
                    }
                }

                verify(source.getProperties().withReplicatedNulls(true).isNodePartitionedWith(filteringSource.getProperties(), sourceToFiltering::get));

                // if colocated joins are disabled, force redistribute when using a custom partitioning
                if (!isColocatedJoinEnabled(session) && hasMultipleSources(source.getNode(), filteringSource.getNode())) {
                    Partitioning filteringPartitioning = source.getProperties().translate(createTranslator(sourceToFiltering)).getNodePartitioning().get();
                    filteringSource = withDerivedProperties(
                            partitionedExchange(idAllocator.getNextId(), REMOTE, filteringSource.getNode(), new PartitioningScheme(
                                    filteringPartitioning,
                                    filteringSource.getNode().getOutputSymbols(),
                                    Optional.empty(),
                                    true,
                                    Optional.empty())),
                            filteringSource.getProperties());
                }
            }
            else {
                source = node.getSource().accept(this, context.withPreferredProperties(PreferredProperties.any()));
                // Delete operator works fine even if TableScans on the filtering (right) side is not co-located with itself. It only cares about the corresponding TableScan,
                // which is always on the source (left) side. Therefore, hash-partitioned semi-join is always allowed on the filtering side.
                filteringSource = node.getFilteringSource().accept(this, context.withPreferredProperties(PreferredProperties.any()));

                // make filtering source match requirements of source
                if (source.getProperties().isSingleNode()) {
                    if (!filteringSource.getProperties().isSingleNode() ||
                            (!isColocatedJoinEnabled(session) && hasMultipleSources(source.getNode(), filteringSource.getNode()))) {
                        filteringSource = withDerivedProperties(
                                gatheringExchange(idAllocator.getNextId(), REMOTE, filteringSource.getNode()),
                                filteringSource.getProperties());
                    }
                }
                else {
                    filteringSource = withDerivedProperties(
                            replicatedExchange(idAllocator.getNextId(), REMOTE, filteringSource.getNode()),
                            filteringSource.getProperties());
                }
            }

            return rebaseAndDeriveProperties(node, ImmutableList.of(source, filteringSource));
        }

        @Override
        public PlanWithProperties visitIndexJoin(IndexJoinNode node, Context context)
        {
            List<Symbol> joinColumns = node.getCriteria().stream()
                    .map(IndexJoinNode.EquiJoinClause::getProbe)
                    .collect(toImmutableList());

            // Only prefer grouping on join columns if no parent local property preferences
            List<LocalProperty<Symbol>> desiredLocalProperties = context.getPreferredProperties().getLocalProperties().isEmpty() ? grouped(joinColumns) : ImmutableList.of();

            PlanWithProperties probeSource = node.getProbeSource().accept(this, context.withPreferredProperties(
                    PreferredProperties.partitionedWithLocal(ImmutableSet.copyOf(joinColumns), desiredLocalProperties)
                            .mergeWithParent(context.getPreferredProperties())));
            ActualProperties probeProperties = probeSource.getProperties();

            PlanWithProperties indexSource = node.getIndexSource().accept(this, context.withPreferredProperties(PreferredProperties.any()));

            // TODO: allow repartitioning if unpartitioned to increase parallelism
            if (shouldRepartitionForIndexJoin(joinColumns, context.getPreferredProperties(), probeProperties)) {
                probeSource = withDerivedProperties(
                        partitionedExchange(idAllocator.getNextId(), REMOTE, probeSource.getNode(), joinColumns, node.getProbeHashSymbol()),
                        probeProperties);
            }

            // TODO: if input is grouped, create streaming join

            // index side is really a nested-loops plan, so don't add exchanges
            PlanNode result = ChildReplacer.replaceChildren(node, ImmutableList.of(probeSource.getNode(), node.getIndexSource()));
            return new PlanWithProperties(result, deriveProperties(result, ImmutableList.of(probeSource.getProperties(), indexSource.getProperties())));
        }

        private boolean shouldRepartitionForIndexJoin(List<Symbol> joinColumns, PreferredProperties parentPreferredProperties, ActualProperties probeProperties)
        {
            // See if distributed index joins are enabled
            if (!distributedIndexJoins) {
                return false;
            }

            // No point in repartitioning if the plan is not distributed
            if (probeProperties.isSingleNode()) {
                return false;
            }

            Optional<PreferredProperties.PartitioningProperties> parentPartitioningPreferences = parentPreferredProperties.getGlobalProperties()
                    .flatMap(PreferredProperties.Global::getPartitioningProperties);

            // Disable repartitioning if it would disrupt a parent's partitioning preference when streaming is enabled
            boolean parentAlreadyPartitionedOnChild = parentPartitioningPreferences
                    .map(partitioning -> probeProperties.isStreamPartitionedOn(partitioning.getPartitioningColumns()))
                    .orElse(false);
            if (preferStreamingOperators && parentAlreadyPartitionedOnChild) {
                return false;
            }

            // Otherwise, repartition if we need to align with the join columns
            if (!probeProperties.isStreamPartitionedOn(joinColumns)) {
                return true;
            }

            // If we are already partitioned on the join columns because the data has been forced effectively into one stream,
            // then we should repartition if that would make a difference (from the single stream state).
            return probeProperties.isEffectivelySingleStream() && probeProperties.isStreamRepartitionEffective(joinColumns);
        }

        @Override
        public PlanWithProperties visitIndexSource(IndexSourceNode node, Context context)
        {
            return new PlanWithProperties(
                    node,
                    ActualProperties.builder()
                            .global(singleStreamPartition())
                            .build());
        }

        private Function<Symbol, Optional<Symbol>> outputToInputTranslator(UnionNode node, int sourceIndex)
        {
            return symbol -> Optional.of(node.getSymbolMapping().get(symbol).get(sourceIndex));
        }

        private Partitioning selectUnionPartitioning(UnionNode node, Context context, PreferredProperties.PartitioningProperties parentPreference)
        {
            // Use the parent's requested partitioning if available
            if (parentPreference.getPartitioning().isPresent()) {
                return parentPreference.getPartitioning().get();
            }

            // Try planning the children to see if any of them naturally produce a partitioning (for now, just select the first)
            boolean nullsAndAnyReplicated = parentPreference.isNullsAndAnyReplicated();
            for (int sourceIndex = 0; sourceIndex < node.getSources().size(); sourceIndex++) {
                PreferredProperties.PartitioningProperties childPartitioning = parentPreference.translate(outputToInputTranslator(node, sourceIndex)).get();
                PreferredProperties childPreferred = PreferredProperties.builder()
                        .global(PreferredProperties.Global.distributed(childPartitioning.withNullsAndAnyReplicated(nullsAndAnyReplicated)))
                        .build();
                PlanWithProperties child = node.getSources().get(sourceIndex).accept(this, context.withPreferredProperties(childPreferred));
                if (child.getProperties().isNodePartitionedOn(childPartitioning.getPartitioningColumns(), nullsAndAnyReplicated)) {
                    Function<Symbol, Optional<Symbol>> childToParent = createTranslator(createMapping(node.sourceOutputLayout(sourceIndex), node.getOutputSymbols()));
                    return child.getProperties().translate(childToParent).getNodePartitioning().get();
                }
            }

            // Otherwise, choose an arbitrary partitioning over the columns
            return Partitioning.create(FIXED_HASH_DISTRIBUTION, ImmutableList.copyOf(parentPreference.getPartitioningColumns()));
        }

        @Override
        public PlanWithProperties visitUnion(UnionNode node, Context context)
        {
            PreferredProperties parentPreference = context.getPreferredProperties();
            Optional<PreferredProperties.Global> parentGlobal = parentPreference.getGlobalProperties();
            if (parentGlobal.isPresent() && parentGlobal.get().isDistributed() && parentGlobal.get().getPartitioningProperties().isPresent()) {
                PreferredProperties.PartitioningProperties parentPartitioningPreference = parentGlobal.get().getPartitioningProperties().get();
                boolean nullsAndAnyReplicated = parentPartitioningPreference.isNullsAndAnyReplicated();
                Partitioning desiredParentPartitioning = selectUnionPartitioning(node, context, parentPartitioningPreference);

                ImmutableList.Builder<PlanNode> partitionedSources = ImmutableList.builder();
                ImmutableListMultimap.Builder<Symbol, Symbol> outputToSourcesMapping = ImmutableListMultimap.builder();

                for (int sourceIndex = 0; sourceIndex < node.getSources().size(); sourceIndex++) {
                    Partitioning childPartitioning = desiredParentPartitioning.translate(createDirectTranslator(createMapping(node.getOutputSymbols(), node.sourceOutputLayout(sourceIndex))));

                    PreferredProperties childPreferred = PreferredProperties.builder()
                            .global(PreferredProperties.Global.distributed(PreferredProperties.PartitioningProperties.partitioned(childPartitioning)
                                    .withNullsAndAnyReplicated(nullsAndAnyReplicated)))
                            .build();

                    PlanWithProperties source = node.getSources().get(sourceIndex).accept(this, context.withPreferredProperties(childPreferred));
                    if (!source.getProperties().isNodePartitionedOn(childPartitioning, nullsAndAnyReplicated)) {
                        source = withDerivedProperties(
                                partitionedExchange(
                                        idAllocator.getNextId(),
                                        REMOTE,
                                        source.getNode(),
                                        new PartitioningScheme(
                                                childPartitioning,
                                                source.getNode().getOutputSymbols(),
                                                Optional.empty(),
                                                nullsAndAnyReplicated,
                                                Optional.empty())),
                                source.getProperties());
                    }
                    partitionedSources.add(source.getNode());

                    for (int column = 0; column < node.getOutputSymbols().size(); column++) {
                        outputToSourcesMapping.put(node.getOutputSymbols().get(column), node.sourceOutputLayout(sourceIndex).get(column));
                    }
                }
                UnionNode newNode = new UnionNode(
                        node.getId(),
                        partitionedSources.build(),
                        outputToSourcesMapping.build(),
                        ImmutableList.copyOf(outputToSourcesMapping.build().keySet()));

                return new PlanWithProperties(
                        newNode,
                        ActualProperties.builder()
                                .global(partitionedOn(desiredParentPartitioning, Optional.of(desiredParentPartitioning)))
                                .build()
                                .withReplicatedNulls(parentPartitioningPreference.isNullsAndAnyReplicated()));
            }

            // first, classify children into partitioned and unpartitioned
            List<PlanNode> unpartitionedChildren = new ArrayList<>();
            List<List<Symbol>> unpartitionedOutputLayouts = new ArrayList<>();

            List<PlanNode> partitionedChildren = new ArrayList<>();
            List<List<Symbol>> partitionedOutputLayouts = new ArrayList<>();

            List<PlanWithProperties> plannedChildren = new ArrayList<>();

            for (int i = 0; i < node.getSources().size(); i++) {
                PlanWithProperties child = node.getSources().get(i).accept(this, context.withPreferredProperties(PreferredProperties.any()));
                plannedChildren.add(child);
                if (child.getProperties().isSingleNode()) {
                    unpartitionedChildren.add(child.getNode());
                    unpartitionedOutputLayouts.add(node.sourceOutputLayout(i));
                }
                else {
                    partitionedChildren.add(child.getNode());
                    // union may drop or duplicate symbols from the input so we must provide an exact mapping
                    partitionedOutputLayouts.add(node.sourceOutputLayout(i));
                }
            }

            PlanNode result;
            if (!partitionedChildren.isEmpty() && unpartitionedChildren.isEmpty()) {
                // parent does not have preference or prefers some partitioning without any explicit partitioning - just use
                // children partitioning and don't GATHER partitioned inputs
                // TODO: add FIXED_ARBITRARY_DISTRIBUTION support on non empty unpartitionedChildren
                if (!parentGlobal.isPresent() || parentGlobal.get().isDistributed()) {
                    return arbitraryDistributeUnion(node, plannedChildren, partitionedChildren, partitionedOutputLayouts);
                }

                // add a gathering exchange above partitioned inputs
                result = new ExchangeNode(
                        idAllocator.getNextId(),
                        GATHER,
                        REMOTE,
                        new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), node.getOutputSymbols()),
                        partitionedChildren,
                        partitionedOutputLayouts);
            }
            else if (!unpartitionedChildren.isEmpty()) {
                if (!partitionedChildren.isEmpty()) {
                    // add a gathering exchange above partitioned inputs and fold it into the set of unpartitioned inputs
                    // NOTE: new symbols for ExchangeNode output are required in order to keep plan logically correct with new local union below

                    List<Symbol> exchangeOutputLayout = node.getOutputSymbols().stream()
                            .map(outputSymbol -> symbolAllocator.newSymbol(outputSymbol.getName(), types.get(outputSymbol)))
                            .collect(toImmutableList());

                    result = new ExchangeNode(
                            idAllocator.getNextId(),
                            GATHER,
                            REMOTE,
                            new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), exchangeOutputLayout),
                            partitionedChildren,
                            partitionedOutputLayouts);

                    unpartitionedChildren.add(result);
                    unpartitionedOutputLayouts.add(result.getOutputSymbols());
                }

                ImmutableListMultimap.Builder<Symbol, Symbol> mappings = ImmutableListMultimap.builder();
                for (int i = 0; i < node.getOutputSymbols().size(); i++) {
                    for (List<Symbol> outputLayout : unpartitionedOutputLayouts) {
                        mappings.put(node.getOutputSymbols().get(i), outputLayout.get(i));
                    }
                }

                // add local union for all unpartitioned inputs
                result = new UnionNode(node.getId(), unpartitionedChildren, mappings.build(), ImmutableList.copyOf(mappings.build().keySet()));
            }
            else {
                throw new IllegalStateException("both unpartitionedChildren partitionedChildren are empty");
            }

            return new PlanWithProperties(
                    result,
                    ActualProperties.builder()
                            .global(singleStreamPartition())
                            .build());
        }

        private PlanWithProperties arbitraryDistributeUnion(
                UnionNode node,
                List<PlanWithProperties> plannedChildren,
                List<PlanNode> partitionedChildren,
                List<List<Symbol>> partitionedOutputLayouts)
        {
            // TODO: can we insert LOCAL exchange for one child SOURCE distributed and another HASH distributed?
            if (countSources(partitionedChildren) == 0) {
                // No source distributed child, we can use insert LOCAL exchange
                // TODO: if all children have the same partitioning, pass this partitioning to the parent
                // instead of "arbitraryPartition".
                return new PlanWithProperties(node.replaceChildren(
                        plannedChildren.stream()
                                .map(PlanWithProperties::getNode)
                                .collect(toList())));
            }
            else {
                // Presto currently can not execute stage that has multiple table scans, so in that case
                // we have to insert REMOTE exchange with FIXED_ARBITRARY_DISTRIBUTION instead of local exchange
                return new PlanWithProperties(
                        new ExchangeNode(
                                idAllocator.getNextId(),
                                REPARTITION,
                                REMOTE,
                                new PartitioningScheme(Partitioning.create(FIXED_ARBITRARY_DISTRIBUTION, ImmutableList.of()), node.getOutputSymbols()),
                                partitionedChildren,
                                partitionedOutputLayouts));
            }
        }

        @Override
        public PlanWithProperties visitApply(ApplyNode node, Context context)
        {
            throw new IllegalStateException("Unexpected node: " + node.getClass().getName());
        }

        @Override
        public PlanWithProperties visitLateralJoin(LateralJoinNode node, Context context)
        {
            throw new IllegalStateException("Unexpected node: " + node.getClass().getName());
        }

        private PlanWithProperties planChild(PlanNode node, Context context)
        {
            return getOnlyElement(node.getSources()).accept(this, context);
        }

        private PlanWithProperties rebaseAndDeriveProperties(PlanNode node, PlanWithProperties child)
        {
            return withDerivedProperties(
                    ChildReplacer.replaceChildren(node, ImmutableList.of(child.getNode())),
                    child.getProperties());
        }

        private PlanWithProperties rebaseAndDeriveProperties(PlanNode node, List<PlanWithProperties> children)
        {
            PlanNode result = node.replaceChildren(
                    children.stream()
                            .map(PlanWithProperties::getNode)
                            .collect(toList()));
            return new PlanWithProperties(result, deriveProperties(result, children.stream().map(PlanWithProperties::getProperties).collect(toList())));
        }

        private PlanWithProperties withDerivedProperties(PlanNode node, ActualProperties inputProperties)
        {
            return new PlanWithProperties(node, deriveProperties(node, inputProperties));
        }

        private ActualProperties deriveProperties(PlanNode result, ActualProperties inputProperties)
        {
            return PropertyDerivations.deriveProperties(result, inputProperties, metadata, session, types, parser);
        }

        private ActualProperties deriveProperties(PlanNode result, List<ActualProperties> inputProperties)
        {
            return PropertyDerivations.deriveProperties(result, inputProperties, metadata, session, types, parser);
        }
    }

    private static Map<Symbol, Symbol> computeIdentityTranslations(Assignments assignments)
    {
        Map<Symbol, Symbol> outputToInput = new HashMap<>();
        for (Map.Entry<Symbol, Expression> assignment : assignments.getMap().entrySet()) {
            if (assignment.getValue() instanceof SymbolReference) {
                outputToInput.put(assignment.getKey(), Symbol.from(assignment.getValue()));
            }
        }
        return outputToInput;
    }

    @VisibleForTesting
    static Comparator<ActualProperties> streamingExecutionPreference(PreferredProperties preferred)
    {
        // Calculating the matches can be a bit expensive, so cache the results between comparisons
        LoadingCache<List<LocalProperty<Symbol>>, List<Optional<LocalProperty<Symbol>>>> matchCache = CacheBuilder.newBuilder()
                .build(CacheLoader.from(actualProperties -> LocalProperties.match(actualProperties, preferred.getLocalProperties())));

        return (actual1, actual2) -> {
            List<Optional<LocalProperty<Symbol>>> matchLayout1 = matchCache.getUnchecked(actual1.getLocalProperties());
            List<Optional<LocalProperty<Symbol>>> matchLayout2 = matchCache.getUnchecked(actual2.getLocalProperties());

            return ComparisonChain.start()
                    .compareTrueFirst(hasLocalOptimization(preferred.getLocalProperties(), matchLayout1), hasLocalOptimization(preferred.getLocalProperties(), matchLayout2))
                    .compareTrueFirst(meetsPartitioningRequirements(preferred, actual1), meetsPartitioningRequirements(preferred, actual2))
                    .compare(matchLayout1, matchLayout2, matchedLayoutPreference())
                    .result();
        };
    }

    private static <T> boolean hasLocalOptimization(List<LocalProperty<T>> desiredLayout, List<Optional<LocalProperty<T>>> matchResult)
    {
        checkArgument(desiredLayout.size() == matchResult.size());
        if (matchResult.isEmpty()) {
            return false;
        }
        // Optimizations can be applied if the first LocalProperty has been modified in the match in any way
        return !matchResult.get(0).equals(Optional.of(desiredLayout.get(0)));
    }

    private static boolean meetsPartitioningRequirements(PreferredProperties preferred, ActualProperties actual)
    {
        if (!preferred.getGlobalProperties().isPresent()) {
            return true;
        }
        PreferredProperties.Global preferredGlobal = preferred.getGlobalProperties().get();
        if (!preferredGlobal.isDistributed()) {
            return actual.isSingleNode();
        }
        if (!preferredGlobal.getPartitioningProperties().isPresent()) {
            return !actual.isSingleNode();
        }
        return actual.isStreamPartitionedOn(preferredGlobal.getPartitioningProperties().get().getPartitioningColumns());
    }

    // Prefer the match result that satisfied the most requirements
    private static <T> Comparator<List<Optional<LocalProperty<T>>>> matchedLayoutPreference()
    {
        return (matchLayout1, matchLayout2) -> {
            Iterator<Optional<LocalProperty<T>>> match1Iterator = matchLayout1.iterator();
            Iterator<Optional<LocalProperty<T>>> match2Iterator = matchLayout2.iterator();
            while (match1Iterator.hasNext() && match2Iterator.hasNext()) {
                Optional<LocalProperty<T>> match1 = match1Iterator.next();
                Optional<LocalProperty<T>> match2 = match2Iterator.next();
                if (match1.isPresent() && match2.isPresent()) {
                    return Integer.compare(match1.get().getColumns().size(), match2.get().getColumns().size());
                }
                else if (match1.isPresent()) {
                    return 1;
                }
                else if (match2.isPresent()) {
                    return -1;
                }
            }
            checkState(!match1Iterator.hasNext() && !match2Iterator.hasNext()); // Should be the same size
            return 0;
        };
    }

    @VisibleForTesting
    static class PlanWithProperties
    {
        private final PlanNode node;
        private final ActualProperties properties;

        public PlanWithProperties(PlanNode node)
        {
            this(node, ActualProperties.builder().build());
        }

        public PlanWithProperties(PlanNode node, ActualProperties properties)
        {
            this.node = node;
            this.properties = properties;
        }

        public PlanNode getNode()
        {
            return node;
        }

        public ActualProperties getProperties()
        {
            return properties;
        }
    }

    private class LayoutConstraintEvaluator
    {
        private final Map<Symbol, ColumnHandle> assignments;
        private final ExpressionInterpreter evaluator;

        public LayoutConstraintEvaluator(Session session, Map<Symbol, Type> types, Map<Symbol, ColumnHandle> assignments, Expression expression)
        {
            this.assignments = assignments;

            Map<NodeRef<Expression>, Type> expressionTypes = getExpressionTypes(session, metadata, parser, types, expression, emptyList());

            evaluator = ExpressionInterpreter.expressionOptimizer(expression, metadata, session, expressionTypes);
        }

        private boolean isCandidate(Map<ColumnHandle, NullableValue> bindings)
        {
            LookupSymbolResolver inputs = new LookupSymbolResolver(assignments, bindings);

            // If any conjuncts evaluate to FALSE or null, then the whole predicate will never be true and so the partition should be pruned
            Object optimized = evaluator.optimize(inputs);
            if (Boolean.FALSE.equals(optimized) || optimized == null || optimized instanceof NullLiteral) {
                return false;
            }

            return true;
        }
    }
}
