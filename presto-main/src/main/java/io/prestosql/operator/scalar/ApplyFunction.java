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
package io.prestosql.operator.scalar;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Primitives;
import io.prestosql.annotation.UsedByGeneratedCode;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionArgumentDefinition;
import io.prestosql.metadata.FunctionMetadata;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.sql.gen.lambda.UnaryFunctionInterface;

import java.lang.invoke.MethodHandle;

import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.metadata.Signature.typeVariable;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.functionTypeArgumentProperty;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.USE_BOXED_TYPE;
import static io.prestosql.spi.type.TypeSignature.functionType;
import static io.prestosql.util.Reflection.methodHandle;

/**
 * This scalar function exists primarily to test lambda expression support.
 */
public final class ApplyFunction
        extends SqlScalarFunction
{
    public static final ApplyFunction APPLY_FUNCTION = new ApplyFunction();

    private static final MethodHandle METHOD_HANDLE = methodHandle(ApplyFunction.class, "apply", Object.class, UnaryFunctionInterface.class);

    private ApplyFunction()
    {
        super(new FunctionMetadata(
                new Signature(
                        "apply",
                        ImmutableList.of(typeVariable("T"), typeVariable("U")),
                        ImmutableList.of(),
                        new TypeSignature("U"),
                        ImmutableList.of(
                                new TypeSignature("T"),
                                functionType(new TypeSignature("T"), new TypeSignature("U"))),
                        false),
                true,
                ImmutableList.of(
                        new FunctionArgumentDefinition(true),
                        new FunctionArgumentDefinition(false)),
                true,
                true,
                "lambda apply function",
                SCALAR));
    }

    @Override
    public ScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, Metadata metadata)
    {
        Type argumentType = boundVariables.getTypeVariable("T");
        Type returnType = boundVariables.getTypeVariable("U");
        return new ScalarFunctionImplementation(
                true,
                ImmutableList.of(
                        valueTypeArgumentProperty(USE_BOXED_TYPE),
                        functionTypeArgumentProperty(UnaryFunctionInterface.class)),
                METHOD_HANDLE.asType(
                        METHOD_HANDLE.type()
                                .changeReturnType(Primitives.wrap(returnType.getJavaType()))
                                .changeParameterType(0, Primitives.wrap(argumentType.getJavaType()))));
    }

    @UsedByGeneratedCode
    public static Object apply(Object input, UnaryFunctionInterface function)
    {
        return function.apply(input);
    }
}
