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
package io.prestosql.plugin.password.ldap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.VerifyException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.airlift.log.Logger;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.BasicPrincipal;
import io.prestosql.spi.security.PasswordAuthenticator;

import javax.inject.Inject;
import javax.naming.AuthenticationException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static io.prestosql.plugin.password.jndi.JndiUtils.createDirContext;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static javax.naming.Context.INITIAL_CONTEXT_FACTORY;
import static javax.naming.Context.PROVIDER_URL;
import static javax.naming.Context.REFERRAL;
import static javax.naming.Context.SECURITY_AUTHENTICATION;
import static javax.naming.Context.SECURITY_CREDENTIALS;
import static javax.naming.Context.SECURITY_PRINCIPAL;

public class LdapAuthenticator
        implements PasswordAuthenticator
{
    private static final Logger log = Logger.get(LdapAuthenticator.class);
    private static final CharMatcher SPECIAL_CHARACTERS = CharMatcher.anyOf(",=+<>#;*()\"\\\u0000");
    private static final CharMatcher WHITESPACE = CharMatcher.anyOf(" \r");

    private final Optional<String> userBindSearchPattern;
    private final Optional<String> groupAuthorizationSearchPattern;
    private final Optional<String> userBaseDistinguishedName;
    private final Optional<String> bindDistinguishedName;
    private final Optional<String> bindPassword;
    private final boolean ignoreReferrals;
    private final Map<String, String> basicEnvironment;
    private final LoadingCache<Credentials, Principal> authenticationCache;

    @Inject
    public LdapAuthenticator(LdapConfig ldapConfig)
    {
        String ldapUrl = requireNonNull(ldapConfig.getLdapUrl(), "ldapUrl is null");
        this.userBindSearchPattern = Optional.ofNullable(ldapConfig.getUserBindSearchPattern());
        this.groupAuthorizationSearchPattern = Optional.ofNullable(ldapConfig.getGroupAuthorizationSearchPattern());
        this.userBaseDistinguishedName = Optional.ofNullable(ldapConfig.getUserBaseDistinguishedName());
        this.bindDistinguishedName = Optional.ofNullable(ldapConfig.getBindDistingushedName());
        this.bindPassword = Optional.ofNullable(ldapConfig.getBindPassword());
        this.ignoreReferrals = ldapConfig.isIgnoreReferrals();

        if (groupAuthorizationSearchPattern.isPresent()) {
            checkState(userBaseDistinguishedName.isPresent(), "Base distinguished name (DN) for user is null");
        }
        checkState(bindDistinguishedName.isPresent() == bindPassword.isPresent(),
                "Both or none bind distinguished name and bind password must be provided");
        checkState(
                !bindDistinguishedName.isPresent() || groupAuthorizationSearchPattern.isPresent(),
                "Group authorization search pattern must be provided when bind distinguished name is not used");
        checkState(bindDistinguishedName.isPresent() || userBindSearchPattern.isPresent(),
                "Either user bind search pattern or bind distinguished name must be provided");

        Map<String, String> environment = ImmutableMap.<String, String>builder()
                .put(INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
                .put(PROVIDER_URL, ldapUrl)
                .build();
        this.basicEnvironment = environment;

        if (this.bindDistinguishedName.isPresent()) {
            this.authenticationCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(ldapConfig.getLdapCacheTtl().toMillis(), MILLISECONDS)
                    .build(CacheLoader.from(this::authenticateWithBindDistinguishedName));
        }
        else {
            this.authenticationCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(ldapConfig.getLdapCacheTtl().toMillis(), MILLISECONDS)
                    .build(CacheLoader.from(this::authenticateWithUserBind));
        }
    }

    @Override
    public Principal createAuthenticatedPrincipal(String user, String password)
    {
        try {
            return authenticationCache.getUnchecked(new Credentials(user, password));
        }
        catch (UncheckedExecutionException e) {
            throwIfInstanceOf(e.getCause(), AccessDeniedException.class);
            throw e;
        }
    }

    private Principal authenticateWithUserBind(Credentials credentials)
    {
        String user = credentials.getUser();
        if (containsSpecialCharacters(user)) {
            throw new AccessDeniedException("Username contains a special LDAP character");
        }
        try {
            String userDistinguishedName = createUserDistinguishedName(user);
            if (groupAuthorizationSearchPattern.isPresent()) {
                // user password is also validated as user DN and password is used for querying LDAP
                checkGroupMembership(user, userDistinguishedName, credentials.getPassword());
            }
            else {
                validatePassword(userDistinguishedName, credentials.getPassword());
            }
            log.debug("Authentication successful for user [%s]", user);
        }
        catch (NamingException e) {
            log.debug(e, "Authentication failed for user [%s], %s", user, e.getMessage());
            throw new RuntimeException("Authentication error");
        }
        return new BasicPrincipal(user);
    }

    private Principal authenticateWithBindDistinguishedName(Credentials credentials)
    {
        String user = credentials.getUser();
        if (containsSpecialCharacters(user)) {
            throw new AccessDeniedException("Username contains a special LDAP character");
        }
        try {
            String userDistinguishedName = validateGroupMembership(user, bindDistinguishedName.get(), bindPassword.get());
            validatePassword(userDistinguishedName, credentials.getPassword());
            log.debug("Authentication successful for user [%s]", user);
        }
        catch (NamingException e) {
            log.debug(e, "Authentication failed for user [%s], %s", user, e.getMessage());
            throw new RuntimeException("Authentication error");
        }
        return new BasicPrincipal(credentials.getUser());
    }

    private String createUserDistinguishedName(String user)
    {
        return replaceUser(userBindSearchPattern.get(), user);
    }

    private String validateGroupMembership(String user, String contextUserDistinguishedName, String contextPassword)
            throws NamingException
    {
        DirContext context = createUserDirContext(contextUserDistinguishedName, contextPassword);
        try {
            return validateGroupMembership(user, context);
        }
        finally {
            context.close();
        }
    }

    private void checkGroupMembership(String user, String contextUserDistinguishedName, String contextPassword)
            throws NamingException
    {
        DirContext context = createUserDirContext(contextUserDistinguishedName, contextPassword);
        try {
            NamingEnumeration<SearchResult> search = searchGroupMembership(user, context);
            try {
                if (!search.hasMore()) {
                    String message = format("User [%s] not a member of an authorized group", user);
                    log.debug(message);
                    throw new AccessDeniedException(message);
                }
            }
            finally {
                search.close();
            }
        }
        finally {
            context.close();
        }
    }

    /**
     * Returns {@code true} when parameter contains a character that has a special meaning in
     * LDAP search or bind name (DN).
     *
     * Based on <a href="https://www.owasp.org/index.php/Preventing_LDAP_Injection_in_Java">Preventing_LDAP_Injection_in_Java</a> and
     * {@link javax.naming.ldap.Rdn#escapeValue(Object) escapeValue} method.
     */
    @VisibleForTesting
    static boolean containsSpecialCharacters(String user)
    {
        if (WHITESPACE.indexIn(user) == 0 || WHITESPACE.lastIndexIn(user) == user.length() - 1) {
            return true;
        }
        return SPECIAL_CHARACTERS.matchesAnyOf(user);
    }

    private String validateGroupMembership(String user, DirContext context)
            throws NamingException
    {
        NamingEnumeration<SearchResult> search = searchGroupMembership(user, context);
        try {
            if (!search.hasMore()) {
                String message = format("User [%s] not a member of an authorized group", user);
                log.debug(message);
                throw new AccessDeniedException(message);
            }

            String userDistinguishedName = search.next().getNameInNamespace();
            while (search.hasMore()) {
                String nextUserDistinguishedName = search.next().getNameInNamespace();
                if (!userDistinguishedName.equals(nextUserDistinguishedName)) {
                    log.debug("Multiple group membership results for user [%s] with different distinguished names: [%s], [%s]", user, userDistinguishedName, nextUserDistinguishedName);
                    throw new AccessDeniedException(format("Multiple group membership results for user [%s] with different distinguished names", user));
                }
            }

            log.debug("Group membership validated for user [%s]", user);
            return userDistinguishedName;
        }
        finally {
            search.close();
        }
    }

    private NamingEnumeration<SearchResult> searchGroupMembership(String user, DirContext context)
            throws NamingException
    {
        String userBase = userBaseDistinguishedName.orElseThrow(VerifyException::new);
        String searchFilter = replaceUser(groupAuthorizationSearchPattern.get(), user);
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        return context.search(userBase, searchFilter, searchControls);
    }

    private void validatePassword(String userDistinguishedName, String password)
            throws NamingException
    {
        createUserDirContext(userDistinguishedName, password).close();
    }

    private DirContext createUserDirContext(String userDistinguishedName, String password)
            throws NamingException
    {
        Map<String, String> environment = createEnvironment(userDistinguishedName, password);
        try {
            // This is the actual Authentication piece. Will throw javax.naming.AuthenticationException
            // if the users password is not correct. Other exceptions may include IO (server not found) etc.
            DirContext context = createDirContext(environment);
            log.debug("Password validation successful for user DN [%s]", userDistinguishedName);
            return context;
        }
        catch (AuthenticationException e) {
            log.debug("Password validation failed for user DN [%s]: %s", userDistinguishedName, e.getMessage());
            throw new AccessDeniedException("Invalid credentials");
        }
    }

    private Map<String, String> createEnvironment(String userDistinguishedName, String password)
    {
        return ImmutableMap.<String, String>builder()
                .putAll(basicEnvironment)
                .put(SECURITY_AUTHENTICATION, "simple")
                .put(SECURITY_PRINCIPAL, userDistinguishedName)
                .put(SECURITY_CREDENTIALS, password)
                .put(REFERRAL, ignoreReferrals ? "ignore" : "follow")
                .build();
    }

    private static String replaceUser(String pattern, String user)
    {
        return pattern.replace("${USER}", user);
    }

    private static class Credentials
    {
        private final String user;
        private final String password;

        private Credentials(String user, String password)
        {
            this.user = requireNonNull(user);
            this.password = requireNonNull(password);
        }

        public String getUser()
        {
            return user;
        }

        public String getPassword()
        {
            return password;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Credentials that = (Credentials) o;

            return Objects.equals(this.user, that.user) &&
                    Objects.equals(this.password, that.password);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(user, password);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("user", user)
                    .add("password", password)
                    .toString();
        }
    }
}
