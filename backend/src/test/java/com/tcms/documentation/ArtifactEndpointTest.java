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
 *   <li><strong>The scope qualifier ignored.</strong> <del>The one that matters, and the reason the
 *       slice touched the authorization core: gate the create on {@code artifact:edit} alone and
 *       only {@code engineerCannotRegisterAtProjectScope} goes red, because a Test Engineer
 *       legitimately registers plan-scope artifacts and a Test Lead legitimately registers
 *       both.</del> → <strong>{@code V52} (§210) de-qualified {@code artifact:edit}, and that
 *       assertion is now {@link Qualifier#engineerRegistersAtProjectScope} — the same call, the
 *       opposite expectation.</strong> This class can no longer catch a qualifier ignored, because
 *       no seeded grant carries one; what it catches instead is the widening being <em>undone</em>,
 *       which would refuse a contributor the project document every cycle inherits (plan §6n) with
 *       nothing about the response looking wrong.</li>
 *   <li><strong>The gate over-applied.</strong> The mirror failure and the more likely one to
 *       ship, because it looks like caution: refuse a Test Engineer and the register is
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
     * Test Engineer. <del>Holds {@code artifact:edit} PLAN_SCOPE — the one persona the whole slice
     * turns on, and the reason V19 exists.</del> → <strong>{@code V52} (§210): unqualified, both
     * scopes.</strong> {@code artifact:edit} was the only key in the catalogue that ever carried
     * {@code PLAN_SCOPE}, so this persona no longer separates the scopes from a Test Lead — and the
     * qualifier {@code V19} added is now carried by no row at all (roles spec §A5).
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
        @DisplayName("a Test Lead registers at PROJECT scope too — the scope gates nobody now")
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
        @DisplayName("an Engineer READS the project-scope artifact — the read never carried a scope")
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

    /**
     * B4 — a retried registration must not make a twin ({@code V59}).
     *
     * <h2>Why the register was the worst of the eight tables to be missing it</h2>
     *
     * <p>Not because a duplicate row is expensive — it is a register entry, and {@code DELETE} has
     * shipped since §126. It is that the sibling operation writes <strong>bytes</strong>: a retried
     * upload duplicated the {@code attachment} row and the blob as well as the artifact, and the
     * blob is the copy nobody sees. {@code ArtifactFileEndpointTest} drives that half; this one
     * drives the reference create, where the assertion that matters is the row count.
     */
    @Nested
    @DisplayName("Idempotency — a retry must not register a second artifact")
    class Idempotency {

        @Test
        @DisplayName("a repeated key answers 200 with the SAME artifact, and registers nothing")
        void aRetryReplaysTheOriginal() throws Exception {
            String key = "intent-" + UUID.randomUUID();

            MvcResult first = create(LEAD, ACME_BANKING, urlBody("Test strategy"), key);
            MvcResult retry = create(LEAD, ACME_BANKING, urlBody("Test strategy"), key);

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("id").asText())
                    .isEqualTo(body(first).get("id").asText());
            assertThat(countArtifacts(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("a replay answers the row as it stands, not the body that created it")
        void aReplayRendersTheLiveRow() throws Exception {
            // The whole reason this family stores a KEY and not a stored response (V17, V42): the
            // artifact may have been amended since, and a replay that served the original response
            // would hand back a title the register no longer shows.
            String key = "intent-" + UUID.randomUUID();
            MvcResult first = create(LEAD, ACME_BANKING, urlBody("Test strategy"), key);
            UUID artifactId = UUID.fromString(body(first).get("id").asText());

            MvcResult amended = edit(LEAD, ACME_BANKING, artifactId,
                    "Test strategy (v2)", "Compliance", null);
            assertThat(amended.getResponse().getStatus()).isEqualTo(200);

            MvcResult retry = create(LEAD, ACME_BANKING, urlBody("Test strategy"), key);

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("title").asText()).isEqualTo("Test strategy (v2)");
        }

        @Test
        @DisplayName("a different key is a different artifact — the header is not a dedupe on content")
        void adifferentKeyRegistersAgain() throws Exception {
            // The guard. Without it, an implementation that deduplicated on the payload would pass
            // the test above and refuse a genuine second registration of the same document.
            create(LEAD, ACME_BANKING, urlBody("Test strategy"), "intent-" + UUID.randomUUID());
            create(LEAD, ACME_BANKING, urlBody("Test strategy"), "intent-" + UUID.randomUUID());

            assertThat(countArtifacts(ACME)).isEqualTo(2);
        }

        @Test
        @DisplayName("no key at all still registers — the header is optional, as it is everywhere")
        void noKeyIsNotADedupe() throws Exception {
            create(LEAD, ACME_BANKING, urlBody("Test strategy"), null);
            create(LEAD, ACME_BANKING, urlBody("Test strategy"), null);

            // NULLs are not comparable, which is what makes the partial unique index permit these
            // two — and is why the index is partial rather than plain.
            assertThat(countArtifacts(ACME)).isEqualTo(2);
        }

        @Test
        @DisplayName("a used key in a project the caller cannot see is a 404, never a replay")
        void theReplayIsInsideTheGate() throws Exception {
            // The lookup runs INSIDE the project gate, which is what keeps it from being an oracle:
            // a company-wide lookup placed before the gate would answer a caller with a row from a
            // project they hold no grant on, and the header is a value they choose.
            //
            // What this does NOT prove is the other half — that the same key is usable again in a
            // second project — because this fixture has no second project LEAD is granted on. The
            // index is (company_id, project_id, key) and `findByIdempotencyKey` names exactly those
            // columns; that pairing is asserted by SchemaConventionTest and by reading, not here.
            String key = "intent-" + UUID.randomUUID();

            assertThat(create(LEAD, ACME_BANKING, urlBody("Shared name"), key)
                    .getResponse().getStatus()).isEqualTo(201);
            assertThat(create(LEAD, ACME_LOCKED, urlBody("Shared name"), key)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("an absurd key is a field error, not an indexed 10 MB header")
        void anOversizedKeyIsRefused() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING, urlBody("Test strategy"),
                    "k".repeat(201));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result).get("errors").has("Idempotency-Key")).isTrue();
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("a blank key is no key — it must not reserve the empty string")
        void aBlankKeyIsNotAKey() throws Exception {
            // A client template rendering an empty header would otherwise register the first
            // artifact under "" and replay it for every unkeyed request afterwards.
            create(LEAD, ACME_BANKING, urlBody("First"), "   ");
            create(LEAD, ACME_BANKING, urlBody("Second"), "");

            assertThat(countArtifacts(ACME)).isEqualTo(2);
        }
    }

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

    /**
     * <strong>Pinning a generated report — audit C6, and the last of {@code artifact}'s payload
     * columns to get a writer.</strong>
     *
     * <p>{@code V1} declared {@code report_artifact_id} and {@link ArtifactKind#GENERATED_REPORT}
     * together, {@code reporting} has produced artifacts since §87, and the register went on
     * answering "not available yet" for two milestones — the sixth time in this project a "blocked"
     * note has outlived its blocker.
     *
     * <p><strong>The request names the JOB and the row stores the ARTIFACT</strong>, which is the
     * one design point worth carrying out of this class. {@code report_artifact} has no
     * {@code project_id} ({@code ReportArtifact}'s header says so, and the reporting module's own
     * download route already routes through the job), so an artifact id alone cannot be proved to
     * belong to the project in the path — while the job id can. What is stored is the artifact
     * because that row is immutable, so the register's entry answers the same bytes for ever;
     * regenerating produces a new job, a new artifact, and does not touch this row.
     */
    @Nested
    @DisplayName("Pinned reports — the job proves the project, the artifact is what is kept")
    class PinnedReports {

        @Test
        @DisplayName("a completed report is pinned, and the row points at the report's own route")
        void aCompletedReportIsPinnable() throws Exception {
            UUID job = completedReportJob(ACME, ACME_BANKING);

            MvcResult result = create(LEAD, ACME_BANKING, pinnedReport("Release 1 numbers", job));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode registered = body(result);
            assertThat(registered.get("kind").asText()).isEqualTo("GENERATED_REPORT");
            // The reports download, not the register's own /content — and the difference is a
            // permission: that route costs report:export, so pinning a report records that it was
            // produced without re-licensing carrying it off.
            assertThat(registered.get("url").asText())
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/reports/artifacts/"
                            + reportArtifactOf(job) + "/content");
        }

        @Test
        @DisplayName("a job that rendered nothing is refused — there are no bytes to pin")
        void anUnfinishedReportIsRefused() throws Exception {
            UUID job = pendingReportJob(ACME, ACME_BANKING);

            MvcResult refused = create(LEAD, ACME_BANKING, pinnedReport("Too early", job));

            // A register entry claiming a document it cannot produce is plan §6n's own failure
            // mode, and this is the arm a real caller meets: a report that failed or is still
            // running looks exactly like one that finished until you read `artifact_id`.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("reportJobId");
            assertThat(countArtifacts(ACME)).isZero();
        }

        @Test
        @DisplayName("another project's completed report is the SAME refusal, not a distinct one")
        void aForeignProjectsReportIsRefused() throws Exception {
            UUID job = completedReportJob(ACME, ACME_LOCKED);

            MvcResult refused = create(LEAD, ACME_BANKING, pinnedReport("Somebody else's", job));

            // §6t: a distinct message would let a caller probe which job ids are real in a project
            // they cannot see. Asserted as the same field AND the same sentence as the arm above,
            // because "same refusal" is the property and two messages would satisfy the field
            // check alone.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("reportJobId");
            assertThat(body(refused).get("errors").get("reportJobId").get(0).asText())
                    .isEqualTo("That report is not one this project has finished generating.");
        }

        @Test
        @DisplayName("GENERATED_REPORT with no job is a field error, not a row pointing nowhere")
        void thePinNeedsAJob() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING, pinnedReport("Nothing in mind", null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("reportJobId");
        }

        @Test
        @DisplayName("a job id on a kind that is not a pin is refused, never quietly dropped")
        void anotherKindMayNotCarryAJob() throws Exception {
            UUID job = completedReportJob(ACME, ACME_BANKING);

            MvcResult refused = create(LEAD, ACME_BANKING,
                    replacingLast(urlBody("An ordinary link"), "\"reportJobId\":\"" + job + "\""));

            // validateScope's rule, over a fourth field: a client sending a job id believes the
            // entry points at that report, and storing a row that does not is the silent
            // disagreement §50 built two checks to catch.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("reportJobId");
        }

        @Test
        @DisplayName("a Viewer may not pin one — the gate is the register's, not the report's")
        void theGateIsTheRegisters() throws Exception {
            UUID job = completedReportJob(ACME, ACME_BANKING);

            MvcResult refused = create(VIEWER, ACME_BANKING, pinnedReport("Numbers", job));

            // A Viewer holds report:view and report:export and still writes nothing here. Pinning
            // is a registration, so it costs artifact:edit — inventing a report-shaped key for it
            // would be a rule the roles spec does not have.
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(countArtifacts(ACME)).isZero();
        }
    }

    /**
     * <strong>Sign-off records — audit C7, the column {@code V1} declared and nothing could
     * set.</strong>
     *
     * <p>{@code write_once} has been honoured by the delete since §126 and by the edit since §229,
     * against a value only {@code psql} could produce; both of those tests used to flip it in SQL
     * and now register one. What is new here is the flag's own behaviour: it is decided at
     * registration, it cannot be added later or taken back, and the two acts it does <em>not</em>
     * refuse are as deliberate as the two it does.
     */
    @Nested
    @DisplayName("Sign-off records — decided at registration, and never after")
    class SignOffRecords {

        @Test
        @DisplayName("a sign-off registration says so on the wire, so the client can grey both acts")
        void theFlagIsServed() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, signOff("March sign-off")));

            // §31's disable-don't-hide, and the reason this reaches the wire at all: the column has
            // been enforced by two refusals a client could not predict, so Correct… and Remove…
            // enabled on every sign-off row and the 409 arrived after the correction was written.
            assertThat(row(ACME_BANKING, id).get("writeOnce").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an ordinary registration is not one — absent means false, never null")
        void absentMeansFalse() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            JsonNode registered = row(ACME_BANKING, id);
            assertThat(registered.get("writeOnce").isNull())
                    .as("required-and-not-nullable: a client must not have to tell a missing "
                            + "field from a false one before deciding whether to offer Correct…")
                    .isFalse();
            assertThat(registered.get("writeOnce").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("an edit cannot make one — the promise is made at registration or not at all")
        void anEditCannotSetIt() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            MvcResult corrected = mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/projects/" + ACME_BANKING + "/artifacts/" + id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(LEAD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"Environment guide","category":"Compliance",\
                                    "description":null,"writeOnce":true}"""))
                    .andReturn();

            // Two layers say no and neither is a filter in the service: ArtifactEditInput has no
            // such component, and the column is updatable = false. Asserted from outside because
            // "the record has three components" is not visible to a reader of the endpoint — and a
            // flag that can be set later can be set on somebody else's row to freeze it.
            assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
            assertThat(row(ACME_BANKING, id).get("writeOnce").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a sign-off is still archivable — circulation is not content")
        void archivingIsStillAllowed() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, signOff("Retired sign-off")));

            assertThat(setStatus(LEAD, ACME_BANKING, id, "ARCHIVED").getResponse().getStatus())
                    .as("a signed record that is no longer in use is exactly what ARCHIVED says, "
                            + "and refusing it would leave the register no way to retire one")
                    .isEqualTo(200);
            assertThat(row(ACME_BANKING, id).get("status").asText()).isEqualTo("ARCHIVED");
        }

        @Test
        @DisplayName("a sign-off is still supersedable — that is the correction path it forces")
        void supersedingIsStillAllowed() throws Exception {
            UUID signed = createdId(create(LEAD, ACME_BANKING, signOff("April sign-off")));

            UUID replacement = createdId(create(LEAD, ACME_BANKING,
                    replacing(signOff("April sign-off (corrected)"), signed)));

            // The whole point of the flag rather than an accident of where the guard sits: edits
            // and deletes are refused precisely so a correction leaves BOTH documents and the fact
            // that one replaced the other. A write-once row that could not be superseded would be
            // a record nobody could ever correct.
            assertThat(row(ACME_BANKING, signed).get("status").asText()).isEqualTo("SUPERSEDED");
            assertThat(row(ACME_BANKING, signed).get("supersededById").asText())
                    .isEqualTo(replacement.toString());
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
         * corrections supersede, never edit" — and <del>no enforcement anywhere, and nothing in the
         * product sets it {@code true} yet. So this test flips the column directly, which is the
         * only way the guard can be observed today.</del> → <strong>registration sets it since
         * audit C7</strong>, so the row here is made the way a lead makes one and the SQL fixture
         * is gone.
         *
         * <p>Writing the check before its writer is deliberate and it is the opposite call from
         * §121 phase 3's deleted seam. A seam guesses at a consumer that may not want it; a
         * <em>refusal</em> guesses at nothing — it is the column's documented meaning, enforced. The
         * alternative is the {@code Company.suspend()} shape the plan recorded two days ago: a
         * guarantee written in a comment, believed by everyone, and doing nothing. Three slices
         * later the writer arrived and the refusal did not have to move, which is the argument
         * paying out rather than merely being made.
         */
        @Test
        @DisplayName("a write-once sign-off record is refused with 409, and survives")
        void aSignOffRecordCannotBeDeleted() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, signOff("Release 1 sign-off")));

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
    // A6 — the register's first edit path
    // =====================================================================

    /**
     * {@code PATCH …/artifacts/{artifactId}} — <strong>correcting a row, which nothing could
     * do</strong>.
     *
     * <h2>The failures this class exists to catch</h2>
     *
     * <ol>
     *   <li><strong>The gate read from the request instead of the row.</strong> There is no scope in
     *       an edit body at all, so the mistake here is subtler than the status endpoint's: gating
     *       on the plain key rather than {@code writeGrantedByRowScope}. Nothing carries
     *       {@code PLAN_SCOPE} since {@code V52}, so the seeded personas cannot tell the two apart —
     *       {@link #aPlanScopedGrantCannotCorrectTheProjects} grants the qualifier by hand for
     *       exactly that reason, which is the only way this suite can see the seam the roles spec
     *       §A5 says must stay wired.</li>
     *   <li><strong>An edit reaching a column the entity forbids.</strong> The scope, the kind and
     *       the payload survive a body that names them — not because the service filters them, but
     *       because {@link ArtifactEditInput} has no field for them and the columns are
     *       {@code updatable = false}. Asserted from outside, because "the record has three
     *       components" is not something a reader of the endpoint can see.</li>
     *   <li><strong>{@code write_once} honoured on delete and forgotten here.</strong> The column's
     *       stated meaning is that corrections supersede and never overwrite, which is a rule about
     *       editing before it is one about deleting, and the delete has enforced it since §126.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Correcting — the three editable fields, and the columns that are not")
    class Correcting {

        @Test
        @DisplayName("a lead corrects the title, category and description in one request")
        void correctsTheThreeFields() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            MvcResult corrected = edit(LEAD, ACME_BANKING, id,
                    "Environment guide (staging)", "Environment", "Now covers staging too.");

            assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
            JsonNode after = row(ACME_BANKING, id);
            assertThat(after.get("title").asText()).isEqualTo("Environment guide (staging)");
            assertThat(after.get("category").asText()).isEqualTo("Environment");
            assertThat(after.get("description").asText()).isEqualTo("Now covers staging too.");
        }

        @Test
        @DisplayName("a null description clears it — the one clearable field")
        void nullClearsTheDescription() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));
            edit(LEAD, ACME_BANKING, id, "Environment guide", "Compliance", "A note.");

            edit(LEAD, ACME_BANKING, id, "Environment guide", "Compliance", null);

            assertThat(row(ACME_BANKING, id).get("description").isNull()).isTrue();
        }

        @Test
        @DisplayName("the scope, the kind and the payload survive a body that names them")
        void theUneditableColumnsAreUntouched() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));
            JsonNode before = row(ACME_BANKING, id);

            // A body carrying every field the create takes. The record binds three of them and the
            // columns are updatable = false, so the extras are neither applied nor refused —
            // asserted from outside, because that is a property of the entity rather than of any
            // line in the service.
            MvcResult corrected = mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/projects/" + ACME_BANKING + "/artifacts/" + id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(LEAD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Renamed\",\"category\":\"Tooling\","
                                    + "\"scope\":\"PLAN\",\"planId\":\"" + BANKING_PLAN + "\","
                                    + "\"kind\":\"DOC_PAGE_REF\","
                                    + "\"url\":\"https://elsewhere.example.test/\"}"))
                    .andReturn();

            assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
            JsonNode after = row(ACME_BANKING, id);
            assertThat(after.get("title").asText()).isEqualTo("Renamed");
            assertThat(after.get("scope").asText()).isEqualTo(before.get("scope").asText());
            assertThat(after.get("kind").asText()).isEqualTo(before.get("kind").asText());
            assertThat(after.get("url").asText()).isEqualTo(before.get("url").asText());
        }

        @Test
        @DisplayName("a blank title is a 400 on the field, not a cleared NOT NULL column")
        void aBlankTitleIsRefused() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            MvcResult refused = edit(LEAD, ACME_BANKING, id, "  ", "Compliance", null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).contains("title");
            assertThat(row(ACME_BANKING, id).get("title").asText()).isEqualTo("Environment guide");
        }

        @Test
        @DisplayName("an omitted title is the same 400 — §68 makes absent and null one value")
        void anOmittedTitleIsRefused() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            MvcResult refused = mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/projects/" + ACME_BANKING + "/artifacts/" + id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(LEAD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\":\"Tooling\"}"))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).contains("title");
        }

        @Test
        @DisplayName("a SUPERSEDED row is still correctable — being replaced is not being frozen")
        void aSupersededRowIsEditable() throws Exception {
            UUID original = createdId(create(LEAD, ACME_BANKING, urlBody("Sign-off")));
            createdId(create(LEAD, ACME_BANKING,
                    replacing(urlBody("Sign-off v2"), original)));

            MvcResult corrected =
                    edit(LEAD, ACME_BANKING, original, "Sign-off (superseded)", "SignOff", null);

            // The status endpoint refuses this row because a STATUS is a fact about another row.
            // A title is not, and a register that cannot fix a typo in what it says was replaced
            // is the dead end this operation exists to close.
            assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
            assertThat(row(ACME_BANKING, original).get("title").asText())
                    .isEqualTo("Sign-off (superseded)");
            assertThat(row(ACME_BANKING, original).get("status").asText()).isEqualTo("SUPERSEDED");
        }

        @Test
        @DisplayName("a write-once artifact is refused with 409 and told to supersede instead")
        void aWriteOnceRowIsRefused() throws Exception {
            // ~~Nothing in the product sets this column, which is exactly why the guard needs a
            // fixture~~ → audit C7 gave it one, so the row is registered as a sign-off through the
            // endpoint a caller uses. The guard is unchanged; what changed is that the state it
            // refuses is now one the product can reach, which is what turns an enforced comment
            // into an enforced behaviour.
            UUID id = createdId(create(LEAD, ACME_BANKING, signOff("January sign-off")));

            MvcResult refused = edit(LEAD, ACME_BANKING, id, "January sign-off v2", "SignOff", null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText()).contains("Supersede");
            assertThat(row(ACME_BANKING, id).get("title").asText()).isEqualTo("January sign-off");
        }

        @Test
        @DisplayName("a Viewer is refused, bodiless — they hold no artifact:edit at all")
        void aViewerIsRefused() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            MvcResult refused = edit(VIEWER, ACME_BANKING, id, "Renamed", "Tooling", null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("a PLAN_SCOPE grant corrects a plan's row and not the project's — 403 with a field")
        void aPlanScopedGrantCannotCorrectTheProjects() throws Exception {
            UUID projectRow = createdId(create(LEAD, ACME_BANKING, urlBody("Test strategy")));
            UUID planRow = createdId(create(LEAD, ACME_BANKING, planBody("Cycle snapshot")));
            // Hand-qualified: V52 emptied the column, so no seeded persona can see this seam any
            // more — and roles spec §A5 keeps the mechanism precisely so a future migration gets
            // the narrowing back rather than having to rebuild a gate that would fail OPEN.
            qualifyEngineersArtifactEdit("PLAN_SCOPE");
            try {
                assertThat(edit(ENGINEER, ACME_BANKING, planRow, "Cycle snapshot v2", "Cycle", null)
                        .getResponse().getStatus())
                        .isEqualTo(200);

                MvcResult refused =
                        edit(ENGINEER, ACME_BANKING, projectRow, "Renamed", "Compliance", null);
                assertThat(refused.getResponse().getStatus()).isEqualTo(403);
                assertThat(errorFields(refused))
                        .as("the form puts the sentence on the scope, which is the one thing the "
                                + "caller cannot change about this request")
                        .contains("scope");
            } finally {
                // `role_permission` is INSTANCE-level: no company_id, seeded once by V3, shared by
                // every test in the container. Leaving PLAN_SCOPE behind reddened three tests in
                // two other nested classes on the first run, all of them asserting that V52
                // emptied this exact column — so the restore is the price of driving the seam at
                // all, and it is a `finally` rather than an @AfterEach so the pair is one thing a
                // reader sees at once.
                qualifyEngineersArtifactEdit("ALL");
            }
        }

        @Test
        @DisplayName("a correction that changes nothing writes no audit row")
        void anUnchangedCorrectionIsSilent() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            assertThat(edit(LEAD, ACME_BANKING, id, "Environment guide", "Compliance", null)
                    .getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(amendmentCount(ACME))
                    .as("two people saving the same correction are agreeing, not conflicting")
                    .isZero();
        }

        @Test
        @DisplayName("a real correction writes one audit row carrying the new values")
        void aCorrectionIsAudited() throws Exception {
            UUID id = createdId(create(LEAD, ACME_BANKING, urlBody("Environment guide")));

            edit(LEAD, ACME_BANKING, id, "Environment guide v2", "Environment", null);

            assertThat(amendmentCount(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("a sibling project's artifact is 404 through this project's path")
        void anotherProjectsArtifactIsNotReachable() throws Exception {
            UUID locked = UUID.fromString("af009000-0000-4000-8000-000000000002");
            insertArtifact(locked, ACME, ACME_LOCKED, "Internal doc");

            assertThat(edit(LEAD, ACME_BANKING, locked, "Renamed", "Tooling", null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    /**
     * {@code PATCH …/artifacts/{artifactId}} with the three editable fields.
     *
     * <p>A {@code null} description is sent as JSON {@code null} rather than omitted — the two are
     * the same value to Jackson (§68), and spelling it makes the intent readable at the call site.
     */
    private MvcResult edit(
            UUID userId, UUID projectId, UUID artifactId,
            String title, String category, String description) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + projectId + "/artifacts/" + artifactId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + quote(title)
                                + ",\"category\":" + quote(category)
                                + ",\"description\":"
                                + (description == null ? "null" : quote(description)) + "}"))
                .andReturn();
    }

    /**
     * Puts {@code PLAN_SCOPE} back on the Test Engineer's {@code artifact:edit}, for this test only.
     *
     * <p>{@code V52} emptied {@code role_permission.qualifier}, so the seeded catalogue can no
     * longer express the narrowing the scope gate implements — and a gate nothing can exercise is a
     * gate that could be deleted with every test still green. Roles spec §A5 is explicit that the
     * mechanism is retained against a future migration, so this is what keeps that retention
     * observable rather than asserted.
     *
     * <p><strong>The caller must put it back.</strong> This table is instance-level and shared by
     * every test in the container — see the {@code finally} at the one call site.
     */
    private void qualifyEngineersArtifactEdit(String qualifier) throws SQLException {
        try (Connection connection = migrationOwnerConnection()) {
            execute(connection,
                    "UPDATE role_permission SET qualifier = '" + qualifier + "'"
                            + " WHERE role_id = (SELECT id FROM role WHERE name = 'Test Engineer')"
                            + " AND permission_key = 'artifact:edit'");
        }
    }

    /** How many corrections have been recorded — the idempotence assertions turn on the count. */
    private long amendmentCount(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM audit_event WHERE action = 'ARTIFACT_AMENDED'");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private MvcResult delete(UUID userId, UUID projectId, UUID artifactId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/artifacts/" + artifactId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andReturn();
    }

    private MvcResult create(UUID userId, UUID projectId, String body) throws Exception {
        return create(userId, projectId, body, null);
    }

    /** @param idempotencyKey the header, or null to send none (B4). */
    private MvcResult create(UUID userId, UUID projectId, String body, String idempotencyKey)
            throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/artifacts")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
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

    /**
     * A sign-off record — the ordinary registration plus {@code writeOnce} (audit C7).
     *
     * <p>Spliced the way {@link #replacing} splices, and for its reason: what these tests are about
     * is one flag's effect on acts the register already had, so re-spelling the payload would put a
     * second definition of "an ordinary registration" beside the first.
     */
    private String signOff(String title) {
        return urlBody(title).substring(0, urlBody(title).length() - 1) + ",\"writeOnce\":true}";
    }

    /** The same body with one more property spliced in before the closing brace. */
    private String replacingLast(String body, String property) {
        return body.substring(0, body.length() - 1) + "," + property + "}";
    }

    /** A pinned report, which names a job rather than carrying a payload of its own (audit C6). */
    private String pinnedReport(String title, UUID jobId) {
        return "{\"scope\":\"PROJECT\",\"category\":\"Compliance\",\"kind\":\"GENERATED_REPORT\""
                + ",\"title\":" + quote(title)
                + ",\"reportJobId\":" + (jobId == null ? "null" : "\"" + jobId + "\"") + "}";
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
     * A finished report of {@code projectId} — a {@code report_artifact} and the
     * {@code report_job} that produced it, as {@code ReportJobRunner.complete} leaves them.
     *
     * <p>Written in SQL rather than by running the pipeline, and the reason is the module boundary
     * this fixture exists to test across: {@code documentation} may not depend on
     * {@code reporting}, so a test of the register that booted the report runner would be asserting
     * the register's rule through the very coupling {@link ReportFacts} exists to avoid. What the
     * register needs from the other side is two columns, and this writes exactly those.
     *
     * @return the job id, which is what a caller pins by — see {@link PinnedReports}
     */
    private UUID completedReportJob(UUID companyId, UUID projectId) throws SQLException {
        UUID artifactId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        try (Connection connection = appConnection(companyId)) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO report_artifact
                        (id, company_id, format, storage_key, filter_snapshot, generated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), 'MD', ?, '{}'::jsonb, now())
                    """)) {
                statement.setObject(1, artifactId);
                statement.setObject(2, companyId);
                statement.setString(3, "reports/" + artifactId + ".md");
                statement.executeUpdate();
            }
            insertReportJob(connection, jobId, companyId, projectId, "COMPLETED", artifactId);
        }
        return jobId;
    }

    /** A claimed job with no bytes yet — the state the pin must refuse. */
    private UUID pendingReportJob(UUID companyId, UUID projectId) throws SQLException {
        UUID jobId = UUID.randomUUID();
        try (Connection connection = appConnection(companyId)) {
            insertReportJob(connection, jobId, companyId, projectId, "RUNNING", null);
        }
        return jobId;
    }

    private void insertReportJob(
            Connection connection, UUID jobId, UUID companyId, UUID projectId, String status,
            UUID artifactId) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO report_job
                    (id, company_id, project_id, type, format, status, artifact_id, requested_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'run_summary', 'MD',
                        ?, CAST(? AS uuid), now())
                """)) {
            statement.setObject(1, jobId);
            statement.setObject(2, companyId);
            statement.setObject(3, projectId);
            statement.setString(4, status);
            statement.setObject(5, artifactId);
            statement.executeUpdate();
        }
    }

    /** The artifact the job produced — what the register stores, read back for the URL assertion. */
    private UUID reportArtifactOf(UUID jobId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT artifact_id FROM report_job WHERE id = ?")) {
            statement.setObject(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getObject(1, UUID.class);
            }
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
