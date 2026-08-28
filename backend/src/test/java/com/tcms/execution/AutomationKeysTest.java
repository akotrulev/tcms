package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.AutomationKeys;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract between the {@code .feature} import and the Cucumber ingestion — one spelling of
 * {@code automationKey}, checked from the ingestion side.
 *
 * <h2>Why this test is in {@code execution} and not beside the import</h2>
 *
 * <p>{@link CucumberReportParser} is package-private in this module and {@code repository} may not
 * import it, which is exactly the reason the format had to move to {@link AutomationKeys} in the
 * first place ({@code integrations.md} X2). So the assertion has to be made from here: parse the
 * report a CI job would actually post, and check that every key the parser builds is one
 * {@link AutomationKeys#cucumber} would have written.
 *
 * <p><strong>The failure this exists to catch is silent and total.</strong> Nothing throws when the
 * two spellings drift — no error, no log line, no 4xx. Every ingested result simply stops matching
 * its case, {@code unmatched_count} equals the number of tests that ran, and the run looks like a
 * suite nobody has mapped yet. That is the sentence {@code IngestedResult#fullName()}'s javadoc has
 * carried since §6c; this is the check under it.
 *
 * <p>The fixture is {@code gherkin/import/report.json} — the report for the three feature files
 * {@code FeatureImportTest} imports, so the two tests are looking at the same names from opposite
 * ends. Change one and change the other.
 */
@DisplayName("automationKey — one spelling, from the ingestion's end")
class AutomationKeysTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CucumberReportParser cucumber = new CucumberReportParser(JSON);

    /** The keys the import writes for {@code orders/payments/rules.feature}, spelled by hand. */
    private static final List<String> EXPECTED = List.of(
            "Orders#Cancel an order",
            "Orders#Refund an order",
            "Payments#Pay by card",
            "Shipping#A large order ships free");

    @Test
    @DisplayName("every key the report parser builds is the one AutomationKeys would have written")
    void theParserAgreesWithTheSpeller() throws IOException {
        List<IngestedResult> results = cucumber.parse(report());

        assertThat(results).isNotEmpty();
        for (IngestedResult result : results) {
            assertThat(result.fullName())
                    .as("the parser and the import must spell one key")
                    .isEqualTo(AutomationKeys.cucumber(result.className(), result.name()));
        }
    }

    @Test
    @DisplayName("and those keys are exactly the ones the import fixture writes")
    void theKeysAreTheImportsKeys() throws IOException {
        // Hand-written rather than derived, on purpose: deriving both sides from the same call
        // would assert that a method equals itself. These four strings are what is stored in
        // test_case.automation_key after FeatureImportTest's APPLY, and a change to either side
        // has to be made in two places.
        assertThat(cucumber.parse(report()).stream().map(IngestedResult::fullName).toList())
                .containsExactlyElementsOf(EXPECTED);
    }

    @Test
    @DisplayName("a dialect with no class joins nothing — the key is the test's own name")
    void aClasslessResultIsJustItsName() {
        assertThat(new IngestedResult(null, "standalone", IngestedOutcome.PASSED, null, null)
                .fullName())
                .isEqualTo("standalone");
        assertThat(new IngestedResult("  ", "standalone", IngestedOutcome.PASSED, null, null)
                .fullName())
                .isEqualTo("standalone");
    }

    private static String report() throws IOException {
        try (InputStream stream = AutomationKeysTest.class
                .getResourceAsStream("/gherkin/import/report.json")) {
            assertThat(stream).as("the shared report fixture must be on the classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
