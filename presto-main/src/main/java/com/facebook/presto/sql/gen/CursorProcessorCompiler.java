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
package com.facebook.presto.sql.gen;

import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.operator.DriverYieldSignal;
import com.facebook.presto.operator.project.CursorProcessorOutput;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.gen.LambdaBytecodeGenerator.CompiledLambda;
import com.facebook.presto.sql.relational.CallExpression;
import com.facebook.presto.sql.relational.ConstantExpression;
import com.facebook.presto.sql.relational.InputReferenceExpression;
import com.facebook.presto.sql.relational.LambdaDefinitionExpression;
import com.facebook.presto.sql.relational.RowExpression;
import com.facebook.presto.sql.relational.RowExpressionVisitor;
import com.facebook.presto.sql.relational.VariableReferenceExpression;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Primitives;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.ClassDefinition;
import io.airlift.bytecode.MethodDefinition;
import io.airlift.bytecode.Parameter;
import io.airlift.bytecode.Scope;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;
import io.airlift.bytecode.control.WhileLoop;
import io.airlift.bytecode.instruction.LabelNode;
import io.airlift.slice.Slice;

import java.util.List;
import java.util.Set;

import static com.facebook.presto.sql.gen.BytecodeUtils.generateWrite;
import static com.facebook.presto.sql.gen.LambdaAndTryExpressionExtractor.extractLambdaAndTryExpressions;
import static io.airlift.bytecode.Access.PUBLIC;
import static io.airlift.bytecode.Access.a;
import static io.airlift.bytecode.Parameter.arg;
import static io.airlift.bytecode.ParameterizedType.type;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantTrue;
import static io.airlift.bytecode.expression.BytecodeExpressions.newInstance;
import static io.airlift.bytecode.expression.BytecodeExpressions.or;
import static io.airlift.bytecode.instruction.JumpInstruction.jump;
import static java.lang.String.format;

public class CursorProcessorCompiler
        implements BodyCompiler
{
    private final Metadata metadata;

    public CursorProcessorCompiler(Metadata metadata)
    {
        this.metadata = metadata;
    }

    @Override
    public void generateMethods(ClassDefinition classDefinition, CallSiteBinder callSiteBinder, RowExpression filter, List<RowExpression> projections)
    {
        CachedInstanceBinder cachedInstanceBinder = new CachedInstanceBinder(classDefinition, callSiteBinder);

        generateProcessMethod(classDefinition, projections.size());

        PreGeneratedExpressions filterPreGeneratedExpressions = generateMethodsForLambdaAndTry(classDefinition, callSiteBinder, cachedInstanceBinder, filter, "filter");
        generateFilterMethod(classDefinition, callSiteBinder, cachedInstanceBinder, filterPreGeneratedExpressions, filter);

        for (int i = 0; i < projections.size(); i++) {
            String methodName = "project_" + i;
            PreGeneratedExpressions projectPreGeneratedExpressions = generateMethodsForLambdaAndTry(classDefinition, callSiteBinder, cachedInstanceBinder, projections.get(i), methodName);
            generateProjectMethod(classDefinition, callSiteBinder, cachedInstanceBinder, projectPreGeneratedExpressions, methodName, projections.get(i));
        }

        MethodDefinition constructorDefinition = classDefinition.declareConstructor(a(PUBLIC));
        BytecodeBlock constructorBody = constructorDefinition.getBody();
        Variable thisVariable = constructorDefinition.getThis();
        constructorBody.comment("super();")
                .append(thisVariable)
                .invokeConstructor(Object.class);

        cachedInstanceBinder.generateInitializations(thisVariable, constructorBody);
        constructorBody.ret();
    }

    private static void generateProcessMethod(ClassDefinition classDefinition, int projections)
    {
        Parameter session = arg("session", ConnectorSession.class);
        Parameter yieldSignal = arg("yieldSignal", DriverYieldSignal.class);
        Parameter cursor = arg("cursor", RecordCursor.class);
        Parameter pageBuilder = arg("pageBuilder", PageBuilder.class);
        MethodDefinition method = classDefinition.declareMethod(a(PUBLIC), "process", type(CursorProcessorOutput.class), session, yieldSignal, cursor, pageBuilder);

        Scope scope = method.getScope();
        Variable completedPositionsVariable = scope.declareVariable(int.class, "completedPositions");
        Variable finishedVariable = scope.declareVariable(boolean.class, "finished");

        method.getBody()
                .comment("int completedPositions = 0;")
                .putVariable(completedPositionsVariable, 0)
                .comment("boolean finished = false;")
                .putVariable(finishedVariable, false);

        // while loop loop body
        LabelNode done = new LabelNode("done");
        WhileLoop whileLoop = new WhileLoop()
                .condition(constantTrue())
                .body(new BytecodeBlock()
                        .comment("if (pageBuilder.isFull() || yieldSignal.isSet()) return new CursorProcessorOutput(completedPositions, false);")
                        .append(new IfStatement()
                                .condition(or(
                                        pageBuilder.invoke("isFull", boolean.class),
                                        yieldSignal.invoke("isSet", boolean.class)))
                                .ifTrue(jump(done)))
                        .comment("if (!cursor.advanceNextPosition()) return new CursorProcessorOutput(completedPositions, true);")
                        .append(new IfStatement()
                                .condition(cursor.invoke("advanceNextPosition", boolean.class))
                                .ifFalse(new BytecodeBlock()
                                        .putVariable(finishedVariable, true)
                                        .gotoLabel(done)))
                        .comment("do the projection")
                        .append(createProjectIfStatement(classDefinition, method, session, cursor, pageBuilder, projections))
                        .comment("completedPositions++;")
                        .incrementVariable(completedPositionsVariable, (byte) 1));

        method.getBody()
                .append(whileLoop)
                .visitLabel(done)
                .append(newInstance(CursorProcessorOutput.class, completedPositionsVariable, finishedVariable)
                        .ret());
    }

    private static IfStatement createProjectIfStatement(
            ClassDefinition classDefinition,
            MethodDefinition method,
            Parameter session,
            Parameter cursor,
            Parameter pageBuilder,
            int projections)
    {
        // if (filter(cursor))
        IfStatement ifStatement = new IfStatement();
        ifStatement.condition()
                .append(method.getThis())
                .getVariable(session)
                .getVariable(cursor)
                .invokeVirtual(classDefinition.getType(), "filter", type(boolean.class), type(ConnectorSession.class), type(RecordCursor.class));

        // pageBuilder.declarePosition();
        ifStatement.ifTrue()
                .getVariable(pageBuilder)
                .invokeVirtual(PageBuilder.class, "declarePosition", void.class);

        // this.project_43(session, cursor, pageBuilder.getBlockBuilder(42)));
        for (int projectionIndex = 0; projectionIndex < projections; projectionIndex++) {
            ifStatement.ifTrue()
                    .append(method.getThis())
                    .getVariable(session)
                    .getVariable(cursor);

            // pageBuilder.getBlockBuilder(0)
            ifStatement.ifTrue()
                    .getVariable(pageBuilder)
                    .push(projectionIndex)
                    .invokeVirtual(PageBuilder.class, "getBlockBuilder", BlockBuilder.class, int.class);

            // project(block..., blockBuilder)gen
            ifStatement.ifTrue()
                    .invokeVirtual(classDefinition.getType(),
                            "project_" + projectionIndex,
                            type(void.class),
                            type(ConnectorSession.class),
                            type(RecordCursor.class),
                            type(BlockBuilder.class));
        }
        return ifStatement;
    }

    private PreGeneratedExpressions generateMethodsForLambdaAndTry(
            ClassDefinition containerClassDefinition,
            CallSiteBinder callSiteBinder,
            CachedInstanceBinder cachedInstanceBinder,
            RowExpression projection,
            String methodPrefix)
    {
        Set<RowExpression> lambdaAndTryExpressions = ImmutableSet.copyOf(extractLambdaAndTryExpressions(projection));

        ImmutableMap.Builder<CallExpression, MethodDefinition> tryMethodMap = ImmutableMap.builder();
        ImmutableMap.Builder<LambdaDefinitionExpression, CompiledLambda> compiledLambdaMap = ImmutableMap.builder();

        int counter = 0;
        for (RowExpression expression : lambdaAndTryExpressions) {
            if (expression instanceof LambdaDefinitionExpression) {
                LambdaDefinitionExpression lambdaExpression = (LambdaDefinitionExpression) expression;
                String methodName = methodPrefix + "_lambda_" + counter;
                PreGeneratedExpressions preGeneratedExpressions = new PreGeneratedExpressions(tryMethodMap.build(), compiledLambdaMap.build());
                CompiledLambda compiledLambda = LambdaBytecodeGenerator.preGenerateLambdaExpression(
                        lambdaExpression,
                        methodName,
                        containerClassDefinition,
                        preGeneratedExpressions,
                        callSiteBinder,
                        cachedInstanceBinder,
                        metadata.getFunctionRegistry());
                compiledLambdaMap.put(lambdaExpression, compiledLambda);
            }
            else {
                throw new VerifyException(format("unexpected expression: %s", expression.toString()));
            }
            counter++;
        }

        return new PreGeneratedExpressions(tryMethodMap.build(), compiledLambdaMap.build());
    }

    private void generateFilterMethod(
            ClassDefinition classDefinition,
            CallSiteBinder callSiteBinder,
            CachedInstanceBinder cachedInstanceBinder,
            PreGeneratedExpressions preGeneratedExpressions,
            RowExpression filter)
    {
        Parameter session = arg("session", ConnectorSession.class);
        Parameter cursor = arg("cursor", RecordCursor.class);
        MethodDefinition method = classDefinition.declareMethod(a(PUBLIC), "filter", type(boolean.class), session, cursor);

        method.comment("Filter: %s", filter);

        Scope scope = method.getScope();
        Variable wasNullVariable = scope.declareVariable(type(boolean.class), "wasNull");

        RowExpressionCompiler compiler = new RowExpressionCompiler(
                callSiteBinder,
                cachedInstanceBinder,
                fieldReferenceCompiler(cursor),
                metadata.getFunctionRegistry(),
                preGeneratedExpressions);

        LabelNode end = new LabelNode("end");
        method.getBody()
                .comment("boolean wasNull = false;")
                .putVariable(wasNullVariable, false)
                .comment("evaluate filter: " + filter)
                .append(compiler.compile(filter, scope))
                .comment("if (wasNull) return false;")
                .getVariable(wasNullVariable)
                .ifFalseGoto(end)
                .pop(boolean.class)
                .push(false)
                .visitLabel(end)
                .retBoolean();
    }

    private void generateProjectMethod(
            ClassDefinition classDefinition,
            CallSiteBinder callSiteBinder,
            CachedInstanceBinder cachedInstanceBinder,
            PreGeneratedExpressions preGeneratedExpressions,
            String methodName,
            RowExpression projection)
    {
        Parameter session = arg("session", ConnectorSession.class);
        Parameter cursor = arg("cursor", RecordCursor.class);
        Parameter output = arg("output", BlockBuilder.class);
        MethodDefinition method = classDefinition.declareMethod(a(PUBLIC), methodName, type(void.class), session, cursor, output);

        method.comment("Projection: %s", projection.toString());

        Scope scope = method.getScope();
        Variable wasNullVariable = scope.declareVariable(type(boolean.class), "wasNull");

        RowExpressionCompiler compiler = new RowExpressionCompiler(
                callSiteBinder,
                cachedInstanceBinder,
                fieldReferenceCompiler(cursor),
                metadata.getFunctionRegistry(),
                preGeneratedExpressions);

        method.getBody()
                .comment("boolean wasNull = false;")
                .putVariable(wasNullVariable, false)
                .getVariable(output)
                .comment("evaluate projection: " + projection.toString())
                .append(compiler.compile(projection, scope))
                .append(generateWrite(callSiteBinder, scope, wasNullVariable, projection.getType()))
                .ret();
    }

    private static RowExpressionVisitor<BytecodeNode, Scope> fieldReferenceCompiler(Variable cursorVariable)
    {
        return new RowExpressionVisitor<BytecodeNode, Scope>()
        {
            @Override
            public BytecodeNode visitInputReference(InputReferenceExpression node, Scope scope)
            {
                int field = node.getField();
                Type type = node.getType();
                Variable wasNullVariable = scope.getVariable("wasNull");

                Class<?> javaType = type.getJavaType();
                if (!javaType.isPrimitive() && javaType != Slice.class) {
                    javaType = Object.class;
                }

                IfStatement ifStatement = new IfStatement();
                ifStatement.condition()
                        .setDescription(format("cursor.get%s(%d)", type, field))
                        .getVariable(cursorVariable)
                        .push(field)
                        .invokeInterface(RecordCursor.class, "isNull", boolean.class, int.class);

                ifStatement.ifTrue()
                        .putVariable(wasNullVariable, true)
                        .pushJavaDefault(javaType);

                ifStatement.ifFalse()
                        .getVariable(cursorVariable)
                        .push(field)
                        .invokeInterface(RecordCursor.class, "get" + Primitives.wrap(javaType).getSimpleName(), javaType, int.class);

                return ifStatement;
            }

            @Override
            public BytecodeNode visitCall(CallExpression call, Scope scope)
            {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public BytecodeNode visitConstant(ConstantExpression literal, Scope scope)
            {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public BytecodeNode visitLambda(LambdaDefinitionExpression lambda, Scope context)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public BytecodeNode visitVariableReference(VariableReferenceExpression reference, Scope context)
            {
                throw new UnsupportedOperationException();
            }
        };
    }
}
