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
package io.trino.plugin.iceberg;

import io.trino.spi.TrinoException;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.trino.plugin.iceberg.TableType.DATA;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public final class IcebergTableName
{
    private IcebergTableName() {}

    private static final Pattern TABLE_PATTERN = Pattern.compile("" +
            "(?<table>[^$@]+)" +
            "(?:\\$(?<type>[^@]+))?");

    public static String tableNameWithType(String tableName, TableType tableType)
    {
        requireNonNull(tableName, "tableName is null");
        return tableName + "$" + tableType.name().toLowerCase(ENGLISH);
    }

    public static String tableNameFrom(String name)
    {
        Matcher match = TABLE_PATTERN.matcher(name);
        if (!match.matches()) {
            throw new TrinoException(NOT_SUPPORTED, "Invalid Iceberg table name: " + name);
        }

        return match.group("table");
    }

    public static Optional<TableType> tableTypeFrom(String name)
    {
        Matcher match = TABLE_PATTERN.matcher(name);
        if (!match.matches()) {
            throw new TrinoException(NOT_SUPPORTED, "Invalid Iceberg table name: " + name);
        }
        String typeString = match.group("type");
        if (typeString == null) {
            return Optional.of(DATA);
        }
        try {
            TableType parsedType = TableType.valueOf(typeString.toUpperCase(ENGLISH));
            if (parsedType == DATA) {
                // $data cannot be encoded in table name
                return Optional.empty();
            }
            return Optional.of(parsedType);
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isDataTable(String name)
    {
        Matcher match = TABLE_PATTERN.matcher(name);
        if (!match.matches()) {
            throw new TrinoException(NOT_SUPPORTED, "Invalid Iceberg table name: " + name);
        }
        String typeString = match.group("type");
        return typeString == null;
    }
}
