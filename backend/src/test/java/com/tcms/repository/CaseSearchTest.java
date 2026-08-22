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
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /cases/search} — the one case read that spans projects (§249).
 *
 * <h2>The defect this class exists to catch, and why the obvious test misses it</h2>
 *
 * <p><strong>RLS does not help here.</strong> The policy predicate is the company, and every project
 * in this fixture's Acme is in the caller's company — so the database serves all seven matching rows
 * to the same session, and the narrowing to the two the caller was granted is entirely the
 * application's. That is the opposite of every other case read in this product, where the project
 * bound is a documented survivor mutation ({@code CaseSpecifications}).
 *
 * <p><strong>A test written about the PAGE passes straight through the defect.</strong> Filter in
 * Java after an unrestricted read and the page is exactly right at {@code size=200}: five rows are
 * dropped and the caller sees the two that are theirs. What is wrong is the {@code total} — 7, a
 * name-free existence claim about four projects' worth of the client's portfolio — and the paging,
 * which is computed over rows the caller cannot reach. So the first assertion in this class is on
 * the count, and the second is on a page too small to witness the difference.
 *
 * <p>The fixture is arranged so that every wrong answer is a DIFFERENT NUMBER: 2 is correct, 7 is
 * the company-wide leak, 8 counts the soft-deleted row, 3 is a per-project loop that forgot the
 * grant on one arm. And {@code TC-1} — the row that sorts FIRST of all seven — is in a project the
 * caller was not granted, so a post-filter at {@code size=1} returns an EMPTY page beside a total
 * of 7.
 *
 * <h2>Every case here is FILED IN A SUITE, and that is load-bearing</h2>
 *
 * <p>The cross-project read applies no live-tree bound: there is no one project whose suites could
 * be enumerated. {@code CaseScope} therefore carries an explicit "not bound by the tree" state, and
 * the state it must not be confused with is {@code liveSuiteIds = []} with {@code includeUnfiled},
 * which {@code CaseSpecifications.suiteScope} reads as {@code suite_id IS NULL} — <em>unfiled
 * only</em>. A fixture whose cases were unfiled would pass under that confusion while the product
 * returned nothing for every real company. One case ({@code TC-8}) is deliberately unfiled, so both
 * halves are covered rather than only the one this class happens to prefer.
 *
 * <p>{@code TC-9} sits in a SOFT-DELETED suite and is findable here, which it is not on that
 * project's own grid. That divergence is Q4 in the plan, decided as "ship it and write it down" —
 * so it is asserted rather than merely documented, on a word of its own ({@code rebate}) so it
 * cannot disturb the {@code refund} arithmetic above.
 *
 * <p>Company and user ids are unique to this class ({@code 4d…}), and so are the SLUGS: {@code
 * company} is not under RLS and {@code uq_company_slug} spans the instance, so a slug is a second
 * global namespace the UUID prefix assignment does not cover — §247 lost 32 unrelated tests to
 * exactly that, and this class was written after the repair.
 */
@AutoConfigureMockMvc
@DisplayName("Cross-project case search — narrowed in the query, and counted over what was narrowed")
class CaseSearchTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("4d000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("4d000000-0000-4000-8000-00000000000b");

    /** GRANTED to SEEKER. Holds exactly TWO cases matching "refund". */
    private static final UUID GRANTED = UUID.fromString("4d001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted. Holds FOUR matching "refund" — deliberately more than GRANTED. */
    private static final UUID UNGRANTED = UUID.fromString("4d001000-0000-4000-8000-000000000002");
    /** Same company, NOT granted, and holds ONE matching case whose key sorts FIRST of all seven. */
    private static final UUID SHADOW = UUID.fromString("4d001000-0000-4000-8000-000000000003");
    /** The other client, with an identically-titled case and an identically-shaped key. */
    private static final UUID UMBRA_ONE = UUID.fromString("4d002000-0000-4000-8000-000000000001");

    /** Test Lead on GRANTED alone. The persona every assertion is written from. */
    private static final UUID SEEKER = UUID.fromString("4d003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding NOTHING. ERD §0.1's third persona. */
    private static final UUID NO_GRANT = UUID.fromString("4d003000-0000-4000-8000-000000000002");
    /** Company Admin — reaches all three Acme projects through a COMPANY-scope grant. */
    private static final UUID ADMIN = UUID.fromString("4d003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. Must never see an Acme row, nor an Acme count. */
    private static final UUID OUTSIDER = UUID.fromString("4d003000-0000-4000-8000-000000000004");

    private static final UUID GRANTED_SUITE = UUID.fromString("4d004000-0000-4000-8000-000000000001");
    private static final UUID UNGRANTED_SUITE = UUID.fromString("4d004000-0000-4000-8000-000000000002");
    private static final UUID SHADOW_SUITE = UUID.fromString("4d004000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_SUITE = UUID.fromString("4d004000-0000-4000-8000-000000000004");
    /** Soft-deleted, and holding a LIVE case. The Q4 divergence is asserted through it. */
    private static final UUID RETIRED_SUITE = UUID.fromString("4d004000-0000-4000-8000-000000000005");

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
            company(ACME, "csearch-acme", "Acme", now);
            company(UMBRA, "csearch-umbra", "Umbra", now);

            activeUser(SEEKER, "seeker@csearch-acme.test", "S. Seeker", now);
            activeUser(NO_GRANT, "nogrant@csearch-acme.test", "N. Grant", now);
            activeUser(ADMIN, "admin@csearch-acme.test", "A. Admin", now);
            activeUser(OUTSIDER, "out@csearch-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(GRANTED, ACME, "CSGRA", "Granted Product", now);
            seedProject(UNGRANTED, ACME, "CSUNG", "Ungranted Product", now);
            seedProject(SHADOW, ACME, "CSSHA", "Shadow Product", now);

            member(ACME, SEEKER, "S. Seeker (Acme)", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("cs-seeker", ACME, Role.TEST_LEAD_ID, SEEKER, GRANTED, now);
            grantOnCompany("cs-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_ONE, UMBRA, "CSUMB", "Umbra Product", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("cs-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_ONE, now);
        }));

        seedTrees();
        seedCases();
    }

    // =====================================================================
    // The count — the assertion the whole area exists for
    // =====================================================================

    @Test
    @DisplayName("the total counts only the granted projects, not the company's matching rows")
    void theTotalIsNarrowedToo() throws Exception {
        JsonNode body = bodyAs(SEEKER, ACME, "q=refund&size=200");

        assertThat(body.get("total").asLong())
                .as("""
                        Seven cases in Acme match 'refund' and the caller holds a grant on the two \
                        in GRANTED. A total of 7 means the count was computed before the narrowing \
                        — the page hides those rows and the number discloses that they exist, \
                        which is the leak §6t and AdminReach.visibleProjects both refuse. A total \
                        of 8 additionally counted the soft-deleted row.""")
                .isEqualTo(2L);

        assertThat(keysOf(body))
                .as("and the page agrees with the count it was served beside")
                .containsExactly("TC-2", "TC-5");
    }

    @Test
    @DisplayName("a page too small to witness the filter still reports the narrowed total")
    void thePageCannotHideTheCount() throws Exception {
        JsonNode body = bodyAs(SEEKER, ACME, "q=refund&size=1&sort=keySeq,asc");

        assertThat(body.get("total").asLong())
                .as("this is the same claim as theTotalIsNarrowedToo, made where the page carries "
                        + "no evidence either way — one row proves nothing about six others")
                .isEqualTo(2L);

        assertThat(keysOf(body))
                .as("""
                        TC-1 sorts first of all seven matches and lives in SHADOW, which this \
                        caller was NOT granted. If the narrowing runs in Java after the read, the \
                        database serves TC-1 as the single row of page 0, Java drops it, and the \
                        response is an EMPTY page beside a total promising rows. Narrowed inside \
                        the query, page 0 is TC-2.""")
                .containsExactly("TC-2");
    }

    @Test
    @DisplayName("RLS is not what saves this endpoint — the ungranted rows are in the caller's own company")
    void rlsIsNotTheBoundary() throws Exception {
        // Ground truth, taken as the container superuser: the rows the caller must not receive are
        // in the caller's company and are therefore RLS-VISIBLE to the caller's own session. If
        // this assertion ever fails, the fixture stopped testing what the class claims to test.
        try (Connection connection = superuserConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM test_case WHERE company_id = CAST(? AS uuid) "
                             + "AND deleted_at IS NULL AND lower(title) LIKE '%refund%'")) {
            statement.setString(1, ACME.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1))
                        .as("seven matching rows in ONE company — five of them in projects the "
                                + "caller holds no grant on. RLS refuses none of them.")
                        .isEqualTo(7L);
            }
        }

        assertThat(bodyAs(SEEKER, ACME, "q=refund&size=200").get("total").asLong())
                .as("and the application, not the database, is what reduces seven to two")
                .isEqualTo(2L);
    }

    // =====================================================================
    // The personas
    // =====================================================================

    @Test
    @DisplayName("a member holding no grants sees nothing and is not refused")
    void theGrantlessMemberSeesNothingAndIsNotRefused() throws Exception {
        JsonNode body = bodyAs(NO_GRANT, ACME, "q=refund");

        assertThat(statusOf(NO_GRANT, ACME, "q=refund"))
                .as("ERD §0.1's third persona. A 403 would make 'you hold no grants' "
                        + "distinguishable from 'nothing matched', which is a fact about the "
                        + "company's portfolio (§6t); a 404 would claim something failed to resolve")
                .isEqualTo(200);
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("items")).isEmpty();
    }

    @Test
    @DisplayName("a Company Admin sees every project, including ones with no per-project grant row")
    void aCompanyAdminSeesEveryProject() throws Exception {
        JsonNode body = bodyAs(ADMIN, ACME, "q=refund&size=200&sort=keySeq,asc");

        assertThat(body.get("total").asLong())
                .as("proves the narrowing is a GRANT question rather than a hard-coded single "
                        + "project, and that a COMPANY-scope grant reaches projects with no row")
                .isEqualTo(7L);
        assertThat(keysOf(body))
                .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-6", "TC-7");
    }

    @Test
    @DisplayName("another client is invisible, and only ids can be doing the scoping")
    void anotherClientIsInvisible() throws Exception {
        JsonNode body = bodyAs(OUTSIDER, UMBRA, "q=refund&size=200");

        assertThat(body.get("total").asLong())
                .as("Umbra's TC-1 carries the same title AND the same display key as Acme's, so a "
                        + "scoping that matched on either would answer 2 here")
                .isEqualTo(1L);
        assertThat(body.get("items").get(0).get("projectId").asText())
                .isEqualTo(UMBRA_ONE.toString());
    }

    // =====================================================================
    // The projectId parameter — narrows within, never widens
    // =====================================================================

    @Test
    @DisplayName("naming an ungranted project matches nothing rather than widening or refusing")
    void theProjectFilterNarrowsAndNeverWidens() throws Exception {
        JsonNode body = bodyAs(SEEKER, ACME, "q=refund&projectId=" + UNGRANTED);

        assertThat(statusOf(SEEKER, ACME, "q=refund&projectId=" + UNGRANTED))
                .as("no 400 and no 404: an ungranted project and a non-existent one must be "
                        + "indistinguishable (§6t)")
                .isEqualTo(200);
        assertThat(body.get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("the project filter intersects the visible set")
    void theProjectFilterIntersects() throws Exception {
        assertThat(bodyAs(ADMIN, ACME, "q=refund&projectId=" + GRANTED).get("total").asLong())
                .as("the parameter narrows within what the caller can see")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a project id that names nothing at all is also just an empty result")
    void anUnknownProjectIdMatchesNothing() throws Exception {
        String absent = "4d00ffff-0000-4000-8000-00000000ffff";

        assertThat(bodyAs(ADMIN, ACME, "q=refund&projectId=" + absent).get("total").asLong())
                .as("the same answer as an ungranted one, which is the point of §6t's rule")
                .isZero();
    }

    // =====================================================================
    // The key short-circuit — gated like everything else
    // =====================================================================

    @Test
    @DisplayName("a case key short-circuits across projects, and the lookup is gated too")
    void aKeyShortCircuitsAcrossProjects() throws Exception {
        JsonNode found = bodyAs(ADMIN, ACME, "q=TC-4");

        assertThat(found.get("total").asLong()).isEqualTo(1L);
        assertThat(keysOf(found)).containsExactly("TC-4");
        assertThat(found.get("items").get(0).get("projectId").asText())
                .as("keys are allocated from a COMPANY counter, so TC-4 names one case in the "
                        + "whole company and the caller does not know which project holds it — "
                        + "which is the situation this endpoint exists for")
                .isEqualTo(UNGRANTED.toString());

        assertThat(bodyAs(SEEKER, ACME, "q=TC-4").get("total").asLong())
                .as("""
                        THE ARM SOMEBODY WILL FORGET, because a key lookup looks like an id lookup \
                        rather than a search. TC-4 is in a project this caller was not granted, so \
                        it matches nothing — not a 404, and no hint that the key exists.""")
                .isZero();
    }

    // =====================================================================
    // What the search does and does not include
    // =====================================================================

    @Test
    @DisplayName("a soft-deleted case is gone, and is not counted")
    void theSoftDeletedCaseIsGone() throws Exception {
        JsonNode body = bodyAs(SEEKER, ACME, "q=refund&size=200");

        assertThat(titlesOf(body)).doesNotContain("Refund (deleted)");
        assertThat(body.get("total").asLong())
                .as("8 would mean deletedAt IS NULL was lost")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("an unfiled case is findable — the search applies no suite bound at all")
    void anUnfiledCaseIsFound() throws Exception {
        // TC-8 is filed in no suite. Every other case here IS filed, which is what makes the pair
        // of assertions meaningful: a scope built as `liveSuiteIds = [] + includeUnfiled` reads as
        // `suite_id IS NULL`, so it would answer TC-8 alone and drop the seven filed rows.
        JsonNode body = bodyAs(ADMIN, ACME, "q=unfiled&size=200");

        assertThat(keysOf(body)).containsExactly("TC-8");
        assertThat(keysOf(bodyAs(ADMIN, ACME, "q=refund&size=200")))
                .as("and the filed rows are still there — the bound is absent, not inverted")
                .hasSize(7);
    }

    @Test
    @DisplayName("a case in a soft-deleted suite IS findable here, unlike on that project's grid")
    void aCaseInARetiredFolderIsStillFindable() throws Exception {
        // Q4, decided as "ship it and write it down": a search is for finding a case you know
        // exists, and a case hidden behind a deleted folder is precisely the one somebody cannot
        // find. On GET /projects/{id}/cases this row is absent (CaseListEndpointTest asserts that
        // for its own equivalent); here it is present, and the divergence is deliberate.
        JsonNode body = bodyAs(SEEKER, ACME, "q=rebate&size=200");

        assertThat(keysOf(body)).containsExactly("TC-9");
    }

    // =====================================================================
    // Every hit names its project
    // =====================================================================

    @Test
    @DisplayName("every hit names its project, and the three fields agree with GET /projects")
    void everyHitNamesItsProject() throws Exception {
        JsonNode body = bodyAs(ADMIN, ACME, "q=refund&size=200");

        for (JsonNode hit : body.get("items")) {
            assertThat(hit.get("projectId").isNull()).isFalse();
            assertThat(hit.get("projectKey").isNull()).isFalse();
            assertThat(hit.get("projectName").isNull()).isFalse();
        }

        JsonNode inGranted = byKey(body, "TC-2");
        assertThat(inGranted.get("projectKey").asText()).isEqualTo("CSGRA");
        assertThat(inGranted.get("projectName").asText())
                .as("the name a reader recognises, and it comes from the GATED project rather "
                        + "than from a second lookup")
                .isEqualTo("Granted Product");
    }

    @Test
    @DisplayName("every field the contract marks required is present on every hit")
    void requiredFieldsArePresent() throws Exception {
        JsonNode hit = byKey(bodyAs(ADMIN, ACME, "q=refund&size=200"), "TC-2");

        assertThat(List.of("id", "key", "keySeq", "title", "projectId", "projectKey", "projectName",
                "lifecycleStatus", "automationStatus", "priority", "updatedAt"))
                .allSatisfy(field -> assertThat(hit.has(field))
                        .as("CaseSearchHit.%s is required by openapi.yaml", field)
                        .isTrue());

        assertThat(hit.has("companyId")).as("tenancy never goes on the wire").isFalse();
        assertThat(hit.has("suiteId")).as("a suite is one project's folder").isFalse();
    }

    // =====================================================================
    // Paging, and the total order that makes it safe
    // =====================================================================

    @Test
    @DisplayName("paging is stable across projects — nothing repeats and nothing is missing")
    void pagingIsStableAcrossProjects() throws Exception {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(keysOf(bodyAs(ADMIN, ACME, "q=refund&size=3&page=" + page)));
        }

        assertThat(seen)
                .as("""
                        The tie-break is keySeq, which is allocated from a COMPANY counter and is \
                        therefore a total order ACROSS projects — but test_case_key_seq_idx is \
                        (company_id, key_seq) and NOT unique, so the allocation guarantees the \
                        order and the schema does not. An unasserted guarantee is a comment.""")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-6", "TC-7");
    }

    // =====================================================================
    // The refusals
    // =====================================================================

    @Test
    @DisplayName("an unbounded search is refused, and the message names the three parameters")
    void anUnboundedSearchIsRefused() throws Exception {
        String body = rawBodyOf(SEEKER, ACME, "size=200");

        assertThat(statusOf(SEEKER, ACME, "size=200"))
                .as("400 and not 404: nothing failed to resolve, and the caller may make any of "
                        + "the requests the message names — /work-items' refusal, verbatim")
                .isEqualTo(400);
        assertThat(body).contains("q").contains("ownerId").contains("tag");
    }

    @Test
    @DisplayName("a one-character query is refused rather than clamped")
    void aOneCharacterQueryIsRefused() throws Exception {
        assertThat(statusOf(SEEKER, ACME, "q=r"))
                .as("LIKE '%r%' over a whole company is the unbounded read wearing a filter")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("an enum filter alone is not a bound")
    void anEnumFilterAloneIsNotABound() throws Exception {
        assertThat(statusOf(SEEKER, ACME, "lifecycleStatus=APPROVED&size=200"))
                .as("'every APPROVED case in every project I can see' is still company-sized")
                .isEqualTo(400);
    }

    // =====================================================================
    // The other two bounds
    // =====================================================================

    @Test
    @DisplayName("the owner filter is cross-project")
    void theOwnerFilterIsCrossProject() throws Exception {
        JsonNode body = bodyAs(ADMIN, ACME, "ownerId=" + SEEKER + "&size=200");

        assertThat(body.get("total").asLong()).isEqualTo(2L);
        assertThat(projectIdsOf(body))
                .as("one case in GRANTED and one in UNGRANTED — 'everything Maria owns, "
                        + "everywhere' is the second-most-asked cross-project question")
                .containsExactlyInAnyOrder(GRANTED.toString(), UNGRANTED.toString());
    }

    @Test
    @DisplayName("the owner filter is narrowed by the grant like everything else")
    void theOwnerFilterIsGatedToo() throws Exception {
        assertThat(bodyAs(SEEKER, ACME, "ownerId=" + SEEKER + "&size=200").get("total").asLong())
                .as("SEEKER owns one case in GRANTED and one in UNGRANTED; only the first is "
                        + "theirs to see, and the count must say 1 rather than 2")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a request with no session is refused")
    void anonymousIsRefused() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders.get(uri("q=refund")))
                .andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    /**
     * The request URI, built as a {@link URI} rather than a template string.
     *
     * <p>{@code MockMvcRequestBuilders.get(String)} treats its argument as a URI <em>template</em>
     * and re-encodes it, which would change what the endpoint receives for any query carrying a
     * percent. {@code CaseListEndpointTest} records the same reason.
     */
    private static URI uri(String query) {
        return URI.create("/api/v1/cases/search" + (query.isEmpty() ? "" : "?" + query));
    }

    private String rawBodyOf(UUID userId, UUID companyId, String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get(uri(query))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode bodyAs(UUID userId, UUID companyId, String query) throws Exception {
        String body = rawBodyOf(userId, companyId, query);
        JsonNode parsed = json.readTree(body);
        assertThat(parsed.isObject()).as("the endpoint must answer a JSON object: %s", body).isTrue();
        return parsed;
    }

    private int statusOf(UUID userId, UUID companyId, String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get(uri(query))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private static List<String> keysOf(JsonNode body) {
        List<String> keys = new ArrayList<>();
        body.get("items").forEach(item -> keys.add(item.get("key").asText()));
        return keys;
    }

    private static List<String> titlesOf(JsonNode body) {
        List<String> titles = new ArrayList<>();
        body.get("items").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private static List<String> projectIdsOf(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("items").forEach(item -> ids.add(item.get("projectId").asText()));
        return ids;
    }

    private static JsonNode byKey(JsonNode body, String key) {
        for (JsonNode item : body.get("items")) {
            if (item.get("key").asText().equals(key)) {
                return item;
            }
        }
        throw new AssertionError("no hit with key " + key);
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

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-search:" + marker).getBytes());
    }

    private static UUID caseId(String marker) {
        return UUID.nameUUIDFromBytes(("case-search-case:" + marker).getBytes());
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, GRANTED_SUITE, ACME, GRANTED, "S-1", 1, "Refunds", false);
            suite(connection, UNGRANTED_SUITE, ACME, UNGRANTED, "S-2", 2, "Refunds", false);
            suite(connection, SHADOW_SUITE, ACME, SHADOW, "S-3", 3, "Refunds", false);
            suite(connection, RETIRED_SUITE, ACME, GRANTED, "S-4", 4, "Retired", true);
        }
        try (Connection connection = appConnection(UMBRA)) {
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_ONE, "S-1", 1, "Refunds", false);
        }
    }

    /**
     * Seven Acme cases matching {@code refund}, keyed from ONE company counter so the keys
     * interleave across projects — which is what makes the ordering assertion meaningful.
     *
     * <p>Two granted, five ungranted, one soft-deleted: every wrong answer is a different number.
     */
    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // Sorts FIRST of all seven, and lives in a project the SEEKER was not granted.
            testCase(connection, caseId("tc1"), ACME, SHADOW, SHADOW_SUITE, "TC-1", 1,
                    "Refund the whole order", null, false);
            testCase(connection, caseId("tc2"), ACME, GRANTED, GRANTED_SUITE, "TC-2", 2,
                    "Partial refund of a shipped order", SEEKER, false);
            testCase(connection, caseId("tc3"), ACME, UNGRANTED, UNGRANTED_SUITE, "TC-3", 3,
                    "Refund a cancelled order", SEEKER, false);
            testCase(connection, caseId("tc4"), ACME, UNGRANTED, UNGRANTED_SUITE, "TC-4", 4,
                    "Refund to store credit", null, false);
            testCase(connection, caseId("tc5"), ACME, GRANTED, GRANTED_SUITE, "TC-5", 5,
                    "Refund reversal", null, false);
            testCase(connection, caseId("tc6"), ACME, UNGRANTED, UNGRANTED_SUITE, "TC-6", 6,
                    "Refund audit trail", null, false);
            testCase(connection, caseId("tc7"), ACME, UNGRANTED, UNGRANTED_SUITE, "TC-7", 7,
                    "Bulk refund", null, false);

            // Filed in NO suite — the other half of "the search applies no suite bound".
            testCase(connection, caseId("tc8"), ACME, GRANTED, null, "TC-8", 8,
                    "An unfiled draft", null, false);
            // LIVE, inside a SOFT-DELETED suite. Its own word, so it cannot disturb the counts.
            testCase(connection, caseId("tc9"), ACME, GRANTED, RETIRED_SUITE, "TC-9", 9,
                    "A rebate from a retired folder", null, false);
            // Soft-deleted. 8 rather than 2 is the answer that counted it.
            testCase(connection, caseId("tc10"), ACME, GRANTED, GRANTED_SUITE, "TC-10", 10,
                    "Refund (deleted)", null, true);
            // Matches nothing, so a dropped free-text filter shows up as an extra row.
            testCase(connection, caseId("tc11"), ACME, GRANTED, GRANTED_SUITE, "TC-11", 11,
                    "Shipping", null, false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            // The same title AND the same display key as Acme's TC-1, in another company.
            testCase(connection, caseId("umb1"), UMBRA, UMBRA_ONE, UMBRA_SUITE, "TC-1", 1,
                    "Refund the whole order", null, false);
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, String key, long keySeq,
            String name, boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED',
                        CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void testCase(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID suiteId,
            String key, long keySeq, String title, UUID ownerId, boolean deleted)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description,
                     step_format, priority, lifecycle_status, automation_status,
                     not_automatable_reason, owner_id, created_by, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, NULL, 'STRUCTURED', 'MEDIUM', 'APPROVED', 'MANUAL', NULL,
                        CAST(? AS uuid), CAST(? AS uuid), CAST(? AS timestamptz),
                        CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId == null ? null : suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, ownerId == null ? null : ownerId.toString());
            statement.setString(9, null);
            statement.setString(10, "2026-01-01T00:00:00Z");
            statement.setString(11, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }
}
