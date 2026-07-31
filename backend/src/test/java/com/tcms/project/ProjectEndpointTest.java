package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InstanceRoleAssignment;
import com.tcms.identity.InstanceRoleAssignmentRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.identity.Team;
import com.tcms.identity.TeamMembership;
import com.tcms.identity.TeamMembershipId;
import com.tcms.identity.TeamMembershipRepository;
import com.tcms.identity.TeamRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.SQLException;
import java.time.Instant;
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
 * The project endpoints — the second gate, finally observable over HTTP.
 *
 * <p>{@code PermissionEvaluatorTest} proves the gate as a <em>permission</em> question. This
 * proves it as an <em>answer to a request</em>, which is a different claim: the evaluator can be
 * perfectly correct while the endpoint above it lists every project in the company because
 * nobody wired the filter in. That failure would pass all 180 existing tests.
 *
 * <h2>The fixture is deliberately hostile</h2>
 *
 * <p>Two companies, each owning a project with the <strong>same key and the same name</strong>,
 * so a test cannot pass by having queried the convenient row; a member with no grant at all,
 * whose forbidden rows sit <em>in their own company</em> where no cross-company assertion can
 * reach them; and a cross-company user who is Company Admin in one and a plain grant-holder in
 * the other.
 *
 * <p>Company ids are unique to this class. Sharing {@code 3333…} with
 * {@code PermissionEvaluatorTest} is exactly how handoff §19's first defect happened — green
 * alone, red in the full suite, and the symptom read like a broken privilege split rather than
 * like two classes seeding the same company.
 */
@AutoConfigureMockMvc
@DisplayName("Project endpoints — company gate, grant gate, and 404 over 403")
class ProjectEndpointTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("6e000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("6e000000-0000-4000-8000-00000000000b");

    private static final UUID NORTH_BANKING = UUID.fromString("6e001000-0000-4000-8000-000000000001");
    private static final UUID NORTH_MOBILE = UUID.fromString("6e001000-0000-4000-8000-000000000002");
    private static final UUID NORTH_ARCHIVED = UUID.fromString("6e001000-0000-4000-8000-000000000003");
    private static final UUID SOUTH_BANKING = UUID.fromString("6e002000-0000-4000-8000-000000000001");

    /** Granted Test Lead on NORTH_BANKING only — the ordinary user. */
    private static final UUID LEAD = UUID.fromString("6e003000-0000-4000-8000-000000000001");
    /** ACTIVE member of North, holding nothing. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("6e003000-0000-4000-8000-000000000002");
    /** Company Admin in North — sees every project, through a grant with no project id. */
    private static final UUID ADMIN = UUID.fromString("6e003000-0000-4000-8000-000000000003");
    /** Test Engineer in South only. Persona 4, from the other side. */
    private static final UUID SOUTHERNER = UUID.fromString("6e003000-0000-4000-8000-000000000004");
    /** Reaches NORTH_MOBILE only through a team grant. */
    private static final UUID TEAM_MEMBER = UUID.fromString("6e003000-0000-4000-8000-000000000005");

    private static final UUID NORTH_QA_TEAM = UUID.fromString("6e004000-0000-4000-8000-000000000001");

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
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-proj", "Northwind", now);
            provisionCompany(SOUTH, "south-proj", "Southwind", now);

            activeUser(LEAD, "lead@north.test", now);
            activeUser(NO_GRANT, "nogrant@north.test", now);
            activeUser(ADMIN, "admin@north.test", now);
            activeUser(SOUTHERNER, "engineer@south.test", now);
            activeUser(TEAM_MEMBER, "team@north.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            // Same key and name as South's project — see the class comment.
            seedProject(NORTH_BANKING, NORTH, "OLB", "Online Banking", "north marker", false, now);
            seedProject(NORTH_MOBILE, NORTH, "MOB", "Mobile App", null, false, now);
            seedProject(NORTH_ARCHIVED, NORTH, "OLD", "Retired Platform", null, true, now);

            member(NORTH, LEAD, "L. Lead", now);
            member(NORTH, NO_GRANT, "N. Grant", now);
            member(NORTH, ADMIN, "A. Admin", now);
            member(NORTH, TEAM_MEMBER, "T. Member", now);

            if (teams.findById(NORTH_QA_TEAM).isEmpty()) {
                teams.save(Team.create(NORTH_QA_TEAM, NORTH, "QA", null, now));
            }
            if (teamMemberships.findById(new TeamMembershipId(NORTH_QA_TEAM, TEAM_MEMBER)).isEmpty()) {
                teamMemberships.save(TeamMembership.join(NORTH, NORTH_QA_TEAM, TEAM_MEMBER, false, now));
            }

            grantOnProject("n-lead", NORTH, Role.TEST_LEAD_ID, LEAD, NORTH_BANKING, now);
            grantOnCompany("n-admin", NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantToTeam("n-team", NORTH, Role.TEST_ENGINEER_ID, NORTH_QA_TEAM, NORTH_MOBILE, now);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            seedProject(SOUTH_BANKING, SOUTH, "OLB", "Online Banking", "south marker", false, now);
            member(SOUTH, SOUTHERNER, "S. Engineer", now);
            grantOnProject("s-eng", SOUTH, Role.TEST_ENGINEER_ID, SOUTHERNER, SOUTH_BANKING, now);
        }));

        // Distinct key prefixes because display keys are unique per COMPANY: North's three
        // projects share one key space, so deriving a key from the project id would risk a
        // collision that presents as a failed insert in an unrelated assertion.
        seedCases("NB", NORTH, NORTH_BANKING, 3, 2);
        seedCases("NM", NORTH, NORTH_MOBILE, 0, 1);
        seedCases("SB", SOUTH, SOUTH_BANKING, 7, 0);
    }

    // =====================================================================
    // Gate 2 — the grant, within the caller's own company
    // =====================================================================

    @Nested
    @DisplayName("The grant gate — membership alone conveys no project visibility")
    class GrantGate {

        @Test
        @DisplayName("a member holding no grant gets an EMPTY list, not their company's projects")
        void memberWithNoGrantSeesNothing() throws Exception {
            // The leak no cross-company assertion can catch: all three of North's projects
            // ARE in this user's company, so RLS admits every one of them and only the grant
            // gate stands between this caller and the client's product portfolio.
            assertThat(listAs(NO_GRANT, NORTH))
                    .as("company membership conveys authentication and ZERO project visibility")
                    .isEmpty();
        }

        @Test
        @DisplayName("the fixture is real — the same three projects ARE visible to an admin")
        void theCompanyIsNotSimplyEmpty() throws Exception {
            // Without this, "sees nothing" would also pass if the seed silently failed, if the
            // company filter excluded everything, or if the endpoint returned [] for everyone.
            assertThat(namesOf(listAs(ADMIN, NORTH)))
                    .containsExactly("Mobile App", "Online Banking", "Retired Platform");
        }

        @Test
        @DisplayName("a grant on one project does not reveal the other two")
        void aGrantIsPerProject() throws Exception {
            assertThat(namesOf(listAs(LEAD, NORTH))).containsExactly("Online Banking");
        }

        @Test
        @DisplayName("a team grant is enough — and reveals only the team's project")
        void teamGrantConfersVisibility() throws Exception {
            assertThat(namesOf(listAs(TEAM_MEMBER, NORTH))).containsExactly("Mobile App");
        }

        @Test
        @DisplayName("a COMPANY-scope grant covers every project, present and future")
        void companyAdminSeesAll() throws Exception {
            assertThat(listAs(ADMIN, NORTH)).hasSize(3);
        }
    }

    // =====================================================================
    // Gate 1 — the company
    // =====================================================================

    @Nested
    @DisplayName("The company gate — nothing of another client's, by any route")
    class CompanyGate {

        @Test
        @DisplayName("a South session sees only South's project, though both share key and name")
        void listIsScopedToTheSessionsCompany() throws Exception {
            List<JsonNode> south = listAs(SOUTHERNER, SOUTH);

            assertThat(south).hasSize(1);
            assertThat(south.getFirst().get("id").asText())
                    .as("both projects are named 'Online Banking' with key 'OLB', so the name "
                            + "proves nothing here — only the id does")
                    .isEqualTo(SOUTH_BANKING.toString());
        }

        @Test
        @DisplayName("North's admin — who may see every project in North — cannot fetch South's")
        void aForeignProjectIs404EvenForAnAdmin() throws Exception {
            // The strongest form of the assertion: this caller holds a COMPANY-scope grant
            // that covers "every project", and the company it covers is the whole point.
            assertThat(statusOfDetail(ADMIN, NORTH, SOUTH_BANKING)).isEqualTo(404);
        }

        @Test
        @DisplayName("caseCount counts only the caller's company — the other's cases are invisible")
        void caseCountDoesNotCrossCompanies() throws Exception {
            // Both companies own a project keyed OLB with cases in it. A count query missing
            // its company predicate, or reading past RLS, would inflate this number rather
            // than fail — which is why the assertion is on the value, not on an exception.
            assertThat(detailAs(SOUTHERNER, SOUTH, SOUTH_BANKING).get("caseCount").asLong())
                    .isEqualTo(7);
            assertThat(detailAs(LEAD, NORTH, NORTH_BANKING).get("caseCount").asLong())
                    .isEqualTo(3);
        }
    }

    // =====================================================================
    // 404, never 403
    // =====================================================================

    @Nested
    @DisplayName("Unreachable is indistinguishable from non-existent")
    class NotFoundNotForbidden {

        @Test
        @DisplayName("an ungranted project in the caller's OWN company is 404, not 403")
        void ungrantedIsNotForbidden() throws Exception {
            // 403 would confirm the id names a real project in this company — which is a fact
            // about the client's portfolio that this caller was not granted.
            assertThat(statusOfDetail(LEAD, NORTH, NORTH_MOBILE)).isEqualTo(404);
        }

        @Test
        @DisplayName("all three unreachable cases answer identically, so none is an oracle")
        void theThreeCasesAreIndistinguishable() throws Exception {
            int ungranted = statusOfDetail(LEAD, NORTH, NORTH_MOBILE);
            int foreign = statusOfDetail(LEAD, NORTH, SOUTH_BANKING);
            int nonExistent = statusOfDetail(LEAD, NORTH, UUID.randomUUID());

            assertThat(List.of(ungranted, foreign, nonExistent))
                    .as("§6t: told apart, the response becomes a probe for what exists in "
                            + "another client's tenancy")
                    .containsExactly(404, 404, 404);
        }

        @Test
        @DisplayName("and the bodies are empty too — a 404 that explains itself is still an oracle")
        void theBodiesCarryNothing() throws Exception {
            assertThat(bodyOfDetail(LEAD, NORTH, SOUTH_BANKING)).isEmpty();
            assertThat(bodyOfDetail(LEAD, NORTH, UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("a granted project is genuinely reachable — the 404s above are the gate, not a broken route")
        void theGrantedOneIs200() throws Exception {
            assertThat(statusOfDetail(LEAD, NORTH, NORTH_BANKING)).isEqualTo(200);
        }
    }

    // =====================================================================
    // The projection
    // =====================================================================

    @Nested
    @DisplayName("The wire shape — what the contract promised")
    class Projection {

        @Test
        @DisplayName("companyId never appears, on either endpoint")
        void companyIdIsNeverExposed() throws Exception {
            // The one field a client has no business seeing: echoed back, it invites the next
            // author to accept it on the way in, which is the whole attack (ERD §0.1 point 2).
            assertThat(detailAs(LEAD, NORTH, NORTH_BANKING).has("companyId")).isFalse();
            assertThat(listAs(ADMIN, NORTH)).allSatisfy(node ->
                    assertThat(node.has("companyId")).isFalse());
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode project = detailAs(LEAD, NORTH, NORTH_BANKING);

            // The contract's required list for Project. A missing one typechecks on this side
            // and breaks the SPA, which cannot null-check a field declared always present.
            assertThat(List.of("id", "key", "name", "archived", "caseCount", "myRole"))
                    .allSatisfy(field -> assertThat(project.has(field))
                            .as("Project.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(project.get("key").asText()).isEqualTo("OLB");
            assertThat(project.get("archived").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("myRole is the caller's PROJECT-scope role name")
        void myRoleNamesTheProjectGrant() throws Exception {
            assertThat(detailAs(LEAD, NORTH, NORTH_BANKING).get("myRole").asText())
                    .isEqualTo("Test Lead");
            assertThat(detailAs(TEAM_MEMBER, NORTH, NORTH_MOBILE).get("myRole").asText())
                    .as("a role granted through a team is still the caller's role on the project")
                    .isEqualTo("Test Engineer");
        }

        @Test
        @DisplayName("myRole is null for a COMPANY-scope grant, per the contract")
        void companyAdminHasNoProjectRole() throws Exception {
            // Not an oversight and not a missing lookup: myRole is defined as "the caller's
            // role on this PROJECT, or null when they hold no project grant". Labelling every
            // project "Company Admin" would state a fact about the company instead.
            assertThat(detailAs(ADMIN, NORTH, NORTH_BANKING).get("myRole").isNull()).isTrue();
        }

        @Test
        @DisplayName("caseCount excludes soft-deleted cases")
        void caseCountIgnoresDeletedCases() throws Exception {
            // NORTH_BANKING has 3 live and 2 soft-deleted. Counting all 5 would contradict the
            // case list on the very next screen, and test_case is soft-deleted precisely so
            // history survives — the count must not.
            assertThat(detailAs(LEAD, NORTH, NORTH_BANKING).get("caseCount").asLong())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a project with no live cases reports 0, not null")
        void aProjectWithNoCasesReportsZero() throws Exception {
            // NORTH_MOBILE has one case and it is deleted, so the GROUP BY returns NO ROW for
            // this project at all. "Absent means zero" is the kind of thing that reads correct
            // and renders as null.
            JsonNode mobile = detailAs(TEAM_MEMBER, NORTH, NORTH_MOBILE);

            assertThat(mobile.get("caseCount").isNull()).isFalse();
            assertThat(mobile.get("caseCount").asLong()).isZero();
        }

        @Test
        @DisplayName("an archived project is listed and marked, not hidden")
        void archivedIsMarkedNotOmitted() throws Exception {
            JsonNode archived = listAs(ADMIN, NORTH).stream()
                    .filter(node -> node.get("id").asText().equals(NORTH_ARCHIVED.toString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the archived project must still be listed — omitting it makes it "
                                    + "unreachable rather than marked"));

            assertThat(archived.get("archived").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("a null description serializes as null rather than being dropped")
        void nullDescriptionIsExplicit() throws Exception {
            // The contract declares description nullable. A field omitted entirely and a field
            // present as null are the same in TypeScript but not in every consumer, and the
            // generated type says the key exists.
            JsonNode mobile = detailAs(TEAM_MEMBER, NORTH, NORTH_MOBILE);

            assertThat(mobile.has("description")).isTrue();
            assertThat(mobile.get("description").isNull()).isTrue();
        }

        @Test
        @DisplayName("the list is ordered by name")
        void listIsOrderedByName() throws Exception {
            assertThat(namesOf(listAs(ADMIN, NORTH)))
                    .containsExactly("Mobile App", "Online Banking", "Retired Platform");
        }
    }

    // =====================================================================
    // Authentication
    // =====================================================================

    @Test
    @DisplayName("both endpoints refuse an anonymous caller")
    void anonymousIsRejected() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/" + NORTH_BANKING))
                .andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * A real signed session for this user in this company.
     *
     * <p>Through {@code SessionTokens} rather than a hand-built JWT or a mocked principal: the
     * {@code co} claim is what {@code CompanyContextFilter} turns into a company context, so a
     * fabricated token would test the controller while skipping the mechanism that makes the
     * company gate exist at all.
     */
    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private List<JsonNode> listAs(UUID userId, UUID companyId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode array = json.readTree(body);
        assertThat(array.isArray()).as("GET /projects must answer a JSON array: %s", body).isTrue();
        List<JsonNode> elements = new java.util.ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private JsonNode detailAs(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return json.readTree(bodyOfDetail(userId, companyId, projectId));
    }

    private String bodyOfDetail(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int statusOfDetail(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private static List<String> namesOf(List<JsonNode> projects) {
        return projects.stream().map(node -> node.get("name").asText()).toList();
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
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

    private void seedProject(
            UUID id, UUID companyId, String key, String name, String description,
            boolean archived, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            Project project = Project.create(id, companyId, key, name, description, now);
            if (archived) {
                project.archive(now);
            }
            projects.save(project);
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
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantOnCompany(String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private void grantToTeam(
            String marker, UUID companyId, UUID roleId, UUID teamId, UUID projectId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forTeamOnProject(
                    id, companyId, roleId, teamId, projectId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("project-endpoint:" + marker).getBytes());
    }

    /**
     * Cases straight into {@code test_case}, as the superuser.
     *
     * <p>There is no {@code TestCase} entity — the {@code repository} module does not exist yet
     * — and {@code caseCount} is precisely the field that needs rows in that table. Written as
     * the superuser rather than through the app role because this fixture needs a suite row and
     * a key sequence it has no service to allocate; the assertions still read through the
     * ordinary policed path, which is what is under test.
     *
     * @param live    non-deleted cases
     * @param deleted soft-deleted ones, which must NOT be counted
     */
    private void seedCases(String prefix, UUID companyId, UUID projectId, int live, int deleted) {
        try (var connection = superuserConnection()) {
            UUID suiteId = UUID.nameUUIDFromBytes(("suite:" + projectId).getBytes());
            try (var suite = connection.prepareStatement("""
                    INSERT INTO suite (id, company_id, project_id, key, key_seq, name, path)
                    VALUES (?, ?, ?, ?, 1, 'Root', 'Root')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                suite.setObject(1, suiteId);
                suite.setObject(2, companyId);
                suite.setObject(3, projectId);
                suite.setString(4, prefix + "-TS-1");
                suite.executeUpdate();
            }

            for (int i = 0; i < live + deleted; i++) {
                boolean isDeleted = i >= live;
                UUID caseId = UUID.nameUUIDFromBytes(("case:" + projectId + ":" + i).getBytes());
                try (var statement = connection.prepareStatement("""
                        INSERT INTO test_case
                            (id, company_id, project_id, suite_id, key, key_seq, title, deleted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)) {
                    statement.setObject(1, caseId);
                    statement.setObject(2, companyId);
                    statement.setObject(3, projectId);
                    statement.setObject(4, suiteId);
                    statement.setString(5, prefix + "-TC-" + i);
                    statement.setLong(6, i + 1L);
                    statement.setString(7, "Seeded case " + i);
                    statement.setObject(8, isDeleted ? Instant.now().atOffset(
                            java.time.ZoneOffset.UTC) : null);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new AssertionError("could not seed cases for the caseCount assertions", e);
        }
    }
}
