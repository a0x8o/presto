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
package io.prestosql.plugin.jdbc.credential.keystore;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestKeyStoreBasedCredentialProviderConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(KeyStoreBasedCredentialProviderConfig.class)
                .setKeyStoreFilePath(null)
                .setKeyStoreType(null)
                .setKeyStorePassword(null)
                .setUserCredentialName(null)
                .setPasswordForUserCredentialName(null)
                .setPasswordCredentialName(null)
                .setPasswordForPasswordCredentialName(null));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("keystore-file-path", "/presto/credentials.jceks")
                .put("keystore-type", "JCEKS")
                .put("keystore-password", "keystore_password")
                .put("keystore-user-credential-name", "userName")
                .put("keystore-user-credential-password", "keystore_password_for_user_name")
                .put("keystore-password-credential-name", "password")
                .put("keystore-password-credential-password", "keystore_password_for_password")
                .build();

        KeyStoreBasedCredentialProviderConfig expected = new KeyStoreBasedCredentialProviderConfig()
                .setKeyStoreFilePath("/presto/credentials.jceks")
                .setKeyStoreType("JCEKS")
                .setKeyStorePassword("keystore_password")
                .setUserCredentialName("userName")
                .setPasswordForUserCredentialName("keystore_password_for_user_name")
                .setPasswordCredentialName("password")
                .setPasswordForPasswordCredentialName("keystore_password_for_password");

        assertFullMapping(properties, expected);
    }
}
