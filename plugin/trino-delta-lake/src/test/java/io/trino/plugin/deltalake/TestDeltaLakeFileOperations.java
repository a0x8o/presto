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
package io.trino.plugin.deltalake;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import io.trino.Session;
import io.trino.filesystem.TrackingFileSystemFactory;
import io.trino.filesystem.TrackingFileSystemFactory.OperationType;
import io.trino.filesystem.hdfs.HdfsFileSystemFactory;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.inject.util.Modules.EMPTY_MODULE;
import static io.trino.filesystem.TrackingFileSystemFactory.OperationType.INPUT_FILE_EXISTS;
import static io.trino.filesystem.TrackingFileSystemFactory.OperationType.INPUT_FILE_GET_LENGTH;
import static io.trino.filesystem.TrackingFileSystemFactory.OperationType.INPUT_FILE_NEW_STREAM;
import static io.trino.filesystem.TrackingFileSystemFactory.OperationType.OUTPUT_FILE_CREATE;
import static io.trino.filesystem.TrackingFileSystemFactory.OperationType.OUTPUT_FILE_CREATE_OR_OVERWRITE;
import static io.trino.plugin.base.util.Closables.closeAllSuppress;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.CDF_DATA;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.CHECKPOINT;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.DATA;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.LAST_CHECKPOINT;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.STARBURST_EXTENDED_STATS_JSON;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.TRANSACTION_LOG_JSON;
import static io.trino.plugin.deltalake.TestDeltaLakeFileOperations.FileType.TRINO_EXTENDED_STATS_JSON;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_FILE_SYSTEM_STATS;
import static io.trino.testing.MultisetAssertions.assertMultisetsEqual;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.Math.toIntExact;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

// single-threaded AccessTrackingFileSystemFactory is shared mutable state
@Test(singleThreaded = true)
public class TestDeltaLakeFileOperations
        extends AbstractTestQueryFramework
{
    private static final int MAX_PREFIXES_COUNT = 10;

    private TrackingFileSystemFactory trackingFileSystemFactory;

    @Override
    protected DistributedQueryRunner createQueryRunner()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("delta_lake")
                .setSchema("default")
                .build();
        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session)
                .addCoordinatorProperty("optimizer.experimental-max-prefetched-information-schema-prefixes", Integer.toString(MAX_PREFIXES_COUNT))
                .build();
        try {
            String metastoreDirectory = queryRunner.getCoordinator().getBaseDataDir().resolve("delta_lake_metastore").toFile().getAbsoluteFile().toURI().toString();
            trackingFileSystemFactory = new TrackingFileSystemFactory(new HdfsFileSystemFactory(HDFS_ENVIRONMENT, HDFS_FILE_SYSTEM_STATS));

            queryRunner.installPlugin(new TestingDeltaLakePlugin(Optional.empty(), Optional.of(trackingFileSystemFactory), EMPTY_MODULE));
            queryRunner.createCatalog(
                    "delta_lake",
                    "delta_lake",
                    Map.of(
                            "hive.metastore", "file",
                            "hive.metastore.catalog.dir", metastoreDirectory,
                            "delta.enable-non-concurrent-writes", "true"));

            queryRunner.execute("CREATE SCHEMA " + session.getSchema().orElseThrow());
            return queryRunner;
        }
        catch (Throwable e) {
            closeAllSuppress(e, queryRunner);
            throw e;
        }
    }

    @Test
    public void testCreateTableAsSelect()
    {
        assertFileSystemAccesses(
                "CREATE TABLE test_create_as_select AS SELECT 1 col_name",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(STARBURST_EXTENDED_STATS_JSON, "extendeded_stats.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(STARBURST_EXTENDED_STATS_JSON, "extendeded_stats.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", OUTPUT_FILE_CREATE_OR_OVERWRITE))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", OUTPUT_FILE_CREATE))
                        .add(new FileOperation(DATA, "no partition", OUTPUT_FILE_CREATE))
                        .build());
        assertUpdate("DROP TABLE test_create_as_select");

        assertFileSystemAccesses(
                "CREATE TABLE test_create_partitioned_as_select WITH (partitioned_by=ARRAY['key']) AS SELECT * FROM (VALUES (1, 'a'), (2, 'b')) t(key, col)",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(STARBURST_EXTENDED_STATS_JSON, "extendeded_stats.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(STARBURST_EXTENDED_STATS_JSON, "extendeded_stats.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", OUTPUT_FILE_CREATE_OR_OVERWRITE))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", OUTPUT_FILE_CREATE))
                        .add(new FileOperation(DATA, "key=1/", OUTPUT_FILE_CREATE))
                        .add(new FileOperation(DATA, "key=2/", OUTPUT_FILE_CREATE))
                        .build());
        assertUpdate("DROP TABLE test_create_partitioned_as_select");
    }

    @Test
    public void testReadUnpartitionedTable()
    {
        assertUpdate("DROP TABLE IF EXISTS test_read_unpartitioned");
        assertUpdate("CREATE TABLE test_read_unpartitioned(key varchar, data varchar)");

        // Create multiple files
        assertUpdate("INSERT INTO test_read_unpartitioned(key, data) VALUES ('p1', '1-abc'), ('p1', '1-def'), ('p2', '2-abc'), ('p2', '2-def')", 4);
        assertUpdate("INSERT INTO test_read_unpartitioned(key, data) VALUES ('p1', '1-baz'), ('p2', '2-baz')", 2);

        // Read all columns
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_unpartitioned')");
        assertFileSystemAccesses(
                "TABLE test_read_unpartitioned",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "no partition", INPUT_FILE_NEW_STREAM), 2)
                        .build());

        // Read with aggregation (this may involve fetching stats so may incur more file system accesses)
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_unpartitioned')");
        assertFileSystemAccesses(
                "SELECT key, max(data) FROM test_read_unpartitioned GROUP BY key",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "no partition", INPUT_FILE_NEW_STREAM), 2)
                        .build());

        assertUpdate("DROP TABLE test_read_unpartitioned");
    }

    @Test
    public void testReadTableCheckpointInterval()
    {
        assertUpdate("DROP TABLE IF EXISTS test_read_checkpoint");

        assertUpdate("CREATE TABLE test_read_checkpoint(key varchar, data varchar) WITH (checkpoint_interval = 2)");
        assertUpdate("INSERT INTO test_read_checkpoint(key, data) VALUES ('p1', '1-abc'), ('p1', '1-def'), ('p2', '2-abc'), ('p2', '2-def')", 4);
        assertUpdate("INSERT INTO test_read_checkpoint(key, data) VALUES ('p1', '1-baz'), ('p2', '2-baz')", 2);

        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_checkpoint')");
        assertFileSystemAccesses(
                "TABLE test_read_checkpoint",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(CHECKPOINT, "00000000000000000002.checkpoint.parquet", INPUT_FILE_GET_LENGTH), 6) // TODO (https://github.com/trinodb/trino/issues/18916) should be checked once per query
                        .addCopies(new FileOperation(CHECKPOINT, "00000000000000000002.checkpoint.parquet", INPUT_FILE_NEW_STREAM), 3) // TODO (https://github.com/trinodb/trino/issues/18916) should be checked once per query
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "no partition", INPUT_FILE_NEW_STREAM), 2)
                        .build());

        assertUpdate("DROP TABLE test_read_checkpoint");
    }

    @Test
    public void testReadWholePartition()
    {
        assertUpdate("DROP TABLE IF EXISTS test_read_part_key");
        assertUpdate("CREATE TABLE test_read_part_key(key varchar, data varchar) WITH (partitioned_by=ARRAY['key'])");

        // Create multiple files per partition
        assertUpdate("INSERT INTO test_read_part_key(key, data) VALUES ('p1', '1-abc'), ('p1', '1-def'), ('p2', '2-abc'), ('p2', '2-def')", 4);
        assertUpdate("INSERT INTO test_read_part_key(key, data) VALUES ('p1', '1-baz'), ('p2', '2-baz')", 2);

        // Read partition and data columns
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_part_key')");
        assertFileSystemAccesses(
                "TABLE test_read_part_key",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "key=p1/", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(DATA, "key=p2/", INPUT_FILE_NEW_STREAM), 2)
                        .build());

        // Read with aggregation (this may involve fetching stats so may incur more file system accesses)
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_part_key')");
        assertFileSystemAccesses(
                "SELECT key, max(data) FROM test_read_part_key GROUP BY key",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "key=p1/", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(DATA, "key=p2/", INPUT_FILE_NEW_STREAM), 2)
                        .build());

        // Read partition column only
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_part_key')");
        assertFileSystemAccesses(
                "SELECT key, count(*) FROM test_read_part_key GROUP BY key",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .build());

        // Read partition column only, one partition only
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_part_key')");
        assertFileSystemAccesses(
                "SELECT count(*) FROM test_read_part_key WHERE key = 'p1'",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .build());

        // Read partition and synthetic columns
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_read_part_key')");
        assertFileSystemAccesses(
                "SELECT count(*), array_agg(\"$path\"), max(\"$file_modified_time\") FROM test_read_part_key GROUP BY key",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .build());

        assertUpdate("DROP TABLE test_read_part_key");
    }

    @Test
    public void testSelfJoin()
    {
        assertUpdate("CREATE TABLE test_self_join_table AS SELECT 2 as age, 0 parent, 3 AS id", 1);

        assertFileSystemAccesses(
                "SELECT child.age, parent.age FROM test_self_join_table child JOIN test_self_join_table parent ON child.parent = parent.id",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRINO_EXTENDED_STATS_JSON, "extended_stats.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "no partition", INPUT_FILE_NEW_STREAM), 2)
                        .build());

        assertUpdate("DROP TABLE test_self_join_table");
    }

    @Test
    public void testDeleteWholePartition()
    {
        assertUpdate("DROP TABLE IF EXISTS test_delete_part_key");
        assertUpdate("CREATE TABLE test_delete_part_key(key varchar, data varchar) WITH (partitioned_by=ARRAY['key'])");

        // Create multiple files per partition
        assertUpdate("INSERT INTO test_delete_part_key(key, data) VALUES ('p1', '1-abc'), ('p1', '1-def'), ('p2', '2-abc'), ('p2', '2-def')", 4);
        assertUpdate("INSERT INTO test_delete_part_key(key, data) VALUES ('p1', '1-baz'), ('p2', '2-baz')", 2);

        // Delete partition column only
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_delete_part_key')");
        assertFileSystemAccesses(
                "DELETE FROM test_delete_part_key WHERE key = 'p1'",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), 2) // TODO (https://github.com/trinodb/trino/issues/16782) should be checked once per query
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .build());

        assertUpdate("DROP TABLE test_delete_part_key");
    }

    @Test
    public void testDeleteWholeTable()
    {
        assertUpdate("DROP TABLE IF EXISTS test_delete_whole_table");
        assertUpdate("CREATE TABLE test_delete_whole_table(key varchar, data varchar)");

        // Create multiple files per partition
        assertUpdate("INSERT INTO test_delete_whole_table(key, data) VALUES ('p1', '1-abc'), ('p1', '1-def'), ('p2', '2-abc'), ('p2', '2-def')", 4);
        assertUpdate("INSERT INTO test_delete_whole_table(key, data) VALUES ('p1', '1-baz'), ('p2', '2-baz')", 2);

        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_delete_whole_table')");
        assertFileSystemAccesses(
                "DELETE FROM test_delete_whole_table WHERE true",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), 2) // TODO (https://github.com/trinodb/trino/issues/16782) should be checked once per query
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_EXISTS))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .build());

        assertUpdate("DROP TABLE test_delete_whole_table");
    }

    @Test
    public void testDeleteWithNonPartitionFilter()
    {
        assertUpdate("CREATE TABLE test_delete_with_non_partition_filter (page_url VARCHAR, key VARCHAR, views INTEGER) WITH (partitioned_by=ARRAY['key'])");
        assertUpdate("INSERT INTO test_delete_with_non_partition_filter VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("INSERT INTO test_delete_with_non_partition_filter VALUES('url2', 'domain2', 2)", 1);
        assertUpdate("INSERT INTO test_delete_with_non_partition_filter VALUES('url3', 'domain3', 3)", 1);

        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'test_delete_with_non_partition_filter')");
        assertFileSystemAccesses(
                "DELETE FROM test_delete_with_non_partition_filter WHERE page_url ='url1'",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), 3) // TODO (https://github.com/trinodb/trino/issues/16782) should be checked once per query
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_EXISTS), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_EXISTS), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_EXISTS), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_EXISTS), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(DATA, "key=domain1/", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(DATA, "key=domain1/", INPUT_FILE_GET_LENGTH), 2)
                        .add(new FileOperation(DATA, "key=domain1/", OUTPUT_FILE_CREATE))
                        .build());

        assertUpdate("DROP TABLE test_delete_with_non_partition_filter");
    }

    @Test
    public void testHistorySystemTable()
    {
        assertUpdate("CREATE TABLE test_history_system_table (a INT, b INT)");
        assertUpdate("INSERT INTO test_history_system_table VALUES (1, 2)", 1);
        assertUpdate("INSERT INTO test_history_system_table VALUES (2, 3)", 1);
        assertUpdate("INSERT INTO test_history_system_table VALUES (3, 4)", 1);
        assertUpdate("INSERT INTO test_history_system_table VALUES (4, 5)", 1);

        assertFileSystemAccesses("SELECT * FROM \"test_history_system_table$history\"",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .build());

        assertFileSystemAccesses("SELECT * FROM \"test_history_system_table$history\" WHERE version = 3",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .build());

        assertFileSystemAccesses("SELECT * FROM \"test_history_system_table$history\" WHERE version > 3",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .build());

        assertFileSystemAccesses("SELECT * FROM \"test_history_system_table$history\" WHERE version >= 3 OR version = 1",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .build());

        assertFileSystemAccesses("SELECT * FROM \"test_history_system_table$history\" WHERE version >= 1 AND version < 3",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .build());

        assertFileSystemAccesses("SELECT * FROM \"test_history_system_table$history\" WHERE version > 1 AND version < 2",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .build());
    }

    @Test
    public void testTableChangesFileSystemAccess()
    {
        assertUpdate("CREATE TABLE table_changes_file_system_access (page_url VARCHAR, key VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true, partitioned_by=ARRAY['key'])");
        assertUpdate("INSERT INTO table_changes_file_system_access VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("INSERT INTO table_changes_file_system_access VALUES('url2', 'domain2', 2)", 1);
        assertUpdate("INSERT INTO table_changes_file_system_access VALUES('url3', 'domain3', 3)", 1);
        assertUpdate("UPDATE table_changes_file_system_access SET page_url = 'url22' WHERE key = 'domain2'", 1);
        assertUpdate("UPDATE table_changes_file_system_access SET page_url = 'url33' WHERE views = 3", 1);
        assertUpdate("DELETE FROM table_changes_file_system_access WHERE page_url = 'url1'", 1);

        // The difference comes from the fact that during UPDATE queries there is no guarantee that rows that are going to be deleted and
        // rows that are going to be inserted come on the same worker to io.trino.plugin.deltalake.DeltaLakeMergeSink.storeMergedRows
        int cdfFilesForDomain2 = countCdfFilesForKey("domain2");
        int cdfFilesForDomain3 = countCdfFilesForKey("domain3");
        assertUpdate("CALL system.flush_metadata_cache(schema_name => CURRENT_SCHEMA, table_name => 'table_changes_file_system_access')");
        assertFileSystemAccesses("SELECT * FROM TABLE(system.table_changes('default', 'table_changes_file_system_access'))",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000004.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000005.json", INPUT_FILE_NEW_STREAM), 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000006.json", INPUT_FILE_NEW_STREAM), 2)
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000007.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(CDF_DATA, "key=domain1/", INPUT_FILE_NEW_STREAM))
                        .addCopies(new FileOperation(CDF_DATA, "key=domain2/", INPUT_FILE_NEW_STREAM), cdfFilesForDomain2)
                        .addCopies(new FileOperation(CDF_DATA, "key=domain3/", INPUT_FILE_NEW_STREAM), cdfFilesForDomain3)
                        .add(new FileOperation(DATA, "key=domain1/", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(DATA, "key=domain2/", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(DATA, "key=domain3/", INPUT_FILE_NEW_STREAM))
                        .build());
    }

    @Test(dataProvider = "metadataQueriesTestTableCountDataProvider")
    public void testInformationSchemaColumns(int tables)
    {
        String schemaName = "test_i_s_columns_schema" + randomNameSuffix();
        assertUpdate("CREATE SCHEMA " + schemaName);
        Session session = Session.builder(getSession())
                .setSchema(schemaName)
                .build();

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "CREATE TABLE test_select_i_s_columns" + i + "(id varchar, age integer)");
            // Produce multiple snapshots and metadata files
            assertUpdate(session, "INSERT INTO test_select_i_s_columns" + i + " VALUES ('abc', 11)", 1);
            assertUpdate(session, "INSERT INTO test_select_i_s_columns" + i + " VALUES ('xyz', 12)", 1);

            assertUpdate(session, "CREATE TABLE test_other_select_i_s_columns" + i + "(id varchar, age integer)"); // won't match the filter
        }

        // Bulk retrieval
        assertFileSystemAccesses(session, "SELECT * FROM information_schema.columns WHERE table_schema = CURRENT_SCHEMA AND table_name LIKE 'test_select_i_s_columns%'",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), tables)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), tables)
                        .build());

        // Pointed lookup
        assertFileSystemAccesses(session, "SELECT * FROM information_schema.columns WHERE table_schema = CURRENT_SCHEMA AND table_name = 'test_select_i_s_columns0'",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .build());

        // Pointed lookup with LIKE predicate (as if unintentional)
        assertFileSystemAccesses(session, "SELECT * FROM information_schema.columns WHERE table_schema = CURRENT_SCHEMA AND table_name LIKE 'test_select_i_s_columns0'",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), tables)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), tables)
                        .build());

        // Pointed lookup via DESCRIBE (which does some additional things before delegating to information_schema.columns)
        assertFileSystemAccesses(session, "DESCRIBE test_select_i_s_columns0",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .build());

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "DROP TABLE test_select_i_s_columns" + i);
            assertUpdate(session, "DROP TABLE test_other_select_i_s_columns" + i);
        }
    }

    @Test(dataProvider = "metadataQueriesTestTableCountDataProvider")
    public void testSystemMetadataTableComments(int tables)
    {
        String schemaName = "test_s_m_table_comments" + randomNameSuffix();
        assertUpdate("CREATE SCHEMA " + schemaName);
        Session session = Session.builder(getSession())
                .setSchema(schemaName)
                .build();

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "CREATE TABLE test_select_s_m_t_comments" + i + "(id varchar, age integer)");
            // Produce multiple snapshots and metadata files
            assertUpdate(session, "INSERT INTO test_select_s_m_t_comments" + i + " VALUES ('abc', 11)", 1);
            assertUpdate(session, "INSERT INTO test_select_s_m_t_comments" + i + " VALUES ('xyz', 12)", 1);

            assertUpdate(session, "CREATE TABLE test_other_select_s_m_t_comments" + i + "(id varchar, age integer)"); // won't match the filter
        }

        // Bulk retrieval
        assertFileSystemAccesses(session, "SELECT * FROM system.metadata.table_comments WHERE schema_name = CURRENT_SCHEMA AND table_name LIKE 'test_select_s_m_t_comments%'",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), tables)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), tables)
                        .build());

        // Pointed lookup
        assertFileSystemAccesses(session, "SELECT * FROM system.metadata.table_comments WHERE schema_name = CURRENT_SCHEMA AND table_name = 'test_select_s_m_t_comments0'",
                ImmutableMultiset.<FileOperation>builder()
                        .add(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM))
                        .add(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM))
                        .build());

        // Pointed lookup with LIKE predicate (as if unintentional)
        assertFileSystemAccesses(session, "SELECT * FROM system.metadata.table_comments WHERE schema_name = CURRENT_SCHEMA AND table_name LIKE 'test_select_s_m_t_comments0'",
                ImmutableMultiset.<FileOperation>builder()
                        .addCopies(new FileOperation(LAST_CHECKPOINT, "_last_checkpoint", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000000.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000001.json", INPUT_FILE_NEW_STREAM), tables * 2)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000002.json", INPUT_FILE_NEW_STREAM), tables)
                        .addCopies(new FileOperation(TRANSACTION_LOG_JSON, "00000000000000000003.json", INPUT_FILE_NEW_STREAM), tables)
                        .build());

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "DROP TABLE test_select_s_m_t_comments" + i);
            assertUpdate(session, "DROP TABLE test_other_select_s_m_t_comments" + i);
        }
    }

    @DataProvider
    public Object[][] metadataQueriesTestTableCountDataProvider()
    {
        return new Object[][] {
                {3},
                {MAX_PREFIXES_COUNT},
                {MAX_PREFIXES_COUNT + 3},
        };
    }

    private int countCdfFilesForKey(String partitionValue)
    {
        String path = (String) computeScalar("SELECT \"$path\" FROM table_changes_file_system_access WHERE key = '" + partitionValue + "'");
        String partitionKey = "key=" + partitionValue;
        String tableLocation = path.substring(0, path.lastIndexOf(partitionKey));
        String partitionCdfFolder = URI.create(tableLocation).getPath() + "_change_data/" + partitionKey + "/";
        return toIntExact(Arrays.stream(new File(partitionCdfFolder).list()).filter(file -> !file.contains(".crc")).count());
    }

    private void assertFileSystemAccesses(@Language("SQL") String query, Multiset<FileOperation> expectedAccesses)
    {
        assertFileSystemAccesses(getSession(), query, expectedAccesses);
    }

    private void assertFileSystemAccesses(Session session, @Language("SQL") String query, Multiset<FileOperation> expectedAccesses)
    {
        assertUpdate("CALL system.flush_metadata_cache()");

        trackingFileSystemFactory.reset();
        getDistributedQueryRunner().executeWithQueryId(session, query);
        assertMultisetsEqual(getOperations(), expectedAccesses);
    }

    private Multiset<FileOperation> getOperations()
    {
        return trackingFileSystemFactory.getOperationCounts()
                .entrySet().stream()
                .filter(entry -> {
                    String path = entry.getKey().location().path();
                    return !path.endsWith(".trinoSchema") && !path.contains(".trinoPermissions");
                })
                .flatMap(entry -> nCopies(entry.getValue(), FileOperation.create(
                        entry.getKey().location().path(),
                        entry.getKey().operationType())).stream())
                .collect(toCollection(HashMultiset::create));
    }

    private record FileOperation(FileType fileType, String fileId, OperationType operationType)
    {
        public static FileOperation create(String path, OperationType operationType)
        {
            Pattern dataFilePattern = Pattern.compile(".*?/(?<partition>key=[^/]*/)?(?<queryId>\\d{8}_\\d{6}_\\d{5}_\\w{5})_(?<uuid>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
            String fileName = path.replaceFirst(".*/", "");
            if (path.matches(".*/_delta_log/_last_checkpoint")) {
                return new FileOperation(LAST_CHECKPOINT, fileName, operationType);
            }
            if (path.matches(".*/_delta_log/\\d+\\.checkpoint\\.parquet")) {
                return new FileOperation(CHECKPOINT, fileName, operationType);
            }
            if (path.matches(".*/_delta_log/\\d+\\.json")) {
                return new FileOperation(TRANSACTION_LOG_JSON, fileName, operationType);
            }
            if (path.matches(".*/_delta_log/_trino_meta/extended_stats.json")) {
                return new FileOperation(TRINO_EXTENDED_STATS_JSON, fileName, operationType);
            }
            if (path.matches(".*/_delta_log/_starburst_meta/extendeded_stats.json")) {
                return new FileOperation(STARBURST_EXTENDED_STATS_JSON, fileName, operationType);
            }
            if (path.matches(".*/_change_data/.*")) {
                Matcher matcher = dataFilePattern.matcher(path);
                if (matcher.matches()) {
                    return new FileOperation(CDF_DATA, matcher.group("partition"), operationType);
                }
            }
            if (!path.contains("_delta_log")) {
                Matcher matcher = dataFilePattern.matcher(path);
                if (matcher.matches()) {
                    return new FileOperation(DATA, firstNonNull(matcher.group("partition"), "no partition"), operationType);
                }
            }
            throw new IllegalArgumentException("File not recognized: " + path);
        }

        public FileOperation
        {
            requireNonNull(fileType, "fileType is null");
            requireNonNull(fileId, "fileId is null");
            requireNonNull(operationType, "operationType is null");
        }
    }

    enum FileType
    {
        LAST_CHECKPOINT,
        CHECKPOINT,
        TRANSACTION_LOG_JSON,
        TRINO_EXTENDED_STATS_JSON,
        STARBURST_EXTENDED_STATS_JSON,
        DATA,
        CDF_DATA,
        /**/;
    }
}
