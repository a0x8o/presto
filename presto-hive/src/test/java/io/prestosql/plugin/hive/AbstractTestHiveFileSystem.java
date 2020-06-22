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
import com.google.common.collect.ImmutableMultimap;
import com.google.common.net.HostAndPort;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import io.airlift.stats.CounterStat;
import io.prestosql.GroupByHashPageIndexerFactory;
import io.prestosql.plugin.base.CatalogName;
import io.prestosql.plugin.hive.AbstractTestHive.HiveTransaction;
import io.prestosql.plugin.hive.AbstractTestHive.Transaction;
import io.prestosql.plugin.hive.HdfsEnvironment.HdfsContext;
import io.prestosql.plugin.hive.authentication.HiveAuthenticationConfig;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.authentication.NoHdfsAuthentication;
import io.prestosql.plugin.hive.metastore.Database;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.PrincipalPrivileges;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.plugin.hive.metastore.cache.CachingHiveMetastore;
import io.prestosql.plugin.hive.metastore.thrift.BridgingHiveMetastore;
import io.prestosql.plugin.hive.metastore.thrift.MetastoreLocator;
import io.prestosql.plugin.hive.metastore.thrift.TestingMetastoreLocator;
import io.prestosql.plugin.hive.metastore.thrift.ThriftHiveMetastore;
import io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreConfig;
import io.prestosql.plugin.hive.security.SqlStandardAccessControlMetadata;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.connector.ConnectorPageSinkProvider;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorPageSourceProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.security.ConnectorIdentity;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.gen.JoinCompiler;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.testing.TestingNodeManager;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.azurebfs.AzureBlobFileSystem;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.hive.AbstractTestHive.createTableProperties;
import static io.prestosql.plugin.hive.AbstractTestHive.filterNonHiddenColumnHandles;
import static io.prestosql.plugin.hive.AbstractTestHive.filterNonHiddenColumnMetadata;
import static io.prestosql.plugin.hive.AbstractTestHive.getAllSplits;
import static io.prestosql.plugin.hive.HiveTestUtils.PAGE_SORTER;
import static io.prestosql.plugin.hive.HiveTestUtils.TYPE_MANAGER;
import static io.prestosql.plugin.hive.HiveTestUtils.getDefaultHiveFileWriterFactories;
import static io.prestosql.plugin.hive.HiveTestUtils.getDefaultHivePageSourceFactories;
import static io.prestosql.plugin.hive.HiveTestUtils.getDefaultHiveRecordCursorProviders;
import static io.prestosql.plugin.hive.HiveTestUtils.getHiveSession;
import static io.prestosql.plugin.hive.HiveTestUtils.getHiveSessionProperties;
import static io.prestosql.plugin.hive.HiveTestUtils.getTypes;
import static io.prestosql.spi.connector.ConnectorSplitManager.SplitSchedulingStrategy.UNGROUPED_SCHEDULING;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.testing.MaterializedResult.materializeSourceDataStream;
import static io.prestosql.testing.QueryAssertions.assertEqualsIgnoreOrder;
import static java.util.Locale.ENGLISH;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public abstract class AbstractTestHiveFileSystem
{
    protected static final HdfsContext TESTING_CONTEXT = new HdfsContext(ConnectorIdentity.ofUser("test"));

    protected String database;
    protected SchemaTableName table;
    protected SchemaTableName tableWithHeader;
    protected SchemaTableName tableWithHeaderAndFooter;
    protected SchemaTableName temporaryCreateTable;

    protected HdfsEnvironment hdfsEnvironment;
    protected LocationService locationService;
    protected TestingHiveMetastore metastoreClient;
    protected HiveMetadataFactory metadataFactory;
    protected HiveTransactionManager transactionManager;
    protected ConnectorSplitManager splitManager;
    protected ConnectorPageSinkProvider pageSinkProvider;
    protected ConnectorPageSourceProvider pageSourceProvider;

    private ExecutorService executor;
    private HiveConfig config;
    private ScheduledExecutorService heartbeatService;

    @BeforeClass
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("hive-%s"));
        heartbeatService = newScheduledThreadPool(1);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (heartbeatService != null) {
            heartbeatService.shutdownNow();
            heartbeatService = null;
        }
    }

    protected abstract Path getBasePath();

    protected void onSetupComplete() {}

    protected void setup(String host, int port, String databaseName, boolean s3SelectPushdownEnabled, HdfsConfiguration hdfsConfiguration)
    {
        database = databaseName;
        table = new SchemaTableName(database, "presto_test_external_fs");
        tableWithHeader = new SchemaTableName(database, "presto_test_external_fs_with_header");
        tableWithHeaderAndFooter = new SchemaTableName(database, "presto_test_external_fs_with_header_and_footer");

        String random = randomUUID().toString().toLowerCase(ENGLISH).replace("-", "");
        temporaryCreateTable = new SchemaTableName(database, "tmp_presto_test_create_" + random);

        config = new HiveConfig().setS3SelectPushdownEnabled(s3SelectPushdownEnabled);

        Optional<HostAndPort> proxy = Optional.ofNullable(System.getProperty("hive.metastore.thrift.client.socks-proxy"))
                .map(HostAndPort::fromString);

        MetastoreLocator metastoreLocator = new TestingMetastoreLocator(proxy, HostAndPort.fromParts(host, port));

        ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("hive-%s"));
        HivePartitionManager hivePartitionManager = new HivePartitionManager(config);

        hdfsEnvironment = new HdfsEnvironment(hdfsConfiguration, new HdfsConfig(), new NoHdfsAuthentication());
        metastoreClient = new TestingHiveMetastore(
                new BridgingHiveMetastore(new ThriftHiveMetastore(metastoreLocator, new HiveConfig(), new ThriftMetastoreConfig(), new HiveAuthenticationConfig(), hdfsEnvironment)),
                executor,
                getBasePath(),
                hdfsEnvironment);
        locationService = new HiveLocationService(hdfsEnvironment);
        JsonCodec<PartitionUpdate> partitionUpdateCodec = JsonCodec.jsonCodec(PartitionUpdate.class);
        metadataFactory = new HiveMetadataFactory(
                new CatalogName("hive"),
                config,
                metastoreClient,
                hdfsEnvironment,
                hivePartitionManager,
                newDirectExecutorService(),
                heartbeatService,
                TYPE_MANAGER,
                locationService,
                partitionUpdateCodec,
                new HiveTypeTranslator(),
                new NodeVersion("test_version"),
                (metastore) -> new SqlStandardAccessControlMetadata(metastore));
        transactionManager = new HiveTransactionManager();
        splitManager = new HiveSplitManager(
                transactionHandle -> ((HiveMetadata) transactionManager.get(transactionHandle)).getMetastore(),
                hivePartitionManager,
                new NamenodeStats(),
                hdfsEnvironment,
                new CachingDirectoryLister(new HiveConfig()),
                new BoundedExecutor(executor, config.getMaxSplitIteratorThreads()),
                new HiveCoercionPolicy(TYPE_MANAGER),
                new CounterStat(),
                config.getMaxOutstandingSplits(),
                config.getMaxOutstandingSplitsSize(),
                config.getMinPartitionBatchSize(),
                config.getMaxPartitionBatchSize(),
                config.getMaxInitialSplits(),
                config.getSplitLoaderConcurrency(),
                config.getMaxSplitsPerSecond(),
                config.getRecursiveDirWalkerEnabled());
        pageSinkProvider = new HivePageSinkProvider(
                getDefaultHiveFileWriterFactories(config, hdfsEnvironment),
                hdfsEnvironment,
                PAGE_SORTER,
                metastoreClient,
                new GroupByHashPageIndexerFactory(new JoinCompiler(createTestMetadataManager())),
                TYPE_MANAGER,
                config,
                locationService,
                partitionUpdateCodec,
                new TestingNodeManager("fake-environment"),
                new HiveEventClient(),
                getHiveSessionProperties(config),
                new HiveWriterStats());
        pageSourceProvider = new HivePageSourceProvider(
                TYPE_MANAGER,
                config,
                hdfsEnvironment,
                getDefaultHivePageSourceFactories(hdfsEnvironment),
                getDefaultHiveRecordCursorProviders(config, hdfsEnvironment),
                new GenericHiveRecordCursorProvider(hdfsEnvironment, config));

        onSetupComplete();
    }

    protected ConnectorSession newSession()
    {
        return getHiveSession(config);
    }

    protected Transaction newTransaction()
    {
        return new HiveTransaction(transactionManager, (HiveMetadata) metadataFactory.create());
    }

    @Test
    public void testGetRecords()
            throws Exception
    {
        assertEqualsIgnoreOrder(
                readTable(table),
                MaterializedResult.resultBuilder(newSession(), BIGINT)
                        .row(3L).row(14L).row(15L) // test_table.csv
                        .row(92L).row(65L).row(35L) // test_table.csv.gz
                        .row(89L).row(79L).row(32L) // test_table.csv.bz2
                        .row(38L).row(46L).row(26L) // test_table.csv.lz4
                        .build());
    }

    @Test
    public void testGetRecordsWithHeader()
            throws IOException
    {
        assertEqualsIgnoreOrder(
                readTable(tableWithHeader),
                MaterializedResult.resultBuilder(newSession(), BIGINT)
                        .row(2L).row(71L).row(82L) // test_table_with_header.csv
                        .row(81L).row(82L).row(84L) // test_table_with_header.csv.gz
                        .row(59L).row(4L).row(52L) // test_table_with_header.csv.bz2
                        .row(35L).row(36L).row(2L) // test_table_with_header.csv.lz4
                        .build());
    }

    @Test
    public void testGetRecordsWithHeaderAndFooter()
            throws IOException
    {
        assertEqualsIgnoreOrder(
                readTable(tableWithHeaderAndFooter),
                MaterializedResult.resultBuilder(newSession(), BIGINT)
                        .row(1L).row(41L).row(42L) // test_table_with_header_and_footer.csv
                        .row(13L).row(56L).row(23L) // test_table_with_header_and_footer.csv.gz
                        .row(73L).row(9L).row(50L) // test_table_with_header_and_footer.csv.bz2
                        .row(48L).row(80L).row(16L) // test_table_with_header_and_footer.csv.lz4
                        .build());
    }

    @Test
    public void testGetFileStatus()
            throws Exception
    {
        Path basePath = getBasePath();
        Path tablePath = new Path(basePath, "presto_test_external_fs");
        Path filePath = new Path(tablePath, "test_table.csv");
        FileSystem fs = hdfsEnvironment.getFileSystem(TESTING_CONTEXT, basePath);

        assertTrue(fs.getFileStatus(basePath).isDirectory(), "basePath should be considered a directory");
        assertTrue(fs.getFileStatus(tablePath).isDirectory(), "tablePath should be considered a directory");
        assertTrue(fs.getFileStatus(filePath).isFile(), "filePath should be considered a file");
        assertFalse(fs.getFileStatus(filePath).isDirectory(), "filePath should not be considered a directory");
        assertFalse(fs.exists(new Path(basePath, "foo-" + randomUUID())), "foo-random path should be found not to exist");
        assertFalse(fs.exists(new Path(basePath, "foo")), "foo path should be found not to exist");
    }

    @Test
    public void testRename()
            throws Exception
    {
        Path basePath = new Path(getBasePath(), randomUUID().toString());
        FileSystem fs = hdfsEnvironment.getFileSystem(TESTING_CONTEXT, basePath);
        assertFalse(fs.exists(basePath));

        // create file foo.txt
        Path path = new Path(basePath, "foo.txt");
        assertTrue(fs.createNewFile(path));
        assertTrue(fs.exists(path));

        // rename foo.txt to bar.txt when bar does not exist
        Path newPath = new Path(basePath, "bar.txt");
        assertFalse(fs.exists(newPath));
        assertTrue(fs.rename(path, newPath));
        assertFalse(fs.exists(path));
        assertTrue(fs.exists(newPath));

        // rename foo.txt to foo.txt when foo.txt does not exist
        assertFalse(fs.rename(path, path));

        // create file foo.txt and rename to existing bar.txt
        assertTrue(fs.createNewFile(path));
        assertFalse(fs.rename(path, newPath));

        // rename foo.txt to foo.txt when foo.txt exists
        assertEquals(fs.rename(path, path), fs instanceof AzureBlobFileSystem);

        // delete foo.txt
        assertTrue(fs.delete(path, false));
        assertFalse(fs.exists(path));

        // create directory source with file
        Path source = new Path(basePath, "source");
        assertTrue(fs.createNewFile(new Path(source, "test.txt")));

        // rename source to non-existing target
        Path target = new Path(basePath, "target");
        assertFalse(fs.exists(target));
        assertTrue(fs.rename(source, target));
        assertFalse(fs.exists(source));
        assertTrue(fs.exists(target));

        // create directory source with file
        assertTrue(fs.createNewFile(new Path(source, "test.txt")));

        // rename source to existing target
        assertTrue(fs.rename(source, target));
        assertFalse(fs.exists(source));
        target = new Path(target, "source");
        assertTrue(fs.exists(target));
        assertTrue(fs.exists(new Path(target, "test.txt")));

        // delete target
        target = new Path(basePath, "target");
        assertTrue(fs.exists(target));
        assertTrue(fs.delete(target, true));
        assertFalse(fs.exists(target));

        // cleanup
        fs.delete(basePath, true);
    }

    @Test
    public void testTableCreation()
            throws Exception
    {
        for (HiveStorageFormat storageFormat : HiveStorageFormat.values()) {
            if (storageFormat == HiveStorageFormat.CSV) {
                // CSV supports only unbounded VARCHAR type
                continue;
            }
            createTable(temporaryCreateTable, storageFormat);
            dropTable(temporaryCreateTable);
        }
    }

    private void createTable(SchemaTableName tableName, HiveStorageFormat storageFormat)
            throws Exception
    {
        List<ColumnMetadata> columns = ImmutableList.<ColumnMetadata>builder()
                .add(new ColumnMetadata("id", BIGINT))
                .build();

        MaterializedResult data = MaterializedResult.resultBuilder(newSession(), BIGINT)
                .row(1L)
                .row(3L)
                .row(2L)
                .build();

        try (Transaction transaction = newTransaction()) {
            ConnectorMetadata metadata = transaction.getMetadata();
            ConnectorSession session = newSession();

            // begin creating the table
            ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(tableName, columns, createTableProperties(storageFormat));
            ConnectorOutputTableHandle outputHandle = metadata.beginCreateTable(session, tableMetadata, Optional.empty());

            // write the records
            ConnectorPageSink sink = pageSinkProvider.createPageSink(transaction.getTransactionHandle(), session, outputHandle);
            sink.appendPage(data.toPage());
            Collection<Slice> fragments = getFutureValue(sink.finish());

            // commit the table
            metadata.finishCreateTable(session, outputHandle, fragments, ImmutableList.of());

            transaction.commit();

            // Hack to work around the metastore not being configured for S3 or other FS.
            // The metastore tries to validate the location when creating the
            // table, which fails without explicit configuration for file system.
            // We work around that by using a dummy location when creating the
            // table and update it here to the correct location.
            metastoreClient.updateTableLocation(
                    database,
                    tableName.getTableName(),
                    locationService.getTableWriteInfo(((HiveOutputTableHandle) outputHandle).getLocationHandle(), false).getTargetPath().toString());
        }

        try (Transaction transaction = newTransaction()) {
            ConnectorMetadata metadata = transaction.getMetadata();
            ConnectorSession session = newSession();

            // load the new table
            ConnectorTableHandle tableHandle = getTableHandle(metadata, tableName);
            List<ColumnHandle> columnHandles = filterNonHiddenColumnHandles(metadata.getColumnHandles(session, tableHandle).values());

            // verify the metadata
            ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(session, getTableHandle(metadata, tableName));
            assertEquals(filterNonHiddenColumnMetadata(tableMetadata.getColumns()), columns);

            // verify the data
            metadata.beginQuery(session);
            ConnectorSplitSource splitSource = splitManager.getSplits(transaction.getTransactionHandle(), session, tableHandle, UNGROUPED_SCHEDULING);
            ConnectorSplit split = getOnlyElement(getAllSplits(splitSource));

            try (ConnectorPageSource pageSource = pageSourceProvider.createPageSource(transaction.getTransactionHandle(), session, split, tableHandle, columnHandles, TupleDomain.all())) {
                MaterializedResult result = materializeSourceDataStream(session, pageSource, getTypes(columnHandles));
                assertEqualsIgnoreOrder(result.getMaterializedRows(), data.getMaterializedRows());
            }

            metadata.cleanupQuery(session);
        }
    }

    private void dropTable(SchemaTableName table)
    {
        try (Transaction transaction = newTransaction()) {
            transaction.getMetastore().dropTable(newSession(), table.getSchemaName(), table.getTableName());
            transaction.commit();
        }
    }

    protected MaterializedResult readTable(SchemaTableName tableName)
            throws IOException
    {
        try (Transaction transaction = newTransaction()) {
            ConnectorMetadata metadata = transaction.getMetadata();
            ConnectorSession session = newSession();

            ConnectorTableHandle table = getTableHandle(metadata, tableName);
            List<ColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(session, table).values());

            metadata.beginQuery(session);
            ConnectorSplitSource splitSource = splitManager.getSplits(transaction.getTransactionHandle(), session, table, UNGROUPED_SCHEDULING);

            List<Type> allTypes = getTypes(columnHandles);
            List<Type> dataTypes = getTypes(columnHandles.stream()
                    .filter(columnHandle -> !((HiveColumnHandle) columnHandle).isHidden())
                    .collect(toImmutableList()));
            MaterializedResult.Builder result = MaterializedResult.resultBuilder(session, dataTypes);

            List<ConnectorSplit> splits = getAllSplits(splitSource);
            for (ConnectorSplit split : splits) {
                try (ConnectorPageSource pageSource = pageSourceProvider.createPageSource(transaction.getTransactionHandle(), session, split, table, columnHandles, TupleDomain.all())) {
                    MaterializedResult pageSourceResult = materializeSourceDataStream(session, pageSource, allTypes);
                    for (MaterializedRow row : pageSourceResult.getMaterializedRows()) {
                        Object[] dataValues = IntStream.range(0, row.getFieldCount())
                                .filter(channel -> !((HiveColumnHandle) columnHandles.get(channel)).isHidden())
                                .mapToObj(row::getField)
                                .toArray();
                        result.row(dataValues);
                    }
                }
            }

            metadata.cleanupQuery(session);
            return result.build();
        }
    }

    private ConnectorTableHandle getTableHandle(ConnectorMetadata metadata, SchemaTableName tableName)
    {
        ConnectorTableHandle handle = metadata.getTableHandle(newSession(), tableName);
        checkArgument(handle != null, "table not found: %s", tableName);
        return handle;
    }

    protected static class TestingHiveMetastore
            extends CachingHiveMetastore
    {
        private final Path basePath;
        private final HdfsEnvironment hdfsEnvironment;

        public TestingHiveMetastore(HiveMetastore delegate, Executor executor, Path basePath, HdfsEnvironment hdfsEnvironment)
        {
            super(delegate, executor, OptionalLong.empty(), OptionalLong.empty(), 0);
            this.basePath = basePath;
            this.hdfsEnvironment = hdfsEnvironment;
        }

        @Override
        public Optional<Database> getDatabase(String databaseName)
        {
            return super.getDatabase(databaseName)
                    .map(database -> Database.builder(database)
                            .setLocation(Optional.of(basePath.toString()))
                            .build());
        }

        @Override
        public void createTable(HiveIdentity identity, Table table, PrincipalPrivileges privileges)
        {
            // hack to work around the metastore not being configured for S3 or other FS
            Table.Builder tableBuilder = Table.builder(table);
            tableBuilder.getStorageBuilder().setLocation("/");
            super.createTable(identity, tableBuilder.build(), privileges);
        }

        @Override
        public void dropTable(HiveIdentity identity, String databaseName, String tableName, boolean deleteData)
        {
            try {
                Optional<Table> table = getTable(identity, databaseName, tableName);
                if (!table.isPresent()) {
                    throw new TableNotFoundException(new SchemaTableName(databaseName, tableName));
                }

                // hack to work around the metastore not being configured for S3 or other FS
                List<String> locations = listAllDataPaths(identity, databaseName, tableName);

                Table.Builder tableBuilder = Table.builder(table.get());
                tableBuilder.getStorageBuilder().setLocation("/");

                // drop table
                replaceTable(identity, databaseName, tableName, tableBuilder.build(), new PrincipalPrivileges(ImmutableMultimap.of(), ImmutableMultimap.of()));
                delegate.dropTable(identity, databaseName, tableName, false);

                // drop data
                if (deleteData) {
                    for (String location : locations) {
                        Path path = new Path(location);
                        hdfsEnvironment.getFileSystem(TESTING_CONTEXT, path).delete(path, true);
                    }
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            finally {
                invalidateTable(databaseName, tableName);
            }
        }

        public void updateTableLocation(String databaseName, String tableName, String location)
        {
            HiveIdentity identity = new HiveIdentity(TESTING_CONTEXT.getIdentity());
            Optional<Table> table = getTable(identity, databaseName, tableName);
            if (!table.isPresent()) {
                throw new TableNotFoundException(new SchemaTableName(databaseName, tableName));
            }

            Table.Builder tableBuilder = Table.builder(table.get());
            tableBuilder.getStorageBuilder().setLocation(location);

            // NOTE: this clears the permissions
            replaceTable(identity, databaseName, tableName, tableBuilder.build(), new PrincipalPrivileges(ImmutableMultimap.of(), ImmutableMultimap.of()));
        }

        private List<String> listAllDataPaths(HiveIdentity identity, String schemaName, String tableName)
        {
            ImmutableList.Builder<String> locations = ImmutableList.builder();
            Table table = getTable(identity, schemaName, tableName).get();
            if (table.getStorage().getLocation() != null) {
                // For partitioned table, there should be nothing directly under this directory.
                // But including this location in the set makes the directory content assert more
                // extensive, which is desirable.
                locations.add(table.getStorage().getLocation());
            }

            Optional<List<String>> partitionNames = getPartitionNames(identity, schemaName, tableName);
            if (partitionNames.isPresent()) {
                getPartitionsByNames(identity, table, partitionNames.get()).values().stream()
                        .map(Optional::get)
                        .map(partition -> partition.getStorage().getLocation())
                        .filter(location -> !location.startsWith(table.getStorage().getLocation()))
                        .forEach(locations::add);
            }

            return locations.build();
        }
    }
}
