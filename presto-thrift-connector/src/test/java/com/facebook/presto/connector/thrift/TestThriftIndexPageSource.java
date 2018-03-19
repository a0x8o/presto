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
package com.facebook.presto.connector.thrift;

import com.facebook.presto.connector.thrift.api.PrestoThriftId;
import com.facebook.presto.connector.thrift.api.PrestoThriftNullableColumnSet;
import com.facebook.presto.connector.thrift.api.PrestoThriftNullableSchemaName;
import com.facebook.presto.connector.thrift.api.PrestoThriftNullableTableMetadata;
import com.facebook.presto.connector.thrift.api.PrestoThriftNullableToken;
import com.facebook.presto.connector.thrift.api.PrestoThriftPageResult;
import com.facebook.presto.connector.thrift.api.PrestoThriftSchemaTableName;
import com.facebook.presto.connector.thrift.api.PrestoThriftService;
import com.facebook.presto.connector.thrift.api.PrestoThriftServiceException;
import com.facebook.presto.connector.thrift.api.PrestoThriftSplit;
import com.facebook.presto.connector.thrift.api.PrestoThriftSplitBatch;
import com.facebook.presto.connector.thrift.api.PrestoThriftTupleDomain;
import com.facebook.presto.connector.thrift.api.datatypes.PrestoThriftInteger;
import com.facebook.presto.connector.thrift.clientproviders.PrestoThriftServiceProvider;
import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.spi.InMemoryRecordSet;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.facebook.presto.connector.thrift.api.PrestoThriftBlock.integerData;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Collections.shuffle;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestThriftIndexPageSource
{
    private static final long MAX_BYTES_PER_RESPONSE = 16_000_000;

    @Test
    public void testGetNextPageTwoConcurrentRequests()
            throws Exception
    {
        final int splits = 3;
        final int lookupRequestsConcurrency = 2;
        final int rowsPerSplit = 1;
        List<SettableFuture<PrestoThriftPageResult>> futures = IntStream.range(0, splits)
                .mapToObj(i -> SettableFuture.<PrestoThriftPageResult>create())
                .collect(toImmutableList());
        List<CountDownLatch> signals = IntStream.range(0, splits)
                .mapToObj(i -> new CountDownLatch(1))
                .collect(toImmutableList());
        TestingThriftService client = new TestingThriftService(rowsPerSplit, false, false)
        {
            @Override
            public ListenableFuture<PrestoThriftPageResult> getRows(PrestoThriftId splitId, List<String> columns, long maxBytes, PrestoThriftNullableToken nextToken)
                    throws PrestoThriftServiceException
            {
                int key = Ints.fromByteArray(splitId.getId());
                signals.get(key).countDown();
                return futures.get(key);
            }
        };
        TestingServiceProvider serviceProvider = new TestingServiceProvider(client);
        ThriftConnectorStats stats = new ThriftConnectorStats();
        long pageSizeReceived = 0;
        ThriftIndexPageSource pageSource = new ThriftIndexPageSource(
                serviceProvider,
                stats,
                new ThriftIndexHandle(new SchemaTableName("default", "table1"), TupleDomain.all()),
                ImmutableList.of(column("a", INTEGER)),
                ImmutableList.of(column("b", INTEGER)),
                new InMemoryRecordSet(ImmutableList.of(INTEGER), generateKeys(0, splits)),
                MAX_BYTES_PER_RESPONSE,
                lookupRequestsConcurrency);

        assertNull(pageSource.getNextPage());
        assertEquals((long) stats.getIndexPageSize().getAllTime().getTotal(), 0);
        signals.get(0).await(1, SECONDS);
        signals.get(1).await(1, SECONDS);
        signals.get(2).await(1, SECONDS);
        assertEquals(signals.get(0).getCount(), 0, "first request wasn't sent");
        assertEquals(signals.get(1).getCount(), 0, "second request wasn't sent");
        assertEquals(signals.get(2).getCount(), 1, "third request shouldn't be sent");

        // at this point first two requests were sent
        assertFalse(pageSource.isFinished());
        assertNull(pageSource.getNextPage());
        assertEquals((long) stats.getIndexPageSize().getAllTime().getTotal(), 0);
        // splits client is closed
        assertEquals(client.timesClosed(), 1);

        // completing the second request
        futures.get(1).set(pageResult(20, null));
        Page page = pageSource.getNextPage();
        pageSizeReceived += page.getSizeInBytes();
        assertEquals((long) stats.getIndexPageSize().getAllTime().getTotal(), pageSizeReceived);
        assertNotNull(page);
        assertEquals(page.getPositionCount(), 1);
        assertEquals(page.getBlock(0).getInt(0, 0), 20);
        // not complete yet
        assertFalse(pageSource.isFinished());
        assertEquals(client.timesClosed(), 2);

        // once one of the requests completes the next one should be sent
        signals.get(2).await(1, SECONDS);
        assertEquals(signals.get(2).getCount(), 0, "third request wasn't sent");

        // completing the first request
        futures.get(0).set(pageResult(10, null));
        page = pageSource.getNextPage();
        assertNotNull(page);
        pageSizeReceived += page.getSizeInBytes();
        assertEquals((long) stats.getIndexPageSize().getAllTime().getTotal(), pageSizeReceived);
        assertEquals(page.getPositionCount(), 1);
        assertEquals(page.getBlock(0).getInt(0, 0), 10);
        // still not complete
        assertFalse(pageSource.isFinished());
        assertEquals(client.timesClosed(), 3);

        // completing the third request
        futures.get(2).set(pageResult(30, null));
        page = pageSource.getNextPage();
        assertNotNull(page);
        pageSizeReceived += page.getSizeInBytes();
        assertEquals((long) stats.getIndexPageSize().getAllTime().getTotal(), pageSizeReceived);
        assertEquals(page.getPositionCount(), 1);
        assertEquals(page.getBlock(0).getInt(0, 0), 30);
        // finished now
        assertTrue(pageSource.isFinished());
        assertEquals(client.timesClosed(), 4);

        // after completion
        assertNull(pageSource.getNextPage());
        pageSource.close();
        // no additional close requests expected
        assertEquals(client.timesClosed(), 4);
    }

    @Test
    public void testGetNextPageMultipleSplitRequest()
            throws Exception
    {
        runGeneralTest(5, 2, 2, true);
    }

    @Test
    public void testGetNextPageNoSplits()
            throws Exception
    {
        runGeneralTest(0, 2, 2, false);
    }

    @Test
    public void testGetNextPageOneConcurrentRequest()
            throws Exception
    {
        runGeneralTest(3, 1, 3, false);
    }

    @Test
    public void testGetNextPageMoreConcurrencyThanRequestsNoContinuation()
            throws Exception
    {
        runGeneralTest(2, 4, 1, false);
    }

    private static void runGeneralTest(int splits, int lookupRequestsConcurrency, int rowsPerSplit, boolean twoSplitBatches)
            throws Exception
    {
        TestingThriftService client = new TestingThriftService(rowsPerSplit, true, twoSplitBatches);
        TestingServiceProvider serviceProvider = new TestingServiceProvider(client);
        ThriftIndexPageSource pageSource = new ThriftIndexPageSource(
                serviceProvider,
                new ThriftConnectorStats(),
                new ThriftIndexHandle(new SchemaTableName("default", "table1"), TupleDomain.all()),
                ImmutableList.of(column("a", INTEGER)),
                ImmutableList.of(column("b", INTEGER)),
                new InMemoryRecordSet(ImmutableList.of(INTEGER), generateKeys(1, splits + 1)),
                MAX_BYTES_PER_RESPONSE,
                lookupRequestsConcurrency);

        List<Integer> actual = new ArrayList<>();
        while (!pageSource.isFinished()) {
            CompletableFuture<?> blocked = pageSource.isBlocked();
            blocked.get(1, SECONDS);
            Page page = pageSource.getNextPage();
            if (page != null) {
                Block block = page.getBlock(0);
                for (int position = 0; position < block.getPositionCount(); position++) {
                    actual.add(block.getInt(position, 0));
                }
            }
        }

        Collections.sort(actual);
        List<Integer> expected = new ArrayList<>(splits * rowsPerSplit);
        for (int split = 1; split <= splits; split++) {
            for (int row = 0; row < rowsPerSplit; row++) {
                expected.add(split * 10 + row);
            }
        }
        assertEquals(actual, expected);

        // must be null after finish
        assertNull(pageSource.getNextPage());

        // 1 client for getting splits and then 1 client per split to get data
        assertEquals(client.timesClosed(), splits + 1);

        pageSource.close();
        // all closes must have happened as part of normal operation, so no change
        assertEquals(client.timesClosed(), splits + 1);
    }

    private static class TestingThriftService
            implements PrestoThriftService
    {
        private final int rowsPerSplit;
        private final boolean shuffleSplits;
        private final boolean twoSplitBatches;
        private final AtomicInteger closed = new AtomicInteger();

        public TestingThriftService(int rowsPerSplit, boolean shuffleSplits, boolean twoSplitBatches)
        {
            this.rowsPerSplit = rowsPerSplit;
            this.shuffleSplits = shuffleSplits;
            this.twoSplitBatches = twoSplitBatches;
        }

        @Override
        public ListenableFuture<PrestoThriftSplitBatch> getIndexSplits(PrestoThriftSchemaTableName schemaTableName, List<String> indexColumnNames, List<String> outputColumnNames, PrestoThriftPageResult keys, PrestoThriftTupleDomain outputConstraint, int maxSplitCount, PrestoThriftNullableToken nextToken)
                throws PrestoThriftServiceException
        {
            if (keys.getRowCount() == 0) {
                return immediateFuture(new PrestoThriftSplitBatch(ImmutableList.of(), null));
            }
            PrestoThriftId newNextToken = null;
            int[] values = keys.getColumnBlocks().get(0).getIntegerData().getInts();
            int begin;
            int end;
            if (twoSplitBatches) {
                if (nextToken.getToken() == null) {
                    begin = 0;
                    end = values.length / 2;
                    newNextToken = new PrestoThriftId(Ints.toByteArray(1));
                }
                else {
                    begin = values.length / 2;
                    end = values.length;
                }
            }
            else {
                begin = 0;
                end = values.length;
            }

            List<PrestoThriftSplit> splits = new ArrayList<>(end - begin);
            for (int i = begin; i < end; i++) {
                splits.add(new PrestoThriftSplit(new PrestoThriftId(Ints.toByteArray(values[i])), ImmutableList.of()));
            }
            if (shuffleSplits) {
                shuffle(splits);
            }
            return immediateFuture(new PrestoThriftSplitBatch(splits, newNextToken));
        }

        @Override
        public ListenableFuture<PrestoThriftPageResult> getRows(PrestoThriftId splitId, List<String> columns, long maxBytes, PrestoThriftNullableToken nextToken)
                throws PrestoThriftServiceException
        {
            if (rowsPerSplit == 0) {
                return immediateFuture(new PrestoThriftPageResult(ImmutableList.of(), 0, null));
            }
            int key = Ints.fromByteArray(splitId.getId());
            int offset = nextToken.getToken() != null ? Ints.fromByteArray(nextToken.getToken().getId()) : 0;
            PrestoThriftId newNextToken = offset + 1 < rowsPerSplit ? new PrestoThriftId(Ints.toByteArray(offset + 1)) : null;
            return immediateFuture(pageResult(key * 10 + offset, newNextToken));
        }

        @Override
        public void close()
        {
            closed.incrementAndGet();
        }

        public int timesClosed()
        {
            return closed.get();
        }

        // methods below are not used for the test

        @Override
        public List<String> listSchemaNames()
                throws PrestoThriftServiceException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PrestoThriftSchemaTableName> listTables(PrestoThriftNullableSchemaName schemaNameOrNull)
                throws PrestoThriftServiceException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrestoThriftNullableTableMetadata getTableMetadata(PrestoThriftSchemaTableName schemaTableName)
                throws PrestoThriftServiceException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public ListenableFuture<PrestoThriftSplitBatch> getSplits(PrestoThriftSchemaTableName schemaTableName, PrestoThriftNullableColumnSet desiredColumns, PrestoThriftTupleDomain outputConstraint, int maxSplitCount, PrestoThriftNullableToken nextToken)
                throws PrestoThriftServiceException
        {
            throw new UnsupportedOperationException();
        }
    }

    private static class TestingServiceProvider
            implements PrestoThriftServiceProvider
    {
        private final PrestoThriftService client;

        public TestingServiceProvider(PrestoThriftService client)
        {
            this.client = requireNonNull(client, "client is null");
        }

        @Override
        public PrestoThriftService anyHostClient()
        {
            return client;
        }

        @Override
        public PrestoThriftService selectedHostClient(List<HostAddress> hosts)
        {
            return client;
        }
    }

    private static ThriftColumnHandle column(String name, Type type)
    {
        return new ThriftColumnHandle(name, type, null, false);
    }

    private static List<List<Integer>> generateKeys(int beginInclusive, int endExclusive)
    {
        return IntStream.range(beginInclusive, endExclusive)
                .mapToObj(ImmutableList::of)
                .collect(toImmutableList());
    }

    private static PrestoThriftPageResult pageResult(int value, PrestoThriftId nextToken)
    {
        return new PrestoThriftPageResult(ImmutableList.of(integerData(new PrestoThriftInteger(null, new int[] {value}))), 1, nextToken);
    }
}
