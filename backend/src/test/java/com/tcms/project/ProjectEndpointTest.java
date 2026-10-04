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
    /** Two grants on ONE project — Viewer directly, Test Lead through a team. */
    private static final UUID DUAL = UUID.fromString("6e003000-0000-4000-8000-000000000006");

    private static final UUID NORTH_QA_TEAM = UUID.fromString("6e004000-0000-4000-8000-000000000001");
    /**
     * A second team, for DUAL alone.
     *
     * <p>Deliberately not reusing the QA team: a Test Lead grant added there would also reach
     * {@code TEAM_MEMBER} and quietly break the assertion that a team grant reveals only the
     * team's own project. Two fixtures sharing a team is how one test's setup becomes another
     * test's failure.
     */
    private static final UUID NORTH_LEADS_TEAM = UUID.fromString("6e004000-0000-4000-8000-000000000002");

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
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-proj", "Northwind", now);
            provisionCompany(SOUTH, "south-proj", "Southwind", now);

            activeUser(LEAD, "lead@north.test", now);
            activeUser(NO_GRANT, "nogrant@north.test", now);
            activeUser(ADMIN, "admin@north.test", now);
            activeUser(SOUTHERNER, "engineer@south.test", now);
            activeUser(TEAM_MEMBER, "team@north.test", now);
            activeUser(DUAL, "dual@north.test", now);
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
            member(NORTH, DUAL, "D. Both", now);

            team(NORTH_QA_TEAM, NORTH, "QA", now);
            team(NORTH_LEADS_TEAM, NORTH, "Leads", now);
            joinTeam(NORTH, NORTH_QA_TEAM, TEAM_MEMBER, now);
            joinTeam(NORTH, NORTH_LEADS_TEAM, DUAL, now);

            grantOnProject("n-lead", NORTH, Role.TEST_LEAD_ID, LEAD, NORTH_BANKING, now);
            grantOnCompany("n-admin", NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantToTeam("n-team", NORTH, Role.TEST_ENGINEER_ID, NORTH_QA_TEAM, NORTH_MOBILE, now);

            // The two-grants-on-one-project case: the weaker one held directly, the stronger
            // through a team, so "strongest wins" cannot pass by preferring direct grants.
            grantOnProject("n-dual-viewer", NORTH, Role.VIEWER_ID, DUAL, NORTH_BANKING, now);
            grantToTeam("n-dual-lead", NORTH, Role.TEST_LEAD_ID, NORTH_LEADS_TEAM, NORTH_BANKING, now);
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

        restoreArchiveState();
    }

    /**
     * Puts North's three projects back the way the fixture above describes them, and clears their
     * audit trail.
     *
     * <p>Here rather than in {@link Archiving} because §159 gave this class its first tests that
     * <em>write</em>, and {@code IntegrationTestBase} does not clean between tests: everything above
     * is written as "create if absent", so a project one test archives is still archived when the
     * next test seeds — including the read-only tests two groups up, which assert that
     * {@code NORTH_BANKING} is not archived and that {@code NORTH_ARCHIVED} is. Left to the nested
     * class, the restore would run for its own tests and not for theirs, and the result would depend
     * on the order JUnit happened to choose. That is §19's failure mode: green alone, red in the
     * suite, and attributed to whichever slice was being written at the time.
     */
    private void restoreArchiveState() throws SQLException {
        try (var connection = superuserConnection()) {
            try (var statement = connection.prepareStatement(
                    "UPDATE project SET archived_at = NULL WHERE id IN (?, ?)")) {
                statement.setObject(1, NORTH_BANKING);
                statement.setObject(2, NORTH_MOBILE);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE project SET archived_at = now() WHERE id = ? AND archived_at IS NULL")) {
                statement.setObject(1, NORTH_ARCHIVED);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE project SET color = 'project-1' WHERE id IN (?, ?, ?)")) {
                statement.setObject(1, NORTH_BANKING);
                statement.setObject(2, NORTH_MOBILE);
                statement.setObject(3, NORTH_ARCHIVED);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "DELETE FROM audit_event WHERE project_id IN (?, ?, ?)")) {
                statement.setObject(1, NORTH_BANKING);
                statement.setObject(2, NORTH_MOBILE);
                statement.setObject(3, NORTH_ARCHIVED);
                statement.executeUpdate();
            }
        }
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
            assertThat(List.of("id", "key", "name", "archived", "caseCount", "color", "myRole"))
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
        @DisplayName("the projection itself excludes COMPANY-scope grants — not the map lookup")
        void companyScopeIsExcludedByTheQueryNotByAccident() {
            // Found by mutation, and the reason this test exists rather than resting on the
            // rendered null above: deleting `project_id IS NOT NULL` from the query changed
            // NOTHING observable. The company-scope row comes back with a null project_id,
            // lands in the result map under a null key that no project id ever matches, and
            // disappears. HashMap tolerating a null key is what hid it.
            //
            // So the assertion is moved to where the predicate lives. A row with no project
            // id has no business in a per-project projection, whatever the consumer happens
            // to survive — and the next consumer may not use a null-tolerant map.
            List<RoleAssignmentRepository.ProjectRoleName> rows = inCompanyRead(NORTH,
                    () -> roleAssignments.findProjectRoleNames(NORTH, ADMIN));

            assertThat(rows)
                    .as("this caller holds ONLY a COMPANY-scope grant, so a per-project "
                            + "projection must return nothing at all for them")
                    .isEmpty();

            // Stated separately so an empty result cannot pass by the query being broken for
            // everyone: the same projection does return a row for a project-scope grant.
            assertThat(inCompanyRead(NORTH, () -> roleAssignments.findProjectRoleNames(NORTH, LEAD)))
                    .hasSize(1);
        }

        @Test
        @DisplayName("two grants on one project — the stronger role is shown, derived from its permissions")
        void strongestGrantWins() throws Exception {
            // DUAL holds Viewer directly and Test Lead through the QA team, both on
            // NORTH_BANKING. One string may be shown, so the tie is broken by how many
            // permissions each role actually confers — read from role_permission, never from
            // a hard-coded ranking of names (handoff §6 lesson 10).
            assertThat(detailAs(DUAL, NORTH, NORTH_BANKING).get("myRole").asText())
                    .as("Test Lead confers strictly more than Viewer, so it is the honest label")
                    .isEqualTo("Test Lead");
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
    // Archiving — §159, the operation the column waited for since V1
    // =====================================================================

    /**
     * {@code PUT /projects/{projectId}/archive}.
     *
     * <p>Nested here rather than in a class of its own because the fixture this needs already
     * exists above and is the hostile one: a Test Lead granted on exactly one project (holds
     * {@code project:settings} there and nowhere else), a Test Engineer reaching another project
     * through a team (holds it nowhere), a member with no grant at all, a Company Admin who reaches
     * every project without a project grant, and a project that is <em>already</em> archived, which
     * from {@code V1} to §159 was a state only a test could produce.
     */
    @Nested
    @DisplayName("Archiving — project:settings, idempotent, and a mark rather than a lock")
    class Archiving {

        @Test
        @DisplayName("a Test Lead archives their project, and the column is finally written")
        void leadArchives() throws Exception {
            JsonNode body = json.readTree(archiveAs(LEAD, NORTH, NORTH_BANKING, "true").body());

            assertThat(body.get("archived").asBoolean()).isTrue();
            assertThat(archivedAtOf(NORTH_BANKING))
                    .as("the response says archived, so the column must actually hold a timestamp")
                    .isNotNull();
        }

        @Test
        @DisplayName("the response is the same shape the list and the detail serve")
        void responseIsTheOneAssembler() throws Exception {
            // viewOfChanged exists so a write cannot answer with a project shaped differently from
            // the GET the client makes next — the field that would drift first being caseCount,
            // which viewOfCreated is allowed to leave at zero and this one is not.
            JsonNode written = json.readTree(archiveAs(LEAD, NORTH, NORTH_BANKING, "true").body());
            JsonNode read = detailAs(LEAD, NORTH, NORTH_BANKING);

            assertThat(written).isEqualTo(read);
            assertThat(written.get("caseCount").asLong())
                    .as("three live cases were seeded into this project")
                    .isEqualTo(3);
            assertThat(written.get("myRole").asText()).isEqualTo("Test Lead");
        }

        @Test
        @DisplayName("archiving and un-archiving round-trips, and both are audited")
        void roundTrip() throws Exception {
            archiveAs(LEAD, NORTH, NORTH_BANKING, "true");
            JsonNode back = json.readTree(archiveAs(LEAD, NORTH, NORTH_BANKING, "false").body());

            assertThat(back.get("archived").asBoolean()).isFalse();
            assertThat(archivedAtOf(NORTH_BANKING))
                    .as("un-archiving clears the timestamp — the sequence lives in audit_event")
                    .isNull();
            assertThat(auditActionsFor(NORTH_BANKING))
                    .containsExactly("project.archived", "project.unarchived");
        }

        @Test
        @DisplayName("setting the state a project already has writes nothing at all")
        void idempotent() throws Exception {
            // The already-archived project, through the Company Admin who reaches it without a
            // project grant. Nothing may move: not the timestamp, which would re-date a retirement
            // that happened months ago, and not the audit trail, where a row per repeated click
            // buries the change that actually happened.
            Instant before = archivedAtOf(NORTH_ARCHIVED);

            Response response = archiveAs(ADMIN, NORTH, NORTH_ARCHIVED, "true");

            assertThat(response.status()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("archived").asBoolean()).isTrue();
            assertThat(archivedAtOf(NORTH_ARCHIVED)).isEqualTo(before);
            assertThat(auditActionsFor(NORTH_ARCHIVED)).isEmpty();
        }

        @Test
        @DisplayName("an archived project still accepts writes — it is a mark, not a lock")
        void archivingDoesNotLock() throws Exception {
            archiveAs(ADMIN, NORTH, NORTH_BANKING, "true");

            // A real project-scoped write through the same gate, on the archived project. If a
            // later slice makes archived projects read-only, this test is where that decision has
            // to be taken deliberately rather than discovered by a client.
            int status = mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/projects/" + NORTH_BANKING + "/settings")
                            .header("Authorization", "Bearer " + tokenFor(ADMIN, NORTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reviewEnabledCase": true, "reviewEnabledSuite": false,
                                     "reviewEnabledPlan": false, "requiredPeerReviews": 1,
                                     "authorMayApproveOwnWork": false,
                                     "editAfterApproval": "RE_REVIEW",
                                     "requireApprovedCaseForRun": false}
                                    """))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Engineer is refused with 403, and nothing moves")
        void engineerIsRefused() throws Exception {
            // TEAM_MEMBER reaches NORTH_MOBILE through a team grant of Test Engineer, which
            // carries project:read and not project:settings. They can see the project, so 403 is
            // the honest answer and 404 would claim their own project had vanished.
            Response response = archiveAs(TEAM_MEMBER, NORTH, NORTH_MOBILE, "true");

            assertThat(response.status()).isEqualTo(403);
            // <s>The refusal never names the permission key.</s> -> superseded by 005 C1a, which
            // added `permission` and `scope` to Problem for exactly this body. The old assertion
            // was about not leaking; naming the key the CALLER LACKS leaks nothing they could not
            // read off the roles matrix, and withholding it is what made the SPA's refusal say
            // "not allowed" and stop. The screen now says which key and who can grant it.
            assertThat(response.body())
                    .as("005 C1a: the refusal names the key the caller lacks, and its scope")
                    .contains("\"permission\":\"project:settings\"")
                    .contains("\"scope\":\"PROJECT\"");
            assertThat(archivedAtOf(NORTH_MOBILE)).isNull();
        }

        @Test
        @DisplayName("a project the caller cannot see is 404, never 403")
        void unreachableIs404() throws Exception {
            // Both unreachable reasons, answered identically: no grant inside the caller's own
            // company, and another company's project entirely. A 403 for either would confirm the
            // id names something real (§6t).
            assertThat(archiveAs(NO_GRANT, NORTH, NORTH_BANKING, "true").status()).isEqualTo(404);
            assertThat(archiveAs(SOUTHERNER, SOUTH, NORTH_BANKING, "true").status()).isEqualTo(404);
            assertThat(archivedAtOf(NORTH_BANKING)).isNull();
        }

        @Test
        @DisplayName("an absent archived field is a 400 on the field, not a silent un-archive")
        void absentFieldIsAFieldError() throws Exception {
            Response response = archiveAs(ADMIN, NORTH, NORTH_ARCHIVED, null);

            assertThat(response.status()).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("errors").has("archived")).isTrue();
            assertThat(archivedAtOf(NORTH_ARCHIVED))
                    .as("a malformed request must not be read as 'set it to false'")
                    .isNotNull();
        }

        @Test
        @DisplayName("an anonymous caller is refused before the project is resolved")
        void anonymousIsRefused() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/projects/" + NORTH_BANKING + "/archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"archived\": true}"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // Colour — 023 A2
    // =====================================================================

    /** {@code PUT /projects/{projectId}/color}, on the same hostile fixture as archiving. */
    @Nested
    @DisplayName("Colour — project:settings, a token name, idempotent")
    class Colouring {

        @Test
        @DisplayName("a Test Lead sets their project's colour, and the column holds it")
        void leadSetsColour() throws Exception {
            Response response = colourAs(LEAD, NORTH, NORTH_BANKING, "project-5");

            assertThat(response.status()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("color").asText()).isEqualTo("project-5");
            assertThat(colourOf(NORTH_BANKING)).isEqualTo("project-5");
            assertThat(detailAs(LEAD, NORTH, NORTH_BANKING).get("color").asText())
                    .isEqualTo("project-5");
        }

        @Test
        @DisplayName("sending the colour the project already has is a 200 that writes nothing")
        void idempotent() throws Exception {
            colourAs(LEAD, NORTH, NORTH_BANKING, "project-5");
            int auditBefore = auditActionsFor(NORTH_BANKING).size();

            assertThat(colourAs(LEAD, NORTH, NORTH_BANKING, "project-5").status()).isEqualTo(200);
            assertThat(auditActionsFor(NORTH_BANKING)).hasSize(auditBefore);
        }

        @Test
        @DisplayName("a colour outside the eight is a 400 on color, and the column is untouched")
        void unknownColourIs400() throws Exception {
            Response response = colourAs(LEAD, NORTH, NORTH_BANKING, "#ff0000");

            assertThat(response.status()).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("errors").has("color")).isTrue();
            assertThat(colourAs(LEAD, NORTH, NORTH_BANKING, null).status()).isEqualTo(400);
            assertThat(colourOf(NORTH_BANKING)).isEqualTo("project-1");
        }

        @Test
        @DisplayName("a Test Engineer is refused with 403 and nothing moves")
        void engineerIsRefused() throws Exception {
            Response response = colourAs(TEAM_MEMBER, NORTH, NORTH_MOBILE, "project-5");

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body()).contains("\"permission\":\"project:settings\"");
            assertThat(colourOf(NORTH_MOBILE)).isEqualTo("project-1");
        }

        @Test
        @DisplayName("a project the caller cannot see is 404, never 403")
        void unreachableIs404() throws Exception {
            assertThat(colourAs(NO_GRANT, NORTH, NORTH_BANKING, "project-5").status()).isEqualTo(404);
            assertThat(colourAs(SOUTHERNER, SOUTH, NORTH_BANKING, "project-5").status()).isEqualTo(404);
            assertThat(colourOf(NORTH_BANKING)).isEqualTo("project-1");
        }

        @Test
        @DisplayName("an anonymous caller is 401")
        void anonymousIsRefused() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/projects/" + NORTH_BANKING + "/color")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"color\": \"project-5\"}"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    private Response colourAs(UUID userId, UUID companyId, UUID projectId, String color)
            throws Exception {

        var result = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/color")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(color == null ? "{}" : "{\"color\": \"" + color + "\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        return new Response(result.getStatus(), result.getContentAsString());
    }

    private String colourOf(UUID projectId) throws SQLException {
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement(
                        "SELECT color FROM project WHERE id = ?")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the project must exist").isTrue();
                return rows.getString("color");
            }
        }
    }

    /** A status and a body together, so a test can assert on both without a second request. */
    private record Response(int status, String body) {
    }

    /**
     * @param archived the JSON value for the field, or {@code null} to send a body without it
     */
    private Response archiveAs(UUID userId, UUID companyId, UUID projectId, String archived)
            throws Exception {

        var result = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/archive")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archived == null ? "{}" : "{\"archived\": " + archived + "}")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        return new Response(result.getStatus(), result.getContentAsString());
    }

    /**
     * {@code project.archived_at} as the database holds it, read through the container's superuser.
     *
     * <p>Ground truth rather than the endpoint's own answer: the response saying "archived" and the
     * column holding a value are two claims, and this slice exists because the second one had never
     * been true.
     */
    private Instant archivedAtOf(UUID projectId) throws SQLException {
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement(
                        "SELECT archived_at FROM project WHERE id = ?")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the project must exist").isTrue();
                var value = rows.getTimestamp("archived_at");
                return value == null ? null : value.toInstant();
            }
        }
    }

    /** This project's audit actions, oldest first. */
    private List<String> auditActionsFor(UUID projectId) throws SQLException {
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement(
                        "SELECT action FROM audit_event WHERE project_id = ? ORDER BY occurred_at")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                List<String> actions = new java.util.ArrayList<>();
                while (rows.next()) {
                    actions.add(rows.getString("action"));
                }
                return List.copyOf(actions);
            }
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

    /**
     * A read inside a company context <em>and</em> a transaction — both are required.
     *
     * <p>A repository query method with no transaction runs with no {@code app.current_company}
     * set, and under RLS that means zero rows: the assertion would pass for the wrong reason,
     * which is the defect {@code RepositoryTransactionTest} exists for.
     */
    private <T> T inCompanyRead(UUID companyId, java.util.function.Supplier<T> read) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status -> read.get());
        }
    }

    private void team(UUID teamId, UUID companyId, String name, Instant now) {
        if (teams.findById(teamId).isEmpty()) {
            teams.save(Team.create(teamId, companyId, name, null, null, now));
        }
    }

    private void joinTeam(UUID companyId, UUID teamId, UUID userId, Instant now) {
        if (teamMemberships.findById(new TeamMembershipId(teamId, userId)).isEmpty()) {
            teamMemberships.save(TeamMembership.join(companyId, teamId, userId, false, now));
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
     * <p>There is still no {@code TestCase} entity — the {@code repository} module exists now and
     * owns {@code Suite}, but nothing maps a case — and {@code caseCount} is precisely the field
     * that needs rows in that table. Written as the superuser rather than through the app role
     * because this fixture needs a suite row and a key sequence it has no service to allocate; the
     * assertions still read through the ordinary policed path, which is what is under test.
     * ({@code SuiteEndpointTest} writes the equivalent fixture through the app role instead, which
     * is the stronger arrangement and the one to copy.)
     *
     * @param live    non-deleted cases
     * @param deleted soft-deleted ones, which must NOT be counted
     */
    private void seedCases(String prefix, UUID companyId, UUID projectId, int live, int deleted) {
        try (var connection = superuserConnection()) {
            UUID suiteId = UUID.nameUUIDFromBytes(("suite:" + projectId).getBytes());
            try (var suite = connection.prepareStatement("""
                    INSERT INTO suite (id, company_id, project_id, key, key_seq, name)
                    VALUES (?, ?, ?, ?, 1, 'Root')
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
