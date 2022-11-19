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
package io.prestosql.metadata;

import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.TypeSignature;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.metadata.Signature.mangleOperatorName;
import static io.prestosql.spi.function.OperatorType.EQUAL;
import static io.prestosql.spi.function.OperatorType.INDETERMINATE;
import static io.prestosql.spi.function.OperatorType.IS_DISTINCT_FROM;
import static io.prestosql.spi.function.OperatorType.NOT_EQUAL;
import static io.prestosql.spi.function.OperatorType.SUBSCRIPT;
import static java.util.Collections.nCopies;

public abstract class SqlOperator
        extends SqlScalarFunction
{
    protected SqlOperator(
            OperatorType operatorType,
            List<TypeVariableConstraint> typeVariableConstraints,
            List<LongVariableConstraint> longVariableConstraints,
            TypeSignature returnType,
            List<TypeSignature> argumentTypes,
            boolean nullable)
    {
        // TODO This should take Signature!
        super(new FunctionMetadata(
                new Signature(
                        mangleOperatorName(operatorType),
                        typeVariableConstraints,
                        longVariableConstraints,
                        returnType,
                        argumentTypes,
                        false),
                nullable,
                nCopies(argumentTypes.size(), new FunctionArgumentDefinition(operatorType == IS_DISTINCT_FROM || operatorType == INDETERMINATE)),
                true,
                true,
                "",
                SCALAR));
        if (operatorType == EQUAL || operatorType == NOT_EQUAL || operatorType == SUBSCRIPT) {
            checkArgument(nullable, "%s operator for %s must be nullable", operatorType, argumentTypes.get(0));
        }
    }
}
