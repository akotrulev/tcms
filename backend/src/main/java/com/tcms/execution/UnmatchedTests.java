package com.tcms.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads the unmatched tests back out of {@code ingestion_job.raw_payload}.
 *
 * <h2>Why they have to be read back at all</h2>
 *
 * <p>{@code IngestionJobView.unmatched} is not a column. It is computed once, while the report is
 * still in memory, by {@link IngestionService#unmatchedIn} — and after that request ends the only
 * record of it is the blob. Two callers need it afterwards and neither can recompute it from the
 * report, because the report is not stored (§117: {@code raw_payload} holds what was
 * <em>understood</em>, and {@code raw_storage_key} has no adapter):
 *
 * <ul>
 *   <li>{@link IngestionReadService} — the history's detail read, which is what the binder screen
 *       is a view of.</li>
 *   <li>{@link IngestionService}'s <strong>replay</strong> path, which served
 *       {@code unmatchedCount: 12} beside {@code unmatched: []} until this class existed. The retry
 *       is the caller that most needs the names: it is the second time one build has failed to bind
 *       the same tests.</li>
 * </ul>
 *
 * <h2>The rule is {@code unmatchedIn}'s, read backwards</h2>
 *
 * <p>A node is unmatched exactly when it carries no {@code caseId}, or carries
 * {@code recorded: false} — the second being a test whose case is real but is not in the run the
 * caller named. That is the same two populations {@code unmatchedIn} unions, and it is a
 * <strong>second implementation of one rule</strong>, which is the thing this codebase keeps
 * refusing. It is allowed here only because
 * {@code IngestionEndpointTest.History.theDetailAgreesWithThePost} asserts the two against each
 * other over one ingestion — the same standing this module gives R6's second policy in SQL (§111).
 *
 * <p><strong>{@code normalizedName} is re-derived, never stored.</strong>
 * {@link IngestedResult#normalize} is a pure function of the name the payload does store, and a
 * stored copy would be a second spelling of the one string that must agree exactly with the parser
 * or every learned mapping silently stops matching (§117).
 */
@Component
public class UnmatchedTests {

    private final ObjectMapper json;

    public UnmatchedTests(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Every test on this job that produced no result, in report order.
     *
     * <p><strong>A payload that cannot be read answers an empty list rather than throwing.</strong>
     * The blob is written by one writer and is not user input, so a malformed one is a defect — but
     * the reader is a <em>history</em> screen, and failing the whole list because one old row is
     * unreadable would take away the record of every other job. The counts are columns and are
     * served either way, so a caller can still see that names are missing.
     */
    List<UnmatchedTestView> of(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return List.of();
        }

        JsonNode results;
        try {
            results = json.readTree(rawPayload).path("results");
        } catch (JsonProcessingException malformed) {
            return List.of();
        }
        if (!results.isArray()) {
            return List.of();
        }

        List<UnmatchedTestView> unmatched = new ArrayList<>();
        for (JsonNode node : results) {
            if (landed(node)) {
                continue;
            }
            String name = node.path("name").asText(null);
            IngestedOutcome outcome = outcomeOf(node);
            if (name == null || outcome == null) {
                // A node the writer could not have produced. Skipped rather than served with a null
                // where the contract declares a required string.
                continue;
            }
            unmatched.add(
                    new UnmatchedTestView(name, IngestedResult.normalize(name), outcome));
        }
        return List.copyOf(unmatched);
    }

    /** See the class header — matched, and its case actually took the verdict. */
    private static boolean landed(JsonNode node) {
        return node.hasNonNull("caseId") && node.path("recorded").asBoolean(false);
    }

    /**
     * The outcome, or null when it names nothing this build knows.
     *
     * <p>By name rather than by ordinal, and unknown values are dropped rather than defaulted: an
     * enum constant removed in a later release would otherwise re-read every historical row of that
     * kind as whichever value happens to be first — and on this enum that is {@code PASSED}.
     */
    private static IngestedOutcome outcomeOf(JsonNode node) {
        String name = node.path("outcome").asText(null);
        if (name == null) {
            return null;
        }
        for (IngestedOutcome outcome : IngestedOutcome.values()) {
            if (outcome.name().equals(name)) {
                return outcome;
            }
        }
        return null;
    }
}
