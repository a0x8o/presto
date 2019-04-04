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
package com.facebook.presto.cost;

import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.relational.StandardFunctionResolution;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.Test;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.spi.type.VarcharType.createVarcharType;
import static com.facebook.presto.sql.planner.iterative.rule.test.PlanBuilder.constantExpressions;
import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.sql.relational.Expressions.constant;
import static com.facebook.presto.sql.relational.Expressions.constantNull;
import static com.facebook.presto.sql.tree.ArithmeticBinaryExpression.Operator.ADD;
import static com.facebook.presto.type.UnknownType.UNKNOWN;

public class TestValuesNodeStats
        extends BaseStatsCalculatorTest
{
    @Test
    public void testStatsForValuesNode()
    {
        StandardFunctionResolution resolution = new StandardFunctionResolution(tester().getMetadata().getFunctionManager());
        tester().assertStatsFor(pb -> pb
                .values(ImmutableList.of(pb.symbol("a", BIGINT), pb.symbol("b", DOUBLE)),
                        ImmutableList.of(
                                ImmutableList.of(call(resolution.arithmeticFunction(ADD, BIGINT, BIGINT), BIGINT, constantExpressions(BIGINT, 3L, 3L)), constant(13.5, DOUBLE)),
                                ImmutableList.of(constant(55, BIGINT), constantNull(DOUBLE)),
                                ImmutableList.of(constant(6L, BIGINT), constant(13.5, DOUBLE)))))
                .check(outputStats -> outputStats.equalTo(
                        PlanNodeStatsEstimate.builder()
                                .setOutputRowCount(3)
                                .addSymbolStatistics(
                                        new Symbol("a"),
                                        SymbolStatsEstimate.builder()
                                                .setNullsFraction(0)
                                                .setLowValue(6)
                                                .setHighValue(55)
                                                .setDistinctValuesCount(2)
                                                .build())
                                .addSymbolStatistics(
                                        new Symbol("b"),
                                        SymbolStatsEstimate.builder()
                                                .setNullsFraction(0.33333333333333333)
                                                .setLowValue(13.5)
                                                .setHighValue(13.5)
                                                .setDistinctValuesCount(1)
                                                .build())
                                .build()));

        tester().assertStatsFor(pb -> pb
                .values(ImmutableList.of(pb.symbol("v", createVarcharType(30))),
                        ImmutableList.of(
                                constantExpressions(VARCHAR, "Alice"),
                                constantExpressions(VARCHAR, "has"),
                                constantExpressions(VARCHAR, "a cat"),
                                ImmutableList.of(constantNull(VARCHAR)))))
                .check(outputStats -> outputStats.equalTo(
                        PlanNodeStatsEstimate.builder()
                                .setOutputRowCount(4)
                                .addSymbolStatistics(
                                        new Symbol("v"),
                                        SymbolStatsEstimate.builder()
                                                .setNullsFraction(0.25)
                                                .setDistinctValuesCount(3)
                                                // TODO .setAverageRowSize(4 + 1. / 3)
                                                .build())
                                .build()));
    }

    @Test
    public void testStatsForValuesNodeWithJustNulls()
    {
        StandardFunctionResolution resolution = new StandardFunctionResolution(tester().getMetadata().getFunctionManager());
        PlanNodeStatsEstimate nullAStats = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(1)
                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.zero())
                .build();

        tester().assertStatsFor(pb -> pb
                .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                        ImmutableList.of(
                                ImmutableList.of(call(resolution.arithmeticFunction(ADD, BIGINT, BIGINT), BIGINT, constant(3, BIGINT), constantNull(BIGINT))))))
                .check(outputStats -> outputStats.equalTo(nullAStats));

        tester().assertStatsFor(pb -> pb
                .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                        ImmutableList.of(ImmutableList.of(constantNull(BIGINT)))))
                .check(outputStats -> outputStats.equalTo(nullAStats));

        tester().assertStatsFor(pb -> pb
                .values(ImmutableList.of(pb.symbol("a", UNKNOWN)),
                        ImmutableList.of(ImmutableList.of(constantNull(UNKNOWN)))))
                .check(outputStats -> outputStats.equalTo(nullAStats));
    }

    @Test
    public void testStatsForEmptyValues()
    {
        tester().assertStatsFor(pb -> pb
                .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                        ImmutableList.of()))
                .check(outputStats -> outputStats.equalTo(
                        PlanNodeStatsEstimate.builder()
                                .setOutputRowCount(0)
                                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.zero())
                                .build()));
    }
}
