package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@code /users}, {@code /teams}, {@code /roles} and {@code /role-assignments} — <strong>the slice
 * that turns a provisioned company into a staffed one.</strong>
 *
 * <p>Four things here are covered nowhere else in the suite, and each is the reason a particular
 * defect would otherwise ship:
 *
 * <ol>
 *   <li><strong>A gate chosen by the payload.</strong> Every other write names one permission. This
 *       one names {@code project:members} or {@code company:members} depending on whether the
 *       request carries a project — and the failure it hides is a Test Lead granting a colleague
 *       Company Admin, or being unable to staff the project they run.</li>
 *   <li><strong>The grant is observable as sight of a project.</strong> A 201 proves a row was
 *       written; it does not prove the colleague can now see anything. Asserted over the wire on
 *       the very next request, in both directions, because "membership conveys zero project
 *       visibility" is the invariant this endpoint exists to lift.</li>
 *   <li><strong>Re-granting returns the existing row.</strong> A duplicate would give one person
 *       two grants to revoke, so revoking the visible one leaves them holding the role.</li>
 *   <li><strong>The self-lockout refusal.</strong> The only refusal in the product that is
 *       computed by doing the write and asking the evaluator again, so it has to be shown both to
 *       fire and to roll back.</li>
 * </ol>
 *
 * <p>Ids use the {@code 7a…} prefix, which no other class in the suite uses. Sharing a prefix is
 * how handoff §19's first defect happened: one class's no-grant persona becomes another's granted
 * lead, and the symptom reads like a broken privilege split.
 */
@AutoConfigureMockMvc
@DisplayName("Role grants — the scope-dependent gate, the idempotent re-grant, and the lockout")
class RoleGrantEndpointTest extends IntegrationTestBase {

    private static final UUID WEST = UUID.fromString("7a000000-0000-4000-8000-00000000000a");
    private static final UUID EAST = UUID.fromString("7a000000-0000-4000-8000-00000000000b");

    /** WEST's two projects. LEAD runs the first and holds nothing on the second. */
    private static final UUID ALPHA = UUID.fromString("7a001000-0000-4000-8000-000000000001");
    private static final UUID BETA = UUID.fromString("7a001000-0000-4000-8000-000000000002");
    private static final UUID EAST_PROJECT = UUID.fromString("7a002000-0000-4000-8000-000000000001");

    /** Company Admin in WEST — a COMPANY-scope grant, which is what a company-wide grant needs. */
    private static final UUID ADMIN = UUID.fromString("7a003000-0000-4000-8000-000000000001");
    /** Test Lead on ALPHA. Holds {@code project:members} there and nowhere else. */
    private static final UUID LEAD = UUID.fromString("7a003000-0000-4000-8000-000000000002");
    /** Test Lead on BETA — so BETA is somebody's project rather than nobody's. */
    private static final UUID BETA_LEAD = UUID.fromString("7a003000-0000-4000-8000-000000000003");
    /** An ACTIVE member of WEST holding nothing at all — the colleague this slice exists for. */
    private static final UUID NEWCOMER = UUID.fromString("7a003000-0000-4000-8000-000000000004");
    /** In WEST's QA team, and holding nothing directly — the team-grant subject. */
    private static final UUID TEAM_MEMBER = UUID.fromString("7a003000-0000-4000-8000-000000000005");
    private static final UUID EAST_ADMIN = UUID.fromString("7a003000-0000-4000-8000-000000000006");
    /** Instance Admin — SYSTEM scope, no membership row anywhere. */
    private static final UUID OPERATOR = UUID.fromString("7a003000-0000-4000-8000-000000000007");

    private static final UUID QA_TEAM = UUID.fromString("7a004000-0000-4000-8000-000000000001");

    /** The seeded grants, kept by the reset; everything else a test writes is removed. */
    private static final UUID GRANT_ADMIN = UUID.fromString("7a005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_LEAD = UUID.fromString("7a005000-0000-4000-8000-000000000002");
    private static final UUID GRANT_BETA_LEAD = UUID.fromString("7a005000-0000-4000-8000-000000000003");
    private static final UUID GRANT_EAST_ADMIN = UUID.fromString("7a005000-0000-4000-8000-000000000004");

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
    private InstanceRoleAssignmentRepository instanceRoleAssignments;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(WEST, "west-grant", "Westwind", now);
            provisionCompany(EAST, "east-grant", "Eastwind", now);

            activeUser(ADMIN, "admin@west-grant.test", now);
            activeUser(LEAD, "lead@west-grant.test", now);
            activeUser(BETA_LEAD, "beta-lead@west-grant.test", now);
            activeUser(NEWCOMER, "newcomer@west-grant.test", now);
            activeUser(TEAM_MEMBER, "team@west-grant.test", now);
            activeUser(EAST_ADMIN, "admin@east-grant.test", now);
            activeUser(OPERATOR, "operator@instance-grant.test", now);
        });

        // No membership row for the operator, on purpose: the SYSTEM term is deliberately not
        // behind the company gate, and a fixture that quietly gave them one would let this class
        // pass whether that is true or not.
        tx.executeWithoutResult(status -> grantInstanceAdmin(OPERATOR, now));

        inCompany(WEST, () -> tx.executeWithoutResult(status -> {
            seedProject(ALPHA, WEST, "ALP", "Alpha Platform", now);
            seedProject(BETA, WEST, "BET", "Beta Platform", now);

            member(WEST, ADMIN, "A. Admin", now);
            member(WEST, LEAD, "L. Lead", now);
            member(WEST, BETA_LEAD, "B. Lead", now);
            member(WEST, NEWCOMER, "N. Newcomer", now);
            member(WEST, TEAM_MEMBER, "T. Member", now);

            if (teams.findScopedById(QA_TEAM).isEmpty()) {
                teams.save(Team.create(QA_TEAM, WEST, "QA Chapter", null, null, now));
                teamMemberships.save(TeamMembership.join(WEST, QA_TEAM, TEAM_MEMBER, true, now));
            }

            grantOnCompany(GRANT_ADMIN, WEST, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject(GRANT_LEAD, WEST, Role.TEST_LEAD_ID, LEAD, ALPHA, now);
            grantOnProject(GRANT_BETA_LEAD, WEST, Role.TEST_LEAD_ID, BETA_LEAD, BETA, now);

            // The company:read baseline every member holds (V15), through the same statement
            // provisioning and invitation acceptance run — not a fixture-only copy of it. Without
            // it these personas are members the product could not have created, and AdminReach
            // would answer "does not read this company" for the Test Lead this class is built on.
            grantCompanyReadBaseline(WEST);
        }));

        inCompany(EAST, () -> tx.executeWithoutResult(status -> {
            seedProject(EAST_PROJECT, EAST, "EST", "Eastwind Portal", now);
            member(EAST, EAST_ADMIN, "E. Admin", now);
            grantOnCompany(GRANT_EAST_ADMIN, EAST, Role.COMPANY_ADMIN_ID, EAST_ADMIN, now);
            grantCompanyReadBaseline(EAST);
        }));

        resetMutableRows();
    }

    /**
     * Puts back every grant a test could have written or removed.
     *
     * <p>This class writes and deletes {@code role_assignment}, which is also what it reads its
     * personas from — so without a reset the second test in the class runs against the first one's
     * grants, and "a Test Lead may not staff BETA" starts passing or failing on JUnit's method
     * order. Green alone and red in the suite is handoff §19's defect and is cheaper to prevent
     * than to diagnose.
     *
     * <p>Through each company's own app-role connection, so RLS is in force while the cleanup runs:
     * a delete that reached across the boundary here would be the very leak the isolation
     * assertions in this class claim cannot happen.
     */
    private void resetMutableRows() throws SQLException {
        reset(WEST, List.of(GRANT_ADMIN, GRANT_LEAD, GRANT_BETA_LEAD));
        reset(EAST, List.of(GRANT_EAST_ADMIN));
    }

    private void reset(UUID companyId, List<UUID> keptGrants) throws SQLException {
        String kept = keptGrants.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + "," + b)
                .orElseThrow();
        try (var connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
            // The membership baseline is kept by role rather than by id: it is granted by the
            // product on joining and is not a row any test here writes or revokes, so listing its
            // ids would mean re-deriving them on every seed. Deleting it would leave personas the
            // product could not have created.
            execute(connection, "DELETE FROM role_assignment WHERE id NOT IN (" + kept + ")"
                    + " AND role_id <> '" + Role.COMPANY_MEMBER_ID + "'");
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate — chosen by the scope of the grant being made
    // =====================================================================

    @Nested
    @DisplayName("The gate depends on the scope, not on one permission")
    class Gate {

        @Test
        @DisplayName("a Test Lead staffs the project they run — 201, with Location")
        void aLeadMayStaffTheirOwnProject() throws Exception {
            MvcResult result = post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode created = json.readTree(result.getResponse().getContentAsString());
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/role-assignments/" + created.get("id").asText());
        }

        @Test
        @DisplayName("and NOT another project in the same company — 403")
        void aLeadMayNotStaffSomebodyElsesProject() throws Exception {
            // The mutation this kills: gating on "holds project:members somewhere", or on the union
            // of the caller's project grants. LEAD holds project:members on ALPHA, so every "does
            // this caller look powerful" heuristic says yes here.
            assertThat(statusOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, BETA))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("nor company-wide — leading one project is not company:members")
        void aLeadMayNotGrantAcrossTheWholeCompany() throws Exception {
            // A company-wide grant covers every project present and future. If project:members
            // authorised it, a Test Lead could appoint themselves a colleague's Company Admin.
            assertThat(statusOf(post(LEAD, WEST, userGrant(Role.COMPANY_ADMIN_ID, NEWCOMER, null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("a Company Admin grants company-wide — 201")
        void aCompanyAdminMayGrantCompanyWide() throws Exception {
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.COMPANY_ADMIN_ID, NEWCOMER, null))))
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a Company Admin grants on a project they hold no project grant on — 201")
        void aCompanyAdminMayStaffAnyProject() throws Exception {
            // Their project:members comes from the COMPANY term, which covers every project present
            // and future — the property that makes Company Admin work at all.
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.TEST_ENGINEER_ID, NEWCOMER, BETA))))
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("an Instance Admin grants with no membership row anywhere — 201")
        void theSystemTermIsNotBehindTheCompanyGate() throws Exception {
            assertThat(statusOf(post(OPERATOR, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA))))
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a member holding nothing may not grant anything")
        void aPlainMemberMayNotGrant() throws Exception {
            assertThat(statusOf(post(NEWCOMER, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("the refusal names no permission key and writes nothing")
        void refusalIsQuietAndLeavesNoTrace() throws Exception {
            long before = grantCount(WEST);
            MvcResult refused = post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, BETA));

            String detail = json.readTree(refused.getResponse().getContentAsString())
                    .get("detail").asText();
            assertThat(detail)
                    .as("naming the key publishes the catalogue to the one caller not entitled to it")
                    .doesNotContain("project:members")
                    .doesNotContain("company:members")
                    .isEqualTo("You do not have permission to manage access here.");
            assertThat(grantCount(WEST)).isEqualTo(before);
            assertThat(auditActions(WEST)).isEmpty();
        }

        @Test
        @DisplayName("a refused caller is refused BEFORE the payload is looked at")
        void thePermissionIsCheckedFirst() throws Exception {
            // A Test Lead naming a person who is not a member of this company gets 403, not 400.
            // Validate first and the endpoint answers "that person is not a member" to a caller who
            // may not staff anything — a membership probe handed to exactly the person just
            // refused. The two statuses are the only thing that distinguishes the orders.
            assertThat(statusOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, EAST_ADMIN, BETA))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("an anonymous caller is 401, before any handler runs")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders.post("/api/v1/role-assignments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // What the grant is FOR
    // =====================================================================

    @Nested
    @DisplayName("A grant is sight of a project, which is the whole point")
    class Effect {

        @Test
        @DisplayName("before it, a member of the company sees no projects at all")
        void membershipConveysZeroProjectVisibility() throws Exception {
            // Roles spec §A1. If this ever passes with a non-empty list, the second gate has gone.
            assertThat(listProjects(NEWCOMER, WEST)).isEmpty();
        }

        @Test
        @DisplayName("after it, they see exactly the project they were granted")
        void aGrantIsImmediatelyVisibleToItsSubject() throws Exception {
            assertThat(statusOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA))))
                    .isEqualTo(201);

            assertThat(idsOf(listProjects(NEWCOMER, WEST)))
                    .containsExactly(ALPHA);
        }

        @Test
        @DisplayName("and the role they were granted is what myRole reports")
        void theRoleIsTheOneThatWasGranted() throws Exception {
            post(LEAD, WEST, userGrant(Role.TEST_ENGINEER_ID, NEWCOMER, ALPHA));

            assertThat(listProjects(NEWCOMER, WEST).getFirst().get("myRole").asText())
                    .isEqualTo("Test Engineer");
        }

        @Test
        @DisplayName("a grant to a TEAM reaches the team's members")
        void aTeamGrantConfersThroughMembership() throws Exception {
            assertThat(idsOf(listProjects(TEAM_MEMBER, WEST))).isEmpty();

            assertThat(statusOf(post(LEAD, WEST, teamGrant(Role.VIEWER_ID, QA_TEAM, ALPHA))))
                    .isEqualTo(201);

            assertThat(idsOf(listProjects(TEAM_MEMBER, WEST))).containsExactly(ALPHA);
        }

        @Test
        @DisplayName("a team grant names the team and no user, which the contract does not allow for")
        void aTeamGrantHasNoUser() throws Exception {
            JsonNode created = json.readTree(
                    post(LEAD, WEST, teamGrant(Role.VIEWER_ID, QA_TEAM, ALPHA))
                            .getResponse().getContentAsString());

            // The divergence recorded in RoleAssignmentView#user: the contract declares `user`
            // required and non-nullable, and ck_role_assignment_subject makes user XOR team, so a
            // team grant has no user to name. Asserted rather than papered over, so the day the
            // contract is corrected this test says what changed.
            assertThat(created.get("user").isNull()).isTrue();
            assertThat(created.get("team").get("id").asText()).isEqualTo(QA_TEAM.toString());
            assertThat(created.get("team").get("name").asText()).isEqualTo("QA Chapter");
        }

        @Test
        @DisplayName("the grant is audited, with the actor, the subject and the scope")
        void theGrantIsAudited() throws Exception {
            UUID granted = idOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA)));

            List<AuditEvent> recorded = inCompanyRead(WEST,
                    () -> auditEvents.findByActionOrderByOccurredAtDesc("role_assignment.granted"));

            assertThat(recorded).hasSize(1);
            AuditEvent event = recorded.getFirst();
            assertThat(event.getActorUserId()).isEqualTo(LEAD);
            assertThat(event.getTargetIds()).containsExactly(granted);
            assertThat(event.getProjectId()).isEqualTo(ALPHA);
            JsonNode changes = json.readTree(event.getChanges());
            assertThat(changes.get("userId").asText()).isEqualTo(NEWCOMER.toString());
            assertThat(changes.get("scope").asText()).isEqualTo("PROJECT");
        }

        @Test
        @DisplayName("a company-wide grant is audited with no project on the row")
        void aCompanyWideGrantIsAuditedAtCompanyLevel() throws Exception {
            post(ADMIN, WEST, userGrant(Role.COMPANY_ADMIN_ID, NEWCOMER, null));

            AuditEvent event = inCompanyRead(WEST,
                    () -> auditEvents.findByActionOrderByOccurredAtDesc("role_assignment.granted"))
                    .getFirst();

            // Inventing a project to fill the column would put a company-wide appointment in one
            // project's history, where the person reading that project's audit trail would be
            // told about a decision that was not about their project.
            assertThat(event.getProjectId()).isNull();
            assertThat(json.readTree(event.getChanges()).get("scope").asText()).isEqualTo("COMPANY");
        }
    }

    // =====================================================================
    // Idempotence
    // =====================================================================

    @Nested
    @DisplayName("Re-granting is the same grant, not a second one")
    class Idempotence {

        @Test
        @DisplayName("an identical re-grant is 200 with the SAME id")
        void reGrantingReturnsTheExistingRow() throws Exception {
            UUID first = idOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA)));

            MvcResult again = post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA));

            assertThat(again.getResponse().getStatus())
                    .as("not 409: the caller's intent — they hold this role here — is already true")
                    .isEqualTo(200);
            assertThat(json.readTree(again.getResponse().getContentAsString()).get("id").asText())
                    .isEqualTo(first.toString());
        }

        @Test
        @DisplayName("and writes no second row, so there is one thing to revoke")
        void reGrantingWritesNothing() throws Exception {
            post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA));
            long after = grantCount(WEST);
            post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA));

            // Two rows would mean revoking the one on the screen leaves the person holding the
            // role, and the admin who did it has every reason to believe access was removed.
            assertThat(grantCount(WEST)).isEqualTo(after);
        }

        @Test
        @DisplayName("the same role at a DIFFERENT scope is a different grant")
        void scopeIsPartOfIdentity() throws Exception {
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA))))
                    .isEqualTo(201);
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, BETA))))
                    .isEqualTo(201);
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — every message lands on the field that caused it")
    class Validation {

        @Test
        @DisplayName("both a user and a team is a 400 on `userId`")
        void bothSubjectsIsRefused() throws Exception {
            String body = "{\"roleId\":\"" + Role.VIEWER_ID + "\",\"userId\":\"" + NEWCOMER
                    + "\",\"teamId\":\"" + QA_TEAM + "\",\"projectId\":\"" + ALPHA + "\"}";
            MvcResult refused = post(LEAD, WEST, body);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "userId"))
                    .containsExactly("Grant the role to a person or to a team, not to both.");
        }

        @Test
        @DisplayName("neither is a 400 on `userId` too — the form has one subject control")
        void neitherSubjectIsRefused() throws Exception {
            String body = "{\"roleId\":\"" + Role.VIEWER_ID + "\",\"projectId\":\"" + ALPHA + "\"}";
            MvcResult refused = post(LEAD, WEST, body);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "userId"))
                    .containsExactly("Choose a person or a team to grant the role to.");
        }

        @Test
        @DisplayName("a SYSTEM-scope role is refused, and told where those grants live")
        void systemScopeRolesAreNotGrantableHere() throws Exception {
            // instance_role_assignment is a separate instance-level table because a nullable
            // company_id under RLS is permanently unreachable. Writing Instance Admin into
            // role_assignment would confer instance-wide power scoped to one company — a tier that
            // does not exist — and would let a Company Admin appoint operators.
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.INSTANCE_ADMIN_ID, NEWCOMER, null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId"))
                    .containsExactly("Instance-wide roles are granted by the operator, not from here.");
        }

        @Test
        @DisplayName("an Instance Admin cannot smuggle one in either")
        void notEvenTheOperatorMayWriteASystemGrantHere() throws Exception {
            // A delta, not an absolute: instance_role_assignment is instance-level with no RLS, so
            // it is the one table every class in the suite shares. An absolute count here passes
            // alone and fails in the suite — handoff §19's defect, in the table most likely to
            // produce it.
            long before = instanceGrantCount();
            // A delta here too, since V15: role_assignment now also carries one membership
            // baseline per member, so an absolute count would pin the fixture's headcount rather
            // than the property this test names.
            long grantsBefore = grantCount(WEST);

            assertThat(statusOf(post(OPERATOR, WEST, userGrant(Role.INSTANCE_ADMIN_ID, NEWCOMER, null))))
                    .isEqualTo(400);

            assertThat(instanceGrantCount()).isEqualTo(before);
            assertThat(grantCount(WEST))
                    .as("nor did it land in role_assignment, where it would confer instance-wide "
                            + "power scoped to one company")
                    .isEqualTo(grantsBefore);
        }

        @Test
        @DisplayName("the membership baseline cannot be granted — it is not a role anyone chooses")
        void theMembershipBaselineIsNotGrantable() throws Exception {
            // Company Member (V15) is granted by the act of joining. Granting it by hand is at
            // best a no-op on a row that already exists; refused as an unknown role rather than
            // with its own message, because GET /roles does not offer it and telling a caller
            // that a role they cannot see exists is a disclosure this endpoint avoids elsewhere.
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.COMPANY_MEMBER_ID, NEWCOMER, null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId")).containsExactly("Choose a role.");
        }

        @Test
        @DisplayName("nor can Agent — it is listed, and it is still not a role a person holds")
        void theAgentCeilingIsNotGrantableEvenThoughItIsListed() throws Exception {
            // The case the flag exists for, and the one a hardcoded COMPANY_MEMBER_ID comparison
            // would have missed entirely. Agent (V40) IS returned by GET /roles — the token form
            // needs it — so unlike the baseline a client can legitimately name it here. The
            // refusal has to come from the row, and this is what asserts that it does.
            //
            // Same message as the baseline's, deliberately: this endpoint's only question is "may
            // this person hold this role", and the honest answer is no. Where it CAN be chosen is
            // the token-issue form, which says so.
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.AGENT_ID, NEWCOMER, ALPHA));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId")).containsExactly("Choose a role.");
        }

        @Test
        @DisplayName("nor Automation Service — V3 said 'never people' and V41 is what enforces it")
        void automationServiceIsNotGrantableToAPerson() throws Exception {
            // The second row to use V40's flag, and the one that makes "the six" and "the
            // assignable set" different lists (spec §A2). It is one of the six, it is listed, it is
            // issuable — and since V41 a person may not hold it, which is what V3's description
            // has claimed since M0 while this endpoint accepted the grant.
            MvcResult refused =
                    post(ADMIN, WEST, userGrant(Role.AUTOMATION_SERVICE_ID, NEWCOMER, ALPHA));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId")).containsExactly("Choose a role.");
        }

        @Test
        @DisplayName("but a grant made BEFORE V41 is still revocable — the axes are not the same")
        void anAutomationServiceGrantMadeBeforeTheRuleIsStillRevocable() throws Exception {
            // THE POINT OF THE SLICE. V40 had grant and revoke reading one flag, which was right
            // while the only non-assignable role was one whose removal breaks a colleague's access.
            // Read that way here, this row could never be deleted — and AdminDirectoryService keeps
            // listing it (below), so it would sit on the screen behind a Revoke button that 403s
            // forever. The migration deletes nothing, so this state is real on any instance that
            // granted the role before today.
            UUID legacy = UUID.fromString("7a005000-0000-4000-8000-00000000000f");
            inCompany(WEST, () -> tx.executeWithoutResult(status -> grantOnProject(
                    legacy, WEST, Role.AUTOMATION_SERVICE_ID, NEWCOMER, ALPHA, Instant.now())));

            assertThat(revoke(ADMIN, WEST, legacy)).isEqualTo(204);
            assertThat(inCompanyRead(WEST, () -> roleAssignments.findById(legacy)))
                    .as("and the row is gone — an administrator can clean up what V41 stopped")
                    .isEmpty();
        }

        @Test
        @DisplayName("and it is listed, which is what makes that Revoke reachable")
        void anAutomationServiceGrantIsStillListed() throws Exception {
            UUID legacy = UUID.fromString("7a005000-0000-4000-8000-00000000000e");
            inCompany(WEST, () -> tx.executeWithoutResult(status -> grantOnProject(
                    legacy, WEST, Role.AUTOMATION_SERVICE_ID, NEWCOMER, ALPHA, Instant.now())));

            // The mirror of theMembershipBaselineIsNotListed, and the opposite answer for the
            // opposite reason: the baseline is hidden because no screen may offer that Revoke,
            // this one is shown because a screen MUST.
            assertThat(assignmentIds(fetch("/api/v1/role-assignments", ADMIN, WEST)))
                    .contains(legacy);
        }

        @Test
        @DisplayName("nor revoked — that would take away a colleague's sight of the company")
        void theMembershipBaselineIsNotRevocable() throws Exception {
            UUID baseline = baselineGrantId(WEST, NEWCOMER);

            // 403 rather than 204: it exists, and the caller — a Company Admin, who may revoke
            // anything else in this company — may not remove it. The only repair for a successful
            // revoke here is re-running the operator CLI, and the person it happened to would see
            // an empty company rather than a refusal.
            assertThat(revoke(ADMIN, WEST, baseline)).isEqualTo(403);
            assertThat(inCompanyRead(WEST, () -> roleAssignments.findById(baseline)))
                    .as("and nothing was deleted")
                    .isPresent();
        }

        @Test
        @DisplayName("and its grants are not listed, so no screen can offer that Revoke")
        void theMembershipBaselineIsNotListed() throws Exception {
            List<JsonNode> assignments = fetch("/api/v1/role-assignments", ADMIN, WEST);

            assertThat(assignments)
                    .as("""
                            A baseline row on this list would carry a Revoke the server refuses — \
                            the exact defect §31's disable-don't-hide rule and AdminReach exist to \
                            prevent — and its `role` would be a name the contract does not \
                            enumerate.""")
                    .allSatisfy(assignment -> assertThat(assignment.get("role").get("name").asText())
                            .isNotEqualTo("Company Member"));
            assertThat(assignments).isNotEmpty();
        }

        @Test
        @DisplayName("a COMPANY-scope role pinned to one project is a 400 on `roleId`")
        void aCompanyRoleCannotBeNarrowed() throws Exception {
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.COMPANY_ADMIN_ID, NEWCOMER, ALPHA));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId")).containsExactly(
                    "This role applies to the whole company, so it cannot be granted on a single "
                            + "project.");
        }

        @Test
        @DisplayName("a PROJECT-scope role granted company-wide is ACCEPTED — it is inherited")
        void aProjectRoleMayBeGrantedCompanyWide() throws Exception {
            // ~~This was a 400: "the stakeholder Viewer over the whole company is NOT expressible
            // here... a seventh role, not a relaxation of this check."~~ → SUPERSEDED. A PROJECT-scope
            // role with no project now means "this role on every project, unless that project says
            // otherwise", which is the inheritance rule. RoleGrantService#scopeError records the
            // reversal and why the seventh-role answer was too narrow: the same want exists for
            // every working role, not only Viewer.
            //
            // What makes widening safe to allow is the other half of the rule — a project-level
            // grant OVERRIDES the inherited one, including downward — so this is a default rather
            // than an irrevocable promotion. PermissionEvaluatorTest.Inheritance drives that.
            MvcResult granted = post(ADMIN, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, null));

            assertThat(granted.getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("a COMPANY-scope role pinned to one project is STILL a 400 — the other direction")
        void aCompanyRoleStillCannotBeNarrowed() throws Exception {
            // The half that did not change, and the reason the two arms are not symmetric: a
            // COMPANY-scope role is authority over the company, not a default for its projects, so
            // pinning a Company Admin to one project would quietly demote them everywhere else.
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.COMPANY_ADMIN_ID, NEWCOMER, ALPHA));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId")).containsExactly(
                    "This role applies to the whole company, so it cannot be granted on a single "
                            + "project.");
        }

        @Test
        @DisplayName("an unknown role is a 400 on `roleId`, not a 500")
        void anUnknownRoleIsAFieldError() throws Exception {
            MvcResult refused = post(ADMIN, WEST,
                    userGrant(UUID.fromString("7a009000-0000-4000-8000-000000000099"), NEWCOMER, ALPHA));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "roleId")).containsExactly("Choose a role.");
        }

        @Test
        @DisplayName("a person who is not a member of this company is a 400 on `userId`")
        void aForeignSubjectIsRefusedWithoutSayingWhy() throws Exception {
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.VIEWER_ID, EAST_ADMIN, ALPHA));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "userId"))
                    .as("the same message as a non-existent account: told apart, this is an "
                            + "instance-wide directory oracle")
                    .containsExactly("That person is not a member of this company.");
        }

        @Test
        @DisplayName("a non-existent account gets the identical message")
        void anUnknownSubjectIsIndistinguishable() throws Exception {
            MvcResult refused = post(ADMIN, WEST, userGrant(Role.VIEWER_ID,
                    UUID.fromString("7a009000-0000-4000-8000-000000000098"), ALPHA));

            assertThat(errorsOn(refused, "userId"))
                    .containsExactly("That person is not a member of this company.");
        }

        @Test
        @DisplayName("a team from another company is a 400 on `teamId`")
        void aForeignTeamIsRefused() throws Exception {
            MvcResult refused = post(EAST_ADMIN, EAST, teamGrant(Role.VIEWER_ID, QA_TEAM, EAST_PROJECT));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "teamId"))
                    .containsExactly("That team does not exist in this company.");
        }

        @Test
        @DisplayName("a project that does not exist is a 404, not a 400")
        void anUnknownProjectIsNotFound() throws Exception {
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.VIEWER_ID, NEWCOMER,
                    UUID.fromString("7a009000-0000-4000-8000-000000000097")))))
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another company's project is the same 404 — never a 403")
        void aForeignProjectIsIndistinguishableFromAMissingOne() throws Exception {
            // §6t: telling them apart makes the endpoint an oracle for what exists in another
            // client's tenancy.
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, EAST_PROJECT))))
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a refused grant writes no row and no audit entry")
        void nothingIsWrittenOnARefusal() throws Exception {
            long before = grantCount(WEST);

            // A COMPANY-scope role pinned to a project — the refusal that survived the inheritance
            // change. The previous fixture here (a PROJECT role granted company-wide) is a 201 now.
            assertThat(statusOf(post(ADMIN, WEST, userGrant(Role.COMPANY_ADMIN_ID, NEWCOMER, ALPHA))))
                    .isEqualTo(400);

            assertThat(grantCount(WEST)).isEqualTo(before);
            assertThat(auditActions(WEST)).isEmpty();
        }
    }

    // =====================================================================
    // Revoking
    // =====================================================================

    @Nested
    @DisplayName("Revoking — gated the same way, idempotent, and refusing to lock you out")
    class Revoking {

        @Test
        @DisplayName("a revoke takes the project away again")
        void revokingRemovesSight() throws Exception {
            UUID granted = idOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA)));
            assertThat(idsOf(listProjects(NEWCOMER, WEST))).containsExactly(ALPHA);

            assertThat(revoke(LEAD, WEST, granted)).isEqualTo(204);

            assertThat(listProjects(NEWCOMER, WEST))
                    .as("permissions are read from the database on every request, so a revoke is "
                            + "effective on the very next one — no session revocation needed")
                    .isEmpty();
        }

        @Test
        @DisplayName("revoking something that is not there is a 204")
        void revokingAnAbsentGrantSucceeds() throws Exception {
            assertThat(revoke(ADMIN, WEST,
                    UUID.fromString("7a009000-0000-4000-8000-000000000096")))
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("revoking twice is a 204 twice — a double-click ends where the caller asked")
        void revokingIsIdempotent() throws Exception {
            UUID granted = idOf(post(LEAD, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA)));

            assertThat(revoke(LEAD, WEST, granted)).isEqualTo(204);
            assertThat(revoke(LEAD, WEST, granted)).isEqualTo(204);
        }

        @Test
        @DisplayName("another company's grant is a 204 too — indistinguishable from absent")
        void aForeignGrantIsNotVisibleAndNotRevocable() throws Exception {
            assertThat(revoke(ADMIN, WEST, GRANT_EAST_ADMIN)).isEqualTo(204);

            assertThat(inCompanyRead(EAST, () -> roleAssignments.findScopedById(GRANT_EAST_ADMIN)))
                    .as("EAST's admin still holds their grant")
                    .isPresent();
        }

        @Test
        @DisplayName("a Test Lead may not revoke a grant on another project — 403")
        void theRevokeGateIsTheGrantGate() throws Exception {
            assertThat(revoke(LEAD, WEST, GRANT_BETA_LEAD)).isEqualTo(403);
            assertThat(inCompanyRead(WEST, () -> roleAssignments.findScopedById(GRANT_BETA_LEAD)))
                    .isPresent();
        }

        @Test
        @DisplayName("nor a company-wide grant — 403")
        void aLeadMayNotRevokeACompanyWideGrant() throws Exception {
            assertThat(revoke(LEAD, WEST, GRANT_ADMIN)).isEqualTo(403);
        }

        @Test
        @DisplayName("a Test Lead may NOT revoke their own Test Lead grant — 400 on assignmentId")
        void revokingYourOwnAbilityToRevokeIsRefused() throws Exception {
            MvcResult refused = revokeResult(LEAD, WEST, GRANT_LEAD);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "assignmentId")).hasSize(1);
            assertThat(errorsOn(refused, "assignmentId").getFirst())
                    .contains("lock you out");
        }

        @Test
        @DisplayName("and the refusal rolls the delete AND its audit row back")
        void theLockoutRefusalIsATrueRollback() throws Exception {
            revokeResult(LEAD, WEST, GRANT_LEAD);

            // The check is computed by doing the delete and asking the evaluator again, so the only
            // thing standing between "refused" and "committed" is the rollback. If it ever stops
            // working, the response still says 400 and the grant is gone.
            assertThat(inCompanyRead(WEST, () -> roleAssignments.findScopedById(GRANT_LEAD)))
                    .isPresent();
            assertThat(auditActions(WEST))
                    .as("an audit entry for a revoke that did not happen is worse than none")
                    .isEmpty();
            assertThat(idsOf(listProjects(LEAD, WEST))).containsExactly(ALPHA);
        }

        @Test
        @DisplayName("a Company Admin may not revoke their own company-wide grant either")
        void theSameRuleAtCompanyScope() throws Exception {
            MvcResult refused = revokeResult(ADMIN, WEST, GRANT_ADMIN);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(inCompanyRead(WEST, () -> roleAssignments.findScopedById(GRANT_ADMIN)))
                    .isPresent();
        }

        @Test
        @DisplayName("but they may revoke a SECOND grant of their own that is not load-bearing")
        void theRefusalIsAboutCapabilityAndNotAboutOwnership() throws Exception {
            // The check asks the evaluator whether the caller still holds the gate, not whether the
            // row named them — so a redundant grant of their own comes off cleanly. A "is this row
            // about me" test would refuse this one and be wrong.
            UUID extra = idOf(post(ADMIN, WEST, userGrant(Role.TEST_ENGINEER_ID, ADMIN, ALPHA)));

            assertThat(revoke(ADMIN, WEST, extra)).isEqualTo(204);
        }

        @Test
        @DisplayName("an Instance Admin revokes anything — the SYSTEM term survives it")
        void anOperatorCannotLockThemselvesOut() throws Exception {
            assertThat(revoke(OPERATOR, WEST, GRANT_LEAD)).isEqualTo(204);
        }

        @Test
        @DisplayName("a Company Admin revoking a Test Lead's grant is fine — it is not their own")
        void revokingSomebodyElsesGrantIsOrdinary() throws Exception {
            assertThat(revoke(ADMIN, WEST, GRANT_LEAD)).isEqualTo(204);
            assertThat(listProjects(LEAD, WEST)).isEmpty();
        }

        @Test
        @DisplayName("the revoke is audited before the row goes")
        void theRevokeIsAudited() throws Exception {
            revoke(ADMIN, WEST, GRANT_LEAD);

            List<AuditEvent> recorded = inCompanyRead(WEST,
                    () -> auditEvents.findByActionOrderByOccurredAtDesc("role_assignment.revoked"));

            assertThat(recorded).hasSize(1);
            assertThat(recorded.getFirst().getTargetIds()).containsExactly(GRANT_LEAD);
            assertThat(recorded.getFirst().getActorUserId()).isEqualTo(ADMIN);
        }
    }

    // =====================================================================
    // The read surface
    // =====================================================================

    @Nested
    @DisplayName("The admin reads — a company directory, never an instance one")
    class Reads {

        @Test
        @DisplayName("GET /users lists this company's members and nobody else's")
        void theDirectoryIsCompanyScoped() throws Exception {
            List<String> emails = fetch("/api/v1/users", ADMIN, WEST).stream()
                    .map(user -> user.get("email").asText())
                    .toList();

            assertThat(emails).contains("admin@west-grant.test", "newcomer@west-grant.test");
            assertThat(emails)
                    .as("app_user is instance-level and has no RLS — the list is driven from "
                            + "company_membership precisely so this cannot happen")
                    .doesNotContain("admin@east-grant.test", "operator@instance-grant.test");
        }

        @Test
        @DisplayName("the display name is the membership's, not the account's canonical one")
        void namesResolveThroughTheMembership() throws Exception {
            JsonNode newcomer = fetch("/api/v1/users", ADMIN, WEST).stream()
                    .filter(user -> user.get("id").asText().equals(NEWCOMER.toString()))
                    .findFirst().orElseThrow();

            // §6u: a contractor may be "A. Kotrulev" to one client and "Aleks (Vendor)" to another.
            assertThat(newcomer.get("displayName").asText()).isEqualTo("N. Newcomer");
        }

        @Test
        @DisplayName("lastLoginAt is withheld from a caller without company:members")
        void whenSomebodyLastSignedInIsAnAdministrativeFact() throws Exception {
            JsonNode asLead = fetch("/api/v1/users", LEAD, WEST).getFirst();
            assertThat(asLead.get("lastLoginAt").isNull()).isTrue();

            // The contract already declares it nullable, so withholding it needs no contract
            // change and an ordinary member sees the same shape with one less fact in it.
            assertThat(fetch("/api/v1/users", ADMIN, WEST)).isNotEmpty();
        }

        @Test
        @DisplayName("GET /roles is the nine plus Agent — the membership baseline is neither")
        void theRoleCatalogueIsWholeAndHasNoWildcard() throws Exception {
            List<JsonNode> roles = fetch("/api/v1/roles", LEAD, WEST);

            // Ten since V2 (2026-08-30): the six V1 seeded, plus Agent, plus Project Manager,
            // Account Manager and Finance. Eleven rows exist; Company Member is the one filtered.
            assertThat(roles).hasSize(10);
            assertThat(roles.stream().map(role -> role.get("name").asText()).toList())
                    .as("""
                            Company Member (V15) is granted by joining, never chosen — offering it \
                            here would put it in the grant picker and, through the assignment \
                            list, behind a Revoke that takes away a colleague's sight of the \
                            company.""")
                    .doesNotContain("Company Member")
                    .as("""
                            Agent (V40) is the other non-assignable role and it IS listed, which is \
                            the asymmetry the flag cannot carry: the token-issue form is the only \
                            place it is ever chosen, so filtering GET /roles by `assignable` would \
                            make the role unissuable through the product. The grant pickers filter \
                            it client-side; RoleGrantService is the enforcement.""")
                    .contains("Agent");
            JsonNode instanceAdmin = roles.stream()
                    .filter(role -> role.get("name").asText().equals("Instance Admin"))
                    .findFirst().orElseThrow();
            assertThat(instanceAdmin.get("scope").asText()).isEqualTo("SYSTEM");
            List<String> keys = new ArrayList<>();
            instanceAdmin.get("permissions").forEach(key -> keys.add(key.asText()));
            assertThat(keys)
                    .as("no '*' sentinel exists in the model — a wildcard would keep reading as "
                            + "'everything' after a migration added a permission the role lacks")
                    .doesNotContain("*")
                    .contains("company:create", "case:read");
        }

        @Test
        @DisplayName("GET /teams shows the org chart and its members")
        void theOrgChartIsRendered() throws Exception {
            JsonNode team = fetch("/api/v1/teams", ADMIN, WEST).getFirst();

            assertThat(team.get("name").asText()).isEqualTo("QA Chapter");
            assertThat(team.get("members")).hasSize(1);
            assertThat(team.get("leads")).hasSize(1);
            assertThat(team.get("members").get(0).get("displayName").asText()).isEqualTo("T. Member");
        }

        @Test
        @DisplayName("a team's project chips are filtered to the projects the reader may see")
        void theOrgChartIsNotAWayAroundProjectVisibility() throws Exception {
            post(ADMIN, WEST, teamGrant(Role.VIEWER_ID, QA_TEAM, BETA));

            JsonNode asBetaLead = fetch("/api/v1/teams", BETA_LEAD, WEST).getFirst();
            assertThat(asBetaLead.get("projects")).hasSize(1);

            // LEAD holds nothing on BETA, so BETA's NAME must not reach them through the org chart.
            JsonNode asAlphaLead = fetch("/api/v1/teams", LEAD, WEST).getFirst();
            assertThat(asAlphaLead.get("projects")).isEmpty();
        }

        @Test
        @DisplayName("GET /role-assignments shows a Company Admin everything in the company")
        void anAdminSeesEveryGrant() throws Exception {
            List<UUID> ids = assignmentIds(fetch("/api/v1/role-assignments", ADMIN, WEST));

            assertThat(ids).contains(GRANT_ADMIN, GRANT_LEAD, GRANT_BETA_LEAD);
        }

        @Test
        @DisplayName("and a Test Lead only the grants on the project they run")
        void theListIsExactlyWhatTheCallerCouldRevoke() throws Exception {
            List<UUID> ids = assignmentIds(fetch("/api/v1/role-assignments", LEAD, WEST));

            // The invariant AdminDirectoryService#listRoleAssignments states: every row rendered
            // carries a Revoke the server will accept, and every grant it would accept a revoke for
            // is on the screen. Listing more would hand a Test Lead the company's whole org
            // structure off a page they were given for one project.
            assertThat(ids).containsExactly(GRANT_LEAD);
            assertThat(ids).doesNotContain(GRANT_ADMIN, GRANT_BETA_LEAD);
        }

        @Test
        @DisplayName("the projectId filter narrows it")
        void theProjectFilterWorks() throws Exception {
            List<UUID> ids = assignmentIds(
                    fetch("/api/v1/role-assignments?projectId=" + BETA, ADMIN, WEST));

            assertThat(ids).containsExactly(GRANT_BETA_LEAD);
        }

        @Test
        @DisplayName("filtering by a project the caller cannot administer is [] — not a 404")
        void anUnreachableFilterIsAnEmptyList() throws Exception {
            assertThat(fetch("/api/v1/role-assignments?projectId=" + EAST_PROJECT, ADMIN, WEST))
                    .isEmpty();
        }

        @Test
        @DisplayName("a member holding nothing sees no grants at all")
        void aPlainMemberAdministersNothing() throws Exception {
            assertThat(fetch("/api/v1/role-assignments", NEWCOMER, WEST)).isEmpty();
        }

        @Test
        @DisplayName("EAST sees none of WEST's people or grants")
        void theCompanyBoundaryHolds() throws Exception {
            post(ADMIN, WEST, userGrant(Role.VIEWER_ID, NEWCOMER, ALPHA));

            assertThat(assignmentIds(fetch("/api/v1/role-assignments", EAST_ADMIN, EAST)))
                    .containsExactly(GRANT_EAST_ADMIN);
            assertThat(fetch("/api/v1/users", EAST_ADMIN, EAST).stream()
                    .map(user -> user.get("email").asText()).toList())
                    .containsExactly("admin@east-grant.test");
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult post(UUID userId, UUID companyId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/role-assignments")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private int revoke(UUID userId, UUID companyId, UUID assignmentId) throws Exception {
        return revokeResult(userId, companyId, assignmentId).getResponse().getStatus();
    }

    private MvcResult revokeResult(UUID userId, UUID companyId, UUID assignmentId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/role-assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private static String userGrant(UUID roleId, UUID userId, UUID projectId) {
        return "{\"roleId\":\"" + roleId + "\",\"userId\":\"" + userId + "\",\"projectId\":"
                + (projectId == null ? "null" : "\"" + projectId + "\"") + "}";
    }

    private static String teamGrant(UUID roleId, UUID teamId, UUID projectId) {
        return "{\"roleId\":\"" + roleId + "\",\"teamId\":\"" + teamId + "\",\"projectId\":"
                + (projectId == null ? "null" : "\"" + projectId + "\"") + "}";
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private UUID idOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("expected a grant, got: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private List<String> errorsOn(MvcResult result, String field) throws Exception {
        JsonNode errors = json.readTree(result.getResponse().getContentAsString()).get("errors");
        if (errors == null || errors.get(field) == null) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        errors.get(field).forEach(message -> messages.add(message.asText()));
        return messages;
    }

    private List<JsonNode> fetch(String path, UUID userId, UUID companyId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode array = json.readTree(body);
        assertThat(array.isArray()).as("%s must answer an array: %s", path, body).isTrue();
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private List<JsonNode> listProjects(UUID userId, UUID companyId) throws Exception {
        return fetch("/api/v1/projects", userId, companyId);
    }

    private static List<UUID> idsOf(List<JsonNode> projects) {
        return projects.stream().map(node -> UUID.fromString(node.get("id").asText())).toList();
    }

    private static List<UUID> assignmentIds(List<JsonNode> assignments) {
        return assignments.stream().map(node -> UUID.fromString(node.get("id").asText())).toList();
    }

    /** The V15 baseline grant one member holds — never on the wire, so it is read directly. */
    private UUID baselineGrantId(UUID companyId, UUID userId) {
        return inCompanyRead(companyId, () -> roleAssignments.findByUserId(userId).stream()
                .filter(grant -> Role.COMPANY_MEMBER_ID.equals(grant.getRoleId()))
                .map(RoleAssignment::getId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no membership baseline for " + userId + " — the fixture did not run "
                                + "tcms_grant_company_read_baseline")));
    }

    private long grantCount(UUID companyId) {
        return inCompanyRead(companyId, () -> (long) roleAssignments.findAllByOrderByGrantedAtDesc().size());
    }

    private long instanceGrantCount() {
        return tx.execute(status -> instanceRoleAssignments.count());
    }

    private List<String> auditActions(UUID companyId) {
        return inCompanyRead(companyId, () -> {
            List<String> actions = new ArrayList<>();
            auditEvents.findByActionOrderByOccurredAtDesc("role_assignment.granted")
                    .forEach(event -> actions.add(event.getAction()));
            auditEvents.findByActionOrderByOccurredAtDesc("role_assignment.revoked")
                    .forEach(event -> actions.add(event.getAction()));
            return actions;
        });
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    /**
     * A read inside a company context <em>and</em> a transaction — both are required. A repository
     * query method with no transaction runs with no {@code app.current_company}, and under RLS that
     * means zero rows: the assertion would pass for the wrong reason.
     */
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
            user.recordLogin(now);
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

    /** V15's baseline, run exactly as provisioning runs it. Requires an open context and a tx. */
    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }

    private void grantInstanceAdmin(UUID userId, Instant now) {
        UUID id = UUID.fromString("7a006000-0000-4000-8000-000000000001");
        if (instanceRoleAssignments.findById(id).isEmpty()) {
            instanceRoleAssignments.save(
                    InstanceRoleAssignment.forUser(id, Role.INSTANCE_ADMIN_ID, userId, null, now));
        }
    }
}
