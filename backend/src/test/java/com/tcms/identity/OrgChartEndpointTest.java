package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectAccess;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /org-chart} — <strong>the disclosure boundary, asserted on the response body.</strong>
 *
 * <p>Every "absent" cell of the plan's §4.4 redaction table gets a named arm here, and the ones that
 * matter assert against the raw JSON rather than against a parsed field. A project name the server
 * sends and the client merely declines to render is the defect this class exists to catch, and it is
 * invisible to any assertion that reads a field by name — there is no field to read.
 *
 * <p>The design in one sentence: <em>the people axis is a company fact; the project axis is the
 * reader's own.</em> Two colleagues open this chart, see the same people, and see different
 * matrices. Several tests below look like they contradict each other and do not — they are the same
 * chart read by different people, which is the whole point.
 *
 * <h2>The load-bearing one</h2>
 *
 * <p>{@link Agreement} pins the display path to the authorization path in <strong>both</strong>
 * directions: a cell must never be served for a (person, project) where
 * {@link ProjectAccess#canView} is false, and a cell must <em>exist</em> wherever it is true and the
 * person holds no COMPANY-scope role. The first direction is the leak; the second is the one that
 * hides people. It was verified by breaking it — replacing {@code reach.maySee} in
 * {@code OrgChartService} with {@code true} reddens {@code noCellIsServedForAProjectTheViewerCannotOpen}
 * with BOREAL's id in the failure message — and reverted. A test that cannot fail is not coverage
 * (§162).
 *
 * <p>Ids use the {@code 0c…} prefix, which no other class in the suite uses — checked with a scan of
 * every {@code UUID.fromString} literal under {@code src/test/java} rather than assumed, because
 * handoff §19's fixture collision has now happened five times, two lanes taking the same prefix
 * merge clean, and the failure lands at runtime on {@code project_pkey}.
 */
@AutoConfigureMockMvc
@DisplayName("The org chart — the people are the company's, the columns are the reader's")
class OrgChartEndpointTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("0c000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("0c000000-0000-4000-8000-00000000000b");

    /** Visible to every NORTH person who holds anything. */
    private static final UUID ATLAS = UUID.fromString("0c001000-0000-4000-8000-000000000001");
    /**
     * The project VIEWER cannot open — the subject of every "no column, no cell, no count" arm.
     * Named distinctively so a substring search of the raw body is a real assertion.
     */
    private static final UUID BOREAL = UUID.fromString("0c001000-0000-4000-8000-000000000002");
    /** SOUTH's project. Nothing in NORTH's chart may mention it, ever. */
    private static final UUID SOUTH_PROJECT = UUID.fromString("0c001000-0000-4000-8000-000000000003");

    /** Company Admin of NORTH: sees every project, and has NO cell in any of them. */
    private static final UUID ADMIN = UUID.fromString("0c003000-0000-4000-8000-000000000001");
    /** Test Lead granted DIRECTLY on ATLAS and on BOREAL. */
    private static final UUID LEAD = UUID.fromString("0c003000-0000-4000-8000-000000000002");
    /** Test Engineer on ATLAS THROUGH the QA team, and in no other way. */
    private static final UUID ENGINEER = UUID.fromString("0c003000-0000-4000-8000-000000000003");
    /** Company-wide Test Engineer — a PROJECT-scope role granted with no project. INHERITED. */
    private static final UUID ROVER = UUID.fromString("0c003000-0000-4000-8000-000000000004");
    /** Viewer on ATLAS alone. The clipped reader, and the persona most of this class is about. */
    private static final UUID VIEWER = UUID.fromString("0c003000-0000-4000-8000-000000000005");
    /** Invited to NORTH, never accepted, holds nothing. */
    private static final UUID INVITEE = UUID.fromString("0c003000-0000-4000-8000-000000000006");
    /** Was a member of NORTH and was removed. Must not appear at all. */
    private static final UUID DEPARTED = UUID.fromString("0c003000-0000-4000-8000-000000000007");
    /** A member of SOUTH and of nothing in NORTH — the cross-company subject. */
    private static final UUID OUTSIDER = UUID.fromString("0c003000-0000-4000-8000-000000000008");

    private static final UUID QA_TEAM = UUID.fromString("0c004000-0000-4000-8000-000000000001");
    private static final UUID PLATFORM_TEAM = UUID.fromString("0c004000-0000-4000-8000-000000000002");

    private static final UUID GRANT_ADMIN = UUID.fromString("0c005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_LEAD_ATLAS = UUID.fromString("0c005000-0000-4000-8000-000000000002");
    private static final UUID GRANT_LEAD_BOREAL = UUID.fromString("0c005000-0000-4000-8000-000000000003");
    private static final UUID GRANT_QA_TEAM = UUID.fromString("0c005000-0000-4000-8000-000000000004");
    private static final UUID GRANT_ROVER = UUID.fromString("0c005000-0000-4000-8000-000000000005");
    private static final UUID GRANT_VIEWER = UUID.fromString("0c005000-0000-4000-8000-000000000006");
    private static final UUID GRANT_OUTSIDER = UUID.fromString("0c005000-0000-4000-8000-000000000007");

    private static final String BOREAL_NAME = "Boreal Ledger";
    private static final String BOREAL_KEY = "BOR";
    private static final String SOUTH_NAME = "Southwind Portal";

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
    private ProjectAccess access;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-chart", "Northwind", now);
            provisionCompany(SOUTH, "south-chart", "Southwind", now);

            activeUser(ADMIN, "admin@north-chart.test", now);
            activeUser(LEAD, "lead@north-chart.test", now);
            activeUser(ENGINEER, "engineer@north-chart.test", now);
            activeUser(ROVER, "rover@north-chart.test", now);
            activeUser(VIEWER, "viewer@north-chart.test", now);
            activeUser(INVITEE, "invitee@north-chart.test", now);
            activeUser(DEPARTED, "departed@north-chart.test", now);
            activeUser(OUTSIDER, "outsider@south-chart.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, NORTH, "ATL", "Atlas", now);
            seedProject(BOREAL, NORTH, BOREAL_KEY, BOREAL_NAME, now);

            member(NORTH, ADMIN, "A. Admin", now);
            member(NORTH, LEAD, "L. Lead", now);
            member(NORTH, ENGINEER, "E. Engineer", now);
            member(NORTH, ROVER, "R. Rover", now);
            member(NORTH, VIEWER, "V. Viewer", now);
            invitedMember(NORTH, INVITEE, "I. Invitee", now);
            removedMember(NORTH, DEPARTED, "D. Departed", now);

            team(QA_TEAM, NORTH, "QA", null, now);
            team(PLATFORM_TEAM, NORTH, "Platform", QA_TEAM, now);
            joinTeam(NORTH, QA_TEAM, ENGINEER, false, now);
            joinTeam(NORTH, QA_TEAM, LEAD, true, now);
            joinTeam(NORTH, PLATFORM_TEAM, ENGINEER, true, now);

            grantOnCompany(GRANT_ADMIN, NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject(GRANT_LEAD_ATLAS, NORTH, Role.TEST_LEAD_ID, LEAD, ATLAS, now);
            grantOnProject(GRANT_LEAD_BOREAL, NORTH, Role.TEST_LEAD_ID, LEAD, BOREAL, now);
            grantTeamOnProject(GRANT_QA_TEAM, NORTH, Role.TEST_ENGINEER_ID, QA_TEAM, ATLAS, now);
            // A PROJECT-scope role with NO project: the company-wide default §A1.1 calls inherited.
            grantOnCompany(GRANT_ROVER, NORTH, Role.TEST_ENGINEER_ID, ROVER, now);
            grantOnProject(GRANT_VIEWER, NORTH, Role.VIEWER_ID, VIEWER, ATLAS, now);
            grantCompanyReadBaseline(NORTH);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            seedProject(SOUTH_PROJECT, SOUTH, "STH", SOUTH_NAME, now);
            member(SOUTH, OUTSIDER, "O. Outsider", now);
            grantOnProject(GRANT_OUTSIDER, SOUTH, Role.TEST_LEAD_ID, OUTSIDER, SOUTH_PROJECT, now);
            grantCompanyReadBaseline(SOUTH);
        }));

        dropGrantsTestsAdded();
    }

    /**
     * Removes every grant this class's own tests created.
     *
     * <p>Two arms below add a grant to drive §A1.1 and the ranking, and the seeding above is
     * idempotent rather than destructive — so without this the next test reads the previous one's
     * matrix and "ROVER inherits Test Engineer on ATLAS" starts passing or failing on JUnit's method
     * order. §19's defect, cheaper to prevent than to diagnose. Through NORTH's own app-role
     * connection, so RLS is in force while the cleanup runs.
     */
    private void dropGrantsTestsAdded() throws SQLException {
        String seeded = List.of(
                        GRANT_ADMIN, GRANT_LEAD_ATLAS, GRANT_LEAD_BOREAL,
                        GRANT_QA_TEAM, GRANT_ROVER, GRANT_VIEWER).stream()
                .map(id -> "'" + id + "'")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        try (Connection connection = appConnection(NORTH);
             var statement = connection.prepareStatement(
                     "DELETE FROM role_assignment WHERE id NOT IN (" + seeded + ")"
                             + " AND role_id <> '" + Role.COMPANY_MEMBER_ID + "'")) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // §4.4, row by row — what a plain member may see
    // =====================================================================

    @Nested
    @DisplayName("§4.4 — the people axis is unredacted for every member")
    class ThePeopleAxis {

        @Test
        @DisplayName("id, displayName, email and avatarUrl reach a plain member")
        void identityFieldsAreUnredacted() throws Exception {
            JsonNode lead = personIn(chartAs(VIEWER), LEAD);

            assertThat(lead.get("id").asText()).isEqualTo(LEAD.toString());
            assertThat(lead.get("displayName").asText()).isEqualTo("L. Lead");
            assertThat(lead.get("email").asText()).isEqualTo("lead@north-chart.test");
            assertThat(lead.has("avatarUrl")).isTrue();
        }

        @Test
        @DisplayName("status reaches a plain member — ACTIVE and INVITED alike")
        void statusIsUnredacted() throws Exception {
            JsonNode chart = chartAs(VIEWER);

            assertThat(personIn(chart, LEAD).get("status").asText()).isEqualTo("ACTIVE");
            // Onboarding is not a secret, and rendering an invitee ACTIVE would state a fact about
            // a different client's tenancy for somebody already active elsewhere.
            assertThat(personIn(chart, INVITEE).get("status").asText()).isEqualTo("INVITED");
        }

        @Test
        @DisplayName("a REMOVED membership is absent — offboarding removes, it does not deactivate")
        void aRemovedMemberIsNotOnTheChart() throws Exception {
            assertThat(idsOf(chartAs(VIEWER).get("people")))
                    .as("\"removed\" must not mean \"still listed, merely inert\"")
                    .doesNotContain(DEPARTED.toString());
        }

        @Test
        @DisplayName("teams and leadsTeams are refs with ids, and leadsTeams is a subset of teams")
        void teamsAreNodesNotNames() throws Exception {
            JsonNode engineer = personIn(chartAs(VIEWER), ENGINEER);

            // Refs rather than names: the chart links a person to a NODE. `User.teams` is
            // string[] and stays that way.
            assertThat(namesOf(engineer.get("teams"))).containsExactly("Platform", "QA");
            assertThat(engineer.get("teams").get(0).get("id").asText()).isNotBlank();
            assertThat(namesOf(engineer.get("leadsTeams"))).containsExactly("Platform");
            assertThat(namesOf(engineer.get("teams")))
                    .containsAll(namesOf(engineer.get("leadsTeams")));
        }

        @Test
        @DisplayName("a Team Lead flag is not the Test Lead role — ENGINEER leads Platform and is neither")
        void theLeadFlagConfersNothing() throws Exception {
            JsonNode engineer = personIn(chartAs(VIEWER), ENGINEER);

            assertThat(namesOf(engineer.get("leadsTeams"))).containsExactly("Platform");
            assertThat(engineer.get("companyRoles")).isEmpty();
            assertThat(engineer.get("projects").get(0).get("roleName").asText())
                    .as("their role comes from the QA team's grant, not from leading Platform")
                    .isEqualTo("Test Engineer");
        }
    }

    @Nested
    @DisplayName("005 N6 — the Gaps panel: every key a column has no active holder for")
    class TheGaps {

        @Test
        @DisplayName("a column staffed by a Test Lead and a Test Engineer has no gap the lead's role does not cover")
        void aLeadCoversTheTier() throws Exception {
            // ATLAS: LEAD holds Test Lead directly, ENGINEER via the QA team, VIEWER holds Viewer,
            // ROVER inherits Test Engineer company-wide. Nobody holds a COMPANY-scope key through
            // a project column — ADMIN's Company Admin grant is company-wide and counts on every
            // column — so the only keys nobody holds on ATLAS are the SYSTEM-scope seven.
            List<String> gaps = gapsOn(chartAs(ADMIN), ATLAS);

            assertThat(gaps).doesNotContain("case:approve", "project:settings", "case:review");
            assertThat(gaps)
                    .as("SYSTEM-scope keys are a gap on every column — an instance operator is "
                            + "not staffing, which is exactly what the panel is for")
                    .contains("company:create", "instance:audit");
        }

        @Test
        @DisplayName("a column nobody can approve on names the approval keys")
        void anUnapprovableColumnIsNamed() throws Exception {
            // BOREAL: LEAD holds Test Lead there, so approvals are covered. Revoke that one grant
            // and the column keeps ROVER's inherited Test Engineer (reviews, no approvals) and
            // ADMIN's company-wide role — which, being COMPANY scope, holds the project keys too.
            // So the gap only appears once ADMIN is gone as well; both are removed to isolate it.
            try (Connection connection = appConnection(NORTH);
                 var statement = connection.prepareStatement(
                         "DELETE FROM role_assignment WHERE id IN (?, ?)")) {
                statement.setObject(1, GRANT_LEAD_BOREAL);
                statement.setObject(2, GRANT_ADMIN);
                statement.executeUpdate();
            }

            // Read as ROVER, whose inherited role reaches every column; LEAD has just lost BOREAL.
            List<String> gaps = gapsOn(chartAs(ROVER), BOREAL);

            assertThat(gaps).contains("case:approve", "suite:approve", "plan:approve",
                    "project:settings", "project:members");
            assertThat(gaps)
                    .as("ROVER's inherited Test Engineer still reviews there")
                    .doesNotContain("case:review", "case:edit");
        }

        @Test
        @DisplayName("an INVITED grantee and a REMOVED member hold nothing — a gap is about who can act")
        void onlyActiveHoldersCount() throws Exception {
            // INVITEE is granted Test Lead on BOREAL while still INVITED; it must not close the
            // approval gap opened above, because an unredeemed invitation cannot approve anything.
            try (Connection connection = appConnection(NORTH);
                 var statement = connection.prepareStatement(
                         "DELETE FROM role_assignment WHERE id IN (?, ?)")) {
                statement.setObject(1, GRANT_LEAD_BOREAL);
                statement.setObject(2, GRANT_ADMIN);
                statement.executeUpdate();
            }
            inCompany(NORTH, () -> tx.executeWithoutResult(status -> grantOnProject(
                    UUID.fromString("0c005000-0000-4000-8000-0000000000aa"),
                    NORTH, Role.TEST_LEAD_ID, INVITEE, BOREAL, Instant.now())));

            assertThat(gapsOn(chartAs(ROVER), BOREAL)).contains("case:approve");
        }

        @Test
        @DisplayName("gaps are scoped to the caller's columns — no column, no gap, no existence claim")
        void gapsFollowTheColumns() throws Exception {
            JsonNode chart = chartAs(LEAD);
            List<String> columns = idsOf(chart.get("projects"));

            for (JsonNode gap : chart.get("gaps")) {
                assertThat(columns).contains(gap.get("projectId").asText());
            }
            assertThat(chart.get("gaps").toString()).doesNotContain(SOUTH_PROJECT.toString());
        }

        @Test
        @DisplayName("sorted by key within a column, so two reads are diffable")
        void sortedWithinAColumn() throws Exception {
            assertThat(gapsOn(chartAs(ADMIN), ATLAS)).isSorted();
        }

        private List<String> gapsOn(JsonNode chart, UUID projectId) {
            List<String> keys = new ArrayList<>();
            for (JsonNode gap : chart.get("gaps")) {
                if (gap.get("projectId").asText().equals(projectId.toString())) {
                    keys.add(gap.get("permission").asText());
                }
            }
            return keys;
        }
    }

    @Nested
    @DisplayName("§4.4 — companyRoles, lastLoginAt, and what is on no tier at all")
    class TheRedactedFields {

        @Test
        @DisplayName("companyRoles reaches every member — a company's administrators are not a secret")
        void companyRolesAreUnredacted() throws Exception {
            JsonNode chart = chartAs(VIEWER);

            assertThat(stringsOf(personIn(chart, ADMIN).get("companyRoles")))
                    .containsExactly("Company Admin");
            assertThat(personIn(chart, LEAD).get("companyRoles")).isEmpty();
        }

        @Test
        @DisplayName("the Company Member baseline is filtered out — every member holds it")
        void theBaselineIsNotABadge() throws Exception {
            JsonNode chart = chartAs(VIEWER);

            // Dropped by id rather than by role.assignable: three seeded rows are non-assignable
            // and only this one is a consequence of membership.
            assertThat(chart.get("people").toString()).doesNotContain("Company Member");
        }

        @Test
        @DisplayName("a Company Admin has NO cell in any column — authority is not a project default")
        void aCompanyAdminIsNotSmearedAcrossTheColumns() throws Exception {
            // Read as ADMIN, who sees both columns, so "no cells" cannot pass by the columns
            // being empty. §A1.1 property 2: filling every column with a COMPANY-scope role is
            // exactly the misreading putting it on the PERSON exists to prevent.
            JsonNode chart = chartAs(ADMIN);

            assertThat(chart.get("projects")).hasSize(2);
            assertThat(personIn(chart, ADMIN).get("projects")).isEmpty();
            assertThat(stringsOf(personIn(chart, ADMIN).get("companyRoles")))
                    .containsExactly("Company Admin");
        }

        @Test
        @DisplayName("lastLoginAt is null for a caller without company:members")
        void lastLoginIsWithheldFromAnOrdinaryMember() throws Exception {
            JsonNode chart = chartAs(VIEWER);

            for (JsonNode person : chart.get("people")) {
                assertThat(person.get("lastLoginAt").isNull())
                        .as("when a colleague last signed in is an administrative fact, not a "
                                + "directory one — the same rule as User.lastLoginAt")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("lastLoginAt is present for a caller who holds it — the field is real, not always null")
        void lastLoginReachesAnAdministrator() throws Exception {
            // Stated separately so the arm above cannot pass by the field being null for everyone,
            // which is how a redaction test passes against a value nothing populates.
            signIn(ADMIN);

            assertThat(personIn(chartAs(ADMIN), ADMIN).get("lastLoginAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("no grant id is on this surface — revocability belongs to GET /role-assignments")
        void noGrantIdReachesTheWire() throws Exception {
            String body = bodyAs(ADMIN);

            // Read as ADMIN, who may revoke every one of them — so their absence is a property of
            // the SURFACE and not of this reader.
            assertThat(body)
                    .doesNotContain(GRANT_LEAD_ATLAS.toString())
                    .doesNotContain(GRANT_QA_TEAM.toString())
                    .doesNotContain(GRANT_ROVER.toString())
                    .doesNotContain(GRANT_ADMIN.toString());
        }

        @Test
        @DisplayName("SYSTEM scope appears nowhere — an instance fact is not on a tenant's wire")
        void systemScopeIsAbsent() throws Exception {
            String body = bodyAs(ADMIN);

            // instance_role_assignment has no company_id and no RLS. There is no field to redact,
            // which is the point — telling a tenant which of its members has instance-wide reach
            // is a different disclosure, audited rather than advertised (roles spec §A7).
            assertThat(body).doesNotContain("Instance Admin").doesNotContain("isSystemAdmin");
        }
    }

    // =====================================================================
    // The project axis — the disclosure boundary
    // =====================================================================

    @Nested
    @DisplayName("§4.4 — the project axis is clipped to the READER, with no count and no placeholder")
    class TheProjectAxis {

        @Test
        @DisplayName("a member with project:read on one project of two gets exactly one column")
        void theColumnsAreTheReadersOwn() throws Exception {
            JsonNode chart = chartAs(VIEWER);

            assertThat(idsOf(chart.get("projects"))).containsExactly(ATLAS.toString());
        }

        @Test
        @DisplayName("the unreachable project's id, key, name and any count are ABSENT FROM THE BODY")
        void anUnreachableProjectIsIndistinguishableFromANonExistentOne() throws Exception {
            String body = bodyAs(VIEWER);

            // The real assertion of this class, and it is on the raw body rather than on a parsed
            // field BECAUSE there is no field to parse. A server that sent the name and a client
            // that declined to render it would pass every UI-level test ever written.
            assertThat(body)
                    .doesNotContain(BOREAL.toString())
                    .doesNotContain(BOREAL_NAME)
                    .doesNotContain(BOREAL_KEY);
            // And no COUNT either: a count is a name-free existence claim about the company's
            // portfolio, which §4.3's Option D was rejected for.
            assertThat(json.readTree(body).get("projects")).hasSize(1);
            assertThat(json.readTree(body).has("hiddenProjectCount")).isFalse();
        }

        @Test
        @DisplayName("a colleague's role on a project the reader cannot open produces no cell")
        void aCellIsAPropertyOfTheREADER() throws Exception {
            // LEAD holds Test Lead on BOREAL and VIEWER cannot open it. Their row is present and
            // complete on the people axis, and the cell simply does not exist.
            JsonNode leadForViewer = personIn(chartAs(VIEWER), LEAD);
            assertThat(idsOf(leadForViewer.get("projects"), "projectId"))
                    .containsExactly(ATLAS.toString());

            // The same person, the same grants, read by somebody who can open BOREAL. Two
            // colleagues seeing DIFFERENT matrices of the same company is the design, not a bug.
            JsonNode leadForAdmin = personIn(chartAs(ADMIN), LEAD);
            assertThat(idsOf(leadForAdmin.get("projects"), "projectId"))
                    .containsExactlyInAnyOrder(ATLAS.toString(), BOREAL.toString());
        }

        @Test
        @DisplayName("a team's grant on an unreachable project does not leak through the team layer")
        void theTeamLayerIsClippedToo() throws Exception {
            // `Team.projects[]` already filters on the reader's reach, and the chart serves teams
            // through the SAME assembler — so this is a regression arm on the reuse rather than on
            // a second filter. If somebody reimplements listTeams here, this is what reddens.
            assertThat(bodyAs(VIEWER)).doesNotContain(BOREAL_NAME);
            assertThat(chartAs(ADMIN).get("teams").toString()).contains("Atlas");
        }
    }

    // =====================================================================
    // via — the three arms, one fixture each
    // =====================================================================

    @Nested
    @DisplayName("via — DIRECT, TEAM, INHERITED, and the Company Admin who is none of them")
    class Via {

        @Test
        @DisplayName("DIRECT — a grant naming this person and this project")
        void aDirectGrant() throws Exception {
            JsonNode cell = cellIn(chartAs(ADMIN), LEAD, ATLAS);

            assertThat(cell.get("via").asText()).isEqualTo("DIRECT");
            assertThat(cell.get("roleName").asText()).isEqualTo("Test Lead");
            assertThat(cell.get("viaTeam").isNull())
                    .as("viaTeam is non-null EXACTLY when via is TEAM")
                    .isTrue();
        }

        @Test
        @DisplayName("TEAM — a grant to a team they belong to, and it NAMES the team")
        void aGrantThroughATeam() throws Exception {
            // The arm a matrix built from `role_assignment WHERE project_id = ?` renders
            // identically to DIRECT — and the shape an org chart exists to encourage.
            JsonNode cell = cellIn(chartAs(ADMIN), ENGINEER, ATLAS);

            assertThat(cell.get("via").asText()).isEqualTo("TEAM");
            assertThat(cell.get("roleName").asText()).isEqualTo("Test Engineer");
            assertThat(cell.get("viaTeam").get("name").asText()).isEqualTo("QA");
            assertThat(cell.get("viaTeam").get("id").asText()).isEqualTo(QA_TEAM.toString());
        }

        @Test
        @DisplayName("INHERITED — a company-wide PROJECT-scope role, on EVERY column")
        void aCompanyWideProjectRole() throws Exception {
            // The arm that same naive query omits ENTIRELY. ROVER holds one grant, naming no
            // project, and has a cell in both columns.
            JsonNode chart = chartAs(ADMIN);
            JsonNode rover = personIn(chart, ROVER);

            assertThat(idsOf(rover.get("projects"), "projectId"))
                    .containsExactlyInAnyOrder(ATLAS.toString(), BOREAL.toString());
            for (JsonNode cell : rover.get("projects")) {
                assertThat(cell.get("via").asText()).isEqualTo("INHERITED");
                assertThat(cell.get("roleName").asText()).isEqualTo("Test Engineer");
                assertThat(cell.get("viaTeam").isNull()).isTrue();
            }
        }

        @Test
        @DisplayName("a grant naming the project REPLACES the inherited one — §A1.1, not a union")
        void aNamedGrantReplacesTheInheritedRole() throws Exception {
            // ROVER gains a Viewer grant on ATLAS: a WEAKER role that must still win, because the
            // switch is whether a grant names the project and never what it confers. This is "the
            // one place in this model where adding a grant REMOVES a permission", and a union here
            // renders the colleague you just demoted as still holding the higher role.
            UUID demotion = UUID.fromString("0c005000-0000-4000-8000-00000000000a");
            inCompany(NORTH, () -> tx.executeWithoutResult(status -> grantOnProject(
                    demotion, NORTH, Role.VIEWER_ID, ROVER, ATLAS, Instant.now())));

            JsonNode rover = personIn(chartAs(ADMIN), ROVER);

            assertThat(cellFor(rover, ATLAS).get("roleName").asText()).isEqualTo("Viewer");
            assertThat(cellFor(rover, ATLAS).get("via").asText()).isEqualTo("DIRECT");
            // The other column is untouched: replace applies per project, not company-wide.
            assertThat(cellFor(rover, BOREAL).get("roleName").asText()).isEqualTo("Test Engineer");
            assertThat(cellFor(rover, BOREAL).get("via").asText()).isEqualTo("INHERITED");
            // And the inherited name is NOT also present — replace, not union. One string per cell.
            assertThat(rover.get("projects")).hasSize(2);
        }

        @Test
        @DisplayName("two grants on one project — the stronger role wins, and via follows THAT grant")
        void theStrongerOfTwoGrantsWins() throws Exception {
            // ENGINEER already holds Test Engineer on ATLAS through QA. Add a DIRECT Viewer grant:
            // weaker, so the team's role must still show — and `via` must stay TEAM, because the
            // arm shown and the name shown cannot come from two different grants.
            UUID weaker = UUID.fromString("0c005000-0000-4000-8000-00000000000b");
            inCompany(NORTH, () -> tx.executeWithoutResult(status -> grantOnProject(
                    weaker, NORTH, Role.VIEWER_ID, ENGINEER, ATLAS, Instant.now())));

            JsonNode cell = cellIn(chartAs(ADMIN), ENGINEER, ATLAS);

            assertThat(cell.get("roleName").asText()).isEqualTo("Test Engineer");
            assertThat(cell.get("via").asText()).isEqualTo("TEAM");
            assertThat(cell.get("viaTeam").get("name").asText()).isEqualTo("QA");
        }
    }

    // =====================================================================
    // The agreement test — §8.3, and the single most valuable one here
    // =====================================================================

    @Nested
    @DisplayName("the display path and the authorization path answer the same question")
    class Agreement {

        @Test
        @DisplayName("every COLUMN served is one the READER can open — the disclosure boundary")
        void noColumnIsServedForAProjectTheReaderCannotOpen() throws Exception {
            // The reader-side half, and the arm that reddens when `reach.maySee` is weakened. The
            // two subject-side arms below do NOT: a cell for a project the reader cannot open still
            // names a person who legitimately holds a role there, so both would stay green while
            // the whole boundary was gone. Asked of the evaluator directly rather than of
            // AdminReach, so this is a second observer of the axis and not the same one twice.
            List<String> leaked = new ArrayList<>();

            for (UUID reader : List.of(ADMIN, LEAD, ENGINEER, ROVER, VIEWER)) {
                for (JsonNode column : chartAs(reader).get("projects")) {
                    UUID projectId = UUID.fromString(column.get("id").asText());
                    if (!inCompanyRead(NORTH, () -> access.canView(reader, projectId))) {
                        leaked.add(reader + " was served a column for " + projectId
                                + " (" + column.get("name").asText() + ") and cannot open it");
                    }
                }
            }

            assertThat(leaked).isEmpty();
        }

        @Test
        @DisplayName("no cell is served for a (person, project) where the PERSON's canView is FALSE")
        void noCellIsServedForAProjectItsPersonCannotOpen() throws Exception {
            // Direction one: the leak. A chart cell asserting somebody has a role on a project they
            // cannot open is the display path disagreeing with the evaluator — the class
            // ProjectMembershipService's header is written about.
            List<String> disagreements = new ArrayList<>();

            for (UUID reader : List.of(ADMIN, LEAD, ENGINEER, ROVER, VIEWER)) {
                JsonNode chart = chartAs(reader);
                for (JsonNode person : chart.get("people")) {
                    UUID subject = UUID.fromString(person.get("id").asText());
                    for (JsonNode cell : person.get("projects")) {
                        UUID projectId = UUID.fromString(cell.get("projectId").asText());
                        if (!inCompanyRead(NORTH, () -> access.canView(subject, projectId))) {
                            disagreements.add("read by " + reader + ": " + subject
                                    + " has a cell on " + projectId + " and cannot open it");
                        }
                    }
                }
            }

            assertThat(disagreements).isEmpty();
        }

        @Test
        @DisplayName("a cell EXISTS wherever canView is true and the person holds no company role")
        void everyViewableProjectHasACell() throws Exception {
            // Direction two, and the one that HIDES people rather than leaking them. Read as
            // ADMIN, whose columns are every project, so the sweep is over the whole matrix.
            JsonNode chart = chartAs(ADMIN);
            List<UUID> columns = List.of(ATLAS, BOREAL);
            List<String> missing = new ArrayList<>();

            for (JsonNode person : chart.get("people")) {
                UUID subject = UUID.fromString(person.get("id").asText());
                if (!person.get("companyRoles").isEmpty()) {
                    // A Company Admin reaches every project through a COMPANY-scope grant and
                    // correctly has no cell — the exclusion this direction has to carry.
                    continue;
                }
                for (UUID projectId : columns) {
                    boolean canView = inCompanyRead(NORTH, () -> access.canView(subject, projectId));
                    boolean hasCell = !cellFor(person, projectId).isMissingNode();
                    if (canView && !hasCell) {
                        missing.add(subject + " can open " + projectId + " and has no cell");
                    }
                }
            }

            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("the sweep is over a real matrix — it would pass over an empty one")
        void theSweepHasSomethingToSweep() throws Exception {
            // Both arms above are "no counterexamples found", which an empty chart satisfies.
            JsonNode chart = chartAs(ADMIN);
            long cells = 0;
            for (JsonNode person : chart.get("people")) {
                cells += person.get("projects").size();
            }

            assertThat(chart.get("people")).hasSizeGreaterThanOrEqualTo(6);
            assertThat(chart.get("projects")).hasSize(2);
            assertThat(cells).isGreaterThanOrEqualTo(5);
        }
    }

    // =====================================================================
    // Tenancy — the boundary that is not this feature's, and must hold anyway
    // =====================================================================

    @Nested
    @DisplayName("tenancy — another company's chart is not reachable, by two independent layers")
    class Tenancy {

        @Test
        @DisplayName("SOUTH's people, projects and teams appear nowhere in NORTH's chart")
        void noCrossCompanyLeakage() throws Exception {
            String body = bodyAs(ADMIN);

            assertThat(body)
                    .doesNotContain(OUTSIDER.toString())
                    .doesNotContain("O. Outsider")
                    .doesNotContain(SOUTH_PROJECT.toString())
                    .doesNotContain(SOUTH_NAME);
        }

        @Test
        @DisplayName("and NORTH's appear nowhere in SOUTH's — stated both ways")
        void theIsolationIsSymmetric() throws Exception {
            // "Sees one company" would also pass if it were consistently the WRONG one.
            String body = bodyAs(OUTSIDER, SOUTH);

            assertThat(body)
                    .doesNotContain(ATLAS.toString())
                    .doesNotContain(BOREAL_NAME)
                    .doesNotContain("A. Admin");
            assertThat(idsOf(json.readTree(body).get("people"))).containsExactly(OUTSIDER.toString());
        }

        @Test
        @DisplayName("RLS backstops it — a NEVER-SET GUC reads the chart's tables as zero rows")
        void anUnsetGucYieldsZeroRows() throws Exception {
            // The first of the two spellings of "unset". `current_setting(name, true)` returns
            // NULL, and `company_id = NULL` is never true — so the failure is closed rather than
            // open. Get the flag or the comparison wrong and a context-less query returns the
            // entire instance.
            try (Connection connection = appConnection()) {
                assertThat(countOf(connection, "company_membership")).isZero();
                assertThat(countOf(connection, "team_membership")).isZero();
                assertThat(countOf(connection, "role_assignment")).isZero();
            }
        }

        @Test
        @DisplayName("and so does a RECYCLED connection, where the GUC resets to '' and not to unset")
        void aPooledConnectionYieldsZeroRowsToo() throws Exception {
            // The second spelling, and the one production actually has. Once a custom setting has
            // been assigned — even with is_local => true — its reset value for the rest of the
            // SESSION is the empty string, so `current_setting(name, true)` returns '' rather than
            // NULL. V4 wraps the read in nullif(…, '') for exactly this; without it a context-less
            // read raises "invalid input syntax for type uuid" instead of returning nothing.
            try (Connection connection = appConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement(
                        "SELECT set_config('app.current_company', ?, true)")) {
                    statement.setString(1, NORTH.toString());
                    statement.execute();
                }
                connection.commit();
                connection.setAutoCommit(true);

                assertThat(countOf(connection, "company_membership")).isZero();
                assertThat(countOf(connection, "team_membership")).isZero();
                assertThat(countOf(connection, "role_assignment")).isZero();
            }
        }
    }

    // =====================================================================
    // The contract's shape
    // =====================================================================

    @Nested
    @DisplayName("the shape the contract declares")
    class ContractShape {

        @Test
        @DisplayName("the document has exactly teams, people, projects and gaps, all present")
        void theTopLevelShape() throws Exception {
            JsonNode chart = chartAs(VIEWER);

            // `gaps` is the fourth member since 005 S6 — required in the contract, so an absent
            // key is the SERVER-ONLY/CONTRACT-ONLY divergence ContractConventionTest exists for,
            // not an empty panel.
            assertThat(fieldsOf(chart))
                    .containsExactlyInAnyOrder("teams", "people", "projects", "gaps");
            assertThat(chart.get("teams").isArray()).isTrue();
            assertThat(chart.get("people").isArray()).isTrue();
            assertThat(chart.get("projects").isArray()).isTrue();
            assertThat(chart.get("gaps").isArray()).isTrue();
        }

        @Test
        @DisplayName("a person carries every declared property, required ones populated")
        void thePersonShape() throws Exception {
            JsonNode person = personIn(chartAs(VIEWER), ENGINEER);

            assertThat(fieldsOf(person)).containsExactlyInAnyOrder(
                    "id", "displayName", "email", "avatarUrl", "status",
                    "teams", "leadsTeams", "companyRoles", "projects", "lastLoginAt");
            // `required` does not descend, so an array declared required and served absent is the
            // defect the contract's own working agreement keeps paying for.
            assertThat(person.get("teams").isArray()).isTrue();
            assertThat(person.get("leadsTeams").isArray()).isTrue();
            assertThat(person.get("companyRoles").isArray()).isTrue();
            assertThat(person.get("projects").isArray()).isTrue();
        }

        @Test
        @DisplayName("a column carries id, key, name and archived")
        void theProjectShape() throws Exception {
            JsonNode column = chartAs(VIEWER).get("projects").get(0);

            assertThat(fieldsOf(column)).containsExactlyInAnyOrder("id", "key", "name", "archived");
            assertThat(column.get("key").asText()).isEqualTo("ATL");
            assertThat(column.get("archived").isBoolean()).isTrue();
        }

        @Test
        @DisplayName("a cell carries projectId, roleName, via and viaTeam")
        void theAssignmentShape() throws Exception {
            JsonNode cell = cellIn(chartAs(VIEWER), VIEWER, ATLAS);

            assertThat(fieldsOf(cell))
                    .containsExactlyInAnyOrder("projectId", "roleName", "via", "viaTeam");
            assertThat(cell.get("via").asText()).isIn("DIRECT", "TEAM", "INHERITED");
        }

        @Test
        @DisplayName("the read answers 200 for every member — it is gated on nothing they lack")
        void everyMemberMayRead() throws Exception {
            for (UUID reader : List.of(ADMIN, LEAD, ENGINEER, ROVER, VIEWER, INVITEE)) {
                assertThat(statusAs(reader))
                        .as("the chart is readable by any member of the company (plan §6k)")
                        .isEqualTo(200);
            }
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private JsonNode chartAs(UUID userId) throws Exception {
        return json.readTree(bodyAs(userId));
    }

    private String bodyAs(UUID userId) throws Exception {
        return bodyAs(userId, NORTH);
    }

    private String bodyAs(UUID userId, UUID companyId) throws Exception {
        return request(userId, companyId).getResponse().getContentAsString();
    }

    private int statusAs(UUID userId) throws Exception {
        return request(userId, NORTH).getResponse().getStatus();
    }

    private MvcResult request(UUID userId, UUID companyId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/org-chart")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private static JsonNode personIn(JsonNode chart, UUID userId) {
        for (JsonNode person : chart.get("people")) {
            if (person.get("id").asText().equals(userId.toString())) {
                return person;
            }
        }
        throw new AssertionError(userId + " is not on this chart");
    }

    private static JsonNode cellIn(JsonNode chart, UUID userId, UUID projectId) {
        JsonNode cell = cellFor(personIn(chart, userId), projectId);
        if (cell.isMissingNode()) {
            throw new AssertionError(userId + " has no cell on " + projectId);
        }
        return cell;
    }

    private static JsonNode cellFor(JsonNode person, UUID projectId) {
        for (JsonNode cell : person.get("projects")) {
            if (cell.get("projectId").asText().equals(projectId.toString())) {
                return cell;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static List<String> idsOf(JsonNode array) {
        return idsOf(array, "id");
    }

    private static List<String> idsOf(JsonNode array, String field) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get(field).asText()));
        return ids;
    }

    private static List<String> namesOf(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.get("name").asText()));
        return names;
    }

    private static List<String> stringsOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<String> fieldsOf(JsonNode object) {
        List<String> fields = new ArrayList<>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static long countOf(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    /** Records a sign-in, so {@code lastLoginAt} is a value rather than a null for everyone. */
    private void signIn(UUID userId) {
        tx.executeWithoutResult(status -> users.findById(userId).ifPresent(account -> {
            account.recordLogin(Instant.now());
            users.save(account);
        }));
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

    private void invitedMember(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            memberships.save(CompanyMembership.invite(companyId, userId, displayName, null, now));
        }
    }

    private void removedMember(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            membership.remove();
            memberships.save(membership);
        }
    }

    private void team(UUID id, UUID companyId, String name, UUID parentTeamId, Instant now) {
        if (teams.findById(id).isEmpty()) {
            teams.save(Team.create(id, companyId, name, null, parentTeamId, now));
        }
    }

    private void joinTeam(UUID companyId, UUID teamId, UUID userId, boolean lead, Instant now) {
        if (teamMemberships.findByTeamIdAndUserId(teamId, userId).isEmpty()) {
            teamMemberships.save(TeamMembership.join(companyId, teamId, userId, lead, now));
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

    private void grantTeamOnProject(
            UUID id, UUID companyId, UUID roleId, UUID teamId, UUID projectId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forTeamOnProject(
                    id, companyId, roleId, teamId, projectId, null, now));
        }
    }

    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }
}
