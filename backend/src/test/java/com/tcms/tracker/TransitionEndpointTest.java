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
 * {@code POST /issues/{key}/transitions} — the move, and the refusals.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A deny-list read as an allow-list.</strong> ~~The seeded workflow lets anything
 *       reach anything except Done, which only In Review reaches.~~ → V13: the seeded workflow is a
 *       LINE — each status reaches the next one, plus Rework and Blocked — so most pairs have no
 *       row, and a service that accepts a pair it has no row for is green on every legal move in
 *       the product and wrong on every gate the workflow exists to express.</li>
 *   <li><strong>An epic that cannot be moved.</strong> ~~D31 derives an epic's status, so a manual
 *       one is the "In Progress, 100%" disagreement the derivation exists to make
 *       unrepresentable.~~ → inverted by 021 R10: an epic has a lifecycle of its own and the PO
 *       drags it along the same line, so the failure this class now watches for is the opposite —
 *       an epic silently refused, or offered a set of moves a story is not.</li>
 *   <li><strong>Finished work being blocked.</strong> The seeded pairs never list Done → Blocked,
 *       so a service that leant on the seed alone would look correct here and accept the move the
 *       day somebody marks a different status {@code blocking}.</li>
 *   <li><strong>{@code done_at} cleared by a reopen.</strong> Nothing fails when it is: the row is
 *       still valid, the screen still renders, and every lead time computed from it silently
 *       lengthens. Only an assertion that reopens and re-closes can see it.</li>
 *   <li><strong>A move gated on something other than {@code issue:edit}.</strong> There is no
 *       {@code board:move} permission — dragging a card costs exactly what editing costs.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Issue transitions — an allow-list, an epic that moves, and done_at that survives a reopen")
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

        // New -> Refining is the first step of the line, and the only non-Blocked move a
        // brand-new issue has (021 §2.3).
        JsonNode moved = move(LEAD, story.get("key").asText(), statusNamed("Refining"));

        assertThat(moved.get("status").get("name").asText()).isEqualTo("Refining");
        assertThat(moved.get("status").get("kind").asText()).isEqualTo("NOT_STARTED");
    }

    @Test
    void aPairTheWorkflowHasNoRowForIsRefusedWithAReason() throws Exception {
        // The deploy gate: only Ready to deploy reaches Done (021 D-l — tested is not shipped). A
        // service that treated the table as a deny-list would accept this and every other assertion
        // here would still pass.
        JsonNode story = story("Passkeys");
        move(LEAD, story.get("key").asText(), statusNamed("Refining"));

        MvcResult refused =
                attempt(LEAD, story.get("key").asText(), statusNamed("Done")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains("from Refining to Done");
    }

    @Test
    void theRefusedMoveLeavesTheIssueWhereItWas() throws Exception {
        // Story H5: "the card does not move and the board tells me why". A 409 with the issue
        // already moved is the same defect as snapping back — worse, because the two disagree.
        JsonNode story = story("Passkeys");
        move(LEAD, story.get("key").asText(), statusNamed("Refining"));
        attempt(LEAD, story.get("key").asText(), statusNamed("Done"));

        assertThat(read(story.get("key").asText()).get("status").get("name").asText())
                .isEqualTo("Refining");
    }

    @Test
    @DisplayName("an epic moves along the workflow like a story (021 R10)")
    void anEpicMovesLikeAStory() throws Exception {
        // The inversion of this class's second failure. D31 refused every move on an epic because
        // an epic had no lifecycle of its own; it has one now — written, refined, made ready,
        // shipped — and the PO is the person who moves it. What stayed derived is the COMPLETION,
        // which is a percentage and was never this endpoint's.
        JsonNode epic = epic("Authentication");
        String key = epic.get("key").asText();

        JsonNode moved = move(LEAD, key, statusNamed("Refining"));

        assertThat(moved.get("status").get("name").asText()).isEqualTo("Refining");
        assertThat(allowedNames(moved))
                .as("the workflow's set, exactly as a story in Refining gets")
                .contains("Ready to develop", "Blocked");
    }

    @Test
    @DisplayName("an epic is refused the same pairs a story is — it is not a special case either way")
    void anEpicIsBoundByTheSameAllowList() throws Exception {
        // The other half of R10. "An epic moves" must not read as "an epic moves anywhere": the
        // widening is that the EPIC branch is gone, not that the allow-list is.
        String key = epic("Authentication").get("key").asText();

        MvcResult refused = attempt(LEAD, key, statusNamed("Done")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("from New to Done");
    }

    @Test
    @DisplayName("Blocked from a done status is refused — finished work is not waiting for anything")
    void blockingFromDoneIsRefused() throws Exception {
        // The seeded pairs list Blocked from every NON-done status, so this arm cannot be proved by
        // the seed: a service that only ever consulted issue_transition would be green here and
        // wrong the day somebody marks a different status `blocking` in Settings. The pair is
        // written by hand for exactly that reason — the refusal has to be the service's own.
        String key = story("Passkeys").get("key").asText();
        walkToReadyToDeploy(key);
        move(LEAD, key, statusNamed("Done"));
        allowMove("Done", "Blocked");

        MvcResult refused = attempt(LEAD, key, statusNamed("Blocked")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .as("and the sentence does NOT offer to edit the workflow, because the pair is "
                        + "already there and adding it is what just happened")
                .contains("Finished work cannot be blocked");
        assertThat(read(key).get("status").get("name").asText()).isEqualTo("Done");
        assertThat(read(key).get("blockedFromStatus").isNull()).isTrue();
    }

    @Test
    void doneAtSurvivesAReopen() throws Exception {
        // Nothing fails when it does not: the row is valid, the screen renders, and every lead time
        // computed from the column quietly lengthens each time somebody reopens something.
        //
        // THE REOPEN IS A DIRECT WRITE, AND THAT IS V13'S DOING RATHER THAN A SHORTCUT. The seeded
        // workflow is a one-way line and 021 §2.3 enumerates no move OUT of Done, so a finished
        // issue has no reopen path through this endpoint at all — the first close and the second
        // are both real transitions, and only the step between them is arranged by hand.
        JsonNode story = story("Passkeys");
        String key = story.get("key").asText();
        walkToReadyToDeploy(key);
        JsonNode done = move(LEAD, key, statusNamed("Done"));
        String firstDoneAt = done.get("doneAt").asText();

        putBackTo(key, "Ready to deploy");
        JsonNode reclosed = move(LEAD, key, statusNamed("Done"));

        assertThat(firstDoneAt).isNotBlank();
        assertThat(reclosed.get("doneAt").asText()).isEqualTo(firstDoneAt);
    }

    @Test
    void statusEnteredAtMovesOnEveryAcceptedTransition() throws Exception {
        JsonNode story = story("Passkeys");
        String key = story.get("key").asText();
        String atCreation = read(key).get("statusEnteredAt").asText();

        JsonNode moved = move(LEAD, key, statusNamed("Refining"));

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
        JsonNode moved = move(LEAD, key, statusNamed("Refining"));

        assertThat(move(LEAD, key, statusNamed("Refining")).get("status").get("name").asText())
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

        assertThat(attempt(VIEWER, story.get("key").asText(), statusNamed("Refining"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void anIssueTheCallerCannotSeeIsA404() throws Exception {
        assertThat(attempt(LEAD, "MOVA-99999", statusNamed("Refining"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("Blocked records where the card came from, and Unblock returns it there")
    void blockingRecordsTheOriginAndUnblockReturnsToIt() throws Exception {
        // 021 D-c. Blocked is a place work WAITS, not a place work goes, so the return trip should
        // cost one click and no decision — which is why the origin is a column on the issue and not
        // ten reverse issue_transition pairs. Ten pairs would make "back to where it was"
        // indistinguishable from "to anywhere", which is the whole distinction the column buys.
        String key = story("Passkeys").get("key").asText();
        move(LEAD, key, statusNamed("Refining"));
        move(LEAD, key, statusNamed("Ready to develop"));
        move(LEAD, key, statusNamed("In progress"));

        JsonNode blocked = move(LEAD, key, statusNamed("Blocked"));

        assertThat(blocked.get("status").get("name").asText()).isEqualTo("Blocked");
        assertThat(blocked.get("status").get("blocking").asBoolean())
                .as("the flag is on the wire, so a client knows which status remembers")
                .isTrue();
        assertThat(blocked.get("blockedFromStatus").get("name").asText())
                .as("the status it was LEAVING, not the one it entered")
                .isEqualTo("In progress");
        assertThat(allowedNames(blocked))
                .as("""
                        The origin is offered even though issue_transition has NO row from Blocked \
                        to it. §12.4 the wrong way round would be a client refusing at the drag a \
                        move the server accepts, which is exactly what an unoffered legal target \
                        produces.""")
                .containsExactly("In progress");

        JsonNode back = move(LEAD, key, statusNamed("In progress"));

        assertThat(back.get("status").get("name").asText()).isEqualTo("In progress");
        assertThat(back.get("blockedFromStatus").isNull())
                .as("cleared on the way out — a card that is not blocked must not say where it "
                        + "was blocked from")
                .isTrue();
    }

    @Test
    @DisplayName("from Blocked, only the remembered origin is legal — the column widens nothing else")
    void fromBlockedOnlyTheRememberedOriginIsLegal() throws Exception {
        // The check that says the widening is scoped. issue_transition has no row FROM Blocked at
        // all, so if the column made any target legal rather than one, this move would be accepted
        // and every workflow in the product would have a hole in it shaped like its blocking status.
        String key = story("Passkeys").get("key").asText();
        move(LEAD, key, statusNamed("Refining"));
        move(LEAD, key, statusNamed("Ready to develop"));
        move(LEAD, key, statusNamed("In progress"));
        move(LEAD, key, statusNamed("Blocked"));

        MvcResult refused = attempt(LEAD, key, statusNamed("In review")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains("from Blocked to In review");
        assertThat(read(key).get("status").get("name").asText()).isEqualTo("Blocked");
    }

    @Test
    @DisplayName("blocking from a different status remembers that one instead")
    void theOriginIsWhicheverStatusTheIssueWasIn() throws Exception {
        // The column is not a constant. Blocking from Refining must return to Refining, or the
        // "origin" is a default wearing a column's name — which is the shape D-c exists to refuse.
        String key = story("Passkeys").get("key").asText();
        move(LEAD, key, statusNamed("Refining"));

        JsonNode blocked = move(LEAD, key, statusNamed("Blocked"));

        assertThat(blocked.get("blockedFromStatus").get("name").asText()).isEqualTo("Refining");
        assertThat(move(LEAD, key, statusNamed("Refining")).get("status").get("name").asText())
                .isEqualTo("Refining");
    }

    // =====================================================================

    private static List<String> allowedNames(JsonNode issue) {
        List<String> names = new java.util.ArrayList<>();
        issue.get("allowedTransitions").forEach(status -> names.add(status.get("name").asText()));
        return names;
    }

    /**
     * The seven moves from {@code New} to {@code Ready to deploy} — the whole line short of the
     * deploy step (021 §2.3).
     *
     * <p>Spelled out rather than shortcut, because the walk is itself an assertion: every one of
     * these pairs has to be a row, and a missing one fails here naming the step.
     */
    private void walkToReadyToDeploy(String key) throws Exception {
        for (String name : List.of("Refining", "Ready to develop", "In progress", "In review",
                "Ready for test", "In test", "Ready to deploy")) {
            move(LEAD, key, statusNamed(name));
        }
    }

    /**
     * A pair written straight into {@code issue_transition}, for a move the seed does not list.
     *
     * <p>Only one test wants it, and it wants the pair to EXIST so the refusal it then asserts is
     * demonstrably the service's own rather than the allow-list's.
     */
    private void allowMove(String from, String to) throws Exception {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement("""
                        INSERT INTO issue_transition (
                                company_id, project_id, from_status_id, to_status_id)
                        SELECT f.company_id, f.project_id, f.id, t.id
                          FROM issue_status f, issue_status t
                         WHERE f.project_id = CAST(? AS uuid) AND f.name = ?
                           AND t.project_id = CAST(? AS uuid) AND t.name = ?
                        """)) {
            statement.setObject(1, PROJECT);
            statement.setString(2, from);
            statement.setObject(3, PROJECT);
            statement.setString(4, to);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** A status written straight onto the row, for the one step the workflow has no move for. */
    private void putBackTo(String key, String statusName) throws Exception {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement("""
                        UPDATE issue SET status_id = (SELECT id FROM issue_status
                                                       WHERE project_id = CAST(? AS uuid)
                                                         AND name = ?)
                         WHERE issue_key = ?
                        """)) {
            statement.setObject(1, PROJECT);
            statement.setString(2, statusName);
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }

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
