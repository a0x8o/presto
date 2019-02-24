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

import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.sql.planner.OrderingScheme;
import com.facebook.presto.sql.planner.PartitioningScheme;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.sql.planner.plan.StatisticAggregations;
import com.facebook.presto.sql.planner.plan.StatisticAggregationsDescriptor;
import com.facebook.presto.sql.planner.plan.StatisticsWriterNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.ExpressionRewriter;
import com.facebook.presto.sql.tree.ExpressionTreeRewriter;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.facebook.presto.sql.planner.plan.AggregationNode.groupingSets;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

public class SymbolMapper
{
    private final Map<Symbol, Symbol> mapping;

    public SymbolMapper(Map<Symbol, Symbol> mapping)
    {
        this.mapping = ImmutableMap.copyOf(requireNonNull(mapping, "mapping is null"));
    }

    public Symbol map(Symbol symbol)
    {
        Symbol canonical = symbol;
        while (mapping.containsKey(canonical) && !mapping.get(canonical).equals(canonical)) {
            canonical = mapping.get(canonical);
        }
        return canonical;
    }

    public Expression map(Expression value)
    {
        return ExpressionTreeRewriter.rewriteWith(new ExpressionRewriter<Void>()
        {
            @Override
            public Expression rewriteSymbolReference(SymbolReference node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
            {
                Symbol canonical = map(Symbol.from(node));
                return canonical.toSymbolReference();
            }
        }, value);
    }

    public AggregationNode map(AggregationNode node, PlanNode source)
    {
        return map(node, source, node.getId());
    }

    public AggregationNode map(AggregationNode node, PlanNode source, PlanNodeIdAllocator idAllocator)
    {
        return map(node, source, idAllocator.getNextId());
    }

    private AggregationNode map(AggregationNode node, PlanNode source, PlanNodeId newNodeId)
    {
        ImmutableMap.Builder<Symbol, Aggregation> aggregations = ImmutableMap.builder();
        for (Entry<Symbol, Aggregation> entry : node.getAggregations().entrySet()) {
            aggregations.put(map(entry.getKey()), map(entry.getValue()));
        }

        return new AggregationNode(
                newNodeId,
                source,
                aggregations.build(),
                groupingSets(
                        mapAndDistinct(node.getGroupingKeys()),
                        node.getGroupingSetCount(),
                        node.getGlobalGroupingSets()),
                ImmutableList.of(),
                node.getStep(),
                node.getHashSymbol().map(this::map),
                node.getGroupIdSymbol().map(this::map));
    }

    private Aggregation map(Aggregation aggregation)
    {
        return new Aggregation(
                (FunctionCall) map(aggregation.getCall()),
                aggregation.getSignature(),
                aggregation.getMask().map(this::map));
    }

    public TopNNode map(TopNNode node, PlanNode source, PlanNodeId newNodeId)
    {
        ImmutableList.Builder<Symbol> symbols = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, SortOrder> orderings = ImmutableMap.builder();
        Set<Symbol> seenCanonicals = new HashSet<>(node.getOrderingScheme().getOrderBy().size());
        for (Symbol symbol : node.getOrderingScheme().getOrderBy()) {
            Symbol canonical = map(symbol);
            if (seenCanonicals.add(canonical)) {
                seenCanonicals.add(canonical);
                symbols.add(canonical);
                orderings.put(canonical, node.getOrderingScheme().getOrdering(symbol));
            }
        }

        return new TopNNode(
                newNodeId,
                source,
                node.getCount(),
                new OrderingScheme(symbols.build(), orderings.build()),
                node.getStep());
    }

    public TableWriterNode map(TableWriterNode node, PlanNode source)
    {
        return map(node, source, node.getId());
    }

    public TableWriterNode map(TableWriterNode node, PlanNode source, PlanNodeId newNodeId)
    {
        // Intentionally does not use canonicalizeAndDistinct as that would remove columns
        ImmutableList<Symbol> columns = node.getColumns().stream()
                .map(this::map)
                .collect(toImmutableList());

        return new TableWriterNode(
                newNodeId,
                source,
                node.getTarget(),
                map(node.getRowCountSymbol()),
                map(node.getFragmentSymbol()),
                columns,
                node.getColumnNames(),
                node.getPartitioningScheme().map(partitioningScheme -> canonicalize(partitioningScheme, source)),
                node.getStatisticsAggregation().map(this::map),
                node.getStatisticsAggregationDescriptor().map(this::map));
    }

    public StatisticsWriterNode map(StatisticsWriterNode node, PlanNode source)
    {
        return new StatisticsWriterNode(
                node.getId(),
                source,
                node.getTarget(),
                node.getRowCountSymbol(),
                node.isRowCountEnabled(),
                node.getDescriptor().map(this::map));
    }

    public TableFinishNode map(TableFinishNode node, PlanNode source)
    {
        return new TableFinishNode(
                node.getId(),
                source,
                node.getTarget(),
                map(node.getRowCountSymbol()),
                node.getStatisticsAggregation().map(this::map),
                node.getStatisticsAggregationDescriptor().map(descriptor -> descriptor.map(this::map)));
    }

    private PartitioningScheme canonicalize(PartitioningScheme scheme, PlanNode source)
    {
        return new PartitioningScheme(
                scheme.getPartitioning().translate(this::map),
                mapAndDistinct(source.getOutputSymbols()),
                scheme.getHashColumn().map(this::map),
                scheme.isReplicateNullsAndAny(),
                scheme.getBucketToPartition());
    }

    private StatisticAggregations map(StatisticAggregations statisticAggregations)
    {
        Map<Symbol, Aggregation> aggregations = statisticAggregations.getAggregations().entrySet().stream()
                .collect(toImmutableMap(entry -> map(entry.getKey()), entry -> map(entry.getValue())));
        return new StatisticAggregations(aggregations, mapAndDistinct(statisticAggregations.getGroupingSymbols()));
    }

    private StatisticAggregationsDescriptor<Symbol> map(StatisticAggregationsDescriptor<Symbol> descriptor)
    {
        return descriptor.map(this::map);
    }

    private List<Symbol> map(List<Symbol> outputs)
    {
        return outputs.stream()
                .map(this::map)
                .collect(toImmutableList());
    }

    private List<Symbol> mapAndDistinct(List<Symbol> outputs)
    {
        Set<Symbol> added = new HashSet<>();
        ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
        for (Symbol symbol : outputs) {
            Symbol canonical = map(symbol);
            if (added.add(canonical)) {
                builder.add(canonical);
            }
        }
        return builder.build();
    }

    public static SymbolMapper.Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private final ImmutableMap.Builder<Symbol, Symbol> mappings = ImmutableMap.builder();

        public SymbolMapper build()
        {
            return new SymbolMapper(mappings.build());
        }

        public void put(Symbol from, Symbol to)
        {
            mappings.put(from, to);
        }
    }
}
