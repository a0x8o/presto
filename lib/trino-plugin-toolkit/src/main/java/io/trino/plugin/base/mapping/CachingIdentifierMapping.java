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
package io.trino.plugin.base.mapping;

import com.google.common.base.CharMatcher;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import io.trino.cache.NonKeyEvictableCache;
import io.trino.plugin.base.mapping.IdentifierMappingModule.ForCachingIdentifierMapping;
import io.trino.spi.TrinoException;
import io.trino.spi.security.ConnectorIdentity;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static io.trino.cache.SafeCaches.buildNonEvictableCacheWithWeakInvalidateAll;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class CachingIdentifierMapping
        implements IdentifierMapping
{
    private final NonKeyEvictableCache<ConnectorIdentity, Mapping> remoteSchemaNames;
    private final NonKeyEvictableCache<RemoteTableNameCacheKey, Mapping> remoteTableNames;
    private final IdentifierMapping identifierMapping;

    @Inject
    public CachingIdentifierMapping(
            MappingConfig mappingConfig,
            @ForCachingIdentifierMapping IdentifierMapping identifierMapping)
    {
        CacheBuilder<Object, Object> remoteNamesCacheBuilder = CacheBuilder.newBuilder()
                .expireAfterWrite(mappingConfig.getCaseInsensitiveNameMatchingCacheTtl().toMillis(), MILLISECONDS);
        this.remoteSchemaNames = buildNonEvictableCacheWithWeakInvalidateAll(remoteNamesCacheBuilder);
        this.remoteTableNames = buildNonEvictableCacheWithWeakInvalidateAll(remoteNamesCacheBuilder);

        this.identifierMapping = requireNonNull(identifierMapping, "identifierMapping is null");
    }

    public void flushCache()
    {
        // Note: this may not invalidate ongoing loads (https://github.com/trinodb/trino/issues/10512, https://github.com/google/guava/issues/1881)
        // This is acceptable, since this operation is invoked manually, and not relied upon for correctness.
        remoteSchemaNames.invalidateAll();
        remoteTableNames.invalidateAll();
    }

    @Override
    public String fromRemoteSchemaName(String remoteSchemaName)
    {
        return identifierMapping.fromRemoteSchemaName(remoteSchemaName);
    }

    @Override
    public String fromRemoteTableName(String remoteSchemaName, String remoteTableName)
    {
        return identifierMapping.fromRemoteTableName(remoteSchemaName, remoteTableName);
    }

    @Override
    public String fromRemoteColumnName(String remoteColumnName)
    {
        return identifierMapping.fromRemoteColumnName(remoteColumnName);
    }

    @Override
    public String toRemoteSchemaName(RemoteIdentifiers remoteIdentifiers, ConnectorIdentity identity, String schemaName)
    {
        requireNonNull(schemaName, "schemaName is null");
        verify(CharMatcher.forPredicate(Character::isUpperCase).matchesNoneOf(schemaName), "Expected schema name from internal metadata to be lowercase: %s", schemaName);
        try {
            Mapping mapping = remoteSchemaNames.getIfPresent(identity);
            if (mapping != null && !mapping.hasRemoteObject(schemaName)) {
                // This might be a schema that has just been created. Force reload.
                mapping = null;
            }
            if (mapping == null) {
                mapping = createSchemaMapping(remoteIdentifiers.getRemoteSchemas());
                remoteSchemaNames.put(identity, mapping);
            }
            String remoteSchema = mapping.get(schemaName);
            if (remoteSchema != null) {
                return remoteSchema;
            }
        }
        catch (RuntimeException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to find remote schema name: " + firstNonNull(e.getMessage(), e), e);
        }

        return identifierMapping.toRemoteSchemaName(remoteIdentifiers, identity, schemaName);
    }

    @Override
    public String toRemoteTableName(RemoteIdentifiers remoteIdentifiers, ConnectorIdentity identity, String remoteSchema, String tableName)
    {
        requireNonNull(remoteSchema, "remoteSchema is null");
        requireNonNull(tableName, "tableName is null");
        verify(CharMatcher.forPredicate(Character::isUpperCase).matchesNoneOf(tableName), "Expected table name from internal metadata to be lowercase: %s", tableName);
        try {
            RemoteTableNameCacheKey cacheKey = new RemoteTableNameCacheKey(identity, remoteSchema);
            Mapping mapping = remoteTableNames.getIfPresent(cacheKey);
            if (mapping != null && !mapping.hasRemoteObject(tableName)) {
                // This might be a table that has just been created. Force reload.
                mapping = null;
            }
            if (mapping == null) {
                mapping = createTableMapping(remoteSchema, remoteIdentifiers.getRemoteTables(remoteSchema));
                remoteTableNames.put(cacheKey, mapping);
            }
            String remoteTable = mapping.get(tableName);
            if (remoteTable != null) {
                return remoteTable;
            }
        }
        catch (RuntimeException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to find remote table name: " + firstNonNull(e.getMessage(), e), e);
        }

        return identifierMapping.toRemoteTableName(remoteIdentifiers, identity, remoteSchema, tableName);
    }

    @Override
    public String toRemoteColumnName(RemoteIdentifiers remoteIdentifiers, String columnName)
    {
        return identifierMapping.toRemoteColumnName(remoteIdentifiers, columnName);
    }

    private Mapping createSchemaMapping(Collection<String> remoteSchemas)
    {
        return createMapping(remoteSchemas, identifierMapping::fromRemoteSchemaName);
    }

    private Mapping createTableMapping(String remoteSchema, Set<String> remoteTables)
    {
        return createMapping(
                remoteTables,
                remoteTableName -> identifierMapping.fromRemoteTableName(remoteSchema, remoteTableName));
    }

    private static Mapping createMapping(Collection<String> remoteNames, Function<String, String> mapping)
    {
        Map<String, String> map = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (String remoteName : remoteNames) {
            String name = mapping.apply(remoteName);
            if (duplicates.contains(name)) {
                continue;
            }
            if (map.put(name, remoteName) != null) {
                duplicates.add(name);
                map.remove(name);
            }
        }
        return new Mapping(map, duplicates);
    }

    private static final class Mapping
    {
        private final Map<String, String> mapping;
        private final Set<String> duplicates;

        public Mapping(Map<String, String> mapping, Set<String> duplicates)
        {
            this.mapping = ImmutableMap.copyOf(requireNonNull(mapping, "mapping is null"));
            this.duplicates = ImmutableSet.copyOf(requireNonNull(duplicates, "duplicates is null"));
        }

        public boolean hasRemoteObject(String remoteName)
        {
            return mapping.containsKey(remoteName) || duplicates.contains(remoteName);
        }

        @Nullable
        public String get(String remoteName)
        {
            checkArgument(!duplicates.contains(remoteName), "Ambiguous name: %s", remoteName);
            return mapping.get(remoteName);
        }
    }
}
