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
package io.prestosql.spi.security;

import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

public class ConnectorIdentity
{
    private final String user;
    private final Set<String> groups;
    private final Optional<Principal> principal;
    private final Optional<SelectedRole> role;
    private final Map<String, String> extraCredentials;

    @Deprecated
    public ConnectorIdentity(String user, Optional<Principal> principal, Optional<SelectedRole> role)
    {
        this(user, principal, role, emptyMap());
    }

    @Deprecated
    public ConnectorIdentity(String user, Optional<Principal> principal, Optional<SelectedRole> role, Map<String, String> extraCredentials)
    {
        this(user, emptySet(), principal, role, extraCredentials);
    }

    private ConnectorIdentity(
            String user,
            Set<String> groups,
            Optional<Principal> principal,
            Optional<SelectedRole> role,
            Map<String, String> extraCredentials)
    {
        this.user = requireNonNull(user, "user is null");
        this.groups = unmodifiableSet(new HashSet<>(requireNonNull(groups, "groups is null")));
        this.principal = requireNonNull(principal, "principal is null");
        this.role = requireNonNull(role, "role is null");
        this.extraCredentials = unmodifiableMap(new HashMap<>(requireNonNull(extraCredentials, "extraCredentials is null")));
    }

    public String getUser()
    {
        return user;
    }

    public Set<String> getGroups()
    {
        return groups;
    }

    public Optional<Principal> getPrincipal()
    {
        return principal;
    }

    public Optional<SelectedRole> getRole()
    {
        return role;
    }

    public Map<String, String> getExtraCredentials()
    {
        return extraCredentials;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("ConnectorIdentity{");
        sb.append("user='").append(user).append('\'');
        sb.append(", groups=").append(groups);
        principal.ifPresent(principal -> sb.append(", principal=").append(principal));
        role.ifPresent(role -> sb.append(", role=").append(role));
        sb.append(", extraCredentials=").append(extraCredentials.keySet());
        sb.append('}');
        return sb.toString();
    }

    public static ConnectorIdentity ofUser(String user)
    {
        return new Builder(user).build();
    }

    public static Builder forUser(String user)
    {
        return new Builder(user);
    }

    public static class Builder
    {
        private final String user;
        private Set<String> groups = emptySet();
        private Optional<Principal> principal = Optional.empty();
        private Optional<SelectedRole> role = Optional.empty();
        private Map<String, String> extraCredentials = new HashMap<>();

        private Builder(String user)
        {
            this.user = requireNonNull(user, "user is null");
        }

        public Builder withGroups(Set<String> groups)
        {
            this.groups = unmodifiableSet(new HashSet<>(requireNonNull(groups, "groups is null")));
            return this;
        }

        public Builder withPrincipal(Principal principal)
        {
            return withPrincipal(Optional.of(requireNonNull(principal, "principal is null")));
        }

        public Builder withPrincipal(Optional<Principal> principal)
        {
            this.principal = requireNonNull(principal, "principal is null");
            return this;
        }

        public Builder withRole(SelectedRole role)
        {
            return withRole(Optional.of(requireNonNull(role, "role is null")));
        }

        public Builder withRole(Optional<SelectedRole> role)
        {
            this.role = requireNonNull(role, "role is null");
            return this;
        }

        public Builder withExtraCredentials(Map<String, String> extraCredentials)
        {
            this.extraCredentials = new HashMap<>(requireNonNull(extraCredentials, "extraCredentials is null"));
            return this;
        }

        public ConnectorIdentity build()
        {
            return new ConnectorIdentity(user, groups, principal, role, extraCredentials);
        }
    }
}
