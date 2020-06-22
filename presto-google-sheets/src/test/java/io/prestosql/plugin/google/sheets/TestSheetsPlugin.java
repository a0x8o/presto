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
package io.prestosql.plugin.google.sheets;

import com.google.common.io.Resources;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorFactory;
import io.prestosql.testing.TestingConnectorContext;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import static com.google.common.collect.ImmutableMap.Builder;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.plugin.google.sheets.TestGoogleSheets.GOOGLE_SHEETS;
import static java.io.File.createTempFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertNotNull;

public class TestSheetsPlugin
{
    static final String TEST_METADATA_SHEET_ID = "1Es4HhWALUQjoa-bQh4a8B5HROz7dpGMfq_HbfoaW5LM#Tables";

    static String getTestCredentialsPath()
            throws Exception
    {
        String encodedCredentials = Resources.toString(Resources.getResource("gsheets-creds-base64-encoded.props"), UTF_8);
        byte[] credentials = Base64.getDecoder().decode(encodedCredentials.trim());
        File tempFile = createTempFile(System.getProperty("java.io.tmpdir"), "credentials-" + System.currentTimeMillis() + ".json");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), credentials);
        return tempFile.getAbsolutePath();
    }

    @Test
    public void testCreateConnector()
            throws Exception
    {
        Plugin plugin = new SheetsPlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        Builder<String, String> propertiesMap = new Builder<String, String>().put("credentials-path", getTestCredentialsPath()).put("metadata-sheet-id", TEST_METADATA_SHEET_ID);
        Connector c = factory.create(GOOGLE_SHEETS, propertiesMap.build(), new TestingConnectorContext());
        assertNotNull(c);
    }
}
