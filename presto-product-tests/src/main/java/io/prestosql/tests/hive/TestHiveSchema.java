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
package io.prestosql.tests.hive;

import com.google.common.collect.ImmutableList;
import io.prestosql.tempto.AfterTestWithContext;
import io.prestosql.tempto.BeforeTestWithContext;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.assertions.QueryAssert;
import io.prestosql.tempto.query.QueryExecutionException;
import io.prestosql.tempto.query.QueryResult;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.Condition;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tests.TestGroups.STORAGE_FORMATS;
import static io.prestosql.tests.utils.QueryExecutors.onPresto;
import static java.util.Objects.requireNonNull;

public class TestHiveSchema
        extends ProductTest
{
    @BeforeTestWithContext
    public void setUp()
    {
        // make sure hive.default schema is not empty
        onPresto().executeQuery("DROP TABLE IF EXISTS hive.default.test_sys_schema_disabled_table_in_default");
        onPresto().executeQuery("CREATE TABLE hive.default.test_sys_schema_disabled_table_in_default(a bigint)");
    }

    @AfterTestWithContext
    public void tearDown()
    {
        onPresto().executeQuery("DROP TABLE hive.default.test_sys_schema_disabled_table_in_default");
    }

    // Note: this test is run on various Hive versions. Hive before 3 did not have `sys` schema, but it does not hurt to run the test there too.
    @Test(groups = STORAGE_FORMATS)
    public void testSysSchemaFilteredOut()
    {
        // SHOW SCHEMAS
        assertThat(onPresto().executeQuery("SHOW SCHEMAS FROM hive"))
                .satisfies(containsFirstColumnValue("information_schema"))
                .satisfies(containsFirstColumnValue("default"))
                .doesNotHave(containsFirstColumnValue("sys"));

        // SHOW TABLES
        assertThat(() -> onPresto().executeQuery("SHOW TABLES FROM hive.sys"))
                .failsWithMessage("line 1:1: Schema 'sys' does not exist");

        // SHOW COLUMNS
        assertThat(() -> onPresto().executeQuery("SHOW COLUMNS FROM hive.sys.version")) // sys.version exists in Hive 3 and is a view
                .failsWithMessage("line 1:1: Table 'hive.sys.version' does not exist");
        assertThat(() -> onPresto().executeQuery("SHOW COLUMNS FROM hive.sys.table_params")) // sys.table_params exists in Hive 3 and is a table
                .failsWithMessage("line 1:1: Table 'hive.sys.table_params' does not exist");

        // DESCRIBE
        assertThat(() -> onPresto().executeQuery("DESCRIBE hive.sys.version")) // sys.version exists in Hive 3 and is a view
                .failsWithMessage("line 1:1: Table 'hive.sys.version' does not exist");
        assertThat(() -> onPresto().executeQuery("DESCRIBE hive.sys.table_params")) // sys.table_params exists in Hive 3 and is a table
                .failsWithMessage("line 1:1: Table 'hive.sys.table_params' does not exist");

        // information_schema.schemata
        assertThat(onPresto().executeQuery("SELECT schema_name FROM information_schema.schemata"))
                .satisfies(containsFirstColumnValue("information_schema"))
                .satisfies(containsFirstColumnValue("default"))
                .doesNotHave(containsFirstColumnValue("sys"));

        // information_schema.tables
        assertThat(onPresto().executeQuery("SELECT DISTINCT table_schema FROM information_schema.tables"))
                .satisfies(containsFirstColumnValue("information_schema"))
                .satisfies(containsFirstColumnValue("default"))
                .doesNotHave(containsFirstColumnValue("sys"));
        assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'sys'"))
                .hasNoRows();
        assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'sys' AND table_name = 'version'")) // sys.version exists in Hive 3
                .hasNoRows();

        // information_schema.columns -- it has a special handling path in metadata, which also depends on query predicates
        assertThat(onPresto().executeQuery("SELECT DISTINCT table_schema FROM information_schema.columns"))
                .satisfies(containsFirstColumnValue("information_schema"))
                .satisfies(containsFirstColumnValue("default"))
                .doesNotHave(containsFirstColumnValue("sys"));
        assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.columns WHERE table_schema = 'sys'"))
                .hasNoRows();
        assertThat(onPresto().executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'sys' AND table_name = 'version'")) // sys.version exists in Hive 3
                .hasNoRows();

        // information_schema.table_privileges -- it has a special handling path in metadata, which also depends on query predicates
        if (tablePrivilegesSupported()) {
            assertThat(onPresto().executeQuery("SELECT DISTINCT table_schema FROM information_schema.table_privileges"))
                    .doesNotHave(containsFirstColumnValue("information_schema"))
                    .satisfies(containsFirstColumnValue("default"))
                    .doesNotHave(containsFirstColumnValue("sys"));
            assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.table_privileges WHERE table_schema = 'sys'"))
                    .hasNoRows();
            assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.table_privileges WHERE table_schema = 'sys' AND table_name = 'version'")) // sys.version exists in Hive 3
                    .hasNoRows();
        }

        // SELECT
        assertThat(() -> onPresto().executeQuery("SELECT * FROM hive.sys.version")) // sys.version exists in Hive 3 and is a view
                .failsWithMessage("line 1:15: Schema 'sys' does not exist");
        assertThat(() -> onPresto().executeQuery("SELECT * FROM hive.sys.table_params")) // sys.table_params exists in Hive 3 and is a table
                .failsWithMessage("line 1:15: Schema 'sys' does not exist");
    }

    // Note: this test is run on various Hive versions. Hive before 3 did not have `information_schema` schema, but it does not hurt to run the test there too.
    @Test(groups = STORAGE_FORMATS)
    public void testHiveInformationSchemaFilteredOut()
    {
        List<String> allInformationSchemaTables = ImmutableList.<String>builder()
                // In particular, no column_privileges which exists in Hive 3's information_schema
                .add("columns")
                .add("tables")
                .add("views")
                .add("schemata")
                .add("table_privileges")
                .add("roles")
                .add("applicable_roles")
                .add("enabled_roles")
                .build();
        List<QueryAssert.Row> allInformationSchemaTablesAsRows = allInformationSchemaTables.stream()
                .map(QueryAssert.Row::row)
                .collect(toImmutableList());

        // This test is run in various setups and we may or may not have access to hive.information_schema.roles table
        List<String> allInformationSchemaTablesExceptRoles = allInformationSchemaTables.stream()
                .filter(tableName -> !tableName.equals("roles"))
                .collect(toImmutableList());
        List<QueryAssert.Row> allInformationSchemaTablesExceptRolesAsRows = allInformationSchemaTablesExceptRoles.stream()
                .map(QueryAssert.Row::row)
                .collect(toImmutableList());

        // SHOW SCHEMAS
        assertThat(onPresto().executeQuery("SHOW SCHEMAS FROM hive"))
                .satisfies(containsFirstColumnValue("information_schema"));

        // SHOW TABLES
        assertThat(onPresto().executeQuery("SHOW TABLES FROM hive.information_schema"))
                .satisfies(containsFirstColumnValue("tables"))
                .satisfies(containsFirstColumnValue("columns"))
                .satisfies(containsFirstColumnValue("table_privileges"))
                .doesNotHave(containsFirstColumnValue("column_privileges")); // Hive 3's information_schema has column_privileges view

        // SHOW COLUMNS
        assertThat(onPresto().executeQuery("SHOW COLUMNS FROM hive.information_schema.columns"))
                .satisfies(containsFirstColumnValue("table_catalog"))
                .satisfies(containsFirstColumnValue("table_schema"))
                .doesNotHave(containsFirstColumnValue("is_updatable")); // Hive 3's information_schema.columns has is_updatable column

        assertThat(() -> onPresto().executeQuery("SHOW COLUMNS FROM hive.information_schema.column_privileges")) // Hive 3's information_schema has column_privileges view
                .failsWithMessage("line 1:1: Table 'hive.information_schema.column_privileges' does not exist");

        // DESCRIBE
        assertThat(onPresto().executeQuery("DESCRIBE hive.information_schema.columns"))
                .satisfies(containsFirstColumnValue("table_catalog"))
                .satisfies(containsFirstColumnValue("table_schema"))
                .satisfies(containsFirstColumnValue("column_name"))
                .doesNotHave(containsFirstColumnValue("is_updatable")); // Hive 3's information_schema.columns has is_updatable column

        assertThat(() -> onPresto().executeQuery("DESCRIBE hive.information_schema.column_privileges")) // Hive 3's information_schema has column_privileges view
                .failsWithMessage("line 1:1: Table 'hive.information_schema.column_privileges' does not exist");

        // information_schema.schemata
        assertThat(onPresto().executeQuery("SELECT schema_name FROM information_schema.schemata"))
                .satisfies(containsFirstColumnValue("information_schema"));

        // information_schema.tables
        assertThat(onPresto().executeQuery("SELECT DISTINCT table_schema FROM information_schema.tables"))
                .satisfies(containsFirstColumnValue("information_schema"));
        assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'information_schema'"))
                .containsOnly(allInformationSchemaTablesAsRows);
        Assertions.assertThat(onPresto().executeQuery("SELECT table_schema, table_name FROM information_schema.tables").rows().stream()
                .filter(row -> row.get(0).equals("information_schema"))
                .map(row -> (String) row.get(1)))
                .containsOnly(allInformationSchemaTables.toArray(new String[0]));
        // information_schema.column_privileges exists in Hive 3
        assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'information_schema' AND table_name = 'column_privileges'"))
                .hasNoRows();

        // information_schema.columns -- it has a special handling path in metadata, which also depends on query predicates
        assertThat(onPresto().executeQuery("SELECT DISTINCT table_schema FROM information_schema.columns"))
                .satisfies(containsFirstColumnValue("information_schema"));
        assertThat(onPresto().executeQuery("SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = 'information_schema' AND table_name != 'roles'"))
                .containsOnly(allInformationSchemaTablesExceptRolesAsRows);
        Assertions.assertThat(onPresto().executeQuery("SELECT table_schema, table_name, column_name FROM information_schema.columns").rows().stream()
                .filter(row -> row.get(0).equals("information_schema"))
                .map(row -> (String) row.get(1))
                .filter(tableName -> !tableName.equals("roles"))
                .distinct())
                .containsOnly(allInformationSchemaTablesExceptRoles.toArray(new String[0]));
        assertThat(onPresto().executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'information_schema' AND table_name = 'columns'"))
                .containsOnly(
                        // In particular, no is_updatable column which exists in Hive 3's information_schema.columns
                        row("table_catalog"),
                        row("table_schema"),
                        row("table_name"),
                        row("column_name"),
                        row("ordinal_position"),
                        row("column_default"),
                        row("is_nullable"),
                        row("data_type"));
        // information_schema.column_privileges exists in Hive 3
        assertThat(onPresto().executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'information_schema' AND table_name = 'column_privileges'"))
                .hasNoRows();

        // information_schema.table_privileges -- it has a special handling path in metadata, which also depends on query predicates
        if (tablePrivilegesSupported()) {
            assertThat(onPresto().executeQuery("SELECT DISTINCT table_schema FROM information_schema.table_privileges"))
                    .satisfies(containsFirstColumnValue("default"))
                    .doesNotHave(containsFirstColumnValue("information_schema")); // tables in information_schema have no privileges
            assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.table_privileges WHERE table_schema = 'information_schema'"))
                    .hasNoRows(); // tables in information_schema have no privileges
            Assertions.assertThat(onPresto().executeQuery("SELECT table_schema, table_name, privilege_type FROM information_schema.table_privileges").rows().stream()
                    .filter(row -> row.get(0).equals("information_schema"))
                    .map(row -> (String) row.get(1)))
                    .isEmpty(); // tables in information_schema have no privileges
            assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.table_privileges WHERE table_schema = 'information_schema' AND table_name = 'columns'"))
                    .hasNoRows();
            // information_schema.column_privileges exists in Hive 3
            assertThat(onPresto().executeQuery("SELECT table_name FROM information_schema.table_privileges WHERE table_schema = 'information_schema' AND table_name = 'column_privileges'"))
                    .hasNoRows();
        }

        // SELECT
        assertThat(() -> onPresto().executeQuery("SELECT * FROM hive.information_schema.column_privileges"))  // information_schema.column_privileges exists in Hive 3
                .failsWithMessage("line 1:15: Table 'hive.information_schema.column_privileges' does not exist");
    }

    /** Returns whether table privileges are supported in current setup. */
    private boolean tablePrivilegesSupported()
    {
        try {
            onPresto().executeQuery("SELECT * FROM information_schema.table_privileges");
            return true;
        }
        catch (QueryExecutionException e) {
            if (nullToEmpty(e.getMessage()).endsWith(": This connector does not support table privileges")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * @apiNote The expected use context is in negative matching. This is why this method works on single values.
     * When matching full rows, it would be possible to have false-positive results.
     */
    private static <T> Condition<QueryResult> containsFirstColumnValue(T value)
    {
        requireNonNull(value, "value is null");
        return new Condition<>(
                queryResult -> {
                    List<?> values = queryResult.column(1);
                    if (!values.isEmpty()) {
                        // When contains() is used in a negative context (doesNotHave(...)), it could be possible to get false-positives when types are wrong.
                        Class<?> expectedType = value.getClass();
                        Class<?> actualType = values.get(0).getClass();
                        verify(expectedType.equals(actualType), "Expected QueryResult to contain %s values, but it contains %s", expectedType, actualType);
                    }
                    return values.contains(value);
                },
                "Contains(%s)",
                value);
    }
}
