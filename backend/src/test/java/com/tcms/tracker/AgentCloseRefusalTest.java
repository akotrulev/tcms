package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * D33 — <strong>"Require review to close issues", and the four things it must NOT do.</strong>
 *
 * <p>The rule: a move into a {@code DONE}-kind status is refused when the project has switched
 * {@code reviewEnabledIssue} on, {@code authorMayApproveOwnWork} is off, and the caller is the
 * issue's <em>assignee</em>. There is deliberately no token-specific branch — an assistant is
 * refused because its owner would be, which is what makes "the same move with the gate off
 * succeeds" true rather than a special case.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A gate that bites with the setting off.</strong> That dead-ends a one-person
 *       company the moment its own assistant works a task (A6), and it is the reason the default is
 *       off. Asserted in both directions on one issue.</li>
 *   <li><strong>A gate that bites everybody.</strong> A second person closing somebody else's work
 *       is exactly what the setting asks for; refusing them makes the project unclosable.</li>
 *   <li><strong>A refusal with no trace.</strong> The lead who turned the setting on cannot see it
 *       working, and the assignee's own history says nothing happened.</li>
 *   <li><strong>A projection that still offers the move.</strong> Three surfaces carry
 *       {@code allowedTransitions} — the issue, the project board card and the personal board card
 *       — and a client that is offered Done and then refused it is §12.4's "accepted and reverted"
 *       failure with an extra round trip.</li>
 * </ol>
 *
 * <p>Ids use the {@code e7…} prefix, checked free across the suite before it was taken.
 */
@AutoConfigureMockMvc
@DisplayName("Review to close — refused for the assignee, allowed for a second person, off by default")
class AgentCloseRefusalTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("e7000000-0000-4000-8000-000000000001");
    private static final UUID ATLAS = UUID.fromString("e7001000-0000-4000-8000-000000000001");

    /** Test Lead on Atlas — turns the setting on, and is the second person who may close. */
    private static final UUID ADMIN = UUID.fromString("e7002000-0000-4000-8000-000000000001");
    /** Test Engineer on Atlas, and the assignee of everything this class creates. */
    private static final UUID DANA = UUID.fromString("e7002000-0000-4000-8000-000000000002");

    private static final UUID ADMIN_GRANT = UUID.fromString("e7003000-0000-4000-8000-000000000001");
    private static final UUID DANA_GRANT = UUID.fromString("e7003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "close-gate-acme", "Acme", now);
            activeUser(ADMIN, "lead@close-gate.test", "L. Lead", now);
            activeUser(DANA, "dana@close-gate.test", "Dana Developer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, ACME, "CLGA", "Atlas", now);
            member(ACME, ADMIN, "L. Lead", now);
            member(ACME, DANA, "Dana Developer", now);
        }));

        grantOnProject(ADMIN_GRANT, ACME, Role.TEST_LEAD_ID, ADMIN, ATLAS);
        grantOnProject(DANA_GRANT, ACME, Role.TEST_ENGINEER_ID, DANA, ATLAS);
        seedProjectDefaults(ACME, ATLAS);
        clearIssues(List.of(ACME));
        clearTrail();
        reviewToClose(false);
    }

    private void clearTrail() throws SQLException {
        try (var connection = appConnection(ACME)) {
            for (String sql : List.of("DELETE FROM audit_event", "DELETE FROM api_token")) {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.executeUpdate();
                }
            }
        }
    }

    // =====================================================================

    @Test
    @DisplayName("the assignee is refused with 409, and the issue does not move")
    void theAssigneeIsRefused() throws Exception {
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        MvcResult refused = attempt(DANA, key, statusNamed("Done"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("a second person");
        assertThat(readAs(DANA, key).get("status").get("name").asText())
                .isEqualTo("Ready to deploy");
        assertThat(readAs(DANA, key).get("doneAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("the refusal is on the issue's history")
    void theRefusalIsRecorded() throws Exception {
        // Without this the lead who turned the setting on has no evidence it is doing anything,
        // and the assignee's own history says nothing happened at all.
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        attempt(DANA, key, statusNamed("Done"));

        assertThat(auditActions()).contains("issue.close_refused");
        assertThat(changesOf("issue.close_refused")).contains(key).contains("Done");
    }

    @Test
    @DisplayName("an assistant is refused exactly as its owner would be — no token-specific branch")
    void anAssistantIsRefusedAsItsOwner() throws Exception {
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);
        String secret = assistantFor(DANA);

        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/issues/" + key + "/transitions")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statusId\":\"" + statusNamed("Done") + "\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        // The refusal is attributed to the person, via the assistant — the same row shape every
        // other act through a token writes.
        assertThat(auditActions()).contains("issue.close_refused");
    }

    @Test
    @DisplayName("a second person closes it — that is what the setting asks for")
    void aSecondPersonMayClose() throws Exception {
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        MvcResult closed = attempt(ADMIN, key, statusNamed("Done"));

        assertThat(closed.getResponse().getStatus())
                .as(closed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(json.readTree(closed.getResponse().getContentAsString())
                .get("doneAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("with the setting OFF the same caller closes the same task — A6")
    void aOnePersonCompanyStillClosesItsOwnWork() throws Exception {
        // The one-person company path, and the reason the default is off. Same issue, same caller,
        // same move: the only difference is the project setting.
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        MvcResult closed = attempt(DANA, key, statusNamed("Done"));

        assertThat(closed.getResponse().getStatus())
                .as(closed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(json.readTree(closed.getResponse().getContentAsString())
                .get("doneAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("an unassigned issue is not guarded — nobody is the author of untaken work")
    void anUnassignedIssueIsNotGuarded() throws Exception {
        reviewToClose(true);
        String key = created("Nobody has taken this", null);
        walkToReadyToDeploy(DANA, key);

        assertThat(attempt(DANA, key, statusNamed("Done")).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an epic is guarded like anything else now that a drag can close one (021 R10)")
    void anEpicIsGuardedToo() throws Exception {
        // The gate used to be unreachable on an epic for a reason that has nothing to do with the
        // gate: TransitionService refused every epic move before it got here (D31). R10 removed
        // that refusal, so this is the first release in which a PO who wrote the epic can also
        // ship it in one drag — which is exactly what "review to close" exists to make a second
        // person's decision. Extended here rather than in a class of its own, because it is the
        // same rule and a separate class is how two spellings of it start.
        reviewToClose(true);
        String key = createdOfType("Epic", "Authentication", DANA);
        walkToReadyToDeploy(DANA, key);

        MvcResult refused = attempt(DANA, key, statusNamed("Done"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("a second person");
        assertThat(allowedNames(readAs(DANA, key)))
                .as("and withheld from the epic's own list, so the drag never starts")
                .doesNotContain("Done").contains("Rework", "Blocked");
        assertThat(allowedNames(readAs(ADMIN, key))).contains("Done");
    }

    @Test
    @DisplayName("GET /issues/{key} drops Done for the assignee and keeps the other moves")
    void theIssueProjectionIsCallerScoped() throws Exception {
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        List<String> forDana = allowedNames(readAs(DANA, key));
        List<String> forAdmin = allowedNames(readAs(ADMIN, key));

        // Subtracted, never replaced: the other moves out of Ready to deploy must survive, or the
        // refusal is a dead end — an assignee who cannot close and cannot do anything else either.
        assertThat(forDana).doesNotContain("Done").contains("Rework", "Blocked");
        assertThat(forAdmin).contains("Done");
    }

    @Test
    @DisplayName("the project board card drops it too, so the drag is refused before it starts")
    void theBoardCardIsCallerScoped() throws Exception {
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        assertThat(boardCardMoves(DANA, key)).doesNotContain("Done").contains("Rework");
        assertThat(boardCardMoves(ADMIN, key)).contains("Done");
    }

    @Test
    @DisplayName("and so does the personal board, which is a third assembly of the same fact")
    void thePersonalBoardCardIsCallerScoped() throws Exception {
        reviewToClose(true);
        String key = assignedToDana("Rewrite the retry budget");
        walkToReadyToDeploy(DANA, key);

        assertThat(personalBoardMoves(DANA, key)).doesNotContain("Done");
    }

    @Test
    @DisplayName("the setting round-trips through PUT /settings, off by default")
    void theSettingRoundTrips() throws Exception {
        assertThat(settings(ADMIN).get("reviewEnabledIssue").asBoolean()).isFalse();

        reviewToClose(true);

        assertThat(settings(ADMIN).get("reviewEnabledIssue").asBoolean()).isTrue();
    }

    // ===================================================================== helpers

    /** Through the endpoint, so this both arranges the test and proves the flag is threaded. */
    private void reviewToClose(boolean on) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ATLAS + "/settings")
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewEnabledCase\":false,\"reviewEnabledSuite\":false,"
                                + "\"reviewEnabledPlan\":false,\"reviewEnabledDoc\":false,"
                                + "\"requiredPeerReviews\":0,\"authorMayApproveOwnWork\":false,"
                                + "\"editAfterApproval\":\"RETAIN\","
                                + "\"requireApprovedCaseForRun\":false,"
                                + "\"reviewEnabledIssue\":" + on + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode settings(UUID callerId) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ATLAS + "/settings")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private String assignedToDana(String title) throws Exception {
        return created(title, DANA);
    }

    private String created(String title, UUID assigneeId) throws Exception {
        return createdOfType("Task", title, assigneeId);
    }

    private String createdOfType(String typeName, String title, UUID assigneeId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + ATLAS + "\",\"typeId\":\""
                                + typeNamed(typeName)
                                + "\",\"title\":\"" + title + "\",\"assigneeId\":"
                                + (assigneeId == null ? "null" : "\"" + assigneeId + "\"") + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private String assistantFor(UUID callerId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/me/assistants")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"claude-code\",\"projectId\":\"" + ATLAS + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("secret").asText();
    }

    private MvcResult attempt(UUID callerId, String key, UUID statusId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/issues/" + key + "/transitions")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statusId\":\"" + statusId + "\"}"))
                .andReturn();
    }

    /**
     * The seven moves from {@code New} to {@code Ready to deploy} — the whole line short of the
     * deploy step, which is the one this class is about.
     *
     * <p>~~One move to In Review.~~ → V13: the seeded workflow is a LINE (021 §2.3) and the close
     * gate moved to its end, because tested is not shipped (D-l). Ready to deploy is the only
     * status with a {@code DONE}-kind target, so it is the only place the review-to-close guard has
     * anything to withhold.
     */
    private void walkToReadyToDeploy(UUID callerId, String key) throws Exception {
        for (String name : List.of("Refining", "Ready to develop", "In progress", "In review",
                "Ready for test", "In test", "Ready to deploy")) {
            move(callerId, key, statusNamed(name));
        }
    }

    private void move(UUID callerId, String key, UUID statusId) throws Exception {
        MvcResult result = attempt(callerId, key, statusId);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode readAs(UUID callerId, String key) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> allowedNames(JsonNode issue) {
        List<String> names = new ArrayList<>();
        issue.get("allowedTransitions").forEach(status -> names.add(status.get("name").asText()));
        return names;
    }

    private List<String> boardCardMoves(UUID callerId, String key) throws Exception {
        JsonNode boards = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ATLAS + "/boards")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn().getResponse().getContentAsString());
        String boardId = boards.get(0).get("id").asText();

        JsonNode contents = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/boards/" + boardId + "/cards")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn().getResponse().getContentAsString());

        for (JsonNode lane : contents.get("lanes")) {
            for (JsonNode card : lane.get("cards")) {
                if (key.equals(card.get("key").asText())) {
                    List<String> names = new ArrayList<>();
                    card.get("allowedTransitions")
                            .forEach(status -> names.add(status.get("name").asText()));
                    return names;
                }
            }
        }
        throw new AssertionError("the board has no card for " + key);
    }

    private List<String> personalBoardMoves(UUID callerId, String key) throws Exception {
        JsonNode board = json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/board")
                        .param("projectId", ATLAS.toString())
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn().getResponse().getContentAsString());

        for (JsonNode card : board) {
            if (key.equals(card.get("id").asText())) {
                List<String> names = new ArrayList<>();
                card.get("allowedTransitions")
                        .forEach(status -> names.add(status.get("name").asText()));
                return names;
            }
        }
        throw new AssertionError("the personal board has no card for " + key);
    }

    private JsonNode workflow() throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ATLAS + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private UUID statusNamed(String name) throws Exception {
        for (JsonNode status : workflow().get("statuses")) {
            if (status.get("name").asText().equals(name)) {
                return UUID.fromString(status.get("id").asText());
            }
        }
        throw new AssertionError("this project's workflow has no status called " + name);
    }

    private UUID typeNamed(String name) throws Exception {
        for (JsonNode type : workflow().get("types")) {
            if (type.get("name").asText().equals(name)) {
                return UUID.fromString(type.get("id").asText());
            }
        }
        throw new AssertionError("this project has no type called " + name);
    }

    private List<String> auditActions() throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement("SELECT action FROM audit_event")) {
            try (var rows = statement.executeQuery()) {
                List<String> actions = new ArrayList<>();
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
                return actions;
            }
        }
    }

    private String changesOf(String action) throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT changes::text FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no %s audit row was written", action).isTrue();
                return rows.getString(1);
            }
        }
    }
}
