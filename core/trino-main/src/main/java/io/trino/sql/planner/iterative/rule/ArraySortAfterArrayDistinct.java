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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.trino.metadata.Metadata;
import io.trino.metadata.ResolvedFunction;
import io.trino.operator.scalar.ArrayDistinctFunction;
import io.trino.operator.scalar.ArraySortFunction;
import io.trino.spi.function.CatalogSchemaFunctionName;
import io.trino.spi.type.Type;
import io.trino.sql.PlannerContext;
import io.trino.sql.planner.BuiltinFunctionCallBuilder;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.ExpressionTreeRewriter;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.SymbolReference;

import java.util.List;
import java.util.Set;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.metadata.GlobalFunctionCatalog.builtinFunctionName;

public class ArraySortAfterArrayDistinct
        extends ExpressionRewriteRuleSet
{
    private static final CatalogSchemaFunctionName ARRAY_DISTINCT_NAME = builtinFunctionName(ArrayDistinctFunction.NAME);
    private static final CatalogSchemaFunctionName ARRAY_SORT_NAME = builtinFunctionName(ArraySortFunction.NAME);

    public ArraySortAfterArrayDistinct(PlannerContext plannerContext)
    {
        super((expression, context) -> rewrite(expression, plannerContext.getMetadata()));
    }

    @Override
    public Set<Rule<?>> rules()
    {
        return ImmutableSet.of(
                projectExpressionRewrite(),
                filterExpressionRewrite(),
                joinExpressionRewrite(),
                valuesExpressionRewrite(),
                patternRecognitionExpressionRewrite());
    }

    private static Expression rewrite(Expression expression, Metadata metadata)
    {
        if (expression instanceof SymbolReference) {
            return expression;
        }
        return ExpressionTreeRewriter.rewriteWith(new Visitor(metadata), expression);
    }

    private static class Visitor
            extends io.trino.sql.tree.ExpressionRewriter<Void>
    {
        private final Metadata metadata;

        public Visitor(Metadata metadata)
        {
            this.metadata = metadata;
        }

        @Override
        public Expression rewriteFunctionCall(FunctionCall node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            FunctionCall rewritten = treeRewriter.defaultRewrite(node, context);
            if (metadata.decodeFunction(rewritten.getName()).getSignature().getName().equals(ARRAY_DISTINCT_NAME) &&
                    getOnlyElement(rewritten.getArguments()) instanceof FunctionCall) {
                Expression expression = getOnlyElement(rewritten.getArguments());
                FunctionCall functionCall = (FunctionCall) expression;
                ResolvedFunction resolvedFunction = metadata.decodeFunction(functionCall.getName());
                if (resolvedFunction.getSignature().getName().equals(ARRAY_SORT_NAME)) {
                    List<Expression> arraySortArguments = functionCall.getArguments();
                    List<Type> arraySortArgumentsTypes = resolvedFunction.getSignature().getArgumentTypes();

                    FunctionCall arrayDistinctCall = BuiltinFunctionCallBuilder.resolve(metadata)
                            .setName(ArrayDistinctFunction.NAME)
                            .setArguments(
                                    ImmutableList.of(arraySortArgumentsTypes.get(0)),
                                    ImmutableList.of(arraySortArguments.get(0)))
                            .build();

                    BuiltinFunctionCallBuilder arraySortCallBuilder = BuiltinFunctionCallBuilder.resolve(metadata)
                            .setName(ArraySortFunction.NAME)
                            .addArgument(arraySortArgumentsTypes.get(0), arrayDistinctCall);

                    if (arraySortArguments.size() == 2) {
                        arraySortCallBuilder.addArgument(arraySortArgumentsTypes.get(1), arraySortArguments.get(1));
                    }
                    return arraySortCallBuilder.build();
                }
            }
            return rewritten;
        }
    }
}
