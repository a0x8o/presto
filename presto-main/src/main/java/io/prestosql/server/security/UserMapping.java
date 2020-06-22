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
package io.prestosql.server.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.plugin.base.util.JsonUtils.parseJson;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;

public final class UserMapping
{
    private final List<Rule> rules;

    public static UserMapping createUserMapping(Optional<String> userMappingPattern, Optional<File> userMappingFile)
    {
        if (userMappingPattern.isPresent()) {
            checkArgument(!userMappingFile.isPresent(), "user mapping pattern and file can not both be set");
            return new UserMapping(ImmutableList.of(new Rule(userMappingPattern.get())));
        }
        if (userMappingFile.isPresent()) {
            List<Rule> rules = parseJson(userMappingFile.get().toPath(), UserMappingRules.class).getRules();
            return new UserMapping(rules);
        }
        return new UserMapping(ImmutableList.of(new Rule("(.*)")));
    }

    @VisibleForTesting
    UserMapping(List<Rule> rules)
    {
        this.rules = ImmutableList.copyOf(requireNonNull(rules, "rules is null"));
    }

    public String mapUser(String principal)
            throws UserMappingException
    {
        Optional<String> user = Optional.empty();
        for (Rule rule : rules) {
            user = rule.mapUser(principal);
            if (user.isPresent()) {
                break;
            }
        }

        if (!user.isPresent()) {
            throw new UserMappingException("No user mapping patterns match the principal");
        }
        return user.get();
    }

    public static final class UserMappingRules
    {
        private final List<Rule> rules;

        @JsonCreator
        public UserMappingRules(
                @JsonProperty("rules") List<Rule> rules)
        {
            this.rules = ImmutableList.copyOf(requireNonNull(rules, "rules is null"));
        }

        public List<Rule> getRules()
        {
            return rules;
        }
    }

    public static final class Rule
    {
        private final Pattern pattern;
        private final String user;
        private final boolean allow;

        public Rule(String pattern)
        {
            this(pattern, "$1", true);
        }

        @JsonCreator
        public Rule(
                @JsonProperty("pattern") String pattern,
                @JsonProperty("user") Optional<String> user,
                @JsonProperty("allow") Optional<Boolean> allow)
        {
            this(pattern,
                    requireNonNull(user, "user is null").orElse("$1"),
                    requireNonNull(allow, "allow is null").orElse(TRUE));
        }

        public Rule(String pattern, String user, boolean allow)
        {
            this.pattern = Pattern.compile(requireNonNull(pattern, "pattern is null"));
            this.user = requireNonNull(user, "user is null");
            this.allow = allow;
        }

        public Optional<String> mapUser(String principal)
                throws UserMappingException
        {
            Matcher matcher = pattern.matcher(principal);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            if (!allow) {
                throw new UserMappingException("Principal is not allowed");
            }
            String result = matcher.replaceAll(user).trim();
            if (result.isEmpty()) {
                throw new UserMappingException("Principal matched, but mapped user is empty");
            }
            return Optional.of(result);
        }
    }
}
