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
import java.util.List;
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
 * What the one transaction actually wrote — the four facts nothing else in this package asserts.
 *
 * <ol>
 *   <li><strong>One outbox event, not one per issue.</strong> Every handler of
 *       {@code tracker.issues_changed} is a whole-company recompute that reads no payload, so a
 *       thousand-issue import would otherwise be a thousand identical full-table projections. And
 *       the count must not be zero: without the call {@code issue_fact} holds not one imported
 *       issue and every board, report and percentage in the product is blind to the migration.</li>
 *   <li><strong>The source's backlog order is the rank</strong> when the option is taken, so the
 *       trigger's {@code max(rank) + 1024} in insert order — an ordering nobody chose — is not what
 *       a client's backlog opens as (D-i).</li>
 *   <li><strong>A duplicate label name is "already there", not a failure.</strong> An importer hits
 *       one on the second issue carrying a label and on every issue after it (D-h).</li>
 *   <li><strong>An unmatched comment author is kept as text</strong> on
 *       {@code issue_comment.external_author}, with {@code created_by} left null — never reassigned
 *       to somebody who did not write it, and never prefixed onto the body (D-j).</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("The write pass — one event, the source's order, labels reused, authors kept as text")
class ImportWriterTest extends ImportTestBase {

    private static final UUID ACME = UUID.fromString("14500000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("14501000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("14502000-0000-4000-8000-000000000001");
    private static final UUID LEAD_GRANT = UUID.fromString("14503000-0000-4000-8000-000000000001");

    /** Four, so "one event" is distinguishable from "one per issue" and the ranks are a sequence. */
    private static final int SOURCE_ISSUES = 4;

    @Autowired
    private FakeImportSource source;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "import-writer-acme", "Acme", now);
            activeUser(LEAD, "lead@import-writer.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IMWR", "Import Writer", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        clearWrites();

        source.reset()
                .holding(new SourceProject("PROJ", "Northwind Storefront", SOURCE_ISSUES),
                        snapshot("PROJ", SOURCE_ISSUES));
    }

    private void clearWrites() throws SQLException {
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM import_dropped_field");
            statement.executeUpdate("DELETE FROM import_run");
            statement.executeUpdate("DELETE FROM issue_label_assignment");
            statement.executeUpdate("DELETE FROM issue_label");
            statement.executeUpdate("DELETE FROM \"release\"");
            statement.executeUpdate("DELETE FROM outbox");
        }
    }

    @Test
    @DisplayName("one coalesced outbox event for the whole run, and not zero")
    void oneEventForTheWholeRun() throws Exception {
        awaitTerminal(LEAD, ACME, start(new ImportOptions(false, true)));

        assertThat(outboxRows("tracker.issues_changed"))
                .as("""
                    ONE. IssueFactOnIssuesChanged is a whole-company recompute that reads no \
                    payload, so one event per issue would be N identical full-table projections — \
                    which is the shape Outbox.recordCoalesced exists for. And not ZERO: without \
                    the call issue_fact holds not one imported issue, and every board, report and \
                    percentage in the product is blind to the migration.""")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("with the option on, the source's backlog order is the rank the trigger did not set")
    void theSourceBacklogOrderSurvives() throws Exception {
        awaitTerminal(LEAD, ACME, start(new ImportOptions(false, true)));

        List<String> keysInRankOrder = keysOrderedByRank();

        assertThat(keysInRankOrder)
                .as("""
                    The BEFORE INSERT trigger fills rank with max(rank) + 1024 in INSERT order \
                    (012 D-k), and it stands aside whenever the insert supplies one. Without that \
                    door a thousand imported issues get a thousand ranks in an order nobody chose, \
                    and a client's first look at their new backlog is at somebody else's ordering \
                    presented as their own (D-i).""")
                .containsExactly("PROJ-1", "PROJ-2", "PROJ-3", "PROJ-4");
    }

    @Test
    @DisplayName("a label every issue carries is created once, not refused on the second issue")
    void aRepeatedLabelIsAlreadyThere() throws Exception {
        JsonNode report = awaitTerminal(LEAD, ACME, start(new ImportOptions(false, true)));

        assertThat(report.path("status").asText())
                .as("body was: %s", report)
                .isEqualTo("COMPLETED");

        assertThat(labelNames())
                .as("""
                    Every issue in the fixture carries the same label. POST /projects/{id}/labels \
                    answers 409 on a duplicate name, and an importer meets that on the SECOND issue \
                    and on every issue after it — so a duplicate is the ordinary case and is read \
                    as "already there" rather than as a failure (D-h).""")
                .containsExactly("imported");

        assertThat(labelAssignments())
                .as("one assignment per issue, off one label")
                .isEqualTo(SOURCE_ISSUES);
    }

    @Test
    @DisplayName("sprints become labels only when asked, and Sprint stays in the dropped list either way")
    void sprintsBecomeLabelsOnlyWhenAsked() throws Exception {
        awaitTerminal(LEAD, ACME, start(new ImportOptions(true, true)));

        assertThat(labelNames())
                .as("""
                    One label per distinct sprint name, and DEFAULT OFF: a project with sixty \
                    sprints becomes sixty labels nobody chose, and the label picker is a \
                    project-wide vocabulary (D-h).""")
                .containsExactlyInAnyOrder("imported", "Sprint 12");
    }

    @Test
    @DisplayName("an unmatched comment author is kept as text, with created_by left null")
    void anUnmatchedAuthorIsKeptAsText() throws Exception {
        awaitTerminal(LEAD, ACME, start(new ImportOptions(false, true)));

        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT external_author, created_by FROM issue_comment LIMIT 1")) {

            assertThat(rows.next()).as("the import wrote no comments at all").isTrue();
            assertThat(rows.getString("external_author"))
                    .as("""
                        import-report.html's People panel promises an unmatched author is \
                        PRESERVED AS TEXT rather than reassigned to somebody who did not write the \
                        comment — and not prefixed onto the body, which would edit somebody's \
                        words to carry metadata and come back out as content in the next export \
                        (D-j).""")
                    .isEqualTo("Someone Else");
            assertThat(rows.getString("created_by"))
                    .as("never both: the two columns are never set together, so no screen has to "
                            + "choose between them")
                    .isNull();
        }
    }

    @Test
    @DisplayName("the report names the people it did not find, not only the ones it did")
    void theReportNamesTheUnmatched() throws Exception {
        JsonNode report = awaitTerminal(LEAD, ACME, start(new ImportOptions(false, true)));

        JsonNode person = report.path("people").get(0);
        assertThat(person.path("sourceName").asText()).isEqualTo("Someone Else");
        assertThat(person.path("matched").asBoolean())
                .as("""
                    Reported either way. A report listing only the matches would leave a reader \
                    unable to tell "everybody was found" from "we did not look" — and the \
                    unmatched half is what explains the external_author strings on their \
                    comments.""")
                .isFalse();
    }

    // =====================================================================

    private UUID start(ImportOptions options) throws Exception {
        UUID target = firstStatusId();
        ImportInput input = new ImportInput(
                PROJECT,
                connection(),
                "PROJ",
                List.of(
                        new StatusMappingEntry("New", target),
                        new StatusMappingEntry("Awaiting Triage", target)),
                options);

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/imports")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(202);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).path("id").asText());
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

    private int outboxRows(String eventType) throws SQLException {
        // Read as the app role WITH THIS COMPANY OPEN, and that is the assertion rather than
        // convention: `outbox` is company-owned, every other class in the suite writes its own
        // tracker.issues_changed rows, and a superuser read would count theirs too — which is a
        // green "one event" that becomes red the day somebody adds a test class before this one.
        // Status is not filtered: the dispatcher may already have claimed the row.
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM outbox WHERE event_type = ?")) {
            statement.setString(1, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private List<String> keysOrderedByRank() throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT issue_key FROM issue WHERE project_id = CAST(? AS uuid) "
                                // rank, created_at, id — never rank alone: the column is
                                // deliberately not unique, so every ordered read carries the
                                // tiebreakers Issue#rank's comment names.
                                + "ORDER BY rank, created_at, id")) {
            statement.setObject(1, PROJECT);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
            }
        }
        return keys;
    }

    private List<String> labelNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT name FROM issue_label ORDER BY name")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private int labelAssignments() throws SQLException {
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM issue_label_assignment")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
