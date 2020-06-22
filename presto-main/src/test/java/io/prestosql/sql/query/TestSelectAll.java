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
package io.prestosql.sql.query;

import io.prestosql.testing.MaterializedResult;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestSelectAll
{
    private QueryAssertions assertions;

    private static final String UNSUPPORTED_DECORRELATION_MESSAGE = ".*: Given correlated subquery is not supported";

    @BeforeClass
    public void init()
    {
        assertions = new QueryAssertions();
    }

    @AfterClass(alwaysRun = true)
    public void teardown()
    {
        assertions.close();
        assertions = null;
    }

    @Test
    public void testSelectAllFromRow()
    {
        // named row fields and aliases
        assertions.assertQuery(
                "SELECT alias1, alias2 FROM (SELECT CAST((ROW (1, 'a')) AS ROW (f1 bigint, f2 varchar(1))).* AS (alias1, alias2)) t",
                "SELECT BIGINT '1' alias1, 'a' alias2");

        // unnamed row fields and aliases
        assertions.assertQuery(
                "SELECT alias1, alias2 FROM (SELECT (ROW (1, 'a')).* AS (alias1, alias2)) t",
                "SELECT 1 alias1, 'a' alias2");

        // named row fields, no aliases
        assertions.assertQuery(
                "SELECT f1, f2 FROM (SELECT CAST((ROW (1, 'a')) AS ROW (f1 bigint, f2 varchar(1))).*) t",
                "SELECT BIGINT '1' f1, 'a' f2");
        assertions.assertQuery(
                "SELECT f1 FROM (SELECT t.r.* FROM (VALUES ROW(CAST(ROW(1) AS ROW(f1 integer))), ROW(CAST(ROW(2) AS ROW(f1 integer)))) t(r))",
                "VALUES 1, 2");

        // unnamed row fields, no aliases
        assertions.assertQuery("SELECT (ROW (1, 'a')).*", "SELECT 1, 'a'");

        // wildcard from nested row
        assertions.assertQuery("SELECT a.b.* FROM (VALUES (ROW (ROW (1, 2, 3)))) A (b)", "SELECT 1, 2, 3");
        assertions.assertQuery("SELECT b[1][1].* FROM (VALUES (ROW (ROW (ROW ( ROW (1, 2, 3)))))) A (b)", "SELECT 1, 2, 3");

        // mixed select items
        assertions.assertQuery("SELECT (1, 2).*, 3", "SELECT 1, 2, 3");
        assertions.assertQuery("SELECT *, (x, 2*x).* AS (a, b), 3*x AS c FROM (VALUES 1) t(x)", "SELECT 1, 1 a, 2 b, 3 c");

        // multiple wildcards
        assertions.assertQuery("SELECT ROW(a, b).*, ROW(b, c).*, ROW(a, c).* FROM (VALUES (1, 2, 3)) t(a, b, c)", "VALUES (1, 2, 2, 3, 1, 3)");

        // non-deterministic expression precomputed
        MaterializedResult materializedResult = assertions.execute("SELECT (x, x, x, x, x, x, x, x).* FROM (SELECT rand()) T(x)");
        long distinctValuesCount = materializedResult.getMaterializedRows().get(0).getFields().stream().distinct().count();
        assertEquals(distinctValuesCount, 1, "rand() must be computed once only");

        // non-deterministic subquery
        MaterializedResult materializedResult1 = assertions.execute("SELECT (SELECT (rand(), rand(), rand(), rand())).*");
        long distinctValuesCount1 = materializedResult1.getMaterializedRows().get(0).getFields().stream().distinct().count();
        assertTrue(distinctValuesCount1 >= 3, "rand() must be computed multiple times");
    }

    @Test
    public void testSelectAllFromTable()
    {
        // qualified name from alias, column aliases
        assertions.assertQuery("SELECT a, b, c FROM (SELECT T.* FROM (VALUES (1, 2, 3)) T (a, b, c))", "SELECT 1 a, 2 b, 3 c");

        // qualified name from alias, column aliases
        assertions.assertQuery("SELECT a, b, c FROM (SELECT T.* AS (a, b, c) FROM (VALUES (1, 2, 3)) T (x, y, z))", "SELECT 1 a, 2 b, 3 c");
    }

    @Test
    public void testSelectAllWithOrderBy()
    {
        // order by row field
        assertions.assertQueryOrdered(
                "SELECT t.r.* FROM (VALUES ROW(ROW(1, 'a', true)), ROW(ROW(2, 'b', false))) t(r) ORDER BY 2 DESC",
                "VALUES (2, 'b', false), (1, 'a', true)");
        assertions.assertQueryOrdered(
                "SELECT t.r.* AS (x, y, z) FROM (VALUES ROW(ROW(1, 'a', true)), ROW(ROW(2, 'b', false))) t(r) ORDER BY y DESC",
                "VALUES (2, 'b', false), (1, 'a', true)");

        // order by row field, named row fields
        assertions.assertQueryOrdered(
                "SELECT t.r.* FROM (VALUES ROW(CAST(ROW(1) AS ROW(f1 integer))), ROW(CAST(ROW(2) AS ROW(f1 integer)))) t(r) ORDER BY 1 DESC",
                "VALUES 2, 1");
        assertions.assertQueryOrdered(
                "SELECT t.r.* FROM (VALUES ROW(CAST(ROW(1) AS ROW(f1 integer))), ROW(CAST(ROW(2) AS ROW(f1 integer)))) t(r) ORDER BY f1 DESC",
                "VALUES 2, 1");
        assertions.assertFails(
                "SELECT t.r.* FROM (VALUES ROW(CAST(ROW(1) AS ROW(f1 integer))), ROW(CAST(ROW(2) AS ROW(f2 integer)))) t(r) ORDER BY f1 DESC",
                ".*Column 'f1' cannot be resolved");
        assertions.assertQueryOrdered(
                "SELECT t.r.* AS (f1) FROM (VALUES ROW(CAST(ROW(1) AS ROW(f1 integer))), ROW(CAST(ROW(2) AS ROW(f2 integer)))) t(r) ORDER BY f1 DESC",
                "VALUES 2, 1");
        assertions.assertFails(
                "SELECT t.r.* AS (x) FROM (VALUES ROW(CAST(ROW(1) AS ROW(f1 bigint))), ROW(CAST(ROW(2) AS ROW(f1 bigint)))) t(r) ORDER BY f1 DESC",
                ".*Column 'f1' cannot be resolved");

        // order by row
        assertions.assertQueryOrdered(
                "SELECT t.r.* AS (x, y, z) FROM (VALUES ROW(ROW(1, 'a', true)), ROW(ROW(2, 'b', false))) t(r) ORDER BY r DESC",
                "VALUES (2, 'b', false), (1, 'a', true)");

        // mixed wildcards
        assertions.assertQueryOrdered(
                "SELECT *, t.r.* AS (x, y) FROM (VALUES ROW(ROW('a', true)), ROW(ROW('b', false))) t(r) ORDER BY y",
                "VALUES (ROW('b', false), 'b', false), (ROW('a', true), 'a', true)");
    }

    @Test
    public void testSelectAllWithGroupBy()
    {
        assertions.assertQuery(
                "SELECT t.r.* FROM (VALUES ROW(ROW(1, 'a', true)), ROW(ROW(1, 'a', true))) t(r) GROUP BY r",
                "VALUES (1, 'a', true)");
        assertions.assertQuery(
                "SELECT t.r.* FROM (VALUES ROW(ROW('a', true)), ROW(ROW('a', true)), ROW(ROW('a', false)), ROW(ROW('b', true))) t(r) GROUP BY r",
                "VALUES ('a', true), ('a', false), ('b', true)");
    }

    @Test
    public void testSelectAllWithAggregationAndOrderBy()
    {
        assertions.assertQueryOrdered(
                "SELECT t.r.* FROM (VALUES ROW(ROW(1, 'a', true)), ROW(ROW(2, 'b', false))) t(r) GROUP BY r ORDER BY r DESC",
                "VALUES (2, 'b', false), (1, 'a', true)");
        assertions.assertQueryOrdered(
                "SELECT r, x, count(x), t.r.* FROM (VALUES (ROW(1), 'a'), (ROW(2), 'b'), (ROW(1), 'a'), (ROW(1), 'b')) t(r, x) GROUP BY r, x ORDER BY r, x DESC",
                "VALUES (ROW(1), 'b', BIGINT '1', 1), (ROW(1), 'a', 2, 1), (ROW(2), 'b', 1, 2)");
        assertions.assertQueryOrdered(
                "SELECT array_agg(x), t.r.* FROM (VALUES (ROW(1), 'a'), (ROW(2), 'b'), (ROW(1), 'a'), (ROW(1), 'b')) t(r, x) GROUP BY r, x ORDER BY r, x DESC",
                "VALUES (ARRAY['b'], 1), (ARRAY['a', 'a'], 1), (ARRAY['b'], 2)");
        assertions.assertQueryOrdered(
                "SELECT array_agg(x), t.r.* FROM (VALUES (ROW(1), 'a'), (ROW(2), 'b'), (ROW(1), 'a'), (ROW(1), 'b')) t(r, x) GROUP BY r ORDER BY r DESC",
                "VALUES (ARRAY['b'], 2), (ARRAY['a', 'a', 'b'], 1)");
        assertions.assertQueryOrdered(
                "SELECT array_agg(x), t.r.* FROM (VALUES (ROW(1, true), 'a'), (ROW(2, false), 'b'), (ROW(1, true), 'c')) t(r, x) GROUP BY r ORDER BY r[2]",
                "VALUES (ARRAY['b'], 2, false), (ARRAY['a', 'c'], 1, true)");
        assertions.assertQueryOrdered(
                "SELECT array_agg(r[2]), t.r.* FROM (VALUES ROW(ROW(1, true)), ROW(ROW(2, false)), ROW(ROW(1, true))) t(r) GROUP BY r ORDER BY r[2]",
                "VALUES (ARRAY[false], 2, false), (ARRAY[true, true], 1, true)");
    }

    @Test
    public void testSelectAllFromOuterScopeTable()
    {
        // scalar subquery
        assertions.assertQuery("SELECT (SELECT t.*) FROM (VALUES 1, 2) t(a)", "VALUES 1, 2");
        assertions.assertQuery("SELECT (SELECT t.* FROM (SELECT 'a', 'b')) FROM (VALUES 1, 2) t(a)", "VALUES 1, 2");
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0)) FROM (VALUES 1, 2) t(a)", "VALUES 1, 2");
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0)) FROM (SELECT * FROM (VALUES 1, 1, 1) LIMIT 2) t(a)", "VALUES 1, 1");
        assertions.assertFails(
                "SELECT (SELECT t.* FROM (VALUES 0)) FROM (VALUES (1, 1), (2, 2)) t(a, b)",
                ".*Multiple columns returned by subquery are not yet supported. Found 2");
        // the following query should fail due to multiple rows returned from subquery, but instead fails to decorrelate
        assertions.assertFails("SELECT (SELECT t.* FROM (VALUES 0, 1)) FROM (VALUES 2) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);
        // filter in subquery
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0) WHERE true) FROM (VALUES 1) t(a)", "VALUES 1");
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0) WHERE 0 = 0) FROM (VALUES 1) t(a)", "VALUES 1");
        assertions.assertFails("SELECT (SELECT t.* FROM (VALUES 0) t2(b) WHERE b > 1) FROM (VALUES 1) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT (SELECT t.* FROM (VALUES 0) WHERE false) FROM (VALUES 1) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);
        // limit in subquery
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0) LIMIT 1) FROM (VALUES 1, 2) t(a)", "VALUES 1, 2");
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0) LIMIT 5) FROM (VALUES 1, 2) t(a)", "VALUES 1, 2");
        assertions.assertFails("SELECT (SELECT t.* FROM (VALUES 0) LIMIT 0) FROM (VALUES 1, 2) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT (SELECT t.* FROM (VALUES 0, 1) LIMIT 1) FROM (VALUES 2, 3) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT (SELECT t.* FROM (SELECT * FROM (VALUES 0, 1) LIMIT 1)) FROM (VALUES 2, 3) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);
        // alias shadowing
        assertions.assertQuery("SELECT (SELECT t.* FROM (VALUES 0) t) FROM (VALUES 1) t(a)", "VALUES 0");
        assertions.assertQuery("SELECT(SELECT(SELECT t.* FROM (VALUES 0)) FROM (VALUES 1) t(a)) FROM (VALUES 2) t(a)", "VALUES 1");

        // EXISTS subquery
        assertions.assertQuery("SELECT EXISTS(SELECT t.* FROM (VALUES 0)) FROM (VALUES 1, 2) t(a)", "VALUES true, true");
        assertions.assertQuery("SELECT EXISTS(SELECT t.* FROM (VALUES 1) t2(b) WHERE t2.b > t.a) FROM (VALUES 0, 2) t(a)", "VALUES true, false");

        // IN subquery
        assertions.assertFails("SELECT 1 IN (SELECT t.*) FROM (VALUES 1, 2) t(a)", UNSUPPORTED_DECORRELATION_MESSAGE);

        // lateral relation
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.*)", "VALUES (0, 0), (1, 1)");
        assertions.assertQuery("SELECT t.a, t2.d FROM (VALUES (0, 1), (2, 3)) t(a, b), LATERAL (SELECT t.*) t2(c, d)", "VALUES (0, 1), (2, 3)");
        // limit in lateral relation
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* LIMIT 5)", "VALUES (0, 0), (1, 1)");
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* LIMIT 0)", UNSUPPORTED_DECORRELATION_MESSAGE);
        // filter in lateral relation
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* WHERE true)", "VALUES (0, 0), (1, 1)");
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* WHERE 0 = 0)", "VALUES (0, 0), (1, 1)");
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* WHERE false)", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* WHERE t.a = 0)", UNSUPPORTED_DECORRELATION_MESSAGE);
        // FROM in lateral relation
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* FROM (VALUES 1))", "VALUES (0, 0), (1, 1)");
        assertions.assertQuery("SELECT t.* FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.*) t", "VALUES (0, 0), (1, 1)");
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* FROM (VALUES 1, 2))", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* FROM (VALUES 1, 2) LIMIT 1)", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t.* FROM (SELECT * FROM (VALUES 1, 2) LIMIT 1))", UNSUPPORTED_DECORRELATION_MESSAGE);

        // reference to further outer scope relation
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t2.* from (VALUES 3, 4) t2(b), LATERAL (SELECT t.*))", "VALUES (0, 3), (1, 3), (0, 4), (1, 4)");
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t2.* from (VALUES 2), LATERAL (SELECT t.*) t2(b))", "VALUES (0, 0), (1, 1)");
        assertions.assertQuery("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT t2.b from (VALUES 2), LATERAL (SELECT t.*) t2(b))", "VALUES (0, 0), (1, 1)");
        assertions.assertFails("SELECT * FROM (VALUES 0) t(a), LATERAL (SELECT t2.* from (VALUES 1, 2), LATERAL (SELECT t.*) t2(b))", UNSUPPORTED_DECORRELATION_MESSAGE);
        assertions.assertFails("SELECT * FROM (VALUES 0, 1) t(a), LATERAL (SELECT * from (VALUES 2), LATERAL (SELECT t.*))", UNSUPPORTED_DECORRELATION_MESSAGE);
    }
}
