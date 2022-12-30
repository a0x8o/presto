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
package io.prestosql.jdbc;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import io.prestosql.client.ClientSelectedRole;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Maps.immutableEntry;
import static io.prestosql.client.ClientSelectedRole.Type.ALL;
import static io.prestosql.client.ClientSelectedRole.Type.NONE;
import static io.prestosql.jdbc.AbstractConnectionProperty.checkedPredicate;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

final class ConnectionProperties
{
    public static final ConnectionProperty<String> USER = new User();
    public static final ConnectionProperty<String> PASSWORD = new Password();
    public static final ConnectionProperty<Map<String, ClientSelectedRole>> ROLES = new Roles();
    public static final ConnectionProperty<HostAndPort> SOCKS_PROXY = new SocksProxy();
    public static final ConnectionProperty<HostAndPort> HTTP_PROXY = new HttpProxy();
    public static final ConnectionProperty<String> APPLICATION_NAME_PREFIX = new ApplicationNamePrefix();
    public static final ConnectionProperty<Boolean> SSL = new Ssl();
    public static final ConnectionProperty<String> SSL_KEY_STORE_PATH = new SslKeyStorePath();
    public static final ConnectionProperty<String> SSL_KEY_STORE_PASSWORD = new SslKeyStorePassword();
    public static final ConnectionProperty<String> SSL_TRUST_STORE_PATH = new SslTrustStorePath();
    public static final ConnectionProperty<String> SSL_TRUST_STORE_PASSWORD = new SslTrustStorePassword();
    public static final ConnectionProperty<String> KERBEROS_SERVICE_PRINCIPAL_PATTERN = new KerberosServicePrincipalPattern();
    public static final ConnectionProperty<String> KERBEROS_REMOTE_SERVICE_NAME = new KerberosRemoteServiceName();
    public static final ConnectionProperty<Boolean> KERBEROS_USE_CANONICAL_HOSTNAME = new KerberosUseCanonicalHostname();
    public static final ConnectionProperty<String> KERBEROS_PRINCIPAL = new KerberosPrincipal();
    public static final ConnectionProperty<File> KERBEROS_CONFIG_PATH = new KerberosConfigPath();
    public static final ConnectionProperty<File> KERBEROS_KEYTAB_PATH = new KerberosKeytabPath();
    public static final ConnectionProperty<File> KERBEROS_CREDENTIAL_CACHE_PATH = new KerberosCredentialCachePath();
    public static final ConnectionProperty<String> ACCESS_TOKEN = new AccessToken();
    public static final ConnectionProperty<Map<String, String>> EXTRA_CREDENTIALS = new ExtraCredentials();
    public static final ConnectionProperty<String> CLIENT_INFO = new ClientInfo();
    public static final ConnectionProperty<String> CLIENT_TAGS = new ClientTags();
    public static final ConnectionProperty<String> TRACE_TOKEN = new TraceToken();
    public static final ConnectionProperty<Map<String, String>> SESSION_PROPERTIES = new SessionProperties();

    private static final Set<ConnectionProperty<?>> ALL_PROPERTIES = ImmutableSet.<ConnectionProperty<?>>builder()
            .add(USER)
            .add(PASSWORD)
            .add(ROLES)
            .add(SOCKS_PROXY)
            .add(HTTP_PROXY)
            .add(APPLICATION_NAME_PREFIX)
            .add(SSL)
            .add(SSL_KEY_STORE_PATH)
            .add(SSL_KEY_STORE_PASSWORD)
            .add(SSL_TRUST_STORE_PATH)
            .add(SSL_TRUST_STORE_PASSWORD)
            .add(KERBEROS_REMOTE_SERVICE_NAME)
            .add(KERBEROS_SERVICE_PRINCIPAL_PATTERN)
            .add(KERBEROS_USE_CANONICAL_HOSTNAME)
            .add(KERBEROS_PRINCIPAL)
            .add(KERBEROS_CONFIG_PATH)
            .add(KERBEROS_KEYTAB_PATH)
            .add(KERBEROS_CREDENTIAL_CACHE_PATH)
            .add(ACCESS_TOKEN)
            .add(EXTRA_CREDENTIALS)
            .add(CLIENT_INFO)
            .add(CLIENT_TAGS)
            .add(TRACE_TOKEN)
            .add(SESSION_PROPERTIES)
            .build();

    private static final Map<String, ConnectionProperty<?>> KEY_LOOKUP = unmodifiableMap(ALL_PROPERTIES.stream()
            .collect(toMap(ConnectionProperty::getKey, identity())));

    private static final Map<String, String> DEFAULTS;

    static {
        ImmutableMap.Builder<String, String> defaults = ImmutableMap.builder();
        for (ConnectionProperty<?> property : ALL_PROPERTIES) {
            property.getDefault().ifPresent(value -> defaults.put(property.getKey(), value));
        }
        DEFAULTS = defaults.build();
    }

    private ConnectionProperties() {}

    public static ConnectionProperty<?> forKey(String propertiesKey)
    {
        return KEY_LOOKUP.get(propertiesKey);
    }

    public static Set<ConnectionProperty<?>> allProperties()
    {
        return ALL_PROPERTIES;
    }

    public static Map<String, String> getDefaults()
    {
        return DEFAULTS;
    }

    private static class User
            extends AbstractConnectionProperty<String>
    {
        public User()
        {
            super("user", REQUIRED, ALLOWED, NON_EMPTY_STRING_CONVERTER);
        }
    }

    private static class Password
            extends AbstractConnectionProperty<String>
    {
        public Password()
        {
            super("password", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static class Roles
            extends AbstractConnectionProperty<Map<String, ClientSelectedRole>>
    {
        public Roles()
        {
            super("roles", NOT_REQUIRED, ALLOWED, Roles::parseRoles);
        }

        // Roles consists of a list of catalog role pairs.
        // E.g., `jdbc:presto://example.net:8080/?roles=catalog1:none;catalog2:all;catalog3:role` will set following roles:
        //  - `none` in `catalog1`
        //  - `all` in `catalog2`
        //  - `role` in `catalog3`
        public static Map<String, ClientSelectedRole> parseRoles(String roles)
        {
            return new MapPropertyParser("roles").parse(roles).entrySet().stream()
                    .collect(toImmutableMap(entry -> entry.getKey(), entry -> mapToClientSelectedRole(entry.getValue())));
        }

        private static ClientSelectedRole mapToClientSelectedRole(String role)
        {
            checkArgument(!role.contains("\""), "Role must not contain double quotes: %s", role);
            if (ALL.name().equalsIgnoreCase(role)) {
                return new ClientSelectedRole(ALL, Optional.empty());
            }
            if (NONE.name().equalsIgnoreCase(role)) {
                return new ClientSelectedRole(NONE, Optional.empty());
            }
            return new ClientSelectedRole(ClientSelectedRole.Type.ROLE, Optional.of(role));
        }
    }

    private static class SocksProxy
            extends AbstractConnectionProperty<HostAndPort>
    {
        private static final Predicate<Properties> NO_HTTP_PROXY =
                checkedPredicate(properties -> !HTTP_PROXY.getValue(properties).isPresent());

        public SocksProxy()
        {
            super("socksProxy", NOT_REQUIRED, NO_HTTP_PROXY, HostAndPort::fromString);
        }
    }

    private static class HttpProxy
            extends AbstractConnectionProperty<HostAndPort>
    {
        private static final Predicate<Properties> NO_SOCKS_PROXY =
                checkedPredicate(properties -> !SOCKS_PROXY.getValue(properties).isPresent());

        public HttpProxy()
        {
            super("httpProxy", NOT_REQUIRED, NO_SOCKS_PROXY, HostAndPort::fromString);
        }
    }

    private static class ApplicationNamePrefix
            extends AbstractConnectionProperty<String>
    {
        public ApplicationNamePrefix()
        {
            super("applicationNamePrefix", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static class ClientInfo
            extends AbstractConnectionProperty<String>
    {
        public ClientInfo()
        {
            super("clientInfo", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static class ClientTags
            extends AbstractConnectionProperty<String>
    {
        public ClientTags()
        {
            super("clientTags", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static class TraceToken
            extends AbstractConnectionProperty<String>
    {
        public TraceToken()
        {
            super("traceToken", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static class Ssl
            extends AbstractConnectionProperty<Boolean>
    {
        public Ssl()
        {
            super("SSL", NOT_REQUIRED, ALLOWED, BOOLEAN_CONVERTER);
        }
    }

    private static class SslKeyStorePath
            extends AbstractConnectionProperty<String>
    {
        private static final Predicate<Properties> IF_SSL_ENABLED =
                checkedPredicate(properties -> SSL.getValue(properties).orElse(false));

        public SslKeyStorePath()
        {
            super("SSLKeyStorePath", NOT_REQUIRED, IF_SSL_ENABLED, STRING_CONVERTER);
        }
    }

    private static class SslKeyStorePassword
            extends AbstractConnectionProperty<String>
    {
        private static final Predicate<Properties> IF_KEY_STORE =
                checkedPredicate(properties -> SSL_KEY_STORE_PATH.getValue(properties).isPresent());

        public SslKeyStorePassword()
        {
            super("SSLKeyStorePassword", NOT_REQUIRED, IF_KEY_STORE, STRING_CONVERTER);
        }
    }

    private static class SslTrustStorePath
            extends AbstractConnectionProperty<String>
    {
        private static final Predicate<Properties> IF_SSL_ENABLED =
                checkedPredicate(properties -> SSL.getValue(properties).orElse(false));

        public SslTrustStorePath()
        {
            super("SSLTrustStorePath", NOT_REQUIRED, IF_SSL_ENABLED, STRING_CONVERTER);
        }
    }

    private static class SslTrustStorePassword
            extends AbstractConnectionProperty<String>
    {
        private static final Predicate<Properties> IF_TRUST_STORE =
                checkedPredicate(properties -> SSL_TRUST_STORE_PATH.getValue(properties).isPresent());

        public SslTrustStorePassword()
        {
            super("SSLTrustStorePassword", NOT_REQUIRED, IF_TRUST_STORE, STRING_CONVERTER);
        }
    }

    private static class KerberosRemoteServiceName
            extends AbstractConnectionProperty<String>
    {
        public KerberosRemoteServiceName()
        {
            super("KerberosRemoteServiceName", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static Predicate<Properties> isKerberosEnabled()
    {
        return checkedPredicate(properties -> KERBEROS_REMOTE_SERVICE_NAME.getValue(properties).isPresent());
    }

    private static class KerberosServicePrincipalPattern
            extends AbstractConnectionProperty<String>
    {
        public KerberosServicePrincipalPattern()
        {
            super("KerberosServicePrincipalPattern", Optional.of("${SERVICE}@${HOST}"), isKerberosEnabled(), ALLOWED, STRING_CONVERTER);
        }
    }

    private static class KerberosPrincipal
            extends AbstractConnectionProperty<String>
    {
        public KerberosPrincipal()
        {
            super("KerberosPrincipal", NOT_REQUIRED, isKerberosEnabled(), STRING_CONVERTER);
        }
    }

    private static class KerberosUseCanonicalHostname
            extends AbstractConnectionProperty<Boolean>
    {
        public KerberosUseCanonicalHostname()
        {
            super("KerberosUseCanonicalHostname", Optional.of("true"), isKerberosEnabled(), ALLOWED, BOOLEAN_CONVERTER);
        }
    }

    private static class KerberosConfigPath
            extends AbstractConnectionProperty<File>
    {
        public KerberosConfigPath()
        {
            super("KerberosConfigPath", NOT_REQUIRED, isKerberosEnabled(), FILE_CONVERTER);
        }
    }

    private static class KerberosKeytabPath
            extends AbstractConnectionProperty<File>
    {
        public KerberosKeytabPath()
        {
            super("KerberosKeytabPath", NOT_REQUIRED, isKerberosEnabled(), FILE_CONVERTER);
        }
    }

    private static class KerberosCredentialCachePath
            extends AbstractConnectionProperty<File>
    {
        public KerberosCredentialCachePath()
        {
            super("KerberosCredentialCachePath", NOT_REQUIRED, isKerberosEnabled(), FILE_CONVERTER);
        }
    }

    private static class AccessToken
            extends AbstractConnectionProperty<String>
    {
        public AccessToken()
        {
            super("accessToken", NOT_REQUIRED, ALLOWED, STRING_CONVERTER);
        }
    }

    private static class ExtraCredentials
            extends AbstractConnectionProperty<Map<String, String>>
    {
        public ExtraCredentials()
        {
            super("extraCredentials", NOT_REQUIRED, ALLOWED, ExtraCredentials::parseExtraCredentials);
        }

        // Extra credentials consists of a list of credential name value pairs.
        // E.g., `jdbc:presto://example.net:8080/?extraCredentials=abc:xyz;foo:bar` will create credentials `abc=xyz` and `foo=bar`
        public static Map<String, String> parseExtraCredentials(String extraCredentialString)
        {
            return new MapPropertyParser("extraCredentials").parse(extraCredentialString);
        }
    }

    private static class SessionProperties
            extends AbstractConnectionProperty<Map<String, String>>
    {
        private static final Splitter NAME_PARTS_SPLITTER = Splitter.on('.');

        public SessionProperties()
        {
            super("sessionProperties", NOT_REQUIRED, ALLOWED, SessionProperties::parseSessionProperties);
        }

        // Session properties consists of a list of session property name value pairs.
        // E.g., `jdbc:presto://example.net:8080/?sessionProperties=abc:xyz;catalog.foo:bar` will create session properties `abc=xyz` and `catalog.foo=bar`
        public static Map<String, String> parseSessionProperties(String sessionPropertiesString)
        {
            Map<String, String> sessionProperties = new MapPropertyParser("sessionProperties").parse(sessionPropertiesString);
            for (String sessionPropertyName : sessionProperties.keySet()) {
                checkArgument(NAME_PARTS_SPLITTER.splitToList(sessionPropertyName).size() <= 2, "Malformed session property name: %s", sessionPropertyName);
            }
            return sessionProperties;
        }
    }

    private static class MapPropertyParser
    {
        private static final CharMatcher PRINTABLE_ASCII = CharMatcher.inRange((char) 0x21, (char) 0x7E);
        private static final Splitter MAP_ENTRIES_SPLITTER = Splitter.on(';');
        private static final Splitter MAP_ENTRY_SPLITTER = Splitter.on(':');

        private final String mapName;

        private MapPropertyParser(String mapName)
        {
            this.mapName = requireNonNull(mapName, "mapName is null");
        }

        /**
         * Parses map in a form: key1:value1;key2:value2
         */
        public Map<String, String> parse(String map)
        {
            return MAP_ENTRIES_SPLITTER.splitToList(map).stream()
                    .map(this::parseEntry)
                    .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private Map.Entry<String, String> parseEntry(String credential)
        {
            List<String> keyValue = MAP_ENTRY_SPLITTER.limit(2).splitToList(credential);
            checkArgument(keyValue.size() == 2, "Malformed %s: %s", mapName, credential);
            String key = keyValue.get(0);
            String value = keyValue.get(1);
            checkArgument(!key.isEmpty(), "%s key is empty", mapName);
            checkArgument(!value.isEmpty(), "%s key is empty", mapName);

            checkArgument(PRINTABLE_ASCII.matchesAllOf(key), "%s key '%s' contains spaces or is not printable ASCII", mapName, key);
            // do not log value as it may contain sensitive information
            checkArgument(PRINTABLE_ASCII.matchesAllOf(value), "%s value for key '%s' contains spaces or is not printable ASCII", mapName, key);
            return immutableEntry(key, value);
        }
    }
}
