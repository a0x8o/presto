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

import com.facebook.presto.Session;
import com.facebook.presto.client.FailureInfo;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.operator.scalar.ScalarFunctionImplementation;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.RowBlockBuilder;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.OperatorType;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.InputReferenceExpression;
import com.facebook.presto.spi.relation.LambdaDefinitionExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.RowExpressionVisitor;
import com.facebook.presto.spi.relation.SpecialFormExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.ArrayType;
import com.facebook.presto.spi.type.FunctionType;
import com.facebook.presto.spi.type.RowType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.sql.InterpretedFunctionInvoker;
import com.facebook.presto.sql.planner.Interpreters.LambdaSymbolResolver;
import com.facebook.presto.sql.relational.StandardFunctionResolution;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.util.Failures;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Primitives;
import io.airlift.joni.Regex;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.facebook.presto.metadata.CastType.CAST;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.AND;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.BIND;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.COALESCE;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.DEREFERENCE;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.IF;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.IN;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.IS_NULL;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.NULL_IF;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.OR;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.ROW_CONSTRUCTOR;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.SWITCH;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.WHEN;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.TypeUtils.writeNativeValue;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.spi.type.VarcharType.createVarcharType;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;
import static com.facebook.presto.sql.gen.VarArgsToMapAdapterGenerator.generateVarArgsToMapAdapter;
import static com.facebook.presto.sql.planner.Interpreters.interpretDereference;
import static com.facebook.presto.sql.planner.Interpreters.interpretLikePredicate;
import static com.facebook.presto.sql.planner.LiteralEncoder.isSupportedLiteralType;
import static com.facebook.presto.sql.planner.LiteralEncoder.toRowExpression;
import static com.facebook.presto.sql.planner.RowExpressionInterpreter.SpecialCallResult.changed;
import static com.facebook.presto.sql.planner.RowExpressionInterpreter.SpecialCallResult.notChanged;
import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.sql.tree.ArrayConstructor.ARRAY_CONSTRUCTOR;
import static com.facebook.presto.type.JsonType.JSON;
import static com.facebook.presto.type.LikeFunctions.isLikePattern;
import static com.facebook.presto.type.LikeFunctions.unescapeLiteralLikePattern;
import static com.facebook.presto.type.UnknownType.UNKNOWN;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.slice.Slices.utf8Slice;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class RowExpressionInterpreter
{
    private final RowExpression expression;
    private final Metadata metadata;
    private final Session session;
    private final boolean optimize;
    private final InterpretedFunctionInvoker functionInvoker;
    private final com.facebook.presto.sql.relational.DeterminismEvaluator determinismEvaluator;
    private final StandardFunctionResolution resolution;

    private final Visitor visitor;

    public static Object evaluateConstantRowExpression(RowExpression expression, Metadata metadata, Session session)
    {
        // evaluate the expression
        Object result = new RowExpressionInterpreter(expression, metadata, session, false).evaluate();
        verify(!(result instanceof RowExpression), "RowExpression interpreter returned an unresolved expression");
        return result;
    }

    public static RowExpressionInterpreter rowExpressionInterpreter(RowExpression expression, Metadata metadata, Session session)
    {
        return new RowExpressionInterpreter(expression, metadata, session, false);
    }

    public RowExpressionInterpreter(RowExpression expression, Metadata metadata, Session session, boolean optimize)
    {
        this.expression = requireNonNull(expression, "expression is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.session = requireNonNull(session, "session is null");
        this.optimize = optimize;
        this.functionInvoker = new InterpretedFunctionInvoker(metadata.getFunctionManager());
        this.determinismEvaluator = new com.facebook.presto.sql.relational.DeterminismEvaluator(metadata.getFunctionManager());
        this.resolution = new StandardFunctionResolution(metadata.getFunctionManager());

        this.visitor = new Visitor();
    }

    public Type getType()
    {
        return expression.getType();
    }

    public Object evaluate()
    {
        checkState(!optimize, "evaluate() not allowed for optimizer");
        return expression.accept(visitor, null);
    }

    @VisibleForTesting
    public Object optimize(SymbolResolver inputs)
    {
        checkState(optimize, "evaluate(SymbolResolver) not allowed for interpreter");
        return expression.accept(visitor, inputs);
    }

    private class Visitor
            implements RowExpressionVisitor<Object, Object>
    {
        @Override
        public Object visitInputReference(InputReferenceExpression node, Object context)
        {
            return node;
        }

        @Override
        public Object visitConstant(ConstantExpression node, Object context)
        {
            return node.getValue();
        }

        @Override
        public Object visitVariableReference(VariableReferenceExpression node, Object context)
        {
            if (context instanceof SymbolResolver) {
                return ((SymbolResolver) context).getValue(new Symbol(node.getName()));
            }
            return node;
        }

        @Override
        public Object visitCall(CallExpression node, Object context)
        {
            List<Type> argumentTypes = new ArrayList<>();
            List<Object> argumentValues = new ArrayList<>();
            for (RowExpression expression : node.getArguments()) {
                Object value = expression.accept(this, context);
                argumentValues.add(value);
                argumentTypes.add(expression.getType());
            }

            FunctionHandle functionHandle = node.getFunctionHandle();

            // Special casing for large constant array construction
            if (functionHandle.getSignature().getName().toUpperCase().equals(ARRAY_CONSTRUCTOR)) {
                SpecialCallResult result = tryHandleArrayConstructor(node, argumentValues);
                if (result.isChanged()) {
                    return result.getValue();
                }
            }

            // Special casing for cast
            if (resolution.isCastFunction(functionHandle)) {
                SpecialCallResult result = tryHandleCast(node, argumentValues);
                if (result.isChanged()) {
                    return result.getValue();
                }
            }

            // Special casing for like
            if (resolution.isLikeFunction(functionHandle)) {
                SpecialCallResult result = tryHandleLike(node, argumentValues, argumentTypes, context);
                if (result.isChanged()) {
                    return result.getValue();
                }
            }

            ScalarFunctionImplementation function = metadata.getFunctionManager().getScalarFunctionImplementation(functionHandle);
            for (int i = 0; i < argumentValues.size(); i++) {
                Object value = argumentValues.get(i);
                if (value == null && function.getArgumentProperty(i).getNullConvention() == RETURN_NULL_ON_NULL) {
                    return null;
                }
            }

            // do not optimize non-deterministic functions
            if (optimize && (!function.isDeterministic() || hasUnresolvedValue(argumentValues) || functionHandle.getSignature().getName().equals("fail"))) {
                return call(functionHandle, node.getType(), toRowExpressions(argumentValues, argumentTypes));
            }
            return functionInvoker.invoke(functionHandle, session.toConnectorSession(), argumentValues);
        }

        @Override
        public Object visitLambda(LambdaDefinitionExpression node, Object context)
        {
            if (optimize) {
                // TODO: enable optimization related to lambda expression
                // A mechanism to convert function type back into lambda expression need to exist to enable optimization
                return node;
            }

            RowExpression body = node.getBody();
            FunctionType functionType = (FunctionType) node.getType();
            checkArgument(node.getArguments().size() == functionType.getArgumentTypes().size());

            return generateVarArgsToMapAdapter(
                    Primitives.wrap(functionType.getReturnType().getJavaType()),
                    functionType.getArgumentTypes().stream()
                            .map(Type::getJavaType)
                            .map(Primitives::wrap)
                            .collect(toImmutableList()),
                    node.getArguments(),
                    map -> body.accept(this, new LambdaSymbolResolver(map)));
        }

        @Override
        public Object visitSpecialForm(SpecialFormExpression node, Object context)
        {
            switch (node.getForm()) {
                case IF: {
                    checkArgument(node.getArguments().size() == 3);
                    Object condition = processWithExceptionHandling(node.getArguments().get(0), context);
                    Object trueValue = processWithExceptionHandling(node.getArguments().get(1), context);
                    Object falseValue = processWithExceptionHandling(node.getArguments().get(2), context);

                    if (condition instanceof RowExpression) {
                        return new SpecialFormExpression(
                                IF,
                                node.getType(),
                                toRowExpression(condition, node.getArguments().get(0).getType()),
                                toRowExpression(trueValue, node.getArguments().get(1).getType()),
                                toRowExpression(falseValue, node.getArguments().get(2).getType()));
                    }
                    else if (Boolean.TRUE.equals(condition)) {
                        return trueValue;
                    }
                    return falseValue;
                }
                case NULL_IF: {
                    checkArgument(node.getArguments().size() == 2);
                    Object left = processWithExceptionHandling(node.getArguments().get(0), context);
                    if (left == null) {
                        return null;
                    }

                    Object right = processWithExceptionHandling(node.getArguments().get(1), context);
                    if (right == null) {
                        return left;
                    }

                    if (hasUnresolvedValue(left, right)) {
                        return new SpecialFormExpression(
                                NULL_IF,
                                node.getType(),
                                toRowExpression(left, node.getArguments().get(0).getType()),
                                toRowExpression(right, node.getArguments().get(1).getType()));
                    }

                    Type leftType = node.getArguments().get(0).getType();
                    Type rightType = node.getArguments().get(1).getType();
                    Type commonType = metadata.getTypeManager().getCommonSuperType(leftType, rightType).get();
                    FunctionHandle firstCast = metadata.getFunctionManager().lookupCast(CAST, leftType.getTypeSignature(), commonType.getTypeSignature());
                    FunctionHandle secondCast = metadata.getFunctionManager().lookupCast(CAST, rightType.getTypeSignature(), commonType.getTypeSignature());

                    // cast(first as <common type>) == cast(second as <common type>)
                    boolean equal = Boolean.TRUE.equals(invokeOperator(
                            OperatorType.EQUAL,
                            ImmutableList.of(commonType, commonType),
                            ImmutableList.of(
                                    functionInvoker.invoke(firstCast, session.toConnectorSession(), left),
                                    functionInvoker.invoke(secondCast, session.toConnectorSession(), right))));

                    if (equal) {
                        return null;
                    }
                    return left;
                }
                case IS_NULL: {
                    checkArgument(node.getArguments().size() == 1);
                    Object value = processWithExceptionHandling(node.getArguments().get(0), context);
                    if (value instanceof RowExpression) {
                        return new SpecialFormExpression(
                                IS_NULL,
                                node.getType(),
                                toRowExpression(value, node.getArguments().get(0).getType()));
                    }
                    return value == null;
                }
                case AND: {
                    Object left = node.getArguments().get(0).accept(this, context);
                    Object right;

                    if (Boolean.FALSE.equals(left)) {
                        return false;
                    }

                    right = node.getArguments().get(1).accept(this, context);

                    if (Boolean.TRUE.equals(right)) {
                        return left;
                    }

                    if (Boolean.FALSE.equals(right) || Boolean.TRUE.equals(left)) {
                        return right;
                    }

                    if (left == null && right == null) {
                        return null;
                    }
                    return new SpecialFormExpression(
                            AND,
                            node.getType(),
                            toRowExpressions(
                                    asList(left, right),
                                    ImmutableList.of(node.getArguments().get(0).getType(), node.getArguments().get(1).getType())));
                }
                case OR: {
                    Object left = node.getArguments().get(0).accept(this, context);
                    Object right;

                    if (Boolean.TRUE.equals(left)) {
                        return true;
                    }

                    right = node.getArguments().get(1).accept(this, context);

                    if (Boolean.FALSE.equals(right)) {
                        return left;
                    }

                    if (Boolean.TRUE.equals(right) || Boolean.FALSE.equals(left)) {
                        return right;
                    }

                    if (left == null && right == null) {
                        return null;
                    }
                    return new SpecialFormExpression(
                            OR,
                            node.getType(),
                            toRowExpressions(
                                    asList(left, right),
                                    ImmutableList.of(node.getArguments().get(0).getType(), node.getArguments().get(1).getType())));
                }
                case ROW_CONSTRUCTOR: {
                    RowType rowType = (RowType) node.getType();
                    List<Type> parameterTypes = rowType.getTypeParameters();
                    List<RowExpression> arguments = node.getArguments();

                    int cardinality = arguments.size();
                    List<Object> values = new ArrayList<>(cardinality);
                    arguments.forEach(argument -> values.add(argument.accept(this, context)));
                    if (hasUnresolvedValue(values)) {
                        return new SpecialFormExpression(ROW_CONSTRUCTOR, node.getType(), toRowExpressions(values, parameterTypes));
                    }
                    else {
                        BlockBuilder blockBuilder = new RowBlockBuilder(parameterTypes, null, 1);
                        BlockBuilder singleRowBlockWriter = blockBuilder.beginBlockEntry();
                        for (int i = 0; i < cardinality; ++i) {
                            writeNativeValue(parameterTypes.get(i), singleRowBlockWriter, values.get(i));
                        }
                        blockBuilder.closeEntry();
                        return rowType.getObject(blockBuilder, 0);
                    }
                }
                case COALESCE: {
                    Type type = node.getType();
                    List<Object> values = node.getArguments().stream()
                            .map(value -> processWithExceptionHandling(value, context))
                            .filter(Objects::nonNull)
                            .flatMap(expression -> {
                                if (expression instanceof SpecialFormExpression && ((SpecialFormExpression) expression).getForm() == COALESCE) {
                                    return ((SpecialFormExpression) expression).getArguments().stream();
                                }
                                return Stream.of(expression);
                            })
                            .collect(toList());

                    if ((!values.isEmpty() && !(values.get(0) instanceof RowExpression)) || values.size() == 1) {
                        return values.get(0);
                    }
                    ImmutableList.Builder<RowExpression> operandsBuilder = ImmutableList.builder();
                    Set<RowExpression> visitedExpression = new HashSet<>();
                    for (Object value : values) {
                        RowExpression expression = toRowExpression(value, type);
                        if (!determinismEvaluator.isDeterministic(expression) || visitedExpression.add(expression)) {
                            operandsBuilder.add(expression);
                        }
                        if (expression instanceof ConstantExpression && !(((ConstantExpression) expression).getValue() == null)) {
                            break;
                        }
                    }
                    List<RowExpression> expressions = operandsBuilder.build();

                    if (expressions.isEmpty()) {
                        return null;
                    }

                    if (expressions.size() == 1) {
                        return getOnlyElement(expressions);
                    }
                    return new SpecialFormExpression(COALESCE, node.getType(), expressions);
                }
                case IN: {
                    checkArgument(node.getArguments().size() >= 2, "values must not be empty");

                    // use toList to handle null values
                    List<RowExpression> valueExpressions = node.getArguments().subList(1, node.getArguments().size());
                    List<Object> values = valueExpressions.stream().map(value -> value.accept(this, context)).collect(toList());
                    List<Type> valuesTypes = valueExpressions.stream().map(RowExpression::getType).collect(toImmutableList());
                    Object target = node.getArguments().get(0).accept(this, context);
                    Type targetType = node.getArguments().get(0).getType();

                    if (target == null) {
                        return null;
                    }

                    boolean hasUnresolvedValue = false;
                    if (target instanceof RowExpression) {
                        hasUnresolvedValue = true;
                    }

                    boolean hasNullValue = false;
                    boolean found = false;
                    List<Object> unresolvedValues = new ArrayList<>(values.size());
                    List<Type> unresolvedValueTypes = new ArrayList<>(values.size());
                    for (int i = 0; i < values.size(); i++) {
                        Object value = values.get(i);
                        Type valueType = valuesTypes.get(i);
                        if (value instanceof RowExpression || target instanceof RowExpression) {
                            hasUnresolvedValue = true;
                            unresolvedValues.add(value);
                            unresolvedValueTypes.add(valueType);
                            continue;
                        }

                        if (value == null) {
                            hasNullValue = true;
                        }
                        else {
                            Boolean result = (Boolean) invokeOperator(OperatorType.EQUAL, ImmutableList.of(targetType, valueType), ImmutableList.of(target, value));
                            if (result == null) {
                                hasNullValue = true;
                            }
                            else if (!found && result) {
                                // in does not short-circuit so we must evaluate all value in the list
                                found = true;
                            }
                        }
                    }
                    if (found) {
                        return true;
                    }

                    if (hasUnresolvedValue) {
                        List<RowExpression> expressionValues = toRowExpressions(unresolvedValues, unresolvedValueTypes);
                        List<RowExpression> simplifiedExpressionValues = Stream.concat(
                                Stream.concat(
                                        Stream.of(toRowExpression(target, targetType)),
                                        expressionValues.stream().filter(determinismEvaluator::isDeterministic).distinct()),
                                expressionValues.stream().filter((expression -> !determinismEvaluator.isDeterministic(expression))))
                                .collect(toImmutableList());
                        return new SpecialFormExpression(IN, node.getType(), simplifiedExpressionValues);
                    }
                    if (hasNullValue) {
                        return null;
                    }
                    return false;
                }
                case DEREFERENCE: {
                    checkArgument(node.getArguments().size() == 2);

                    Object base = node.getArguments().get(0).accept(this, context);
                    int index = ((Number) node.getArguments().get(1).accept(this, context)).intValue();

                    // if the base part is evaluated to be null, the dereference expression should also be null
                    if (base == null) {
                        return null;
                    }

                    if (hasUnresolvedValue(base)) {
                        return new SpecialFormExpression(
                                DEREFERENCE,
                                node.getType(),
                                toRowExpression(base, node.getArguments().get(0).getType()),
                                toRowExpression(index, node.getArguments().get(1).getType()));
                    }
                    return interpretDereference(base, node.getType(), index);
                }
                case BIND: {
                    List<Object> values = node.getArguments()
                            .stream()
                            .map(value -> value.accept(this, context))
                            .collect(toImmutableList());
                    if (hasUnresolvedValue(values)) {
                        return new SpecialFormExpression(
                                BIND,
                                node.getType(),
                                toRowExpressions(values, node.getArguments().stream().map(RowExpression::getType).collect(toImmutableList())));
                    }
                    return insertArguments((MethodHandle) values.get(values.size() - 1), 0, values.subList(0, values.size() - 1).toArray());
                }
                case SWITCH: {
                    Object value = processWithExceptionHandling(node.getArguments().get(0), context);

                    List<RowExpression> whenClauses;
                    Object elseValue;
                    RowExpression last = node.getArguments().get(node.getArguments().size() - 1);
                    if (last instanceof SpecialFormExpression && ((SpecialFormExpression) last).getForm().equals(WHEN)) {
                        whenClauses = node.getArguments().subList(1, node.getArguments().size());
                        elseValue = null;
                    }
                    else {
                        whenClauses = node.getArguments().subList(1, node.getArguments().size() - 1);
                        elseValue = processWithExceptionHandling(last, context);
                    }

                    if (value == null) {
                        return elseValue;
                    }

                    List<RowExpression> simplifiedWhenClauses = new ArrayList<>();
                    for (RowExpression whenClause : whenClauses) {
                        checkArgument(whenClause instanceof SpecialFormExpression && ((SpecialFormExpression) whenClause).getForm().equals(WHEN));

                        RowExpression operand = ((SpecialFormExpression) whenClause).getArguments().get(0);
                        RowExpression result = ((SpecialFormExpression) whenClause).getArguments().get(1);

                        Object operandValue = processWithExceptionHandling(operand, context);
                        Object resultValue = processWithExceptionHandling(result, context);

                        // call equals(value, operand)
                        if (operandValue instanceof RowExpression || value instanceof RowExpression) {
                            // cannot fully evaluate, add updated whenClause
                            simplifiedWhenClauses.add(new SpecialFormExpression(WHEN, whenClause.getType(), toRowExpression(operandValue, operand.getType()), toRowExpression(resultValue, result.getType())));
                        }
                        else if (operandValue != null) {
                            Boolean isEqual = (Boolean) invokeOperator(
                                    OperatorType.EQUAL,
                                    ImmutableList.of(node.getArguments().get(0).getType(), operand.getType()),
                                    ImmutableList.of(value, operandValue));
                            if (isEqual != null && isEqual) {
                                // condition is true, use this as elseValue
                                elseValue = resultValue;
                                break;
                            }
                        }
                    }

                    if (simplifiedWhenClauses.isEmpty()) {
                        return elseValue;
                    }

                    ImmutableList.Builder<RowExpression> argumentsBuilder = ImmutableList.builder();
                    argumentsBuilder.add(toRowExpression(value, node.getArguments().get(0).getType()))
                            .addAll(simplifiedWhenClauses)
                            .add(toRowExpression(elseValue, node.getArguments().get(node.getArguments().size() - 1).getType()));
                    return new SpecialFormExpression(SWITCH, node.getType(), argumentsBuilder.build());
                }
                default:
                    throw new IllegalStateException("Can not compile special form: " + node.getForm());
            }
        }

        private Object processWithExceptionHandling(RowExpression expression, Object context)
        {
            if (expression == null) {
                return null;
            }
            try {
                return expression.accept(this, context);
            }
            catch (RuntimeException e) {
                // HACK
                // Certain operations like 0 / 0 or likeExpression may throw exceptions.
                // Wrap them in a call that will throw the exception if the expression is actually executed
                return createFailureFunction(e, expression.getType());
            }
        }

        private RowExpression createFailureFunction(RuntimeException exception, Type type)
        {
            requireNonNull(exception, "Exception is null");

            String failureInfo = JsonCodec.jsonCodec(FailureInfo.class).toJson(Failures.toFailure(exception).toFailureInfo());
            FunctionHandle jsonParse = metadata.getFunctionManager().resolveFunction(session, QualifiedName.of("json_parse"), fromTypes(VARCHAR));
            Object json = functionInvoker.invoke(jsonParse, session.toConnectorSession(), utf8Slice(failureInfo));

            FunctionHandle failureFunction = metadata.getFunctionManager().resolveFunction(session, QualifiedName.of("fail"), fromTypes(JSON));
            FunctionHandle cast = metadata.getFunctionManager().lookupCast(CAST, UNKNOWN.getTypeSignature(), type.getTypeSignature());

            return call(cast, type, call(failureFunction, UNKNOWN, toRowExpression(json, JSON)));
        }

        private boolean hasUnresolvedValue(Object... values)
        {
            return hasUnresolvedValue(ImmutableList.copyOf(values));
        }

        private boolean hasUnresolvedValue(List<Object> values)
        {
            return values.stream().anyMatch(instanceOf(RowExpression.class)::apply);
        }

        private Object invokeOperator(OperatorType operatorType, List<? extends Type> argumentTypes, List<Object> argumentValues)
        {
            FunctionHandle operatorHandle = metadata.getFunctionManager().resolveOperator(operatorType, fromTypes(argumentTypes));
            return functionInvoker.invoke(operatorHandle, session.toConnectorSession(), argumentValues);
        }

        private List<RowExpression> toRowExpressions(List<Object> values, List<Type> types)
        {
            checkArgument(values != null, "value is null");
            checkArgument(types != null, "value is null");
            checkArgument(values.size() == types.size());
            ImmutableList.Builder<RowExpression> rowExpressions = ImmutableList.builder();
            for (int i = 0; i < values.size(); i++) {
                rowExpressions.add(toRowExpression(values.get(i), types.get(i)));
            }
            return rowExpressions.build();
        }

        private SpecialCallResult tryHandleArrayConstructor(CallExpression callExpression, List<Object> argumentValues)
        {
            checkArgument(callExpression.getFunctionHandle().getSignature().getName().toUpperCase().equals(ARRAY_CONSTRUCTOR));
            boolean allConstants = true;
            for (Object values : argumentValues) {
                if (values instanceof RowExpression) {
                    allConstants = false;
                    break;
                }
            }
            if (allConstants) {
                Type elementType = ((ArrayType) callExpression.getType()).getElementType();
                BlockBuilder arrayBlockBuilder = elementType.createBlockBuilder(null, argumentValues.size());
                for (Object value : argumentValues) {
                    writeNativeValue(elementType, arrayBlockBuilder, value);
                }
                return changed(arrayBlockBuilder.build());
            }
            return notChanged();
        }

        private SpecialCallResult tryHandleCast(CallExpression callExpression, List<Object> argumentValues)
        {
            checkArgument(resolution.isCastFunction(callExpression.getFunctionHandle()));
            checkArgument(callExpression.getArguments().size() == 1);
            Type sourceType = callExpression.getArguments().get(0).getType();
            Type targetType = callExpression.getType();

            Object value = argumentValues.get(0);

            if (value == null) {
                return changed(null);
            }

            if (value instanceof RowExpression) {
                if (targetType.equals(sourceType)) {
                    return changed(value);
                }
                return changed(call(callExpression.getFunctionHandle(), callExpression.getType(), toRowExpression(value, sourceType)));
            }

            // TODO: still there is limitation for RowExpression. Example types could be Regex
            if (optimize && !isSupportedLiteralType(targetType)) {
                return changed(call(callExpression.getFunctionHandle(), callExpression.getType(), toRowExpression(value, sourceType)));
            }
            return notChanged();
        }

        private SpecialCallResult tryHandleLike(CallExpression callExpression, List<Object> argumentValues, List<Type> argumentTypes, Object context)
        {
            StandardFunctionResolution resolution = new StandardFunctionResolution(metadata.getFunctionManager());
            checkArgument(resolution.isLikeFunction(callExpression.getFunctionHandle()));
            checkArgument(callExpression.getArguments().size() == 2);
            RowExpression likePatternExpression = callExpression.getArguments().get(1);
            checkArgument(
                    (likePatternExpression instanceof CallExpression &&
                            (((CallExpression) likePatternExpression).getFunctionHandle().equals(resolution.likePatternFunction()) ||
                            (resolution.isCastFunction(((CallExpression) likePatternExpression).getFunctionHandle())))),
                    "expect a like_pattern function or a cast function");
            Object value = argumentValues.get(0);
            Object possibleCompiledPattern = argumentValues.get(1);

            if (value == null) {
                return changed(null);
            }

            CallExpression likePatternCall = (CallExpression) likePatternExpression;

            Object nonCompiledPattern = likePatternCall.getArguments().get(0).accept(this, context);
            if (nonCompiledPattern == null) {
                return changed(null);
            }

            boolean hasEscape = false;  // We cannot use Optional given escape could exist and its value is null
            Object escape = null;
            if (likePatternCall.getArguments().size() == 2) {
                hasEscape = true;
                escape = likePatternCall.getArguments().get(1).accept(this, context);
            }

            if (hasEscape && escape == null) {
                return changed(null);
            }

            if (!hasUnresolvedValue(value) && !hasUnresolvedValue(nonCompiledPattern) && (!hasEscape || !hasUnresolvedValue(escape))) {
                // fast path when we know the pattern and escape are constants
                if (possibleCompiledPattern instanceof Regex) {
                    return changed(interpretLikePredicate(argumentTypes.get(0), (Slice) value, (Regex) possibleCompiledPattern));
                }
                if (possibleCompiledPattern == null) {
                    return changed(null);
                }
                checkState((resolution.isCastFunction(((CallExpression) possibleCompiledPattern).getFunctionHandle())));
                possibleCompiledPattern = functionInvoker.invoke(((CallExpression) possibleCompiledPattern).getFunctionHandle(), session.toConnectorSession(), nonCompiledPattern);
                return changed(interpretLikePredicate(argumentTypes.get(0), (Slice) value, (Regex) possibleCompiledPattern));
            }

            // if pattern is a constant without % or _ replace with a comparison
            if (nonCompiledPattern instanceof Slice && (escape == null || escape instanceof Slice) && !isLikePattern((Slice) nonCompiledPattern, (Slice) escape)) {
                Slice unescapedPattern = unescapeLiteralLikePattern((Slice) nonCompiledPattern, (Slice) escape);
                Type valueType = argumentTypes.get(0);
                Type patternType = createVarcharType(unescapedPattern.length());
                TypeManager typeManager = metadata.getTypeManager();
                Optional<Type> commonSuperType = typeManager.getCommonSuperType(valueType, patternType);
                checkArgument(commonSuperType.isPresent(), "Missing super type when optimizing %s", callExpression);
                RowExpression valueExpression = toRowExpression(value, valueType);
                RowExpression patternExpression = toRowExpression(unescapedPattern, patternType);
                Type superType = commonSuperType.get();
                if (!valueType.equals(superType)) {
                    FunctionHandle cast = metadata.getFunctionManager().lookupCast(CAST, valueType.getTypeSignature(), superType.getTypeSignature());
                    valueExpression = call(cast, superType, valueExpression);
                }
                if (!patternType.equals(superType)) {
                    FunctionHandle cast = metadata.getFunctionManager().lookupCast(CAST, patternType.getTypeSignature(), superType.getTypeSignature());
                    patternExpression = call(cast, superType, patternExpression);
                }
                FunctionHandle equal = metadata.getFunctionManager().resolveOperator(OperatorType.EQUAL, fromTypes(superType, superType));
                return changed(call(equal, BOOLEAN, valueExpression, patternExpression).accept(this, context));
            }
            return notChanged();
        }
    }

    static final class SpecialCallResult
    {
        private final Object value;
        private final boolean changed;

        private SpecialCallResult(Object value, boolean changed)
        {
            this.value = value;
            this.changed = changed;
        }

        public static SpecialCallResult notChanged()
        {
            return new SpecialCallResult(null, false);
        }

        public static SpecialCallResult changed(Object value)
        {
            return new SpecialCallResult(value, true);
        }

        public Object getValue()
        {
            return value;
        }

        public boolean isChanged()
        {
            return changed;
        }
    }
}
