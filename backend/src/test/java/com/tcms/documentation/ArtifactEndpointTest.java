package com.tcms.documentation;

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
import java.sql.ResultSet;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The artifact register end to end — the two scopes, the inheritance, and the qualifier V19 exists
 * for.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The scope qualifier ignored.</strong> The one that matters, and the reason the slice
 *       touched the authorization core. Gate the create on {@code artifact:edit} alone and only
 *       {@link Qualifier#engineerCannotRegisterAtProjectScope} goes red — every other assertion
 *       passes, because a Test Engineer legitimately registers plan-scope artifacts and a Test Lead
 *       legitimately registers both. A Test Engineer who can rewrite the project's test strategy is
 *       editing a document every cycle inherits read-only (plan §6n), and nothing about the response
 *       would look wrong.</li>
 *   <li><strong>The qualifier over-applied.</strong> The mirror failure and the more likely one to
 *       ship, because it looks like caution: refuse a Test Engineer both scopes and the register is
 *       Lead-only, which the roles spec's ✓ explicitly is not.
 *       {@link Qualifier#engineerCanRegisterAtPlanScope} is the guard.</li>
 *   <li><strong>Inheritance dropped, or applied to the wrong half.</strong> A plan register that
 *       omits the project's documents makes every cycle re-upload the environment guide — plan §6n
 *       calls that the single most common failure for a register. A plan register that returns them
 *       <em>unflagged</em> is worse: the reader edits what they think is theirs.</li>
 *   <li><strong>A plan id from another project accepted.</strong> {@code V1}'s foreign key proves
 *       only that the plan exists. A row attached to a sibling project's plan is findable by neither
 *       register, so it is data no screen can show — see {@link PlanFacts}.</li>
 *   <li><strong>A {@code javascript:} URL stored.</strong> The register renders external links as
 *       anchors, so this is stored XSS on the screen where a colleague's link is clicked without
 *       being read.</li>
 *   <li><strong>An unregistrable kind silently stored.</strong> {@code FILE} needs the storage port
 *       that does not exist; accepting one would create a row the register renders with no way to
 *       reach the bytes.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code af…}, checked against every prefix
 * already in {@code src/test/java} before writing. Sharing them is how handoff §19's first defect
 * happened, and §29 and §54 each hit it again.
 */
@AutoConfigureMockMvc
@DisplayName("Artifact register — the two scopes, the inheritance, and the plan-scope qualifier")
class ArtifactEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("af000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("af000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. Carries the plans and the artifacts. */
    private static final UUID ACME_BANKING = UUID.fromString("af001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the 404-not-403 fixture, and the foreign plan's home. */
    private static final UUID ACME_LOCKED = UUID.fromString("af001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("af002000-0000-4000-8000-000000000001");

    /** Test Lead. Holds {@code artifact:edit} unqualified — both scopes. */
    private static final UUID LEAD = UUID.fromString("af003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer. Holds {@code artifact:edit} <strong>PLAN_SCOPE</strong> — the one persona the
     * whole slice turns on, and the reason V19 exists.
     */
    private static final UUID ENGINEER = UUID.fromString("af003000-0000-4000-8000-000000000002");
    /** Viewer. Reads the register and holds no {@code artifact:edit} at all. */
    private static final UUID VIEWER = UUID.fromString("af003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("af003000-0000-4000-8000-000000000004");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("af003000-0000-4000-8000-000000000005");

    /** A plan of ACME_BANKING — what a plan-scope artifact attaches to. */
    private static final UUID BANKING_PLAN = UUID.fromString("af004000-0000-4000-8000-000000000001");
    /** A plan of ACME_LOCKED — same company, different project. The PlanFacts fixture. */
    private static final UUID FOREIGN_PLAN = UUID.fromString("af004000-0000-4000-8000-000000000002");

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
            company(ACME, "af-acme", "Acme", now);
            company(UMBRA, "af-umbra", "Umbra", now);

            activeUser(LEAD, "lead@af-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@af-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@af-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@af-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@af-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "AFOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "AFLCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("af-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("af-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("af-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "AFUMB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("af-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedProjectDefaults();
        seedPlans();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>Artifacts are deleted before plans because {@code artifact.plan_id} references
     * {@code test_plan}, and doc pages after artifacts for the same reason on {@code doc_page_id}.
     * Getting this order wrong surfaces as a foreign-key violation in {@code @BeforeEach}, which
     * reads as "the fixture is broken" rather than "the teardown is out of order".
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // Through each company's OWN context: RLS means the other's connection could not
                // see these rows to delete them, which is isolation working rather than a leftover.
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM artifact");
                execute(connection, "DELETE FROM test_plan");
                execute(connection, "DELETE FROM doc_block");
                execute(connection, "DELETE FROM doc_page");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
        }
    }

    /**
     * Two plans in one company and two different projects.
     *
     * <p>Raw SQL rather than {@code execution}'s own repository, because this module's test has no
     * more business importing {@code execution} than the module does — {@link PlanFacts} reads the
     * table the same way, and a fixture built through the sibling's API would prove that API works
     * rather than that this one does.
     */
    private void seedPlans() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            insertPlan(connection, BANKING_PLAN, ACME_BANKING, "AFOLB-P1", 1, "Release 1");
            insertPlan(connection, FOREIGN_PLAN, ACME_LOCKED, "AFLCK-P1", 1, "Internal Release 1");
        }
    }

    // =====================================================================
    // The qualifier — the reason this slice touched the authorization core
    // =====================================================================

    @Nested
    @DisplayName("The PLAN_SCOPE qualifier — a Test Engineer curates a cycle, not the project")
    class Qualifier {

        @Test
        @DisplayName("a Test Engineer registers a PLAN-scope artifact and gets 201")
        void engineerCanRegisterAtPlanScope() throws Exception {
            // The guard against over-applying the qualifier. The roles spec grants a Test Engineer
            // artifact:edit with a restriction, not a refusal — withholding it entirely would make
            // the register Lead-only and leave nobody able to attach their own cycle's sign-off.
            MvcResult result = create(ENGINEER, ACME_BANKING,
                    link("PLAN", BANKING_PLAN, "SignOff", "Release 1 sign-off",
                            "https://sign.example.test/r1"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("scope").asText()).isEqualTo("PLAN");
        }

        @Test
        @DisplayName("the same Test Engineer is REFUSED a PROJECT-scope artifact")
        void engineerCannotRegisterAtProjectScope() throws Exception {
            // THE assertion of this class. Gate the create on artifact:edit alone — the obvious
            // implementation, and what every other endpoint in the codebase does — and this is the
            // only test in the suite that goes red.
            MvcResult result = create(ENGINEER, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"));

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(countArtifacts(ACME)).isZero();

            // The refusal names the field, unlike the bodiless 403 a Viewer gets. §31's rule is
            // that an action the server will refuse renders disabled with a reason — and the SPA
            // structurally cannot honour it here, because its permission model is keys-only and
            // this caller DOES hold artifact:edit. So the next best thing is a refusal the form can
            // put on the scope selector, one radio button away from succeeding.
            assertThat(errorFields(result)).containsExactly("scope");
        }

        @Test
        @DisplayName("a Viewer's refusal stays bodiless — they hold nothing, so there is no advice")
        void theViewerRefusalCarriesNoField() throws Exception {
            MvcResult result = create(VIEWER, ACME_BANKING,
                    link("PLAN", BANKING_PLAN, "SignOff", "Sign-off", "https://x.example.test/a"));

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString())
                    .as("changing the scope would not help a caller who holds no artifact:edit, so "
                            + "advice naming the scope field would be actively misleading")
                    .isEmpty();
        }

        @Test
        @DisplayName("a Test Lead registers at PROJECT scope — so the 403 above is the qualifier")
        void leadCanRegisterAtProjectScope() throws Exception {
            // Without this the test above passes just as well against a broken route, a missing
            // grant, or a create that refuses everybody. The same payload, a different caller.
            MvcResult result = create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("scope").asText()).isEqualTo("PROJECT");
        }

        @Test
        @DisplayName("the refused Engineer still READS the project-scope artifact they cannot write")
        void theRefusalIsAboutWritingAndNotAboutSeeing() throws Exception {
            create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"));

            // artifact:read is unqualified for everyone who holds it. A qualifier applied to the
            // read as well would hide the project's documents from the people who most need them —
            // the inheritance in plan §6n exists precisely so a tester finds the environment guide.
            assertThat(titlesOf(list(ENGINEER, ACME_BANKING, null, null)))
                    .containsExactly("Test strategy");
        }

        @Test
        @DisplayName("a Viewer holds no artifact:edit at all and is refused both scopes")
        void viewerCannotRegisterAnything() throws Exception {
            assertThat(create(VIEWER, ACME_BANKING,
                    link("PLAN", BANKING_PLAN, "SignOff", "Sign-off", "https://x.example.test/a"))
                    .getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(create(VIEWER, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Strategy", "https://x.example.test/b"))
                    .getResponse().getStatus())
                    .isEqualTo(403);
        }
    }

    // =====================================================================
    // Inheritance — plan §6n
    // =====================================================================

    @Nested
    @DisplayName("Inheritance — a plan shows the project's documents, marked")
    class Inheritance {

        @BeforeEach
        void twoScopes() throws Exception {
            create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Environment", "Environment guide",
                            "https://wiki.example.test/env"));
            create(LEAD, ACME_BANKING,
                    link("PLAN", BANKING_PLAN, "SignOff", "Release 1 sign-off",
                            "https://sign.example.test/r1"));
        }

        @Test
        @DisplayName("the project register shows only the project's own — never a plan's")
        void projectScopeExcludesPlanRows() throws Exception {
            // A cycle's sign-off presented as though it governed the project is the inverse of the
            // inheritance rule and just as misleading.
            List<JsonNode> rows = list(LEAD, ACME_BANKING, null, null);

            assertThat(titlesOf(rows)).containsExactly("Environment guide");
            assertThat(rows.getFirst().get("inherited").asBoolean())
                    .as("nothing is inherited when the project itself is what was asked for")
                    .isFalse();
        }

        @Test
        @DisplayName("the plan register shows both, and only the project's is flagged inherited")
        void planScopeIncludesProjectRowsFlagged() throws Exception {
            List<JsonNode> rows = list(LEAD, ACME_BANKING, "PLAN", BANKING_PLAN);

            assertThat(titlesOf(rows))
                    .as("the plan's own first, the project's after — never interleaved by category, "
                            + "so what the reader may edit stays visually separable")
                    .containsExactly("Release 1 sign-off", "Environment guide");

            assertThat(rows.get(0).get("inherited").asBoolean())
                    .as("the plan's own row is not inherited")
                    .isFalse();
            assertThat(rows.get(1).get("inherited").asBoolean())
                    .as("""
                            THE marker. Unflagged, a reader cannot tell which documents belong to \
                            their release — and so they duplicate them, which plan §6n names as the \
                            single most common failure mode for a register.""")
                    .isTrue();
        }

        @Test
        @DisplayName("a plan of another project shows that project's rows, not this one's")
        void aPlanIsScopedToItsOwnProject() throws Exception {
            // FOREIGN_PLAN belongs to ACME_LOCKED. Asking for it under ACME_BANKING must not
            // return ACME_LOCKED's cycle — and must not leak ACME_BANKING's plan row either.
            assertThat(titlesOf(list(LEAD, ACME_BANKING, "PLAN", FOREIGN_PLAN)))
                    .as("only the project's inherited rows remain — the plan matches nothing here")
                    .containsExactly("Environment guide");
        }
    }

    // =====================================================================
    // The scope/planId combinations, and the cross-project plan
    // =====================================================================

    @Nested
    @DisplayName("Scope and plan must agree — no silent fallback")
    class ScopeAgreement {

        @Test
        @DisplayName("scope=PLAN with no planId is 400, not the project's register")
        void planScopeNeedsAPlan() throws Exception {
            // Falling back to the project's register would answer one question under a heading
            // promising another — §47's dashboard tile, which linked to everyone's cases under a
            // heading naming one person.
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/artifacts")
                            .param("scope", "PLAN")
                            .header(HttpHeaders.AUTHORIZATION, bearer(LEAD)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("scope=PROJECT with a planId is 400, not a silently ignored parameter")
        void projectScopeTakesNoPlan() throws Exception {
            // The direction §50 found and called worse: a client that believes the plan narrowed
            // something gets a response that agrees with it and does not.
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/artifacts")
                            .param("scope", "PROJECT")
                            .param("planId", BANKING_PLAN.toString())
                            .header(HttpHeaders.AUTHORIZATION, bearer(LEAD)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("registering against a plan of another project is 400, not a hidden row")
        void aPlanFromAnotherProjectIsRefused() throws Exception {
            // V1's foreign key proves the plan exists; only PlanFacts proves it is THIS project's.
            // Accepted, the row would be found by neither register — data no screen can show.
            MvcResult result = create(LEAD, ACME_BANKING,
                    link("PLAN", FOREIGN_PLAN, "SignOff", "Wrong project's sign-off",
                            "https://sign.example.test/x"));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(result)).containsExactly("planId");
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("a PROJECT-scope registration carrying a planId is refused, not trimmed")
        void projectScopeRegistrationTakesNoPlan() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING,
                    link("PROJECT", BANKING_PLAN, "Strategy", "Strategy",
                            "https://wiki.example.test/s"));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(result)).containsExactly("planId");
        }
    }

    // =====================================================================
    // What may be registered
    // =====================================================================

    @Nested
    @DisplayName("Reference kinds only, and a link must be a link")
    class Kinds {

        @Test
        @DisplayName("a javascript: URL is refused — the register renders these as anchors")
        void onlyHttpUrls() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Other", "Innocent looking",
                            "javascript:alert(document.cookie)"));

            // Stored XSS wearing the product's own markup, on the screen where a colleague's link
            // is clicked without being read. An allow-list, because the dangerous set is open-ended.
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(result)).containsExactly("url");
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("a FILE is refused with a sentence naming what to do instead")
        void uploadsAreNotRegistrableYet() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING, """
                    {"scope":"PROJECT","category":"TestData","kind":"FILE","title":"Seed data"}""");

            // Accepting it would create a row the register renders with no way to reach the bytes.
            // The storage port is open question #28 and is not built.
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(result)).containsExactly("kind");
        }

        @Test
        @DisplayName("a doc page reference must name a live page of THIS project")
        void docPageMustBeInThisProject() throws Exception {
            UUID strayPage = UUID.randomUUID();

            MvcResult result = create(LEAD, ACME_BANKING, """
                    {"scope":"PROJECT","category":"Strategy","kind":"DOC_PAGE_REF",\
                    "title":"Strategy","docPageId":"%s"}""".formatted(strayPage));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(result)).containsExactly("docPageId");
        }

        @Test
        @DisplayName("every cross-field error is reported at once, not one per round trip")
        void allErrorsTogether() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING, """
                    {"scope":"PLAN","category":"","kind":"EXTERNAL_URL","title":""}""");

            // A form that surfaces one error per submit makes the user discover their payload by
            // bisection.
            assertThat(errorFields(result))
                    .containsExactlyInAnyOrder("title", "category", "planId", "url");
        }
    }

    // =====================================================================
    // Reachability and isolation
    // =====================================================================

    @Nested
    @DisplayName("404 never 403 for reachability, and no company sees another's register")
    class Reachability {

        @Test
        @DisplayName("a member with no grant gets 404 on the register, not 403")
        void noGrantIs404() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/artifacts")
                            .header(HttpHeaders.AUTHORIZATION, bearer(NO_GRANT)))
                    .andReturn().getResponse().getStatus())
                    .as("403 would confirm the project id names something real (§6t)")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a project of another company is the same 404")
        void anotherCompanyIs404() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + UMBRA_BANKING + "/artifacts")
                            .header(HttpHeaders.AUTHORIZATION, bearer(LEAD)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a create against an unreachable project is 404, and writes nothing")
        void createOnUnreachableProjectIs404() throws Exception {
            MvcResult result = create(LEAD, ACME_LOCKED,
                    link("PROJECT", null, "Strategy", "Strategy", "https://x.example.test/a"));

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("an outsider's register in their own company never shows Acme's rows")
        void companiesAreIsolated() throws Exception {
            create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Acme's strategy",
                            "https://wiki.example.test/acme"));

            assertThat(titlesOf(list(OUTSIDER, UMBRA_BANKING, null, null))).isEmpty();
        }
    }

    // =====================================================================
    // The response
    // =====================================================================

    @Nested
    @DisplayName("The response — who touched it, and what it points at")
    class Response {

        @Test
        @DisplayName("updatedBy is the COMPANY MEMBERSHIP's name, not the account's canonical one")
        void updatedByUsesTheMembershipName() throws Exception {
            create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"));

            // Plan §6u: the same person can be "A. Kotrulev" to one client and "Aleksandar
            // (Contractor)" to another. LEAD's account name is "L. Lead" and their Acme membership
            // says "L. Lead of Acme" — resolving from app_user alone would show every client the
            // same string, and this is the assertion that tells the two apart.
            JsonNode row = list(LEAD, ACME_BANKING, null, null).getFirst();
            assertThat(row.get("updatedBy").get("displayName").asText()).isEqualTo("L. Lead of Acme");
        }

        @Test
        @DisplayName("a doc page reference carries docPageId so the register can link to it")
        void docPageReferencesAreNavigable() throws Exception {
            UUID pageId = createDocPage(LEAD, ACME_BANKING, "Test strategy");

            MvcResult result = create(LEAD, ACME_BANKING, """
                    {"scope":"PROJECT","category":"Strategy","kind":"DOC_PAGE_REF",\
                    "title":"Test strategy","docPageId":"%s"}""".formatted(pageId));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            // Without this field the kind plan §6n steers people toward is the one kind the reader
            // cannot open, while an EXTERNAL_URL beside it is clickable.
            assertThat(body(result).get("docPageId").asText()).isEqualTo(pageId.toString());
            assertThat(body(result).get("url").isNull()).isTrue();
        }

        @Test
        @DisplayName("a registration begins ACTIVE — status is not an input (§6g)")
        void statusIsNeverChosenByTheAuthor() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING, """
                    {"scope":"PROJECT","category":"SignOff","kind":"EXTERNAL_URL",\
                    "title":"Sign-off","url":"https://sign.example.test/a",\
                    "status":"SUPERSEDED"}""");

            // The field is not on ArtifactInput at all, so the extra property is ignored rather
            // than honoured. A create that could set its own status is an author approving their
            // own sign-off — the one thing the register exists to make auditable.
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("status").asText()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("registering writes one audit event naming the scope, not just the title")
        void theActIsAudited() throws Exception {
            create(LEAD, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"));

            // "Who put the project's strategy document here" is a scope question, and a row saying
            // only the title cannot answer it.
            //
            // Parsed rather than substring-matched: the column is `jsonb`, which Postgres stores
            // normalized — keys reordered, a space after each colon — so an assertion on the text
            // would be testing Postgres's formatting rather than what was recorded.
            List<String> changes = auditChanges(ACME);
            assertThat(changes).hasSize(1);

            JsonNode recorded = json.readTree(changes.getFirst());
            assertThat(recorded.get("scope").asText()).isEqualTo("PROJECT");
            assertThat(recorded.get("title").asText()).isEqualTo("Test strategy");
            assertThat(recorded.get("category").asText()).isEqualTo("Strategy");
        }

        @Test
        @DisplayName("a freshly provisioned project's register is empty, not seeded")
        void nothingSeedsAFirstArtifact() throws Exception {
            // Plan §6n floats seeding "the right empty slots" from a template; that is a project-
            // settings feature. Inventing a client's document set is the same mistake as seeding
            // their Feature tree.
            assertThat(titlesOf(list(LEAD, ACME_BANKING, null, null))).isEmpty();
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private MvcResult create(UUID userId, UUID projectId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/artifacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private List<JsonNode> list(UUID userId, UUID projectId, String scope, UUID planId)
            throws Exception {

        var request = MockMvcRequestBuilders
                .get("/api/v1/projects/" + projectId + "/artifacts")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId));
        if (scope != null) {
            request = request.param("scope", scope);
        }
        if (planId != null) {
            request = request.param("planId", planId.toString());
        }

        MvcResult result = mvc.perform(request).andReturn();
        if (result.getResponse().getStatus() != 200) {
            return List.of();
        }
        List<JsonNode> rows = new ArrayList<>();
        json.readTree(result.getResponse().getContentAsString()).forEach(rows::add);
        return rows;
    }

    /** A doc page, created through its own endpoint — the only writer there is. */
    private UUID createDocPage(UUID userId, UUID projectId, String title) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/doc-pages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + quote(title) + "}"))
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private String bearer(UUID userId) {
        // Inside a transaction: SessionTokens writes a session row, and the write needs the company
        // context the transaction manager establishes in doBegin.
        return "Bearer "
                + tx.execute(status -> tokens.issue(userId, companyOf(userId), 1, false).token());
    }

    private static UUID companyOf(UUID userId) {
        return userId.equals(OUTSIDER) ? UMBRA : ACME;
    }

    // =====================================================================
    // helpers — payloads and reading responses
    // =====================================================================

    private String link(String scope, UUID planId, String category, String title, String url) {
        StringBuilder body = new StringBuilder("{\"scope\":\"").append(scope).append('"');
        if (planId != null) {
            body.append(",\"planId\":\"").append(planId).append('"');
        }
        body.append(",\"category\":").append(quote(category))
                .append(",\"kind\":\"EXTERNAL_URL\"")
                .append(",\"title\":").append(quote(title))
                .append(",\"url\":").append(quote(url))
                .append('}');
        return body.toString();
    }

    private String quote(String value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<String> titlesOf(List<JsonNode> rows) {
        return rows.stream().map(row -> row.get("title").asText()).toList();
    }

    /** The field names in a ProblemDetail's {@code errors} property. */
    private List<String> errorFields(MvcResult result) throws Exception {
        List<String> fields = new ArrayList<>();
        body(result).get("errors").fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    // =====================================================================
    // helpers — database
    // =====================================================================

    private long countArtifacts(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("SELECT count(*) FROM artifact");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<String> auditChanges(UUID companyId) throws SQLException {
        List<String> details = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT changes FROM audit_event WHERE action = 'ARTIFACT_REGISTERED'");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                details.add(rows.getString(1));
            }
        }
        return details;
    }

    private void insertPlan(
            Connection connection, UUID id, UUID projectId, String key, long keySeq, String name)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_plan (id, company_id, project_id, key, key_seq, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
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
        UUID id = UUID.nameUUIDFromBytes(("artifact-endpoint:" + marker).getBytes());
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

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {

        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }
}
