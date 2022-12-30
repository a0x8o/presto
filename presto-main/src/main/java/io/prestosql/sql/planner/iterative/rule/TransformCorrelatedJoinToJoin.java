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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.optimizations.PlanNodeDecorrelator;
import io.prestosql.sql.planner.optimizations.PlanNodeDecorrelator.DecorrelatedNode;
import io.prestosql.sql.planner.plan.CorrelatedJoinNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.tree.Expression;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.matching.Pattern.nonEmpty;
import static io.prestosql.sql.ExpressionUtils.combineConjuncts;
import static io.prestosql.sql.planner.plan.CorrelatedJoinNode.Type.INNER;
import static io.prestosql.sql.planner.plan.CorrelatedJoinNode.Type.LEFT;
import static io.prestosql.sql.planner.plan.Patterns.CorrelatedJoin.correlation;
import static io.prestosql.sql.planner.plan.Patterns.correlatedJoin;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;

/**
 * Tries to decorrelate subquery and rewrite it using normal join.
 * Decorrelated predicates are part of join condition.
 */
public class TransformCorrelatedJoinToJoin
        implements Rule<CorrelatedJoinNode>
{
    private static final Pattern<CorrelatedJoinNode> PATTERN = correlatedJoin()
            .with(nonEmpty(correlation()));

    private final Metadata metadata;

    public TransformCorrelatedJoinToJoin(Metadata metadata)
    {
        this.metadata = metadata;
    }

    @Override
    public Pattern<CorrelatedJoinNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(CorrelatedJoinNode correlatedJoinNode, Captures captures, Context context)
    {
        checkArgument(correlatedJoinNode.getType() == INNER || correlatedJoinNode.getType() == LEFT, "correlation in %s JOIN", correlatedJoinNode.getType().name());
        PlanNode subquery = correlatedJoinNode.getSubquery();

        PlanNodeDecorrelator planNodeDecorrelator = new PlanNodeDecorrelator(metadata, context.getSymbolAllocator(), context.getLookup());
        Optional<DecorrelatedNode> decorrelatedNodeOptional = planNodeDecorrelator.decorrelateFilters(subquery, correlatedJoinNode.getCorrelation());
        if (!decorrelatedNodeOptional.isPresent()) {
            return Result.empty();
        }
        DecorrelatedNode decorrelatedSubquery = decorrelatedNodeOptional.get();

        Expression filter = combineConjuncts(
                metadata,
                decorrelatedSubquery.getCorrelatedPredicates().orElse(TRUE_LITERAL),
                correlatedJoinNode.getFilter());

        return Result.ofPlanNode(new JoinNode(
                correlatedJoinNode.getId(),
                correlatedJoinNode.getType().toJoinNodeType(),
                correlatedJoinNode.getInput(),
                decorrelatedSubquery.getNode(),
                ImmutableList.of(),
                correlatedJoinNode.getInput().getOutputSymbols(),
                correlatedJoinNode.getSubquery().getOutputSymbols(),
                filter.equals(TRUE_LITERAL) ? Optional.empty() : Optional.of(filter),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty()));
    }
}
