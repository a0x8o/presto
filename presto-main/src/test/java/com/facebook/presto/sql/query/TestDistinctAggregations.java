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
package com.facebook.presto.sql.query;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestDistinctAggregations
{
    protected QueryAssertions assertions;

    @BeforeClass
    public void init()
    {
        assertions = new QueryAssertions();
    }

    @AfterClass(alwaysRun = true)
    public void teardown()
    {
        assertions.close();
    }

    @Test
    public void testGroupAllSingleDistinct()
    {
        assertions.assertQuery(
                "SELECT count(DISTINCT x) FROM " +
                        "(VALUES 1, 1, 2, 3) t(x)",
                "VALUES BIGINT '3'");

        assertions.assertQuery(
                "SELECT count(DISTINCT x), sum(DISTINCT x) FROM " +
                        "(VALUES 1, 1, 2, 3) t(x)",
                "VALUES (BIGINT '3', BIGINT '6')");
    }

    @Test
    public void testGroupBySingleDistinct()
    {
        assertions.assertQuery(
                "SELECT k, count(DISTINCT x) FROM " +
                        "(VALUES " +
                        "   (1, 1), " +
                        "   (1, 1), " +
                        "   (1, 2)," +
                        "   (1, 3)," +
                        "   (2, 1), " +
                        "   (2, 10), " +
                        "   (2, 10)," +
                        "   (2, 20)," +
                        "   (2, 30)" +
                        ") t(k, x) " +
                        "GROUP BY k",
                "VALUES " +
                        "(1, BIGINT '3'), " +
                        "(2, BIGINT '4')");

        assertions.assertQuery(
                "SELECT k, count(DISTINCT x), sum(DISTINCT x) FROM " +
                        "(VALUES " +
                        "   (1, 1), " +
                        "   (1, 1), " +
                        "   (1, 2)," +
                        "   (1, 3)," +
                        "   (2, 1), " +
                        "   (2, 10), " +
                        "   (2, 10)," +
                        "   (2, 20)," +
                        "   (2, 30)" +
                        ") t(k, x) " +
                        "GROUP BY k",
                "VALUES " +
                        "(1, BIGINT '3', BIGINT '6'), " +
                        "(2, BIGINT '4', BIGINT '61')");
    }

    @Test
    public void testGroupingSetsSingleDistinct()
    {
        assertions.assertQuery(
                "SELECT k, count(DISTINCT x) FROM " +
                        "(VALUES " +
                        "   (1, 1), " +
                        "   (1, 1), " +
                        "   (1, 2)," +
                        "   (1, 3)," +
                        "   (2, 1), " +
                        "   (2, 10), " +
                        "   (2, 10)," +
                        "   (2, 20)," +
                        "   (2, 30)" +
                        ") t(k, x) " +
                        "GROUP BY GROUPING SETS ((), (k))",
                "VALUES " +
                        "(1, BIGINT '3'), " +
                        "(2, BIGINT '4'), " +
                        "(CAST(NULL AS INTEGER), BIGINT '6')");

        assertions.assertQuery(
                "SELECT k, count(DISTINCT x), sum(DISTINCT x) FROM " +
                        "(VALUES " +
                        "   (1, 1), " +
                        "   (1, 1), " +
                        "   (1, 2)," +
                        "   (1, 3)," +
                        "   (2, 1), " +
                        "   (2, 10), " +
                        "   (2, 10)," +
                        "   (2, 20)," +
                        "   (2, 30)" +
                        ") t(k, x) " +
                        "GROUP BY GROUPING SETS ((), (k))",
                "VALUES " +
                        "(1, BIGINT '3', BIGINT '6'), " +
                        "(2, BIGINT '4', BIGINT '61'), " +
                        "(CAST(NULL AS INTEGER), BIGINT '6', BIGINT '66')");
    }

    @Test
    public void testGroupAllMixedDistinct()
    {
        assertions.assertQuery(
                "SELECT count(DISTINCT x), count(*) FROM " +
                        "(VALUES 1, 1, 2, 3) t(x)",
                "VALUES (BIGINT '3', BIGINT '4')");

        assertions.assertQuery(
                "SELECT count(DISTINCT x), count(DISTINCT y) FROM " +
                        "(VALUES " +
                        "   (1, 10), " +
                        "   (1, 20)," +
                        "   (1, 30)," +
                        "   (2, 30)) t(x, y)",
                "VALUES (BIGINT '2', BIGINT '3')");

        assertions.assertQuery(
                "SELECT k, count(DISTINCT x), count(DISTINCT y) FROM " +
                        "(VALUES " +
                        "   (1, 1, 100), " +
                        "   (1, 1, 100), " +
                        "   (1, 2, 100)," +
                        "   (1, 3, 200)," +
                        "   (2, 1, 100), " +
                        "   (2, 10, 200), " +
                        "   (2, 10, 300)," +
                        "   (2, 20, 400)," +
                        "   (2, 30, 400)" +
                        ") t(k, x, y) " +
                        "GROUP BY GROUPING SETS ((), (k))",
                "VALUES " +
                        "(1, BIGINT '3', BIGINT '2'), " +
                        "(2, BIGINT '4', BIGINT '4'), " +
                        "(CAST(NULL AS INTEGER), BIGINT '6', BIGINT '4')");
    }

    @Test
    public void testMultipleInputs()
    {
        assertions.assertQuery(
                "SELECT corr(DISTINCT x, y) FROM " +
                        "(VALUES " +
                        "   (1, 1)," +
                        "   (2, 2)," +
                        "   (2, 2)," +
                        "   (3, 3)" +
                        ") t(x, y)",
                "VALUES (REAL '1.0')");

        assertions.assertQuery(
                "SELECT corr(DISTINCT x, y), corr(DISTINCT y, x) FROM " +
                        "(VALUES " +
                        "   (1, 1)," +
                        "   (2, 2)," +
                        "   (2, 2)," +
                        "   (3, 3)" +
                        ") t(x, y)",
                "VALUES (REAL '1.0', REAL '1.0')");

        assertions.assertQuery(
                "SELECT corr(DISTINCT x, y), corr(DISTINCT y, x), count(*) FROM " +
                        "(VALUES " +
                        "   (1, 1)," +
                        "   (2, 2)," +
                        "   (2, 2)," +
                        "   (3, 3)" +
                        ") t(x, y)",
                "VALUES (REAL '1.0', REAL '1.0', BIGINT '4')");

        assertions.assertQuery(
                "SELECT corr(DISTINCT x, y), corr(DISTINCT y, x), count(DISTINCT x) FROM " +
                        "(VALUES " +
                        "   (1, 1)," +
                        "   (2, 2)," +
                        "   (2, 2)," +
                        "   (3, 3)" +
                        ") t(x, y)",
                "VALUES (REAL '1.0', REAL '1.0', BIGINT '3')");
    }

    @Test
    public void testMixedDistinctAndNonDistinct()
    {
        assertions.assertQuery(
                "SELECT sum(DISTINCT x), sum(DISTINCT y), sum(z) FROM " +
                        "(VALUES " +
                        "   (1, 10, 100), " +
                        "   (1, 20, 200)," +
                        "   (2, 20, 300)," +
                        "   (3, 30, 300)) t(x, y, z)",
                "VALUES (BIGINT '6', BIGINT '60', BIGINT '900')");
    }
}
