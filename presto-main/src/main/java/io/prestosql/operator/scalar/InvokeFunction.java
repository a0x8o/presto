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
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionArgumentDefinition;
import io.prestosql.metadata.FunctionMetadata;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.sql.gen.lambda.LambdaFunctionInterface;

import java.lang.invoke.MethodHandle;

import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.metadata.Signature.typeVariable;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.functionTypeArgumentProperty;
import static io.prestosql.spi.type.TypeSignature.functionType;
import static io.prestosql.util.Reflection.methodHandle;

/**
 * This scalar function exists primarily to test lambda expression support.
 */
public final class InvokeFunction
        extends SqlScalarFunction
{
    public static final InvokeFunction INVOKE_FUNCTION = new InvokeFunction();

    private static final MethodHandle METHOD_HANDLE = methodHandle(InvokeFunction.class, "invoke", InvokeLambda.class);

    private InvokeFunction()
    {
        super(new FunctionMetadata(
                new Signature(
                        "invoke",
                        ImmutableList.of(typeVariable("T")),
                        ImmutableList.of(),
                        new TypeSignature("T"),
                        ImmutableList.of(functionType(new TypeSignature("T"))),
                        false),
                true,
                ImmutableList.of(new FunctionArgumentDefinition(false)),
                true,
                true,
                "lambda invoke function",
                SCALAR));
    }

    @Override
    public ScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, Metadata metadata)
    {
        Type returnType = boundVariables.getTypeVariable("T");
        return new ScalarFunctionImplementation(
                true,
                ImmutableList.of(functionTypeArgumentProperty(InvokeLambda.class)),
                METHOD_HANDLE.asType(
                        METHOD_HANDLE.type()
                                .changeReturnType(Primitives.wrap(returnType.getJavaType()))));
    }

    public static Object invoke(InvokeLambda function)
    {
        return function.apply();
    }

    @FunctionalInterface
    public interface InvokeLambda
            extends LambdaFunctionInterface
    {
        Object apply();
    }
}
