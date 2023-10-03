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
package io.trino.filesystem.hdfs;

import com.google.common.collect.ImmutableList;
import io.trino.filesystem.FileEntry;
import io.trino.filesystem.FileEntry.Block;
import io.trino.filesystem.FileIterator;
import io.trino.filesystem.Location;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

class HdfsFileIterator
        implements FileIterator
{
    private final Location listingLocation;
    private final Path listingPath;
    private final RemoteIterator<LocatedFileStatus> iterator;

    public HdfsFileIterator(Location listingLocation, Path listingPath, RemoteIterator<LocatedFileStatus> iterator)
    {
        this.listingLocation = requireNonNull(listingLocation, "listingPath is null");
        this.listingPath = requireNonNull(listingPath, "listingPath is null");
        this.iterator = requireNonNull(iterator, "iterator is null");
    }

    @Override
    public boolean hasNext()
            throws IOException
    {
        // TODO: remove this workaround for https://issues.apache.org/jira/browse/HADOOP-18662
        int attempts = 0;
        while (true) {
            try {
                return iterator.hasNext();
            }
            catch (FileNotFoundException | RuntimeException e) {
                if ((e instanceof RuntimeException) && !nullToEmpty(e.getMessage()).contains(": No such file or directory\n")) {
                    throw new IOException(e);
                }
                attempts++;
                if (attempts > 1000) {
                    throw e;
                }
            }
        }
    }

    @Override
    public FileEntry next()
            throws IOException
    {
        LocatedFileStatus status = iterator.next();

        verify(status.isFile(), "iterator returned a non-file: %s", status);

        if (status.getPath().equals(listingPath)) {
            throw new IOException("Listing location is a file, not a directory: " + listingLocation);
        }

        List<Block> blocks = Stream.of(status.getBlockLocations())
                .map(HdfsFileIterator::toTrinoBlock)
                .collect(toImmutableList());

        return new FileEntry(
                listedLocation(listingLocation, listingPath, status.getPath()),
                status.getLen(),
                Instant.ofEpochMilli(status.getModificationTime()),
                blocks.isEmpty() ? Optional.empty() : Optional.of(blocks));
    }

    static Location listedLocation(Location listingLocation, Path listingPath, Path listedPath)
    {
        String root = listingPath.toUri().getPath();
        String path = listedPath.toUri().getPath();

        verify(path.startsWith(root), "iterator path [%s] not a child of listing path [%s] for location [%s]", path, root, listingLocation);

        int index = root.endsWith("/") ? root.length() : root.length() + 1;
        return listingLocation.appendPath(path.substring(index));
    }

    private static Block toTrinoBlock(BlockLocation location)
    {
        try {
            return new Block(ImmutableList.copyOf(location.getHosts()), location.getOffset(), location.getLength());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
