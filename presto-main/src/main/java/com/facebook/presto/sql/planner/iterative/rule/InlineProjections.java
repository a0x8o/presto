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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.matching.Capture;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolsExtractor;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.Assignments.Builder;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.relational.OriginalExpressionUtils;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Literal;
import com.facebook.presto.sql.tree.TryExpression;
import com.facebook.presto.sql.util.AstUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.facebook.presto.matching.Capture.newCapture;
import static com.facebook.presto.sql.planner.ExpressionSymbolInliner.inlineSymbols;
import static com.facebook.presto.sql.planner.plan.AssignmentUtils.identityAsSymbolReference;
import static com.facebook.presto.sql.planner.plan.AssignmentUtils.isIdentity;
import static com.facebook.presto.sql.planner.plan.Patterns.project;
import static com.facebook.presto.sql.planner.plan.Patterns.source;
import static com.facebook.presto.sql.relational.Expressions.variable;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.castToExpression;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.castToRowExpression;
import static java.util.stream.Collectors.toSet;

/**
 * Inlines expressions from a child project node into a parent project node
 * as long as they are simple constants, or they are referenced only once (to
 * avoid introducing duplicate computation) and the references don't appear
 * within a TRY block (to avoid changing semantics).
 */
public class InlineProjections
        implements Rule<ProjectNode>
{
    private static final Capture<ProjectNode> CHILD = newCapture();

    private static final Pattern<ProjectNode> PATTERN = project()
            .with(source().matching(project().capturedAs(CHILD)));

    @Override
    public Pattern<ProjectNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(ProjectNode parent, Captures captures, Context context)
    {
        ProjectNode child = captures.get(CHILD);

        Sets.SetView<VariableReferenceExpression> targets = extractInliningTargets(parent, child, context);
        if (targets.isEmpty()) {
            return Result.empty();
        }

        // inline the expressions
        Assignments assignments = child.getAssignments().filter(targets::contains);
        Map<VariableReferenceExpression, RowExpression> parentAssignments = parent.getAssignments()
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> castToRowExpression(inlineReferences(castToExpression(entry.getValue()), assignments))));

        // Synthesize identity assignments for the inputs of expressions that were inlined
        // to place in the child projection.
        // If all assignments end up becoming identity assignments, they'll get pruned by
        // other rules
        Set<VariableReferenceExpression> inputs = child.getAssignments()
                .entrySet().stream()
                .filter(entry -> targets.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(OriginalExpressionUtils::castToExpression)
                .flatMap(entry -> SymbolsExtractor.extractAllVariable(entry, context.getSymbolAllocator().getTypes()).stream())
                .collect(toSet());

        Builder childAssignments = Assignments.builder();
        for (Map.Entry<VariableReferenceExpression, RowExpression> assignment : child.getAssignments().entrySet()) {
            if (!targets.contains(assignment.getKey())) {
                childAssignments.put(assignment);
            }
        }
        for (VariableReferenceExpression input : inputs) {
            childAssignments.put(identityAsSymbolReference(input));
        }

        return Result.ofPlanNode(
                new ProjectNode(
                        parent.getId(),
                        new ProjectNode(
                                child.getId(),
                                child.getSource(),
                                childAssignments.build()),
                        Assignments.copyOf(parentAssignments)));
    }

    private Expression inlineReferences(Expression expression, Assignments assignments)
    {
        Function<Symbol, Expression> mapping = symbol -> {
            if (assignments.get(symbol) == null) {
                return symbol.toSymbolReference();
            }
            return castToExpression(assignments.get(symbol));
        };

        return inlineSymbols(mapping, expression);
    }

    private Sets.SetView<VariableReferenceExpression> extractInliningTargets(ProjectNode parent, ProjectNode child, Context context)
    {
        // candidates for inlining are
        //   1. references to simple constants
        //   2. references to complex expressions that
        //      a. are not inputs to try() expressions
        //      b. appear only once across all expressions
        //      c. are not identity projections
        // which come from the child, as opposed to an enclosing scope.

        Set<VariableReferenceExpression> childOutputSet = ImmutableSet.copyOf(child.getOutputVariables());

        Map<VariableReferenceExpression, Long> dependencies = parent.getAssignments()
                .getExpressions()
                .stream()
                .map(OriginalExpressionUtils::castToExpression)
                .flatMap(expression -> SymbolsExtractor.extractAll(expression).stream())
                .map(symbol -> variable(symbol.getName(), context.getSymbolAllocator().getTypes().get(symbol)))
                .filter(childOutputSet::contains)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        // find references to simple constants
        Set<VariableReferenceExpression> constants = dependencies.keySet().stream()
                .filter(input -> castToExpression(child.getAssignments().get(input)) instanceof Literal)
                .collect(toSet());

        // exclude any complex inputs to TRY expressions. Inlining them would potentially
        // change the semantics of those expressions
        Set<VariableReferenceExpression> tryArguments = parent.getAssignments()
                .getExpressions().stream()
                .flatMap(expression -> extractTryArguments(castToExpression(expression), context.getSymbolAllocator().getTypes()).stream())
                .collect(toSet());

        Set<VariableReferenceExpression> singletons = dependencies.entrySet().stream()
                .filter(entry -> entry.getValue() == 1) // reference appears just once across all expressions in parent project node
                .filter(entry -> !tryArguments.contains(entry.getKey())) // they are not inputs to TRY. Otherwise, inlining might change semantics
                .filter(entry -> !isIdentity(child.getAssignments(), entry.getKey())) // skip identities, otherwise, this rule will keep firing forever
                .map(Map.Entry::getKey)
                .collect(toSet());

        return Sets.union(singletons, constants);
    }

    private Set<VariableReferenceExpression> extractTryArguments(Expression expression, TypeProvider types)
    {
        return AstUtils.preOrder(expression)
                .filter(TryExpression.class::isInstance)
                .map(TryExpression.class::cast)
                .flatMap(tryExpression -> SymbolsExtractor.extractAllVariable(tryExpression, types).stream())
                .collect(toSet());
    }
}
