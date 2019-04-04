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

import com.facebook.presto.sql.planner.iterative.GroupReference;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.ApplyNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.facebook.presto.sql.planner.iterative.Lookup.noLookup;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.castToExpression;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.isExpression;
import static java.util.Objects.requireNonNull;

public class ExpressionExtractor
{
    public static List<Expression> extractExpressions(PlanNode plan)
    {
        return extractExpressions(plan, noLookup());
    }

    public static List<Expression> extractExpressions(PlanNode plan, Lookup lookup)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(lookup, "lookup is null");

        ImmutableList.Builder<Expression> expressionsBuilder = ImmutableList.builder();
        plan.accept(new Visitor(true, lookup), expressionsBuilder);
        return expressionsBuilder.build();
    }

    public static List<Expression> extractExpressionsNonRecursive(PlanNode plan)
    {
        ImmutableList.Builder<Expression> expressionsBuilder = ImmutableList.builder();
        plan.accept(new Visitor(false, noLookup()), expressionsBuilder);
        return expressionsBuilder.build();
    }

    private ExpressionExtractor()
    {
    }

    private static class Visitor
            extends SimplePlanVisitor<ImmutableList.Builder<Expression>>
    {
        private final boolean recursive;
        private final Lookup lookup;

        Visitor(boolean recursive, Lookup lookup)
        {
            this.recursive = recursive;
            this.lookup = requireNonNull(lookup, "lookup is null");
        }

        @Override
        protected Void visitPlan(PlanNode node, ImmutableList.Builder<Expression> context)
        {
            if (recursive) {
                return super.visitPlan(node, context);
            }
            return null;
        }

        @Override
        public Void visitGroupReference(GroupReference node, ImmutableList.Builder<Expression> context)
        {
            return lookup.resolve(node).accept(this, context);
        }

        @Override
        public Void visitAggregation(AggregationNode node, ImmutableList.Builder<Expression> context)
        {
            node.getAggregations().values()
                    .forEach(aggregation -> context.add(aggregation.getCall()));
            return super.visitAggregation(node, context);
        }

        @Override
        public Void visitFilter(FilterNode node, ImmutableList.Builder<Expression> context)
        {
            context.add(node.getPredicate());
            return super.visitFilter(node, context);
        }

        @Override
        public Void visitProject(ProjectNode node, ImmutableList.Builder<Expression> context)
        {
            context.addAll(node.getAssignments().getExpressions());
            return super.visitProject(node, context);
        }

        @Override
        public Void visitJoin(JoinNode node, ImmutableList.Builder<Expression> context)
        {
            node.getFilter().ifPresent(context::add);
            return super.visitJoin(node, context);
        }

        @Override
        public Void visitValues(ValuesNode node, ImmutableList.Builder<Expression> context)
        {
            node.getRows().forEach(rowExpressions -> rowExpressions.forEach(rowExpression -> {
                if (isExpression(rowExpression)) {
                    context.add(castToExpression(rowExpression));
                }
            }));
            return super.visitValues(node, context);
        }

        @Override
        public Void visitApply(ApplyNode node, ImmutableList.Builder<Expression> context)
        {
            context.addAll(node.getSubqueryAssignments().getExpressions());
            return super.visitApply(node, context);
        }
    }
}
