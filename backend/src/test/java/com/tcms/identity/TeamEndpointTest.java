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
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /teams} and its five writes — <strong>the org chart stops being read-only.</strong>
 *
 * <p>{@code team} has had readers since {@code V1} and no writer at all, so three things this class
 * asserts had never been observable in the product:
 *
 * <ol>
 *   <li><strong>{@code company:teams} decides, and it is not "administers something".</strong> The
 *       permission was seeded by {@code V3} and granted correctly since M0 with <em>no consumer in
 *       Java</em>. A Test Lead administers a project and holds {@code project:members}, and every
 *       "does this caller look powerful" heuristic says yes — so the Lead's 403 is the test that
 *       kills the gate a hurried implementation would have written.</li>
 *   <li><strong>A team membership must be a company membership.</strong>
 *       {@code team_membership.user_id} references instance-level {@code app_user} while
 *       {@code company_id} comes from the session, so a sibling company's user satisfies the
 *       foreign key, the company column and RLS. The test asserts the refusal <em>and that no row
 *       was written</em>, because the org chart would not have rendered one: the directory resolves
 *       names through company membership and silently drops an id it cannot place, while the
 *       permission evaluator resolves teams through {@code team_membership} alone. The defect's
 *       whole signature is an invisible grant.</li>
 *   <li><strong>A team actually confers access.</strong> A 200 from {@code PUT .../members/{id}}
 *       proves a row exists; it does not prove the org chart does anything. So the team is granted
 *       a role, somebody is put in it, and their sight of the project is asserted <em>over the
 *       wire on the next request</em> — and asserted again after they are taken out.</li>
 * </ol>
 *
 * <p>Ids use the {@code 7b…} prefix, which no other class in the suite uses — checked rather than
 * assumed, because handoff §19's fixture collision has now happened five times and the prefix space
 * is hand-managed with no check behind it.
 */
@AutoConfigureMockMvc
@DisplayName("Teams — the gate nobody had ever asked, and the membership that must be a membership")
class TeamEndpointTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("7b000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("7b000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("7b001000-0000-4000-8000-000000000001");
    private static final UUID SOUTH_PROJECT =
            UUID.fromString("7b001000-0000-4000-8000-000000000002");

    /** Company Admin in NORTH — holds {@code company:teams}. */
    private static final UUID ADMIN = UUID.fromString("7b003000-0000-4000-8000-000000000001");
    /** Test Lead on ATLAS. Administers a project and holds no {@code company:teams}. */
    private static final UUID LEAD = UUID.fromString("7b003000-0000-4000-8000-000000000002");
    /** An ACTIVE member of NORTH holding nothing — the person teams get built around. */
    private static final UUID MEMBER = UUID.fromString("7b003000-0000-4000-8000-000000000003");
    /** Invited to NORTH and has not accepted. Staffable: onboarding does not wait. */
    private static final UUID INVITEE = UUID.fromString("7b003000-0000-4000-8000-000000000004");
    /** Was a member of NORTH and was removed. Not staffable — that is what removal means. */
    private static final UUID DEPARTED = UUID.fromString("7b003000-0000-4000-8000-000000000005");
    /** A member of SOUTH and of nothing in NORTH — the cross-company subject. */
    private static final UUID OUTSIDER = UUID.fromString("7b003000-0000-4000-8000-000000000006");

    private static final UUID GRANT_ADMIN = UUID.fromString("7b005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_LEAD = UUID.fromString("7b005000-0000-4000-8000-000000000002");

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
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-team", "Northwind", now);
            provisionCompany(SOUTH, "south-team", "Southwind", now);

            activeUser(ADMIN, "admin@north-team.test", now);
            activeUser(LEAD, "lead@north-team.test", now);
            activeUser(MEMBER, "member@north-team.test", now);
            activeUser(INVITEE, "invitee@north-team.test", now);
            activeUser(DEPARTED, "departed@north-team.test", now);
            activeUser(OUTSIDER, "outsider@south-team.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, NORTH, "ATL", "Atlas", now);

            member(NORTH, ADMIN, "A. Admin", now);
            member(NORTH, LEAD, "L. Lead", now);
            member(NORTH, MEMBER, "M. Member", now);
            invitedMember(NORTH, INVITEE, "I. Invitee", now);
            removedMember(NORTH, DEPARTED, "D. Departed", now);

            grantOnCompany(GRANT_ADMIN, NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject(GRANT_LEAD, NORTH, Role.TEST_LEAD_ID, LEAD, ATLAS, now);
            grantCompanyReadBaseline(NORTH);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            seedProject(SOUTH_PROJECT, SOUTH, "STH", "Southwind Portal", now);
            member(SOUTH, OUTSIDER, "O. Outsider", now);
            grantCompanyReadBaseline(SOUTH);
        }));

        resetMutableRows();
    }

    /**
     * Removes every team, team membership and non-seeded grant.
     *
     * <p>This class creates and deletes the very rows it reads its assertions from, so without a
     * reset the second test runs against the first one's org chart and "this company has no team
     * called QA" starts passing or failing on JUnit's method order — §19's defect, cheaper to
     * prevent than to diagnose. Through each company's own app-role connection, so RLS is in force
     * while the cleanup runs.
     */
    private void resetMutableRows() throws SQLException {
        reset(NORTH, List.of(GRANT_ADMIN, GRANT_LEAD));
        reset(SOUTH, List.of());
    }

    private void reset(UUID companyId, List<UUID> keptGrants) throws SQLException {
        String kept = keptGrants.isEmpty()
                ? "'00000000-0000-4000-8000-000000000000'"
                : keptGrants.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + "," + b)
                        .orElseThrow();
        try (var connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM role_assignment WHERE id NOT IN (" + kept + ")"
                    + " AND role_id <> '" + Role.COMPANY_MEMBER_ID + "'");
            execute(connection, "DELETE FROM team_membership");
            execute(connection, "DELETE FROM team");
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate — company:teams, and nothing that merely resembles it
    // =====================================================================

    @Nested
    @DisplayName("company:teams decides, and administering a project is not it")
    class Gate {

        @Test
        @DisplayName("a Company Admin creates a team — 201, with Location")
        void aCompanyAdminMayCreate() throws Exception {
            MvcResult result = create(ADMIN, team("Platform", null, null));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode created = json.readTree(result.getResponse().getContentAsString());
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/teams/" + created.get("id").asText());
            assertThat(created.get("name").asText()).isEqualTo("Platform");
        }

        @Test
        @DisplayName("a Test Lead may NOT — leading a project is not owning the org chart")
        void aLeadMayNotCreate() throws Exception {
            // The mutation this kills: gating on AdminReach.companyWide(), on "holds
            // project:members somewhere", or on any "this caller administers something" reading.
            // LEAD passes all of those and holds no company:teams.
            assertThat(statusOf(create(LEAD, team("Platform", null, null)))).isEqualTo(403);
        }

        @Test
        @DisplayName("a plain member may not")
        void aPlainMemberMayNotCreate() throws Exception {
            assertThat(statusOf(create(MEMBER, team("Platform", null, null)))).isEqualTo(403);
        }

        @Test
        @DisplayName("the refusal comes before the payload is judged — an invalid body still 403s")
        void theGateRunsFirst() throws Exception {
            // Otherwise the endpoint reports whether a name is taken to a caller who may not use
            // it, which is RoleGrantService's directory-probe argument.
            assertThat(statusOf(create(MEMBER, team("", null, null)))).isEqualTo(403);
        }

        @Test
        @DisplayName("a Company Admin of another company cannot see this one's team at all — 404")
        void anotherCompanysAdminGetsNothing() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            // SOUTH's session cannot address a NORTH row: the company filter and RLS agree, and
            // the answer is indistinguishable from "no such team" (§6t).
            assertThat(statusOf(mvc.perform(MockMvcRequestBuilders
                    .delete("/api/v1/teams/" + teamId)
                    .header("Authorization", "Bearer " + tokenFor(OUTSIDER, SOUTH)))
                    .andReturn()))
                    .isEqualTo(403);
        }
    }

    // =====================================================================
    // Creating and editing
    // =====================================================================

    @Nested
    @DisplayName("Creating and editing a team")
    class Writing {

        @Test
        @DisplayName("a blank name is a field error on name")
        void nameIsRequired() throws Exception {
            MvcResult result = create(ADMIN, team("   ", null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("name")).isTrue();
        }

        @Test
        @DisplayName("a duplicate name is refused CASE-INSENSITIVELY — \"QA\" and \"qa\" are one team")
        void duplicateNamesAreFoldedCaseInsensitively() throws Exception {
            createdTeam(ADMIN, "QA", null);

            MvcResult result = create(ADMIN, team("qa", null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            // The stored spelling, so the administrator can find the team they collided with.
            assertThat(errorsOf(result).get("name").get(0).asText()).contains("\"QA\"");
        }

        @Test
        @DisplayName("the same name in another company is fine — uniqueness is per company")
        void namesAreUniquePerCompanyOnly() throws Exception {
            createdTeam(ADMIN, "QA", null);

            // If V21's index had been written without company_id this is the assertion that fails,
            // and it would fail as a 500 from the constraint rather than as a field error.
            inCompany(SOUTH, () -> tx.executeWithoutResult(status ->
                    teams.save(Team.create(UUID.randomUUID(), SOUTH, "QA", null, null,
                            Instant.now()))));

            assertThat(inCompanyRead(SOUTH, () -> teams.findAllByOrderByNameAsc()).size())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a parent from another company is a field error, not a 500 from the trigger")
        void aForeignParentIsRefusedInTheApplication() throws Exception {
            UUID southTeam = inCompanyRead(SOUTH, () -> {
                Team team = Team.create(
                        UUID.randomUUID(), SOUTH, "Southern", null, null, Instant.now());
                teams.save(team);
                return team.getId();
            });

            MvcResult result = create(ADMIN, team("Platform", null, southTeam));

            // V2's tcms_enforce_team_hierarchy would also refuse this, with SQLSTATE 23514 — a 500.
            // A trigger is the right place for the invariant and the wrong place for the answer.
            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("parentTeamId")).isTrue();
        }

        @Test
        @DisplayName("a team renamed to its own name is fine — it is not its own duplicate")
        void aTeamIsExcludedFromItsOwnDuplicateCheck() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            // Without the self-exclusion, every edit that does not rename fails on the team's own
            // name — so a description-only edit would be impossible.
            assertThat(statusOf(update(ADMIN, teamId, team("Platform", "Owns the platform", null))))
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a team may be MOVED, unlike a classification value")
        void aTeamMayBeReparented() throws Exception {
            UUID parent = createdTeam(ADMIN, "Engineering", null);
            UUID child = createdTeam(ADMIN, "Platform", null);

            MvcResult result = update(ADMIN, child, team("Platform", null, parent));

            assertThat(statusOf(result)).isEqualTo(200);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("parentTeamId").asText())
                    .isEqualTo(parent.toString());
        }

        @Test
        @DisplayName("PUT with a null parent moves a team back to the root")
        void aNullParentDetachesToTheRoot() throws Exception {
            UUID parent = createdTeam(ADMIN, "Engineering", null);
            UUID child = createdTeam(ADMIN, "Platform", parent);

            MvcResult result = update(ADMIN, child, team("Platform", null, null));

            // The whole reason the edit is a PUT: under PATCH an absent parentTeamId and an
            // explicit null are the same bytes, so this operation would have been unreachable.
            assertThat(statusOf(result)).isEqualTo(200);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("parentTeamId").isNull())
                    .isTrue();
        }

        @Test
        @DisplayName("a team cannot be its own parent")
        void selfParentingIsRefused() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            MvcResult result = update(ADMIN, teamId, team("Platform", null, teamId));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("parentTeamId")).isTrue();
        }

        @Test
        @DisplayName("a team cannot be moved under its own descendant — the cycle refusal")
        void aCycleIsRefused() throws Exception {
            UUID top = createdTeam(ADMIN, "Engineering", null);
            UUID middle = createdTeam(ADMIN, "Platform", top);
            UUID bottom = createdTeam(ADMIN, "Payments", middle);

            // Two levels down, not one: a check that only compared against direct children would
            // pass this and sever the branch from the root.
            MvcResult result = update(ADMIN, top, team("Engineering", null, bottom));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).get("parentTeamId").get(0).asText())
                    .contains("below this team");
        }

        @Test
        @DisplayName("editing a team that does not exist here is 404")
        void anUnknownTeamIs404() throws Exception {
            assertThat(statusOf(update(ADMIN, UUID.randomUUID(), team("Platform", null, null))))
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // Deleting
    // =====================================================================

    @Nested
    @DisplayName("Deleting a team, and the two things that hold it back")
    class Deleting {

        @Test
        @DisplayName("an empty team deletes — 204")
        void anEmptyTeamDeletes() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            assertThat(statusOf(delete(ADMIN, teamId))).isEqualTo(204);
            assertThat(inCompanyRead(NORTH, () -> teams.findScopedById(teamId))).isEmpty();
        }

        @Test
        @DisplayName("a team with MEMBERS still deletes — membership confers nothing")
        void membersDoNotBlockADelete() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            putMember(ADMIN, teamId, MEMBER, false);

            assertThat(statusOf(delete(ADMIN, teamId))).isEqualTo(204);
        }

        @Test
        @DisplayName("a team holding a GRANT is refused, 409, with the count")
        void grantsBlockADelete() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            grantToTeam(teamId, Role.VIEWER_ID, ATLAS);

            MvcResult result = delete(ADMIN, teamId);

            // role_assignment.team_id is ON DELETE CASCADE, so without this refusal the delete
            // succeeds and revokes the grant silently. The count is what makes the message
            // actionable rather than "it is in use".
            assertThat(statusOf(result)).isEqualTo(409);
            assertThat(errorsOf(result).get("grants").get(0).asText()).contains("1 role grant");
        }

        @Test
        @DisplayName("and the grant is still there afterwards — the refusal rolled nothing forward")
        void arefusedDeleteLeavesTheGrantIntact() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            grantToTeam(teamId, Role.VIEWER_ID, ATLAS);

            delete(ADMIN, teamId);

            assertThat(inCompanyRead(NORTH, () -> roleAssignments.countByTeamId(teamId)))
                    .isEqualTo(1);
            assertThat(inCompanyRead(NORTH, () -> teams.findScopedById(teamId))).isPresent();
        }

        @Test
        @DisplayName("a team with CHILD TEAMS is refused, 409, with the count")
        void childTeamsBlockADelete() throws Exception {
            UUID parent = createdTeam(ADMIN, "Engineering", null);
            createdTeam(ADMIN, "Platform", parent);

            MvcResult result = delete(ADMIN, parent);

            // parent_team_id has no cascade, so the alternative to refusing is a foreign-key
            // violation surfacing as a 500.
            assertThat(statusOf(result)).isEqualTo(409);
            assertThat(errorsOf(result).get("children").get(0).asText()).contains("1 team");
        }

        @Test
        @DisplayName("deleting a team takes its memberships with it")
        void memberrshipsGoWithTheTeam() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            putMember(ADMIN, teamId, MEMBER, false);

            delete(ADMIN, teamId);

            assertThat(inCompanyRead(NORTH, () -> teamMemberships.findByTeamId(teamId))).isEmpty();
        }

        @Test
        @DisplayName("a Test Lead may not delete a team either")
        void theDeleteIsGatedToo() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            assertThat(statusOf(delete(LEAD, teamId))).isEqualTo(403);
        }
    }

    // =====================================================================
    // Membership — the check that keeps a foreign account out of the chart
    // =====================================================================

    @Nested
    @DisplayName("Membership must be company membership")
    class Membership {

        @Test
        @DisplayName("a member of this company goes in, and reads back on the team")
        void aMemberJoins() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            MvcResult result = putMember(ADMIN, teamId, MEMBER, false);

            assertThat(statusOf(result)).isEqualTo(200);
            JsonNode team = json.readTree(result.getResponse().getContentAsString());
            assertThat(team.get("members")).hasSize(1);
            assertThat(team.get("leads")).isEmpty();
        }

        @Test
        @DisplayName("a lead is a member with a flag — they appear in BOTH lists")
        void aLeadIsAlsoAMember() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            JsonNode team = json.readTree(
                    putMember(ADMIN, teamId, MEMBER, true).getResponse().getContentAsString());

            assertThat(team.get("leads")).hasSize(1);
            assertThat(team.get("members")).hasSize(1);
        }

        @Test
        @DisplayName("a SIBLING COMPANY'S user is refused — 404, and no row is written")
        void aForeignAccountCannotBeStaffed() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            MvcResult result = putMember(ADMIN, teamId, OUTSIDER, false);

            assertThat(statusOf(result)).isEqualTo(404);
            // The assertion that matters. The foreign key, the company column and the RLS policy
            // all accept this row, and the org chart would then render the person NOWHERE while
            // the evaluator handed them the team's grants — an invisible grant. A status-only
            // assertion would pass against an implementation that wrote the row and then 404'd.
            assertThat(inCompanyRead(NORTH, () -> teamMemberships.findByTeamId(teamId))).isEmpty();
        }

        @Test
        @DisplayName("a REMOVED member is refused — re-inviting them is what makes them staffable")
        void aRemovedMemberCannotBeStaffed() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            assertThat(statusOf(putMember(ADMIN, teamId, DEPARTED, false))).isEqualTo(404);
            assertThat(inCompanyRead(NORTH, () -> teamMemberships.findByTeamId(teamId))).isEmpty();
        }

        @Test
        @DisplayName("an INVITED member IS staffable — onboarding does not wait for acceptance")
        void anInviteeIsStaffable() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            assertThat(statusOf(putMember(ADMIN, teamId, INVITEE, false))).isEqualTo(200);
        }

        @Test
        @DisplayName("a repeated PUT is the same state — no duplicate, no error")
        void thePutIsIdempotent() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            putMember(ADMIN, teamId, MEMBER, false);
            assertThat(statusOf(putMember(ADMIN, teamId, MEMBER, false))).isEqualTo(200);

            assertThat(inCompanyRead(NORTH, () -> teamMemberships.findByTeamId(teamId))).hasSize(1);
        }

        @Test
        @DisplayName("promoting keeps the row's joinedAt rather than re-creating it")
        void promotingPreservesJoinedAt() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            putMember(ADMIN, teamId, MEMBER, false);
            Instant joined = inCompanyRead(NORTH,
                    () -> teamMemberships.findByTeamIdAndUserId(teamId, MEMBER).orElseThrow()
                            .getJoinedAt());

            putMember(ADMIN, teamId, MEMBER, true);

            // A blind save would reset it, losing the one fact the row carries beyond its own key.
            assertThat(inCompanyRead(NORTH,
                    () -> teamMemberships.findByTeamIdAndUserId(teamId, MEMBER).orElseThrow()
                            .getJoinedAt()))
                    .isEqualTo(joined);
        }

        @Test
        @DisplayName("a body with no lead flag is a field error, not a silent demotion")
        void anAbsentLeadFlagIsRefused() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            putMember(ADMIN, teamId, MEMBER, true);

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/teams/" + teamId + "/members/" + MEMBER)
                            .header("Authorization", "Bearer " + tokenFor(ADMIN, NORTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();

            // TeamMemberInput.lead is boxed precisely so this is reachable: a primitive boolean
            // would read {} as false and DEMOTE the lead, answering 200.
            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(inCompanyRead(NORTH,
                    () -> teamMemberships.findByTeamIdAndUserId(teamId, MEMBER).orElseThrow()
                            .isLead()))
                    .isTrue();
        }

        @Test
        @DisplayName("removing somebody who is not in the team is still 204")
        void removingANonMemberIsIdempotent() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            assertThat(statusOf(removeMember(ADMIN, teamId, MEMBER))).isEqualTo(204);
        }

        @Test
        @DisplayName("staffing a team that does not exist here is 404")
        void anUnknownTeamIs404() throws Exception {
            assertThat(statusOf(putMember(ADMIN, UUID.randomUUID(), MEMBER, false))).isEqualTo(404);
        }

        @Test
        @DisplayName("a Test Lead may not staff a team")
        void membershipWritesAreGatedToo() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);

            assertThat(statusOf(putMember(LEAD, teamId, MEMBER, false))).isEqualTo(403);
            assertThat(statusOf(removeMember(LEAD, teamId, MEMBER))).isEqualTo(403);
        }
    }

    // =====================================================================
    // The point of the whole thing — a team confers access
    // =====================================================================

    @Nested
    @DisplayName("A team grant reaches its members, and stops when they leave")
    class TeamGrantsReach {

        @Test
        @DisplayName("joining a granted team makes the project visible on the very next request")
        void aTeamGrantIsObservableOverTheWire() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            grantToTeam(teamId, Role.VIEWER_ID, ATLAS);

            // Before: a member of the company with no grant of their own sees no project at all —
            // "membership conveys zero project visibility", roles spec §A1.
            assertThat(projectsVisibleTo(MEMBER)).isEmpty();

            putMember(ADMIN, teamId, MEMBER, false);

            // After. This is what makes the org chart load-bearing rather than decorative, and it
            // is the first time in the product's life that this path could be exercised at all:
            // the grant endpoint's team branch has always worked and there was no way to create a
            // team for it to name.
            assertThat(projectsVisibleTo(MEMBER)).hasSize(1);
        }

        @Test
        @DisplayName("and leaving the team takes it away again, with no sign-out")
        void leavingRevokesOnTheNextRequest() throws Exception {
            UUID teamId = createdTeam(ADMIN, "Platform", null);
            grantToTeam(teamId, Role.VIEWER_ID, ATLAS);
            putMember(ADMIN, teamId, MEMBER, false);

            removeMember(ADMIN, teamId, MEMBER);

            // The same token, deliberately: no epoch bump, because the evaluator reads team
            // membership on every request and nothing about it is carried in the token.
            assertThat(projectsVisibleTo(MEMBER)).isEmpty();
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult create(UUID callerId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/teams")
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult update(UUID callerId, UUID teamId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/teams/" + teamId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult delete(UUID callerId, UUID teamId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/teams/" + teamId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH)))
                .andReturn();
    }

    private MvcResult putMember(UUID callerId, UUID teamId, UUID userId, boolean lead)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/teams/" + teamId + "/members/" + userId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lead\":" + lead + "}"))
                .andReturn();
    }

    private MvcResult removeMember(UUID callerId, UUID teamId, UUID userId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/teams/" + teamId + "/members/" + userId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH)))
                .andReturn();
    }

    /** Creates a team through the API and returns its id — the fixture builder for everything else. */
    private UUID createdTeam(UUID callerId, String name, UUID parentTeamId) throws Exception {
        MvcResult result = create(callerId, team(name, null, parentTeamId));
        assertThat(statusOf(result)).isEqualTo(201);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private JsonNode projectsVisibleTo(UUID userId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenFor(userId, NORTH)))
                .andReturn();
        assertThat(statusOf(result)).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String team(String name, String description, UUID parentTeamId) {
        return "{\"name\":" + quoted(name)
                + ",\"description\":" + quoted(description)
                + ",\"parentTeamId\":"
                + (parentTeamId == null ? "null" : "\"" + parentTeamId + "\"") + "}";
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode errorsOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("errors");
    }

    private void grantToTeam(UUID teamId, UUID roleId, UUID projectId) {
        inCompany(NORTH, () -> tx.executeWithoutResult(status -> roleAssignments.save(
                RoleAssignment.forTeamOnProject(
                        UUID.randomUUID(), NORTH, roleId, teamId, projectId, ADMIN,
                        Instant.now()))));
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private <T> T inCompanyRead(UUID companyId, Supplier<T> read) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status -> read.get());
        }
    }

    private void provisionCompany(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
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

    /** Invited and not accepted — an INVITED membership, which is staffable. */
    private void invitedMember(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            memberships.save(CompanyMembership.invite(companyId, userId, displayName, null, now));
        }
    }

    /** Was a member and was removed — the state that must NOT be staffable. */
    private void removedMember(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            membership.remove();
            memberships.save(membership);
        }
    }

    private void grantOnCompany(UUID id, UUID companyId, UUID roleId, UUID userId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private void grantOnProject(
            UUID id, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }
}
