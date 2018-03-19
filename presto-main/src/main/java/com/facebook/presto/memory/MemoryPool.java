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
package com.facebook.presto.memory;

import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.memory.MemoryPoolId;
import com.facebook.presto.spi.memory.MemoryPoolInfo;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import org.weakref.jmx.Managed;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.facebook.presto.operator.Operator.NOT_BLOCKED;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class MemoryPool
{
    private final MemoryPoolId id;
    private final long maxBytes;

    @GuardedBy("this")
    private long reservedBytes;
    @GuardedBy("this")
    private long reservedRevocableBytes;

    @Nullable
    @GuardedBy("this")
    private NonCancellableMemoryFuture<?> future;

    @GuardedBy("this")
    // TODO: It would be better if we just tracked QueryContexts, but their lifecycle is managed by a weak reference, so we can't do that
    private final Map<QueryId, Long> queryMemoryReservations = new HashMap<>();
    @GuardedBy("this")
    private final Map<QueryId, Long> queryMemoryRevocableReservations = new HashMap<>();

    private final List<MemoryPoolListener> listeners = new CopyOnWriteArrayList<>();

    public MemoryPool(MemoryPoolId id, DataSize size)
    {
        this.id = requireNonNull(id, "name is null");
        requireNonNull(size, "size is null");
        maxBytes = size.toBytes();
    }

    public MemoryPoolId getId()
    {
        return id;
    }

    public synchronized MemoryPoolInfo getInfo()
    {
        return new MemoryPoolInfo(maxBytes, reservedBytes, reservedRevocableBytes, queryMemoryReservations, queryMemoryRevocableReservations);
    }

    public void addListener(MemoryPoolListener listener)
    {
        listeners.add(requireNonNull(listener, "listener cannot be null"));
    }

    public void removeListener(MemoryPoolListener listener)
    {
        listeners.remove(requireNonNull(listener, "listener cannot be null"));
    }

    /**
     * Reserves the given number of bytes. Caller should wait on the returned future, before allocating more memory.
     */
    public ListenableFuture<?> reserve(QueryId queryId, long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");

        ListenableFuture<?> result;
        synchronized (this) {
            if (bytes != 0) {
                queryMemoryReservations.merge(queryId, bytes, Long::sum);
            }
            reservedBytes += bytes;
            if (getFreeBytes() <= 0) {
                if (future == null) {
                    future = NonCancellableMemoryFuture.create();
                }
                checkState(!future.isDone(), "future is already completed");
                result = future;
            }
            else {
                result = NOT_BLOCKED;
            }
        }

        onMemoryReserved();
        return result;
    }

    private void onMemoryReserved()
    {
        listeners.forEach(listener -> listener.onMemoryReserved(this));
    }

    public ListenableFuture<?> reserveRevocable(QueryId queryId, long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");

        ListenableFuture<?> result;
        synchronized (this) {
            if (bytes != 0) {
                queryMemoryRevocableReservations.merge(queryId, bytes, Long::sum);
            }
            reservedRevocableBytes += bytes;
            if (getFreeBytes() <= 0) {
                if (future == null) {
                    future = NonCancellableMemoryFuture.create();
                }
                checkState(!future.isDone(), "future is already completed");
                result = future;
            }
            else {
                result = NOT_BLOCKED;
            }
        }

        onMemoryReserved();
        return result;
    }

    /**
     * Try to reserve the given number of bytes. Return value indicates whether the caller may use the requested memory.
     */
    public boolean tryReserve(QueryId queryId, long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        synchronized (this) {
            if (getFreeBytes() - bytes < 0) {
                return false;
            }
            reservedBytes += bytes;
            if (bytes != 0) {
                queryMemoryReservations.merge(queryId, bytes, Long::sum);
            }
        }

        onMemoryReserved();
        return true;
    }

    public synchronized void free(QueryId queryId, long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        checkArgument(reservedBytes >= bytes, "tried to free more memory than is reserved");
        if (bytes == 0) {
            // Freeing zero bytes is a no-op
            return;
        }

        Long queryReservation = queryMemoryReservations.get(queryId);
        requireNonNull(queryReservation, "queryReservation is null");
        checkArgument(queryReservation - bytes >= 0, "tried to free more memory than is reserved by query");
        queryReservation -= bytes;
        if (queryReservation == 0) {
            queryMemoryReservations.remove(queryId);
        }
        else {
            queryMemoryReservations.put(queryId, queryReservation);
        }
        reservedBytes -= bytes;
        if (getFreeBytes() > 0 && future != null) {
            future.set(null);
            future = null;
        }
    }

    public synchronized void freeRevocable(QueryId queryId, long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        checkArgument(reservedRevocableBytes >= bytes, "tried to free more revocable memory than is reserved");
        if (bytes == 0) {
            // Freeing zero bytes is a no-op
            return;
        }

        Long queryReservation = queryMemoryRevocableReservations.get(queryId);
        requireNonNull(queryReservation, "queryReservation is null");
        checkArgument(queryReservation - bytes >= 0, "tried to free more revocable memory than is reserved by query");
        queryReservation -= bytes;
        if (queryReservation == 0) {
            queryMemoryRevocableReservations.remove(queryId);
        }
        else {
            queryMemoryRevocableReservations.put(queryId, queryReservation);
        }
        reservedRevocableBytes -= bytes;
        if (getFreeBytes() > 0 && future != null) {
            future.set(null);
            future = null;
        }
    }

    /**
     * Returns the number of free bytes. This value may be negative, which indicates that the pool is over-committed.
     */
    @Managed
    public synchronized long getFreeBytes()
    {
        return maxBytes - reservedBytes - reservedRevocableBytes;
    }

    @Managed
    public synchronized long getMaxBytes()
    {
        return maxBytes;
    }

    @Managed
    public synchronized long getReservedBytes()
    {
        return reservedBytes;
    }

    @Managed
    public synchronized long getReservedRevocableBytes()
    {
        return reservedRevocableBytes;
    }

    synchronized long getQueryUserMemoryReservation(QueryId queryId)
    {
        return queryMemoryReservations.getOrDefault(queryId, 0L);
    }

    synchronized long getQueryRevocableMemoryReservation(QueryId queryId)
    {
        return queryMemoryRevocableReservations.getOrDefault(queryId, 0L);
    }

    @Override
    public synchronized String toString()
    {
        return toStringHelper(this)
                .add("id", id)
                .add("maxBytes", maxBytes)
                .add("freeBytes", getFreeBytes())
                .add("reservedBytes", reservedBytes)
                .add("reservedRevocableBytes", reservedRevocableBytes)
                .add("future", future)
                .toString();
    }

    private static class NonCancellableMemoryFuture<V>
            extends AbstractFuture<V>
    {
        public static <V> NonCancellableMemoryFuture<V> create()
        {
            return new NonCancellableMemoryFuture<V>();
        }

        @Override
        public boolean set(@Nullable V value)
        {
            return super.set(value);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            throw new UnsupportedOperationException("cancellation is not supported");
        }
    }
}
