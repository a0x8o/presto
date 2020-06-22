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
package io.prestosql.sql;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.DeterminismEvaluator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolsExtractor;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.IsNullPredicate;
import io.prestosql.sql.tree.LambdaExpression;
import io.prestosql.sql.tree.LogicalBinaryExpression;
import io.prestosql.sql.tree.LogicalBinaryExpression.Operator;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.SymbolReference;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.sql.tree.BooleanLiteral.FALSE_LITERAL;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.IS_DISTINCT_FROM;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public final class ExpressionUtils
{
    private ExpressionUtils() {}

    public static List<Expression> extractConjuncts(Expression expression)
    {
        return extractPredicates(LogicalBinaryExpression.Operator.AND, expression);
    }

    public static List<Expression> extractDisjuncts(Expression expression)
    {
        return extractPredicates(LogicalBinaryExpression.Operator.OR, expression);
    }

    public static List<Expression> extractPredicates(LogicalBinaryExpression expression)
    {
        return extractPredicates(expression.getOperator(), expression);
    }

    public static List<Expression> extractPredicates(LogicalBinaryExpression.Operator operator, Expression expression)
    {
        ImmutableList.Builder<Expression> resultBuilder = ImmutableList.builder();
        extractPredicates(operator, expression, resultBuilder);
        return resultBuilder.build();
    }

    private static void extractPredicates(LogicalBinaryExpression.Operator operator, Expression expression, ImmutableList.Builder<Expression> resultBuilder)
    {
        if (expression instanceof LogicalBinaryExpression && ((LogicalBinaryExpression) expression).getOperator() == operator) {
            LogicalBinaryExpression logicalBinaryExpression = (LogicalBinaryExpression) expression;
            extractPredicates(operator, logicalBinaryExpression.getLeft(), resultBuilder);
            extractPredicates(operator, logicalBinaryExpression.getRight(), resultBuilder);
        }
        else {
            resultBuilder.add(expression);
        }
    }

    public static Expression and(Expression... expressions)
    {
        return and(Arrays.asList(expressions));
    }

    public static Expression and(Collection<Expression> expressions)
    {
        return binaryExpression(LogicalBinaryExpression.Operator.AND, expressions);
    }

    public static Expression or(Expression... expressions)
    {
        return or(Arrays.asList(expressions));
    }

    public static Expression or(Collection<Expression> expressions)
    {
        return binaryExpression(LogicalBinaryExpression.Operator.OR, expressions);
    }

    public static Expression binaryExpression(LogicalBinaryExpression.Operator operator, Collection<Expression> expressions)
    {
        requireNonNull(operator, "operator is null");
        requireNonNull(expressions, "expressions is null");

        if (expressions.isEmpty()) {
            switch (operator) {
                case AND:
                    return TRUE_LITERAL;
                case OR:
                    return FALSE_LITERAL;
                default:
                    throw new IllegalArgumentException("Unsupported LogicalBinaryExpression operator");
            }
        }

        // Build balanced tree for efficient recursive processing that
        // preserves the evaluation order of the input expressions.
        //
        // The tree is built bottom up by combining pairs of elements into
        // binary AND expressions.
        //
        // Example:
        //
        // Initial state:
        //  a b c d e
        //
        // First iteration:
        //
        //  /\    /\   e
        // a  b  c  d
        //
        // Second iteration:
        //
        //    / \    e
        //  /\   /\
        // a  b c  d
        //
        //
        // Last iteration:
        //
        //      / \
        //    / \  e
        //  /\   /\
        // a  b c  d

        Queue<Expression> queue = new ArrayDeque<>(expressions);
        while (queue.size() > 1) {
            Queue<Expression> buffer = new ArrayDeque<>();

            // combine pairs of elements
            while (queue.size() >= 2) {
                buffer.add(new LogicalBinaryExpression(operator, queue.remove(), queue.remove()));
            }

            // if there's and odd number of elements, just append the last one
            if (!queue.isEmpty()) {
                buffer.add(queue.remove());
            }

            // continue processing the pairs that were just built
            queue = buffer;
        }

        return queue.remove();
    }

    public static Expression combinePredicates(Metadata metadata, Operator operator, Expression... expressions)
    {
        return combinePredicates(metadata, operator, Arrays.asList(expressions));
    }

    public static Expression combinePredicates(Metadata metadata, Operator operator, Collection<Expression> expressions)
    {
        if (operator == LogicalBinaryExpression.Operator.AND) {
            return combineConjuncts(metadata, expressions);
        }

        return combineDisjuncts(metadata, expressions);
    }

    public static Expression combineConjuncts(Metadata metadata, Expression... expressions)
    {
        return combineConjuncts(metadata, Arrays.asList(expressions));
    }

    public static Expression combineConjuncts(Metadata metadata, Collection<Expression> expressions)
    {
        requireNonNull(expressions, "expressions is null");

        List<Expression> conjuncts = expressions.stream()
                .flatMap(e -> ExpressionUtils.extractConjuncts(e).stream())
                .filter(e -> !e.equals(TRUE_LITERAL))
                .collect(toList());

        conjuncts = removeDuplicates(metadata, conjuncts);

        if (conjuncts.contains(FALSE_LITERAL)) {
            return FALSE_LITERAL;
        }

        return and(conjuncts);
    }

    public static Expression combineConjunctsWithDuplicates(Collection<Expression> expressions)
    {
        requireNonNull(expressions, "expressions is null");

        List<Expression> conjuncts = expressions.stream()
                .flatMap(e -> ExpressionUtils.extractConjuncts(e).stream())
                .filter(e -> !e.equals(TRUE_LITERAL))
                .collect(toList());

        if (conjuncts.contains(FALSE_LITERAL)) {
            return FALSE_LITERAL;
        }

        return and(conjuncts);
    }

    public static Expression combineDisjuncts(Metadata metadata, Expression... expressions)
    {
        return combineDisjuncts(metadata, Arrays.asList(expressions));
    }

    public static Expression combineDisjuncts(Metadata metadata, Collection<Expression> expressions)
    {
        return combineDisjunctsWithDefault(metadata, expressions, FALSE_LITERAL);
    }

    public static Expression combineDisjunctsWithDefault(Metadata metadata, Collection<Expression> expressions, Expression emptyDefault)
    {
        requireNonNull(expressions, "expressions is null");

        List<Expression> disjuncts = expressions.stream()
                .flatMap(e -> ExpressionUtils.extractDisjuncts(e).stream())
                .filter(e -> !e.equals(FALSE_LITERAL))
                .collect(toList());

        disjuncts = removeDuplicates(metadata, disjuncts);

        if (disjuncts.contains(TRUE_LITERAL)) {
            return TRUE_LITERAL;
        }

        return disjuncts.isEmpty() ? emptyDefault : or(disjuncts);
    }

    public static Expression filterDeterministicConjuncts(Metadata metadata, Expression expression)
    {
        return filterConjuncts(metadata, expression, expression1 -> DeterminismEvaluator.isDeterministic(expression1, metadata));
    }

    public static Expression filterNonDeterministicConjuncts(Metadata metadata, Expression expression)
    {
        return filterConjuncts(metadata, expression, not(testExpression -> DeterminismEvaluator.isDeterministic(testExpression, metadata)));
    }

    public static Expression filterConjuncts(Metadata metadata, Expression expression, Predicate<Expression> predicate)
    {
        List<Expression> conjuncts = extractConjuncts(expression).stream()
                .filter(predicate)
                .collect(toList());

        return combineConjuncts(metadata, conjuncts);
    }

    public static boolean referencesAny(Expression expression, Collection<Symbol> variables)
    {
        Set<Symbol> references = SymbolsExtractor.extractUnique(expression);

        return variables.stream().anyMatch(references::contains);
    }

    public static Function<Expression, Expression> expressionOrNullSymbols(final Predicate<Symbol>... nullSymbolScopes)
    {
        return expression -> {
            ImmutableList.Builder<Expression> resultDisjunct = ImmutableList.builder();
            resultDisjunct.add(expression);

            for (Predicate<Symbol> nullSymbolScope : nullSymbolScopes) {
                List<Symbol> symbols = SymbolsExtractor.extractUnique(expression).stream()
                        .filter(nullSymbolScope)
                        .collect(toImmutableList());

                if (Iterables.isEmpty(symbols)) {
                    continue;
                }

                ImmutableList.Builder<Expression> nullConjuncts = ImmutableList.builder();
                for (Symbol symbol : symbols) {
                    nullConjuncts.add(new IsNullPredicate(symbol.toSymbolReference()));
                }

                resultDisjunct.add(and(nullConjuncts.build()));
            }

            return or(resultDisjunct.build());
        };
    }

    /**
     * Removes duplicate deterministic expressions. Preserves the relative order
     * of the expressions in the list.
     */
    private static List<Expression> removeDuplicates(Metadata metadata, List<Expression> expressions)
    {
        Set<Expression> seen = new HashSet<>();

        ImmutableList.Builder<Expression> result = ImmutableList.builder();
        for (Expression expression : expressions) {
            if (!DeterminismEvaluator.isDeterministic(expression, metadata)) {
                result.add(expression);
            }
            else if (!seen.contains(expression)) {
                result.add(expression);
                seen.add(expression);
            }
        }

        return result.build();
    }

    public static Expression normalize(Expression expression)
    {
        if (expression instanceof NotExpression) {
            NotExpression not = (NotExpression) expression;
            if (not.getValue() instanceof ComparisonExpression && ((ComparisonExpression) not.getValue()).getOperator() != IS_DISTINCT_FROM) {
                ComparisonExpression comparison = (ComparisonExpression) not.getValue();
                return new ComparisonExpression(comparison.getOperator().negate(), comparison.getLeft(), comparison.getRight());
            }
            if (not.getValue() instanceof NotExpression) {
                return normalize(((NotExpression) not.getValue()).getValue());
            }
        }
        return expression;
    }

    public static Expression rewriteIdentifiersToSymbolReferences(Expression expression)
    {
        return ExpressionTreeRewriter.rewriteWith(new ExpressionRewriter<Void>()
        {
            @Override
            public Expression rewriteIdentifier(Identifier node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
            {
                return new SymbolReference(node.getValue());
            }

            @Override
            public Expression rewriteLambdaExpression(LambdaExpression node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
            {
                return new LambdaExpression(node.getArguments(), treeRewriter.rewrite(node.getBody(), context));
            }
        }, expression);
    }
}
