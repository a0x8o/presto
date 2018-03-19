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
package com.facebook.presto.operator.annotations;

import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.type.TypeManager;

import java.lang.invoke.MethodHandle;

import static com.facebook.presto.metadata.SignatureBinder.applyBoundVariables;
import static java.util.Objects.requireNonNull;

public abstract class ScalarImplementationDependency
        implements ImplementationDependency
{
    private final Signature signature;

    protected ScalarImplementationDependency(Signature signature)
    {
        this.signature = requireNonNull(signature, "signature is null");
    }

    public Signature getSignature()
    {
        return signature;
    }

    @Override
    public MethodHandle resolve(BoundVariables boundVariables, TypeManager typeManager, FunctionRegistry functionRegistry)
    {
        Signature signature = applyBoundVariables(this.signature, boundVariables, this.signature.getArgumentTypes().size());
        return functionRegistry.getScalarFunctionImplementation(signature).getMethodHandle();
    }
}
