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

import com.facebook.presto.spi.function.InvocationConvention;
import com.facebook.presto.spi.function.OperatorType;
import com.facebook.presto.spi.type.TypeSignature;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.metadata.InternalSignatureUtils.internalOperator;
import static java.util.Objects.requireNonNull;

public final class OperatorImplementationDependency
        extends ScalarImplementationDependency
{
    private final OperatorType operator;

    public OperatorImplementationDependency(OperatorType operator, TypeSignature returnType, List<TypeSignature> argumentTypes, Optional<InvocationConvention> invocationConvention)
    {
        super(internalOperator(operator, returnType, argumentTypes), invocationConvention);
        this.operator = requireNonNull(operator, "operator is null");
    }

    public OperatorType getOperator()
    {
        return operator;
    }
}
