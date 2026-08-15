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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /**
     * A second plan of ACME_BANKING — added by §209 for the half of the same-scope rule that
     * {@link #BANKING_PLAN} alone cannot express: two rows may share a scope and still sit in
     * different cycles, and one cycle's document does not replace another's.
     */
    private static final UUID SECOND_PLAN = UUID.fromString("af004000-0000-4000-8000-000000000003");

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
            insertPlan(connection, SECOND_PLAN, ACME_BANKING, "AFOLB-P2", 2, "Release 2");
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
        @DisplayName("the same Test Engineer registers at PROJECT scope too since V52")
        void engineerRegistersAtProjectScope() throws Exception {
            /*
              ~~THE assertion of this class. Gate the create on artifact:edit alone — the obvious
              implementation, and what every other endpoint in the codebase does — and this is the
              only test in the suite that goes red.~~

              → INVERTED by V52 (§210), which de-qualified artifact:edit. A contributor edits,
              unqualified, so the project-scope row every plan inherits read-only is theirs now.

              AND THE WARNING THE OLD COMMENT CARRIES IS NOW LIVE. `writeGrantedByRowScope` still
              runs on this path and simply always answers yes for this caller, so gating the create
              on `artifact:edit` alone — the obvious implementation — leaves the whole suite green.
              The scope seam is retained because it is driven by DATA: a migration that re-qualifies
              the key gets the narrowing back, where a deleted gate would fail OPEN. Roles spec §A5,
              and `SeedDataTest.artifactEditIsUnqualifiedEverywhere` is where the current answer is
              pinned.
            */
            MvcResult result = create(ENGINEER, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(countArtifacts(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("a Viewer is still refused, at either scope — the gate is a gate")
        void viewerCannotRegisterAtAll() throws Exception {
            // What is left of the discrimination above. Viewer holds no artifact:edit at all, so
            // this proves the create is gated — the half engineerRegistersAtProjectScope can no
            // longer prove now that the contributor's grant is unqualified.
            assertThat(create(VIEWER, ACME_BANKING,
                    link("PROJECT", null, "Strategy", "Test strategy",
                            "https://wiki.example.test/strategy"))
                    .getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("/me/permissions still carries a qualifier per key — now ALL for everything held")
        void theQualifierIsOnTheWire() throws Exception {
            /*
              §65's reason for existing, asserted at the surface the SPA actually reads.

              ~~The test above proves the server refuses; this proves the client could have KNOWN.~~
              → V52 (§210) de-qualified `artifact:edit`, so there is nothing left in the seeded
              catalogue for the SPA to be warned about: every held key answers ALL.

              THIS TEST IS KEPT AND IT IS NOW WEAK, WHICH IS THE POINT OF SAYING SO. The control
              assertion it was built around — a second key with a DIFFERENT qualifier, the only thing
              that tells "resolved" from "hard-coded" — CANNOT BE MADE from seeded data any more,
              because no grant carries a qualifier. A server that stamped every entry with a constant
              "ALL" would satisfy everything below. `§210 DQ-6` is the queue row; the honest fix is a
              fixture that qualifies a grant directly rather than a persona that inherits one.
            */
            Map<String, String> engineer = heldPermissions(ENGINEER, ACME_BANKING);

            assertThat(engineer.get("artifact:edit"))
                    .as("unqualified since V52 — the grant the roles spec used to qualify here")
                    .isEqualTo("ALL");
            assertThat(engineer.get("artifact:read"))
                    .as("and its unqualified neighbour, unchanged")
                    .isEqualTo("ALL");
            assertThat(heldPermissions(LEAD, ACME_BANKING).get("artifact:edit"))
                    .as("a Test Lead held it outright before V52 and still does")
                    .isEqualTo("ALL");

            // NONE is never serialized: a permission nobody holds is an absent entry, not a
            // present one qualified to nothing. A Viewer holding no artifact:edit must be silent
            // about it rather than sending a value the client would have to interpret.
            assertThat(heldPermissions(VIEWER, ACME_BANKING))
                    .as("a Viewer holds artifact:read and not artifact:edit")
                    .containsEntry("artifact:read", "ALL")
                    .doesNotContainKey("artifact:edit");
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
        @DisplayName("a FILE is refused HERE and the message names the upload endpoint")
        void uploadsGoToTheFileEndpoint() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING, """
                    {"scope":"PROJECT","category":"TestData","kind":"FILE","title":"Seed data"}""");

            // ~~"the storage port is open question #28 and is not built"~~ → it is, and a FILE is
            // registrable since §126 phase 3 — at POST /artifacts/files, because the bytes have to
            // travel WITH the registration. Accepting it here would create the exact row that
            // endpoint's whole shape exists to make unreachable: a register entry saying a document
            // is held, with no way to reach it.
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(result)).containsExactly("kind");

            // The advice, asserted. §124's ordering rule: advice that is confidently wrong is worse
            // than a flat refusal, and this one is safe to give because the caller holds the same
            // permission for the endpoint it names.
            assertThat(result.getResponse().getContentAsString())
                    .contains("/artifacts/files");
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
    // Deletion — artifact:delete's first consumer, and the two things it must refuse
    // =====================================================================

    /**
     * <strong>{@code artifact:delete} had been in the catalogue since M0 with no consumer</strong>,
     * so there was no delete endpoint at all (plan §8 #53). These are the assertions that make the
     * key mean something, and the first is the one that would have been got wrong: reusing
     * {@code artifact:edit} compiles, passes review, and hands every Test Engineer the ability to
     * remove a lead's sign-off record.
     */
    /**
     * <strong>The act that writes {@code SUPERSEDED}</strong>, and the scope rule that is the whole
     * of its authorization.
     *
     * <p>Superseding writes a <em>second</em> row — the one being replaced — which nothing gated.
     * The rule that makes the create's own scope-qualified gate cover it is that the replacement
     * must sit in the same place as the row it replaces. Delete that rule and
     * {@link #theEngineerCannotRetireWhatTheyMayNotEdit} is the only assertion that goes red.
     */
    @Nested
    @DisplayName("Superseding — one request, two rows, one gate")
    class Supersession {

        @Test
        @DisplayName("the replacement flips the old row and names itself on it")
        void theReplacementFlipsTheOldRow() throws Exception {
            UUID replaced = createdId(create(LEAD, ACME_BANKING, urlBody("Sign-off v1")));

            UUID replacement = createdId(create(
                    LEAD, ACME_BANKING, replacing(urlBody("Sign-off v2"), replaced)));

            JsonNode old = row(ACME_BANKING, replaced);
            assertThat(old.get("status").asText()).isEqualTo("SUPERSEDED");
            assertThat(old.get("supersededById").asText())
                    .as("a status the reader cannot follow is a dead end — the pointer is what "
                            + "turns \"superseded\" into \"superseded by that one\"")
                    .isEqualTo(replacement.toString());
            assertThat(row(ACME_BANKING, replacement).get("status").asText()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("the replaced row stays in the register — that is the point of not deleting it")
        void theOldRowStays() throws Exception {
            UUID replaced = createdId(create(LEAD, ACME_BANKING, urlBody("Sign-off v1")));
            create(LEAD, ACME_BANKING, replacing(urlBody("Sign-off v2"), replaced));

            assertThat(titlesOf(list(LEAD, ACME_BANKING, null, null)))
                    .as("plan §6n makes the audit trail the register's value; a supersession that "
                            + "hid the old row would answer the audit question with a gap")
                    .containsExactlyInAnyOrder("Sign-off v1", "Sign-off v2");
        }

        /**
         * <strong>THE gate assertion of this class.</strong> The engineer may register at plan
         * scope, so the create itself is allowed — and the row they name is the project's, which
         * they hold read-only (plan §6n). Drop the same-scope rule and this 400 becomes a 201 that
         * retires a document the caller may not edit, with a response that looks entirely correct.
         */
        @Test
        @DisplayName("a Test Engineer cannot retire a PROJECT artifact by replacing it from a plan")
        void theEngineerCannotRetireWhatTheyMayNotEdit() throws Exception {
            UUID strategy = createdId(create(LEAD, ACME_BANKING, urlBody("Test strategy")));

            MvcResult refused = create(
                    ENGINEER, ACME_BANKING, replacing(planBody("Cycle note"), strategy));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).contains("supersedes");
            assertThat(row(ACME_BANKING, strategy).get("status").asText())
                    .as("a refused registration must leave the other row alone")
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("a plan artifact cannot be replaced from another plan's register either")
        void theScopeRuleIsAboutThePlanToo() throws Exception {
            UUID first = createdId(create(LEAD, ACME_BANKING, planBody("Release 1 snapshot")));
            String otherPlan = link("PLAN", SECOND_PLAN, "Cycle", "Release 2 snapshot",
                    "https://docs.example.test/r2");

            MvcResult refused = create(LEAD, ACME_BANKING, replacing(otherPlan, first));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).contains("supersedes");
        }

        @Test
        @DisplayName("an artifact already replaced is refused — the chain keeps its middle")
        void aSecondReplacementIsRefused() throws Exception {
            UUID first = createdId(create(LEAD, ACME_BANKING, urlBody("Sign-off v1")));
            create(LEAD, ACME_BANKING, replacing(urlBody("Sign-off v2"), first));

            MvcResult refused =
                    create(LEAD, ACME_BANKING, replacing(urlBody("Sign-off v3"), first));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").get("supersedes").get(0).asText())
                    .as("overwriting superseded_by_id would leave the register saying v3 replaced "
                            + "v1 and that v2 replaced nothing")
                    .contains("already been replaced");
        }

        @Test
        @DisplayName("an archived artifact is refused, and told to be restored first")
        void anArchivedArtifactIsRefused() throws Exception {
            UUID retired = createdId(create(LEAD, ACME_BANKING, urlBody("Old guide")));
            assertThat(setStatus(LEAD, ACME_BANKING, retired, "ARCHIVED")
                    .getResponse().getStatus()).isEqualTo(200);

            MvcResult refused =
                    create(LEAD, ACME_BANKING, replacing(urlBody("New guide"), retired));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").get("supersedes").get(0).asText())
                    .contains("Restore it");
        }

        @Test
        @DisplayName("an artifact of another project is one message with an absent one")
        void anotherProjectsArtifactIsRefused() throws Exception {
            UUID elsewhere = UUID.fromString("af005000-0000-4000-8000-000000000009");
            insertArtifact(elsewhere, ACME, ACME_LOCKED, "Locked project's strategy");

            MvcResult refused =
                    create(LEAD, ACME_BANKING, replacing(urlBody("Replacement"), elsewhere));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").get("supersedes").get(0).asText())
                    .as("a distinct message would let a caller probe which artifact ids are real "
                            + "in a project they cannot see (§6t)")
                    .isEqualTo("That artifact is not in this project.");
        }

        @Test
        @DisplayName("the kinds may differ — a stale link replaced by an authored page")
        void kindsMayDiffer() throws Exception {
            UUID link = createdId(create(LEAD, ACME_BANKING, urlBody("Strategy on Confluence")));
            UUID page = createDocPage(LEAD, ACME_BANKING, "Test strategy");
            String pageRef = "{\"scope\":\"PROJECT\",\"category\":\"Strategy\","
                    + "\"kind\":\"DOC_PAGE_REF\",\"title\":\"Test strategy\","
                    + "\"docPageId\":\"" + page + "\"}";

            MvcResult replaced = create(LEAD, ACME_BANKING, replacing(pageRef, link));

            assertThat(replaced.getResponse().getStatus())
                    .as("what is recorded is that one document took over from another — plan §6n "
                            + "steers people off stale copies and this is the move that does it")
                    .isEqualTo(201);
            assertThat(row(ACME_BANKING, link).get("status").asText()).isEqualTo("SUPERSEDED");
        }

        @Test
        @DisplayName("the supersession is audited as the edge, naming both rows")
        void theActIsAudited() throws Exception {
            UUID replaced = createdId(create(LEAD, ACME_BANKING, urlBody("January sign-off")));
            create(LEAD, ACME_BANKING, replacing(urlBody("January sign-off corrected"), replaced));

            assertThat(auditChanges(ACME, "ARTIFACT_SUPERSEDED"))
                    .as("\"what happened to the January sign-off\" is unanswerable from a row "
                            + "that only says its status changed")
                    .hasSize(1)
                    .allSatisfy(changes -> assertThat(changes)
                            .contains("January sign-off")
                            .contains("January sign-off corrected"));
        }
    }

    /**
     * Archiving and restoring — {@code ARCHIVED}'s only writer, and the qualifier read against the
     * <strong>row's</strong> scope rather than the request's.
     */
    @Nested
    @DisplayName("Archiving — the contributor's key, read against the row")
    class Circulation {

        @Test
        @DisplayName("a Test Lead archives an artifact and restores it")
        void archivesAndRestores() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Old environment guide")));

            MvcResult archived = setStatus(LEAD, ACME_BANKING, id, "ARCHIVED");
            assertThat(archived.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(archived).get("status").asText()).isEqualTo("ARCHIVED");

            assertThat(body(setStatus(LEAD, ACME_BANKING, id, "ACTIVE")).get("status").asText())
                    .as("nothing outside the row asserts ARCHIVED, so restoring is the same act "
                            + "back — unlike SUPERSEDED, which another row is holding")
                    .isEqualTo("ACTIVE");
        }

        /**
         * <strong>THE gate assertion of this class.</strong> One caller, one key, two rows — and
         * the difference is the row's own scope. Gate this on the request instead of the row and
         * the engineer archives the project's test strategy by not mentioning its scope.
         */
        @Test
        @DisplayName("a Test Engineer archives at either scope since V52 — the qualifier is gone")
        void theQualifierIsReadAgainstTheRow() throws Exception {
            // Inverted by V52 (§210). The name is kept because the MECHANISM is unchanged and is
            // what the next reader needs: the qualifier is read against the ROW, not the request,
            // so gating this on the request instead would still be the defect the day a migration
            // re-qualifies artifact:edit. Today both calls are 200 and neither can prove that.
            UUID cycleDoc = createdId(create(ENGINEER, ACME_BANKING, planBody("Cycle snapshot")));
            UUID strategy = createdId(create(LEAD, ACME_BANKING, urlBody("Test strategy")));

            assertThat(setStatus(ENGINEER, ACME_BANKING, cycleDoc, "ARCHIVED")
                    .getResponse().getStatus())
                    .as("a Test Engineer curates their own cycle — unchanged")
                    .isEqualTo(200);

            assertThat(setStatus(ENGINEER, ACME_BANKING, strategy, "ARCHIVED")
                    .getResponse().getStatus())
                    .as("and the project's shared document too, which is the V52 widening")
                    .isEqualTo(200);
            assertThat(row(ACME_BANKING, strategy).get("status").asText()).isEqualTo("ARCHIVED");
        }

        @Test
        @DisplayName("setting the status it already has is agreement, not a conflict")
        void idempotent() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Guide")));
            setStatus(LEAD, ACME_BANKING, id, "ARCHIVED");

            assertThat(setStatus(LEAD, ACME_BANKING, id, "ARCHIVED").getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(auditChanges(ACME, "ARTIFACT_STATUS_CHANGED"))
                    .as("an audit row per repeated click buries the change that happened")
                    .hasSize(1);
        }

        @Test
        @DisplayName("SUPERSEDED is not a value the endpoint takes")
        void supersededCannotBeSent() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Guide")));

            MvcResult refused = setStatus(LEAD, ACME_BANKING, id, "SUPERSEDED");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).contains("status");
            assertThat(row(ACME_BANKING, id).get("status").asText()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("a superseded artifact cannot be archived — 409, and not about the caller")
        void supersededCannotBeLeft() throws Exception {
            UUID replaced = createdId(create(LEAD, ACME_BANKING, urlBody("Sign-off v1")));
            create(LEAD, ACME_BANKING, replacing(urlBody("Sign-off v2"), replaced));

            MvcResult refused = setStatus(LEAD, ACME_BANKING, replaced, "ARCHIVED");

            assertThat(refused.getResponse().getStatus())
                    .as("the caller holds the strongest key in the module; it is the state that "
                            + "refuses, so an administrator cannot help and a 403 would lie")
                    .isEqualTo(409);
            assertThat(body(refused).get("detail").asText()).contains("replaced this one");
        }

        @Test
        @DisplayName("a Viewer is refused with no body — they hold artifact:edit at no scope")
        void theViewerIsRefused() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Guide")));

            MvcResult refused = setStatus(VIEWER, ACME_BANKING, id, "ARCHIVED");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("another project's artifact is a 404 through a project the caller can write")
        void anotherProjectsArtifactIsNotReachable() throws Exception {
            UUID elsewhere = UUID.fromString("af005000-0000-4000-8000-00000000000a");
            insertArtifact(elsewhere, ACME, ACME_LOCKED, "Locked project's guide");

            assertThat(setStatus(LEAD, ACME_BANKING, elsewhere, "ARCHIVED")
                    .getResponse().getStatus())
                    .as("the path segment must select the row, not merely authorise the call")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the move is audited with the state it left")
        void theMoveIsAudited() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Guide")));

            setStatus(LEAD, ACME_BANKING, id, "ARCHIVED");

            List<String> recorded = auditChanges(ACME, "ARTIFACT_STATUS_CHANGED");
            assertThat(recorded).hasSize(1);
            // Read as JSON rather than as a substring: the column is jsonb, so Postgres hands the
            // object back reformatted and a literal `"from":"ACTIVE"` never matches.
            JsonNode changes = json.readTree(recorded.get(0));
            assertThat(changes.get("from").asText())
                    .as("a status event without the state it left says nothing")
                    .isEqualTo("ACTIVE");
            assertThat(changes.get("to").asText()).isEqualTo("ARCHIVED");
        }
    }

    @Nested
    @DisplayName("Deleting an artifact — the key that is not artifact:edit")
    class Deletion {

        @Test
        @DisplayName("a Test Lead unregisters a mis-registered link, and the row is GONE")
        void theLeadDeletes() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Wrong link")));

            MvcResult removed = delete(LEAD, ACME_BANKING, id);

            assertThat(removed.getResponse().getStatus()).isEqualTo(204);
            assertThat(removed.getResponse().getContentAsString())
                    .as("204 carries no body — there is no row left to render")
                    .isEmpty();
            assertThat(countArtifacts(ACME))
                    .as("artifact has no deleted_at, unlike case and suite; the schema's own "
                            + "answer is that the row is gone, and hiding it instead would be a "
                            + "second lifecycle nobody asked for")
                    .isZero();
            assertThat(titlesOf(list(LEAD, ACME_BANKING, null, null))).isEmpty();
        }

        /**
         * <strong>THE gate assertion.</strong> The engineer created this artifact one line earlier —
         * they hold {@code artifact:edit} at plan scope and may register — and they may not remove
         * it. Swapping the service's constant to {@code artifact:edit} turns this 403 into a 204.
         */
        @Test
        @DisplayName("a Test Engineer deletes what they may create since V52")
        void theEngineerDeletesWhatTheyMayCreate() throws Exception {
            // ~~creative versus destructive, roles spec §A~~ → V52 (§210) granted artifact:delete to
            // Test Engineer and DELETED that principle rather than narrowing it a third time. The
            // key stays separate from artifact:edit (a company may separate them; removing a
            // register entry is its own audit event) — but no seeded role now holds one without the
            // other, so nothing here proves the route asks the right one. §210 DQ-5.
            UUID id = createdId(create(ENGINEER, ACME_BANKING, planBody("Engineer's own row")));

            assertThat(delete(ENGINEER, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(204);
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("a Viewer is refused too, and neither 403 carries a body")
        void theViewerIsRefused() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Anything")));

            MvcResult refused = delete(VIEWER, ACME_BANKING, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).isEmpty();
        }

        /**
         * <strong>The write-once guard, made reachable by the fixture.</strong>
         *
         * <p>{@code artifact.write_once} has had a comment since {@code V1} — "sign-off records:
         * corrections supersede, never edit" — and no enforcement anywhere, and nothing in the
         * product sets it {@code true} yet. So this test flips the column directly, which is the
         * only way the guard can be observed today.
         *
         * <p>Writing the check before its writer is deliberate and it is the opposite call from
         * §121 phase 3's deleted seam. A seam guesses at a consumer that may not want it; a
         * <em>refusal</em> guesses at nothing — it is the column's documented meaning, enforced. The
         * alternative is the {@code Company.suspend()} shape the plan recorded two days ago: a
         * guarantee written in a comment, believed by everyone, and doing nothing.
         */
        @Test
        @DisplayName("a write-once sign-off record is refused with 409, and survives")
        void aSignOffRecordCannotBeDeleted() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Release 1 sign-off")));
            setWriteOnce(id);

            MvcResult refused = delete(LEAD, ACME_BANKING, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText())
                    .as("names the way forward — a 409 that only says no leaves the lead with "
                            + "no next action, and superseding is the one they want")
                    .contains("Supersede");
            assertThat(countArtifacts(ACME)).isEqualTo(1);
        }

        /**
         * The inbound edge {@code Artifact}'s javadoc said did not exist — see
         * {@code ArtifactRepository.isSupersedingSomething}. Without the check this is a foreign-key
         * violation surfacing as a 500.
         *
         * <p>~~The state is produced by raw SQL, which is the only way to reach it.~~ <strong>→ by
         * the register's own act since §209.</strong> The guard was written before its writer
         * existed — the same call the write-once row makes — and this is the round where the
         * fixture stops being a stand-in for a product that could not do it.
         */
        @Test
        @DisplayName("an artifact that superseded another is refused with 409, not a 500")
        void theReplacementCannotBeDeleted() throws Exception {
            UUID replaced = createdId(create(LEAD, ACME_BANKING, urlBody("Old sign-off")));
            UUID replacement = createdId(create(
                    LEAD, ACME_BANKING, replacing(urlBody("Corrected sign-off"), replaced)));

            MvcResult refused = delete(LEAD, ACME_BANKING, replacement);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText()).contains("pointing at nothing");

            assertThat(delete(LEAD, ACME_BANKING, replaced).getResponse().getStatus())
                    .as("the OTHER end of the same edge is deletable — the guard is about the "
                            + "direction of the pointer, not about superseding in general")
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("the removal is audited with the row's content, since the row will be gone")
        void theRemovalIsAudited() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Compliance evidence")));

            assertThat(delete(LEAD, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(204);

            assertThat(auditChanges(ACME, "ARTIFACT_REMOVED"))
                    .as("a target id alone names something an auditor cannot look up — this is "
                            + "the one case where the event must carry the content")
                    .hasSize(1)
                    .allSatisfy(changes -> assertThat(changes)
                            .contains("Compliance evidence")
                            .contains("https://"));
        }

        /**
         * <strong>The sharpest of the reachability rows, and the reason the repository query takes
         * a {@code projectId}.</strong>
         *
         * <p>Everything lines up for this to succeed by accident: the row is in the caller's own
         * company so RLS admits it, the caller is a Test Lead who holds {@code artifact:delete}, and
         * the project in the path is one they are fully granted on — so the gate passes. Only the
         * predicate on {@code project_id} stops the delete. A {@code findById} here would remove an
         * artifact from a project the caller was never gated on, through a path that never named it.
         *
         * <p>Seeded by SQL because {@code ACME_LOCKED} is granted to nobody in this class: the API
         * cannot put a row there, which is what makes it the right home for this fixture.
         */
        @Test
        @DisplayName("a sibling project's artifact is 404 through this project's path, not 403")
        void anotherProjectsArtifactIsNotReachable() throws Exception {
            UUID locked = UUID.fromString("af009000-0000-4000-8000-000000000001");
            insertArtifact(locked, ACME, ACME_LOCKED, "Internal doc");

            assertThat(delete(LEAD, ACME_BANKING, locked).getResponse().getStatus())
                    .as("the caller holds the strongest key in this module and is gated on the "
                            + "project they named; neither entitles them to a row in another one")
                    .isEqualTo(404);
            assertThat(countArtifacts(ACME))
                    .as("the sibling project's register is untouched")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an id that never existed is the same 404")
        void anAbsentArtifactIs404() throws Exception {
            assertThat(delete(LEAD, ACME_BANKING,
                    UUID.fromString("afdead00-0000-4000-8000-00000000dead"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another company's artifact is 404, and RLS is the backstop under the check")
        void anotherCompanyIs404() throws Exception {
            UUID theirs = createdId(create(OUTSIDER, UMBRA_BANKING, urlBody("Umbra's doc")));

            assertThat(delete(LEAD, UMBRA_BANKING, theirs).getResponse().getStatus()).isEqualTo(404);
            assertThat(countArtifacts(UMBRA))
                    .as("the row is untouched in the company that owns it")
                    .isEqualTo(1);
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private MvcResult delete(UUID userId, UUID projectId, UUID artifactId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/artifacts/" + artifactId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andReturn();
    }

    private MvcResult create(UUID userId, UUID projectId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/artifacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /**
     * {@code GET /me/permissions} for one caller on one project, reduced to key → qualifier.
     *
     * <p>Reads the wire rather than the evaluator on purpose: {@code PermissionEvaluatorTest}
     * already pins what the qualifier resolves to, and the thing this class cares about is whether
     * it survives serialization to the client that has to act on it.
     */
    private Map<String, String> heldPermissions(UUID userId, UUID projectId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/me/permissions")
                        .param("projectId", projectId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        Map<String, String> held = new LinkedHashMap<>();
        json.readTree(result.getResponse().getContentAsString())
                .get("permissions")
                .forEach(entry -> held.put(
                        entry.get("key").asText(), entry.get("qualifier").asText()));
        return held;
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

    /** A project-scope external link — the ordinary registration these tests delete. */
    private String urlBody(String title) {
        return link("PROJECT", null, "Compliance", title,
                "https://docs.example.test/" + title.toLowerCase(Locale.ROOT).replace(' ', '-'));
    }

    /** A plan-scope one, which is the only scope a Test Engineer may register at. */
    private String planBody(String title) {
        return link("PLAN", BANKING_PLAN, "Cycle", title, "https://docs.example.test/plan-doc");
    }

    /** The same registration, replacing {@code supersedes}. Spliced in rather than re-spelled. */
    private String replacing(String body, UUID supersedes) {
        return body.substring(0, body.length() - 1) + ",\"supersedes\":\"" + supersedes + "\"}";
    }

    private MvcResult setStatus(UUID userId, UUID projectId, UUID artifactId, String status)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/artifacts/" + artifactId
                                + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":" + quote(status) + "}"))
                .andReturn();
    }

    /** One row of the register, read back through the list the SPA reads. */
    private JsonNode row(UUID projectId, UUID artifactId) throws Exception {
        return list(LEAD, projectId, null, null).stream()
                .filter(candidate -> candidate.get("id").asText().equals(artifactId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such row in the register: " + artifactId));
    }

    private UUID createdId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("the fixture's own create must succeed, or the assertion under test is "
                        + "measuring the wrong failure")
                .isEqualTo(201);
        return UUID.fromString(body(result).get("id").asText());
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

    /** A PROJECT-scope external link, straight into the table — for projects the API cannot reach. */
    private void insertArtifact(UUID id, UUID companyId, UUID projectId, String title)
            throws SQLException {

        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("""
                     INSERT INTO artifact (id, company_id, scope, project_id, category, kind,
                                           title, status, external_url)
                     VALUES (?, ?, 'PROJECT', ?, 'Compliance', 'EXTERNAL_URL', ?, 'ACTIVE',
                             'https://docs.example.test/internal')
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, companyId);
            statement.setObject(3, projectId);
            statement.setString(4, title);
            statement.executeUpdate();
        }
    }

    /**
     * Flips {@code write_once}, which nothing in the product can set.
     *
     * <p>Raw SQL is the only way to reach it and that is the point of the test it serves: the
     * column has carried its meaning in a {@code V1} comment since M0, and the guard that honours
     * it is unreachable until something writes it. A fixture is what makes an early guard
     * assertable instead of aspirational.
     */
    private void setWriteOnce(UUID artifactId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE artifact SET write_once = true WHERE id = ?")) {
            statement.setObject(1, artifactId);
            statement.executeUpdate();
        }
    }

    private List<String> auditChanges(UUID companyId, String action) throws SQLException {
        List<String> details = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT changes FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    details.add(rows.getString(1));
                }
            }
        }
        return details;
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
