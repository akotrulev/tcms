package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The import endpoints' shared fixture — one {@link FakeImportSource}, and nothing else.
 *
 * <p>No ids, no company slugs and no project keys, exactly as {@link TrackerEndpointTestBase}'s
 * header requires: a slug shared between two classes is green alone and red in the suite, and the
 * failure names neither class.
 *
 * <p>The fake is registered here rather than per class because registering it changes the Spring
 * context's cache key, and one shared context for the import classes is cheaper than one each.
 * <strong>No test in this package may reach a real source</strong> — the fake outranks
 * {@code JiraImportSource} by {@code Ordered}, so {@link ImportSources} picks it for every provider.
 */
@Import(ImportTestBase.Sources.class)
public abstract class ImportTestBase extends TrackerEndpointTestBase {

    @TestConfiguration
    static class Sources {

        @Bean
        FakeImportSource fakeImportSource() {
            return new FakeImportSource();
        }
    }

    /** A Jira Cloud connection whose site is public, so {@link ImportUrlGuard} allows it. */
    protected static ImportConnection connection() {
        return new ImportConnection(
                ImportProvider.JIRA_CLOUD,
                "https://northwind.atlassian.net",
                "ops@northwind.test",
                "not-a-real-token");
    }

    /**
     * Polls the report until the run leaves {@code RUNNING}, or fails saying it never did.
     *
     * <p>Polling rather than a latch, because that is <strong>what a client does</strong>: the
     * contract's 202 hands back a row and an address, and a test that reached inside the executor
     * would be asserting about a mechanism instead of about the operation. The deadline is generous
     * against a cold Testcontainers Postgres and short enough that a run which never terminates
     * fails the class in twenty seconds rather than hanging the suite.
     *
     * @return the terminal report
     */
    protected JsonNode awaitTerminal(UUID actor, UUID companyId, UUID runId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = report(actor, companyId, runId);
            if (!"RUNNING".equals(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the run never left RUNNING; last report was " + last);
    }

    /** {@code GET /imports/{importRunId}}, asserting 200 — the poll target and the report. */
    protected JsonNode report(UUID actor, UUID companyId, UUID runId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/imports/" + runId)
                        .header("Authorization", "Bearer " + tokenFor(actor, companyId)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * A snapshot with one issue of each shape the preview has to say something about.
     *
     * <p>Small on purpose: what the preview is judged on is the mapping and the dropped-field list,
     * both of which are per distinct value rather than per issue, so a thousand rows would slow the
     * suite and prove nothing extra.
     *
     * <p>The two statuses are chosen against {@code tcms_seed_project_defaults}: {@code Backlog} is
     * one of the six it creates and {@code Awaiting Triage} is not, so one row comes back matched
     * and the other unmatched. A pair that both matched would leave the suggestion's null arm — the
     * one the mapping step exists for — untested.
     */
    protected static SourceProjectSnapshot snapshot(String keyPrefix, int issueCount) {
        List<SourceIssue> issues = new ArrayList<>();
        for (int index = 1; index <= issueCount; index++) {
            issues.add(new SourceIssue(
                    keyPrefix + "-" + index,
                    index % 2 == 0 ? "Bug" : "Story",
                    index % 2 == 0 ? "Awaiting Triage" : "Backlog",
                    "Issue " + index,
                    "As read from the source.",
                    null,
                    null,
                    null,
                    null,
                    List.of("imported"),
                    List.of("1.4.0"),
                    List.of("Sprint 12"),
                    index,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(new SourceComment(
                            "Someone Else",
                            "someone@northwind.test",
                            "A comment the import keeps.",
                            Instant.parse("2026-01-02T00:00:00Z")))));
        }

        return new SourceProjectSnapshot(
                List.copyOf(issues),
                List.of(
                        new SourceStatus("Backlog", "new", (issueCount + 1) / 2),
                        new SourceStatus("Awaiting Triage", "indeterminate", issueCount / 2)),
                List.of(
                        new SourceType("Story", false, (issueCount + 1) / 2),
                        new SourceType("Bug", false, issueCount / 2)),
                List.of(new SourceVersion("1.4.0", false)),
                List.of(new SourcePerson("Someone Else", "someone@northwind.test")),
                List.of(
                        new SourceField("Story Points", "number", false, issueCount),
                        new SourceField("Squad", "option", true, issueCount)),
                42);
    }
}
