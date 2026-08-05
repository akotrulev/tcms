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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{projectId}/tags} — the tags in use, which is what both tag controls and
 * §97's plan sections complete from.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Counting through soft-deleted cases.</strong> A deleted case carries {@code smoke}
 *       too, so ignoring {@code deleted_at} answers 3 where the live answer is 2 — and a tag whose
 *       <em>only</em> case was deleted would appear at all, which the contract says is impossible
 *       rather than filtered.</li>
 *   <li><strong>A sibling project's tags leaking in.</strong> {@code case_tag} has a
 *       {@code company_id} and <strong>no {@code project_id}</strong>, so RLS alone bounds this to
 *       the company: without the join to {@code test_case} the endpoint hands over the tag
 *       vocabulary of every project in the caller's instance, and every cross-company test in the
 *       suite still passes. This is the finding the class is built around.</li>
 *   <li><strong>An unstable order.</strong> Two tags tie on count, so an ordering with no second
 *       key returns them in whatever order the database chose that day — a picker that reshuffles
 *       between two loads of one screen.</li>
 *   <li><strong>An empty project treated as an error.</strong> A project nobody has tagged is
 *       {@code 200 []}; the control renders "no tags yet" and the author types the tag by hand.</li>
 *   <li><strong>403 instead of 404</strong> on a project the caller cannot see, which would make
 *       this an oracle for another client's project ids (§6t).</li>
 * </ol>
 *
 * <p>Ids are {@code 6c…} — unique to this class, and hex. Sharing a prefix is handoff §19's defect,
 * which has landed five times: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Tags in use — derived, project-bounded, and ordered so it does not reshuffle")
class TagEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("6c100000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("6c100000-0000-4000-8000-00000000000b");

    /** Granted to ENGINEER. Holds the vocabulary under test. */
    private static final UUID ACME_BANKING = UUID.fromString("6c200000-0000-4000-8000-000000000001");
    /** Same company, NOT granted. Its tags must never appear, nor inflate a count. */
    private static final UUID ACME_MOBILE = UUID.fromString("6c200000-0000-4000-8000-000000000002");
    /** The other client's project. */
    private static final UUID UMBRA_BANKING = UUID.fromString("6c200000-0000-4000-8000-000000000003");
    /** Granted to ENGINEER, deliberately untagged. */
    private static final UUID ACME_EMPTY = UUID.fromString("6c200000-0000-4000-8000-000000000004");

    private static final UUID ENGINEER = UUID.fromString("6c300000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding no grant at all. */
    private static final UUID NO_GRANT = UUID.fromString("6c300000-0000-4000-8000-000000000002");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("6c300000-0000-4000-8000-000000000003");

    private static final UUID SUITE = UUID.fromString("6c400000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_SUITE = UUID.fromString("6c400000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("6c400000-0000-4000-8000-000000000003");

    private static final UUID CASE_ONE = UUID.fromString("6c600000-0000-4000-8000-000000000001");
    private static final UUID CASE_TWO = UUID.fromString("6c600000-0000-4000-8000-000000000002");
    private static final UUID CASE_THREE = UUID.fromString("6c600000-0000-4000-8000-000000000003");
    /** Soft-deleted. Carries `smoke` (so a careless count reads 3) and is `retired`'s only case. */
    private static final UUID CASE_DELETED = UUID.fromString("6c600000-0000-4000-8000-000000000004");
    private static final UUID MOBILE_CASE = UUID.fromString("6c600000-0000-4000-8000-000000000011");
    private static final UUID UMBRA_CASE = UUID.fromString("6c600000-0000-4000-8000-000000000021");

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
            company(ACME, "tag-acme", "Acme", now);
            company(UMBRA, "tag-umbra", "Umbra", now);

            activeUser(ENGINEER, "engineer@tag-acme.test", now);
            activeUser(NO_GRANT, "nogrant@tag-acme.test", now);
            activeUser(OUTSIDER, "engineer@tag-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "6COLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "6CMOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "6CNEW", "Just Provisioned", now);

            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("tag-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("tag-eng-e", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_EMPTY, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "6CAUO", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("tag-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedTags();
    }

    @Nested
    @DisplayName("what the list is made of")
    class Membership {

        @Test
        @DisplayName("counts live cases, and a deleted one neither counts nor keeps a tag alive")
        void countsLiveCasesOnly() throws Exception {
            List<JsonNode> tags = tagsOf(ENGINEER, ACME, ACME_BANKING);

            // CASE_DELETED carries `smoke` as well, so a count that ignores deleted_at reads 3.
            assertThat(countOf(tags, "smoke"))
                    .as("a count through the deleted case reads 3 — the response carried %s",
                            summarise(tags))
                    .isEqualTo(2);

            assertThat(namesOf(tags))
                    .as("`retired` is carried ONLY by the deleted case, so it must be absent "
                            + "entirely rather than present with a count of zero — the contract "
                            + "says zero-count rows are impossible rather than filtered")
                    .doesNotContain("retired");
        }

        @Test
        @DisplayName("an untagged project answers 200 with [], not 404")
        void untaggedProjectIsNotAnError() throws Exception {
            assertThat(tagsOf(ENGINEER, ACME, ACME_EMPTY))
                    .as("an author who gets no tags can still type one; a 404 while a picker opens "
                            + "reads as the control being broken")
                    .isEmpty();
        }

        @Test
        @DisplayName("serves the tag and its count and nothing else")
        void servesExactlyTheContractShape() throws Exception {
            List<JsonNode> tags = tagsOf(ENGINEER, ACME, ACME_BANKING);

            assertThat(tags).isNotEmpty();
            for (JsonNode node : tags) {
                List<String> fields = new ArrayList<>();
                node.fieldNames().forEachRemaining(fields::add);
                assertThat(fields).containsExactlyInAnyOrder("tag", "caseCount");
            }
        }
    }

    @Nested
    @DisplayName("the order")
    class Ordering {

        @Test
        @DisplayName("most-used first, then alphabetically — the tie is what makes that observable")
        void ordersByCountThenName() throws Exception {
            List<JsonNode> tags = tagsOf(ENGINEER, ACME, ACME_BANKING);

            // `checkout` and `smoke` both have 2. Without the second sort key their relative order
            // is the database's and can differ between two requests, which is a picker that
            // reshuffles while somebody is reading it.
            assertThat(namesOf(tags))
                    .as("counts are checkout=2, smoke=2, regression=1 — %s", summarise(tags))
                    .containsExactly("checkout", "smoke", "regression");
        }

        @Test
        @DisplayName("the same order twice, which is the half a single request cannot prove")
        void theOrderIsStable() throws Exception {
            assertThat(namesOf(tagsOf(ENGINEER, ACME, ACME_BANKING)))
                    .isEqualTo(namesOf(tagsOf(ENGINEER, ACME, ACME_BANKING)));
        }
    }

    @Nested
    @DisplayName("isolation")
    class Isolation {

        @Test
        @DisplayName("a sibling project's tags never appear, and never inflate a count")
        void siblingProjectIsInvisible() throws Exception {
            List<JsonNode> tags = tagsOf(ENGINEER, ACME, ACME_BANKING);

            // THE finding this class is built around. case_tag has no project_id, so RLS bounds
            // this query to the COMPANY and nothing more. ACME_MOBILE is the same company, is not
            // granted to the caller, and carries both a tag of its own and one of Banking's.
            assertThat(namesOf(tags))
                    .as("without the join to test_case this endpoint serves the tag vocabulary of "
                            + "every project in the instance — %s", summarise(tags))
                    .doesNotContain("mobile-only");
            assertThat(countOf(tags, "checkout"))
                    .as("MOBILE_CASE carries `checkout` too — a missing project predicate shows up "
                            + "here as 3 rather than as a new row, which is the half of the leak "
                            + "that a doesNotContain assertion cannot see")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("another company's tags never appear")
        void otherCompanyIsInvisible() throws Exception {
            assertThat(namesOf(tagsOf(ENGINEER, ACME, ACME_BANKING))).doesNotContain("umbra-only");
        }

        @Test
        @DisplayName("404 — not 403 — for a project the caller cannot see")
        void unreachableProjectIs404() throws Exception {
            assertThat(statusOf(ENGINEER, ACME, ACME_MOBILE))
                    .as("a 403 confirms the id names something real")
                    .isEqualTo(404);
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING)).isEqualTo(404);
            assertThat(statusOf(OUTSIDER, UMBRA, ACME_BANKING)).isEqualTo(404);
            assertThat(statusOf(ENGINEER, ACME, UUID.randomUUID())).isEqualTo(404);
        }
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    private List<JsonNode> tagsOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/tags")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("expected the tag list: %s", response.getContentAsString())
                .isEqualTo(200);

        List<JsonNode> rows = new ArrayList<>();
        json.readTree(response.getContentAsString()).forEach(rows::add);
        return rows;
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/tags")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static List<String> namesOf(List<JsonNode> rows) {
        return rows.stream().map(node -> node.get("tag").asText()).toList();
    }

    private static long countOf(List<JsonNode> rows, String tag) {
        return rows.stream()
                .filter(node -> node.get("tag").asText().equals(tag))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no tag " + tag + " — carried " + summarise(rows)))
                .get("caseCount")
                .asLong();
    }

    private static String summarise(List<JsonNode> rows) {
        return rows.stream()
                .map(node -> node.get("tag").asText() + "=" + node.get("caseCount").asLong())
                .toList()
                .toString();
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

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("tag-endpoint:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * The cases and their tags, written through the ordinary app role — the same policed path
     * production reads through, so a fixture that could not be written under RLS is one that lies
     * about what the endpoint will see.
     */
    private void seedTags() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, SUITE, ACME, ACME_BANKING, "6COLB-S1", 1);
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, "6CMOB-S1", 2);

            testCase(connection, CASE_ONE, ACME, ACME_BANKING, SUITE, "6COLB-1", 1, false);
            tag(connection, ACME, CASE_ONE, "smoke");
            tag(connection, ACME, CASE_ONE, "checkout");

            testCase(connection, CASE_TWO, ACME, ACME_BANKING, SUITE, "6COLB-2", 2, false);
            tag(connection, ACME, CASE_TWO, "smoke");
            tag(connection, ACME, CASE_TWO, "checkout");

            testCase(connection, CASE_THREE, ACME, ACME_BANKING, SUITE, "6COLB-3", 3, false);
            tag(connection, ACME, CASE_THREE, "regression");

            // Deleted: carries `smoke` (a careless count reads 3) and is `retired`'s only case.
            testCase(connection, CASE_DELETED, ACME, ACME_BANKING, SUITE, "6COLB-4", 4, true);
            tag(connection, ACME, CASE_DELETED, "smoke");
            tag(connection, ACME, CASE_DELETED, "retired");

            // The sibling project — same company, one shared tag and one of its own.
            testCase(connection, MOBILE_CASE, ACME, ACME_MOBILE, MOBILE_SUITE, "6CMOB-1", 1, false);
            tag(connection, ACME, MOBILE_CASE, "checkout");
            tag(connection, ACME, MOBILE_CASE, "mobile-only");
        }

        try (Connection connection = appConnection(UMBRA)) {
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "6CAUO-S1", 1);
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "6CAUO-1", 1, false);
            tag(connection, UMBRA, UMBRA_CASE, "umbra-only");
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, name, path, position)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, ?, ?,
                        CAST(? AS ltree), 0)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, "Suite " + key);
            statement.setString(7, "n" + id.toString().replace("-", ""));
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, current_version_no,
                     lock_version, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', 1, 0,
                        CAST(? AS timestamptz), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, "2026-01-01T00:00:00Z");
            statement.setString(9, deleted ? "2026-01-02T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void tag(Connection connection, UUID companyId, UUID caseId, String tag)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }
}
