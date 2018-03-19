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
package com.facebook.presto.type;

import com.facebook.presto.operator.scalar.AbstractTestFunctions;
import org.testng.annotations.Test;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;

public class TestDoubleOperators
        extends AbstractTestFunctions
{
    @Test
    public void testLiteral()
    {
        assertFunction("37.7E0", DOUBLE, 37.7);
        assertFunction("17.1E0", DOUBLE, 17.1);
    }

    @Test
    public void testTypeConstructor()
    {
        assertFunction("DOUBLE '12.34'", DOUBLE, 12.34);
        assertFunction("DOUBLE '-17.6'", DOUBLE, -17.6);
        assertFunction("DOUBLE '+754'", DOUBLE, 754.0);
        assertFunction("DOUBLE PRECISION '12.34'", DOUBLE, 12.34);
        assertFunction("DOUBLE PRECISION '-17.6'", DOUBLE, -17.6);
        assertFunction("DOUBLE PRECISION '+754'", DOUBLE, 754.0);
    }

    @Test
    public void testAdd()
    {
        assertFunction("37.7E0 + 37.7E0", DOUBLE, 37.7 + 37.7);
        assertFunction("37.7E0 + 17.1E0", DOUBLE, 37.7 + 17.1);
        assertFunction("17.1E0 + 37.7E0", DOUBLE, 17.1 + 37.7);
        assertFunction("17.1E0 + 17.1E0", DOUBLE, 17.1 + 17.1);
    }

    @Test
    public void testSubtract()
    {
        assertFunction("37.7E0 - 37.7E0", DOUBLE, 37.7 - 37.7);
        assertFunction("37.7E0 - 17.1E0", DOUBLE, 37.7 - 17.1);
        assertFunction("17.1E0 - 37.7E0", DOUBLE, 17.1 - 37.7);
        assertFunction("17.1E0 - 17.1E0", DOUBLE, 17.1 - 17.1);
    }

    @Test
    public void testMultiply()
    {
        assertFunction("37.7E0 * 37.7E0", DOUBLE, 37.7 * 37.7);
        assertFunction("37.7E0 * 17.1E0", DOUBLE, 37.7 * 17.1);
        assertFunction("17.1E0 * 37.7E0", DOUBLE, 17.1 * 37.7);
        assertFunction("17.1E0 * 17.1E0", DOUBLE, 17.1 * 17.1);
    }

    @Test
    public void testDivide()
    {
        assertFunction("37.7E0 / 37.7E0", DOUBLE, 37.7 / 37.7);
        assertFunction("37.7E0 / 17.1E0", DOUBLE, 37.7 / 17.1);
        assertFunction("17.1E0 / 37.7E0", DOUBLE, 17.1 / 37.7);
        assertFunction("17.1E0 / 17.1E0", DOUBLE, 17.1 / 17.1);
    }

    @Test
    public void testModulus()
    {
        assertFunction("37.7E0 % 37.7E0", DOUBLE, 37.7 % 37.7);
        assertFunction("37.7E0 % 17.1E0", DOUBLE, 37.7 % 17.1);
        assertFunction("17.1E0 % 37.7E0", DOUBLE, 17.1 % 37.7);
        assertFunction("17.1E0 % 17.1E0", DOUBLE, 17.1 % 17.1);
    }

    @Test
    public void testNegation()
    {
        assertFunction("-(37.7E0)", DOUBLE, -37.7);
        assertFunction("-(17.1E0)", DOUBLE, -17.1);
    }

    @Test
    public void testEqual()
    {
        assertFunction("37.7E0 = 37.7E0", BOOLEAN, true);
        assertFunction("37.7E0 = 17.1E0", BOOLEAN, false);
        assertFunction("17.1E0 = 37.7E0", BOOLEAN, false);
        assertFunction("17.1E0 = 17.1E0", BOOLEAN, true);
    }

    @Test
    public void testNotEqual()
    {
        assertFunction("37.7E0 <> 37.7E0", BOOLEAN, false);
        assertFunction("37.7E0 <> 17.1E0", BOOLEAN, true);
        assertFunction("17.1E0 <> 37.7E0", BOOLEAN, true);
        assertFunction("17.1E0 <> 17.1E0", BOOLEAN, false);
    }

    @Test
    public void testLessThan()
    {
        assertFunction("37.7E0 < 37.7E0", BOOLEAN, false);
        assertFunction("37.7E0 < 17.1E0", BOOLEAN, false);
        assertFunction("17.1E0 < 37.7E0", BOOLEAN, true);
        assertFunction("17.1E0 < 17.1E0", BOOLEAN, false);
    }

    @Test
    public void testLessThanOrEqual()
    {
        assertFunction("37.7E0 <= 37.7E0", BOOLEAN, true);
        assertFunction("37.7E0 <= 17.1E0", BOOLEAN, false);
        assertFunction("17.1E0 <= 37.7E0", BOOLEAN, true);
        assertFunction("17.1E0 <= 17.1E0", BOOLEAN, true);
    }

    @Test
    public void testGreaterThan()
    {
        assertFunction("37.7E0 > 37.7E0", BOOLEAN, false);
        assertFunction("37.7E0 > 17.1E0", BOOLEAN, true);
        assertFunction("17.1E0 > 37.7E0", BOOLEAN, false);
        assertFunction("17.1E0 > 17.1E0", BOOLEAN, false);
    }

    @Test
    public void testGreaterThanOrEqual()
    {
        assertFunction("37.7E0 >= 37.7E0", BOOLEAN, true);
        assertFunction("37.7E0 >= 17.1E0", BOOLEAN, true);
        assertFunction("17.1E0 >= 37.7E0", BOOLEAN, false);
        assertFunction("17.1E0 >= 17.1E0", BOOLEAN, true);
    }

    @Test
    public void testBetween()
    {
        assertFunction("37.7E0 BETWEEN 37.7E0 AND 37.7E0", BOOLEAN, true);
        assertFunction("37.7E0 BETWEEN 37.7E0 AND 17.1E0", BOOLEAN, false);

        assertFunction("37.7E0 BETWEEN 17.1E0 AND 37.7E0", BOOLEAN, true);
        assertFunction("37.7E0 BETWEEN 17.1E0 AND 17.1E0", BOOLEAN, false);

        assertFunction("17.1E0 BETWEEN 37.7E0 AND 37.7E0", BOOLEAN, false);
        assertFunction("17.1E0 BETWEEN 37.7E0 AND 17.1E0", BOOLEAN, false);

        assertFunction("17.1E0 BETWEEN 17.1E0 AND 37.7E0", BOOLEAN, true);
        assertFunction("17.1E0 BETWEEN 17.1E0 AND 17.1E0", BOOLEAN, true);
    }

    @Test
    public void testCastToVarchar()
    {
        assertFunction("cast(37.7E0 as varchar)", VARCHAR, "37.7");
        assertFunction("cast(17.1E0 as varchar)", VARCHAR, "17.1");
    }

    @Test
    public void testCastToBigint()
    {
        assertFunction("cast(37.7E0 as bigint)", BIGINT, 38L);
        assertFunction("cast(17.1E0 as bigint)", BIGINT, 17L);
    }

    @Test
    public void testCastToBoolean()
    {
        assertFunction("cast(37.7E0 as boolean)", BOOLEAN, true);
        assertFunction("cast(17.1E0 as boolean)", BOOLEAN, true);
        assertFunction("cast(0.0E0 as boolean)", BOOLEAN, false);
    }

    @Test
    public void testCastToFloat()
    {
        assertFunction("cast('754.1985' as real)", REAL, 754.1985f);
        assertFunction("cast('-754.2008' as real)", REAL, -754.2008f);
        assertFunction("cast('0.0' as real)", REAL, 0.0f);
        assertFunction("cast('-0.0' as real)", REAL, -0.0f);
    }

    @Test
    public void testCastFromVarchar()
    {
        assertFunction("cast('37.7' as double)", DOUBLE, 37.7);
        assertFunction("cast('17.1' as double)", DOUBLE, 17.1);
        assertFunction("cast('37.7' as double precision)", DOUBLE, 37.7);
        assertFunction("cast('17.1' as double precision)", DOUBLE, 17.1);
    }

    @Test
    public void testIsDistinctFrom()
    {
        assertFunction("CAST(NULL AS DOUBLE) IS DISTINCT FROM CAST(NULL AS DOUBLE)", BOOLEAN, false);
        assertFunction("37.7 IS DISTINCT FROM 37.7", BOOLEAN, false);
        assertFunction("37 IS DISTINCT FROM 37.8", BOOLEAN, true);
        assertFunction("NULL IS DISTINCT FROM 37.7", BOOLEAN, true);
        assertFunction("37.7 IS DISTINCT FROM NULL", BOOLEAN, true);
    }
}
