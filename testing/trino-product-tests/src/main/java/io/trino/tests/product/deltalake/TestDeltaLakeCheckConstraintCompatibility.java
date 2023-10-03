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
package io.trino.tests.product.deltalake;

import io.trino.tempto.assertions.QueryAssert.Row;
import io.trino.testng.services.Flaky;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertQueryFailure;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static io.trino.tests.product.TestGroups.DELTA_LAKE_DATABRICKS;
import static io.trino.tests.product.TestGroups.DELTA_LAKE_OSS;
import static io.trino.tests.product.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.DATABRICKS_COMMUNICATION_FAILURE_ISSUE;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.DATABRICKS_COMMUNICATION_FAILURE_MATCH;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.dropDeltaTableWithRetry;
import static io.trino.tests.product.deltalake.util.DeltaLakeTestUtils.getTablePropertiesOnDelta;
import static io.trino.tests.product.utils.QueryExecutors.onDelta;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

public class TestDeltaLakeCheckConstraintCompatibility
        extends BaseTestDeltaLakeS3Storage
{
    @Test(groups = {DELTA_LAKE_DATABRICKS, DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS}, dataProvider = "checkConstraints")
    @Flaky(issue = DATABRICKS_COMMUNICATION_FAILURE_ISSUE, match = DATABRICKS_COMMUNICATION_FAILURE_MATCH)
    public void testCheckConstraintInsertCompatibility(String columnDefinition, String checkConstraint, String validInput, Row insertedValue, String invalidInput)
    {
        String tableName = "test_check_constraint_insert_" + randomNameSuffix();

        onDelta().executeQuery("CREATE TABLE default." + tableName +
                "(" + columnDefinition + ") " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'");
        onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT a_constraint CHECK (" + checkConstraint + ")");

        try {
            onDelta().executeQuery("INSERT INTO default." + tableName + " VALUES (" + validInput + ")");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(insertedValue);
            onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES (" + validInput + ")");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(insertedValue, insertedValue);

            assertThatThrownBy(() -> onDelta().executeQuery("INSERT INTO default." + tableName + " VALUES (" + invalidInput + ")"))
                    .hasMessageMatching("(?s).* CHECK constraint .* violated by row with values.*");
            assertThatThrownBy(() -> onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES (" + invalidInput + ")"))
                    .hasMessageContaining("Check constraint violation");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(insertedValue, insertedValue);
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }

    @DataProvider
    public static Object[][] checkConstraints()
    {
        return new Object[][] {
                // columnDefinition, checkConstraint, validInput, insertedValue, invalidInput
                // Operand
                {"a INT", "a = 1", "1", row(1), "2"},
                {"a INT", "a > 1", "2", row(2), "1"},
                {"a INT", "a >= 1", "1", row(1), "0"},
                {"a INT", "a < 1", "0", row(0), "1"},
                {"a INT", "a <= 1", "1", row(1), "2"},
                {"a INT", "a <> 1", "2", row(2), "1"},
                {"a INT", "a != 1", "2", row(2), "1"},
                // Arithmetic binary
                {"a INT, b INT", "a = b + 1", "2, 1", row(2, 1), "2, 2"},
                {"a INT, b INT", "a = b - 1", "1, 2", row(1, 2), "1, 3"},
                {"a INT, b INT", "a = b * 2", "4, 2", row(4, 2), "4, 3"},
                {"a INT, b INT", "a = b / 2", "2, 4", row(2, 4), "2, 6"},
                {"a INT, b INT", "a = b % 2", "1, 5", row(1, 5), "1, 6"},
                {"a INT, b INT", "a = b & 5", "1, 3", row(1, 3), "1, 4"},
                {"a INT, b INT", "a = b ^ 5", "6, 3", row(6, 3), "6, 4"},
                // Between
                {"a INT", "a BETWEEN 1 AND 10", "1", row(1), "0"},
                {"a INT", "a BETWEEN 1 AND 10", "10", row(10), "11"},
                {"a INT", "a NOT BETWEEN 1 AND 10", "0", row(0), "1"},
                {"a INT", "a NOT BETWEEN 1 AND 10", "11", row(11), "10"},
                {"a INT, b INT, c INT", "a BETWEEN b AND c", "5, 1, 10", row(5, 1, 10), "11, 1, 10"},
                // Supported types
                {"a INT", "a < 100", "1", row(1), "100"},
                {"a STRING", "a = 'valid'", "'valid'", row("valid"), "'invalid'"},
                {"a STRING", "a = \"double-quote\"", "'double-quote'", row("double-quote"), "'invalid'"},
                // Identifier
                {"`a.dot` INT", "`a.dot` = 1", "1", row(1), "2"},
        };
    }

    // TODO: Add DELTA_LAKE_DATABRICKS groups once flakiness on Databricks is resolved
    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testCheckConstraintUpdateCompatibility()
    {
        String tableName = "test_check_constraint_update_" + randomNameSuffix();

        onDelta().executeQuery("CREATE TABLE default." + tableName +
                "(a INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'");

        try {
            onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT a_constraint CHECK (a < 3)");

            onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES 1");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(1));
            onTrino().executeQuery("UPDATE delta.default." + tableName + " SET a = 2");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(2));

            assertThatThrownBy(() -> onDelta().executeQuery("UPDATE default." + tableName + " SET a = 3"))
                    .hasMessageMatching("(?s).* CHECK constraint .* violated by row with values.*");
            assertThatThrownBy(() -> onTrino().executeQuery("UPDATE delta.default." + tableName + " SET a = 3"))
                    .hasMessageContaining("Check constraint violation");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(2));
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }

    // TODO: Add DELTA_LAKE_DATABRICKS groups once flakiness on Databricks is resolved
    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testCheckConstraintMergeCompatibility()
    {
        String tableName1 = "test_check_constraint_merge_" + randomNameSuffix();
        String tableName2 = "test_check_constraint_merge_" + randomNameSuffix();

        onDelta().executeQuery("CREATE TABLE default." + tableName1 +
                "(a INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName1 + "'");

        onDelta().executeQuery("CREATE TABLE default." + tableName2 +
                "(a INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName2 + "'");

        try {
            onDelta().executeQuery("ALTER TABLE default." + tableName1 + " ADD CONSTRAINT a_constraint CHECK (a < 3)");

            onTrino().executeQuery("INSERT INTO delta.default." + tableName1 + " VALUES (1)");
            onTrino().executeQuery("INSERT INTO delta.default." + tableName2 + " VALUES (1), (2)");

            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName1))
                    .containsOnly(row(1));

            onTrino().executeQuery("MERGE INTO delta.default." + tableName1 + " t USING delta.default." + tableName2 + " s " +
                    "ON (t.a = s.a) " +
                    "WHEN MATCHED " +
                    "THEN UPDATE SET a = 2 " +
                    "WHEN NOT MATCHED " +
                    "THEN INSERT (a) VALUES (2)");

            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName1))
                    .containsOnly(
                            row(2),
                            row(2));

            assertThatThrownBy(() -> onDelta().executeQuery("MERGE INTO default." + tableName1 + " t USING default." + tableName2 + " s " +
                    "ON (t.a = s.a) " +
                    "WHEN MATCHED " +
                    "THEN UPDATE SET a = 3 " +
                    "WHEN NOT MATCHED " +
                    "THEN INSERT (a) VALUES (3)"))
                    .hasMessageMatching("(?s).* CHECK constraint .* violated by row with values.*");
            assertThatThrownBy(() -> onTrino().executeQuery("MERGE INTO delta.default." + tableName1 + " t USING delta.default." + tableName2 + " s " +
                    "ON (t.a = s.a) " +
                    "WHEN MATCHED " +
                    "THEN UPDATE SET a = 3 " +
                    "WHEN NOT MATCHED " +
                    "THEN INSERT (a) VALUES (3)"))
                    .hasMessageContaining("Check constraint violation");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName1))
                    .containsOnly(
                            row(2),
                            row(2));
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName1);
            dropDeltaTableWithRetry("default." + tableName2);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    public void testCheckConstraintWriterFeature()
    {
        String tableName = "test_check_constraint_writer_feature_" + randomNameSuffix();

        onDelta().executeQuery("CREATE TABLE default." + tableName +
                "(a INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'" +
                "TBLPROPERTIES ('delta.minWriterVersion'='7')");
        onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT a_constraint CHECK (a > 1)");
        try {
            onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES 2");
            assertQueryFailure(() -> onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES 1"))
                    .hasMessageContaining("Check constraint violation");

            // delta.feature.checkConstraints still exists even after unsetting the property
            onDelta().executeQuery("ALTER TABLE default." + tableName + " UNSET TBLPROPERTIES ('delta.feature.checkConstraints')");
            assertThat(getTablePropertiesOnDelta("default", tableName))
                    .contains(entry("delta.feature.checkConstraints", "supported"))
                    .contains(entry("delta.constraints.a_constraint", "a > 1"));

            // Remove the constraint directly
            onDelta().executeQuery("ALTER TABLE default." + tableName + " UNSET TBLPROPERTIES ('delta.constraints.a_constraint')");
            assertThat(getTablePropertiesOnDelta("default", tableName))
                    .contains(entry("delta.feature.checkConstraints", "supported"))
                    .doesNotContainKey("delta.constraints.a_constraint");

            // CHECK constraints shouldn't be enforced after the constraint property was removed
            onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES 3");
            onDelta().executeQuery("INSERT INTO default." + tableName + " VALUES 4");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(2), row(3), row(4));
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }

    @Test(groups = {DELTA_LAKE_DATABRICKS, DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    @Flaky(issue = DATABRICKS_COMMUNICATION_FAILURE_ISSUE, match = DATABRICKS_COMMUNICATION_FAILURE_MATCH)
    public void testCheckConstraintUnknownCondition()
    {
        String tableName = "test_check_constraint_unknown_" + randomNameSuffix();

        onDelta().executeQuery("CREATE TABLE default." + tableName +
                "(a INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'");
        onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT a_constraint CHECK (a > 1)");

        try {
            // Values which produces unknown conditions are treated as FALSE by DELTA specification and as TRUE by Trino according to SQL standard
            // https://github.com/delta-io/delta/issues/1714
            assertThatThrownBy(() -> onDelta().executeQuery("INSERT INTO default." + tableName + " VALUES (null)"))
                    .hasMessageMatching("(?s).* CHECK constraint .* violated by row with values.*");

            onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES (null)");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row((Object) null));
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }

    @Test(groups = {DELTA_LAKE_DATABRICKS, DELTA_LAKE_OSS, PROFILE_SPECIFIC_TESTS})
    @Flaky(issue = DATABRICKS_COMMUNICATION_FAILURE_ISSUE, match = DATABRICKS_COMMUNICATION_FAILURE_MATCH)
    public void testCheckConstraintAcrossColumns()
    {
        String tableName = "test_check_constraint_across_columns_" + randomNameSuffix();

        onDelta().executeQuery("CREATE TABLE default." + tableName +
                "(a INT, b INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'");
        onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT a_constraint CHECK (a = b)");
        try {
            onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES (1, 1)");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(1, 1));

            assertThatThrownBy(() -> onDelta().executeQuery("INSERT INTO default." + tableName + " VALUES (1, 2)"))
                    .hasMessageMatching("(?s).* CHECK constraint .* violated by row with values.*");
            assertThatThrownBy(() -> onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES (1, 2)"))
                    .hasMessageContaining("Check constraint violation");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(1, 1));
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, DELTA_LAKE_DATABRICKS, PROFILE_SPECIFIC_TESTS})
    @Flaky(issue = DATABRICKS_COMMUNICATION_FAILURE_ISSUE, match = DATABRICKS_COMMUNICATION_FAILURE_MATCH)
    public void testMetadataOperationsRetainCheckConstraint()
    {
        String tableName = "test_metadata_operations_retain_check_constraints_" + randomNameSuffix();
        onDelta().executeQuery("CREATE TABLE default." + tableName + " (a INT, b INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'");
        onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT aIsPositive CHECK (a > 0)");
        try {
            onTrino().executeQuery("ALTER TABLE delta.default." + tableName + " ADD COLUMN c INT");
            onTrino().executeQuery("COMMENT ON COLUMN delta.default." + tableName + ".c IS 'example column comment'");
            onTrino().executeQuery("COMMENT ON TABLE delta.default." + tableName + " IS 'example table comment'");
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }

    @Test(groups = {DELTA_LAKE_OSS, DELTA_LAKE_DATABRICKS, PROFILE_SPECIFIC_TESTS})
    @Flaky(issue = DATABRICKS_COMMUNICATION_FAILURE_ISSUE, match = DATABRICKS_COMMUNICATION_FAILURE_MATCH)
    public void testUnsupportedCheckConstraintExpression()
    {
        String tableName = "test_unsupported_check_constraints_" + randomNameSuffix();
        onDelta().executeQuery("CREATE TABLE default." + tableName + " (a INT, b INT) " +
                "USING DELTA " +
                "LOCATION 's3://" + bucketName + "/databricks-compatibility-test-" + tableName + "'");

        try {
            // This constraint should be changed to a new one if the connector supports the expression
            onDelta().executeQuery("ALTER TABLE default." + tableName + " ADD CONSTRAINT test_constraint CHECK (a = abs(b))");
            onDelta().executeQuery("INSERT INTO default." + tableName + " VALUES (1, -1), (2, -2)");

            assertQueryFailure(() -> onTrino().executeQuery("INSERT INTO delta.default." + tableName + " VALUES (1, -1)"))
                    .hasMessageContaining("Failed to convert Delta check constraints to Trino expression");
            assertQueryFailure(() -> onTrino().executeQuery("UPDATE delta.default." + tableName + " SET a = -1"))
                    .hasMessageContaining("Failed to convert Delta check constraints to Trino expression");
            onTrino().executeQuery("DELETE FROM delta.default." + tableName + " WHERE a = 2");
            assertQueryFailure(() -> onTrino().executeQuery("MERGE INTO delta.default." + tableName + " t USING delta.default." + tableName + " s " +
                    "ON (t.a = s.a) WHEN MATCHED THEN UPDATE SET b = -1"))
                    .hasMessageContaining("Failed to convert Delta check constraints to Trino expression");

            // Verify these operations succeed even if check constraints have unsupported expressions
            onTrino().executeQuery("ALTER TABLE delta.default." + tableName + " ADD COLUMN c INT");
            onTrino().executeQuery("COMMENT ON COLUMN delta.default." + tableName + ".c IS 'example column comment'");
            onTrino().executeQuery("COMMENT ON TABLE delta.default." + tableName + " IS 'example table comment'");
            assertThat(onTrino().executeQuery("SELECT * FROM delta.default." + tableName))
                    .containsOnly(row(1, -1, null));
        }
        finally {
            dropDeltaTableWithRetry("default." + tableName);
        }
    }
}
