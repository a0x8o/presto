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
package io.trino.operator.aggregation.listagg;

import io.airlift.slice.Slice;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.VariableWidthBlockBuilder;
import io.trino.sql.analyzer.TypeSignatureProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.block.BlockAssertions.createBooleansBlock;
import static io.trino.block.BlockAssertions.createStringsBlock;
import static io.trino.operator.aggregation.AggregationTestUtils.assertAggregation;
import static io.trino.spi.StandardErrorCode.EXCEEDED_FUNCTION_MEMORY_LIMIT;
import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;

public class TestListaggAggregationFunction
{
    private static final TestingFunctionResolution FUNCTION_RESOLUTION = new TestingFunctionResolution();

    @Test
    public void testInputEmptyState()
    {
        SingleListaggAggregationState state = new SingleListaggAggregationState();

        String s = "value1";
        Block value = createStringsBlock(s);
        Slice separator = utf8Slice(",");
        Slice overflowFiller = utf8Slice("...");
        ListaggAggregationFunction.input(
                state,
                value,
                separator,
                false,
                overflowFiller,
                true,
                0);

        VariableWidthBlockBuilder blockBuilder = VARCHAR.createBlockBuilder(null, 1);
        state.write(blockBuilder);
        String result = VARCHAR.getSlice(blockBuilder.build(), 0).toString(StandardCharsets.UTF_8);
        assertEquals(result, s);
    }

    @Test
    public void testInputOverflowOverflowFillerTooLong()
    {
        String overflowFillerTooLong = ".".repeat(65_537);

        SingleListaggAggregationState state = new SingleListaggAggregationState();

        assertThatThrownBy(() -> ListaggAggregationFunction.input(
                state,
                createStringsBlock("value1"),
                utf8Slice(","),
                false,
                utf8Slice(overflowFillerTooLong),
                false,
                0))
                .isInstanceOf(TrinoException.class)
                .matches(throwable -> ((TrinoException) throwable).getErrorCode() == INVALID_FUNCTION_ARGUMENT.toErrorCode());
    }

    @Test
    public void testOutputStateSingleValue()
    {
        SingleListaggAggregationState state = createListaggAggregationState(",", true, "...", false,
                "value1");
        assertEquals(getOutputStateOnlyValue(state, 1024), "value1");
    }

    @Test
    public void testOutputStateWithOverflowError()
    {
        SingleListaggAggregationState state = createListaggAggregationState("", true, "...", false,
                "overflowvalue1", "overflowvalue2");
        state.setMaxOutputLength(20);

        assertThatThrownBy(() -> state.write(VARCHAR.createBlockBuilder(null, 1)))
                .isInstanceOf(TrinoException.class)
                .matches(throwable -> ((TrinoException) throwable).getErrorCode() == EXCEEDED_FUNCTION_MEMORY_LIMIT.toErrorCode());
    }

    @Test
    public void testOutputStateWithEmptyValues()
    {
        SingleListaggAggregationState state = createListaggAggregationState(",", true, "...", false,
                "trino", "", "", "", "");
        assertEquals(getOutputStateOnlyValue(state, 12), "trino,,,,");
    }

    @Test
    public void testOutputStateWithEmptyDelimiter()
    {
        SingleListaggAggregationState state = createListaggAggregationState("", true, "...", false,
                "value1", "value2");
        assertEquals(getOutputStateOnlyValue(state, 12), "value1value2");
    }

    @Test
    public void testOutputStateWithSeparatorSpecialUnicodeCharacter()
    {
        SingleListaggAggregationState state = createListaggAggregationState("♥", true, "...", false,
                "Trino", "SQL", "on", "everything");
        assertEquals(getOutputStateOnlyValue(state, 29), "Trino♥SQL♥on♥everything");
    }

    @Test
    public void testOutputTruncatedStateFirstValueTooBigWithoutIndicationCount()
    {
        SingleListaggAggregationState state = createListaggAggregationState(",", false, "...", false,
                "value1", "value2");

        assertEquals(getOutputStateOnlyValue(state, 5), "...");
    }

    @Test
    public void testOutputTruncatedStateLastDelimiterOmitted()
    {
        SingleListaggAggregationState state = createListaggAggregationState("###", false, "...", false,
                "value1", "value2", "value3");
        assertEquals(getOutputStateOnlyValue(state, 18), "value1###value2###...");
        assertEquals(getOutputStateOnlyValue(state, 19), "value1###value2###...");
        assertEquals(getOutputStateOnlyValue(state, 20), "value1###value2###...");
        assertEquals(getOutputStateOnlyValue(state, 21), "value1###value2###...");
        assertEquals(getOutputStateOnlyValue(state, 22), "value1###value2###...");
    }

    @Test
    public void testOutputTruncatedStateWithoutIndicationCount()
    {
        SingleListaggAggregationState state = createListaggAggregationState(",", false, "...", false,
                "value1", "value2");
        assertEquals(getOutputStateOnlyValue(state, 9), "value1,...");
        assertEquals(getOutputStateOnlyValue(state, 10), "value1,...");
        assertEquals(getOutputStateOnlyValue(state, 11), "value1,...");
        assertEquals(getOutputStateOnlyValue(state, 12), "value1,...");
        assertEquals(getOutputStateOnlyValue(state, 13), "value1,value2");
    }

    @Test
    public void testOutputTruncatedStateWithIndicationCount()
    {
        SingleListaggAggregationState state = createListaggAggregationState(",", false, "...", true,
                "string1", "string2");
        assertEquals(getOutputStateOnlyValue(state, 12), "string1,...(1)");
        assertEquals(getOutputStateOnlyValue(state, 13), "string1,...(1)");
        assertEquals(getOutputStateOnlyValue(state, 14), "string1,...(1)");
    }

    @Test
    public void testOutputTruncatedStateWithIndicationCountAlphabet()
    {
        SingleListaggAggregationState state = createListaggAggregationState(",", false, "...", true,
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "x", "y", "z");
        assertEquals(getOutputStateOnlyValue(state, 13), "a,b,c,d,e,f,g,...(18)");
        assertEquals(getOutputStateOnlyValue(state, 14), "a,b,c,d,e,f,g,...(18)");
        assertEquals(getOutputStateOnlyValue(state, 15), "a,b,c,d,e,f,g,h,...(17)");
        assertEquals(getOutputStateOnlyValue(state, 16), "a,b,c,d,e,f,g,h,...(17)");
        assertEquals(getOutputStateOnlyValue(state, 17), "a,b,c,d,e,f,g,h,i,...(16)");
        assertEquals(getOutputStateOnlyValue(state, 18), "a,b,c,d,e,f,g,h,i,...(16)");
        assertEquals(getOutputStateOnlyValue(state, 19), "a,b,c,d,e,f,g,h,i,j,...(15)");
        assertEquals(getOutputStateOnlyValue(state, 20), "a,b,c,d,e,f,g,h,i,j,...(15)");
        assertEquals(getOutputStateOnlyValue(state, 21), "a,b,c,d,e,f,g,h,i,j,k,...(14)");
    }

    @Test
    public void testOutputTruncatedStateWithIndicationCountComplexSeparator()
    {
        SingleListaggAggregationState state = createListaggAggregationState("###", false, "...", true,
                "a", "b", "c", "dd", "e", "f", "g", "h", "i", "j", "k", "l");

        assertEquals(getOutputStateOnlyValue(state, 100), "a###b###c###dd###e###f###g###h###i###j###k###l");
        assertEquals(getOutputStateOnlyValue(state, 15), "a###b###c###dd###...(8)");
        assertEquals(getOutputStateOnlyValue(state, 16), "a###b###c###dd###...(8)");
        assertEquals(getOutputStateOnlyValue(state, 17), "a###b###c###dd###...(8)");
        assertEquals(getOutputStateOnlyValue(state, 18), "a###b###c###dd###e###...(7)");
        assertEquals(getOutputStateOnlyValue(state, 19), "a###b###c###dd###e###...(7)");
        assertEquals(getOutputStateOnlyValue(state, 20), "a###b###c###dd###e###...(7)");
        assertEquals(getOutputStateOnlyValue(state, 21), "a###b###c###dd###e###...(7)");
    }

    @Test
    public void testExecute()
    {
        List<TypeSignatureProvider> parameterTypes = fromTypes(VARCHAR, VARCHAR, BOOLEAN, VARCHAR, BOOLEAN);
        assertAggregation(
                FUNCTION_RESOLUTION,
                "listagg",
                parameterTypes,
                null,
                createStringsBlock(null, null, null),
                createStringsBlock(",", ",", ","),
                createBooleansBlock(false, false, false),
                createStringsBlock("", "", ""),
                createBooleansBlock(false, false, false));
        assertAggregation(
                FUNCTION_RESOLUTION,
                "listagg",
                parameterTypes,
                "a,c",
                createStringsBlock("a", null, "c"),
                createStringsBlock(",", ",", ","),
                createBooleansBlock(false, false, false),
                createStringsBlock("", "", ""),
                createBooleansBlock(false, false, false));
    }

    private static String getOutputStateOnlyValue(SingleListaggAggregationState state, int maxOutputLengthInBytes)
    {
        VariableWidthBlockBuilder blockBuilder = VARCHAR.createBlockBuilder(null, 1, maxOutputLengthInBytes + 20);
        state.setMaxOutputLength(maxOutputLengthInBytes);
        state.write(blockBuilder);
        return VARCHAR.getSlice(blockBuilder.build(), 0).toStringUtf8();
    }

    private static SingleListaggAggregationState createListaggAggregationState(String separator, boolean overflowError, String overflowFiller, boolean showOverflowEntryCount, String... values)
    {
        SingleListaggAggregationState state = new SingleListaggAggregationState();
        state.initialize(utf8Slice(separator), overflowError, utf8Slice(overflowFiller), showOverflowEntryCount);
        for (String value : values) {
            state.add(createStringsBlock(value), 0);
        }
        return state;
    }
}
