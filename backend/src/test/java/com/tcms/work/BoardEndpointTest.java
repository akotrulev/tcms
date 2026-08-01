package com.tcms.work;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /work-items} — the kanban board (plan §6l).
 *
 * <h2>The failures this class exists to catch, and why none of them throws</h2>
 *
 * <ol>
 *   <li><strong>A derived card that can be moved.</strong> The single invariant §6l is built around:
 *       a card mirroring an execution must not own a stored status, or a tester marks the case
 *       Passed in the run and the card sits in "In Progress" forever. The endpoint has to
 *       <em>refuse</em> the move, and refuse it as a 409 rather than a 404 — a 404 reads as "the
 *       card is gone" and invites a reload that brings it straight back.</li>
 *   <li><strong>A finished execution still on the board.</strong> §6l: "execution completed → card
 *       leaves". A generator that returns every execution turns the board into an append-only log of
 *       everything ever run, which looks like data and grows forever.</li>
 *   <li><strong>Skipped work resurrected.</strong> The unrecorded executions in a <em>completed</em>
 *       run are cases that were deliberately not run. Putting them on the board makes closed runs a
 *       permanent backlog nobody can ever clear.</li>
 *   <li><strong>A task assigned to somebody who cannot open its project.</strong> The id is trivially
 *       available from any other tab, the card renders their name perfectly, and the only person who
 *       ever sees the problem is the assignee — who gets a 404 when they click it.</li>
 *   <li><strong>{@code kind} accepted from the client.</strong> A POST with {@code kind: EXECUTION}
 *       would materialise a derived item, which is defect 1 arriving through the front door.</li>
 *   <li><strong>The read gate used as the write gate.</strong> Every role holds {@code project:read};
 *       {@code workitem:manage} is held by four of the six (roles spec §A6). A Viewer must be able to
 *       look at the board and unable to change it.</li>
 * </ol>
 *
 * <h2>The fixture</h2>
 *
 * <p>Two companies with identically keyed cases, so only ids prove the scoping. In Acme: a run
 * {@code IN_PROGRESS} holding one unrecorded and one recorded execution, a {@code COMPLETED} run
 * holding an unrecorded one, a case flagged in {@code case_health}, and a stored task. Personas are
 * a Test Lead (holds {@code workitem:manage}), a Viewer (does not), and a member with no grant at
 * all.
 *
 * <p>Ids are unique to this class — {@code b0…} — and so are the company slugs and project keys.
 * {@code CaseHealthRecomputeTest} in this same slice lost eleven tests to a slug it shared with
 * another class: green alone, red in the suite, §19's shape exactly.
 */
@AutoConfigureMockMvc
@DisplayName("Board endpoints — tasks are rows, everything else is a projection")
class BoardEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("b0001000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT = UUID.fromString("b0001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_PROJECT = UUID.fromString("b0001000-0000-4000-8000-000000000003");
    private static final UUID SUITE = UUID.fromString("b0002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("b0002000-0000-4000-8000-000000000002");

    /** Holds workitem:manage on PROJECT. */
    private static final UUID LEAD = UUID.fromString("b0003000-0000-4000-8000-000000000001");
    /** Can see the board, holds no workitem:manage. */
    private static final UUID VIEWER = UUID.fromString("b0003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("b0003000-0000-4000-8000-000000000003");

    private static final UUID LIVE_RUN = UUID.fromString("b0004000-0000-4000-8000-000000000001");
    private static final UUID CLOSED_RUN = UUID.fromString("b0004000-0000-4000-8000-000000000002");

    /** Unrecorded, in the live run — the one EXECUTION card. */
    private static final UUID OUTSTANDING = UUID.fromString("b0005000-0000-4000-8000-000000000001");
    /** Recorded, in the live run — finished work, must have left the board. */
    private static final UUID FINISHED = UUID.fromString("b0005000-0000-4000-8000-000000000002");
    /** Unrecorded, in the CLOSED run — skipped, must not be resurrected. */
    private static final UUID SKIPPED = UUID.fromString("b0005000-0000-4000-8000-000000000003");
    /** Flagged in case_health — the one CASE_MAINTENANCE card. */
    private static final UUID FLAGGED = UUID.fromString("b0005000-0000-4000-8000-000000000004");
    /** Umbra's case, keyed identically to prove only ids scope this. */
    private static final UUID FOREIGN = UUID.fromString("b0005000-0000-4000-8000-000000000005");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.tcms.auth.SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "board-acme", "Acme", now);
            company(UMBRA, "board-umbra", "Umbra", now);
            activeUser(LEAD, "lead@board-acme.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@board-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@board-acme.test", "N. Grant", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "BRDA", "Board Project", now);
            seedProject(OTHER_PROJECT, ACME, "BRDB", "Sibling Project", now);
            member(ACME, LEAD, "L. Lead (Acme)", now);
            member(ACME, VIEWER, "V. Viewer (Acme)", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            grantOnProject("lead", ACME, Role.TEST_LEAD_ID, LEAD, PROJECT, now);
            grantOnProject("viewer", ACME, Role.VIEWER_ID, VIEWER, PROJECT, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_PROJECT, UMBRA, "BRDU", "Umbra Project", now)));

        clearMutableRows();

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, PROJECT);
            suite(acme, SUITE, ACME, PROJECT, "TS-1", "Root");
            testCase(acme, OUTSTANDING, ACME, PROJECT, SUITE, "TC-1", 1, LEAD);
            testCase(acme, FINISHED, ACME, PROJECT, SUITE, "TC-2", 2, LEAD);
            testCase(acme, SKIPPED, ACME, PROJECT, SUITE, "TC-3", 3, LEAD);
            testCase(acme, FLAGGED, ACME, PROJECT, SUITE, "TC-4", 4, LEAD);

            run(acme, LIVE_RUN, ACME, PROJECT, "TR-1", 1, "IN_PROGRESS");
            run(acme, CLOSED_RUN, ACME, PROJECT, "TR-2", 2, "COMPLETED");

            execution(acme, ACME, PROJECT, LIVE_RUN, OUTSTANDING, false, LEAD);
            execution(acme, ACME, PROJECT, LIVE_RUN, FINISHED, true, LEAD);
            execution(acme, ACME, PROJECT, CLOSED_RUN, SKIPPED, false, LEAD);

            health(acme, ACME, PROJECT, FLAGGED, "never_executed");
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "TS-1", "Root");
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, null);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The response — the contract's shape, for both kinds of card")
    class Shape {

        @Test
        @DisplayName("every field the contract marks required is present on every card")
        void requiredFieldsArePresent() throws Exception {
            JsonNode board = boardAs(LEAD, PROJECT);

            assertThat(board).isNotEmpty();
            for (JsonNode card : board) {
                assertThat(List.of("id", "derived", "kind", "title", "status", "priority",
                        "overdue", "projectId", "projectKey", "allowedTransitions"))
                        .allSatisfy(field -> assertThat(card.has(field))
                                .as("WorkItem.%s is required by openapi.yaml, missing on %s",
                                        field, card.get("kind"))
                                .isTrue());
            }
        }

        @Test
        @DisplayName("status is a code from the contract's enum, never a display label")
        void statusIsAStableCode() throws Exception {
            // §53's whole finding on the frontend side: the SPA compared status to 'To do' and the
            // mock served 'To do', so mock mode agreed with itself while a real server answering
            // TODO would have filed every card into no column. This is that check on the server.
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                assertThat(card.get("status").asText())
                        .isIn("TODO", "IN_PROGRESS", "IN_REVIEW", "DONE");
            }
        }
    }

    @Nested
    @DisplayName("Derived cards — projections, and the rules about which rows become one")
    class Derived {

        @Test
        @DisplayName("an unrecorded execution in a live run is a card")
        void outstandingExecutionsAppear() throws Exception {
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION")).containsExactly("TC-1");
        }

        @Test
        @DisplayName("a recorded execution has left the board")
        void recordedExecutionsAreGone() throws Exception {
            // §6l: "execution completed → card leaves". Otherwise the board is an append-only log
            // of everything ever run, and it grows without bound while looking like a to-do list.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION"))
                    .as("TC-2 was recorded and is finished work")
                    .doesNotContain("TC-2");
        }

        @Test
        @DisplayName("an unrecorded execution in a CLOSED run is not resurrected")
        void skippedWorkStaysSkipped() throws Exception {
            // The cases nobody ran in a run that is over. They are a reporting fact (§52's suite
            // timeline counts them as notRun) and not outstanding work — on the board they would be
            // a backlog that can never be cleared, because the run they belong to is closed.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION"))
                    .as("TC-3's run is COMPLETED")
                    .doesNotContain("TC-3");
        }

        @Test
        @DisplayName("a case flagged in case_health is a maintenance card naming its signal")
        void flaggedCasesAppear() throws Exception {
            JsonNode card = cardOfKind(boardAs(LEAD, PROJECT), "CASE_MAINTENANCE");

            assertThat(card.get("linkedKey").asText()).isEqualTo("TC-4");
            assertThat(card.get("description").asText())
                    .as("a card that hides its reason behind a click gets ignored in bulk")
                    .contains("NEVER_EXECUTED");
        }

        @Test
        @DisplayName("a derived card offers no transitions at all")
        void derivedCardsCannotBeMoved() throws Exception {
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                if (card.get("derived").asBoolean()) {
                    assertThat(card.get("allowedTransitions"))
                            .as("%s follows its source; offering a move that silently does nothing "
                                    + "is worse than offering none", card.get("id").asText())
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("a derived id is not a UUID, so it can never collide with a task's")
        void derivedIdsAreSynthetic() throws Exception {
            JsonNode card = cardOfKind(boardAs(LEAD, PROJECT), "EXECUTION");
            assertThat(card.get("id").asText()).startsWith("execution:");
            assertThat(card.get("sourceType").asText()).isEqualTo("EXECUTION");
        }
    }

    @Nested
    @DisplayName("Creating a task")
    class Creating {

        @Test
        @DisplayName("a created task starts in TODO and is not derived")
        void createsATask() throws Exception {
            JsonNode created = createAs(LEAD, """
                    {"projectId":"%s","title":"Update the refund cases"}""".formatted(PROJECT), 201);

            assertThat(created.get("status").asText())
                    .as("a create that could choose its column is a move nobody recorded")
                    .isEqualTo("TODO");
            assertThat(created.get("derived").asBoolean()).isFalse();
            assertThat(created.get("kind").asText()).isEqualTo("TASK");
            assertThat(created.get("allowedTransitions")).hasSize(3);
        }

        @Test
        @DisplayName("kind is ignored — a client cannot materialise a derived item")
        void kindIsNotAnInput() throws Exception {
            // The front-door version of the invariant. A stored row claiming to be an EXECUTION owns
            // a status the execution cannot see, and the two diverge on the first result recorded.
            JsonNode created = createAs(LEAD, """
                    {"projectId":"%s","title":"Sneaky","kind":"EXECUTION"}""".formatted(PROJECT), 201);

            assertThat(created.get("kind").asText()).isEqualTo("TASK");
            assertThat(created.get("derived").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a blank title is refused on its own field")
        void titleIsRequired() throws Exception {
            JsonNode problem = createAs(LEAD, """
                    {"projectId":"%s","title":"   "}""".formatted(PROJECT), 400);

            assertThat(problem.get("errors").has("title")).isTrue();
        }

        @Test
        @DisplayName("an assignee with no grant on the project is refused")
        void assigneeMustBeOnTheProject() throws Exception {
            // NO_GRANT is an ACTIVE member of Acme, so a membership check alone accepts them. The
            // card would render their name, show on their board, and 404 when they click it — and
            // the person who assigned it would never see anything wrong.
            JsonNode problem = createAs(LEAD, """
                    {"projectId":"%s","title":"For a stranger","assigneeId":"%s"}"""
                    .formatted(PROJECT, NO_GRANT), 400);

            assertThat(problem.get("errors").has("assigneeId")).isTrue();
        }

        @Test
        @DisplayName("an unassigned task is unassigned, not silently assigned to its author")
        void unassignedStaysUnassigned() throws Exception {
            // §6l's unclaimed pool. Defaulting to the author makes it unreachable, and the
            // correction is invisible once made because the author's own name looks plausible.
            JsonNode created = createAs(LEAD, """
                    {"projectId":"%s","title":"Nobody's yet"}""".formatted(PROJECT), 201);

            assertThat(created.hasNonNull("assignee")).isFalse();
        }
    }

    @Nested
    @DisplayName("Moving a card — the invariant, enforced on the server")
    class Moving {

        @Test
        @DisplayName("a task moves, and reports its new column")
        void aTaskMoves() throws Exception {
            String id = createAs(LEAD, """
                    {"projectId":"%s","title":"Movable"}""".formatted(PROJECT), 201)
                    .get("id").asText();

            JsonNode moved = patchAs(LEAD, id, "IN_PROGRESS", 200);

            assertThat(moved.get("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(moved.get("allowedTransitions"))
                    .as("the column it is now in is not a move it can make")
                    .noneSatisfy(t -> assertThat(t.asText()).isEqualTo("IN_PROGRESS"));
        }

        @Test
        @DisplayName("a derived card is refused with 409, not 404")
        void aDerivedCardCannotBeMoved() throws Exception {
            // THE test for this module. 404 would read as "that card is gone" and invite a reload
            // that brings it straight back; 409 says the request is impossible, not the resource
            // absent. The detail names the action that does work.
            String derivedId = cardOfKind(boardAs(LEAD, PROJECT), "EXECUTION").get("id").asText();

            JsonNode problem = patchAs(LEAD, derivedId, "DONE", 409);

            assertThat(problem.get("detail").asText())
                    .as("the refusal has to say where to go instead, or it is just a wall")
                    .contains("follows");
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownIdsAreNotFound() throws Exception {
            patchAs(LEAD, UUID.randomUUID().toString(), "DONE", 404);
        }

        @Test
        @DisplayName("an unknown column is 400 and names the four")
        void unknownColumnsAreRefused() throws Exception {
            String id = createAs(LEAD, """
                    {"projectId":"%s","title":"Movable"}""".formatted(PROJECT), 201)
                    .get("id").asText();

            JsonNode problem = patchAs(LEAD, id, "BLOCKED", 400);

            assertThat(problem.get("detail").asText()).contains("TODO", "IN_PROGRESS", "DONE");
        }
    }

    @Nested
    @DisplayName("Gates — the read gate is not the write gate")
    class Gates {

        @Test
        @DisplayName("a Viewer sees the board")
        void viewersCanRead() throws Exception {
            assertThat(boardAs(VIEWER, PROJECT)).isNotEmpty();
        }

        @Test
        @DisplayName("a Viewer cannot create a task — 403, not 404")
        void viewersCannotWrite() throws Exception {
            // The distinction that matters: they CAN see the project, so hiding it would be a lie.
            // 403 here and 404 for NO_GRANT below is the whole of §6t's rule on one endpoint.
            createAs(VIEWER, """
                    {"projectId":"%s","title":"Not mine to add"}""".formatted(PROJECT), 403);
        }

        @Test
        @DisplayName("a member with no grant gets 404 on read and on write")
        void noGrantIsNotFound() throws Exception {
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("projectId", PROJECT.toString())
                            .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));

            createAs(NO_GRANT, """
                    {"projectId":"%s","title":"Invisible"}""".formatted(PROJECT), 404);
        }

        @Test
        @DisplayName("a sibling project the caller was not granted is 404, not an empty board")
        void siblingProjectsAreNotReachable() throws Exception {
            // OTHER_PROJECT is in the same company, so RLS and the Hibernate filter both pass it.
            // Only the project grant keeps it out — persona 3's failure mode one level up.
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("projectId", OTHER_PROJECT.toString())
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
        }

        @Test
        @DisplayName("another company's project is 404, indistinguishable from an absent one")
        void otherCompaniesAreNotFound() throws Exception {
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("projectId", UMBRA_PROJECT.toString())
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
        }
    }

    @Nested
    @DisplayName("mine=true — the personal board")
    class Mine {

        @Test
        @DisplayName("filters to the caller across stored and derived cards alike")
        void filtersBothKinds() throws Exception {
            createAs(LEAD, """
                    {"projectId":"%s","title":"Mine","assigneeId":"%s"}"""
                    .formatted(PROJECT, LEAD), 201);
            createAs(LEAD, """
                    {"projectId":"%s","title":"Nobody's"}""".formatted(PROJECT), 201);

            JsonNode mine = boardAs(LEAD, PROJECT, true);

            assertThat(mine).isNotEmpty();
            for (JsonNode card : mine) {
                assertThat(card.get("assignee").get("id").asText())
                        .as("a personal board showing somebody else's work is a leak of attention, "
                                + "and nothing would ever fail because of it")
                        .isEqualTo(LEAD.toString());
            }
            assertThat(titlesOf(mine)).doesNotContain("Nobody's");
        }
    }

    // =====================================================================
    // Request plumbing
    // =====================================================================

    private JsonNode boardAs(UUID userId, UUID projectId) throws Exception {
        return boardAs(userId, projectId, false);
    }

    private JsonNode boardAs(UUID userId, UUID projectId, boolean mine) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                        .param("projectId", projectId.toString())
                        .param("mine", String.valueOf(mine))
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode createAs(UUID userId, String body, int expectedStatus) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.post("/api/v1/work-items")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        String content = response.getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private JsonNode patchAs(UUID userId, String cardId, String status, int expectedStatus)
            throws Exception {

        var response = mvc.perform(MockMvcRequestBuilders.patch("/api/v1/work-items/" + cardId)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        String content = response.getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private static List<String> keysOfKind(JsonNode board, String kind) {
        List<String> keys = new ArrayList<>();
        for (JsonNode card : board) {
            if (kind.equals(card.get("kind").asText())) {
                keys.add(card.get("linkedKey").asText());
            }
        }
        return keys;
    }

    private static JsonNode cardOfKind(JsonNode board, String kind) {
        for (JsonNode card : board) {
            if (kind.equals(card.get("kind").asText())) {
                return card;
            }
        }
        throw new AssertionError("no card of kind " + kind + " on the board");
    }

    private static List<String> titlesOf(JsonNode board) {
        List<String> titles = new ArrayList<>();
        board.forEach(card -> titles.add(card.get("title").asText()));
        return titles;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("board:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("work_item_link", "work_item", "case_health",
                        "step_result", "test_execution", "test_run", "test_case_version",
                        "test_case", "suite")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, parent_id, key, key_seq, path, name,
                                   position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, 1,
                        CAST(? AS ltree), ?, 0, 'APPROVED', NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setString(5, "s" + id.toString().substring(0, 8).replace("-", "_"));
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, UUID ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, owner_id, created_at,
                     updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', CAST(? AS uuid), now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, ownerId == null ? null : ownerId.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, "Run " + key);
            statement.setString(7, status);
            statement.setString(8, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, boolean recorded, UUID assigneeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     assignee_id, executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        CASE WHEN ? THEN (SELECT s.id FROM case_status s
                                           WHERE s.project_id = CAST(? AS uuid) LIMIT 1)
                             ELSE NULL END,
                        CAST(? AS uuid),
                        CASE WHEN ? THEN now() ELSE NULL END,
                        now(), now())
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setBoolean(6, recorded);
            statement.setString(7, projectId.toString());
            statement.setString(8, assigneeId == null ? null : assigneeId.toString());
            statement.setBoolean(9, recorded);
            statement.executeUpdate();
        }
    }

    private void health(Connection connection, UUID companyId, UUID projectId, UUID caseId,
            String signalColumn) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_health (case_id, company_id, project_id, %s, computed_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), true, now())
                ON CONFLICT (case_id) DO NOTHING
                """.formatted(signalColumn))) {
            statement.setString(1, caseId.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.executeUpdate();
        }
    }
}
