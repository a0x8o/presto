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
package io.prestosql.operator.aggregation.state;

import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.AccumulatorState;
import io.prestosql.spi.function.AccumulatorStateMetadata;
import io.prestosql.spi.type.Type;

@AccumulatorStateMetadata(stateSerializerClass = NullableDoubleStateSerializer.class)
public interface NullableDoubleState
        extends AccumulatorState
{
    double getDouble();

    void setDouble(double value);

    @InitialBooleanValue(true)
    boolean isNull();

    void setNull(boolean value);

    static void write(Type type, NullableDoubleState state, BlockBuilder out)
    {
        if (state.isNull()) {
            out.appendNull();
        }
        else {
            type.writeDouble(out, state.getDouble());
        }
    }
}
