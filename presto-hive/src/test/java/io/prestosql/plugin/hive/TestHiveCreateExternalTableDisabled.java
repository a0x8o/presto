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
package io.prestosql.plugin.hive;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.testing.AbstractTestQueryFramework;
import io.prestosql.testing.QueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.io.File;

import static com.google.common.io.Files.createTempDir;
import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.prestosql.tpch.TpchTable.CUSTOMER;
import static io.prestosql.tpch.TpchTable.ORDERS;
import static java.lang.String.format;

public class TestHiveCreateExternalTableDisabled
        extends AbstractTestQueryFramework
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return HiveQueryRunner.builder()
                .setHiveProperties(ImmutableMap.of(
                        "hive.non-managed-table-writes-enabled", "true",
                        "hive.non-managed-table-creates-enabled", "false"))
                .setInitialTables(ImmutableList.of(ORDERS, CUSTOMER))
                .build();
    }

    @Test
    public void testCreateExternalTableWithData()
            throws Exception
    {
        File tempDir = createTempDir();

        @Language("SQL") String createTableSql = format("" +
                        "CREATE TABLE test_create_external " +
                        "WITH (external_location = '%s') AS " +
                        "SELECT * FROM tpch.tiny.nation",
                tempDir.toURI().toASCIIString());
        assertQueryFails(createTableSql, "Cannot create a non-managed Hive table using CREATE TABLE AS");

        deleteRecursively(tempDir.toPath(), ALLOW_INSECURE);
    }

    @Test
    public void testCreateExternalTable()
            throws Exception
    {
        File tempDir = createTempDir();

        @Language("SQL") String createTableSql = format("" +
                        "CREATE TABLE test_create_external (n TINYINT) " +
                        "WITH (external_location = '%s')",
                tempDir.toURI().toASCIIString());
        assertQueryFails(createTableSql, "Cannot create non-managed Hive table");

        deleteRecursively(tempDir.toPath(), ALLOW_INSECURE);
    }
}
