package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code issue_fact} — the projection every downstream module reads, and its one writer.
 *
 * <h2>The failures this class exists to catch, and why the first one is silent</h2>
 *
 * <ol>
 *   <li><strong>A second writer.</strong> Scope §3 names it: the moment {@code time} or
 *       {@code billing} writes this table, {@code tracker} stops being the authority on issue state
 *       and the two disagree <em>silently</em> — nothing breaks, nothing reddens, and a report is
 *       just wrong. {@link #nothingOutsideTrackerWritesIssueFact} is a computed check for exactly
 *       that reason: a schema-wide invariant needs a script, not a careful reading.</li>
 *   <li><strong>An epic's {@code status_kind} copied rather than derived.</strong> The obvious
 *       implementation copies {@code issue_status.kind}, and then a report and the epic's own screen
 *       answer differently about the same epic — D31's disagreement, one table over.</li>
 *   <li><strong>{@code done_at} rewritten by a reopen.</strong> Every lead time computed from the
 *       column silently lengthens, and nothing fails.</li>
 *   <li><strong>A deleted issue removed from the projection</strong> rather than mirrored, which
 *       makes reporting over a closed period change when somebody tidies the backlog.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("issue_fact — one writer, a derived epic status, and a done_at that survives a reopen")
class IssueFactProjectionTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("2c000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("2c001000-0000-4000-8000-000000000001");
    private static final UUID LEAD = UUID.fromString("2c002000-0000-4000-8000-000000000001");

    /** {@code INSERT INTO issue_fact} or {@code UPDATE issue_fact}, in any main source file. */
    private static final Pattern WRITES_ISSUE_FACT =
            Pattern.compile("(?s).*\\b(INSERT\\s+INTO|UPDATE)\\s+issue_fact\\b.*");

    @Autowired
    private IssueFactProjection projection;

    private static final UUID LEAD_GRANT =
            UUID.fromString("2c003000-0000-4000-8000-000000000001");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "tracker-fact-acme", "Acme", now);
            activeUser(LEAD, "lead@tracker-fact.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "FCTA", "Fact Project", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
    }

    @Test
    void nothingOutsideTrackerWritesIssueFact() throws IOException {
        // A computed check, not a careful reading — CLAUDE.md's rule, and the one that found five
        // join tables without company_id when reading V1 top to bottom had not.
        Path main = Path.of("src/main/java");
        Set<String> writers;
        try (Stream<Path> sources = Files.walk(main)) {
            writers = sources
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(IssueFactProjectionTest::writesIssueFact)
                    .map(file -> main.relativize(file).toString())
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }

        assertThat(writers)
                .as("scope §3: issue_fact has one writer. A downstream module writing it makes "
                        + "tracker stop being the authority on issue state, silently — nothing "
                        + "breaks, no test reddens, and a report is just wrong")
                .containsExactly("com/tcms/tracker/IssueFactProjection.java");
    }

    @Test
    void everyLiveIssueIsProjectedWithItsFourTimestamps() throws Exception {
        String key = create("Story", "Passkeys", null);
        refresh();

        Map<String, Object> fact = factFor(key);
        assertThat(fact.get("issue_key")).isEqualTo(key);
        assertThat(fact.get("title")).isEqualTo("Passkeys");
        assertThat(fact.get("type_name")).isEqualTo("Story");
        assertThat(fact.get("type_level")).isEqualTo("STORY");
        assertThat(fact.get("counts_toward_completion")).isEqualTo(true);
        assertThat(fact.get("created_at")).isNotNull();
        assertThat(fact.get("status_entered_at")).isNotNull();
        assertThat(fact.get("done_at")).isNull();
        // M3's column, landed now because it cannot be backfilled: "scope added after the release
        // was created" leaves no other trace than the moment of assignment (009 review R12).
        assertThat(fact.get("release_assigned_at")).isNull();
    }

    @Test
    void anEpicsStatusKindIsDerivedFromItsStoriesAndNotCopied() throws Exception {
        // The epic sits in the project's FIRST status for its whole life — nothing ever moves it —
        // so a projection that copied status_id would read NOT_STARTED here for ever. This is the
        // assertion that tells the two implementations apart.
        String epic = create("Epic", "Authentication", null);
        String story = create("Story", "Passkeys", epic);
        moveThrough(story, "In Progress");
        refresh();

        assertThat(factFor(epic).get("status_kind")).isEqualTo("IN_PROGRESS");

        moveThrough(story, "In Review", "Done");
        refresh();

        assertThat(factFor(epic).get("status_kind")).isEqualTo("DONE");
        // And the name is one of THIS project's own, never a hardcoded word (story B1).
        assertThat(factFor(epic).get("status_name")).isEqualTo("Done");
    }

    @Test
    void anEmptyEpicIsNotStartedRatherThanVacuouslyDone() throws Exception {
        String epic = create("Epic", "Nothing under it yet", null);
        refresh();

        assertThat(factFor(epic).get("status_kind")).isEqualTo("NOT_STARTED");
    }

    @Test
    void theProjectionAndTheEndpointAgreeAboutAnEpic() throws Exception {
        // Two spellings of D31 — EpicStatus.of in Java and a CASE in SQL — and this is the only
        // thing that can catch them drifting apart. Nothing else compares them.
        String epic = create("Epic", "Authentication", null);
        String story = create("Story", "Passkeys", epic);
        moveThrough(story, "In Progress");
        refresh();

        assertThat(read(epic).get("status").get("kind").asText())
                .isEqualTo(factFor(epic).get("status_kind"));
    }

    @Test
    void aTasksEpicIsItsGrandparent() throws Exception {
        String epic = create("Epic", "Authentication", null);
        String story = create("Story", "Passkeys", epic);
        String task = create("Sub-task", "Draft the flow", story);
        refresh();

        assertThat(factFor(task).get("epic_id")).isEqualTo(factFor(epic).get("issue_id"));
        assertThat(factFor(task).get("parent_id")).isEqualTo(factFor(story).get("issue_id"));
        // A story with no epic has no epic_id, and that is not the same as "not looked up".
        String orphan = create("Story", "Unscheduled", null);
        refresh();
        assertThat(factFor(orphan).get("epic_id")).isNull();
    }

    @Test
    void doneAtSurvivesAReopen() throws Exception {
        String story = create("Story", "Passkeys", null);
        moveThrough(story, "In Progress", "In Review", "Done");
        refresh();
        Object firstDone = factFor(story).get("done_at");

        moveThrough(story, "In Progress", "In Review", "Done");
        refresh();

        assertThat(firstDone).isNotNull();
        assertThat(factFor(story).get("done_at")).isEqualTo(firstDone);
    }

    @Test
    void aDeletedIssueIsMirroredRatherThanRemoved() throws Exception {
        // Reporting over a closed period must not change when somebody tidies the backlog.
        String story = create("Story", "Mistake", null);
        refresh();
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/issues/" + story)
                .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)));
        refresh();

        assertThat(factFor(story).get("deleted_at")).isNotNull();
    }

    @Test
    void aSecondRefreshRewritesRatherThanDuplicating() throws Exception {
        // The outbox is at-least-once, so this is what makes a repeated delivery cost a repeated
        // statement rather than a wrong answer.
        String story = create("Story", "Passkeys", null);
        refresh();
        refresh();

        assertThat(factCount()).isEqualTo(1);
        assertThat(factFor(story)).isNotNull();
    }

    // =====================================================================

    private static boolean writesIssueFact(Path file) {
        try {
            return WRITES_ISSUE_FACT.matcher(Files.readString(file)).matches();
        } catch (IOException unreadable) {
            throw new AssertionError("src/main is this rule's corpus", unreadable);
        }
    }

    /**
     * Runs the projection the way the outbox dispatcher does.
     *
     * <p><strong>The company is opened AROUND the transaction, never inside it.</strong>
     * {@code CompanyAwareTransactionManager} sets {@code app.current_company} in {@code doBegin},
     * so a context opened after the transaction started arrives too late: the GUC is unset, RLS
     * matches nothing, and the {@code INSERT … SELECT} writes ZERO ROWS and reports success. That
     * is the silent shape CLAUDE.md names — no error, no exception, just an empty projection.
     * {@code refresh()} is itself {@code @Transactional}, so it opens its own.
     */
    private void refresh() {
        inCompany(ACME, () -> projection.refresh());
    }

    private long factCount() throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement("SELECT count(*) FROM issue_fact");
                var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private Map<String, Object> factFor(String issueKey) throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT * FROM issue_fact WHERE issue_key = ?")) {
            statement.setString(1, issueKey);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no issue_fact row for %s", issueKey).isTrue();
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                var meta = rows.getMetaData();
                for (int column = 1; column <= meta.getColumnCount(); column++) {
                    row.put(meta.getColumnLabel(column), rows.getObject(column));
                }
                return row;
            }
        }
    }

    private String create(String typeName, String title, String parentKey) throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("projectId", PROJECT.toString());
        payload.put("typeId", typeNamed(typeName).toString());
        payload.put("title", title);
        payload.put("parentKey", parentKey);

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void moveThrough(String key, String... statusNames) throws Exception {
        for (String statusName : statusNames) {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/issues/" + key + "/transitions")
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("statusId", statusNamed(statusName).toString()))))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("move %s to %s: %s", key, statusName,
                            result.getResponse().getContentAsString())
                    .isEqualTo(200);
        }
    }

    private JsonNode read(String key) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode workflow() throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private UUID typeNamed(String name) throws Exception {
        return idOf(workflow().get("types"), name);
    }

    private UUID statusNamed(String name) throws Exception {
        return idOf(workflow().get("statuses"), name);
    }

    private static UUID idOf(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (row.get("name").asText().equals(name)) {
                return UUID.fromString(row.get("id").asText());
            }
        }
        throw new AssertionError("no row called " + name);
    }
}
