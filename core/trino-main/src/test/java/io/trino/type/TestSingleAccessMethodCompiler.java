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
package io.trino.type;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ByteVector;

import java.lang.invoke.MethodType;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;

import static io.trino.util.SingleAccessMethodCompiler.compileSingleAccessMethod;
import static java.lang.invoke.MethodHandles.lookup;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestSingleAccessMethodCompiler
{
    @Test
    public void testBasic()
            throws ReflectiveOperationException
    {
        LongUnaryOperator addOne = compileSingleAccessMethod(
                "AddOne",
                LongUnaryOperator.class,
                lookup().findStatic(TestSingleAccessMethodCompiler.class, "increment", MethodType.methodType(long.class, long.class)));
        assertEquals(addOne.applyAsLong(1), 2L);
    }

    private static long increment(long x)
    {
        return x + 1;
    }

    @Test
    public void testBasicWithClassNameTooLong()
            throws ReflectiveOperationException
    {
        int symbolTableSizeLimit = 65535;
        int overflowingNameLength = 65550;
        StringBuilder builder = new StringBuilder(overflowingNameLength);
        for (int i = 0; i < 1150; i++) {
            builder.append("NameThatIsLongerThanTheAllowedSymbolTableUTF8ConstantSize");
        }
        String suggestedName = builder.toString();
        assertEquals(suggestedName.length(), overflowingNameLength);

        // Ensure that symbol table entries are still limited to 65535 bytes
        assertThatThrownBy(() -> new ByteVector().putUTF8(suggestedName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UTF8 string too large");

        // Class generation should succeed by truncating the class name
        LongUnaryOperator addOne = compileSingleAccessMethod(
                suggestedName,
                LongUnaryOperator.class,
                lookup().findStatic(TestSingleAccessMethodCompiler.class, "increment", MethodType.methodType(long.class, long.class)));
        assertEquals(addOne.applyAsLong(1), 2L);
        assertTrue(addOne.getClass().getName().length() < symbolTableSizeLimit, "class name should be truncated with extra room to spare");
    }

    @Test
    public void testGeneric()
            throws ReflectiveOperationException
    {
        @SuppressWarnings("unchecked")
        LongFunction<String> print = (LongFunction<String>) compileSingleAccessMethod(
                "Print",
                LongFunction.class,
                lookup().findStatic(TestSingleAccessMethodCompiler.class, "incrementAndPrint", MethodType.methodType(String.class, long.class)));
        assertEquals(print.apply(1), "2");
    }

    private static String incrementAndPrint(long x)
    {
        return String.valueOf(x + 1);
    }
}
