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

import com.google.inject.Inject;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.assertions.QueryAssert;
import io.prestosql.tempto.fulfillment.table.hive.HiveDataSource;
import io.prestosql.tempto.hadoop.hdfs.HdfsClient;
import io.prestosql.tempto.internal.hadoop.hdfs.HdfsDataSourceWriter;
import io.prestosql.tempto.query.QueryResult;
import org.testng.annotations.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.tempto.fulfillment.table.hive.InlineDataSource.createResourceDataSource;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.HIVE_PARTITIONING;
import static io.prestosql.tests.TestGroups.SMOKE;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class TestHivePartitionProcedures
        extends ProductTest
{
    private static final String HDFS_DIRECTORY_PATH = "/user/hive/warehouse/";
    private static final String OUTSIDE_TABLES_DIRECTORY_PATH = "/user/hive/dangling";
    private static final String FIRST_TABLE = "first_table";
    private static final String SECOND_TABLE = "second_table";
    private static final String VIEW_TABLE = "view_table";

    @Inject
    private HdfsClient hdfsClient;

    @Inject
    private HdfsDataSourceWriter hdfsDataSourceWriter;

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testUnregisterPartition()
    {
        createPartitionedTable(FIRST_TABLE);

        assertThat(getTableCount(FIRST_TABLE)).isEqualTo(3L);
        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("a", "b", "c");

        dropPartition(FIRST_TABLE, "col", "a");

        assertThat(getTableCount(FIRST_TABLE)).isEqualTo(2L);
        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("b", "c");

        // should not drop data
        assertThat(hdfsClient.exist(HDFS_DIRECTORY_PATH + FIRST_TABLE + "/col=a/")).isTrue();
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testUnregisterViewTableShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);
        createView(VIEW_TABLE, FIRST_TABLE);

        QueryAssert.assertThat(() -> dropPartition(VIEW_TABLE, "col", "a"))
                .failsWithMessage("Table is a view: default." + VIEW_TABLE);
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testUnregisterMissingTableShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);

        QueryAssert.assertThat(() -> dropPartition("missing_table", "col", "f"))
                .failsWithMessage("Table 'default.missing_table' not found");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testUnregisterUnpartitionedTableShouldFail()
    {
        createUnpartitionedTable(SECOND_TABLE);

        QueryAssert.assertThat(() -> dropPartition(SECOND_TABLE, "col", "a"))
                .failsWithMessage("Table is not partitioned: default." + SECOND_TABLE);
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testUnregisterInvalidPartitionColumnsShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);

        QueryAssert.assertThat(() -> dropPartition(FIRST_TABLE, "not_existing_partition_col", "a"))
                .failsWithMessage("Provided partition column names do not match actual partition column names: [col]");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testUnregisterMissingPartitionShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);

        QueryAssert.assertThat(() -> dropPartition(FIRST_TABLE, "col", "f"))
                .failsWithMessage("Partition 'col=f' does not exist");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterPartitionMissingTableShouldFail()
    {
        QueryAssert.assertThat(() -> addPartition("missing_table", "col", "f", "/"))
                .failsWithMessage("Table 'default.missing_table' not found");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterUnpartitionedTableShouldFail()
    {
        createUnpartitionedTable(SECOND_TABLE);

        QueryAssert.assertThat(() -> addPartition(SECOND_TABLE, "col", "a", "/"))
                .failsWithMessage("Table is not partitioned: default." + SECOND_TABLE);
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterViewTableShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);
        createView(VIEW_TABLE, FIRST_TABLE);

        QueryAssert.assertThat(() -> addPartition(VIEW_TABLE, "col", "a", "/"))
                .failsWithMessage("Table is a view: default." + VIEW_TABLE);
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterPartitionCollisionShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);

        QueryAssert.assertThat(() -> addPartition(FIRST_TABLE, "col", "a", "/"))
                .failsWithMessage("Partition [col=a] is already registered");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterPartitionInvalidPartitionColumnsShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);

        QueryAssert.assertThat(() -> addPartition(FIRST_TABLE, "not_existing_partition_col", "a", "/"))
                .failsWithMessage("Provided partition column names do not match actual partition column names: [col]");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterPartitionInvalidLocationShouldFail()
    {
        createPartitionedTable(FIRST_TABLE);

        QueryAssert.assertThat(() -> addPartition(FIRST_TABLE, "col", "f", "/some/non/existing/path"))
                .failsWithMessage("Partition location does not exist: /some/non/existing/path");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterPartition()
    {
        createPartitionedTable(FIRST_TABLE);
        createPartitionedTable(SECOND_TABLE);

        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("a", "b", "c");

        query(format("INSERT INTO %s (val, col) VALUES (10, 'f')", SECOND_TABLE));
        assertThat(getPartitionValues(SECOND_TABLE)).containsOnly("a", "b", "c", "f");

        // Move partition f from SECOND_TABLE to FIRST_TABLE
        addPartition(FIRST_TABLE, "col", "f", HDFS_DIRECTORY_PATH + SECOND_TABLE + "/col=f");
        dropPartition(SECOND_TABLE, "col", "f");

        assertThat(getPartitionValues(SECOND_TABLE)).containsOnly("a", "b", "c");
        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("a", "b", "c", "f");
    }

    @Test(groups = {HIVE_PARTITIONING, SMOKE})
    public void testRegisterPartitionFromAnyLocation()
    {
        createPartitionedTable(FIRST_TABLE);
        createDanglingLocationWithData(OUTSIDE_TABLES_DIRECTORY_PATH, "dangling");

        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("a", "b", "c");
        addPartition(FIRST_TABLE, "col", "f", OUTSIDE_TABLES_DIRECTORY_PATH);

        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("a", "b", "c", "f");
        assertThat(getValues(FIRST_TABLE)).containsOnly(1, 2, 3, 42);

        dropPartition(FIRST_TABLE, "col", "f");

        assertThat(getPartitionValues(FIRST_TABLE)).containsOnly("a", "b", "c");
        assertThat(getValues(FIRST_TABLE)).containsOnly(1, 2, 3);
    }

    private QueryResult dropPartition(String tableName, String partitionCol, String partition)
    {
        return query(format("CALL system.unregister_partition(\n" +
                        "    schema_name => '%s',\n" +
                        "    table_name => '%s',\n" +
                        "    partition_columns => ARRAY['%s'],\n" +
                        "    partition_values => ARRAY['%s'])",
                "default", tableName, partitionCol, partition));
    }

    private QueryResult addPartition(String tableName, String partitionCol, String partition, String location)
    {
        return query(format("CALL system.register_partition(\n" +
                        "    schema_name => '%s',\n" +
                        "    table_name => '%s',\n" +
                        "    partition_columns => ARRAY['%s'],\n" +
                        "    partition_values => ARRAY['%s'],\n" +
                        "    location => '%s')",
                "default", tableName, partitionCol, partition, location));
    }

    private void createDanglingLocationWithData(String path, String tableName)
    {
        hdfsClient.createDirectory(path);
        HiveDataSource dataSource = createResourceDataSource(tableName, "io/prestosql/tests/hive/data/single_int_column/data.textfile");
        hdfsDataSourceWriter.ensureDataOnHdfs(path, dataSource);
    }

    private static void createPartitionedTable(String tableName)
    {
        query("DROP TABLE IF EXISTS " + tableName);

        query("CREATE TABLE " + tableName + " (val int, col varchar) WITH (format = 'TEXTFILE', partitioned_by = ARRAY['col'])");
        query("INSERT INTO " + tableName + " VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    }

    private static void createView(String viewName, String tableName)
    {
        query("DROP VIEW IF EXISTS " + viewName);
        query(format("CREATE VIEW %s AS SELECT val, col FROM %s", viewName, tableName));
    }

    private static void createUnpartitionedTable(String tableName)
    {
        query("DROP TABLE IF EXISTS " + tableName);

        query("CREATE TABLE " + tableName + " (val int, col varchar) WITH (format = 'TEXTFILE')");
        query("INSERT INTO " + tableName + " VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    }

    private Long getTableCount(String tableName)
    {
        QueryResult countResult = query("SELECT count(*) FROM " + tableName);
        return (Long) countResult.row(0).get(0);
    }

    private Set<String> getPartitionValues(String tableName)
    {
        return query("SELECT col FROM " + tableName).rows().stream().map(row -> row.get(0)).map(String.class::cast).collect(Collectors.toSet());
    }

    private Set<Integer> getValues(String tableName)
    {
        return query("SELECT val FROM " + tableName).column(1).stream()
                .map(Integer.class::cast)
                .collect(toImmutableSet());
    }
}
