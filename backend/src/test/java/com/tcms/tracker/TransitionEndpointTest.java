package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code POST /issues/{key}/transitions} — the move, and the two refusals.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A deny-list read as an allow-list.</strong> The seeded workflow lets anything reach
 *       anything <em>except</em> Done, which only In Review reaches — so a service that accepts a
 *       pair it has no row for is green on every legal move in the product and wrong on the one gate
 *       the workflow exists to express.</li>
 *   <li><strong>An epic whose status somebody set by hand.</strong> D31 derives it; a manual one is
 *       the "In Progress, 100%" disagreement the derivation exists to make unrepresentable.</li>
 *   <li><strong>{@code done_at} cleared by a reopen.</strong> Nothing fails when it is: the row is
 *       still valid, the screen still renders, and every lead time computed from it silently
 *       lengthens. Only an assertion that reopens and re-closes can see it.</li>
 *   <li><strong>A move gated on something other than {@code issue:edit}.</strong> There is no
 *       {@code board:move} permission — dragging a card costs exactly what editing costs.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Issue transitions — an allow-list, an epic's refusal, and done_at that survives a reopen")
class TransitionEndpointTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("1e000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("1e001000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("1e002000-0000-4000-8000-000000000001");
    /** Holds {@code issue:read} and no key that writes one. */
    private static final UUID VIEWER = UUID.fromString("1e002000-0000-4000-8000-000000000002");

    private static final UUID LEAD_GRANT =
            UUID.fromString("1e003000-0000-4000-8000-000000000001");
    private static final UUID VIEWER_GRANT =
            UUID.fromString("1e003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "tracker-move-acme", "Acme", now);
            activeUser(LEAD, "lead@tracker-move.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@tracker-move.test", "V. Viewer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "MOVA", "Move Project", now);
            member(ACME, LEAD, "Leonora Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        grantOnProject(VIEWER_GRANT, ACME, Role.VIEWER_ID, VIEWER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
    }

    @Test
    void aMoveTheWorkflowAllowsIsAccepted() throws Exception {
        JsonNode story = story("Passkeys");

        JsonNode moved = move(LEAD, story.get("key").asText(), statusNamed("In Progress"));

        assertThat(moved.get("status").get("name").asText()).isEqualTo("In Progress");
        assertThat(moved.get("status").get("kind").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aPairTheWorkflowHasNoRowForIsRefusedWithAReason() throws Exception {
        // The seeded workflow's one gate: only In Review reaches Done. A service that treated the
        // table as a deny-list would accept this and every other assertion here would still pass.
        JsonNode story = story("Passkeys");
        move(LEAD, story.get("key").asText(), statusNamed("In Progress"));

        MvcResult refused =
                attempt(LEAD, story.get("key").asText(), statusNamed("Done")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains("from In Progress to Done");
    }

    @Test
    void theRefusedMoveLeavesTheIssueWhereItWas() throws Exception {
        // Story H5: "the card does not move and the board tells me why". A 409 with the issue
        // already moved is the same defect as snapping back — worse, because the two disagree.
        JsonNode story = story("Passkeys");
        move(LEAD, story.get("key").asText(), statusNamed("In Progress"));
        attempt(LEAD, story.get("key").asText(), statusNamed("Done"));

        assertThat(read(story.get("key").asText()).get("status").get("name").asText())
                .isEqualTo("In Progress");
    }

    @Test
    void anEpicRefusesEveryMoveBecauseItsStatusIsDerived() throws Exception {
        JsonNode epic = epic("Authentication");

        MvcResult refused =
                attempt(LEAD, epic.get("key").asText(), statusNamed("In Progress")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("It follows its stories");
    }

    @Test
    void doneAtSurvivesAReopen() throws Exception {
        // Nothing fails when it does not: the row is valid, the screen renders, and every lead time
        // computed from the column quietly lengthens each time somebody reopens something.
        JsonNode story = story("Passkeys");
        String key = story.get("key").asText();
        move(LEAD, key, statusNamed("In Progress"));
        move(LEAD, key, statusNamed("In Review"));
        JsonNode done = move(LEAD, key, statusNamed("Done"));
        String firstDoneAt = done.get("doneAt").asText();

        move(LEAD, key, statusNamed("In Progress"));
        move(LEAD, key, statusNamed("In Review"));
        JsonNode reclosed = move(LEAD, key, statusNamed("Done"));

        assertThat(firstDoneAt).isNotBlank();
        assertThat(reclosed.get("doneAt").asText()).isEqualTo(firstDoneAt);
    }

    @Test
    void statusEnteredAtMovesOnEveryAcceptedTransition() throws Exception {
        JsonNode story = story("Passkeys");
        String key = story.get("key").asText();
        String atCreation = read(key).get("statusEnteredAt").asText();

        JsonNode moved = move(LEAD, key, statusNamed("Ready"));

        assertThat(moved.get("statusEnteredAt").asText()).isNotEqualTo(atCreation);
    }

    @Test
    void movingToTheStatusItIsAlreadyInIsAcceptedRatherThanRefused() throws Exception {
        // There is no self-pair row and there never is one: ck_issue_transition_distinct refuses it,
        // because storing it would make "no moves allowed" and "only the current one" the same set.
        // A client retrying a dropped response must see the state it asked for, not a 409 about a
        // move it already made.
        JsonNode story = story("Passkeys");
        String key = story.get("key").asText();
        JsonNode moved = move(LEAD, key, statusNamed("Ready"));

        assertThat(move(LEAD, key, statusNamed("Ready")).get("status").get("name").asText())
                .isEqualTo(moved.get("status").get("name").asText());
    }

    @Test
    void aStatusFromAnotherProjectIsA400RatherThanAMove() throws Exception {
        JsonNode story = story("Passkeys");

        MvcResult refused = attempt(LEAD, story.get("key").asText(), UUID.randomUUID()).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aViewerCannotMoveACard() throws Exception {
        // There is no board:move permission — dragging a card changes the issue's status and costs
        // issue:edit, which a Viewer does not hold.
        JsonNode story = story("Passkeys");

        assertThat(attempt(VIEWER, story.get("key").asText(), statusNamed("In Progress"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void anIssueTheCallerCannotSeeIsA404() throws Exception {
        assertThat(attempt(LEAD, "MOVA-99999", statusNamed("In Progress"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    // =====================================================================

    private JsonNode story(String title) throws Exception {
        return create(typeNamed("Story"), title, null);
    }

    private JsonNode epic(String title) throws Exception {
        return create(typeNamed("Epic"), title, null);
    }

    private JsonNode create(UUID typeId, String title, String parentKey) throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("projectId", PROJECT.toString());
        payload.put("typeId", typeId.toString());
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
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode move(UUID userId, String key, UUID statusId) throws Exception {
        MvcResult result = attempt(userId, key, statusId).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions attempt(
            UUID userId, String key, UUID statusId) throws Exception {

        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + key + "/transitions")
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("statusId", statusId.toString()))));
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
}
