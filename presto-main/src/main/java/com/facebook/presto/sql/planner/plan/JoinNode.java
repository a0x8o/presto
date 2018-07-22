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
package com.facebook.presto.sql.planner.plan;

import com.facebook.presto.sql.planner.SortExpressionContext;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Join;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import javax.annotation.concurrent.Immutable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.facebook.presto.sql.planner.SortExpressionExtractor.extractSortExpression;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.FULL;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.INNER;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.LEFT;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.RIGHT;
import static com.facebook.presto.util.SpatialJoinUtils.isSpatialJoinFilter;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@Immutable
public class JoinNode
        extends PlanNode
{
    private final Type type;
    private final PlanNode left;
    private final PlanNode right;
    private final List<EquiJoinClause> criteria;
    /**
     * List of output symbols produced by join. Output symbols
     * must be from either left or right side of join. Symbols
     * from left join side must precede symbols from right side
     * of join.
     */
    private final List<Symbol> outputSymbols;
    private final Optional<Expression> filter;
    private final Optional<Symbol> leftHashSymbol;
    private final Optional<Symbol> rightHashSymbol;
    private final Optional<DistributionType> distributionType;
    private Optional<Boolean> spatialJoin = Optional.empty();

    @JsonCreator
    public JoinNode(@JsonProperty("id") PlanNodeId id,
            @JsonProperty("type") Type type,
            @JsonProperty("left") PlanNode left,
            @JsonProperty("right") PlanNode right,
            @JsonProperty("criteria") List<EquiJoinClause> criteria,
            @JsonProperty("outputSymbols") List<Symbol> outputSymbols,
            @JsonProperty("filter") Optional<Expression> filter,
            @JsonProperty("leftHashSymbol") Optional<Symbol> leftHashSymbol,
            @JsonProperty("rightHashSymbol") Optional<Symbol> rightHashSymbol,
            @JsonProperty("distributionType") Optional<DistributionType> distributionType)
    {
        super(id);
        requireNonNull(type, "type is null");
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
        requireNonNull(criteria, "criteria is null");
        requireNonNull(outputSymbols, "outputSymbols is null");
        requireNonNull(filter, "filter is null");
        requireNonNull(leftHashSymbol, "leftHashSymbol is null");
        requireNonNull(rightHashSymbol, "rightHashSymbol is null");
        requireNonNull(distributionType, "distributionType is null");

        this.type = type;
        this.left = left;
        this.right = right;
        this.criteria = ImmutableList.copyOf(criteria);
        this.outputSymbols = ImmutableList.copyOf(outputSymbols);
        this.filter = filter;
        this.leftHashSymbol = leftHashSymbol;
        this.rightHashSymbol = rightHashSymbol;
        this.distributionType = distributionType;

        List<Symbol> inputSymbols = ImmutableList.<Symbol>builder()
                .addAll(left.getOutputSymbols())
                .addAll(right.getOutputSymbols())
                .build();
        checkArgument(new HashSet<>(inputSymbols).containsAll(outputSymbols), "Left and right join inputs do not contain all output symbols");
        checkArgument(!isCrossJoin() || inputSymbols.equals(outputSymbols), "Cross join does not support output symbols pruning or reordering");

        checkArgument(!(criteria.isEmpty() && leftHashSymbol.isPresent()), "Left hash symbol is only valid in an equijoin");
        checkArgument(!(criteria.isEmpty() && rightHashSymbol.isPresent()), "Right hash symbol is only valid in an equijoin");
    }

    public JoinNode flipChildren()
    {
        return new JoinNode(
                getId(),
                flipType(type),
                right,
                left,
                flipJoinCriteria(criteria),
                flipOutputSymbols(getOutputSymbols(), left, right),
                filter,
                rightHashSymbol,
                leftHashSymbol,
                distributionType);
    }

    private static Type flipType(Type type)
    {
        switch (type) {
            case INNER:
                return INNER;
            case FULL:
                return FULL;
            case LEFT:
                return RIGHT;
            case RIGHT:
                return LEFT;
            default:
                throw new IllegalStateException("No inverse defined for join type: " + type);
        }
    }

    private static List<EquiJoinClause> flipJoinCriteria(List<EquiJoinClause> joinCriteria)
    {
        return joinCriteria.stream()
                .map(EquiJoinClause::flip)
                .collect(toImmutableList());
    }

    private static List<Symbol> flipOutputSymbols(List<Symbol> outputSymbols, PlanNode left, PlanNode right)
    {
        List<Symbol> leftSymbols = outputSymbols.stream()
                .filter(symbol -> left.getOutputSymbols().contains(symbol))
                .collect(Collectors.toList());
        List<Symbol> rightSymbols = outputSymbols.stream()
                .filter(symbol -> right.getOutputSymbols().contains(symbol))
                .collect(Collectors.toList());
        return ImmutableList.<Symbol>builder()
                .addAll(rightSymbols)
                .addAll(leftSymbols)
                .build();
    }

    public enum DistributionType
    {
        PARTITIONED,
        REPLICATED
    }

    public enum Type
    {
        INNER("InnerJoin"),
        LEFT("LeftJoin"),
        RIGHT("RightJoin"),
        FULL("FullJoin");

        private final String joinLabel;

        Type(String joinLabel)
        {
            this.joinLabel = joinLabel;
        }

        public String getJoinLabel()
        {
            return joinLabel;
        }

        public static Type typeConvert(Join.Type joinType)
        {
            // Omit SEMI join types because they must be inferred by the planner and not part of the SQL parse tree
            switch (joinType) {
                case CROSS:
                case IMPLICIT:
                case INNER:
                    return Type.INNER;
                case LEFT:
                    return Type.LEFT;
                case RIGHT:
                    return Type.RIGHT;
                case FULL:
                    return Type.FULL;
                default:
                    throw new UnsupportedOperationException("Unsupported join type: " + joinType);
            }
        }
    }

    @JsonProperty("type")
    public Type getType()
    {
        return type;
    }

    @JsonProperty("left")
    public PlanNode getLeft()
    {
        return left;
    }

    @JsonProperty("right")
    public PlanNode getRight()
    {
        return right;
    }

    @JsonProperty("criteria")
    public List<EquiJoinClause> getCriteria()
    {
        return criteria;
    }

    @JsonProperty("filter")
    public Optional<Expression> getFilter()
    {
        return filter;
    }

    public Optional<SortExpressionContext> getSortExpressionContext()
    {
        return filter
                .flatMap(filter -> extractSortExpression(ImmutableSet.copyOf(right.getOutputSymbols()), filter));
    }

    @JsonProperty("leftHashSymbol")
    public Optional<Symbol> getLeftHashSymbol()
    {
        return leftHashSymbol;
    }

    @JsonProperty("rightHashSymbol")
    public Optional<Symbol> getRightHashSymbol()
    {
        return rightHashSymbol;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of(left, right);
    }

    @Override
    @JsonProperty("outputSymbols")
    public List<Symbol> getOutputSymbols()
    {
        return outputSymbols;
    }

    @JsonProperty("distributionType")
    public Optional<DistributionType> getDistributionType()
    {
        return distributionType;
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitJoin(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        checkArgument(newChildren.size() == 2, "expected newChildren to contain 2 nodes");
        PlanNode newLeft = newChildren.get(0);
        PlanNode newRight = newChildren.get(1);
        // Reshuffle join output symbols (for cross joins) since order of symbols in child nodes might have changed
        List<Symbol> newOutputSymbols = Stream.concat(newLeft.getOutputSymbols().stream(), newRight.getOutputSymbols().stream())
                .filter(outputSymbols::contains)
                .collect(toImmutableList());
        return new JoinNode(getId(), type, newLeft, newRight, criteria, newOutputSymbols, filter, leftHashSymbol, rightHashSymbol, distributionType);
    }

    public JoinNode withDistributionType(DistributionType distributionType)
    {
        return new JoinNode(getId(), type, left, right, criteria, outputSymbols, filter, leftHashSymbol, rightHashSymbol, Optional.of(distributionType));
    }

    public boolean isCrossJoin()
    {
        return criteria.isEmpty() && !filter.isPresent() && type == INNER;
    }

    public boolean isSpatialJoin()
    {
        if (spatialJoin.isPresent()) {
            return spatialJoin.get();
        }
        spatialJoin = Optional.of((type == INNER || type == LEFT) && criteria.isEmpty() && filter.isPresent() && isSpatialJoinFilter(left, right, filter.get()));
        return spatialJoin.get();
    }

    public static class EquiJoinClause
    {
        private final Symbol left;
        private final Symbol right;

        @JsonCreator
        public EquiJoinClause(@JsonProperty("left") Symbol left, @JsonProperty("right") Symbol right)
        {
            this.left = requireNonNull(left, "left is null");
            this.right = requireNonNull(right, "right is null");
        }

        @JsonProperty("left")
        public Symbol getLeft()
        {
            return left;
        }

        @JsonProperty("right")
        public Symbol getRight()
        {
            return right;
        }

        public ComparisonExpression toExpression()
        {
            return new ComparisonExpression(ComparisonExpression.Operator.EQUAL, left.toSymbolReference(), right.toSymbolReference());
        }

        public EquiJoinClause flip()
        {
            return new EquiJoinClause(right, left);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }

            if (obj == null || !this.getClass().equals(obj.getClass())) {
                return false;
            }

            EquiJoinClause other = (EquiJoinClause) obj;

            return Objects.equals(this.left, other.left) &&
                    Objects.equals(this.right, other.right);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(left, right);
        }

        @Override
        public String toString()
        {
            return format("%s = %s", left, right);
        }
    }
}
