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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{projectId}/step-suggestions} — the corpus the step editor completes from.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Counting step rows instead of distinct cases.</strong> The fixture gives one case
 *       the same triple <em>three times</em>, so a row count answers 5 where the distinct-case
 *       answer is 3. Both numbers are plausible on sight, which is why the fixture makes them
 *       different rather than trusting a reading of the SQL.</li>
 *   <li><strong>Counting through soft-deleted cases.</strong> A deleted case also uses the top
 *       triple, so ignoring {@code deleted_at} answers 4. Cases are soft-deleted because executions
 *       reference them, so this is the ordinary state of a real project, not an edge case.</li>
 *   <li><strong>Grouping on the action alone.</strong> Two steps share an action and differ in
 *       expected result. Grouping on {@code action} collapses them and the endpoint then hands the
 *       author one of the two expected results arbitrarily — a wrong value silently filled into a
 *       field they will not re-check.</li>
 *   <li><strong>A sibling project's steps leaking in.</strong> The same company owns a project the
 *       caller holds no grant on, carrying an identical action. Only {@code project_id} separates
 *       them, so an endpoint gated on the company alone passes every cross-company test in the
 *       suite and still leaks.</li>
 *   <li><strong>An id served alongside the text.</strong> A suggestion is text to copy, and the
 *       absence of an id is what keeps it that way. If one ever appears, a consumer will link to it
 *       and the copy-not-reference decision is silently reversed.</li>
 *   <li><strong>Gherkin steps excluded.</strong> One table serves both formats; a {@code keyword}
 *       predicate nobody asked for would hide the corpus of any project that writes Gherkin.</li>
 * </ol>
 *
 * <p>Company, project and user ids are unique to this class ({@code c4…}). Sharing them with
 * another class is how handoff §19's defect happened, five times: green alone, red in the full
 * suite.
 */
@AutoConfigureMockMvc
@DisplayName("Step suggestions — the corpus, its ranking, and what must never be in it")
class StepSuggestionEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("c4100000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("c4100000-0000-4000-8000-00000000000b");

    /** Granted to ENGINEER. Carries the corpus under test. */
    private static final UUID ACME_BANKING = UUID.fromString("c4200000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to ENGINEER. Its steps must never appear in BANKING's corpus. */
    private static final UUID ACME_MOBILE = UUID.fromString("c4200000-0000-4000-8000-000000000002");
    /** The other client's project, with an identical action. */
    private static final UUID UMBRA_BANKING = UUID.fromString("c4200000-0000-4000-8000-000000000003");
    /** Granted to ENGINEER, deliberately left with no cases at all. */
    private static final UUID ACME_EMPTY = UUID.fromString("c4200000-0000-4000-8000-000000000004");

    private static final UUID ENGINEER = UUID.fromString("c4300000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. */
    private static final UUID NO_GRANT = UUID.fromString("c4300000-0000-4000-8000-000000000002");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("c4300000-0000-4000-8000-000000000003");

    private static final UUID SUITE = UUID.fromString("c4400000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_SUITE = UUID.fromString("c4400000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("c4400000-0000-4000-8000-000000000003");

    // ---- the corpus, as triples ------------------------------------------------------------
    // A: three live cases use it, and ONE of them uses it THREE TIMES. Distinct-case count: 3.
    private static final String A_ACTION = "Log in as admin";
    private static final String A_DATA = "admin@acme.test";
    private static final String A_RESULT = "The dashboard loads";
    // B: A's action with a DIFFERENT expected result. One case. Proves the group key is the triple.
    private static final String B_RESULT = "The audit log opens";
    // C: two live cases, no test data.
    private static final String C_ACTION = "Submit the refund form";
    private static final String C_RESULT = "A confirmation appears";
    // D/E: the `%` metacharacter and the near-miss that makes escaping observable.
    private static final String D_ACTION = "Refund 100% of the payment";
    private static final String E_ACTION = "Refund 1000 EUR in full";
    // F: a Gherkin step — keyword non-null. Must be in the corpus.
    private static final String F_ACTION = "the account is overdrawn";
    // G: used ONLY by the soft-deleted case. Must not appear at all.
    private static final String G_ACTION = "Restore the merchant balance";

    private static final UUID CASE_ONE = UUID.fromString("c4600000-0000-4000-8000-000000000001");
    private static final UUID CASE_TWO = UUID.fromString("c4600000-0000-4000-8000-000000000002");
    private static final UUID CASE_THREE = UUID.fromString("c4600000-0000-4000-8000-000000000003");
    private static final UUID CASE_FOUR = UUID.fromString("c4600000-0000-4000-8000-000000000004");
    /** Soft-deleted. Uses A (so a wrong count reads 4) and is the only user of G. */
    private static final UUID CASE_DELETED = UUID.fromString("c4600000-0000-4000-8000-000000000005");
    private static final UUID MOBILE_CASE = UUID.fromString("c4600000-0000-4000-8000-000000000011");
    private static final UUID UMBRA_CASE = UUID.fromString("c4600000-0000-4000-8000-000000000021");

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
            company(ACME, "stsg-acme", "Acme", now);
            company(UMBRA, "stsg-umbra", "Umbra", now);

            activeUser(ENGINEER, "engineer@stsg-acme.test", now);
            activeUser(NO_GRANT, "nogrant@stsg-acme.test", now);
            activeUser(OUTSIDER, "engineer@stsg-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "C4OLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "C4MOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "C4NEW", "Just Provisioned", now);

            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("stsg-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("stsg-eng-e", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_EMPTY, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "C4AUO", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("stsg-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCorpus();
    }

    @Nested
    @DisplayName("the ranking")
    class Ranking {

        @Test
        @DisplayName("most-used first, counted in distinct LIVE cases and never in step rows")
        void ranksByDistinctLiveCases() throws Exception {
            List<JsonNode> corpus = corpusOf(ENGINEER, ACME, ACME_BANKING);

            // A is used by three live cases. CASE_ONE writes it THREE times, so a row count says
            // 5; CASE_DELETED writes it once more, so ignoring deleted_at says 4. Only the
            // distinct-live-case reading says 3, which is the number this endpoint promises.
            assertThat(useCountOf(corpus, A_ACTION, A_RESULT))
                    .as("A row count would read 5 and a count through the deleted case 4 — the "
                            + "response carried %s", summarise(corpus))
                    .isEqualTo(3);

            assertThat(useCountOf(corpus, C_ACTION, C_RESULT)).isEqualTo(2);
            assertThat(useCountOf(corpus, A_ACTION, B_RESULT)).isEqualTo(1);

            assertThat(actionsOf(corpus).indexOf(A_ACTION))
                    .as("the most-used triple must sort first, or the corpus opens on what somebody "
                            + "wrote yesterday instead of what the project does")
                    .isZero();
        }

        @Test
        @DisplayName("a triple repeated inside ONE case counts that case once")
        void repeatsWithinOneCaseCountOnce() throws Exception {
            // The mutation guard for the assertion above: if CASE_ONE's three copies were ever
            // collapsed in the fixture, `isEqualTo(3)` would pass against a row-counting
            // implementation and prove nothing.
            assertThat(stepRowsWithAction(CASE_ONE, A_ACTION))
                    .as("the fixture must keep writing this triple three times in one case, or the "
                            + "distinct-case assertion stops being able to fail")
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("what the corpus is made of")
    class Membership {

        @Test
        @DisplayName("the same action with a different expected result is a SECOND suggestion")
        void groupsOnTheWholeTriple() throws Exception {
            List<JsonNode> corpus = corpusOf(ENGINEER, ACME, ACME_BANKING);

            List<JsonNode> loginRows = corpus.stream()
                    .filter(node -> node.get("action").asText().equals(A_ACTION))
                    .toList();

            assertThat(loginRows)
                    .as("grouping on the action alone collapses these two and then fills the "
                            + "author's expected-result field with whichever one won — %s",
                            summarise(corpus))
                    .hasSize(2);
            assertThat(loginRows.stream().map(node -> node.get("expectedResult").asText()).toList())
                    .containsExactlyInAnyOrder(A_RESULT, B_RESULT);
        }

        @Test
        @DisplayName("a Gherkin step is in the corpus — one table serves both formats")
        void includesGherkinSteps() throws Exception {
            assertThat(actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING)))
                    .as("a keyword predicate nobody asked for hides the corpus of every project "
                            + "that writes Gherkin")
                    .contains(F_ACTION);
        }

        @Test
        @DisplayName("a step only a soft-deleted case uses is absent entirely")
        void excludesStepsOnlyDeletedCasesUse() throws Exception {
            assertThat(actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING)))
                    .as("retiring a case must stop the endpoint offering its step text")
                    .doesNotContain(G_ACTION);
        }

        @Test
        @DisplayName("no id is served — a suggestion is text to copy, not an entity to reference")
        void servesNoIdentifier() throws Exception {
            List<JsonNode> corpus = corpusOf(ENGINEER, ACME, ACME_BANKING);

            assertThat(corpus).isNotEmpty();
            for (JsonNode node : corpus) {
                List<String> fields = new ArrayList<>();
                node.fieldNames().forEachRemaining(fields::add);
                assertThat(fields)
                        .as("an id here is the first half of a linkage this product decided not to "
                                + "have: a step is copied and edits to it propagate nowhere, unlike "
                                + "a condition. A consumer will use whatever is served.")
                        .containsExactlyInAnyOrder(
                                "action", "testData", "expectedResult", "useCount");
            }
        }

        @Test
        @DisplayName("an empty project answers 200 with [], not 404")
        void emptyProjectIsNotAnError() throws Exception {
            assertThat(corpusOf(ENGINEER, ACME, ACME_EMPTY))
                    .as("an author who gets no suggestions can still write the step by hand; a 404 "
                            + "while they type reads as the editor being broken")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("isolation")
    class Isolation {

        @Test
        @DisplayName("a sibling project's steps never appear, and never inflate a count")
        void siblingProjectIsInvisible() throws Exception {
            List<JsonNode> corpus = corpusOf(ENGINEER, ACME, ACME_BANKING);

            // ACME_MOBILE is the SAME company and writes the same action. Only project_id
            // separates them, so an endpoint gated on the company alone passes every
            // cross-company test in the suite and still hands over a project the caller was
            // never granted.
            assertThat(actionsOf(corpus)).doesNotContain("Open the mobile app");
            assertThat(useCountOf(corpus, A_ACTION, A_RESULT))
                    .as("MOBILE_CASE uses this exact triple too — a missing project predicate "
                            + "shows up here as 4 rather than as a new row")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("another company's steps never appear")
        void otherCompanyIsInvisible() throws Exception {
            assertThat(actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING)))
                    .doesNotContain("Open the Umbra portal");
        }

        @Test
        @DisplayName("404 — not 403 — for a project the caller holds no grant on")
        void ungrantedProjectIs404() throws Exception {
            assertThat(statusOf(ENGINEER, ACME, ACME_MOBILE))
                    .as("a 403 confirms the id names something real, which makes this endpoint an "
                            + "oracle for another client's project ids")
                    .isEqualTo(404);
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING)).isEqualTo(404);
            assertThat(statusOf(OUTSIDER, UMBRA, ACME_BANKING)).isEqualTo(404);
            assertThat(statusOf(ENGINEER, ACME, UUID.randomUUID())).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("the query")
    class Query {

        @Test
        @DisplayName("matches a case-insensitive substring of the action")
        void matchesCaseInsensitiveSubstring() throws Exception {
            assertThat(actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING, "q", "log in")))
                    .as("a typeahead that refuses 'log in' for 'Log in as admin' is one nobody uses")
                    .containsOnly(A_ACTION);
        }

        @Test
        @DisplayName("a literal % searches for the character rather than matching everything")
        void escapesLikeMetacharacters() throws Exception {
            List<String> matched = actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING, "q", "100%"));

            // Without escaping, `100%` becomes the pattern `%100%%` — which still matches
            // E_ACTION ("Refund 1000 EUR"), so the near-miss row is what makes this able to fail.
            assertThat(matched)
                    .as("an unescaped %% turns a keystroke into 'all results', which reads as the "
                            + "search being broken rather than as a character being special")
                    .containsOnly(D_ACTION);
        }

        @Test
        @DisplayName("a blank q is the whole corpus, not an empty search")
        void blankQueryIsEverything() throws Exception {
            assertThat(actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING, "q", "   ")))
                    .containsExactlyElementsOf(actionsOf(corpusOf(ENGINEER, ACME, ACME_BANKING)));
        }
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    private List<JsonNode> corpusOf(UUID userId, UUID companyId, UUID projectId, String... params)
            throws Exception {

        var response = mvc.perform(request(userId, companyId, projectId, params))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("expected the corpus: %s", response.getContentAsString())
                .isEqualTo(200);

        List<JsonNode> rows = new ArrayList<>();
        json.readTree(response.getContentAsString()).forEach(rows::add);
        return rows;
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(request(userId, companyId, projectId))
                .andReturn().getResponse().getStatus();
    }

    /**
     * Binds each parameter as a <em>value</em> rather than appending it to the URL.
     *
     * <p>{@code MockMvc} takes the string it is handed as a URI template and does not
     * percent-decode the query string, so {@code ?q=100%25} would arrive as six literal characters
     * and the escaping test would fail for a reason unrelated to escaping.
     */
    private MockHttpServletRequestBuilder request(
            UUID userId, UUID companyId, UUID projectId, String... params) {

        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
                .get("/api/v1/projects/" + projectId + "/step-suggestions")
                .header("Authorization", "Bearer " + tokenFor(userId, companyId));

        for (int i = 0; i < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static List<String> actionsOf(List<JsonNode> rows) {
        return rows.stream().map(node -> node.get("action").asText()).toList();
    }

    private static long useCountOf(List<JsonNode> rows, String action, String expectedResult) {
        return rows.stream()
                .filter(node -> node.get("action").asText().equals(action)
                        && node.get("expectedResult").asText("").equals(expectedResult))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no suggestion " + action + " / " + expectedResult + " — carried "
                                + summarise(rows)))
                .get("useCount")
                .asLong();
    }

    private static String summarise(List<JsonNode> rows) {
        return rows.stream()
                .map(node -> node.get("action").asText() + "=" + node.get("useCount").asLong())
                .toList()
                .toString();
    }

    /** Ground truth, read as the superuser — what the fixture actually wrote. */
    private int stepRowsWithAction(UUID caseId, String action) throws SQLException {
        try (Connection connection = superuserConnection();
                var statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM test_step
                         WHERE case_id = CAST(? AS uuid) AND action = ?
                        """)) {
            statement.setString(1, caseId.toString());
            statement.setString(2, action);
            try (var rows = statement.executeQuery()) {
                rows.next();
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
        UUID id = UUID.nameUUIDFromBytes(("step-suggestion-endpoint:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * The cases and their steps, written through the ordinary app role.
     *
     * <p>The app role rather than the superuser on purpose: it is the same policed path production
     * reads through, so a fixture that could not be written under RLS is one that lies about what
     * the endpoint will see.
     */
    private void seedCorpus() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, SUITE, ACME, ACME_BANKING, "C4OLB-S1", 1);
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, "C4MOB-S1", 2);

            // A three times in ONE case — so a row count reads 5 where the answer is 3.
            testCase(connection, CASE_ONE, ACME_BANKING, SUITE, "C4OLB-1", 1, false);
            step(connection, 1, CASE_ONE, 0, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 2, CASE_ONE, 1, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 3, CASE_ONE, 2, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 4, CASE_ONE, 3, null, C_ACTION, null, C_RESULT);

            testCase(connection, CASE_TWO, ACME_BANKING, SUITE, "C4OLB-2", 2, false);
            step(connection, 5, CASE_TWO, 0, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 6, CASE_TWO, 1, null, C_ACTION, null, C_RESULT);

            // B — A's action, a different expected result. Proves the group key is the triple.
            testCase(connection, CASE_THREE, ACME_BANKING, SUITE, "C4OLB-3", 3, false);
            step(connection, 7, CASE_THREE, 0, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 8, CASE_THREE, 1, null, A_ACTION, A_DATA, B_RESULT);

            testCase(connection, CASE_FOUR, ACME_BANKING, SUITE, "C4OLB-4", 4, false);
            step(connection, 9, CASE_FOUR, 0, null, D_ACTION, null, null);
            step(connection, 10, CASE_FOUR, 1, null, E_ACTION, null, null);
            step(connection, 11, CASE_FOUR, 2, "GIVEN", F_ACTION, null, null);

            // Soft-deleted: uses A (so a careless count reads 4) and is G's only user.
            testCase(connection, CASE_DELETED, ACME_BANKING, SUITE, "C4OLB-5", 5, true);
            step(connection, 12, CASE_DELETED, 0, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 13, CASE_DELETED, 1, null, G_ACTION, null, null);

            // The sibling project — same company, same triple, plus one action of its own.
            testCase(connection, MOBILE_CASE, ACME_MOBILE, MOBILE_SUITE, "C4MOB-1", 1, false);
            step(connection, 14, MOBILE_CASE, 0, null, A_ACTION, A_DATA, A_RESULT);
            step(connection, 15, MOBILE_CASE, 1, null, "Open the mobile app", null, null);
        }

        try (Connection connection = appConnection(UMBRA)) {
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "C4AUO-S1", 1);
            testCase(connection, UMBRA_CASE, UMBRA_BANKING, UMBRA_SUITE, "C4AUO-1", 1, false);
            step(connection, 21, UMBRA_CASE, 0, UMBRA, null, "Open the Umbra portal", null, null);
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, name, path, position)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, ?, ?,
                        CAST(? AS ltree), ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, "Refunds");
            statement.setString(7, "n" + id.toString().replace("-", ""));
            statement.setInt(8, 0);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID projectId, UUID suiteId, String key,
            long keySeq, boolean deleted) throws SQLException {

        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
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

    private void step(Connection connection, int marker, UUID caseId, int position, String keyword,
            String action, String testData, String expectedResult) throws SQLException {

        step(connection, marker, caseId, position, ACME, keyword, action, testData, expectedResult);
    }

    /**
     * One step. Nullable parameters bound through an explicit {@code CAST}.
     *
     * <p>{@code setObject(i, null)} leaves pgjdbc with no type to infer and fails with "could not
     * determine data type of parameter" — a fixture-only failure that reads like a schema problem.
     */
    private void step(Connection connection, int marker, UUID caseId, int position, UUID companyId,
            String keyword, String action, String testData, String expectedResult)
            throws SQLException {

        UUID id = UUID.fromString(String.format("c4700000-0000-4000-8000-%012d", marker));
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step
                    (id, company_id, case_id, position, keyword, action, test_data, expected_result)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, position);
            statement.setString(5, keyword);
            statement.setString(6, action);
            statement.setString(7, testData);
            statement.setString(8, expectedResult);
            statement.executeUpdate();
        }
    }
}
