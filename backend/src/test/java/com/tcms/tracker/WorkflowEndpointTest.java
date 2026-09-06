package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcms.identity.Role;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code /projects/{projectId}/workflow} — story B1's settings screen.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A project seeded with no workflow at all.</strong> Story B1's second criterion is
 *       that a sensible default is already there, and it is the reason the seed lives in
 *       {@code tcms_seed_project_defaults} rather than behind an outbox event whose floor is a
 *       minute (D-b). A project without statuses cannot hold its first issue.</li>
 *   <li><strong>A reorder that leaves two statuses claiming one position.</strong> Exactly what a
 *       per-status PATCH generates, which is why the endpoint is a whole-document PUT.</li>
 *   <li><strong>A status removed out from under the issues holding it.</strong> There must be no
 *       window in which an issue points at a status that is gone (B1's third criterion), and the
 *       PUT — which carries no destination — must refuse rather than choose one.</li>
 *   <li><strong>A project left with no {@code DONE}-kind status.</strong> Every percentage in the
 *       product is computed from that kind (D8), so the screens would render a percentage of
 *       nothing.</li>
 *   <li><strong>The read gated on the write's key.</strong> Every screen with a status picker needs
 *       this document, including the read-only ones.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Project workflow — seeded, replaced whole, and never left without a Done")
class WorkflowEndpointTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("1f000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("1f001000-0000-4000-8000-000000000001");

    /** Holds {@code issue:workflow}. */
    private static final UUID LEAD = UUID.fromString("1f002000-0000-4000-8000-000000000001");
    /** Holds {@code issue:read} and not {@code issue:workflow} — §A3's Test Engineer line, from below. */
    private static final UUID VIEWER = UUID.fromString("1f002000-0000-4000-8000-000000000002");

    private static final UUID LEAD_GRANT =
            UUID.fromString("1f003000-0000-4000-8000-000000000001");
    private static final UUID VIEWER_GRANT =
            UUID.fromString("1f003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "tracker-flow-acme", "Acme", now);
            activeUser(LEAD, "lead@tracker-flow.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@tracker-flow.test", "V. Viewer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "FLWA", "Flow Project", now);
            member(ACME, LEAD, "Leonora Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        grantOnProject(VIEWER_GRANT, ACME, Role.VIEWER_ID, VIEWER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        restoreWorkflow();
    }

    /**
     * And again afterwards, because {@code WorkflowSeedTest} asserts that <strong>every</strong>
     * seeded project in the database has the six default statuses in order — and half the tests
     * here rename, reorder or remove one. Restoring only on the way in is green in this class and
     * red in that one, which is the cross-class shape §19 keeps producing.
     */
    @AfterEach
    void putTheWorkflowBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreWorkflow();
    }

    @Test
    void aProjectArrivesWithAWorkflowNobodyHadToConfigure() throws Exception {
        JsonNode workflow = workflow(LEAD);

        assertThat(names(workflow.get("statuses")))
                .containsExactly("New", "Refining", "Ready to develop", "In progress", "In review",
                        "Ready for test", "In test", "Ready to deploy", "Done", "Rework",
                        "Blocked");
        assertThat(names(workflow.get("types")))
                .containsExactly("Epic", "Story", "Task", "Bug", "Spike", "Sub-task");
        assertThat(workflow.get("transitions")).isNotEmpty();
    }

    @Test
    void theSeededGateIsThatOnlyInReviewReachesDone() throws Exception {
        // settings-workflow.html states it in words; this is the same claim as rows. It is what
        // makes issue_transition an allow-list rather than a table nothing consults.
        //
        // ~~Only In Review reaches Done, the review gate.~~ → V13 moved the gate to the END of the
        // line: Ready to deploy is the tester's last word and the drag to Done is the PO's deploy
        // confirmation (021 D-l), which is also the figure release readiness reads.
        JsonNode workflow = workflow(LEAD);
        UUID done = idOf(workflow.get("statuses"), "Done");

        List<String> reachDone = new java.util.ArrayList<>();
        workflow.get("transitions").forEach(move -> {
            if (move.get("toStatusId").asText().equals(done.toString())) {
                reachDone.add(nameOf(workflow.get("statuses"), move.get("fromStatusId").asText()));
            }
        });

        assertThat(reachDone).containsExactly("Ready to deploy");
    }

    @Test
    void aViewerReadsTheWorkflowAndCannotReplaceIt() throws Exception {
        // Gated on visibility rather than on issue:workflow, because every screen that renders a
        // status picker needs this document — including the read-only ones.
        assertThat(names(workflow(VIEWER).get("statuses"))).isNotEmpty();

        assertThat(put(VIEWER, workflow(LEAD)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void reorderingIsOneStatementAboutTheSet() throws Exception {
        JsonNode workflow = workflow(LEAD);
        ArrayNode statuses = (ArrayNode) workflow.get("statuses");
        JsonNode first = statuses.get(0);
        statuses.remove(0);
        statuses.add(first);

        JsonNode stored = json.readTree(
                put(LEAD, workflow).andReturn().getResponse().getContentAsString());

        assertThat(names(stored.get("statuses")))
                .containsExactly("Refining", "Ready to develop", "In progress", "In review",
                        "Ready for test", "In test", "Ready to deploy", "Done", "Rework",
                        "Blocked", "New");
        // Positions renumbered from zero with no duplicate — the state a per-status PATCH would
        // have passed through and this shape cannot express.
        List<Integer> positions = new java.util.ArrayList<>();
        stored.get("statuses").forEach(status -> positions.add(status.get("position").asInt()));
        assertThat(positions).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void aRenamedStatusKeepsItsIdAndTheIssuesInIt() throws Exception {
        JsonNode workflow = workflow(LEAD);
        UUID first = idOf(workflow.get("statuses"), "New");
        String issueKey = story("Something in the backlog");

        ((ObjectNode) workflow.get("statuses").get(0)).put("name", "Icebox");
        put(LEAD, workflow);

        JsonNode reread = read(issueKey);
        assertThat(reread.get("status").get("id").asText()).isEqualTo(first.toString());
        assertThat(reread.get("status").get("name").asText()).isEqualTo("Icebox");
    }

    @Test
    void thePutRefusesToDropAStatusThatStillHoldsWork() throws Exception {
        // It carries no destination, so choosing one would be B1's third criterion answered by
        // guessing. The DELETE below is the operation that asks.
        story("Something in the backlog");
        JsonNode workflow = workflow(LEAD);
        ((ArrayNode) workflow.get("statuses")).remove(0);

        MvcResult refused = put(LEAD, workflow).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("in New");
    }

    @Test
    void anEmptyStatusMayBeDroppedByThePut() throws Exception {
        JsonNode workflow = workflow(LEAD);
        int blocked = indexOf(workflow.get("statuses"), "Blocked");
        ((ArrayNode) workflow.get("statuses")).remove(blocked);

        JsonNode stored = json.readTree(
                put(LEAD, workflow).andReturn().getResponse().getContentAsString());

        assertThat(names(stored.get("statuses"))).doesNotContain("Blocked");
    }

    @Test
    void aPutThatWouldLeaveNoDoneStatusIsRefused() throws Exception {
        JsonNode workflow = workflow(LEAD);
        ((ArrayNode) workflow.get("statuses")).remove(indexOf(workflow.get("statuses"), "Done"));

        MvcResult refused = put(LEAD, workflow).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains("Keep one status that means done");
    }

    @Test
    void removingAStatusMovesItsIssuesInTheSameTransaction() throws Exception {
        String issueKey = story("Something in the backlog");
        JsonNode workflow = workflow(LEAD);
        UUID first = idOf(workflow.get("statuses"), "New");
        UUID ready = idOf(workflow.get("statuses"), "Ready to develop");

        MvcResult removed = mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + PROJECT + "/workflow/statuses/" + first)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("reassignToStatusId", ready.toString()))))
                .andReturn();

        assertThat(removed.getResponse().getStatus()).isEqualTo(204);
        assertThat(read(issueKey).get("status").get("name").asText())
                .isEqualTo("Ready to develop");
        assertThat(names(workflow(LEAD).get("statuses"))).doesNotContain("New");
    }

    @Test
    @DisplayName("a second blocking status is a 422 naming both")
    void twoBlockingStatusesAreRefusedNamingBoth() throws Exception {
        // 021 D-b. "Which status is the blocking one" is read to decide what to write into
        // issue.blocked_from_status_id, so two of them make that write pick one at random.
        // uq_issue_status_one_blocking would refuse this too — with a constraint violation and a
        // 500, which tells the person who ticked the box nothing they can act on.
        JsonNode workflow = workflow(LEAD);
        ((ObjectNode) statusNode(workflow, "Rework")).put("blocking", true);

        MvcResult refused = put(LEAD, workflow).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString())
                .as("both, so the person knows which two to choose between")
                .contains("Rework").contains("Blocked");
        assertThat(blockingNames(workflow(LEAD)))
                .as("and nothing was stored — the whole document is refused, not half applied")
                .containsExactly("Blocked");
    }

    @Test
    @DisplayName("the blocking flag moves to another status when the document says so")
    void theBlockingFlagFollowsTheDocument() throws Exception {
        // The other side of the 422: one is legal, and it has to be WRITABLE or the refusal above
        // is guarding a field nobody can set. The move is from a LATER status to an EARLIER one on
        // purpose — uq_issue_status_one_blocking is a plain partial index rather than a deferred
        // constraint, so a service that flushed the arriving true before the departing false would
        // answer a perfectly legal document with a 500.
        JsonNode workflow = workflow(LEAD);
        ((ObjectNode) statusNode(workflow, "Blocked")).put("blocking", false);
        ((ObjectNode) statusNode(workflow, "Rework")).put("blocking", true);

        JsonNode stored = json.readTree(
                put(LEAD, workflow).andReturn().getResponse().getContentAsString());

        assertThat(blockingNames(stored)).containsExactly("Rework");
    }

    @Test
    @DisplayName("removing a status takes the blocked cards that REMEMBER it with the ones in it")
    void removingAStatusRewritesTheRememberedOrigins() throws Exception {
        // 021 D-c. blocked_from_status_id is ON DELETE SET NULL, so nothing breaks without this —
        // the card simply loses its way back and Unblock falls through to the ordinary moves. The
        // caller has already answered "where does this column's work go", though, and an issue
        // blocked FROM it was in that column one drag ago: answering the same question two
        // different ways for two issues one drag apart is the surprise this closes.
        String issueKey = story("Waiting on the vendor");
        moveThrough(issueKey, "Refining", "Ready to develop", "In progress", "Blocked");
        assertThat(read(issueKey).get("blockedFromStatus").get("name").asText())
                .isEqualTo("In progress");

        JsonNode workflow = workflow(LEAD);
        remove(idOf(workflow.get("statuses"), "In progress"),
                idOf(workflow.get("statuses"), "Ready to develop"));

        JsonNode blocked = read(issueKey);
        assertThat(blocked.get("status").get("name").asText()).isEqualTo("Blocked");
        assertThat(blocked.get("blockedFromStatus").get("name").asText())
                .isEqualTo("Ready to develop");
        assertThat(names(blocked.get("allowedTransitions")))
                .as("and the way out follows it, because the origin IS the way out")
                .contains("Ready to develop");
    }

    @Test
    void removingTheLastDoneStatusIsRefusedWhateverTheDestination() throws Exception {
        JsonNode workflow = workflow(LEAD);
        UUID done = idOf(workflow.get("statuses"), "Done");
        UUID ready = idOf(workflow.get("statuses"), "Ready to develop");

        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + PROJECT + "/workflow/statuses/" + done)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("reassignToStatusId", ready.toString()))))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aProjectTheCallerCannotSeeIsA404() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + UUID.randomUUID() + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    // =====================================================================

    /**
     * Puts the seeded workflow back, because several tests here change it.
     *
     * <p>{@code seedProjectDefaults} cannot do it: it only inserts what is missing BY NAME, so a
     * renamed or reordered workflow is invisible to it — which is the same property
     * {@code IssueEndpointTest} guards from the other side.
     */
    private void restoreWorkflow() throws SQLException {
        try (var connection = appConnection(ACME)) {
            for (String statement : List.of(
                    "DELETE FROM issue_transition WHERE project_id = CAST(? AS uuid)",
                    "DELETE FROM issue_status WHERE project_id = CAST(? AS uuid)",
                    "DELETE FROM issue_type WHERE project_id = CAST(? AS uuid)")) {
                try (var prepared = connection.prepareStatement(statement)) {
                    prepared.setObject(1, PROJECT);
                    prepared.executeUpdate();
                }
            }
            try (var prepared = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                prepared.setObject(1, ACME);
                prepared.setObject(2, PROJECT);
                prepared.execute();
            }
        }
    }

    private JsonNode workflow(UUID userId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions put(UUID userId, JsonNode document)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/projects/" + PROJECT + "/workflow")
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(document)));
    }

    private String story(String title) throws Exception {
        UUID storyType = idOf(workflow(LEAD).get("types"), "Story");
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "projectId", PROJECT.toString(),
                                "typeId", storyType.toString(),
                                "title", title))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private JsonNode read(String key) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private void moveThrough(String key, String... statusNames) throws Exception {
        JsonNode workflow = workflow(LEAD);
        for (String statusName : statusNames) {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/issues/" + key + "/transitions")
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "statusId",
                                    idOf(workflow.get("statuses"), statusName).toString()))))
                    .andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("move %s to %s: %s", key, statusName,
                            result.getResponse().getContentAsString())
                    .isEqualTo(200);
        }
    }

    private void remove(UUID statusId, UUID destinationId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + PROJECT + "/workflow/statuses/" + statusId)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("reassignToStatusId", destinationId.toString()))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(204);
    }

    private static JsonNode statusNode(JsonNode workflow, String name) {
        for (JsonNode status : workflow.get("statuses")) {
            if (status.get("name").asText().equals(name)) {
                return status;
            }
        }
        throw new AssertionError("no status called " + name);
    }

    private static List<String> blockingNames(JsonNode workflow) {
        List<String> names = new java.util.ArrayList<>();
        workflow.get("statuses").forEach(status -> {
            if (status.get("blocking").asBoolean()) {
                names.add(status.get("name").asText());
            }
        });
        return names;
    }

    private static List<String> names(JsonNode rows) {
        List<String> names = new java.util.ArrayList<>();
        rows.forEach(row -> names.add(row.get("name").asText()));
        return names;
    }

    private static int indexOf(JsonNode rows, String name) {
        return names(rows).indexOf(name);
    }

    private static UUID idOf(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (row.get("name").asText().equals(name)) {
                return UUID.fromString(row.get("id").asText());
            }
        }
        throw new AssertionError("no row called " + name);
    }

    private static String nameOf(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (row.get("id").asText().equals(id)) {
                return row.get("name").asText();
            }
        }
        throw new AssertionError("no row with id " + id);
    }
}
