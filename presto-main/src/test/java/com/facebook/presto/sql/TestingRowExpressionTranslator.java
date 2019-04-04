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
package com.facebook.presto.sql;

import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.analyzer.ExpressionAnalyzer;
import com.facebook.presto.sql.analyzer.Scope;
import com.facebook.presto.sql.planner.ExpressionInterpreter;
import com.facebook.presto.sql.planner.LiteralEncoder;
import com.facebook.presto.sql.planner.NoOpSymbolResolver;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.relational.SqlToRowExpressionTranslator;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NodeRef;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static java.util.Collections.emptyList;

class TestingRowExpressionTranslator
{
    private final Metadata metadata;
    private final LiteralEncoder literalEncoder;

    public TestingRowExpressionTranslator(Metadata metadata)
    {
        this.metadata = metadata;
        this.literalEncoder = new LiteralEncoder(metadata.getBlockEncodingSerde());
    }

    public TestingRowExpressionTranslator()
    {
        this(MetadataManager.createTestMetadataManager());
    }

    RowExpression translateAndOptimize(Expression expression)
    {
        return translateAndOptimize(expression, getExpressionTypes(expression, TypeProvider.empty()));
    }

    RowExpression translateAndOptimize(Expression expression, TypeProvider typeProvider)
    {
        return translateAndOptimize(expression, getExpressionTypes(expression, typeProvider));
    }

    RowExpression translate(Expression expression, TypeProvider typeProvider)
    {
        return SqlToRowExpressionTranslator.translate(
                expression,
                getExpressionTypes(expression, typeProvider),
                ImmutableMap.of(),
                metadata.getFunctionManager(),
                metadata.getTypeManager(),
                TEST_SESSION,
                false);
    }

    RowExpression translateAndOptimize(Expression expression, Map<NodeRef<Expression>, Type> types)
    {
        return SqlToRowExpressionTranslator.translate(expression, types, ImmutableMap.of(), metadata.getFunctionManager(), metadata.getTypeManager(), TEST_SESSION, true);
    }

    Expression simplifyExpression(Expression expression)
    {
        // Testing simplified expressions is important, since simplification may create CASTs or function calls that cannot be simplified by the ExpressionOptimizer

        Map<NodeRef<Expression>, Type> expressionTypes = getExpressionTypes(expression, TypeProvider.empty());
        ExpressionInterpreter interpreter = ExpressionInterpreter.expressionOptimizer(expression, metadata, TEST_SESSION, expressionTypes);
        Object value = interpreter.optimize(NoOpSymbolResolver.INSTANCE);
        return literalEncoder.toExpression(value, expressionTypes.get(NodeRef.of(expression)));
    }

    private Map<NodeRef<Expression>, Type> getExpressionTypes(Expression expression, TypeProvider typeProvider)
    {
        ExpressionAnalyzer expressionAnalyzer = ExpressionAnalyzer.createWithoutSubqueries(
                metadata.getFunctionManager(),
                metadata.getTypeManager(),
                TEST_SESSION,
                typeProvider,
                emptyList(),
                node -> new IllegalStateException("Unexpected node: %s" + node),
                WarningCollector.NOOP,
                false);
        expressionAnalyzer.analyze(expression, Scope.create());
        return expressionAnalyzer.getExpressionTypes();
    }
}
