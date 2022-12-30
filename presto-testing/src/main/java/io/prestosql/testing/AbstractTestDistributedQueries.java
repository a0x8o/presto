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
package io.prestosql.testing;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedTimeoutException;
import io.airlift.testing.Assertions;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.dispatcher.DispatchManager;
import io.prestosql.execution.QueryInfo;
import io.prestosql.execution.QueryManager;
import io.prestosql.server.BasicQueryInfo;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.security.Identity;
import io.prestosql.testing.sql.TestTable;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.toOptional;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static io.airlift.units.Duration.nanosSince;
import static io.prestosql.SystemSessionProperties.QUERY_MAX_MEMORY;
import static io.prestosql.connector.informationschema.InformationSchemaTable.INFORMATION_SCHEMA;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static io.prestosql.testing.QueryAssertions.assertContains;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.ADD_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_VIEW;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_VIEW_WITH_SELECT_COLUMNS;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.EXECUTE_FUNCTION;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.GRANT_EXECUTE_FUNCTION;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SELECT_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SET_SESSION;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SET_USER;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SHOW_CREATE_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.privilege;
import static io.prestosql.testing.TestingSession.TESTING_CATALOG;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static io.prestosql.testing.sql.TestTable.randomTableSuffix;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public abstract class AbstractTestDistributedQueries
        extends AbstractTestQueries
{
    protected boolean supportsViews()
    {
        return true;
    }

    protected boolean supportsArrays()
    {
        return true;
    }

    /**
     * Ensure the tests are run with {@link DistributedQueryRunner}. E.g. {@link LocalQueryRunner} takes some
     * shortcuts, not exercising certain aspects.
     */
    @Test
    public void ensureDistributedQueryRunner()
    {
        assertThat(getQueryRunner().getNodeCount()).as("query runner node count")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    public void testSetSession()
    {
        MaterializedResult result = computeActual("SET SESSION test_string = 'bar'");
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of("test_string", "bar"));

        result = computeActual(format("SET SESSION %s.connector_long = 999", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_long", "999"));

        result = computeActual(format("SET SESSION %s.connector_string = 'baz'", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_string", "baz"));

        result = computeActual(format("SET SESSION %s.connector_string = 'ban' || 'ana'", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_string", "banana"));

        result = computeActual(format("SET SESSION %s.connector_long = 444", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_long", "444"));

        result = computeActual(format("SET SESSION %s.connector_long = 111 + 111", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_long", "222"));

        result = computeActual(format("SET SESSION %s.connector_boolean = 111 < 3", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_boolean", "false"));

        result = computeActual(format("SET SESSION %s.connector_double = 11.1", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getSetSessionProperties(), ImmutableMap.of(TESTING_CATALOG + ".connector_double", "11.1"));
    }

    @Test
    public void testResetSession()
    {
        MaterializedResult result = computeActual(getSession(), "RESET SESSION test_string");
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getResetSessionProperties(), ImmutableSet.of("test_string"));

        result = computeActual(getSession(), format("RESET SESSION %s.connector_string", TESTING_CATALOG));
        assertTrue((Boolean) getOnlyElement(result).getField(0));
        assertEquals(result.getResetSessionProperties(), ImmutableSet.of(TESTING_CATALOG + ".connector_string"));
    }

    @Test
    public void testCreateTable()
    {
        assertUpdate("CREATE TABLE test_create (a bigint, b double, c varchar)");
        assertTrue(getQueryRunner().tableExists(getSession(), "test_create"));
        assertTableColumnNames("test_create", "a", "b", "c");

        assertUpdate("DROP TABLE test_create");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_create"));

        assertQueryFails("CREATE TABLE test_create (a bad_type)", ".* Unknown type 'bad_type' for column 'a'");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_create"));

        assertUpdate("CREATE TABLE test_create_table_if_not_exists (a bigint, b varchar, c double)");
        assertTrue(getQueryRunner().tableExists(getSession(), "test_create_table_if_not_exists"));
        assertTableColumnNames("test_create_table_if_not_exists", "a", "b", "c");

        assertUpdate("CREATE TABLE IF NOT EXISTS test_create_table_if_not_exists (d bigint, e varchar)");
        assertTrue(getQueryRunner().tableExists(getSession(), "test_create_table_if_not_exists"));
        assertTableColumnNames("test_create_table_if_not_exists", "a", "b", "c");

        assertUpdate("DROP TABLE test_create_table_if_not_exists");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_create_table_if_not_exists"));

        // Test CREATE TABLE LIKE
        assertUpdate("CREATE TABLE test_create_original (a bigint, b double, c varchar)");
        assertTrue(getQueryRunner().tableExists(getSession(), "test_create_original"));
        assertTableColumnNames("test_create_original", "a", "b", "c");

        assertUpdate("CREATE TABLE test_create_like (LIKE test_create_original, d boolean, e varchar)");
        assertTrue(getQueryRunner().tableExists(getSession(), "test_create_like"));
        assertTableColumnNames("test_create_like", "a", "b", "c", "d", "e");

        assertUpdate("DROP TABLE test_create_original");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_create_original"));

        assertUpdate("DROP TABLE test_create_like");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_create_like"));
    }

    @Test
    public void testCreateTableAsSelect()
    {
        assertUpdate("CREATE TABLE IF NOT EXISTS test_ctas AS SELECT name, regionkey FROM nation", "SELECT count(*) FROM nation");
        assertTableColumnNames("test_ctas", "name", "regionkey");
        assertUpdate("DROP TABLE test_ctas");

        // Some connectors support CREATE TABLE AS but not the ordinary CREATE TABLE. Let's test CTAS IF NOT EXISTS with a table that is guaranteed to exist.
        assertUpdate("CREATE TABLE IF NOT EXISTS nation AS SELECT orderkey, discount FROM lineitem", 0);
        assertTableColumnNames("nation", "nationkey", "name", "regionkey", "comment");

        assertCreateTableAsSelect(
                "test_select",
                "SELECT orderdate, orderkey, totalprice FROM orders",
                "SELECT count(*) FROM orders");

        assertCreateTableAsSelect(
                "test_group",
                "SELECT orderstatus, sum(totalprice) x FROM orders GROUP BY orderstatus",
                "SELECT count(DISTINCT orderstatus) FROM orders");

        assertCreateTableAsSelect(
                "test_join",
                "SELECT count(*) x FROM lineitem JOIN orders ON lineitem.orderkey = orders.orderkey",
                "SELECT 1");

        assertCreateTableAsSelect(
                "test_limit",
                "SELECT orderkey FROM orders ORDER BY orderkey LIMIT 10",
                "SELECT 10");

        assertCreateTableAsSelect(
                "test_unicode",
                "SELECT '\u2603' unicode",
                "SELECT 1");

        assertCreateTableAsSelect(
                "test_with_data",
                "SELECT * FROM orders WITH DATA",
                "SELECT * FROM orders",
                "SELECT count(*) FROM orders");

        assertCreateTableAsSelect(
                "test_with_no_data",
                "SELECT * FROM orders WITH NO DATA",
                "SELECT * FROM orders LIMIT 0",
                "SELECT 0");

        // Tests for CREATE TABLE with UNION ALL: exercises PushTableWriteThroughUnion optimizer

        assertCreateTableAsSelect(
                "test_union_all",
                "SELECT orderdate, orderkey, totalprice FROM orders WHERE orderkey % 2 = 0 UNION ALL " +
                        "SELECT orderdate, orderkey, totalprice FROM orders WHERE orderkey % 2 = 1",
                "SELECT orderdate, orderkey, totalprice FROM orders",
                "SELECT count(*) FROM orders");

        assertCreateTableAsSelect(
                Session.builder(getSession()).setSystemProperty("redistribute_writes", "true").build(),
                "test_union_all",
                "SELECT CAST(orderdate AS DATE) orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT count(*) + 1 FROM orders");

        assertCreateTableAsSelect(
                Session.builder(getSession()).setSystemProperty("redistribute_writes", "false").build(),
                "test_union_all",
                "SELECT CAST(orderdate AS DATE) orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT count(*) + 1 FROM orders");

        assertExplainAnalyze("EXPLAIN ANALYZE CREATE TABLE analyze_test AS SELECT orderstatus FROM orders");
        assertQuery("SELECT * from analyze_test", "SELECT orderstatus FROM orders");
        assertUpdate("DROP TABLE analyze_test");
    }

    @Test
    public void testExplainAnalyze()
    {
        assertExplainAnalyze("EXPLAIN ANALYZE SELECT * FROM orders");
        assertExplainAnalyze("EXPLAIN ANALYZE SELECT count(*), clerk FROM orders GROUP BY clerk");
        assertExplainAnalyze(
                "EXPLAIN ANALYZE SELECT x + y FROM (" +
                        "   SELECT orderdate, COUNT(*) x FROM orders GROUP BY orderdate) a JOIN (" +
                        "   SELECT orderdate, COUNT(*) y FROM orders GROUP BY orderdate) b ON a.orderdate = b.orderdate");
        assertExplainAnalyze("" +
                "EXPLAIN ANALYZE SELECT *, o2.custkey\n" +
                "  IN (\n" +
                "    SELECT orderkey\n" +
                "    FROM lineitem\n" +
                "    WHERE orderkey % 5 = 0)\n" +
                "FROM (SELECT * FROM orders WHERE custkey % 256 = 0) o1\n" +
                "JOIN (SELECT * FROM orders WHERE custkey % 256 = 0) o2\n" +
                "  ON (o1.orderkey IN (SELECT orderkey FROM lineitem WHERE orderkey % 4 = 0)) = (o2.orderkey IN (SELECT orderkey FROM lineitem WHERE orderkey % 4 = 0))\n" +
                "WHERE o1.orderkey\n" +
                "  IN (\n" +
                "    SELECT orderkey\n" +
                "    FROM lineitem\n" +
                "    WHERE orderkey % 4 = 0)\n" +
                "ORDER BY o1.orderkey\n" +
                "  IN (\n" +
                "    SELECT orderkey\n" +
                "    FROM lineitem\n" +
                "    WHERE orderkey % 7 = 0)");
        assertExplainAnalyze("EXPLAIN ANALYZE SELECT count(*), clerk FROM orders GROUP BY clerk UNION ALL SELECT sum(orderkey), clerk FROM orders GROUP BY clerk");

        assertExplainAnalyze("EXPLAIN ANALYZE SHOW COLUMNS FROM orders");
        assertExplainAnalyze("EXPLAIN ANALYZE EXPLAIN SELECT count(*) FROM orders");
        assertExplainAnalyze("EXPLAIN ANALYZE EXPLAIN ANALYZE SELECT count(*) FROM orders");
        assertExplainAnalyze("EXPLAIN ANALYZE SHOW FUNCTIONS");
        assertExplainAnalyze("EXPLAIN ANALYZE SHOW TABLES");
        assertExplainAnalyze("EXPLAIN ANALYZE SHOW SCHEMAS");
        assertExplainAnalyze("EXPLAIN ANALYZE SHOW CATALOGS");
        assertExplainAnalyze("EXPLAIN ANALYZE SHOW SESSION");
    }

    @Test
    public void testExplainAnalyzeVerbose()
    {
        assertExplainAnalyze("EXPLAIN ANALYZE VERBOSE SELECT * FROM orders");
        assertExplainAnalyze("EXPLAIN ANALYZE VERBOSE SELECT rank() OVER (PARTITION BY orderkey ORDER BY clerk DESC) FROM orders");
        assertExplainAnalyze("EXPLAIN ANALYZE VERBOSE SELECT rank() OVER (PARTITION BY orderkey ORDER BY clerk DESC) FROM orders WHERE orderkey < 0");
    }

    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = "EXPLAIN ANALYZE doesn't support statement type: DropTable")
    public void testExplainAnalyzeDDL()
    {
        computeActual("EXPLAIN ANALYZE DROP TABLE orders");
    }

    protected void assertExplainAnalyze(@Language("SQL") String query)
    {
        String value = (String) computeActual(query).getOnlyValue();

        assertTrue(value.matches("(?s:.*)CPU:.*, Input:.*, Output(?s:.*)"), format("Expected output to contain \"CPU:.*, Input:.*, Output\", but it is %s", value));

        // TODO: check that rendered plan is as expected, once stats are collected in a consistent way
        // assertTrue(value.contains("Cost: "), format("Expected output to contain \"Cost: \", but it is %s", value));
    }

    protected void assertCreateTableAsSelect(String table, @Language("SQL") String query, @Language("SQL") String rowCountQuery)
    {
        assertCreateTableAsSelect(getSession(), table, query, query, rowCountQuery);
    }

    protected void assertCreateTableAsSelect(String table, @Language("SQL") String query, @Language("SQL") String expectedQuery, @Language("SQL") String rowCountQuery)
    {
        assertCreateTableAsSelect(getSession(), table, query, expectedQuery, rowCountQuery);
    }

    protected void assertCreateTableAsSelect(Session session, String table, @Language("SQL") String query, @Language("SQL") String expectedQuery, @Language("SQL") String rowCountQuery)
    {
        assertUpdate(session, "CREATE TABLE " + table + " AS " + query, rowCountQuery);
        assertQuery(session, "SELECT * FROM " + table, expectedQuery);
        assertUpdate(session, "DROP TABLE " + table);

        assertFalse(getQueryRunner().tableExists(session, table));
    }

    @Test
    public void testRenameTable()
    {
        assertUpdate("CREATE TABLE test_rename AS SELECT 123 x", 1);

        assertUpdate("ALTER TABLE test_rename RENAME TO test_rename_new");
        assertQuery("SELECT x FROM test_rename_new", "VALUES 123");

        assertUpdate("ALTER TABLE test_rename_new RENAME TO TEST_RENAME"); // 'TEST_RENAME' is upper-case, not delimited
        assertQuery(
                "SELECT x FROM test_rename", // 'test_rename' is lower-case, not delimited
                "VALUES 123");

        assertUpdate("DROP TABLE test_rename");

        assertFalse(getQueryRunner().tableExists(getSession(), "test_rename"));
        assertFalse(getQueryRunner().tableExists(getSession(), "test_rename_new"));
    }

    @Test
    public void testCommentTable()
    {
        assertUpdate("CREATE TABLE test_comment(id integer)");

        assertUpdate("COMMENT ON TABLE test_comment IS 'new comment'");
        MaterializedResult materializedRows = computeActual("SHOW CREATE TABLE test_comment");
        assertTrue(materializedRows.getMaterializedRows().get(0).getField(0).toString().contains("COMMENT 'new comment'"));

        assertUpdate("COMMENT ON TABLE test_comment IS ''");
        materializedRows = computeActual("SHOW CREATE TABLE test_comment");
        assertTrue(materializedRows.getMaterializedRows().get(0).getField(0).toString().contains("COMMENT ''"));

        assertUpdate("COMMENT ON TABLE test_comment IS NULL");
        materializedRows = computeActual("SHOW CREATE TABLE test_comment");
        assertFalse(materializedRows.getMaterializedRows().get(0).getField(0).toString().contains("COMMENT"));

        assertUpdate("DROP TABLE test_comment");
    }

    @Test
    public void testRenameColumn()
    {
        assertUpdate("CREATE TABLE test_rename_column AS SELECT 'some value' x", 1);

        assertUpdate("ALTER TABLE test_rename_column RENAME COLUMN x TO y");
        assertQuery("SELECT y FROM test_rename_column", "VALUES 'some value'");

        assertUpdate("ALTER TABLE test_rename_column RENAME COLUMN y TO Z"); // 'Z' is upper-case, not delimited
        assertQuery(
                "SELECT z FROM test_rename_column", // 'z' is lower-case, not delimited
                "VALUES 'some value'");

        // There should be exactly one column
        assertQuery("SELECT * FROM test_rename_column", "VALUES 'some value'");

        assertUpdate("DROP TABLE test_rename_column");
    }

    @Test
    public void testDropColumn()
    {
        assertUpdate("CREATE TABLE test_drop_column AS SELECT 123 x, 111 a", 1);

        assertUpdate("ALTER TABLE test_drop_column DROP COLUMN x");
        assertQueryFails("SELECT x FROM test_drop_column", ".* Column 'x' cannot be resolved");

        assertQueryFails("ALTER TABLE test_drop_column DROP COLUMN a", ".* Cannot drop the only column in a table");
    }

    @Test
    public void testAddColumn()
    {
        assertUpdate("CREATE TABLE test_add_column AS SELECT CAST('first' AS varchar) x", 1);

        assertQueryFails("ALTER TABLE test_add_column ADD COLUMN x bigint", ".* Column 'x' already exists");
        assertQueryFails("ALTER TABLE test_add_column ADD COLUMN X bigint", ".* Column 'X' already exists");
        assertQueryFails("ALTER TABLE test_add_column ADD COLUMN q bad_type", ".* Unknown type 'bad_type' for column 'q'");

        assertUpdate("ALTER TABLE test_add_column ADD COLUMN a varchar");
        assertUpdate("INSERT INTO test_add_column SELECT 'second', 'xxx'", 1);
        assertQuery(
                "SELECT x, a FROM test_add_column",
                "VALUES ('first', NULL), ('second', 'xxx')");

        assertUpdate("ALTER TABLE test_add_column ADD COLUMN b double");
        assertUpdate("INSERT INTO test_add_column SELECT 'third', 'yyy', 33.3E0", 1);
        assertQuery(
                "SELECT x, a, b FROM test_add_column",
                "VALUES ('first', NULL, NULL), ('second', 'xxx', NULL), ('third', 'yyy', 33.3)");

        assertUpdate("DROP TABLE test_add_column");
    }

    @Test
    public void testInsert()
    {
        @Language("SQL") String query = "SELECT orderdate, orderkey, totalprice FROM orders";

        assertUpdate("CREATE TABLE test_insert AS " + query + " WITH NO DATA", 0);
        assertQuery("SELECT count(*) FROM test_insert", "SELECT 0");

        assertUpdate("INSERT INTO test_insert " + query, "SELECT count(*) FROM orders");

        assertQuery("SELECT * FROM test_insert", query);

        assertUpdate("INSERT INTO test_insert (orderkey) VALUES (-1)", 1);
        assertUpdate("INSERT INTO test_insert (orderkey) VALUES (null)", 1);
        assertUpdate("INSERT INTO test_insert (orderdate) VALUES (DATE '2001-01-01')", 1);
        assertUpdate("INSERT INTO test_insert (orderkey, orderdate) VALUES (-2, DATE '2001-01-02')", 1);
        assertUpdate("INSERT INTO test_insert (orderdate, orderkey) VALUES (DATE '2001-01-03', -3)", 1);
        assertUpdate("INSERT INTO test_insert (totalprice) VALUES (1234)", 1);

        assertQuery("SELECT * FROM test_insert", query
                + " UNION ALL SELECT null, -1, null"
                + " UNION ALL SELECT null, null, null"
                + " UNION ALL SELECT DATE '2001-01-01', null, null"
                + " UNION ALL SELECT DATE '2001-01-02', -2, null"
                + " UNION ALL SELECT DATE '2001-01-03', -3, null"
                + " UNION ALL SELECT null, null, 1234");

        // UNION query produces columns in the opposite order
        // of how they are declared in the table schema
        assertUpdate(
                "INSERT INTO test_insert (orderkey, orderdate, totalprice) " +
                        "SELECT orderkey, orderdate, totalprice FROM orders " +
                        "UNION ALL " +
                        "SELECT orderkey, orderdate, totalprice FROM orders",
                "SELECT 2 * count(*) FROM orders");

        assertUpdate("DROP TABLE test_insert");
    }

    @Test
    public void testInsertWithCoercion()
    {
        assertUpdate("CREATE TABLE test_insert_with_coercion (" +
                "tinyint_column TINYINT, " +
                "integer_column INTEGER, " +
                "decimal_column DECIMAL(5, 3), " +
                "real_column REAL, " +
                "char_column CHAR(3), " +
                "bounded_varchar_column VARCHAR(3), " +
                "unbounded_varchar_column VARCHAR, " +
                "date_column DATE)");

        assertUpdate("INSERT INTO test_insert_with_coercion (tinyint_column, integer_column, decimal_column, real_column) VALUES (1e0, 2e0, 3e0, 4e0)", 1);
        assertUpdate("INSERT INTO test_insert_with_coercion (char_column, bounded_varchar_column, unbounded_varchar_column) VALUES (CAST('aa     ' AS varchar), CAST('aa     ' AS varchar), CAST('aa     ' AS varchar))", 1);
        assertUpdate("INSERT INTO test_insert_with_coercion (char_column, bounded_varchar_column, unbounded_varchar_column) VALUES (NULL, NULL, NULL)", 1);
        assertUpdate("INSERT INTO test_insert_with_coercion (char_column, bounded_varchar_column, unbounded_varchar_column) VALUES (CAST(NULL AS varchar), CAST(NULL AS varchar), CAST(NULL AS varchar))", 1);
        assertUpdate("INSERT INTO test_insert_with_coercion (date_column) VALUES (TIMESTAMP '2019-11-18 22:13:40')", 1);

        assertQuery(
                "SELECT * FROM test_insert_with_coercion",
                "VALUES " +
                        "(1, 2, 3, 4, NULL, NULL, NULL, NULL), " +
                        "(NULL, NULL, NULL, NULL, 'aa ', 'aa ', 'aa     ', NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, DATE '2019-11-18')");

        assertQueryFails("INSERT INTO test_insert_with_coercion (integer_column) VALUES (3e9)", "Out of range for integer: 3.0E9");
        assertQueryFails("INSERT INTO test_insert_with_coercion (char_column) VALUES ('abcd')", "Cannot truncate non-space characters on INSERT");
        assertQueryFails("INSERT INTO test_insert_with_coercion (bounded_varchar_column) VALUES ('abcd')", "Cannot truncate non-space characters on INSERT");

        assertUpdate("DROP TABLE test_insert_with_coercion");
    }

    @Test
    public void testInsertUnicode()
    {
        assertUpdate("DROP TABLE IF EXISTS test_insert_unicode");

        assertUpdate("CREATE TABLE test_insert_unicode(test varchar)");
        assertUpdate("INSERT INTO test_insert_unicode(test) VALUES 'Hello', U&'hello\\6d4B\\8Bd5\\+10FFFFworld\\7F16\\7801' ", 2);
        assertThat(computeActual("SELECT test FROM test_insert_unicode").getOnlyColumnAsSet())
                .containsExactlyInAnyOrder("Hello", "hello测试􏿿world编码");
        assertUpdate("DROP TABLE test_insert_unicode");

        assertUpdate("CREATE TABLE test_insert_unicode(test varchar)");
        assertUpdate("INSERT INTO test_insert_unicode(test) VALUES 'aa', 'bé'", 2);
        assertQuery("SELECT test FROM test_insert_unicode", "VALUES 'aa', 'bé'");
        assertQuery("SELECT test FROM test_insert_unicode WHERE test = 'aa'", "VALUES 'aa'");
        assertQuery("SELECT test FROM test_insert_unicode WHERE test > 'ba'", "VALUES 'bé'");
        assertQuery("SELECT test FROM test_insert_unicode WHERE test < 'ba'", "VALUES 'aa'");
        assertQueryReturnsEmptyResult("SELECT test FROM test_insert_unicode WHERE test = 'ba'");
        assertUpdate("DROP TABLE test_insert_unicode");

        assertUpdate("CREATE TABLE test_insert_unicode(test varchar)");
        assertUpdate("INSERT INTO test_insert_unicode(test) VALUES 'a', 'é'", 2);
        assertQuery("SELECT test FROM test_insert_unicode", "VALUES 'a', 'é'");
        assertQuery("SELECT test FROM test_insert_unicode WHERE test = 'a'", "VALUES 'a'");
        assertQuery("SELECT test FROM test_insert_unicode WHERE test > 'b'", "VALUES 'é'");
        assertQuery("SELECT test FROM test_insert_unicode WHERE test < 'b'", "VALUES 'a'");
        assertQueryReturnsEmptyResult("SELECT test FROM test_insert_unicode WHERE test = 'b'");
        assertUpdate("DROP TABLE test_insert_unicode");
    }

    @Test
    public void testInsertArray()
    {
        skipTestUnless(supportsArrays());

        assertUpdate("CREATE TABLE test_insert_array (a ARRAY<DOUBLE>, b ARRAY<BIGINT>)");

        assertUpdate("INSERT INTO test_insert_array (a) VALUES (ARRAY[null])", 1);
        assertUpdate("INSERT INTO test_insert_array (a, b) VALUES (ARRAY[1.23E1], ARRAY[1.23E1])", 1);
        assertQuery("SELECT a[1], b[1] FROM test_insert_array", "VALUES (null, null), (12.3, 12)");

        assertUpdate("DROP TABLE test_insert_array");
    }

    @Test
    public void testDelete()
    {
        // delete half the table, then delete the rest

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");

        assertUpdate("DELETE FROM test_delete WHERE orderkey % 2 = 0", "SELECT count(*) FROM orders WHERE orderkey % 2 = 0");
        assertQuery("SELECT * FROM test_delete", "SELECT * FROM orders WHERE orderkey % 2 <> 0");

        assertUpdate("DELETE FROM test_delete", "SELECT count(*) FROM orders WHERE orderkey % 2 <> 0");
        assertQuery("SELECT * FROM test_delete", "SELECT * FROM orders LIMIT 0");

        assertUpdate("DROP TABLE test_delete");

        // delete successive parts of the table

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");

        assertUpdate("DELETE FROM test_delete WHERE custkey <= 100", "SELECT count(*) FROM orders WHERE custkey <= 100");
        assertQuery("SELECT * FROM test_delete", "SELECT * FROM orders WHERE custkey > 100");

        assertUpdate("DELETE FROM test_delete WHERE custkey <= 300", "SELECT count(*) FROM orders WHERE custkey > 100 AND custkey <= 300");
        assertQuery("SELECT * FROM test_delete", "SELECT * FROM orders WHERE custkey > 300");

        assertUpdate("DELETE FROM test_delete WHERE custkey <= 500", "SELECT count(*) FROM orders WHERE custkey > 300 AND custkey <= 500");
        assertQuery("SELECT * FROM test_delete", "SELECT * FROM orders WHERE custkey > 500");

        assertUpdate("DROP TABLE test_delete");

        // delete using a constant property

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");

        assertUpdate("DELETE FROM test_delete WHERE orderstatus = 'O'", "SELECT count(*) FROM orders WHERE orderstatus = 'O'");
        assertQuery("SELECT * FROM test_delete", "SELECT * FROM orders WHERE orderstatus <> 'O'");

        assertUpdate("DROP TABLE test_delete");

        // delete without matching any rows

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");
        assertUpdate("DELETE FROM test_delete WHERE rand() < 0", 0);
        assertUpdate("DELETE FROM test_delete WHERE orderkey < 0", 0);
        assertUpdate("DROP TABLE test_delete");

        // delete with a predicate that optimizes to false

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");
        assertUpdate("DELETE FROM test_delete WHERE orderkey > 5 AND orderkey < 4", 0);
        assertUpdate("DROP TABLE test_delete");

        // delete using a subquery

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM lineitem", "SELECT count(*) FROM lineitem");

        assertUpdate(
                "DELETE FROM test_delete WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderstatus = 'F')",
                "SELECT count(*) FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderstatus = 'F')");
        assertQuery(
                "SELECT * FROM test_delete",
                "SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderstatus <> 'F')");

        assertUpdate("DROP TABLE test_delete");

        // delete with multiple SemiJoin

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM lineitem", "SELECT count(*) FROM lineitem");

        assertUpdate(
                "DELETE FROM test_delete\n" +
                        "WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderstatus = 'F')\n" +
                        "  AND orderkey IN (SELECT orderkey FROM orders WHERE custkey % 5 = 0)\n",
                "SELECT count(*) FROM lineitem\n" +
                        "WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderstatus = 'F')\n" +
                        "  AND orderkey IN (SELECT orderkey FROM orders WHERE custkey % 5 = 0)");
        assertQuery(
                "SELECT * FROM test_delete",
                "SELECT * FROM lineitem\n" +
                        "WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderstatus <> 'F')\n" +
                        "  OR orderkey IN (SELECT orderkey FROM orders WHERE custkey % 5 <> 0)");

        assertUpdate("DROP TABLE test_delete");

        // delete with SemiJoin null handling

        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");

        assertUpdate(
                "DELETE FROM test_delete\n" +
                        "WHERE (orderkey IN (SELECT CASE WHEN orderkey % 3 = 0 THEN NULL ELSE orderkey END FROM lineitem)) IS NULL\n",
                "SELECT count(*) FROM orders\n" +
                        "WHERE (orderkey IN (SELECT CASE WHEN orderkey % 3 = 0 THEN NULL ELSE orderkey END FROM lineitem)) IS NULL\n");
        assertQuery(
                "SELECT * FROM test_delete",
                "SELECT * FROM orders\n" +
                        "WHERE (orderkey IN (SELECT CASE WHEN orderkey % 3 = 0 THEN NULL ELSE orderkey END FROM lineitem)) IS NOT NULL\n");

        assertUpdate("DROP TABLE test_delete");

        // delete using a scalar and EXISTS subquery
        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");
        assertUpdate("DELETE FROM test_delete WHERE orderkey = (SELECT orderkey FROM orders ORDER BY orderkey LIMIT 1)", 1);
        assertUpdate("DELETE FROM test_delete WHERE orderkey = (SELECT orderkey FROM orders WHERE false)", 0);
        assertUpdate("DELETE FROM test_delete WHERE EXISTS(SELECT 1 WHERE false)", 0);
        assertUpdate("DELETE FROM test_delete WHERE EXISTS(SELECT 1)", "SELECT count(*) - 1 FROM orders");
        assertUpdate("DROP TABLE test_delete");

        // test EXPLAIN ANALYZE with CTAS
        assertExplainAnalyze("EXPLAIN ANALYZE CREATE TABLE analyze_test AS SELECT CAST(orderstatus AS VARCHAR(15)) orderstatus FROM orders");
        assertQuery("SELECT * from analyze_test", "SELECT orderstatus FROM orders");
        // check that INSERT works also
        assertExplainAnalyze("EXPLAIN ANALYZE INSERT INTO analyze_test SELECT clerk FROM orders");
        assertQuery("SELECT * from analyze_test", "SELECT orderstatus FROM orders UNION ALL SELECT clerk FROM orders");
        // check DELETE works with EXPLAIN ANALYZE
        assertExplainAnalyze("EXPLAIN ANALYZE DELETE FROM analyze_test WHERE TRUE");
        assertQuery("SELECT COUNT(*) from analyze_test", "SELECT 0");
        assertUpdate("DROP TABLE analyze_test");

        // Test DELETE access control
        assertUpdate("CREATE TABLE test_delete AS SELECT * FROM orders", "SELECT count(*) FROM orders");
        assertAccessDenied("DELETE FROM test_delete where orderkey < 12", "Cannot select from columns \\[orderkey\\] in table or view .*.test_delete.*", privilege("orderkey", SELECT_COLUMN));
        assertAccessAllowed("DELETE FROM test_delete where orderkey < 12", privilege("orderdate", SELECT_COLUMN));
        assertAccessAllowed("DELETE FROM test_delete", privilege("orders", SELECT_COLUMN));
    }

    @Test
    public void testDropTableIfExists()
    {
        assertFalse(getQueryRunner().tableExists(getSession(), "test_drop_if_exists"));
        assertUpdate("DROP TABLE IF EXISTS test_drop_if_exists");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_drop_if_exists"));
    }

    @Test
    public void testView()
    {
        skipTestUnless(supportsViews());

        @Language("SQL") String query = "SELECT orderkey, orderstatus, totalprice / 2 half FROM orders";

        assertUpdate("CREATE VIEW test_view AS SELECT 123 x");
        assertUpdate("CREATE OR REPLACE VIEW test_view AS " + query);

        assertUpdate("CREATE VIEW test_view_with_comment COMMENT 'orders' AS SELECT 123 x");
        assertUpdate("CREATE OR REPLACE VIEW test_view_with_comment COMMENT 'orders' AS " + query);

        MaterializedResult materializedRows = computeActual("SHOW CREATE VIEW test_view_with_comment");
        assertTrue(materializedRows.getMaterializedRows().get(0).getField(0).toString().contains("COMMENT 'orders'"));

        assertQuery("SELECT * FROM test_view", query);
        assertQuery("SELECT * FROM test_view_with_comment", query);

        assertQuery(
                "SELECT * FROM test_view a JOIN test_view b on a.orderkey = b.orderkey",
                format("SELECT * FROM (%s) a JOIN (%s) b ON a.orderkey = b.orderkey", query, query));

        assertQuery("WITH orders AS (SELECT * FROM orders LIMIT 0) SELECT * FROM test_view", query);

        String name = format("%s.%s.test_view", getSession().getCatalog().get(), getSession().getSchema().get());
        assertQuery("SELECT * FROM " + name, query);

        assertUpdate("DROP VIEW test_view");
        assertUpdate("DROP VIEW test_view_with_comment");
    }

    @Test
    public void testViewCaseSensitivity()
    {
        skipTestUnless(supportsViews());

        computeActual("CREATE VIEW test_view_uppercase AS SELECT X FROM (SELECT 123 X)");
        computeActual("CREATE VIEW test_view_mixedcase AS SELECT XyZ FROM (SELECT 456 XyZ)");
        assertQuery("SELECT * FROM test_view_uppercase", "SELECT X FROM (SELECT 123 X)");
        assertQuery("SELECT * FROM test_view_mixedcase", "SELECT XyZ FROM (SELECT 456 XyZ)");
    }

    @Test
    public void testCompatibleTypeChangeForView()
    {
        skipTestUnless(supportsViews());

        assertUpdate("CREATE TABLE test_table_1 AS SELECT 'abcdefg' a", 1);
        assertUpdate("CREATE VIEW test_view_1 AS SELECT a FROM test_table_1");

        assertQuery("SELECT * FROM test_view_1", "VALUES 'abcdefg'");

        // replace table with a version that's implicitly coercible to the previous one
        assertUpdate("DROP TABLE test_table_1");
        assertUpdate("CREATE TABLE test_table_1 AS SELECT 'abc' a", 1);

        assertQuery("SELECT * FROM test_view_1", "VALUES 'abc'");

        assertUpdate("DROP VIEW test_view_1");
        assertUpdate("DROP TABLE test_table_1");
    }

    @Test
    public void testCompatibleTypeChangeForView2()
    {
        skipTestUnless(supportsViews());

        assertUpdate("CREATE TABLE test_table_2 AS SELECT BIGINT '1' v", 1);
        assertUpdate("CREATE VIEW test_view_2 AS SELECT * FROM test_table_2");

        assertQuery("SELECT * FROM test_view_2", "VALUES 1");

        // replace table with a version that's implicitly coercible to the previous one
        assertUpdate("DROP TABLE test_table_2");
        assertUpdate("CREATE TABLE test_table_2 AS SELECT INTEGER '1' v", 1);

        assertQuery("SELECT * FROM test_view_2 WHERE v = 1", "VALUES 1");

        assertUpdate("DROP VIEW test_view_2");
        assertUpdate("DROP TABLE test_table_2");
    }

    @Test
    public void testViewMetadata()
    {
        skipTestUnless(supportsViews());

        @Language("SQL") String query = "SELECT BIGINT '123' x, 'foo' y";
        assertUpdate("CREATE VIEW meta_test_view AS " + query);

        // test INFORMATION_SCHEMA.TABLES
        MaterializedResult actual = computeActual(format(
                "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = '%s'",
                getSession().getSchema().get()));

        MaterializedResult expected = resultBuilder(getSession(), actual.getTypes())
                .row("customer", "BASE TABLE")
                .row("lineitem", "BASE TABLE")
                .row("meta_test_view", "VIEW")
                .row("nation", "BASE TABLE")
                .row("orders", "BASE TABLE")
                .row("part", "BASE TABLE")
                .row("partsupp", "BASE TABLE")
                .row("region", "BASE TABLE")
                .row("supplier", "BASE TABLE")
                .build();

        assertContains(actual, expected);

        // test SHOW TABLES
        actual = computeActual("SHOW TABLES");

        MaterializedResult.Builder builder = resultBuilder(getSession(), actual.getTypes());
        for (MaterializedRow row : expected.getMaterializedRows()) {
            builder.row(row.getField(0));
        }
        expected = builder.build();

        assertContains(actual, expected);

        // test INFORMATION_SCHEMA.VIEWS
        actual = computeActual(format(
                "SELECT table_name, view_definition FROM information_schema.views WHERE table_schema = '%s'",
                getSession().getSchema().get()));

        expected = resultBuilder(getSession(), actual.getTypes())
                .row("meta_test_view", formatSqlText(query))
                .build();

        assertContains(actual, expected);

        // test SHOW COLUMNS
        actual = computeActual("SHOW COLUMNS FROM meta_test_view");

        expected = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("x", "bigint", "", "")
                .row("y", "varchar(3)", "", "")
                .build();

        assertEquals(actual, expected);

        // test SHOW CREATE VIEW
        String expectedSql = formatSqlText(format(
                "CREATE VIEW %s.%s.%s AS %s",
                getSession().getCatalog().get(),
                getSession().getSchema().get(),
                "meta_test_view",
                query)).trim();

        actual = computeActual("SHOW CREATE VIEW meta_test_view");

        assertEquals(getOnlyElement(actual.getOnlyColumnAsSet()), expectedSql);

        actual = computeActual(format("SHOW CREATE VIEW %s.%s.meta_test_view", getSession().getCatalog().get(), getSession().getSchema().get()));

        assertEquals(getOnlyElement(actual.getOnlyColumnAsSet()), expectedSql);

        assertUpdate("DROP VIEW meta_test_view");
    }

    @Test
    public void testShowCreateView()
    {
        skipTestUnless(supportsViews());
        checkState(getSession().getCatalog().isPresent(), "catalog is not set");
        checkState(getSession().getSchema().isPresent(), "schema is not set");

        String viewName = "test_show_create_view";
        assertUpdate("DROP VIEW IF EXISTS " + viewName);
        String ddl = format(
                "CREATE VIEW %s.%s.%s AS\n" +
                        "SELECT *\n" +
                        "FROM\n" +
                        "  (\n" +
                        " VALUES \n" +
                        "     ROW (1, 'one')\n" +
                        "   , ROW (2, 't')\n" +
                        ")  t (col1, col2)",
                getSession().getCatalog().get(),
                getSession().getSchema().get(),
                viewName);
        assertUpdate(ddl);

        assertEquals(computeActual("SHOW CREATE VIEW " + viewName).getOnlyValue(), ddl);

        assertUpdate("DROP VIEW " + viewName);
    }

    @Test
    public void testQueryLoggingCount()
    {
        QueryManager queryManager = ((DistributedQueryRunner) getQueryRunner()).getCoordinator().getQueryManager();
        executeExclusively(() -> {
            assertUntilTimeout(
                    () -> assertEquals(
                            queryManager.getQueries().stream()
                                    .map(BasicQueryInfo::getQueryId)
                                    .map(queryManager::getFullQueryInfo)
                                    .filter(info -> !info.isFinalQueryInfo())
                                    .collect(toList()),
                            ImmutableList.of()),
                    new Duration(1, MINUTES));

            // We cannot simply get the number of completed queries as soon as all the queries are completed, because this counter may not be up-to-date at that point.
            // The completed queries counter is updated in a final query info listener, which is called eventually.
            // Therefore, here we wait until the value of this counter gets stable.

            DispatchManager dispatchManager = ((DistributedQueryRunner) getQueryRunner()).getCoordinator().getDispatchManager();
            long beforeCompletedQueriesCount = waitUntilStable(() -> dispatchManager.getStats().getCompletedQueries().getTotalCount(), new Duration(5, SECONDS));
            long beforeSubmittedQueriesCount = dispatchManager.getStats().getSubmittedQueries().getTotalCount();
            assertUpdate("CREATE TABLE test_query_logging_count AS SELECT 1 foo_1, 2 foo_2_4", 1);
            assertQuery("SELECT foo_1, foo_2_4 FROM test_query_logging_count", "SELECT 1, 2");
            assertUpdate("DROP TABLE test_query_logging_count");
            assertQueryFails("SELECT * FROM test_query_logging_count", ".*Table .* does not exist");

            // TODO: Figure out a better way of synchronization
            assertUntilTimeout(
                    () -> assertEquals(dispatchManager.getStats().getCompletedQueries().getTotalCount() - beforeCompletedQueriesCount, 4),
                    new Duration(1, MINUTES));
            assertEquals(dispatchManager.getStats().getSubmittedQueries().getTotalCount() - beforeSubmittedQueriesCount, 4);
        });
    }

    private <T> T waitUntilStable(Supplier<T> computation, Duration timeout)
    {
        T lastValue = computation.get();
        long start = System.nanoTime();
        while (!currentThread().isInterrupted() && nanosSince(start).compareTo(timeout) < 0) {
            sleepUninterruptibly(100, MILLISECONDS);
            T currentValue = computation.get();
            if (currentValue.equals(lastValue)) {
                return currentValue;
            }
            lastValue = currentValue;
        }
        throw new UncheckedTimeoutException();
    }

    private static void assertUntilTimeout(Runnable assertion, Duration timeout)
    {
        long start = System.nanoTime();
        while (!currentThread().isInterrupted()) {
            try {
                assertion.run();
                return;
            }
            catch (AssertionError e) {
                if (nanosSince(start).compareTo(timeout) > 0) {
                    throw e;
                }
            }
            sleepUninterruptibly(50, MILLISECONDS);
        }
    }

    @Test
    public void testLargeQuerySuccess()
    {
        assertQuery("SELECT " + Joiner.on(" AND ").join(nCopies(500, "1 = 1")), "SELECT true");
    }

    @Test
    public void testShowSchemasFromOther()
    {
        MaterializedResult result = computeActual("SHOW SCHEMAS FROM tpch");
        assertTrue(result.getOnlyColumnAsSet().containsAll(ImmutableSet.of(INFORMATION_SCHEMA, "tiny", "sf1")));
    }

    @Test
    public void testTableSampleSystem()
    {
        MaterializedResult fullSample = computeActual("SELECT orderkey FROM orders TABLESAMPLE SYSTEM (100)");
        MaterializedResult emptySample = computeActual("SELECT orderkey FROM orders TABLESAMPLE SYSTEM (0)");
        MaterializedResult randomSample = computeActual("SELECT orderkey FROM orders TABLESAMPLE SYSTEM (50)");
        MaterializedResult all = computeActual("SELECT orderkey FROM orders");

        assertContains(all, fullSample);
        assertEquals(emptySample.getMaterializedRows().size(), 0);
        assertTrue(all.getMaterializedRows().size() >= randomSample.getMaterializedRows().size());
    }

    @Test
    public void testTableSampleWithFiltering()
    {
        MaterializedResult emptySample = computeActual("SELECT DISTINCT orderkey, orderdate FROM orders TABLESAMPLE SYSTEM (99) WHERE orderkey BETWEEN 0 AND 0");
        MaterializedResult halfSample = computeActual("SELECT DISTINCT orderkey, orderdate FROM orders TABLESAMPLE SYSTEM (50) WHERE orderkey BETWEEN 0 AND 9999999999");
        MaterializedResult all = computeActual("SELECT orderkey, orderdate FROM orders");

        assertEquals(emptySample.getMaterializedRows().size(), 0);
        // Assertions need to be loose here because SYSTEM sampling random selects data on split boundaries. In this case either all the data will be selected, or
        // none of it. Sampling with a 100% ratio is ignored, so that also cannot be used to guarantee results.
        assertTrue(all.getMaterializedRows().size() >= halfSample.getMaterializedRows().size());
    }

    @Test
    public void testSymbolAliasing()
    {
        assertUpdate("CREATE TABLE test_symbol_aliasing AS SELECT 1 foo_1, 2 foo_2_4", 1);
        assertQuery("SELECT foo_1, foo_2_4 FROM test_symbol_aliasing", "SELECT 1, 2");
        assertUpdate("DROP TABLE test_symbol_aliasing");
    }

    @Test
    public void testNonQueryAccessControl()
    {
        skipTestUnless(supportsViews());

        assertAccessDenied("SET SESSION " + QUERY_MAX_MEMORY + " = '10MB'",
                "Cannot set system session property " + QUERY_MAX_MEMORY,
                privilege(QUERY_MAX_MEMORY, SET_SESSION));

        assertAccessDenied("CREATE TABLE foo (pk bigint)", "Cannot create table .*.foo.*", privilege("foo", CREATE_TABLE));
        assertAccessDenied("DROP TABLE orders", "Cannot drop table .*.orders.*", privilege("orders", DROP_TABLE));
        assertAccessDenied("ALTER TABLE orders RENAME TO foo", "Cannot rename table .*.orders.* to .*.foo.*", privilege("orders", RENAME_TABLE));
        assertAccessDenied("ALTER TABLE orders ADD COLUMN foo bigint", "Cannot add a column to table .*.orders.*", privilege("orders", ADD_COLUMN));
        assertAccessDenied("ALTER TABLE orders DROP COLUMN foo", "Cannot drop a column from table .*.orders.*", privilege("orders", DROP_COLUMN));
        assertAccessDenied("ALTER TABLE orders RENAME COLUMN orderkey TO foo", "Cannot rename a column in table .*.orders.*", privilege("orders", RENAME_COLUMN));
        assertAccessDenied("CREATE VIEW foo as SELECT * FROM orders", "Cannot create view .*.foo.*", privilege("foo", CREATE_VIEW));
        // todo add DROP VIEW test... not all connectors have view support

        try {
            assertAccessDenied("SELECT 1", "Principal .* cannot become user " + getSession().getUser() + ".*", privilege(getSession().getUser(), SET_USER));
        }
        catch (AssertionError e) {
            // There is no clean exception message for authorization failure.  We simply get a 403
            Assertions.assertContains(e.getMessage(), "statusCode=403");
        }
    }

    @Test
    public void testViewColumnAccessControl()
    {
        skipTestUnless(supportsViews());

        Session viewOwnerSession = TestingSession.testSessionBuilder()
                .setIdentity(Identity.ofUser("test_view_access_owner"))
                .setCatalog(getSession().getCatalog().get())
                .setSchema(getSession().getSchema().get())
                .build();

        // TEST COLUMN-LEVEL PRIVILEGES
        // view creation permissions are only checked at query time, not at creation
        assertAccessAllowed(
                viewOwnerSession,
                "CREATE VIEW test_view_column_access AS SELECT * FROM orders",
                privilege("orders", CREATE_VIEW_WITH_SELECT_COLUMNS));

        // verify selecting from a view over a table requires the view owner to have special view creation privileges for the table
        assertAccessDenied(
                "SELECT * FROM test_view_column_access",
                "View owner 'test_view_access_owner' cannot create view that selects from .*.orders.*",
                privilege(viewOwnerSession.getUser(), "orders", CREATE_VIEW_WITH_SELECT_COLUMNS));

        // verify the view owner can select from the view even without special view creation privileges
        assertAccessAllowed(
                viewOwnerSession,
                "SELECT * FROM test_view_column_access",
                privilege(viewOwnerSession.getUser(), "orders", CREATE_VIEW_WITH_SELECT_COLUMNS));

        // verify selecting from a view over a table does not require the session user to have SELECT privileges on the underlying table
        assertAccessAllowed(
                "SELECT * FROM test_view_column_access",
                privilege(getSession().getUser(), "orders", CREATE_VIEW_WITH_SELECT_COLUMNS));
        assertAccessAllowed(
                "SELECT * FROM test_view_column_access",
                privilege(getSession().getUser(), "orders", SELECT_COLUMN));

        Session nestedViewOwnerSession = TestingSession.testSessionBuilder()
                .setIdentity(Identity.ofUser("test_nested_view_access_owner"))
                .setCatalog(getSession().getCatalog().get())
                .setSchema(getSession().getSchema().get())
                .build();

        // view creation permissions are only checked at query time, not at creation
        assertAccessAllowed(
                nestedViewOwnerSession,
                "CREATE VIEW test_nested_view_column_access AS SELECT * FROM test_view_column_access",
                privilege("test_view_column_access", CREATE_VIEW_WITH_SELECT_COLUMNS));

        // verify selecting from a view over a view requires the view owner of the outer view to have special view creation privileges for the inner view
        assertAccessDenied(
                "SELECT * FROM test_nested_view_column_access",
                "View owner 'test_nested_view_access_owner' cannot create view that selects from .*.test_view_column_access.*",
                privilege(nestedViewOwnerSession.getUser(), "test_view_column_access", CREATE_VIEW_WITH_SELECT_COLUMNS));

        // verify selecting from a view over a view does not require the session user to have SELECT privileges for the inner view
        assertAccessAllowed(
                "SELECT * FROM test_nested_view_column_access",
                privilege(getSession().getUser(), "test_view_column_access", CREATE_VIEW_WITH_SELECT_COLUMNS));
        assertAccessAllowed(
                "SELECT * FROM test_nested_view_column_access",
                privilege(getSession().getUser(), "test_view_column_access", SELECT_COLUMN));

        // verify that INVOKER security runs as session user
        assertAccessAllowed(
                viewOwnerSession,
                "CREATE VIEW test_invoker_view_column_access SECURITY INVOKER AS SELECT * FROM orders",
                privilege("orders", CREATE_VIEW_WITH_SELECT_COLUMNS));
        assertAccessAllowed(
                "SELECT * FROM test_invoker_view_column_access",
                privilege(viewOwnerSession.getUser(), "orders", SELECT_COLUMN));
        assertAccessDenied(
                "SELECT * FROM test_invoker_view_column_access",
                "Cannot select from columns \\[.*\\] in table .*.orders.*",
                privilege(getSession().getUser(), "orders", SELECT_COLUMN));

        // change access denied exception to view
        assertAccessDenied("SHOW CREATE VIEW test_nested_view_column_access", "Cannot show create table for .*test_nested_view_column_access.*", privilege("test_nested_view_column_access", SHOW_CREATE_TABLE));
        assertAccessAllowed("SHOW CREATE VIEW test_nested_view_column_access", privilege("test_denied_access_view", SHOW_CREATE_TABLE));

        assertAccessAllowed(nestedViewOwnerSession, "DROP VIEW test_nested_view_column_access");
        assertAccessAllowed(viewOwnerSession, "DROP VIEW test_view_column_access");
        assertAccessAllowed(viewOwnerSession, "DROP VIEW test_invoker_view_column_access");
    }

    @Test
    public void testViewFunctionAccessControl()
    {
        skipTestUnless(supportsViews());

        Session viewOwnerSession = TestingSession.testSessionBuilder()
                .setIdentity(Identity.ofUser("test_view_access_owner"))
                .setCatalog(getSession().getCatalog().get())
                .setSchema(getSession().getSchema().get())
                .build();

        // TEST FUNCTION PRIVILEGES
        // view creation permissions are only checked at query time, not at creation
        assertAccessAllowed(
                viewOwnerSession,
                "CREATE VIEW test_view_function_access AS SELECT abs(1) AS c",
                privilege("abs", GRANT_EXECUTE_FUNCTION));

        assertAccessDenied(
                "SELECT * FROM test_view_function_access",
                "'test_view_access_owner' cannot grant 'abs' execution to user '\\w*'",
                privilege(viewOwnerSession.getUser(), "abs", GRANT_EXECUTE_FUNCTION));

        // verify executing from a view over a function does not require the session user to have execute privileges on the underlying function
        assertAccessAllowed(
                "SELECT * FROM test_view_function_access",
                privilege(getSession().getUser(), "abs", EXECUTE_FUNCTION));

        // TEST SECURITY INVOKER
        // view creation permissions are only checked at query time, not at creation
        assertAccessAllowed(
                viewOwnerSession,
                "CREATE VIEW test_invoker_view_function_access SECURITY INVOKER AS SELECT abs(1) AS c",
                privilege("abs", GRANT_EXECUTE_FUNCTION));
        assertAccessAllowed(
                "SELECT * FROM test_invoker_view_function_access",
                privilege(viewOwnerSession.getUser(), "abs", EXECUTE_FUNCTION));
        assertAccessDenied(
                "SELECT * FROM test_invoker_view_function_access",
                "Cannot execute function abs",
                privilege(getSession().getUser(), "abs", EXECUTE_FUNCTION));

        assertAccessAllowed(viewOwnerSession, "DROP VIEW test_view_function_access");
        assertAccessAllowed(viewOwnerSession, "DROP VIEW test_invoker_view_function_access");
    }

    @Test
    public void testWrittenStats()
    {
        String sql = "CREATE TABLE test_written_stats AS SELECT * FROM nation";
        DistributedQueryRunner distributedQueryRunner = (DistributedQueryRunner) getQueryRunner();
        ResultWithQueryId<MaterializedResult> resultResultWithQueryId = distributedQueryRunner.executeWithQueryId(getSession(), sql);
        QueryInfo queryInfo = distributedQueryRunner.getCoordinator().getQueryManager().getFullQueryInfo(resultResultWithQueryId.getQueryId());

        assertEquals(queryInfo.getQueryStats().getOutputPositions(), 1L);
        assertEquals(queryInfo.getQueryStats().getWrittenPositions(), 25L);
        assertTrue(queryInfo.getQueryStats().getLogicalWrittenDataSize().toBytes() > 0L);

        sql = "INSERT INTO test_written_stats SELECT * FROM nation LIMIT 10";
        resultResultWithQueryId = distributedQueryRunner.executeWithQueryId(getSession(), sql);
        queryInfo = distributedQueryRunner.getCoordinator().getQueryManager().getFullQueryInfo(resultResultWithQueryId.getQueryId());

        assertEquals(queryInfo.getQueryStats().getOutputPositions(), 1L);
        assertEquals(queryInfo.getQueryStats().getWrittenPositions(), 10L);
        assertTrue(queryInfo.getQueryStats().getLogicalWrittenDataSize().toBytes() > 0L);

        assertUpdate("DROP TABLE test_written_stats");
    }

    @Test
    public void testComplexCast()
    {
        Session session = Session.builder(getSession())
                .setSystemProperty(SystemSessionProperties.OPTIMIZE_DISTINCT_AGGREGATIONS, "true")
                .build();
        // This is optimized using CAST(null AS interval day to second) which may be problematic to deserialize on worker
        assertQuery(session, "WITH t(a, b) AS (VALUES (1, INTERVAL '1' SECOND)) " +
                        "SELECT count(DISTINCT a), CAST(max(b) AS VARCHAR) FROM t",
                "VALUES (1, '0 00:00:01.000')");
    }

    @Test
    public void testCreateSchema()
    {
        assertThat(computeActual("SHOW SCHEMAS").getOnlyColumnAsSet()).doesNotContain("test_schema_create");
        assertUpdate("CREATE SCHEMA test_schema_create");
        assertThat(computeActual("SHOW SCHEMAS").getOnlyColumnAsSet()).contains("test_schema_create");
        assertQueryFails("CREATE SCHEMA test_schema_create", "line 1:1: Schema '.*\\.test_schema_create' already exists");
        assertUpdate("DROP SCHEMA test_schema_create");
        assertQueryFails("DROP SCHEMA test_schema_create", "line 1:1: Schema '.*\\.test_schema_create' does not exist");
    }

    @Test
    public void testInsertForDefaultColumn()
    {
        try (TestTable testTable = createTableWithDefaultColumns()) {
            assertUpdate(format("INSERT INTO %s (col_required, col_required2) VALUES (1, 10)", testTable.getName()), 1);
            assertUpdate(format("INSERT INTO %s VALUES (2, 3, 4, 5, 6)", testTable.getName()), 1);
            assertUpdate(format("INSERT INTO %s VALUES (7, null, null, 8, 9)", testTable.getName()), 1);
            assertUpdate(format("INSERT INTO %s (col_required2, col_required) VALUES (12, 13)", testTable.getName()), 1);

            assertQuery("SELECT * FROM " + testTable.getName(), "VALUES (1, null, 43, 42, 10), (2, 3, 4, 5, 6), (7, null, null, 8, 9), (13, null, 43, 42, 12)");
        }
    }

    protected abstract TestTable createTableWithDefaultColumns();

    @Test(dataProvider = "testDataMappingSmokeTestDataProvider")
    public void testDataMappingSmokeTest(DataMappingTestSetup dataMappingTestSetup)
    {
        String prestoTypeName = dataMappingTestSetup.getPrestoTypeName();
        String sampleValueLiteral = dataMappingTestSetup.getSampleValueLiteral();
        String highValueLiteral = dataMappingTestSetup.getHighValueLiteral();

        String tableName = "test_data_mapping_smoke_" + prestoTypeName.replaceAll("[^a-zA-Z0-9]", "_") + "_" + randomTableSuffix();

        Runnable setup = () -> {
            String createTable = format("CREATE TABLE %s(id varchar, value %s)", tableName, prestoTypeName);
            assertUpdate(createTable);
            assertUpdate(
                    format("INSERT INTO %s VALUES ('null value', NULL), ('sample value', %s), ('high value', %s)", tableName, sampleValueLiteral, highValueLiteral),
                    3);
        };
        if (dataMappingTestSetup.isUnsupportedType()) {
            String typeNameBase = prestoTypeName.replaceFirst("\\(.*", "");
            String expectedMessagePart = format("(%1$s.*not (yet )?supported)|((?i)unsupported.*%1$s)|((?i)not supported.*%1$s)", Pattern.quote(typeNameBase));
            assertThatThrownBy(setup::run)
                    .hasMessageFindingMatch(expectedMessagePart)
                    .satisfies(e -> assertThat(getPrestoExceptionCause(e)).hasMessageFindingMatch(expectedMessagePart));
            return;
        }
        setup.run();

        // without pushdown, i.e. test read data mapping
        assertQuery("SELECT id FROM " + tableName + " WHERE rand() = 42 OR value IS NULL", "VALUES 'null value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE rand() = 42 OR value IS NOT NULL", "VALUES ('sample value'), ('high value')");
        assertQuery("SELECT id FROM " + tableName + " WHERE rand() = 42 OR value = " + sampleValueLiteral, "VALUES 'sample value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE rand() = 42 OR value = " + highValueLiteral, "VALUES 'high value'");

        assertQuery("SELECT id FROM " + tableName + " WHERE value IS NULL", "VALUES 'null value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE value IS NOT NULL", "VALUES ('sample value'), ('high value')");
        assertQuery("SELECT id FROM " + tableName + " WHERE value = " + sampleValueLiteral, "VALUES 'sample value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE value != " + sampleValueLiteral, "VALUES 'high value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE value <= " + sampleValueLiteral, "VALUES 'sample value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE value > " + sampleValueLiteral, "VALUES 'high value'");
        assertQuery("SELECT id FROM " + tableName + " WHERE value <= " + highValueLiteral, "VALUES ('sample value'), ('high value')");

        assertUpdate("DROP TABLE " + tableName);
    }

    @DataProvider
    public final Object[][] testDataMappingSmokeTestDataProvider()
    {
        return testDataMappingSmokeTestData().stream()
                .map(this::filterDataMappingSmokeTestData)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(dataMappingTestSetup -> new Object[] {dataMappingTestSetup})
                .toArray(Object[][]::new);
    }

    private List<DataMappingTestSetup> testDataMappingSmokeTestData()
    {
        return ImmutableList.<DataMappingTestSetup>builder()
                .add(new DataMappingTestSetup("tinyint", "37", "127"))
                .add(new DataMappingTestSetup("smallint", "32123", "32767"))
                .add(new DataMappingTestSetup("integer", "1274942432", "2147483647"))
                .add(new DataMappingTestSetup("bigint", "312739231274942432", "9223372036854775807"))
                .add(new DataMappingTestSetup("real", "REAL '567.123'", "REAL '999999.999'"))
                .add(new DataMappingTestSetup("double", "DOUBLE '1234567890123.123'", "DOUBLE '9999999999999.999'"))
                .add(new DataMappingTestSetup("decimal(5,3)", "12.345", "99.999"))
                .add(new DataMappingTestSetup("decimal(15,3)", "123456789012.345", "999999999999.99"))
                .add(new DataMappingTestSetup("date", "DATE '2020-02-12'", "DATE '9999-12-31'"))
                .add(new DataMappingTestSetup("time", "TIME '15:03:00'", "TIME '23:59:59.999'"))
                .add(new DataMappingTestSetup("timestamp", "TIMESTAMP '2020-02-12 15:03:00'", "TIMESTAMP '2199-12-31 23:59:59.999'"))
                .add(new DataMappingTestSetup("timestamp with time zone", "TIMESTAMP '2020-02-12 15:03:00 +01:00'", "TIMESTAMP '9999-12-31 23:59:59.999 +12:00'"))
                .add(new DataMappingTestSetup("char(3)", "'ab'", "'zzz'"))
                .add(new DataMappingTestSetup("varchar(3)", "'de'", "'zzz'"))
                .add(new DataMappingTestSetup("varchar", "'łąka for the win'", "'ŻŻŻŻŻŻŻŻŻŻ'"))
                .add(new DataMappingTestSetup("varbinary", "X'12ab3f'", "X'ffffffffffffffffffff'"))
                .build();
    }

    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        return Optional.of(dataMappingTestSetup);
    }

    private static RuntimeException getPrestoExceptionCause(Throwable e)
    {
        return Throwables.getCausalChain(e).stream()
                .filter(cause -> cause.toString().startsWith(PrestoException.class.getName() + ": ")) // e.g. io.prestosql.client.FailureInfo
                .map(RuntimeException.class::cast)
                .collect(toOptional())
                .orElseThrow(() -> new IllegalArgumentException("Exception does not have PrestoException cause", e));
    }

    protected static final class DataMappingTestSetup
    {
        private final String prestoTypeName;
        private final String sampleValueLiteral;
        private final String highValueLiteral;

        private final boolean unsupportedType;

        public DataMappingTestSetup(String prestoTypeName, String sampleValueLiteral, String highValueLiteral)
        {
            this(prestoTypeName, sampleValueLiteral, highValueLiteral, false);
        }

        private DataMappingTestSetup(String prestoTypeName, String sampleValueLiteral, String highValueLiteral, boolean unsupportedType)
        {
            this.prestoTypeName = requireNonNull(prestoTypeName, "prestoTypeName is null");
            this.sampleValueLiteral = requireNonNull(sampleValueLiteral, "sampleValueLiteral is null");
            this.highValueLiteral = requireNonNull(highValueLiteral, "highValueLiteral is null");
            this.unsupportedType = unsupportedType;
        }

        public String getPrestoTypeName()
        {
            return prestoTypeName;
        }

        public String getSampleValueLiteral()
        {
            return sampleValueLiteral;
        }

        public String getHighValueLiteral()
        {
            return highValueLiteral;
        }

        public boolean isUnsupportedType()
        {
            return unsupportedType;
        }

        public DataMappingTestSetup asUnsupported()
        {
            return new DataMappingTestSetup(
                    prestoTypeName,
                    sampleValueLiteral,
                    highValueLiteral,
                    true);
        }

        @Override
        public String toString()
        {
            // toString is brief because it's used for test case labels in IDE
            return prestoTypeName + (unsupportedType ? "!" : "");
        }
    }
}
