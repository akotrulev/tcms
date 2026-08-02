package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
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
 * {@code GET /projects/{projectId}/members} — who can be given work here (§54).
 *
 * <h2>The two failures this exists to catch, and neither of them throws</h2>
 *
 * <ol>
 *   <li><strong>A member resolved from {@code role_assignment} instead of the evaluator.</strong>
 *       The obvious implementation — "rows where {@code project_id} = this project" — finds only
 *       people granted <em>directly</em>. It silently omits everybody staffed through a team, which
 *       is the arrangement the org chart exists to encourage, and every Company Admin, who can see
 *       every project. Nothing fails: the endpoint answers 200 with a shorter list, and the missing
 *       colleague simply cannot be assigned anything.</li>
 *   <li><strong>The list and the create disagreeing.</strong> This endpoint feeds a picker;
 *       {@code POST /work-items} validates the {@code assigneeId} it produces. If those are two
 *       expressions of one rule they drift, and the harmful direction is a picker offering somebody
 *       the create refuses — a form the user fills in correctly and cannot submit, with a field
 *       error naming a person they were just shown. {@link Agreement} asserts the two are the same
 *       set rather than trusting that they are.</li>
 * </ol>
 *
 * <h2>The fixture</h2>
 *
 * <p>Acme's project is staffed four different ways on purpose — directly, through a team,
 * company-wide, and not at all — plus an invitee who holds a grant and has not accepted. Umbra
 * exists so that "everyone in the instance" and "everyone in this company" are distinguishable.
 *
 * <p>Ids, slugs and project keys are unique to this class ({@code bd…}, {@code pm-}). §19's defect
 * cost eleven tests to a shared slug that was green alone and red in the suite; every naturally
 * keyed fixture value needs the class prefix, not just the primary keys.
 */
@AutoConfigureMockMvc
@DisplayName("Project members — the set an assignment may name")
class ProjectMemberEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("bd000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("bd000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("bd001000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT = UUID.fromString("bd001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_PROJECT = UUID.fromString("bd001000-0000-4000-8000-000000000003");

    /** Test Lead, granted directly on PROJECT. The caller in most tests. */
    private static final UUID LEAD = UUID.fromString("bd003000-0000-4000-8000-000000000001");
    /** Viewer, granted directly. Holds project:read, so assignable — see the test. */
    private static final UUID VIEWER = UUID.fromString("bd003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing at all. */
    private static final UUID NO_GRANT = UUID.fromString("bd003000-0000-4000-8000-000000000003");
    /** Reaches PROJECT only through a TEAM's grant — the row-reading implementation misses them. */
    private static final UUID VIA_TEAM = UUID.fromString("bd003000-0000-4000-8000-000000000004");
    /** Company Admin: no project grant at all, sees every project. Also missed by row-reading. */
    private static final UUID COMPANY_ADMIN = UUID.fromString("bd003000-0000-4000-8000-000000000005");
    /** Granted on PROJECT, membership still INVITED. Must not be offerable. */
    private static final UUID INVITEE = UUID.fromString("bd003000-0000-4000-8000-000000000006");
    /** Umbra's Test Lead. Another client's person entirely. */
    private static final UUID FOREIGNER = UUID.fromString("bd003000-0000-4000-8000-000000000007");

    private static final UUID TEAM = UUID.fromString("bd006000-0000-4000-8000-000000000001");

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
    private TeamRepository teams;

    @Autowired
    private TeamMembershipRepository teamMemberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "pm-acme", "Acme", now);
            company(UMBRA, "pm-umbra", "Umbra", now);
            activeUser(LEAD, "lead@pm-acme.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@pm-acme.test", "Val Viewer", now);
            activeUser(NO_GRANT, "nogrant@pm-acme.test", "Nell Nogrant", now);
            activeUser(VIA_TEAM, "team@pm-acme.test", "Tomas Team", now);
            activeUser(COMPANY_ADMIN, "admin@pm-acme.test", "Ada Admin", now);
            activeUser(INVITEE, "invitee@pm-acme.test", "Ivan Invitee", now);
            activeUser(FOREIGNER, "lead@pm-umbra.test", "Fen Foreign", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "PMA", "Members Project", now);
            seedProject(OTHER_PROJECT, ACME, "PMB", "Sibling Project", now);

            member(ACME, LEAD, "L. Lead (Acme)", now, true);
            member(ACME, VIEWER, "V. Viewer (Acme)", now, true);
            member(ACME, NO_GRANT, "N. Nogrant (Acme)", now, true);
            member(ACME, VIA_TEAM, "T. Team (Acme)", now, true);
            member(ACME, COMPANY_ADMIN, "A. Admin (Acme)", now, true);
            // Invited and never accepted — the one membership that is not ACTIVE.
            member(ACME, INVITEE, "I. Invitee (Acme)", now, false);

            team(TEAM, ACME, "Payments squad", now);
            joinTeam(ACME, TEAM, VIA_TEAM, now);

            grant("lead", RoleAssignment.forUserOnProject(
                    id("lead"), ACME, Role.TEST_LEAD_ID, LEAD, PROJECT, null, now));
            grant("viewer", RoleAssignment.forUserOnProject(
                    id("viewer"), ACME, Role.VIEWER_ID, VIEWER, PROJECT, null, now));
            grant("team", RoleAssignment.forTeamOnProject(
                    id("team"), ACME, Role.TEST_ENGINEER_ID, TEAM, PROJECT, null, now));
            grant("admin", RoleAssignment.forUserOnCompany(
                    id("admin"), ACME, Role.COMPANY_ADMIN_ID, COMPANY_ADMIN, null, now));
            grant("invitee", RoleAssignment.forUserOnProject(
                    id("invitee"), ACME, Role.TEST_ENGINEER_ID, INVITEE, PROJECT, null, now));
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_PROJECT, UMBRA, "PMU", "Umbra Project", now);
            member(UMBRA, FOREIGNER, "F. Foreign (Umbra)", now, true);
            grant("foreign", RoleAssignment.forUserOnProject(
                    id("foreign"), UMBRA, Role.TEST_LEAD_ID, FOREIGNER, UMBRA_PROJECT, null, now));
        }));
    }

    // =====================================================================

    @Nested
    @DisplayName("Who is on the list")
    class Membership {

        @Test
        @DisplayName("somebody granted directly is on it")
        void directGrantsAppear() throws Exception {
            assertThat(idsOf(membersAs(LEAD, PROJECT))).contains(LEAD.toString());
        }

        @Test
        @DisplayName("a Viewer is on it — assignable is not the same question as writable")
        void viewersAreAssignable() throws Exception {
            // A Viewer cannot create a task, and can perfectly well be given one: §6l's board is
            // how a client's own stakeholder is asked to review something. Filtering this list by
            // workitem:manage would quietly make "who can be handed work" mean "who can hand it
            // out", which is a different product.
            assertThat(idsOf(membersAs(LEAD, PROJECT))).contains(VIEWER.toString());
        }

        @Test
        @DisplayName("somebody who reaches the project only through a TEAM is on it")
        void teamGrantsAppear() throws Exception {
            // The test that fails an implementation reading role_assignment for the project. It
            // would answer 200 with a plausible list and Tomas would simply be un-assignable —
            // an omission nothing surfaces, on the exact staffing arrangement the org chart
            // encourages.
            assertThat(idsOf(membersAs(LEAD, PROJECT)))
                    .as("a team's grant is a grant")
                    .contains(VIA_TEAM.toString());
        }

        @Test
        @DisplayName("a Company Admin with no project grant at all is on it")
        void companyWideGrantsAppear() throws Exception {
            // The second miss of the same wrong implementation, and the more visible one: the
            // person most likely to be assigned anything is absent from every project's picker.
            assertThat(idsOf(membersAs(LEAD, PROJECT))).contains(COMPANY_ADMIN.toString());
        }

        @Test
        @DisplayName("a company member with no grant on this project is NOT on it")
        void membershipAloneIsNotEnough() throws Exception {
            // Company membership conveys zero project visibility (roles spec §A1). A card assigned
            // to Nell renders her name, shows on her board, and 404s when she clicks it — and the
            // person who assigned it never sees anything wrong.
            assertThat(idsOf(membersAs(LEAD, PROJECT))).doesNotContain(NO_GRANT.toString());
        }

        @Test
        @DisplayName("an INVITED member holding a grant is NOT on it")
        void invitationsConveyNothingYet() throws Exception {
            // Ivan has a Test Engineer grant on this very project and has never accepted his
            // invitation. An unredeemed invitation must not become a way to be handed work.
            assertThat(idsOf(membersAs(LEAD, PROJECT))).doesNotContain(INVITEE.toString());
        }

        @Test
        @DisplayName("another company's people are not on it")
        void otherCompaniesAreInvisible() throws Exception {
            assertThat(idsOf(membersAs(LEAD, PROJECT))).doesNotContain(FOREIGNER.toString());
        }

        @Test
        @DisplayName("the display name is the membership's, not the account's canonical one")
        void namesComeFromTheMembership() throws Exception {
            // §6u: the same contractor is "A. Kotrulev" to one client and "Aleks (Vendor)" to
            // another, and neither client may see the other's name for them. `AppUser` holds
            // "Leonora Lead"; this company calls her "L. Lead (Acme)".
            JsonNode lead = memberWithId(membersAs(LEAD, PROJECT), LEAD);
            assertThat(lead.get("displayName").asText()).isEqualTo("L. Lead (Acme)");
        }

        @Test
        @DisplayName("sorted by display name, so the picker does not reshuffle between requests")
        void sortedByName() throws Exception {
            List<String> names = new ArrayList<>();
            membersAs(LEAD, PROJECT).forEach(m -> names.add(m.get("displayName").asText()));
            assertThat(names).isSorted();
        }
    }

    @Nested
    @DisplayName("The gate")
    class Gate {

        @Test
        @DisplayName("a sibling project the caller holds no grant on is 404, not an empty list")
        void unreachableProjectsAre404() throws Exception {
            // §6t: an unreachable project and a non-existent one must be indistinguishable. An
            // empty member list would confirm that OTHER_PROJECT exists.
            assertThat(statusOf(LEAD, ACME, OTHER_PROJECT)).isEqualTo(404);
        }

        @Test
        @DisplayName("another company's project is 404")
        void foreignProjectsAre404() throws Exception {
            assertThat(statusOf(LEAD, ACME, UMBRA_PROJECT)).isEqualTo(404);
        }

        @Test
        @DisplayName("a member with no grant anywhere gets 404")
        void noGrantIs404() throws Exception {
            assertThat(statusOf(NO_GRANT, ACME, PROJECT)).isEqualTo(404);
        }

        @Test
        @DisplayName("a Viewer may read it — seeing the project is the whole gate")
        void viewersMayRead() throws Exception {
            // Deliberately NOT project:members, which is the permission to change staffing. A Test
            // Engineer must be able to create a task and name somebody on it; requiring the
            // management permission to read this would leave them able to do the first and not the
            // second.
            assertThat(statusOf(VIEWER, ACME, PROJECT)).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Agreement with the write side — the reason the predicate is shared")
    class Agreement {

        @Test
        @DisplayName("everybody on the list is accepted as an assignee by POST /work-items")
        void everyMemberIsAssignable() throws Exception {
            // The property that makes a picker built from this endpoint honest. If these two ever
            // disagree, the user fills the form in correctly, submits, and gets a field error
            // naming a person the product just offered them.
            for (JsonNode member : membersAs(LEAD, PROJECT)) {
                String assigneeId = member.get("id").asText();
                int status = createTask(LEAD, assigneeId);
                assertThat(status)
                        .as("%s is on the members list and the create refused them",
                                member.get("displayName").asText())
                        .isEqualTo(201);
            }
        }

        @Test
        @DisplayName("somebody absent from the list is refused by POST /work-items")
        void nonMembersAreRefused() throws Exception {
            // The other direction, so the test above cannot be satisfied by a create that accepts
            // everybody.
            assertThat(createTask(LEAD, NO_GRANT.toString())).isEqualTo(400);
            assertThat(createTask(LEAD, INVITEE.toString())).isEqualTo(400);
        }
    }

    // =====================================================================
    // Request plumbing
    // =====================================================================

    private JsonNode membersAs(UUID userId, UUID projectId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    /** Creates a task assigned to {@code assigneeId}, and answers the status code. */
    private int createTask(UUID callerId, String assigneeId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/work-items")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s","title":"Assignment probe","assigneeId":"%s"}"""
                                .formatted(PROJECT, assigneeId)))
                .andReturn().getResponse().getStatus();
    }

    private static List<String> idsOf(JsonNode members) {
        List<String> ids = new ArrayList<>();
        members.forEach(member -> ids.add(member.get("id").asText()));
        return ids;
    }

    private static JsonNode memberWithId(JsonNode members, UUID userId) {
        for (JsonNode member : members) {
            if (userId.toString().equals(member.get("id").asText())) {
                return member;
            }
        }
        throw new AssertionError("no member with id " + userId);
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private static UUID id(String marker) {
        return UUID.nameUUIDFromBytes(("pm:" + marker).getBytes());
    }

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

    private void member(UUID companyId, UUID userId, String displayName, Instant now, boolean active) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            if (active) {
                membership.accept(now);
            }
            memberships.save(membership);
        }
    }

    private void team(UUID id, UUID companyId, String name, Instant now) {
        if (teams.findById(id).isEmpty()) {
            teams.save(Team.create(id, companyId, name, null, null, now));
        }
    }

    private void joinTeam(UUID companyId, UUID teamId, UUID userId, Instant now) {
        if (teamMemberships.findById(new TeamMembershipId(teamId, userId)).isEmpty()) {
            teamMemberships.save(TeamMembership.join(companyId, teamId, userId, false, now));
        }
    }

    private void grant(String marker, RoleAssignment assignment) {
        if (roleAssignments.findById(id(marker)).isEmpty()) {
            roleAssignments.save(assignment);
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
}
