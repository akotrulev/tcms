package com.tcms.repository;

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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{projectId}/cases/{caseId}/versions} — the first reader
 * {@code test_case_version} has ever had.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The order reversed.</strong> The contract says newest first and the panel puts the
 *       current version at the top. Rows arrive oldest-first from any plan that does not sort, and
 *       nothing about five plausible rows in a plausible sequence looks wrong —
 *       {@link Listing#newestFirst} is the only assertion that would notice. §27's condition-order
 *       defect, in a different table.</li>
 *   <li><strong>An empty history answered as 404.</strong> Every case authored before the edit
 *       endpoint existed has no version rows (handoff §28), so collapsing "no history" into "no such
 *       case" 404s the panel on most of the cases in an existing installation — and reads as the case
 *       having been deleted.</li>
 *   <li><strong>The v1 gap filled in.</strong> A synthesized v1 would assert that the pre-edit
 *       content was the post-edit content, in the one table whose purpose is that past claims stay
 *       true.</li>
 *   <li><strong>An unattributable author invented.</strong> {@code created_by} is nullable and has no
 *       FK to {@code app_user} (handoff §28, finding 1), so both "nobody recorded" and "the id names
 *       no account" are states a real row is in. Either one rendered as a name is a false claim about
 *       who changed a test.</li>
 *   <li><strong>The second path segment ignored.</strong> {@code /projects/{granted}/cases/{elsewhere
 *       in the company}/versions} serves change comments — free text about a case the caller cannot
 *       open — and <em>every cross-company assertion still passes</em>, because the row genuinely is
 *       in the caller's company. §27's leak, one endpoint later.</li>
 *   <li><strong>The gate confused with a write gate.</strong> A version list discloses a subset of
 *       what the case detail already discloses, so it takes the read gate every role holds —
 *       {@link Gates#aViewerMayRead} pins that, and it is the assertion that goes red if someone
 *       "hardens" this endpoint onto {@code case:edit} and hides history from the client's own
 *       stakeholder.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code b3…}, checked with
 * {@code grep -rhoE '"[0-9a-f]{8}-[0-9a-f]{4}-' src/test/java} against every prefix already present
 * before writing. Sharing a prefix is handoff §19's defect and §29 hit it again: green alone, red in
 * the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case versions — newest first, gapped where history is gapped, gated as a read")
class CaseVersionEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("b3000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("b3000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("b3001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("b3001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("b3002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("b3003000-0000-4000-8000-000000000001");
    /** Viewer — holds {@code project:read} and no write permission at all. */
    private static final UUID VIEWER = UUID.fromString("b3003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding no grant — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("b3003000-0000-4000-8000-000000000003");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("b3003000-0000-4000-8000-000000000004");
    /** Authored two of the historic versions; offboarded from no company, so they resolve. */
    private static final UUID AUTHOR = UUID.fromString("b3003000-0000-4000-8000-000000000005");
    /**
     * Written into {@code created_by} and deliberately naming <strong>no account at all</strong>.
     * There is no FK on that column, so this row is not merely hypothetical.
     */
    private static final UUID GHOST = UUID.fromString("b3003000-0000-4000-8000-0000000000ff");

    private static final UUID PAYMENTS = UUID.fromString("b3004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("b3004000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("b3004000-0000-4000-8000-000000000003");

    /** Predates the write path: versions 2, 3 and 4, and no v1 — the gap, served as it is. */
    private static final UUID HISTORIC_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000001");
    /** Created through {@code POST}: exactly one version, and it is v1. */
    private static final UUID CREATED_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000002");
    /** Never edited and older than the write path: no version rows at all. */
    private static final UUID UNVERSIONED_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000003");
    /** Two versions: one with {@code created_by} NULL, one naming {@link #GHOST}. */
    private static final UUID UNATTRIBUTED_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000004");
    /** In the sibling project nobody here is granted, and it has versions to leak. */
    private static final UUID LOCKED_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000005");
    /** In the other client, with versions whose comments are identifiable on sight. */
    private static final UUID UMBRA_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000006");
    /** Soft-deleted, and its versions survive the delete — no read may serve them. */
    private static final UUID DELETED_CASE = UUID.fromString("b3005000-0000-4000-8000-000000000007");

    /** The one string that must never appear in a response of Acme's. */
    private static final String CONFIDENTIAL = "UMBRA CO CONFIDENTIAL";

    private static final Instant T0 =
            Instant.parse("2026-03-01T09:00:00Z").truncatedTo(ChronoUnit.MILLIS);

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
            company(ACME, "cv-acme", "Acme", now);
            company(UMBRA, "cv-umbra", "Umbra", now);

            activeUser(LEAD, "lead@cv-acme.test", "L. Lead", now);
            activeUser(VIEWER, "viewer@cv-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@cv-acme.test", "N. Grant", now);
            activeUser(AUTHOR, "author@cv-acme.test", "A. Author (canonical)", now);
            activeUser(OUTSIDER, "out@cv-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CVAOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "CVALCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            // The membership name differs from the account's canonical name on purpose: plan §6u
            // says the membership label wins, and a version list is a place a person's name is
            // rendered. Resolving from app_user alone would show every client the same string.
            member(ACME, AUTHOR, "A. Author", now);

            grantOnProject("cv-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("cv-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CVUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("cv-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        seedContent();
    }

    // =====================================================================
    // The list itself
    // =====================================================================

    @Nested
    @DisplayName("The list — newest first, gapped, and complete")
    class Listing {

        @Test
        @DisplayName("versions come back newest first — THE assertion of this class")
        void newestFirst() throws Exception {
            // Written 3, 2, 4 in that physical order (see seedContent) specifically so an unsorted
            // query cannot come back right by accident. The unique index on (case_id, version_no)
            // makes an ascending read the likely plan, which is the reverse of the contract.
            assertThat(versionNumbers(LEAD, ACME, ACME_BANKING, HISTORIC_CASE))
                    .containsExactly(4, 3, 2);
        }

        @Test
        @DisplayName("the v1 gap is served as a gap and nothing is synthesized")
        void theGapIsNotFilled() throws Exception {
            // A case older than the write path starts at v2 (handoff §28). Inventing a v1 from the
            // current row would claim the pre-edit content was the post-edit content.
            assertThat(versionNumbers(LEAD, ACME, ACME_BANKING, HISTORIC_CASE))
                    .doesNotContain(1)
                    .hasSize(3);
        }

        @Test
        @DisplayName("a case created through POST has exactly one version, and it is v1")
        void aCreatedCaseHasOneVersion() throws Exception {
            assertThat(versionNumbers(LEAD, ACME, ACME_BANKING, CREATED_CASE)).containsExactly(1);
        }

        @Test
        @DisplayName("a case with no history is 200 with an empty array, NOT a 404")
        void noHistoryIsNotAMissingCase() throws Exception {
            MvcResult result = versions(LEAD, ACME, ACME_BANKING, UNVERSIONED_CASE);

            assertThat(result.getResponse().getStatus())
                    .as("most cases in an existing installation have no version rows; 404 here "
                            + "reads as the case having been deleted")
                    .isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
        }

        @Test
        @DisplayName("every field of the contract's EntityVersion is populated from the row")
        void theShapeIsTheContracts() throws Exception {
            JsonNode newest = body(LEAD, ACME, ACME_BANKING, HISTORIC_CASE).get(0);

            assertThat(newest.get("versionNo").asInt()).isEqualTo(4);
            assertThat(newest.get("changeComment").asText()).isEqualTo("Reworded step 2");
            assertThat(newest.get("pinned").asBoolean()).isFalse();
            assertThat(Instant.parse(newest.get("createdAt").asText()))
                    .isEqualTo(T0.plus(3, ChronoUnit.DAYS));
            assertThat(newest.get("createdBy").get("displayName").asText())
                    // The MEMBERSHIP name, not the account's canonical one (plan §6u).
                    .isEqualTo("A. Author");
            assertThat(newest.get("createdBy").get("email").asText())
                    .isEqualTo("author@cv-acme.test");
        }

        @Test
        @DisplayName("a pinned version says so, and an unpinned one does not")
        void pinnedIsReadFromTheRowAndNotAssumed() throws Exception {
            // Nothing writes test_execution yet, so every version this application creates is
            // unpinned — which is exactly why a hard-coded `false` would pass every other test here.
            assertThat(pinnedByVersion(LEAD, ACME, ACME_BANKING, HISTORIC_CASE))
                    .containsExactly(false, true, false);
        }

        @Test
        @DisplayName("a version with no change comment serves null rather than an empty string")
        void anAbsentCommentIsNull() throws Exception {
            JsonNode oldest = body(LEAD, ACME, ACME_BANKING, HISTORIC_CASE).get(2);

            assertThat(oldest.get("versionNo").asInt()).isEqualTo(2);
            // '' and null read identically in a panel and mean different things in the column.
            assertThat(oldest.get("changeComment").isNull()).isTrue();
        }

        @Test
        @DisplayName("the snapshot is not in the response")
        void theSnapshotIsNotShipped() throws Exception {
            String payload = versions(LEAD, ACME, ACME_BANKING, HISTORIC_CASE)
                    .getResponse().getContentAsString();

            // A list entry that carries a full copy of the case sends the whole text of every
            // version in order to render a five-line panel.
            assertThat(payload).doesNotContain("snapshot").doesNotContain("contentHash");
            assertThat(payload).doesNotContain("What the case said at v4");
        }
    }

    // =====================================================================
    // Authors
    // =====================================================================

    @Nested
    @DisplayName("Authors — named where they can be, absent where they cannot")
    class Authors {

        @Test
        @DisplayName("a version with created_by NULL serves createdBy: null")
        void anUnrecordedAuthorIsNull() throws Exception {
            JsonNode rows = body(LEAD, ACME, ACME_BANKING, UNATTRIBUTED_CASE);

            assertThat(rows.get(1).get("versionNo").asInt()).isEqualTo(1);
            assertThat(rows.get(1).get("createdBy").isNull())
                    .as("the column is nullable; inventing an author is a false claim about who "
                            + "changed a test")
                    .isTrue();
        }

        @Test
        @DisplayName("a created_by naming no account serves createdBy: null, not a 500")
        void anUnresolvableAuthorIsNull() throws Exception {
            // There is no FK from test_case_version.created_by to app_user (handoff §28, finding 1),
            // so this row is a state the schema permits today. A lookup that assumed resolution
            // would NPE on it and take the whole panel down.
            JsonNode rows = body(LEAD, ACME, ACME_BANKING, UNATTRIBUTED_CASE);

            assertThat(rows.get(0).get("versionNo").asInt()).isEqualTo(2);
            assertThat(rows.get(0).get("createdBy").isNull()).isTrue();
        }

        @Test
        @DisplayName("one author across several versions is resolved once, and identically")
        void repeatedAuthorsAreConsistent() throws Exception {
            JsonNode rows = body(LEAD, ACME, ACME_BANKING, HISTORIC_CASE);

            // v4 and v3 share an author. Two different renderings of one person on one panel is
            // the same class of defect as the grid and the detail disagreeing about a case (§27).
            assertThat(rows.get(0).get("createdBy")).isEqualTo(rows.get(1).get("createdBy"));
            assertThat(rows.get(0).get("createdBy").get("id").asText())
                    .isEqualTo(AUTHOR.toString());
        }
    }

    // =====================================================================
    // The gates
    // =====================================================================

    @Nested
    @DisplayName("The gates — the read gate, and one bodiless 404 for four different failures")
    class Gates {

        @Test
        @DisplayName("a Viewer may read the history — this is a read, not a write")
        void aViewerMayRead() throws Exception {
            // The gate is project:read, held by all six roles, because this response is a strict
            // subset of what the case detail already serves the same caller. Gating it on case:edit
            // would hide history from the client's own stakeholder, and nothing else here notices.
            assertThat(versionNumbers(VIEWER, ACME, ACME_BANKING, HISTORIC_CASE))
                    .containsExactly(4, 3, 2);
        }

        @Test
        @DisplayName("a member with no grant gets 404 with no body, never 403")
        void membershipConveysNothing() throws Exception {
            MvcResult result = versions(NO_GRANT, ACME, ACME_BANKING, HISTORIC_CASE);

            // The case is in this caller's own company, so no cross-company check reaches it. A 403
            // would confirm the project id names something real (§6t, handoff §3 lesson 13).
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent projects answer identically")
        void theThreeUnreachableProjectsAnswerIdentically() throws Exception {
            for (UUID projectId : List.of(ACME_LOCKED, UMBRA_BANKING, UUID.randomUUID())) {
                MvcResult result = versions(LEAD, ACME, projectId, HISTORIC_CASE);
                assertThat(result.getResponse().getStatus()).as("project %s", projectId)
                        .isEqualTo(404);
                assertThat(result.getResponse().getContentAsString()).isEmpty();
            }
        }

        @Test
        @DisplayName("a case in a sibling project of the same company is 404")
        void aCaseIsScopedToItsProject() throws Exception {
            // The caller passed the gate on ACME_BANKING and on nothing else, so only the case
            // query's own project predicate separates them. Drop it and the change comments of a
            // project the caller cannot open are served through a path naming one they can.
            MvcResult result = versions(LEAD, ACME, ACME_BANKING, LOCKED_CASE);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("an unknown case id is 404, indistinguishable from an ungranted one")
        void anUnknownCaseIs404() throws Exception {
            MvcResult result = versions(LEAD, ACME, ACME_BANKING, UUID.randomUUID());

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("a soft-deleted case's versions are 404, though the rows survive")
        void aDeletedCaseServesNothing() throws Exception {
            // The rows are kept because past runs pin the version they executed (§3 lesson 1). That
            // is a reason to keep them, not a reason to serve them as a live case's history.
            assertThat(versions(LEAD, ACME, ACME_BANKING, DELETED_CASE).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(storedVersionCount(ACME, DELETED_CASE)).isEqualTo(2);
        }

        @Test
        @DisplayName("the 404s are the gate and not a broken route")
        void theRouteItselfWorks() throws Exception {
            // Without this, every assertion above passes just as well against a path that is not
            // mapped at all.
            assertThat(versions(LEAD, ACME, ACME_BANKING, HISTORIC_CASE).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/cases/" + HISTORIC_CASE
                                    + "/versions"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // Tenancy
    // =====================================================================

    @Nested
    @DisplayName("Tenancy — another client's history is unreachable through either path")
    class Tenancy {

        @Test
        @DisplayName("the other client's case is 404 through this client's project")
        void versionsDoNotCrossCompaniesThroughAGrantedPath() throws Exception {
            MvcResult result = versions(LEAD, ACME, ACME_BANKING, UMBRA_CASE);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("the other client's case is 404 through its own project too")
        void versionsDoNotCrossCompaniesThroughTheOwnersPath() throws Exception {
            assertThat(versions(LEAD, ACME, UMBRA_BANKING, UMBRA_CASE).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the other client's lead reads their own history and nothing of ours")
        void eachClientSeesTheirOwn() throws Exception {
            assertThat(versionNumbers(OUTSIDER, UMBRA, UMBRA_BANKING, UMBRA_CASE))
                    .containsExactly(2, 1);
            assertThat(versions(OUTSIDER, UMBRA, UMBRA_BANKING, HISTORIC_CASE)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("no response of Acme's contains a single character of Umbra's change comments")
        void theOtherClientsTextNeverAppears() throws Exception {
            List<UUID> everyCase = List.of(
                    HISTORIC_CASE, CREATED_CASE, UNVERSIONED_CASE, UNATTRIBUTED_CASE,
                    LOCKED_CASE, UMBRA_CASE, DELETED_CASE);

            for (UUID projectId : List.of(ACME_BANKING, ACME_LOCKED, UMBRA_BANKING)) {
                for (UUID caseId : everyCase) {
                    String payload = versions(LEAD, ACME, projectId, caseId)
                            .getResponse().getContentAsString();
                    assertThat(payload).as("project %s, case %s", projectId, caseId)
                            .doesNotContain(CONFIDENTIAL);
                }
            }
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private MvcResult versions(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId + "/versions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode body(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        MvcResult result = versions(userId, companyId, projectId, caseId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<Integer> versionNumbers(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        List<Integer> numbers = new ArrayList<>();
        body(userId, companyId, projectId, caseId)
                .forEach(row -> numbers.add(row.get("versionNo").asInt()));
        return numbers;
    }

    private List<Boolean> pinnedByVersion(
            UUID userId, UUID companyId, UUID projectId, UUID caseId) throws Exception {
        List<Boolean> pinned = new ArrayList<>();
        body(userId, companyId, projectId, caseId)
                .forEach(row -> pinned.add(row.get("pinned").asBoolean()));
        return pinned;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // helpers — reading the database back, through the APP role under RLS
    // =====================================================================

    private int storedVersionCount(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM test_case_version WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    // =====================================================================
    // helpers — fixture
    // =====================================================================

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
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

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("case-version-endpoint:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void seedContent() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-cv-1", 1, "Payments", "s1");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-cv-2", 2, "Internal", "s2");

            testCase(connection, HISTORIC_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cv-1", 901,
                    "Refund a partially captured payment", 4, false);
            testCase(connection, CREATED_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cv-2", 902,
                    "Created after the write path existed", 1, false);
            testCase(connection, UNVERSIONED_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cv-3", 903,
                    "Never edited, and older than the writer", 1, false);
            testCase(connection, UNATTRIBUTED_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cv-4", 904,
                    "Its authors cannot be named", 2, false);
            testCase(connection, LOCKED_CASE, ACME, ACME_LOCKED, LOCKED_SUITE, "TC-cv-5", 905,
                    "In the project nobody here is granted", 2, false);
            testCase(connection, DELETED_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cv-6", 906,
                    "Removed from the repository", 2, true);

            // Written 3, 2, 4 — physically out of order on purpose. An unsorted query returns
            // insertion order on a fresh table, so a fixture written 2, 3, 4 would let a missing
            // ORDER BY look almost right and a missing DESC look exactly wrong in only one place.
            version(connection, ACME, HISTORIC_CASE, 3, AUTHOR, "Added the partial-capture step",
                    true, T0.plus(2, ChronoUnit.DAYS));
            version(connection, ACME, HISTORIC_CASE, 2, LEAD, null,
                    false, T0.plus(1, ChronoUnit.DAYS));
            version(connection, ACME, HISTORIC_CASE, 4, AUTHOR, "Reworded step 2",
                    false, T0.plus(3, ChronoUnit.DAYS));

            version(connection, ACME, CREATED_CASE, 1, LEAD, "Created", false, T0);

            version(connection, ACME, UNATTRIBUTED_CASE, 1, null, "Imported", false, T0);
            version(connection, ACME, UNATTRIBUTED_CASE, 2, GHOST, "Edited by a departed account",
                    false, T0.plus(1, ChronoUnit.DAYS));

            version(connection, ACME, LOCKED_CASE, 1, LEAD, "Internal tooling note", false, T0);
            version(connection, ACME, LOCKED_CASE, 2, LEAD, "Second internal note", false, T0);

            version(connection, ACME, DELETED_CASE, 1, LEAD, "Before the delete", false, T0);
            version(connection, ACME, DELETED_CASE, 2, LEAD, "Also before the delete", false, T0);
        }

        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // Deliberately keyed like Acme's: keys are per company, so the collision is the design
            // working, and a query leaning on key uniqueness would find two rows.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-cv-1", 1, "Payments", "s1");
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-cv-1", 901,
                    "Refund a partially captured payment", 2, false);
            version(connection, UMBRA, UMBRA_CASE, 1, null, CONFIDENTIAL + " — first", false, T0);
            version(connection, UMBRA, UMBRA_CASE, 2, null, CONFIDENTIAL + " — second", false,
                    T0.plus(1, ChronoUnit.DAYS));
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, String key, long keySeq,
            String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    /**
     * @param currentVersionNo written to {@code test_case.current_version_no} so the case and its
     *                         history agree — a detail page reading v4 beside a list whose newest
     *                         entry is v3 is §5-defect-3's class, two screens of one case disagreeing
     * @param deleted          soft-deleted, with its version rows left intact
     */
    private void testCase(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID suiteId,
            String key, long keySeq, String title, int currentVersionNo, boolean deleted)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title,
                     current_version_no, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setInt(8, currentVersionNo);
            statement.setTimestamp(9, deleted ? Timestamp.from(Instant.now()) : null);
            statement.executeUpdate();
        }
    }

    /**
     * One version row, written through the app role under RLS rather than through
     * {@link CaseVersions} — the writer takes a rendered snapshot and an authenticated caller, and
     * this class needs rows the writer cannot currently produce: a null author, an author naming no
     * account, a pinned row, and a history that starts at v2.
     *
     * @param authorId null writes SQL NULL to {@code created_by}
     */
    private void version(
            Connection connection, UUID companyId, UUID caseId, int versionNo, UUID authorId,
            String changeComment, boolean pinned, Instant createdAt) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, change_comment,
                     pinned, created_at, created_by)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), ?,
                        ?, ?, ?, CAST(? AS uuid))
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, versionNo);
            statement.setString(5, "{\"title\":\"What the case said at v" + versionNo + "\"}");
            statement.setString(6, "hash-" + caseId + "-" + versionNo);
            statement.setString(7, changeComment);
            statement.setBoolean(8, pinned);
            statement.setTimestamp(9, Timestamp.from(createdAt));
            statement.setString(10, authorId == null ? null : authorId.toString());
            statement.executeUpdate();
        }
    }
}
