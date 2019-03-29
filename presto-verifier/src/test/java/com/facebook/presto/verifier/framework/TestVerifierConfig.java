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
package com.facebook.presto.verifier.framework;

import com.google.common.collect.ImmutableMap;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class TestVerifierConfig
{
    @Test
    public void testDefault()
    {
        assertRecordedDefaults(recordDefaults(VerifierConfig.class)
                .setAdditionalJdbcDriverPath(null)
                .setControlJdbcDriverClass(null)
                .setTestJdbcDriverClass(null)
                .setControlJdbcUrl(null)
                .setTestJdbcUrl(null)
                .setControlTimeout(new Duration(10, MINUTES))
                .setTestTimeout(new Duration(30, MINUTES))
                .setMetadataTimeout(new Duration(3, MINUTES))
                .setChecksumTimeout(new Duration(20, MINUTES))
                .setControlTablePrefix("tmp_verifier_control")
                .setTestTablePrefix("tmp_verifier_test")
                .setControlCatalogOverride(null)
                .setControlSchemaOverride(null)
                .setControlUsernameOverride(null)
                .setControlPasswordOverride(null)
                .setTestCatalogOverride(null)
                .setTestSchemaOverride(null)
                .setTestUsernameOverride(null)
                .setTestPasswordOverride(null)
                .setWhitelist(null)
                .setBlacklist(null)
                .setSourceQuerySupplier("mysql")
                .setEventClients("human-readable")
                .setJsonEventLogFile(null)
                .setHumanReadableEventLogFile(null)
                .setTestId(null)
                .setMaxConcurrency(10)
                .setSuiteRepetitions(1)
                .setQueryRepetitions(1)
                .setRelativeErrorMargin(1e-4)
                .setRunTearDownOnResultMismatch(false));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("additional-jdbc-driver-path", "/path/to/file")
                .put("control.jdbc-driver-class", "ControlDriver")
                .put("test.jdbc-driver-class", "TestDriver")
                .put("control.jdbc-url", "jdbc:presto://proxy.presto.fbinfra.net")
                .put("test.jdbc-url", "jdbc:presto://proxy.presto.fbinfra.net")
                .put("control.timeout", "1h")
                .put("test.timeout", "2h")
                .put("metadata.timeout", "3h")
                .put("checksum.timeout", "4h")
                .put("control.table-prefix", "local.control")
                .put("test.table-prefix", "local.test")
                .put("control.catalog-override", "control_catalog")
                .put("control.schema-override", "control_schema")
                .put("control.username-override", "control_username")
                .put("control.password-override", "control_password")
                .put("test.catalog-override", "test_catalog")
                .put("test.schema-override", "test_schema")
                .put("test.username-override", "test_username")
                .put("test.password-override", "test_password")
                .put("whitelist", "a,b,c")
                .put("blacklist", "b,d,f")
                .put("source-query.supplier", "custom-supplier")
                .put("event-clients", "json,human-readable")
                .put("json.log-file", "verifier-json.log")
                .put("human-readable.log-file", "verifier-human-readable.log")
                .put("test-id", "12345")
                .put("max-concurrency", "100")
                .put("suite-repetitions", "2")
                .put("query-repetitions", "3")
                .put("relative-error-margin", "2e-5")
                .put("run-teardown-on-result-mismatch", "true")
                .build();
        VerifierConfig expected = new VerifierConfig()
                .setAdditionalJdbcDriverPath("/path/to/file")
                .setControlJdbcDriverClass("ControlDriver")
                .setTestJdbcDriverClass("TestDriver")
                .setControlJdbcUrl("jdbc:presto://proxy.presto.fbinfra.net")
                .setTestJdbcUrl("jdbc:presto://proxy.presto.fbinfra.net")
                .setControlTimeout(new Duration(1, HOURS))
                .setTestTimeout(new Duration(2, HOURS))
                .setMetadataTimeout(new Duration(3, HOURS))
                .setChecksumTimeout(new Duration(4, HOURS))
                .setControlTablePrefix("local.control")
                .setTestTablePrefix("local.test")
                .setControlCatalogOverride("control_catalog")
                .setControlSchemaOverride("control_schema")
                .setControlUsernameOverride("control_username")
                .setControlPasswordOverride("control_password")
                .setTestCatalogOverride("test_catalog")
                .setTestSchemaOverride("test_schema")
                .setTestUsernameOverride("test_username")
                .setTestPasswordOverride("test_password")
                .setWhitelist("a,b,c")
                .setBlacklist("b,d,f")
                .setSourceQuerySupplier("custom-supplier")
                .setEventClients("json,human-readable")
                .setJsonEventLogFile("verifier-json.log")
                .setHumanReadableEventLogFile("verifier-human-readable.log")
                .setTestId("12345")
                .setMaxConcurrency(100)
                .setSuiteRepetitions(2)
                .setQueryRepetitions(3)
                .setRelativeErrorMargin(2e-5)
                .setRunTearDownOnResultMismatch(true);

        assertFullMapping(properties, expected);
    }
}
