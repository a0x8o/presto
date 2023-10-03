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
package io.trino.plugin.hive.rcfile;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import io.airlift.units.DataSize.Unit;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.TrinoInputFile;
import io.trino.filesystem.memory.MemoryInputFile;
import io.trino.hive.formats.FileCorruptionException;
import io.trino.hive.formats.encodings.ColumnEncodingFactory;
import io.trino.hive.formats.encodings.binary.BinaryColumnEncodingFactory;
import io.trino.hive.formats.encodings.text.TextColumnEncodingFactory;
import io.trino.hive.formats.encodings.text.TextEncodingOptions;
import io.trino.hive.formats.rcfile.RcFileReader;
import io.trino.plugin.hive.AcidInfo;
import io.trino.plugin.hive.HiveColumnHandle;
import io.trino.plugin.hive.HiveConfig;
import io.trino.plugin.hive.HivePageSourceFactory;
import io.trino.plugin.hive.ReaderColumns;
import io.trino.plugin.hive.ReaderPageSource;
import io.trino.plugin.hive.acid.AcidTransaction;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.EmptyPageSource;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.Type;
import org.joda.time.DateTimeZone;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static io.trino.plugin.hive.HivePageSourceProvider.projectBaseColumns;
import static io.trino.plugin.hive.ReaderPageSource.noProjectionAdaptation;
import static io.trino.plugin.hive.util.HiveClassNames.COLUMNAR_SERDE_CLASS;
import static io.trino.plugin.hive.util.HiveClassNames.LAZY_BINARY_COLUMNAR_SERDE_CLASS;
import static io.trino.plugin.hive.util.HiveUtil.getDeserializerClassName;
import static io.trino.plugin.hive.util.HiveUtil.splitError;
import static io.trino.plugin.hive.util.SerdeConstants.SERIALIZATION_LIB;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

public class RcFilePageSourceFactory
        implements HivePageSourceFactory
{
    private static final DataSize BUFFER_SIZE = DataSize.of(8, Unit.MEGABYTE);

    private final TrinoFileSystemFactory fileSystemFactory;
    private final DateTimeZone timeZone;

    @Inject
    public RcFilePageSourceFactory(TrinoFileSystemFactory fileSystemFactory, HiveConfig hiveConfig)
    {
        this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        this.timeZone = hiveConfig.getRcfileDateTimeZone();
    }

    public static Properties stripUnnecessaryProperties(Properties schema)
    {
        if (LAZY_BINARY_COLUMNAR_SERDE_CLASS.equals(getDeserializerClassName(schema))) {
            Properties stripped = new Properties();
            stripped.put(SERIALIZATION_LIB, schema.getProperty(SERIALIZATION_LIB));
            return stripped;
        }
        return schema;
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
        ColumnEncodingFactory columnEncodingFactory;
        String deserializerClassName = getDeserializerClassName(schema);
        if (deserializerClassName.equals(LAZY_BINARY_COLUMNAR_SERDE_CLASS)) {
            columnEncodingFactory = new BinaryColumnEncodingFactory(timeZone);
        }
        else if (deserializerClassName.equals(COLUMNAR_SERDE_CLASS)) {
            columnEncodingFactory = new TextColumnEncodingFactory(TextEncodingOptions.fromSchema(Maps.fromProperties(schema)));
        }
        else {
            return Optional.empty();
        }

        checkArgument(acidInfo.isEmpty(), "Acid is not supported");

        List<HiveColumnHandle> projectedReaderColumns = columns;
        Optional<ReaderColumns> readerProjections = projectBaseColumns(columns);

        if (readerProjections.isPresent()) {
            projectedReaderColumns = readerProjections.get().get().stream()
                    .map(HiveColumnHandle.class::cast)
                    .collect(toImmutableList());
        }

        TrinoFileSystem trinoFileSystem = fileSystemFactory.create(session.getIdentity());
        TrinoInputFile inputFile = trinoFileSystem.newInputFile(path);
        try {
            length = min(inputFile.length() - start, length);
            if (!inputFile.exists()) {
                throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, "File does not exist");
            }
            if (estimatedFileSize < BUFFER_SIZE.toBytes()) {
                try (InputStream inputStream = inputFile.newStream()) {
                    byte[] data = inputStream.readAllBytes();
                    inputFile = new MemoryInputFile(path, Slices.wrappedBuffer(data));
                }
            }
        }
        catch (TrinoException e) {
            throw e;
        }
        catch (Exception e) {
            throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, splitError(e, path, start, length), e);
        }

        // Split may be empty now that the correct file size is known
        if (length <= 0) {
            return Optional.of(noProjectionAdaptation(new EmptyPageSource()));
        }

        try {
            ImmutableMap.Builder<Integer, Type> readColumns = ImmutableMap.builder();
            for (HiveColumnHandle column : projectedReaderColumns) {
                readColumns.put(column.getBaseHiveColumnIndex(), column.getType());
            }

            RcFileReader rcFileReader = new RcFileReader(
                    inputFile,
                    columnEncodingFactory,
                    readColumns.buildOrThrow(),
                    start,
                    length);

            ConnectorPageSource pageSource = new RcFilePageSource(rcFileReader, projectedReaderColumns);
            return Optional.of(new ReaderPageSource(pageSource, readerProjections));
        }
        catch (TrinoException e) {
            throw e;
        }
        catch (Throwable e) {
            String message = splitError(e, path, start, length);
            if (e instanceof FileCorruptionException) {
                throw new TrinoException(HIVE_BAD_DATA, message, e);
            }
            throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, message, e);
        }
    }
}
