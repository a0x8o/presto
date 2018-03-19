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
package com.facebook.presto.resourceGroups.db;

import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonCodec;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static io.airlift.json.JsonCodec.listJsonCodec;
import static java.util.Objects.requireNonNull;

public class SelectorRecord
{
    private final long resourceGroupId;
    private final long priority;
    private final Optional<Pattern> userRegex;
    private final Optional<Pattern> sourceRegex;
    private final Optional<String> queryType;
    private final Optional<List<String>> clientTags;

    public SelectorRecord(long resourceGroupId, long priority, Optional<Pattern> userRegex, Optional<Pattern> sourceRegex, Optional<String> queryType, Optional<List<String>> clientTags)
    {
        this.resourceGroupId = resourceGroupId;
        this.priority = priority;
        this.userRegex = requireNonNull(userRegex, "userRegex is null");
        this.sourceRegex = requireNonNull(sourceRegex, "sourceRegex is null");
        this.queryType = requireNonNull(queryType, "queryType is null");
        this.clientTags = requireNonNull(clientTags, "clientTags is null").map(ImmutableList::copyOf);
    }

    public long getResourceGroupId()
    {
        return resourceGroupId;
    }

    public long getPriority()
    {
        return priority;
    }

    public Optional<Pattern> getUserRegex()
    {
        return userRegex;
    }

    public Optional<Pattern> getSourceRegex()
    {
        return sourceRegex;
    }

    public Optional<String> getQueryType()
    {
        return queryType;
    }

    public Optional<List<String>> getClientTags()
    {
        return clientTags;
    }

    public static class Mapper
            implements RowMapper<SelectorRecord>
    {
        private static final JsonCodec<List<String>> LIST_STRING_CODEC = listJsonCodec(String.class);

        @Override
        public SelectorRecord map(ResultSet resultSet, StatementContext context)
                throws SQLException
        {
            return new SelectorRecord(
                    resultSet.getLong("resource_group_id"),
                    resultSet.getLong("priority"),
                    Optional.ofNullable(resultSet.getString("user_regex")).map(Pattern::compile),
                    Optional.ofNullable(resultSet.getString("source_regex")).map(Pattern::compile),
                    Optional.ofNullable(resultSet.getString("query_type")),
                    Optional.ofNullable(resultSet.getString("client_tags")).map(LIST_STRING_CODEC::fromJson));
        }
    }
}
