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

import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.parser.SqlParserOptions;
import com.facebook.presto.tests.StandaloneQueryRunner;
import com.facebook.presto.verifier.checksum.ChecksumValidator;
import com.facebook.presto.verifier.checksum.FloatingPointColumnValidator;
import com.facebook.presto.verifier.checksum.OrderableArrayColumnValidator;
import com.facebook.presto.verifier.checksum.SimpleColumnValidator;
import com.facebook.presto.verifier.event.VerifierQueryEvent;
import com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus;
import com.facebook.presto.verifier.retry.RetryConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import static com.facebook.presto.sql.parser.IdentifierSymbol.AT_SIGN;
import static com.facebook.presto.sql.parser.IdentifierSymbol.COLON;
import static com.facebook.presto.verifier.VerifierTestUtil.CATALOG;
import static com.facebook.presto.verifier.VerifierTestUtil.SCHEMA;
import static com.facebook.presto.verifier.VerifierTestUtil.getJdbcUrl;
import static com.facebook.presto.verifier.VerifierTestUtil.setupPresto;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.FAILED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.SKIPPED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.SUCCEEDED;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.MULTILINE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestDataVerification
{
    private static final String SUITE = "test-suite";
    private static final String NAME = "test-query";
    private static final String TEST_ID = "test-id";

    private static StandaloneQueryRunner queryRunner;

    private VerifierConfig verifierConfig;
    private PrestoAction prestoAction;
    private QueryRewriter queryRewriter;
    private ChecksumValidator checksumValidator;

    @BeforeClass
    public void setupClass()
            throws Exception
    {
        queryRunner = setupPresto();
    }

    @BeforeMethod
    public void setup()
    {
        String jdbcUrl = getJdbcUrl(queryRunner);
        RetryConfig retryConfig = new RetryConfig();
        verifierConfig = new VerifierConfig()
                .setControlJdbcUrl(jdbcUrl)
                .setTestJdbcUrl(jdbcUrl)
                .setTestId(TEST_ID);
        prestoAction = new PrestoAction(
                new PrestoExceptionClassifier(ImmutableSet.of(), ImmutableSet.of()),
                verifierConfig,
                retryConfig,
                retryConfig);
        queryRewriter = new QueryRewriter(
                new SqlParser(new SqlParserOptions().allowIdentifierSymbol(COLON, AT_SIGN)),
                prestoAction,
                verifierConfig);
        checksumValidator = new ChecksumValidator(
                new SimpleColumnValidator(),
                new FloatingPointColumnValidator(verifierConfig),
                new OrderableArrayColumnValidator());
    }

    private DataVerification createVerification(String controlQuery, String testQuery)
    {
        QueryConfiguration configuration = new QueryConfiguration(CATALOG, SCHEMA, "test-user", Optional.empty(), ImmutableMap.of());
        SourceQuery sourceQuery = new SourceQuery(SUITE, NAME, controlQuery, testQuery, configuration, configuration);
        return new DataVerification(prestoAction, sourceQuery, queryRewriter, ImmutableList.of(), verifierConfig, checksumValidator);
    }

    @Test
    public void testSuccess()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT 1.0", "SELECT 1.00001").run();
        assertTrue(event.isPresent());
        assertEvent(event.get(), SUCCEEDED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void testSchemaMismatch()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT 1", "SELECT 1.00001").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.empty(),
                Optional.of("SCHEMA_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED\n" +
                        "SCHEMA MISMATCH\n"));
    }

    @Test
    public void testRowCountMismatch()
    {
        Optional<VerifierQueryEvent> event = createVerification(
                "SELECT 1 x",
                "SELECT 1 x UNION ALL SELECT 1 x").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.of(true),
                Optional.of("ROW_COUNT_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED\n" +
                        "ROW COUNT MISMATCH\n" +
                        "Control 1 rows, Test 2 rows\n"));
    }

    @Test
    public void testColumnMismatch()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT 1.0", "SELECT 1.001").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.of(true),
                Optional.of("COLUMN_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED\n" +
                        "COLUMN MISMATCH\n" +
                        "Control 1 rows, Test 1 rows\n" +
                        "Mismatched Columns:\n" +
                        "  _col0 \\(double\\): control\\(sum: 1.0\\) test\\(sum: 1.001\\) relative error: 9.995002498749525E-4\n"));
    }

    @Test
    public void testParsingFailed()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT", "SELECT 1").run();
        assertFalse(event.isPresent());
    }

    @Test
    public void testRewriteFailed()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT * FROM test", "SELECT 1").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                SKIPPED,
                Optional.empty(),
                Optional.of("PRESTO(SYNTAX_ERROR)"),
                Optional.of("Test state NOT_RUN, Control state NOT_RUN\n.*"));
    }

    @Test
    public void testControlFailed()
    {
        Optional<VerifierQueryEvent> event = createVerification("INSERT INTO dest SELECT * FROM test", "SELECT 1").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                SKIPPED,
                Optional.empty(),
                Optional.of("PRESTO(SYNTAX_ERROR)"),
                Optional.of("Test state NOT_RUN, Control state FAILED_TO_SETUP\n.*"));
    }

    @Test
    public void testNonDeterministic()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT rand()", "SELECT 2.0").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                SKIPPED,
                Optional.of(false),
                Optional.of("COLUMN_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED\n" +
                        "COLUMN MISMATCH\n" +
                        "Control 1 rows, Test 1 rows\n" +
                        "Mismatched Columns:\n" +
                        "  _col0 \\(double\\): control\\(sum: .*\\) test\\(sum: 2.0\\) relative error: .*\n"));
    }

    private void assertEvent(
            VerifierQueryEvent event,
            EventStatus expectedStatus,
            Optional<Boolean> expectedDeterministic,
            Optional<String> expectedErrorCode,
            Optional<String> expectedErrorMessageRegex)
    {
        assertEquals(event.getSuite(), SUITE);
        assertEquals(event.getTestId(), TEST_ID);
        assertEquals(event.getName(), NAME);
        assertEquals(event.getStatus(), expectedStatus.name());
        assertEquals(event.getDeterministic(), expectedDeterministic.orElse(null));
        assertEquals(event.getErrorCode(), expectedErrorCode.orElse(null));
        if (event.getErrorMessage() == null) {
            assertFalse(expectedErrorMessageRegex.isPresent());
        }
        else {
            assertTrue(expectedErrorMessageRegex.isPresent());
            assertTrue(Pattern.compile(expectedErrorMessageRegex.get(), MULTILINE + DOTALL).matcher(event.getErrorMessage()).matches());
        }
    }
}
