package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The module's stated done-when, and its two refusals.
 *
 * <h2>{@code PROJ-4812} is still {@code PROJ-4812} — story J3</h2>
 *
 * <p>That is the one sentence somebody deciding whether to leave Jira actually asks about: every
 * commit message, every bookmark and every link in a year of their own documents says
 * {@code PROJ-4812}, and a migration that renumbers them breaks all of it silently. D-d is how it
 * is kept — two columns, one holding the source's key verbatim and one holding <em>this</em>
 * project's counter — and {@link #originalKeysSurvive} is the assertion that both halves happened.
 *
 * <h2>A failed run leaves the project byte-identical, and that is the strongest claim here</h2>
 *
 * <p>{@link #aFailedRunWritesNothing} drives {@code FakeImportSource.failAfter} and compares every
 * company-owned table before and after, the same instrument
 * {@code ImportPreviewWritesNothingTest} uses. It is the reason D-b chose one transaction over
 * batch commits: a batched failure would leave <em>part</em> of a Jira project in the customer's
 * new tracker, with no way to tell which part, at the exact moment they are being asked to trust
 * the migration.
 */
@AutoConfigureMockMvc
@DisplayName("An import run — the keys survive it, and the two refusals refuse")
class ImportRunEndpointTest extends ImportTestBase {

    private static final UUID ACME = UUID.fromString("14200000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("14201000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("14202000-0000-4000-8000-000000000001");
    private static final UUID LEAD_GRANT = UUID.fromString("14203000-0000-4000-8000-000000000001");

    /** Enough for a failure to land mid-read; small enough that the suite stays fast. */
    private static final int SOURCE_ISSUES = 6;

    @Autowired
    private FakeImportSource source;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "import-run-acme", "Acme", now);
            activeUser(LEAD, "lead@import-run.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IMRN", "Import Run", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        clearImports();

        source.reset()
                .holding(new SourceProject("PROJ", "Northwind Storefront", SOURCE_ISSUES),
                        snapshot("PROJ", SOURCE_ISSUES));
    }

    /**
     * Empties this class's own tables per method.
     *
     * <p>Per method rather than per class for {@code clearIssues}' reason: {@link #aSecondImportIs409}
     * depends on there being no completed run, and would otherwise pass or fail on which method the
     * runner happened to put before it.
     */
    private void clearImports() throws SQLException {
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM import_dropped_field");
            statement.executeUpdate("DELETE FROM import_run");
            statement.executeUpdate("DELETE FROM issue_label_assignment");
            statement.executeUpdate("DELETE FROM issue_label");
            statement.executeUpdate("DELETE FROM \"release\"");
            // The project's issue-key counter too, and this is the assertion rather than tidiness:
            // originalKeysSurvive claims key_seq is 1, and a method that ran before it and
            // completed a run would have advanced the counter — so the claim would pass or fail on
            // the order the runner happened to choose.
            statement.executeUpdate(
                    "UPDATE project_key_sequence SET next_value = 1 WHERE project_id = '"
                            + PROJECT + "'");
        }
    }

    @Test
    @DisplayName("PROJ-4812 is still PROJ-4812, and key_seq is the project's counter — story J3")
    void originalKeysSurvive() throws Exception {
        UUID runId = startAndAwait();

        assertThat(keyOf(1))
                .as("the source's key, verbatim (D-d). Renumbering it would break every commit "
                        + "message, bookmark and document a client wrote over years on the system "
                        + "they are leaving — silently, and with no way back.")
                .isEqualTo("PROJ-1");

        assertThat(keySeqOf("PROJ-1"))
                .as("""
                    key_seq comes from the PROJECT's counter, not from the source's number. Using \
                    the source's would push the project's counter past every unused value below \
                    it, or collide with a locally created key if it did not — and key_seq is the \
                    only column that may order issue keys anywhere in the product.""")
                .isEqualTo(1L);

        JsonNode report = report(LEAD, ACME, runId);
        assertThat(report.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(report.path("issuesImported").asInt()).isEqualTo(SOURCE_ISSUES);
        assertThat(report.path("startedBy").path("displayName").asText())
                .as("named through the active company's membership — plan §6u's rule, which UserRef "
                        + "owns and this module does not restate")
                .isEqualTo("Leonora Lead");
    }

    @Test
    @DisplayName("a source status nobody mapped is a 400 naming it, before anything is accepted")
    void anUnmappedStatusIsRefused() throws Exception {
        // The source's WORKFLOW defines a status no issue is currently in, which is the ordinary
        // state of a real Jira and the case a mapping built from the preview's rows would miss.
        source.alsoDefining("Blocked");

        JsonNode problem = json.readTree(start(mappingForEveryStatus(), 400));

        assertThat(problem.path("errors").path("statusMapping").get(0).asText())
                .as("""
                    Named, and refused HERE rather than by a run that dies four minutes in. An \
                    unmapped status would silently become a default and put its issues in the \
                    wrong column of a board, which is what import-mapping.html's disabled Confirm \
                    exists to prevent — and the server does not trust the client to have done it.""")
                .contains("Blocked");

        assertThat(runRows()).as("nothing was accepted, so nothing is polling").isZero();
    }

    @Test
    @DisplayName("a second import of the same source project is a 409, not a second PROJ-1")
    void aSecondImportIs409() throws Exception {
        startAndAwait();

        JsonNode problem = json.readTree(start(mappingForEveryStatus(), 409));

        assertThat(problem.path("detail").asText())
                .as("""
                    D-m: there is no delta import and no reconciliation. A second run would try to \
                    create a second PROJ-1, which the company-wide uq_issue_key refuses anyway — \
                    this turns that into a sentence a person can read instead of a constraint \
                    violation at minute four.""")
                .contains("already been imported")
                .contains("PROJ");
    }

    @Test
    @DisplayName("a run that fails mid-read ends FAILED and leaves the project byte-identical")
    void aFailedRunWritesNothing() throws Exception {
        Map<String, String> before = snapshotOfEveryCompanyOwnedTable();

        // Mid-READ and not mid-write: the failure lands after the expensive part and before
        // anything is stored, which is the state D-b's two passes exist to make safe.
        source.failAfter(SOURCE_ISSUES / 2);

        UUID runId = startAndAwait();
        JsonNode report = report(LEAD, ACME, runId);

        assertThat(report.path("status").asText()).isEqualTo("FAILED");
        assertThat(report.path("failureReason").asText())
                .as("written for the person watching, and it says what happened to THEIR project "
                        + "rather than that a process died")
                .contains("the project is unchanged");
        assertThat(report.path("issuesImported").asInt())
                .as("every count stays at zero because nothing was written; a non-zero one here "
                        + "would be the report claiming otherwise")
                .isZero();

        Map<String, String> after = snapshotOfEveryCompanyOwnedTable();
        // The run row and its dropped-field rows are the ONE legitimate difference: the row was
        // committed before the source was dialled, which is what makes the operation pollable and
        // what gives ImportReaper something to find. Everything else must be identical.
        after.remove("import_run");
        before.remove("import_run");

        assertThat(after)
                .as("""
                    This is the strongest statement this module makes, and the whole reason D-b \
                    chose one transaction over batch commits: either the whole project arrived or \
                    nothing did. A batched failure would leave PART of a Jira project in the \
                    customer's new tracker with no way to tell which part.""")
                .isEqualTo(before);
    }

    // =====================================================================

    private UUID startAndAwait() throws Exception {
        JsonNode accepted = json.readTree(start(mappingForEveryStatus(), 202));
        UUID runId = UUID.fromString(accepted.path("id").asText());
        assertThat(accepted.path("status").asText())
                .as("202 hands back a row to poll, not a finished report")
                .isEqualTo("RUNNING");
        awaitTerminal(LEAD, ACME, runId);
        return runId;
    }

    private String start(List<StatusMappingEntry> mapping, int expected) throws Exception {
        ImportInput input = new ImportInput(
                PROJECT, connection(), "PROJ", mapping, ImportOptions.defaults());

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/imports")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return result.getResponse().getContentAsString();
    }

    /**
     * Every status the fixture's snapshot uses, onto this project's first status.
     *
     * <p>Onto ONE target on purpose: what the mapping step is for is that the person chooses, and a
     * fixture that spread them over several would be asserting about a choice nobody made. The
     * mapping's <em>coverage</em> is what the server checks, and this covers it.
     */
    private List<StatusMappingEntry> mappingForEveryStatus() throws SQLException {
        UUID target = firstStatusId();
        return List.of(
                new StatusMappingEntry("Backlog", target),
                new StatusMappingEntry("Awaiting Triage", target));
    }

    private UUID firstStatusId() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM issue_status WHERE project_id = CAST(? AS uuid) "
                                + "ORDER BY position LIMIT 1")) {
            statement.setObject(1, PROJECT);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private String keyOf(long keySeq) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT issue_key FROM issue WHERE project_id = CAST(? AS uuid) "
                                + "AND key_seq = ?")) {
            statement.setObject(1, PROJECT);
            statement.setLong(2, keySeq);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no issue at key_seq %s", keySeq).isTrue();
                return rows.getString(1);
            }
        }
    }

    private long keySeqOf(String issueKey) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT key_seq FROM issue WHERE issue_key = ?")) {
            statement.setString(1, issueKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no issue keyed %s", issueKey).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private int runRows() throws SQLException {
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM import_run")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Every company-owned table, hashed — {@code ImportPreviewWritesNothingTest}'s instrument.
     *
     * <p>The table list comes from {@code information_schema} rather than from an array here, so
     * the same rule that decides what RLS protects decides what this compares, and a table added by
     * a later migration is covered without anybody remembering. Read as the container superuser,
     * because reading as {@code tcms_app} would compare zero rows to zero rows for every table
     * outside the open company and report success by fail-closed silence.
     */
    private static Map<String, String> snapshotOfEveryCompanyOwnedTable() throws SQLException {
        Map<String, String> digests = new LinkedHashMap<>();

        try (Connection connection = superuserConnection()) {
            for (String table : companyOwnedTables(connection)) {
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery(
                                "SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), "
                                        + "'empty') FROM \"" + table + "\" t")) {
                    rows.next();
                    digests.put(table, rows.getString(1));
                }
            }
        }
        assertThat(digests)
                .as("information_schema returned no company-owned tables at all — the query has "
                        + "stopped finding them, and every comparison here is vacuous")
                .hasSizeGreaterThan(50);
        return digests;
    }

    private static List<String> companyOwnedTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'company_id'
                ORDER BY table_name
                """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }
}
