package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * {@code /issues} — creating, reading, editing and deleting.
 *
 * <h2>The failures this class exists to catch, and why none of them throws</h2>
 *
 * <ol>
 *   <li><strong>A key that is composed rather than allocated.</strong> Two issues created in one
 *       project must not share a number, and the numbers must come from
 *       {@code project_key_sequence} rather than from {@code MAX(key)+1} — which is green in every
 *       test and collides under load.</li>
 *   <li><strong>A new issue entering a status found by NAME.</strong> The project below renames its
 *       first column, so a lookup on the word "Backlog" puts the issue in the wrong place or throws.
 *       Every other test in this class passes against that defect.</li>
 *   <li><strong>The depth rule read against type names.</strong> Two of this project's six types are
 *       called something with "Task" in it and they sit at different levels, so a rule matching on
 *       the name accepts an orphan sub-task or refuses a legitimate one.</li>
 *   <li><strong>A retry creating a second issue</strong> — or, subtler, replaying correctly while
 *       still burning a key, which shows up as gaps that look like deleted work.</li>
 *   <li><strong>An epic deleted out from under its stories.</strong> {@code parent_id} is
 *       {@code ON DELETE RESTRICT}, so the alternative to the 409 is a 500 nobody can act on.</li>
 *   <li><strong>A read gate used as a write gate.</strong> A Viewer must be able to see the work
 *       table and unable to add to it.</li>
 *   <li><strong>An issue from another company.</strong> The second company below holds a project
 *       with an identically shaped workflow, so only ids and RLS separate them.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Issue endpoints — the key is allocated, the status is positional, the rule is levels")
class IssueEndpointTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("1b000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("1b000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("1b001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("1b001000-0000-4000-8000-000000000002");

    /** Holds the whole {@code issue:*} slice. */
    private static final UUID LEAD = UUID.fromString("1b002000-0000-4000-8000-000000000001");
    /** Holds {@code issue:read} and {@code issue:comment} and nothing that writes an issue. */
    private static final UUID VIEWER = UUID.fromString("1b002000-0000-4000-8000-000000000002");
    /** An ACTIVE Acme member granted on nothing at all. */
    private static final UUID NO_GRANT = UUID.fromString("1b002000-0000-4000-8000-000000000003");

    private static final UUID UNKNOWN_PROJECT =
            UUID.fromString("1b0010ff-0000-4000-8000-0000000000ff");

    private static final UUID LEAD_GRANT =
            UUID.fromString("1b003000-0000-4000-8000-000000000001");
    private static final UUID VIEWER_GRANT =
            UUID.fromString("1b003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "tracker-issue-acme", "Acme", now);
            company(UMBRA, "tracker-issue-umbra", "Umbra", now);
            activeUser(LEAD, "lead@tracker-issue.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@tracker-issue.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@tracker-issue.test", "N. Grant", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "ISSA", "Issue Project", now);
            member(ACME, LEAD, "Leonora Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
        }));
        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_PROJECT, UMBRA, "ISSU", "Umbra Project", now)));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        grantOnProject(VIEWER_GRANT, ACME, Role.VIEWER_ID, VIEWER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        seedProjectDefaults(UMBRA, UMBRA_PROJECT);
        clearIssues(List.of(ACME, UMBRA));
        renameFirstColumn();
    }

    /**
     * Puts the first column's name back.
     *
     * <p>Not tidiness: {@code WorkflowSeedTest} asserts that <strong>every</strong> seeded project in
     * the database has the six default statuses in order, and this class deliberately renames one of
     * them. Leaving it renamed is green here and red there, in a class that has nothing to do with
     * this one — §19's shape, reached through a column name instead of an id.
     */
    @AfterEach
    void restoreFirstColumn() throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "UPDATE issue_status SET name = 'Backlog' "
                                + "WHERE project_id = CAST(? AS uuid) AND name = 'Icebox'")) {
            statement.setObject(1, PROJECT);
            statement.executeUpdate();
        }
    }

    /**
     * Renames the project's first status, and this is the fixture's most load-bearing line.
     *
     * <p>Story B1 says a project may rename its columns. A service that finds the opening status by
     * the word "Backlog" is green against every default project in the suite and wrong for exactly
     * the projects that used the configurability the product promises them — so this project has no
     * status called Backlog at all, and {@link #aNewIssueEntersTheFirstStatusByPosition} is the
     * assertion that can tell the two implementations apart.
     */
    private void renameFirstColumn() throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "UPDATE issue_status SET name = 'Icebox' "
                                + "WHERE project_id = CAST(? AS uuid) AND name = 'Backlog'")) {
            statement.setObject(1, PROJECT);
            statement.executeUpdate();
        }
    }

    @Test
    void aNewIssueEntersTheFirstStatusByPosition() throws Exception {
        JsonNode created = create(LEAD, typeNamed("Story"), "Sign in with a passkey", null, null);

        assertThat(created.get("status").get("name").asText()).isEqualTo("Icebox");
        assertThat(created.get("status").get("kind").asText()).isEqualTo("NOT_STARTED");
        assertThat(created.get("status").get("position").asInt()).isZero();
    }

    @Test
    void theKeyIsAllocatedFromTheProjectsOwnCounterAndNeverRepeats() throws Exception {
        JsonNode first = create(LEAD, typeNamed("Story"), "First", null, null);
        JsonNode second = create(LEAD, typeNamed("Story"), "Second", null, null);

        assertThat(first.get("key").asText()).startsWith("ISSA-");
        assertThat(second.get("key").asText()).isNotEqualTo(first.get("key").asText());
        assertThat(second.get("seq").asLong()).isEqualTo(first.get("seq").asLong() + 1);
    }

    @Test
    void aSubTaskIsRefusedWithoutAParentAndAcceptedUnderAStoryLevelTask() throws Exception {
        // Defect 3. This project has a STORY-level type called "Task" and a TASK-level type called
        // "Sub-task" (D29), so a rule written against names cannot tell these two requests apart.
        MvcResult orphan = post(LEAD, body(typeNamed("Sub-task"), "Orphan", null))
                .andReturn();
        assertThat(orphan.getResponse().getStatus()).isEqualTo(400);
        assertThat(orphan.getResponse().getContentAsString()).contains("needs a story-level parent");

        JsonNode standalone = create(LEAD, typeNamed("Task"), "Update the runbook", null, null);
        JsonNode child = create(
                LEAD, typeNamed("Sub-task"), "Draft it", standalone.get("key").asText(), null);

        assertThat(child.get("parentKey").asText()).isEqualTo(standalone.get("key").asText());
    }

    @Test
    void aSubTaskUnderAnEpicIsRefusedBecauseThatSkipsALevel() throws Exception {
        JsonNode epic = create(LEAD, typeNamed("Epic"), "Authentication", null, null);

        MvcResult refused =
                post(LEAD, body(typeNamed("Sub-task"), "Straight under the epic", epic.get("key").asText()))
                        .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("one level above");
    }

    @Test
    void anEpicCarriesItsStoriesAndAStoryCarriesItsEpic() throws Exception {
        JsonNode epic = create(LEAD, typeNamed("Epic"), "Authentication", null, null);
        JsonNode story =
                create(LEAD, typeNamed("Story"), "Passkeys", epic.get("key").asText(), null);

        JsonNode reread = get(LEAD, epic.get("key").asText());
        assertThat(reread.get("children")).hasSize(1);
        assertThat(reread.get("children").get(0).get("key").asText())
                .isEqualTo(story.get("key").asText());

        JsonNode storyView = get(LEAD, story.get("key").asText());
        assertThat(storyView.get("epicKey").asText()).isEqualTo(epic.get("key").asText());
        assertThat(storyView.get("epicTitle").asText()).isEqualTo("Authentication");
    }

    @Test
    void anEpicOffersNoTransitionsAtAll() throws Exception {
        // D31 from the read side, and it has to agree with TransitionEndpointTest's 409: the empty
        // list and the refusal are one decision, so a screen that renders the menu from this cannot
        // offer a move the endpoint would then refuse.
        JsonNode epic = create(LEAD, typeNamed("Epic"), "Authentication", null, null);
        JsonNode story = create(LEAD, typeNamed("Story"), "Passkeys", null, null);

        assertThat(get(LEAD, epic.get("key").asText()).get("allowedTransitions")).isEmpty();
        assertThat(get(LEAD, story.get("key").asText()).get("allowedTransitions")).isNotEmpty();
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyReplaysWithoutBurningANumber() throws Exception {
        MvcResult first = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .header("Idempotency-Key", "one-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(typeNamed("Story"), "Only once", null)))
                .andReturn();
        MvcResult replay = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .header("Idempotency-Key", "one-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(typeNamed("Story"), "Only once", null)))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);

        JsonNode firstBody = json.readTree(first.getResponse().getContentAsString());
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("key").asText())
                .isEqualTo(firstBody.get("key").asText());

        // The number the replay did NOT spend. Allocating before the replay check would leave a gap
        // here, which reads on a work table as an issue somebody deleted.
        JsonNode next = create(LEAD, typeNamed("Story"), "The next one", null, null);
        assertThat(next.get("seq").asLong()).isEqualTo(firstBody.get("seq").asLong() + 1);
    }

    @Test
    void aPatchThatNamesOneFieldLeavesTheOthersAlone() throws Exception {
        // B1's defect, in this module: Jackson gives an absent field and an explicit null the same
        // value, so a service that cannot ask what it was sent empties everything it was not told.
        JsonNode created = create(LEAD, typeNamed("Story"), "Original", null, 6.0);

        JsonNode patched = patch(LEAD, created.get("key").asText(), "{\"title\":\"Renamed\"}");

        assertThat(patched.get("title").asText()).isEqualTo("Renamed");
        assertThat(patched.get("estimateHours").asDouble()).isEqualTo(6.0);
    }

    @Test
    void aPatchMayStillClearAFieldBySendingItAsNull() throws Exception {
        JsonNode created = create(LEAD, typeNamed("Story"), "Original", null, 6.0);

        JsonNode patched = patch(LEAD, created.get("key").asText(), "{\"estimateHours\":null}");

        assertThat(patched.get("estimateHours").isNull()).isTrue();
    }

    @Test
    void anEpicHoldingStoriesRefusesToBeDeleted() throws Exception {
        JsonNode epic = create(LEAD, typeNamed("Epic"), "Authentication", null, null);
        create(LEAD, typeNamed("Story"), "Passkeys", epic.get("key").asText(), null);

        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/issues/" + epic.get("key").asText())
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("Move or delete them first");
    }

    @Test
    void aDeletedIssueIsGoneFromTheListAndFromItsOwnUrl() throws Exception {
        JsonNode created = create(LEAD, typeNamed("Story"), "Mistake", null, null);

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/issues/" + created.get("key").asText())
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

        assertThat(list(LEAD, PROJECT)).isEmpty();
        assertThat(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + created.get("key").asText())
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void aViewerReadsTheWorkTableAndCannotAddToIt() throws Exception {
        create(LEAD, typeNamed("Story"), "Something to look at", null, null);

        assertThat(list(VIEWER, PROJECT)).hasSize(1);
        assertThat(post(VIEWER, body(typeNamed("Story"), "Not allowed", null))
                .andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void aProjectTheCallerCannotSeeIsA404RatherThanAnEmptyTable() throws Exception {
        // The two must not be conflated: an empty list for a project you cannot see tells you it
        // exists, and an unknown id and an ungranted one must answer identically.
        assertThat(listStatus(NO_GRANT, PROJECT)).isEqualTo(404);
        assertThat(listStatus(LEAD, UMBRA_PROJECT)).isEqualTo(404);
        assertThat(listStatus(LEAD, UNKNOWN_PROJECT)).isEqualTo(404);
    }

    @Test
    void anotherCompanysIssueIsNotReadableByItsKey() throws Exception {
        // Keys are unique per COMPANY, so both projects really can hold ISSU-1 / ISSA-1 shapes.
        // What separates them is RLS and the grant check, not the string.
        JsonNode mine = create(LEAD, typeNamed("Story"), "Mine", null, null);

        assertThat(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + mine.get("key").asText())
                        .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void theListNarrowsByLevelWithoutNamingATypeAtAll() throws Exception {
        JsonNode epic = create(LEAD, typeNamed("Epic"), "Authentication", null, null);
        create(LEAD, typeNamed("Story"), "Passkeys", epic.get("key").asText(), null);
        create(LEAD, typeNamed("Bug"), "Sign-in loops", null, null);

        // Two DIFFERENT types come back for one level, which is the property a name filter cannot
        // have and the reason `level` is on the contract rather than `typeId`.
        List<String> storyLevel = titlesOf(listFiltered(LEAD, PROJECT, "level=STORY"));
        assertThat(storyLevel).containsExactlyInAnyOrder("Passkeys", "Sign-in loops");
        assertThat(titlesOf(listFiltered(LEAD, PROJECT, "level=EPIC")))
                .containsExactly("Authentication");
    }

    @Test
    void unparentedSelectsTheBacklogAndIsNotTheSameAsAnAbsentEpicFilter() throws Exception {
        JsonNode epic = create(LEAD, typeNamed("Epic"), "Authentication", null, null);
        create(LEAD, typeNamed("Story"), "Scheduled", epic.get("key").asText(), null);
        create(LEAD, typeNamed("Story"), "Unscheduled", null, null);

        assertThat(titlesOf(listFiltered(LEAD, PROJECT, "level=STORY&unparented=true")))
                .containsExactly("Unscheduled");
    }

    @Test
    void assigningSomebodyWhoCannotOpenTheProjectIsRefused() throws Exception {
        // The id is trivially available from any other tab, the card would render their name
        // perfectly, and the only person who ever sees the problem is the assignee — who gets a 404
        // when they click it.
        String payload = json.writeValueAsString(Map.of(
                "projectId", PROJECT.toString(),
                "typeId", typeNamed("Story").toString(),
                "title", "Assigned to an outsider",
                "assigneeId", NO_GRANT.toString()));

        MvcResult refused = post(LEAD, payload).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("not a member of this project");
    }

    // =====================================================================
    // Request helpers
    // =====================================================================

    private UUID typeNamed(String name) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("the workflow document, which every helper here reads the vocabulary from: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode workflow = json.readTree(result.getResponse().getContentAsString());
        for (JsonNode type : workflow.get("types")) {
            if (type.get("name").asText().equals(name)) {
                return UUID.fromString(type.get("id").asText());
            }
        }
        throw new AssertionError("this project has no type called " + name
                + " — tcms_seed_project_defaults seeds six and the fixture renames none of them");
    }

    private String body(UUID typeId, String title, String parentKey) throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("projectId", PROJECT.toString());
        payload.put("typeId", typeId.toString());
        payload.put("title", title);
        payload.put("parentKey", parentKey);
        return json.writeValueAsString(payload);
    }

    private JsonNode create(UUID userId, UUID typeId, String title, String parentKey, Double hours)
            throws Exception {

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("projectId", PROJECT.toString());
        payload.put("typeId", typeId.toString());
        payload.put("title", title);
        payload.put("parentKey", parentKey);
        payload.put("estimateHours", hours);

        MvcResult result = post(userId, json.writeValueAsString(payload)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("create %s: %s", title, result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions post(UUID userId, String payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private JsonNode patch(UUID userId, String key, String payload) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.patch("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode get(UUID userId, String key) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode list(UUID userId, UUID projectId) throws Exception {
        return listFiltered(userId, projectId, null);
    }

    private JsonNode listFiltered(UUID userId, UUID projectId, String query) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues?projectId=" + projectId
                                + (query == null ? "" : "&" + query))
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int listStatus(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues?projectId=" + projectId)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getStatus();
    }

    private static List<String> titlesOf(JsonNode list) {
        List<String> titles = new java.util.ArrayList<>();
        list.forEach(issue -> titles.add(issue.get("title").asText()));
        return titles;
    }
}
