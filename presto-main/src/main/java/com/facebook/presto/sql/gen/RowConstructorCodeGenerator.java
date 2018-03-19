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

import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.relational.RowExpression;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Scope;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;

import java.util.List;

import static com.facebook.presto.sql.gen.SqlTypeBytecodeExpression.constantType;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantFalse;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantInt;
import static io.airlift.bytecode.expression.BytecodeExpressions.newInstance;

public class RowConstructorCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(Signature signature, BytecodeGeneratorContext context, Type rowType, List<RowExpression> arguments)
    {
        BytecodeBlock block = new BytecodeBlock().setDescription("Constructor for " + rowType.toString());
        CallSiteBinder binder = context.getCallSiteBinder();
        Scope scope = context.getScope();
        List<Type> types = rowType.getTypeParameters();

        block.comment("Create new RowBlockBuilder; beginBlockEntry;");
        Variable blockBuilder = scope.createTempVariable(BlockBuilder.class);
        Variable singleRowBlockWriter = scope.createTempVariable(BlockBuilder.class);
        block.append(blockBuilder.set(
                constantType(binder, rowType).invoke(
                        "createBlockBuilder",
                        BlockBuilder.class,
                        newInstance(BlockBuilderStatus.class),
                        constantInt(1))));
        block.append(singleRowBlockWriter.set(blockBuilder.invoke("beginBlockEntry", BlockBuilder.class)));

        for (int i = 0; i < arguments.size(); ++i) {
            Type fieldType = types.get(i);
            Class<?> javaType = fieldType.getJavaType();
            if (javaType == void.class) {
                block.comment(i + "-th field type of row is undefined");
                block.append(singleRowBlockWriter.invoke("appendNull", BlockBuilder.class).pop());
            }
            else {
                Variable field = scope.createTempVariable(javaType);
                block.comment("Clean wasNull and Generate + " + i + "-th field of row");
                block.append(context.wasNull().set(constantFalse()));
                block.append(context.generate(arguments.get(i)));
                block.putVariable(field);
                block.append(new IfStatement()
                        .condition(context.wasNull())
                        .ifTrue(singleRowBlockWriter.invoke("appendNull", BlockBuilder.class).pop())
                        .ifFalse(constantType(binder, fieldType).writeValue(singleRowBlockWriter, field).pop()));
            }
        }
        block.comment("closeEntry; slice the SingleRowBlock; wasNull = false;");
        block.append(blockBuilder.invoke("closeEntry", BlockBuilder.class).pop());
        block.append(constantType(binder, rowType).invoke("getObject", Object.class, blockBuilder.cast(Block.class), constantInt(0))
                .cast(Block.class));
        block.append(context.wasNull().set(constantFalse()));
        return block;
    }
}
