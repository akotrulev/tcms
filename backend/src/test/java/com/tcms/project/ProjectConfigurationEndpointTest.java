package com.tcms.project;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{id}/classification} and {@code /statuses} — the project's two vocabularies.
 *
 * <h2>The two failures this class exists to catch, neither of which is dramatic</h2>
 *
 * <ol>
 *   <li><strong>A dimension query filtering {@code project_id = :projectId}.</strong> It returns
 *       the project's empty Feature tree and silently drops Test Type, Test Level and Execution
 *       Phase — which are company-level (ERD §3). The endpoint answers 200 with a short array, the
 *       case editor offers one empty picker, and nothing fails. So the assertions here name the
 *       expected keys rather than counting them.</li>
 *   <li><strong>An endpoint that gates on the company only.</strong> Every cross-company test in
 *       the suite still passes, because the rows genuinely are in the caller's own company — and a
 *       member granted nothing gets the client's whole Feature tree, which names what they are
 *       building. That is persona 3, and it is why the gate is asserted per endpoint even though
 *       the code is now shared with {@code GET /projects/{id}}.</li>
 * </ol>
 *
 * <h2>The fixture is deliberately hostile</h2>
 *
 * <p>Two companies whose vocabularies are seeded from the same function, so <strong>every name
 * matches across the boundary</strong> and only ids can prove scoping. Two projects in one company,
 * so a project-scoped dimension has a same-keyed sibling to be confused with. A retired
 * classification value and a nested Feature node, because neither soft-delete nor the tree has any
 * other coverage. And a project-added status, so {@code kind} cannot pass by being derivable from
 * the name.
 *
 * <p>Company and user ids are unique to this class. Sharing them with another test class is how
 * handoff §19's first defect happened — green alone, red in the full suite, with a symptom that
 * read like a broken privilege split.
 */
@AutoConfigureMockMvc
@DisplayName("Project configuration endpoints — classification and statuses, behind the same gate")
class ProjectConfigurationEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("cf000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("cf000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD. Carries the populated Feature tree and the extra status. */
    private static final UUID ACME_BANKING = UUID.fromString("cf001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to LEAD. Its feature dimension must not leak into BANKING's. */
    private static final UUID ACME_MOBILE = UUID.fromString("cf001000-0000-4000-8000-000000000002");
    /** The other client's project, seeded from the same function so every name collides. */
    private static final UUID UMBRA_BANKING = UUID.fromString("cf002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("cf003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("cf003000-0000-4000-8000-000000000002");
    /** Company Admin in Acme — reaches every project through a grant with no project id. */
    private static final UUID ADMIN = UUID.fromString("cf003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("cf003000-0000-4000-8000-000000000004");

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
            company(ACME, "conf-acme", "Acme", now);
            company(UMBRA, "conf-umbra", "Umbra", now);

            activeUser(LEAD, "lead@conf-acme.test", now);
            activeUser(NO_GRANT, "nogrant@conf-acme.test", now);
            activeUser(ADMIN, "admin@conf-acme.test", now);
            activeUser(OUTSIDER, "engineer@conf-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "CAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("conf-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnCompany("conf-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("conf-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        // The vocabularies come from the V2 seed functions — the only sanctioned writers, and the
        // ones provision-company calls. Hand-inserting the dimensions would test a fixture's idea
        // of the vocabulary rather than the product's.
        seedVocabularies(ACME, List.of(ACME_BANKING, ACME_MOBILE));
        seedVocabularies(UMBRA, List.of(UMBRA_BANKING));

        seedFeatureTree();
        seedExtraStatus();
    }

    // =====================================================================
    // Classification — the union that is easy to get wrong
    // =====================================================================

    @Nested
    @DisplayName("Classification — company-level and project-level dimensions together")
    class Classification {

        @Test
        @DisplayName("all four dimensions come back, not just the project's own")
        void companyLevelDimensionsAreIncluded() throws Exception {
            // The assertion is on the keys, not the count: a query filtering
            // `project_id = :projectId` returns exactly one dimension — the project's empty
            // Feature tree — and answers 200. Counting would have caught it; naming says what
            // was missing, which is the part that reads as a working endpoint.
            assertThat(keysOf(classificationAs(LEAD, ACME, ACME_BANKING)))
                    .containsExactly("test_type", "test_level", "execution_phase", "feature");
        }

        @Test
        @DisplayName("the three company-level ones are locked and the project's Feature is not")
        void lockedReflectsTheScope() throws Exception {
            List<JsonNode> dimensions = classificationAs(LEAD, ACME, ACME_BANKING);

            assertThat(dimensionByKey(dimensions, "test_type").get("locked").asBoolean())
                    .as("locked keeps a client's projects comparable — unlocking it per project "
                            + "would give away the reason these are company-level at all")
                    .isTrue();
            assertThat(dimensionByKey(dimensions, "feature").get("locked").asBoolean())
                    .as("a Feature tree is meant to be edited")
                    .isFalse();
        }

        @Test
        @DisplayName("cardinality and hierarchical are per dimension, not per response")
        void shapeFlagsArePerDimension() throws Exception {
            List<JsonNode> dimensions = classificationAs(LEAD, ACME, ACME_BANKING);

            assertThat(dimensionByKey(dimensions, "test_type").get("cardinality").asText())
                    .isEqualTo("SINGLE");
            assertThat(dimensionByKey(dimensions, "execution_phase").get("cardinality").asText())
                    .as("a test can be both Smoke and Regression — one enum would force a "
                            + "false choice (plan §6d)")
                    .isEqualTo("MULTI");
            assertThat(dimensionByKey(dimensions, "test_type").get("hierarchical").asBoolean())
                    .isFalse();
            assertThat(dimensionByKey(dimensions, "feature").get("hierarchical").asBoolean())
                    .isTrue();
        }

        @Test
        @DisplayName("values come back in seed order, not alphabetically")
        void valuesKeepTheirSeededOrder() throws Exception {
            // position, not name: Functional before Performance is the intended reading order,
            // and ordering by name would put Accessibility first and look plausible.
            JsonNode testType = dimensionByKey(
                    classificationAs(LEAD, ACME, ACME_BANKING), "test_type");

            assertThat(namesOf(testType.get("values")))
                    .containsExactly("Functional", "Performance", "Security", "Usability",
                            "Accessibility", "Compatibility", "Localization", "Recovery");
        }

        @Test
        @DisplayName("a dimension with no values serializes as [], never as null or absent")
        void anEmptyDimensionCarriesAnEmptyArray() throws Exception {
            // A freshly seeded Feature tree is empty, which is the NORMAL state — the contract
            // marks `values` required, so the SPA cannot null-check it, and an absent key here
            // is a crash on the case editor rather than an empty picker.
            JsonNode feature = dimensionByKey(
                    classificationAs(ADMIN, ACME, ACME_MOBILE), "feature");

            assertThat(feature.has("values")).isTrue();
            assertThat(feature.get("values").isArray()).isTrue();
            assertThat(feature.get("values").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a Feature child carries its parentId and its materialized path")
        void theTreeIsReconstructible() throws Exception {
            JsonNode feature = dimensionByKey(
                    classificationAs(LEAD, ACME, ACME_BANKING), "feature");

            JsonNode root = valueByName(feature, "Payments");
            JsonNode child = valueByName(feature, "Refunds");

            assertThat(root.get("parentId").isNull())
                    .as("a root value has no parent, explicitly rather than by omission")
                    .isTrue();
            assertThat(child.get("parentId").asText()).isEqualTo(root.get("id").asText());
            assertThat(child.get("path").asText())
                    .as("selecting a feature must include its descendants by default (§6q), "
                            + "which is what the path is for")
                    .isEqualTo("Payments.Refunds");
            assertThat(root.get("color").asText()).isEqualTo("#334455");
        }

        @Test
        @DisplayName("a retired value is gone from the response, not marked in it")
        void softDeletedValuesAreExcluded() throws Exception {
            // Soft-deleted because past executions were classified under it. Included, it would
            // come back as pickable in the case editor — a retired feature offered as current.
            JsonNode feature = dimensionByKey(
                    classificationAs(LEAD, ACME, ACME_BANKING), "feature");

            assertThat(namesOf(feature.get("values")))
                    .containsExactly("Payments", "Refunds")
                    .doesNotContain("Legacy Onboarding");
        }

        @Test
        @DisplayName("another project's Feature dimension does not appear, though it shares the key")
        void aSiblingProjectsDimensionIsNotIncluded() throws Exception {
            // Both projects own a dimension keyed `feature` in the SAME company, so the company
            // filter admits both and only the project predicate separates them. Asserting on the
            // key would pass with either row; the id is what distinguishes them.
            UUID mobileFeatureId = UUID.fromString(
                    dimensionByKey(classificationAs(ADMIN, ACME, ACME_MOBILE), "feature")
                            .get("id").asText());

            assertThat(idsOf(classificationAs(ADMIN, ACME, ACME_BANKING)))
                    .doesNotContain(mobileFeatureId.toString());
        }

        @Test
        @DisplayName("the other client's identically-named vocabulary is a different set of rows")
        void vocabulariesDoNotCrossCompanies() throws Exception {
            // Both companies were seeded by the same function, so every key and every value name
            // matches. Company-level dimensions are the sharpest case in the whole schema for
            // this: `project_id IS NULL` matches rows in EVERY company, so the project predicate
            // scopes nothing here and company_id is the only thing between the two clients.
            List<String> acme = idsOf(classificationAs(LEAD, ACME, ACME_BANKING));
            List<String> umbra = idsOf(classificationAs(OUTSIDER, UMBRA, UMBRA_BANKING));

            assertThat(keysOf(classificationAs(OUTSIDER, UMBRA, UMBRA_BANKING)))
                    .as("the fixture is only hostile if the other side really is populated")
                    .containsExactly("test_type", "test_level", "execution_phase", "feature");
            assertThat(acme).isNotEmpty().doesNotContainAnyElementsOf(umbra);
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode dimension = dimensionByKey(
                    classificationAs(LEAD, ACME, ACME_BANKING), "feature");

            assertThat(List.of("id", "key", "name", "cardinality", "hierarchical", "locked", "values"))
                    .allSatisfy(field -> assertThat(dimension.has(field))
                            .as("ClassificationDimension.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(List.of("id", "name", "path"))
                    .allSatisfy(field -> assertThat(valueByName(dimension, "Payments").has(field))
                            .as("ClassificationValue.%s is required by openapi.yaml", field)
                            .isTrue());
        }

        @Test
        @DisplayName("companyId and projectId never appear, at either level")
        void tenancyFieldsAreNeverExposed() throws Exception {
            // companyId is the field a client has no business seeing (ERD §0.1 point 2), and
            // projectId is the one a future author would try to SEND in order to pick a scope.
            for (JsonNode dimension : classificationAs(LEAD, ACME, ACME_BANKING)) {
                assertThat(dimension.has("companyId")).isFalse();
                assertThat(dimension.has("projectId")).isFalse();
                dimension.get("values").forEach(value ->
                        assertThat(value.has("companyId")).isFalse());
            }
        }
    }

    // =====================================================================
    // Statuses
    // =====================================================================

    @Nested
    @DisplayName("Statuses — project-scoped, and read by kind rather than by name")
    class Statuses {

        @Test
        @DisplayName("the seven defaults come back in position order, plus what the project added")
        void statusesAreSeededAndOrdered() throws Exception {
            assertThat(namesOf(statusesAs(LEAD, ACME, ACME_BANKING)))
                    .containsExactly("Passed", "Failed", "Blocked", "Skipped", "Retest",
                            // WIP joined the seed at position 6 in V32 (M9), so the fixture's own
                            // custom status moved to 7: two rows sharing a position fall back to
                            // the name, which would put the project's addition among the defaults
                            // and make the ordering the test is about an accident of spelling.
                            "Untested", "WIP", "Passed with comment");
        }

        @Test
        @DisplayName("kind is the row's own value, not derived from the name")
        void kindIsStoredNotInferred() throws Exception {
            // The whole reason `kind` exists: reporting must roll up a project that renamed
            // "Blocked" or added a second passing status without knowing any of their names.
            JsonNode custom = statusByName(statusesAs(LEAD, ACME, ACME_BANKING),
                    "Passed with comment");

            assertThat(custom.get("kind").asText()).isEqualTo("PASSED");
            assertThat(custom.get("countsAsPass").asBoolean())
                    .as("countsAsPass is separate from kind on purpose — a stricter exit "
                            + "criterion may exclude a PASSED-kind status")
                    .isFalse();
        }

        @Test
        @DisplayName("isFinal and countsAsPass are independent of each other and of kind")
        void theTwoBooleansAreDistinct() throws Exception {
            List<JsonNode> statuses = statusesAs(LEAD, ACME, ACME_BANKING);

            assertThat(statusByName(statuses, "Passed").get("countsAsPass").asBoolean()).isTrue();
            assertThat(statusByName(statuses, "Passed").get("isFinal").asBoolean()).isTrue();
            assertThat(statusByName(statuses, "Retest").get("isFinal").asBoolean())
                    .as("a Retest leaves the case still owed a verdict")
                    .isFalse();
            assertThat(statusByName(statuses, "Failed").get("countsAsPass").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("every seeded status carries a colour, though the column allows NULL")
        void everyStatusHasAColour() throws Exception {
            // The one place schema and contract disagree: `color` is required in openapi.yaml and
            // nullable in V1. The invariant holds today because the seed sets all six and nothing
            // else writes a status — pinned here rather than left to hold silently. It stops
            // holding the moment a status-editing endpoint lands without a colour validator, and
            // this test is what will say so. Status colour is load-bearing data and not brandable
            // (§6u), so a colourless status is a data defect, not a state to render.
            assertThat(statusesAs(LEAD, ACME, ACME_BANKING)).allSatisfy(status -> {
                assertThat(status.has("color")).isTrue();
                assertThat(status.get("color").isNull())
                        .as("CaseStatus.color is required by the contract, so the SPA cannot "
                                + "null-check it")
                        .isFalse();
            });
        }

        @Test
        @DisplayName("statuses are per project — the sibling project's are not included")
        void statusesDoNotCrossProjects() throws Exception {
            // Statuses are project-scoped while the classification vocabularies are
            // company-scoped (ERD §3). A read that unioned `project_id IS NULL` here, by symmetry
            // with the dimensions, would invent a tier no writer produces.
            List<String> mobile = idsOf(statusesAs(ADMIN, ACME, ACME_MOBILE));

            assertThat(idsOf(statusesAs(ADMIN, ACME, ACME_BANKING)))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(mobile);
            assertThat(namesOf(statusesAs(ADMIN, ACME, ACME_MOBILE)))
                    .as("the sibling really is populated — otherwise 'does not contain' is vacuous")
                    .hasSize(7);
        }

        @Test
        @DisplayName("the other client's identically-named statuses are different rows")
        void statusesDoNotCrossCompanies() throws Exception {
            assertThat(idsOf(statusesAs(LEAD, ACME, ACME_BANKING)))
                    .doesNotContainAnyElementsOf(idsOf(statusesAs(OUTSIDER, UMBRA, UMBRA_BANKING)));
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode status = statusByName(statusesAs(LEAD, ACME, ACME_BANKING), "Passed");

            assertThat(List.of("id", "name", "kind", "color", "countsAsPass", "isFinal"))
                    .allSatisfy(field -> assertThat(status.has(field))
                            .as("CaseStatus.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(status.has("companyId")).isFalse();
            assertThat(status.has("projectId")).isFalse();
        }
    }

    // =====================================================================
    // The gate, re-asserted per endpoint
    // =====================================================================

    @Nested
    @DisplayName("Both gates apply, on both endpoints")
    class Gates {

        @Test
        @DisplayName("a member with no grant gets 404 on both, not their client's vocabulary")
        void membershipConveysNoVocabulary() throws Exception {
            // The forbidden rows are in THIS caller's own company, so no cross-company assertion
            // can reach them and only the grant gate stands between a member who was granted
            // nothing and the client's feature taxonomy.
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING, "classification")).isEqualTo(404);
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING, "statuses")).isEqualTo(404);
        }

        @Test
        @DisplayName("the same caller who is refused DOES reach a project they hold a grant on")
        void theRefusalIsTheGateAndNotABrokenRoute() throws Exception {
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, "classification")).isEqualTo(200);
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, "statuses")).isEqualTo(200);
        }

        @Test
        @DisplayName("an ungranted project in the caller's own company is 404, not 403")
        void ungrantedIsNotForbidden() throws Exception {
            assertThat(statusOf(LEAD, ACME, ACME_MOBILE, "classification")).isEqualTo(404);
            assertThat(statusOf(LEAD, ACME, ACME_MOBILE, "statuses")).isEqualTo(404);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent are indistinguishable, bodies included")
        void theThreeCasesAnswerIdentically() throws Exception {
            UUID absent = UUID.randomUUID();

            for (String endpoint : List.of("classification", "statuses")) {
                assertThat(List.of(
                        statusOf(LEAD, ACME, ACME_MOBILE, endpoint),
                        statusOf(LEAD, ACME, UMBRA_BANKING, endpoint),
                        statusOf(LEAD, ACME, absent, endpoint)))
                        .as("told apart, the response becomes a probe for what exists in "
                                + "another client's tenancy (§6t)")
                        .containsExactly(404, 404, 404);

                assertThat(bodyOf(LEAD, ACME, UMBRA_BANKING, endpoint))
                        .as("a 404 that explains itself is still an oracle")
                        .isEmpty();
                assertThat(bodyOf(LEAD, ACME, absent, endpoint)).isEmpty();
            }
        }

        @Test
        @DisplayName("a Company Admin's COMPANY-scope grant reaches every project's vocabulary")
        void companyScopeCoversEveryProject() throws Exception {
            assertThat(statusOf(ADMIN, ACME, ACME_BANKING, "classification")).isEqualTo(200);
            assertThat(statusOf(ADMIN, ACME, ACME_MOBILE, "classification")).isEqualTo(200);
            assertThat(statusOf(ADMIN, ACME, UMBRA_BANKING, "classification"))
                    .as("every project in the COMPANY the grant covers — and that company is "
                            + "the whole point")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("both endpoints refuse an anonymous caller")
        void anonymousIsRejected() throws Exception {
            for (String endpoint : List.of("classification", "statuses")) {
                assertThat(mvc.perform(MockMvcRequestBuilders.get(
                                "/api/v1/projects/" + ACME_BANKING + "/" + endpoint))
                        .andReturn().getResponse().getStatus())
                        .isEqualTo(401);
            }
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private List<JsonNode> classificationAs(UUID userId, UUID companyId, UUID projectId)
            throws Exception {
        return arrayOf(bodyOf(userId, companyId, projectId, "classification"));
    }

    private List<JsonNode> statusesAs(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return arrayOf(bodyOf(userId, companyId, projectId, "statuses"));
    }

    private String bodyOf(UUID userId, UUID companyId, UUID projectId, String endpoint)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/" + endpoint)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId, String endpoint)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/" + endpoint)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private List<JsonNode> arrayOf(String body) throws Exception {
        JsonNode array = json.readTree(body);
        assertThat(array.isArray()).as("the endpoint must answer a JSON array: %s", body).isTrue();
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private static JsonNode dimensionByKey(List<JsonNode> dimensions, String key) {
        return dimensions.stream()
                .filter(node -> node.get("key").asText().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no dimension keyed '" + key + "' — the response carried "
                                + keysOf(dimensions)));
    }

    private static JsonNode statusByName(List<JsonNode> statuses, String name) {
        return statuses.stream()
                .filter(node -> node.get("name").asText().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no status named '" + name + "'"));
    }

    private static JsonNode valueByName(JsonNode dimension, String name) {
        for (JsonNode value : dimension.get("values")) {
            if (value.get("name").asText().equals(name)) {
                return value;
            }
        }
        throw new AssertionError("no value named '" + name + "' in dimension "
                + dimension.get("key").asText());
    }

    private static List<String> keysOf(List<JsonNode> dimensions) {
        return dimensions.stream().map(node -> node.get("key").asText()).toList();
    }

    private static List<String> idsOf(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("id").asText()).toList();
    }

    private static List<String> namesOf(Iterable<JsonNode> nodes) {
        List<String> names = new ArrayList<>();
        nodes.forEach(node -> names.add(node.get("name").asText()));
        return names;
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
        return UUID.nameUUIDFromBytes(("project-configuration:" + marker).getBytes());
    }

    /**
     * The vocabularies, through the V2 seed functions rather than by hand.
     *
     * <p>Both are idempotent, so a re-run per test method adds nothing — which is what makes
     * {@code @BeforeEach} safe here.
     */
    private void seedVocabularies(UUID companyId, List<UUID> projectIds) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
                statement.setObject(1, companyId);
                statement.execute();
            }
            for (UUID projectId : projectIds) {
                try (var statement = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                    statement.setObject(1, companyId);
                    statement.setObject(2, projectId);
                    statement.execute();
                }
            }
        }
    }

    /**
     * A two-level Feature tree plus a retired value, in Acme's banking project.
     *
     * <p>The seed leaves Feature empty on purpose — inventing a client's taxonomy is how a
     * controlled vocabulary gets worked around — so the tree, the {@code parentId}, the
     * {@code ltree} path and the soft-delete have no coverage until a fixture writes them. There is
     * no vocabulary-editing endpoint yet, so this is raw SQL through the ordinary app role, with
     * the company context set: the same policed path production reads through.
     */
    private void seedFeatureTree() throws SQLException {
        UUID payments = UUID.fromString("cf005000-0000-4000-8000-000000000001");
        UUID refunds = UUID.fromString("cf005000-0000-4000-8000-000000000002");
        UUID retired = UUID.fromString("cf005000-0000-4000-8000-000000000003");

        try (Connection connection = appConnection(ACME)) {
            insertValue(connection, payments, null, "Payments", "Payments", "#334455", 0, false);
            insertValue(connection, refunds, payments, "Refunds", "Payments.Refunds", null, 1, false);
            // Retired, and therefore invisible — but still resolvable for the executions that ran
            // classified under it, which is why it is a soft delete.
            insertValue(connection, retired, null, "Legacy Onboarding", "Legacy_Onboarding",
                    null, 2, true);
        }
    }

    /**
     * Every nullable parameter is bound as text through an explicit {@code CAST}.
     *
     * <p>{@code setObject(i, null)} leaves pgjdbc with no type to infer, and in a {@code SELECT}
     * list it fails with "could not determine data type of parameter" — a fixture-only failure
     * that reads like a schema problem. The casts also spell out that {@code path} is an
     * {@code ltree} and not a string, which is the point of having one here at all.
     */
    private void insertValue(
            Connection connection, UUID id, UUID parentId, String name, String path,
            String color, int position, boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO classification_value
                    (id, company_id, dimension_id, parent_id, path, name, color, position, deleted_at)
                SELECT CAST(? AS uuid), CAST(? AS uuid), d.id, CAST(? AS uuid), CAST(? AS ltree),
                       ?, CAST(? AS text), ?, CAST(? AS timestamptz)
                  FROM classification_dimension d
                 WHERE d.company_id = CAST(? AS uuid)
                   AND d.project_id = CAST(? AS uuid)
                   AND d.key = 'feature'
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, parentId == null ? null : parentId.toString());
            statement.setString(4, path);
            statement.setString(5, name);
            statement.setString(6, color);
            statement.setInt(7, position);
            // A fixed instant rather than now(): nothing asserts the value, and a deterministic
            // fixture is one less thing that can differ between runs.
            statement.setString(8, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.setString(9, ACME.toString());
            statement.setString(10, ACME_BANKING.toString());
            statement.executeUpdate();
        }
    }

    /**
     * A project-added status, so {@code kind} cannot pass by being inferable from the name.
     *
     * <p>"Passed with comment" is of kind {@code PASSED} and does <em>not</em> count as a pass:
     * exactly the case the two separate columns exist for.
     */
    private void seedExtraStatus() throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement("""
                        INSERT INTO case_status
                            (id, company_id, project_id, name, kind, color, is_final,
                             counts_as_pass, position)
                        VALUES (?, ?, ?, 'Passed with comment', 'PASSED', '#4d7c5f', true, false, 7)
                        ON CONFLICT (id) DO NOTHING
                        """)) {

            statement.setObject(1, UUID.fromString("cf006000-0000-4000-8000-000000000001"));
            statement.setObject(2, ACME);
            statement.setObject(3, ACME_BANKING);
            statement.executeUpdate();
        }
    }
}
