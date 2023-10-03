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
package io.trino.plugin.hive.line;

import com.google.common.collect.Maps;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import io.airlift.units.DataSize.Unit;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.TrinoInputFile;
import io.trino.filesystem.memory.MemoryInputFile;
import io.trino.hive.formats.line.Column;
import io.trino.hive.formats.line.LineDeserializer;
import io.trino.hive.formats.line.LineDeserializerFactory;
import io.trino.hive.formats.line.LineReader;
import io.trino.hive.formats.line.LineReaderFactory;
import io.trino.plugin.hive.AcidInfo;
import io.trino.plugin.hive.HiveColumnHandle;
import io.trino.plugin.hive.HivePageSourceFactory;
import io.trino.plugin.hive.ReaderColumns;
import io.trino.plugin.hive.ReaderPageSource;
import io.trino.plugin.hive.acid.AcidTransaction;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.EmptyPageSource;
import io.trino.spi.predicate.TupleDomain;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.hive.formats.line.LineDeserializer.EMPTY_LINE_DESERIALIZER;
import static io.trino.hive.thrift.metastore.hive_metastoreConstants.FILE_INPUT_FORMAT;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static io.trino.plugin.hive.HivePageSourceProvider.projectBaseColumns;
import static io.trino.plugin.hive.ReaderPageSource.noProjectionAdaptation;
import static io.trino.plugin.hive.util.HiveUtil.getDeserializerClassName;
import static io.trino.plugin.hive.util.HiveUtil.getFooterCount;
import static io.trino.plugin.hive.util.HiveUtil.getHeaderCount;
import static io.trino.plugin.hive.util.HiveUtil.splitError;
import static java.util.Objects.requireNonNull;

public abstract class LinePageSourceFactory
        implements HivePageSourceFactory
{
    private static final DataSize SMALL_FILE_SIZE = DataSize.of(8, Unit.MEGABYTE);

    private final TrinoFileSystemFactory fileSystemFactory;
    private final LineDeserializerFactory lineDeserializerFactory;
    private final LineReaderFactory lineReaderFactory;

    protected LinePageSourceFactory(
            TrinoFileSystemFactory fileSystemFactory,
            LineDeserializerFactory lineDeserializerFactory,
            LineReaderFactory lineReaderFactory)
    {
        this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        this.lineDeserializerFactory = requireNonNull(lineDeserializerFactory, "lineDeserializerFactory is null");
        this.lineReaderFactory = requireNonNull(lineReaderFactory, "lineReaderFactory is null");
    }

    @Override
    public Optional<ReaderPageSource> createPageSource(
            ConnectorSession session,
            Location path,
            long start,
            long length,
            long estimatedFileSize,
            Properties schema,
            List<HiveColumnHandle> columns,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            Optional<AcidInfo> acidInfo,
            OptionalInt bucketNumber,
            boolean originalFile,
            AcidTransaction transaction)
    {
        if (!lineReaderFactory.getHiveOutputFormatClassName().equals(schema.getProperty(FILE_INPUT_FORMAT)) ||
                !lineDeserializerFactory.getHiveSerDeClassNames().contains(getDeserializerClassName(schema))) {
            return Optional.empty();
        }

        checkArgument(acidInfo.isEmpty(), "Acid is not supported");

        // get header and footer count
        int headerCount = getHeaderCount(schema);
        if (headerCount > 1) {
            checkArgument(start == 0, "Multiple header rows are not supported for a split file");
        }
        int footerCount = getFooterCount(schema);
        if (footerCount > 0) {
            checkArgument(start == 0, "Footer not supported for a split file");
        }

        // setup projected columns
        List<HiveColumnHandle> projectedReaderColumns = columns;
        Optional<ReaderColumns> readerProjections = projectBaseColumns(columns);
        if (readerProjections.isPresent()) {
            projectedReaderColumns = readerProjections.get().get().stream()
                    .map(HiveColumnHandle.class::cast)
                    .collect(toImmutableList());
        }

        // create deserializer
        LineDeserializer lineDeserializer = EMPTY_LINE_DESERIALIZER;
        if (!columns.isEmpty()) {
            lineDeserializer = lineDeserializerFactory.create(
                    projectedReaderColumns.stream()
                            .map(column -> new Column(column.getName(), column.getType(), column.getBaseHiveColumnIndex()))
                            .collect(toImmutableList()),
                    Maps.fromProperties(schema));
        }

        // Skip empty inputs
        if (length == 0) {
            return Optional.of(noProjectionAdaptation(new EmptyPageSource()));
        }

        TrinoFileSystem trinoFileSystem = fileSystemFactory.create(session.getIdentity());
        TrinoInputFile inputFile = trinoFileSystem.newInputFile(path);
        try {
            // buffer file if small
            if (estimatedFileSize < SMALL_FILE_SIZE.toBytes()) {
                try (InputStream inputStream = inputFile.newStream()) {
                    byte[] data = inputStream.readAllBytes();
                    inputFile = new MemoryInputFile(path, Slices.wrappedBuffer(data));
                }
            }
            LineReader lineReader = lineReaderFactory.createLineReader(inputFile, start, length, headerCount, footerCount);
            // Split may be empty after discovering the real file size and skipping headers
            if (lineReader.isClosed()) {
                return Optional.of(noProjectionAdaptation(new EmptyPageSource()));
            }
            LinePageSource pageSource = new LinePageSource(lineReader, lineDeserializer, lineReaderFactory.createLineBuffer(), path);
            return Optional.of(new ReaderPageSource(pageSource, readerProjections));
        }
        catch (TrinoException e) {
            throw e;
        }
        catch (Exception e) {
            throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, splitError(e, path, start, length), e);
        }
    }
}
