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

import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.iterative.Rule.Context;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.relational.SqlToRowExpressionTranslator;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NodeRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.facebook.presto.execution.warnings.WarningCollector.NOOP;
import static com.facebook.presto.sql.analyzer.ExpressionAnalyzer.getExpressionTypes;
import static com.facebook.presto.sql.planner.plan.Patterns.values;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.castToExpression;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.isExpression;
import static java.util.Objects.requireNonNull;

public class TranslateExpressions
{
    private final Metadata metadata;
    private final SqlParser sqlParser;

    public TranslateExpressions(Metadata metadata, SqlParser sqlParser)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParseris null");
    }

    public Set<Rule<?>> rules()
    {
        // TODO: finish all other PlanNodes that have Expression
        return ImmutableSet.of(
                new ValuesExpressionTranslation());
    }

    private final class ValuesExpressionTranslation
            implements Rule<ValuesNode>
    {
        @Override
        public Pattern<ValuesNode> getPattern()
        {
            return values();
        }

        @Override
        public Result apply(ValuesNode valuesNode, Captures captures, Context context)
        {
            boolean anyRewritten = false;
            ImmutableList.Builder<List<RowExpression>> rows = ImmutableList.builder();
            for (List<RowExpression> row : valuesNode.getRows()) {
                ImmutableList.Builder<RowExpression> newRow = ImmutableList.builder();
                for (RowExpression rowExpression : row) {
                    if (isExpression(rowExpression)) {
                        Expression expression = castToExpression(rowExpression);
                        RowExpression rewritten = toRowExpression(expression, context, ImmutableMap.of());
                        anyRewritten = true;
                        newRow.add(rewritten);
                    }
                    else {
                        newRow.add(rowExpression);
                    }
                }
                rows.add(newRow.build());
            }
            if (anyRewritten) {
                return Result.ofPlanNode(new ValuesNode(valuesNode.getId(), valuesNode.getOutputSymbols(), rows.build()));
            }
            return Result.empty();
        }
    }

    private RowExpression toRowExpression(Expression expression, Context context, Map<Symbol, Integer> sourceLayout)
    {
        Map<NodeRef<Expression>, Type> types = getExpressionTypes(
                context.getSession(),
                metadata,
                sqlParser,
                context.getSymbolAllocator().getTypes(),
                ImmutableList.of(expression),
                ImmutableList.of(),
                NOOP,
                false);

        return SqlToRowExpressionTranslator.translate(expression, types, sourceLayout, metadata.getFunctionManager(), metadata.getTypeManager(), context.getSession(), false);
    }
}
