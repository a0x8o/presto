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
package io.prestosql.sql.planner;

import io.prestosql.metadata.ResolvedFunction;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.NodeRef;

import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class ResolvedFunctionCallRewriter
{
    private ResolvedFunctionCallRewriter() {}

    public static Expression rewriteResolvedFunctions(Expression expression, Map<NodeRef<FunctionCall>, ResolvedFunction> resolvedFunctions)
    {
        return ExpressionTreeRewriter.rewriteWith(new Visitor(resolvedFunctions), expression);
    }

    private static class Visitor
            extends ExpressionRewriter<Void>
    {
        private final Map<NodeRef<FunctionCall>, ResolvedFunction> resolvedFunctions;

        public Visitor(Map<NodeRef<FunctionCall>, ResolvedFunction> resolvedFunctions)
        {
            this.resolvedFunctions = requireNonNull(resolvedFunctions, "resolvedFunctions is null");
        }

        @Override
        public Expression rewriteFunctionCall(FunctionCall node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            ResolvedFunction resolvedFunction = resolvedFunctions.get(NodeRef.of(node));
            checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

            FunctionCall rewritten = treeRewriter.defaultRewrite(node, context);
            return new FunctionCall(
                    rewritten.getLocation(),
                    resolvedFunction.toQualifiedName(),
                    rewritten.getWindow(),
                    rewritten.getFilter(),
                    rewritten.getOrderBy(),
                    rewritten.isDistinct(),
                    rewritten.getNullTreatment(),
                    rewritten.getArguments());
        }
    }
}
