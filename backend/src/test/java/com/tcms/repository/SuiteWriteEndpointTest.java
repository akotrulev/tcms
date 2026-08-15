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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code POST}, {@code PATCH} and {@code DELETE} on {@code /projects/{projectId}/suites} — the
 * writes that make a provisioned company usable.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Delete gated on {@code suite:manage}.</strong> The permissions are two, and the
 *       roles spec lists their separation as a boundary that was <em>previously wrong and got
 *       fixed</em>. Collapsed, a Test Engineer can delete the tree. Nothing else in the suite would
 *       notice, because every other assertion about the delete would still pass — a lead can still
 *       do it.</li>
 *   <li>~~<strong>A path derived from the name</strong>, and <strong>a silently ignored
 *       move</strong>.~~ → both went with the tree in §98. What replaced them is an assertion that
 *       neither field is served, and that a stale client still sending {@code parentId} is ignored
 *       rather than refused.</li>
 *   <li><strong>A delete that cascades.</strong> Removing a suite holding filed cases soft-deletes other
 *       people's work through a permission whose sibling is {@code OWN}-qualified precisely to stop
 *       that, with no preview. Asserted as a 409 <em>and</em> as the cases still being there.</li>
 *   <li><strong>A key burned by a rejected payload.</strong> The counter only moves forward, so a
 *       blank name that consumed a number leaves a permanent hole per failed attempt.</li>
 *   <li><strong>A created suite with no v1.</strong> §29's defect on cases, one entity over.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code f1…}, checked against every prefix
 * already in {@code src/test/java} before writing. Sharing them is how handoff §19's first defect
 * happened, and §29 hit it again: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Suite write endpoints — create, rename, and the delete that refuses")
class SuiteWriteEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("f1000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("f1000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. Carries the seeded tree. */
    private static final UUID ACME_BANKING = UUID.fromString("f1001000-0000-4000-8000-000000000001");
    /** Same company, granted to LEAD too — so key numbering can be watched spanning projects. */
    private static final UUID ACME_MOBILE = UUID.fromString("f1001000-0000-4000-8000-000000000002");
    /** Same company, granted to nobody here. */
    private static final UUID ACME_LOCKED = UUID.fromString("f1001000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_BANKING = UUID.fromString("f1002000-0000-4000-8000-000000000001");

    /** Test Lead. Holds {@code suite:manage} AND {@code suite:delete}. */
    private static final UUID LEAD = UUID.fromString("f1003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer. ~~Holds {@code suite:manage} and <strong>not</strong> {@code suite:delete} —
     * the one persona that separates the two permissions.~~ → <strong>{@code V52} (§210) granted it
     * {@code suite:delete}</strong>, so this persona no longer separates them and <em>no seeded role
     * does</em>. The keys remain two; nothing in this file can still prove the route asks the right
     * one.
     */
    private static final UUID ENGINEER = UUID.fromString("f1003000-0000-4000-8000-000000000002");
    /** Viewer. Holds {@code project:read} and neither write permission. */
    private static final UUID VIEWER = UUID.fromString("f1003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("f1003000-0000-4000-8000-000000000004");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("f1003000-0000-4000-8000-000000000005");

    /** Root, holds no cases of its own, parent of REFUNDS. */
    private static final UUID PAYMENTS = UUID.fromString("f1004000-0000-4000-8000-000000000001");
    /** Child of PAYMENTS, holds two live cases. The folder a delete must refuse. */
    private static final UUID REFUNDS = UUID.fromString("f1004000-0000-4000-8000-000000000002");
    /** Root, empty of both cases and children. The only folder a delete may take. */
    private static final UUID SPARE = UUID.fromString("f1004000-0000-4000-8000-000000000003");
    /**
     * Root whose only occupant is an <strong>empty</strong> child suite.
     *
     * <p>The one fixture that isolates the child check: every other non-empty folder here also has
     * cases in its subtree, so the case count refuses it too and a missing child check survives.
     */
    /** Two empty suites, a parent and its child until §98 flattened them. Both simply delete. */
    private static final UUID NEST_PARENT = UUID.fromString("f1004000-0000-4000-8000-000000000007");
    private static final UUID NEST_CHILD = UUID.fromString("f1004000-0000-4000-8000-000000000008");
    /** Root in the sibling project, granted to nobody. */
    /** One suite per {@link LifecycleStatus}, for the ladder tests. TS-94 … TS-97. */
    private static final UUID DRAFTED = UUID.fromString("f1004000-0000-4000-8000-000000000010");
    private static final UUID REVIEWING = UUID.fromString("f1004000-0000-4000-8000-000000000011");
    private static final UUID SIGNED_OFF = UUID.fromString("f1004000-0000-4000-8000-000000000012");
    private static final UUID RETIRED = UUID.fromString("f1004000-0000-4000-8000-000000000013");

    /**
     * The condition library, for §186's write path.
     *
     * <p>{@code SIBLING_CONDITION} lives in {@code ACME_MOBILE} — the same company, so the foreign
     * key, the company column and RLS all admit it and only the project predicate refuses it. That
     * is the boundary the union in {@code SuiteWriteService} exists for, and asserting it needs a
     * row that is legitimate in every other respect.
     */
    private static final UUID LOGIN = UUID.fromString("f1005000-0000-4000-8000-000000000001");
    private static final UUID CLEANUP = UUID.fromString("f1005000-0000-4000-8000-000000000002");
    private static final UUID RETIRED_CONDITION =
            UUID.fromString("f1005000-0000-4000-8000-000000000003");
    private static final UUID SIBLING_CONDITION =
            UUID.fromString("f1005000-0000-4000-8000-000000000004");

    private static final UUID LOCKED_SUITE = UUID.fromString("f1004000-0000-4000-8000-000000000004");
    /** Root in the other client, deliberately keyed TS-1 like Acme's. */
    private static final UUID UMBRA_SUITE = UUID.fromString("f1004000-0000-4000-8000-000000000005");

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
            company(ACME, "sw-acme", "Acme", now);
            company(UMBRA, "sw-umbra", "Umbra", now);

            activeUser(LEAD, "lead@sw-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@sw-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@sw-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@sw-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@sw-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "WAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "WAMOB", "Mobile App", now);
            seedProject(ACME_LOCKED, ACME, "WALCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("sw-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("sw-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grantOnProject("sw-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("sw-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "WUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("sw-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedTrees();
        seedConditionLibrary();
    }

    /**
     * Puts back everything a test can write, counters included.
     *
     * <p>The counters are the part that surprises: {@code tcms_seed_company_defaults} is
     * {@code ON CONFLICT DO NOTHING}, correct for provisioning and therefore no reset. Without this,
     * every {@code TS-n} assertion would depend on the order the class ran in — green alone and red
     * in the suite, which is handoff §19's defect again.
     *
     * <p>Suites are deleted before the counter is reset and re-seeded afterwards, because the seeded
     * tree occupies TS-1..TS-3 and a create must be able to predict it gets TS-4.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // Through each company's OWN context: RLS means the other's connection could not see
                // these rows to delete them, which is isolation working rather than a leftover row.
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM test_case");
                // Before `suite`, though `suite_condition.suite_id` cascades: the delete below
                // would take the attachments with it either way, and naming the table here is what
                // makes a reader of this method see that a test can write one.
                execute(connection, "DELETE FROM suite_condition");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    /**
     * Four library entries: two live in the project under test, one retired, one in the sibling
     * project.
     *
     * <p>{@code ON CONFLICT DO NOTHING} because {@link #resetMutableRows} deliberately does not
     * clear {@code condition} — nothing here edits the library, so re-seeding it per test would be
     * work with no assertion behind it.
     */
    private void seedConditionLibrary() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            condition(connection, LOGIN, ACME_BANKING, "Log in as standard user",
                    "[\"Open the application\", \"Enter valid credentials\"]", false);
            condition(connection, CLEANUP, ACME_BANKING, "Reset the test payment data",
                    "[\"Void every pending charge\"]", false);
            condition(connection, RETIRED_CONDITION, ACME_BANKING, "Log in as the old admin",
                    "[\"Open the legacy console\"]", true);
            condition(connection, SIBLING_CONDITION, ACME_MOBILE, "Install the beta build",
                    "[\"Sideload the APK\"]", false);
        }
    }

    /** Every nullable parameter is bound through an explicit {@code CAST} — pgjdbc infers none. */
    private void condition(
            Connection connection, UUID id, UUID projectId, String title, String body,
            boolean retired) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 'BOTH',
                        CAST(? AS jsonb), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, body);
            statement.setString(6, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The two gates, and the fact that they are two
    // =====================================================================

    @Nested
    @DisplayName("The gates — suite:manage to write, suite:delete to remove")
    class Gates {

        @Test
        @DisplayName("a Test Lead creates a suite and gets 201 with the whole suite")
        void leadCanCreate() throws Exception {
            MvcResult result = post(LEAD, ACME, ACME_BANKING, suiteInput("Onboarding"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("name").asText()).isEqualTo("Onboarding");
        }

        @Test
        @DisplayName("a Test Engineer may create and rename — organising the tree is not restricted")
        void engineerCanManage() throws Exception {
            MvcResult created = post(ENGINEER, ACME, ACME_BANKING, suiteInput("Engineer's"));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);

            UUID id = UUID.fromString(body(created).get("id").asText());
            assertThat(patch(ENGINEER, ACME, ACME_BANKING, id, suiteInput("Renamed"), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Engineer DOES delete since V52 — the tier line moved, the keys did not")
        void engineerCanDelete() throws Exception {
            /*
              ~~THE assertion of this class. suite:delete is leads-only because deleting a subtree is
              the most destructive repository action.~~ → INVERTED by V52 (§210): a contributor
              creates, edits and deletes, and the tier line is now approvals and project
              configuration alone.

              READ THIS BEFORE TRUSTING THE GATE. `suite:manage` and `suite:delete` are still two
              keys in the catalogue — but NO SEEDED ROLE NOW HOLDS ONE WITHOUT THE OTHER, so no
              persona in this file can tell them apart, and this class has lost its discriminator.
              Collapse the delete route onto `suite:manage` today and every test here still passes.

              That is a real loss and it is recorded rather than papered over: the keys stay separate
              because a company may want them separated and because removing a folder is a distinct
              audit event, but the CHECK that the route asks the right one now lives only in
              `SuiteWriteService`'s constant and in review. `§210 DQ-5` is the queue row.
            */
            assertThat(delete(ENGINEER, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat(liveSuiteIds(ACME, ACME_BANKING)).doesNotContain(SPARE);
        }

        @Test
        @DisplayName("a Viewer still may not delete — the gate is a gate, not a formality")
        void viewerCannotDelete() throws Exception {
            // What survives of the test above as a real discrimination. Viewer holds neither write
            // key, so this proves the route is gated at all — which is the half `engineerCanDelete`
            // can no longer prove now that the contributor holds both keys.
            assertThat(delete(VIEWER, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).contains(SPARE);
        }

        @Test
        @DisplayName("the same Engineer, refused the delete, DOES reach the rename")
        void theRefusalIsThePermissionAndNotABrokenRoute() throws Exception {
            // Without this the test above passes just as well against a delete route that does not
            // exist, or a caller whose token is wrong.
            assertThat(patch(ENGINEER, ACME, ACME_BANKING, SPARE,
                    suiteInput("Still writable"), null).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Lead may delete")
        void leadCanDelete() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a Viewer may read the tree and write nothing")
        void viewerIsRefusedEveryWrite() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/suites")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(VIEWER, ACME)))
                    .andReturn().getResponse().getStatus())
                    .as("the read gate every role passes — otherwise the 403s below prove nothing")
                    .isEqualTo(200);

            assertThat(post(VIEWER, ACME, ACME_BANKING, suiteInput("Nope"))
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(patch(VIEWER, ACME, ACME_BANKING, SPARE, suiteInput("Nope"), null)
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(delete(VIEWER, ACME, ACME_BANKING, SPARE)
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = post(VIEWER, ACME, ACME_BANKING, suiteInput("Nope"))
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves, "
                            + "and naming it publishes the catalogue to exactly that caller")
                    .doesNotContain("suite:manage")
                    .doesNotContain("suite:delete");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404 on every verb, never 403")
        void membershipConveysNoWrite() throws Exception {
            // The rows are in this caller's own company, so no cross-company assertion reaches
            // them. A 403 here would confirm the project id names something real.
            assertThat(post(NO_GRANT, ACME, ACME_BANKING, suiteInput("Nope"))
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(patch(NO_GRANT, ACME, ACME_BANKING, SPARE, suiteInput("Nope"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(delete(NO_GRANT, ACME, ACME_BANKING, SPARE)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent projects answer 404 with no body")
        void theThreeUnreachableCasesAnswerIdentically() throws Exception {
            UUID absent = UUID.randomUUID();

            for (UUID projectId : List.of(ACME_LOCKED, UMBRA_BANKING, absent)) {
                MvcResult result = post(LEAD, ACME, projectId, suiteInput("Nope"));
                assertThat(result.getResponse().getStatus())
                        .as("project %s", projectId)
                        .isEqualTo(404);
                assertThat(result.getResponse().getContentAsString())
                        .as("a 404 that explains itself is still an oracle (§6t)")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a suite in another project of the same company is 404, not renamed")
        void aSuiteIsScopedToItsProject() throws Exception {
            // LOCKED_SUITE is in ACME_LOCKED. The caller passes the gate on ACME_BANKING, so only
            // the suite query's own project predicate separates them — drop it and a lead granted
            // one project renames folders in another.
            assertThat(patch(LEAD, ACME, ACME_BANKING, LOCKED_SUITE, suiteInput("Stolen"),
                    null).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(nameOf(ACME, LOCKED_SUITE)).isEqualTo("Internal");
        }

        @Test
        @DisplayName("the other client's suite is 404 even with an identical display key")
        void writesDoNotCrossCompanies() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, UMBRA_SUITE, suiteInput("Stolen"),
                    null).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(delete(LEAD, ACME, ACME_BANKING, UMBRA_SUITE)
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(nameOf(UMBRA, UMBRA_SUITE)).isEqualTo("Payments");
        }

        @Test
        @DisplayName("an anonymous caller is refused on every verb")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_BANKING + "/suites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(suiteInput("Nope")))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .delete("/api/v1/projects/" + ACME_BANKING + "/suites/" + SPARE))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // Create — the key, the path, and v1
    // =====================================================================

    @Nested
    @DisplayName("Create — a server-allocated key, a derived path, and version 1")
    class Create {

        @Test
        @DisplayName("the key comes from the company's counter and continues past the seeded tree")
        void keysComeFromTheCounter() throws Exception {
            // The seeded tree took TS-1..TS-3, so the next is TS-4. A key derived from
            // MAX(key_seq)+1 would also answer TS-4 here — that is exactly why this is not the
            // assertion that catches the racing implementation, and DisplayKeyAllocationTest owns
            // that. What this pins is that the key is allocated at all and is not, say, the name.
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Fourth"))))
                    .isEqualTo("TS-4");
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Fifth"))))
                    .isEqualTo("TS-5");
        }

        @Test
        @DisplayName("keys advance across projects — the counter is per company, not per project")
        void keysAreCompanyScoped() throws Exception {
            // Plan §6t. Per-project numbering was the superseded decision, and it fails here: both
            // creates would answer TS-4.
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("In banking"))))
                    .isEqualTo("TS-4");
            assertThat(keyOf(post(LEAD, ACME, ACME_MOBILE, suiteInput("In mobile"))))
                    .isEqualTo("TS-5");
        }

        @Test
        @DisplayName("each company numbers from its own counter, so TS-1 exists twice")
        void countersDoNotCrossCompanies() throws Exception {
            // Umbra's tree is one suite, seeded at TS-1, so its next is TS-2 while Acme is at TS-4.
            assertThat(keyOf(post(OUTSIDER, UMBRA, UMBRA_BANKING, suiteInput("Theirs"))))
                    .isEqualTo("TS-2");
        }

        @Test
        @DisplayName("a rejected payload burns no key")
        void aRefusedCreateDoesNotConsumeANumber() throws Exception {
            assertThat(post(LEAD, ACME, ACME_BANKING, suiteInput("   "))
                    .getResponse().getStatus()).isEqualTo(400);

            // The counter only moves forward, so a burned number is a permanent hole in the
            // company's key sequence for every failed attempt.
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Now valid"))))
                    .isEqualTo("TS-4");
        }

        @Test
        @DisplayName("a created suite carries no parent and no path")
        void theTreeFieldsAreGone() throws Exception {
            /*
              What §98 removed, and the three tests that stood here are worth naming because each
              pinned a decision rather than a field:

                - the path was derived from the KEY SEQUENCE and never from the name, because a
                  name-derived label is not unique ("UI / E2E" and "ui e2e" slug alike), can be
                  empty, and makes every rename a subtree rewrite;
                - a child's path was its parent's plus its own label;
                - a root's `parentId` was null EXPLICITLY rather than by omission, because the SPA
                  bucketed on `parentId ?? null`.

              A create is the place to assert the absence: it is the one path that used to DERIVE
              both values, so a leftover derivation would show up here first.
            */
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, suiteInput("UI / E2E")));

            assertThat(created.has("path")).isFalse();
            assertThat(created.has("parentId")).isFalse();
            assertThat(created.get("key").asText()).isEqualTo("TS-4");
        }

        @Test
        @DisplayName("a new suite is DRAFT — a create cannot grant its own approval")
        void aCreatedSuiteIsDraft() throws Exception {
            assertThat(body(post(LEAD, ACME, ACME_BANKING, suiteInput("Fresh")))
                    .get("lifecycleStatus").asText())
                    .isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("version 1 is written with the suite, so a created folder has no history gap")
        void versionOneIsWrittenWithTheRow() throws Exception {
            // §29's precedent, one entity over: SuiteVersions.writeInitial numbers this 1
            // explicitly. A decide()-style currentVersionNo + 1 would answer 2 on a fresh row,
            // whose column defaults to 1, and every suite in the product would be missing its v1.
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING, suiteInput("V1")))
                    .get("id").asText());

            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
        }

        @Test
        @DisplayName("a rename writes no version — suite_version holds composition, not content")
        void renamingDoesNotBumpTheCompositionVersion() throws Exception {
            // V1's own comment on suite_version.snapshot: a suite version captures composition and
            // explicitly not content, "otherwise every case edit bumps every ancestor suite". A
            // name is content. Bumping here is the start of that spiral, and it looks like
            // diligence.
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING, suiteInput("Before")))
                    .get("id").asText());

            patch(LEAD, ACME, ACME_BANKING, id, suiteInput("After"), null);

            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
        }

        @Test
        @DisplayName("a create writes an audit row naming the key and the name")
        void theCreateIsAudited() throws Exception {
            post(LEAD, ACME, ACME_BANKING, suiteInput("Audited"));

            Map<String, String> events = auditEvents(ACME);
            assertThat(events).containsKey("suite.created");
            assertThat(events.get("suite.created")).contains("TS-4").contains("Audited");
        }

        @Test
        @DisplayName("a name with a quote in it is created, and its audit row is valid JSON")
        void aQuotedNameDoesNotCorruptTheAuditRow() throws Exception {
            // audit_event.detail is JSONB and the detail string is assembled by concatenation, so
            // an unescaped name makes the document malformed, the insert fail, and the whole write
            // roll back — a folder with a quote in its name would simply be uncreatable, and the
            // error would name the audit table rather than the name.
            String awkward = "\"Payments\" \\ v2";

            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":" + json.writeValueAsString(awkward) + "}");

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(created).get("name").asText()).isEqualTo(awkward);
            assertThat(auditEvents(ACME).get("suite.created")).contains("TS-4");
        }

        @Test
        @DisplayName("Location names the created suite")
        void locationIsSet() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, suiteInput("Located"));
            String id = body(created).get("id").asText();

            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/suites/" + id);
        }

        @Test
        @DisplayName("the created suite appears in the tree the next read serves")
        void theCreateIsVisibleToTheRead() throws Exception {
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING, suiteInput("Listed")))
                    .get("id").asText());

            assertThat(liveSuiteIds(ACME, ACME_BANKING)).contains(id);
        }

        @Test
        @DisplayName("a new suite reports caseCount 0 and both condition lists [], never null")
        void theCreateResponseIsTheReadShape() throws Exception {
            // Served through SuiteService's own renderer rather than assembled by the write path,
            // so a field cannot show one value on create and another on the reload after it.
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, suiteInput("Shaped")));

            assertThat(List.of("id", "key", "name", "caseCount", "lifecycleStatus",
                    "preconditions", "postconditions", "lockVersion"))
                    .allSatisfy(field -> assertThat(created.has(field))
                            .as("Suite.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(created.get("caseCount").asLong()).isZero();
            assertThat(created.get("preconditions").isArray()).isTrue();
            assertThat(created.get("preconditions")).isEmpty();
            assertThat(created.get("postconditions").isArray()).isTrue();
            assertThat(created.get("postconditions")).isEmpty();
            assertThat(created.has("companyId")).isFalse();
            assertThat(created.has("projectId")).isFalse();
        }
    }


    // =====================================================================
    // Conditions (§186) — the write path suite_condition never had
    //
    // The table, its `kind` column, the resolution order that reads it and the library's
    // `suiteUsageCount` all shipped in M0. Nothing created a row: `new SuiteCondition(` occurred
    // nowhere in src/main, and the only rows in this repository were written by hand-rolled SQL in
    // a read test's fixture. Everything below drives the endpoint.
    // =====================================================================

    @Nested
    @DisplayName("Conditions — attached, reordered and detached through the suite write")
    class Conditions {

        @Test
        @DisplayName("a create attaches both lists, in the payload's order, and serves them split")
        void aCreateAttachesBothKinds() throws Exception {
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Guarded", List.of(CLEANUP, LOGIN), List.of(LOGIN))));

            // The order is the payload's and NOT the library's: CLEANUP sorts after LOGIN by title,
            // so a service that re-sorted anything would answer these the other way round. A
            // condition list is a procedure and its order is its content.
            assertThat(titlesOf(created.get("preconditions")))
                    .containsExactly("Reset the test payment data", "Log in as standard user");
            assertThat(titlesOf(created.get("postconditions")))
                    .containsExactly("Log in as standard user");

            // The same entry in both lists — legitimate, common, and deduped per list rather than
            // across them. Two different actions at two different moments.
            assertThat(created.get("preconditions").get(1).get("conditionId").asText())
                    .isEqualTo(created.get("postconditions").get(0).get("conditionId").asText());
            assertThat(created.get("preconditions").get(1).get("id").asText())
                    .as("two attachments, so two rows and two ids")
                    .isNotEqualTo(created.get("postconditions").get(0).get("id").asText());
        }

        @Test
        @DisplayName("position is assigned from array order and never taken from the request")
        void positionsComeFromTheArray() throws Exception {
            UUID id = idOf(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Ordered", List.of(CLEANUP, LOGIN), List.of())));

            // Read from the row, not the response: the response orders by the query's ORDER BY, so
            // a service that wrote every row at position 0 would still serve them in insertion
            // order most of the time. `suite_condition` has no constraint that would refuse a
            // duplicate position — this is the only place the value is observable.
            assertThat(positionsOf(id, "PRE")).containsExactly(0, 1);
        }

        @Test
        @DisplayName("an absent list leaves the attachments alone; [] detaches them")
        void absentIsNotEmpty() throws Exception {
            UUID id = idOf(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Standing", List.of(LOGIN), List.of(CLEANUP))));

            // A rename that never mentions conditions. This is the whole reason the two states have
            // different representations: the SPA's rename dialog sends neither field, and emptying
            // a standing procedure on a name change is exactly what that must not do.
            JsonNode renamed = body(patch(LEAD, ACME, ACME_BANKING, id,
                    "{\"name\":\"Renamed\"}", null));
            assertThat(renamed.get("preconditions")).hasSize(1);
            assertThat(renamed.get("postconditions")).hasSize(1);

            JsonNode cleared = body(patch(LEAD, ACME, ACME_BANKING, id,
                    suiteInput("Cleared", List.of(), List.of()), null));
            assertThat(cleared.get("preconditions")).isEmpty();
            assertThat(cleared.get("postconditions")).isEmpty();
        }

        @Test
        @DisplayName("a REORDER keeps every attachment id — and with it every case's opt-out")
        void reorderingPreservesTheAttachmentRow() throws Exception {
            // THE test of this slice. `case_condition_optout` references `suite_condition(id)` ON
            // DELETE CASCADE, so a reconcile that cleared the list and reinserted it would discard
            // every opt-out on EVERY save of the suite — a save that changed nothing an author can
            // see would silently re-impose a condition twenty cases had declined, and the rows
            // proving they declined it would be gone.
            //
            // The case-side reconcile's javadoc says a delete-and-reinsert "would in fact be safe
            // here" and names this table as the one where it is not. This is that sentence, driven.
            UUID id = idOf(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Declinable", List.of(LOGIN, CLEANUP), List.of())));

            UUID attachment = attachmentIdOf(id, LOGIN);
            UUID declining = UUID.nameUUIDFromBytes("sw-ref0".getBytes());
            optOut(declining, attachment);

            // Swap the two. Same set, different order — the ordinary drag-and-drop save.
            body(patch(LEAD, ACME, ACME_BANKING, id,
                    suiteInput("Declinable", List.of(CLEANUP, LOGIN), List.of()), null));

            assertThat(attachmentIdOf(id, LOGIN))
                    .as("the row is moved, not replaced")
                    .isEqualTo(attachment);
            assertThat(positionsOf(id, "PRE")).containsExactly(0, 1);
            assertThat(optOutsAgainst(attachment))
                    .as("the case's refusal survives a reorder of the list it is against")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("DETACHING discards the opt-outs against it, because there is nothing left to decline")
        void detachingTakesTheOptOutWithIt() throws Exception {
            // The other half of the rule above, and it is the cascade rather than a decision this
            // endpoint applies. Asserted so the pair is visible together: a reorder must keep the
            // opt-out and a detach must not, and a reconcile that got either wrong would look
            // correct against the other.
            UUID id = idOf(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Withdrawn", List.of(LOGIN), List.of())));

            UUID attachment = attachmentIdOf(id, LOGIN);
            optOut(UUID.nameUUIDFromBytes("sw-ref0".getBytes()), attachment);

            body(patch(LEAD, ACME, ACME_BANKING, id,
                    suiteInput("Withdrawn", List.of(), List.of()), null));

            assertThat(optOutsAgainst(attachment)).isZero();
        }

        @Test
        @DisplayName("a duplicate inside one list collapses rather than being refused")
        void duplicatesCollapse() throws Exception {
            // Asking the author to remove something they cannot see is worse than resolving it:
            // the same entry twice in one list is a row with no meaning, not a mistake to report.
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Twice", List.of(LOGIN, LOGIN), List.of())));

            assertThat(created.get("preconditions")).hasSize(1);
        }

        @Test
        @DisplayName("a SIBLING project's condition is a field error, not a stored row")
        void aSiblingProjectsConditionIsRefused() throws Exception {
            // It satisfies the foreign key, the company column and RLS — only the project predicate
            // refuses it. Without that half of the union, every later read of this suite would
            // resolve a condition through a project the reader was never granted.
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Leaky", List.of(SIBLING_CONDITION), List.of()));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").get("preconditions").get(0).asText())
                    .as("one message for three causes — retired, another project's, or nothing at "
                            + "all; distinguishing them confirms a condition the caller may not use")
                    .isEqualTo("That condition is not one this project offers.");
        }

        @Test
        @DisplayName("a RETIRED condition cannot be attached, and reports the same message")
        void aRetiredConditionIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    suiteInput("Withdrawn procedure", List.of(), List.of(RETIRED_CONDITION)));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").get("postconditions").get(0).asText())
                    .isEqualTo("That condition is not one this project offers.");
        }

        @Test
        @DisplayName("an inlineBody is REFUSED, not swallowed")
        void inlineIsRefused() throws Exception {
            // Nothing renders an inline attachment — every CaseConditionRef requires a title and
            // the row has no column for one — so accepting it would be a save that succeeds and a
            // condition the author can never see again. The field is declared on the contract
            // precisely so it can be refused rather than being indistinguishable from a typo.
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Inline\",\"preconditions\":[{\"inlineBody\":[\"Be logged in\"]}]}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").get("preconditions").get(0).asText())
                    .isEqualTo("Attach a condition from the library. Inline conditions are not "
                            + "supported yet.");
        }

        @Test
        @DisplayName("a rejected condition list burns no key")
        void aRejectedListBurnsNoKey() throws Exception {
            // §29's rule, on the newest way to be rejected: the counter only moves forward, so a
            // burned number is a permanent hole in the company's keys. Validation runs before the
            // allocation and this is what says so for the condition arm specifically.
            String next = keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Before")));
            post(LEAD, ACME, ACME_BANKING, suiteInput("Leaky", List.of(SIBLING_CONDITION), List.of()));

            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("After"))))
                    .isEqualTo("TS-" + (Integer.parseInt(next.substring(3)) + 1));
        }

        @Test
        @DisplayName("a Viewer cannot attach conditions — the gate is suite:manage, not a new key")
        void theGateIsTheSuiteWritesOwn() throws Exception {
            // Deliberately NOT a thirteenth permission key. Conditions are the suite's content in
            // the way its tag rules are, and a key seeded to nobody is a 403 no administrator can
            // grant their way out of (the argument that kept `evidence:read` from existing).
            assertThat(post(VIEWER, ACME, ACME_BANKING,
                    suiteInput("Nope", List.of(LOGIN), List.of()))
                    .getResponse().getStatus())
                    .isEqualTo(403);
        }
    }

    // =====================================================================
    // Idempotency-Key (V42)
    //
    // The header this endpoint REFUSED until V42, and the refusal was argued rather than
    // forgotten — "a duplicate folder is visible in the tree the moment the create returns".
    // §98 deleted that tree and §135 added a caller with no screen at all, so what is asserted
    // here is the retry a person cannot see and therefore cannot correct.
    // =====================================================================

    @Nested
    @DisplayName("Idempotency — a retry must not create a second suite")
    class Idempotency {

        @Test
        @DisplayName("a repeated key answers 200 with the SAME suite, and creates nothing")
        void aRetryReplaysTheOriginal() throws Exception {
            int before = liveSuiteIds(ACME, ACME_BANKING).size();
            String key = "intent-" + UUID.randomUUID();

            MvcResult first = post(LEAD, ACME, ACME_BANKING, suiteInput("Once"), key);
            MvcResult retry = post(LEAD, ACME, ACME_BANKING, suiteInput("Once"), key);

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            // 200 and not 201: a client can tell "I made this" from "I already had this", and a
            // well-behaved retry is not counted as a second creation in anybody's logs.
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("id").asText()).isEqualTo(body(first).get("id").asText());

            // Exactly one row was added — the assertion that the second call wrote nothing at all.
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).hasSize(before + 1);
        }

        @Test
        @DisplayName("a replay carries no Location — nothing was created by that request")
        void aReplayCarriesNoLocation() throws Exception {
            String key = "intent-" + UUID.randomUUID();
            post(LEAD, ACME, ACME_BANKING, suiteInput("Located"), key);

            MvcResult retry = post(LEAD, ACME, ACME_BANKING, suiteInput("Located"), key);

            // A client that followed a Location on a replay would read it as having made something.
            assertThat(retry.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        }

        @Test
        @DisplayName("the counter does not move on a replay, so the next real create is TS-5")
        void aReplayBurnsNoKey() throws Exception {
            String key = "intent-" + UUID.randomUUID();

            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Fourth"), key)))
                    .isEqualTo("TS-4");
            post(LEAD, ACME, ACME_BANKING, suiteInput("Fourth"), key);

            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Fifth"))))
                    .isEqualTo("TS-5");
        }

        @Test
        @DisplayName("a retry whose payload has drifted still returns the original, not a 400")
        void aRetryIgnoresADriftedPayload() throws Exception {
            // The key is checked BEFORE validation for exactly this: the client is asking "did my
            // intent land", and a 400 would send it to fix fields on a suite that already exists.
            String key = "intent-" + UUID.randomUUID();
            post(LEAD, ACME, ACME_BANKING, suiteInput("Original"), key);

            MvcResult retry = post(LEAD, ACME, ACME_BANKING, suiteInput("   "), key);

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("name").asText()).isEqualTo("Original");
        }

        @Test
        @DisplayName("two different keys with identical bodies create two suites")
        void differentKeysCreateDifferentSuites() throws Exception {
            // The key identifies the intent, not the content: two people may legitimately name two
            // collections the same thing, and deduplicating on the body would refuse the second.
            int before = liveSuiteIds(ACME, ACME_BANKING).size();

            post(LEAD, ACME, ACME_BANKING, suiteInput("Same name"), "intent-a");
            post(LEAD, ACME, ACME_BANKING, suiteInput("Same name"), "intent-b");

            assertThat(liveSuiteIds(ACME, ACME_BANKING)).hasSize(before + 2);
        }

        @Test
        @DisplayName("no key at all creates every time — the header stays optional")
        void withoutAKeyEveryCallCreates() throws Exception {
            int before = liveSuiteIds(ACME, ACME_BANKING).size();

            post(LEAD, ACME, ACME_BANKING, suiteInput("One"));
            post(LEAD, ACME, ACME_BANKING, suiteInput("One"));

            assertThat(liveSuiteIds(ACME, ACME_BANKING)).hasSize(before + 2);
        }

        @Test
        @DisplayName("a blank key is treated as absent, not as a key everyone shares")
        void aBlankKeyIsAbsent() throws Exception {
            // Stored as-is, a blank key becomes ONE key that every misconfigured client replays
            // against, and the project accepts exactly one suite from all of them, forever. The
            // same blank value has to be sent twice — two different blanks would not collide even
            // without the trim, which is how the case endpoint's first version of this test passed
            // against a mutation that removed it.
            int before = liveSuiteIds(ACME, ACME_BANKING).size();

            post(LEAD, ACME, ACME_BANKING, suiteInput("One"), "   ");
            post(LEAD, ACME, ACME_BANKING, suiteInput("Two"), "   ");
            post(LEAD, ACME, ACME_BANKING, suiteInput("Three"), "");
            post(LEAD, ACME, ACME_BANKING, suiteInput("Four"), "");

            assertThat(liveSuiteIds(ACME, ACME_BANKING)).hasSize(before + 4);
        }

        @Test
        @DisplayName("the same key in a DIFFERENT project creates a suite — the scope is the index's")
        void keysAreScopedToTheProject() throws Exception {
            // The unique index is (company_id, project_id, key) and the lookup names the same
            // three. A company-wide constraint under a per-project lookup would pass the check and
            // violate the index — a validated request that 500s on insert.
            String key = "intent-shared";

            MvcResult inBanking = post(LEAD, ACME, ACME_BANKING, suiteInput("Banking"), key);
            MvcResult inMobile = post(LEAD, ACME, ACME_MOBILE, suiteInput("Mobile"), key);

            assertThat(inBanking.getResponse().getStatus()).isEqualTo(201);
            assertThat(inMobile.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(inMobile).get("id").asText())
                    .isNotEqualTo(body(inBanking).get("id").asText());
        }

        @Test
        @DisplayName("an absurdly long key is refused rather than stored")
        void anOverlongKeyIsRefused() throws Exception {
            int before = liveSuiteIds(ACME, ACME_BANKING).size();
            MvcResult result =
                    post(LEAD, ACME, ACME_BANKING, suiteInput("Long key"), "k".repeat(201));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result).get("errors").has("Idempotency-Key")).isTrue();
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).hasSize(before);
        }
    }


    // =====================================================================
    // Membership — the two arms a suite owns (§96)
    // =====================================================================

    @Nested
    @DisplayName("Membership — tag rules and added cases, reconciled like a set")
    class Membership {

        @Test
        @DisplayName("a create stores its rules and its added cases, and serves them back")
        void createStoresMembership() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Smoke\",\"tagRules\":[\"smoke\",\"  smoke  \",\"  \"],"
                            + "\"caseIds\":[\"" + seededCase(0) + "\"]}");

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            // Trimmed and de-duplicated — the same normalization a case's tags get, because a rule
            // and a tag are matched against each other exactly and one of them being trimmed is
            // the difference between a rule that works and one that never matches.
            List<String> rules = new ArrayList<>();
            body(created).get("tagRules").forEach(node -> rules.add(node.asText()));
            assertThat(rules).containsExactly("smoke");
            assertThat(body(created).get("caseIds")).hasSize(1);
            assertThat(storedTagRules(ACME, UUID.fromString(body(created).get("id").asText())))
                    .containsExactly("smoke");
        }

        @Test
        @DisplayName("an ABSENT field leaves membership alone; [] clears it")
        void absentAndEmptyAreDifferentInstructions() throws Exception {
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Claiming\",\"tagRules\":[\"smoke\"]}"))
                    .get("id").asText());

            // The rename dialog's payload: a name and nothing else. Reading that as "clear them"
            // would empty a collection somebody assembled, through a save that never mentioned it.
            patch(LEAD, ACME, ACME_BANKING, id, suiteInput("Renamed"), null);
            assertThat(storedTagRules(ACME, id))
                    .as("a rename must not empty a collection it never mentioned")
                    .containsExactly("smoke");

            patch(LEAD, ACME, ACME_BANKING, id,
                    "{\"name\":\"Renamed\",\"tagRules\":[]}", null);
            assertThat(storedTagRules(ACME, id)).isEmpty();
        }

        @Test
        @DisplayName("a SIBLING PROJECT's case is refused as a field error, not stored")
        void aSiblingProjectsCaseIsRefused() throws Exception {
            // It satisfies the foreign key, the company column and RLS. Without the project bound
            // it would be stored, and every later read of this suite would resolve a case through
            // a project the reader was never granted.
            MvcResult result = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Borrowing\",\"caseIds\":[\"" + siblingCase() + "\"]}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result).get("errors").has("caseIds")).isTrue();
        }

        @Test
        @DisplayName("the count on the response is the resolved membership, not the filed cases")
        void theCountIsTheUnion() throws Exception {
            // The folder holds nothing filed. Its member is the case it just claimed by tag, and
            // the number is served by the same renderer the tree reads — so create, reload and
            // tree cannot disagree about what the folder holds.
            tagCase(ACME, seededCase(0), "smoke");

            JsonNode created = body(post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Resolving\",\"tagRules\":[\"smoke\"]}"));

            assertThat(created.get("caseCount").asLong()).isEqualTo(1);
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — keyed on the field, and the parent is checked")
    class Validation {

        @Test
        @DisplayName("a blank name is a 400 keyed on `name`")
        void aBlankNameIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING, suiteInput("   "));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("name"))
                    .as("the key must match the input schema's field name or the SPA lands the "
                            + "message nowhere")
                    .isTrue();
        }

        @Test
        @DisplayName("a missing name is refused the same way as a blank one")
        void aMissingNameIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING, "{}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("name")).isTrue();
        }

        @Test
        @DisplayName("an over-long name is refused")
        void anOverLongNameIsRefused() throws Exception {
            String tooLong = "x".repeat(SuiteEdit.MAX_NAME + 1);

            assertThat(post(LEAD, ACME, ACME_BANKING, suiteInput(tooLong))
                    .getResponse().getStatus()).isEqualTo(400);
            assertThat(post(LEAD, ACME, ACME_BANKING,
                    suiteInput("x".repeat(SuiteEdit.MAX_NAME))).getResponse().getStatus())
                    .as("the boundary itself is accepted — otherwise the cap is off by one and "
                            + "nothing says so")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a blank description is stored as null, not as whitespace")
        void aBlankDescriptionIsNulled() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Described\",\"description\":\"   \"}");

            assertThat(body(created).get("description").isNull())
                    .as("stored as-is it renders as an empty paragraph, which reads as data loss")
                    .isTrue();
        }
    }

    // =====================================================================
    // Rename — and the move it refuses
    // =====================================================================

    @Nested
    @DisplayName("Rename — content only, and a move is refused rather than dropped")
    class Rename {

        @Test
        @DisplayName("the name and description change and the response carries them")
        void renamingWorks() throws Exception {
            MvcResult renamed = patch(LEAD, ACME, ACME_BANKING, SPARE,
                    "{\"name\":\"Renamed\",\"description\":\"Now described\"}", null);

            assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(renamed).get("name").asText()).isEqualTo("Renamed");
            assertThat(body(renamed).get("description").asText()).isEqualTo("Now described");
            assertThat(nameOf(ACME, SPARE)).isEqualTo("Renamed");
        }

        @Test
        @DisplayName("an unknown field in the body is ignored, not a 400")
        void anUnknownFieldIsIgnored() throws Exception {
            /*
              This replaces §98's four deleted rename tests, and it is the one that still has a
              defect to catch. They were: a rename leaves every path alone (the whole argument for
              a key-derived label — a name-derived one rewrote PAYMENTS' path and orphaned REFUNDS',
              while the tree kept rendering because it was built from parentId and only subtree
              matching silently stopped finding cases); a payload asking to MOVE was a 400 rather
              than a silently ignored field; repeating the current parent was accepted because it
              is not a move; and omitting it on a root was accepted.

              `parentId` is off `SuiteInput` now, so an SPA that has not been rebuilt — or an
              importer written against the old contract — still sends it. It must be IGNORED, not
              refused: Jackson is configured to ignore unknown properties, and a 400 here would
              break every stale client on a field the server no longer has an opinion about.
            */
            String withStaleField =
                    "{\"name\":\"Renamed anyway\",\"parentId\":\"" + PAYMENTS + "\"}";

            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, withStaleField, null)
                    .getResponse().getStatus())
                    .as("an unknown property is ignored — a stale client must not be refused on a "
                            + "field that no longer exists")
                    .isEqualTo(200);
            assertThat(nameOf(ACME, SPARE)).isEqualTo("Renamed anyway");
        }

        @Test
        @DisplayName("a stale If-Match is a 409 and changes nothing")
        void aStalePreconditionIsRefused() throws Exception {
            long current = lockVersionOf(ACME, SPARE);

            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, SPARE,
                    suiteInput("Overwritten"), String.valueOf(current + 7));

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(nameOf(ACME, SPARE)).isEqualTo("Spare");
        }

        @Test
        @DisplayName("the matching If-Match is accepted, quoted or bare")
        void aCurrentPreconditionIsAccepted() throws Exception {
            long current = lockVersionOf(ACME, SPARE);

            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Bare"),
                    String.valueOf(current)).getResponse().getStatus())
                    .isEqualTo(200);

            long next = lockVersionOf(ACME, SPARE);
            assertThat(next).as("the version moved, so the second call is a real second read")
                    .isNotEqualTo(current);
            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Quoted"),
                    "\"" + next + "\"").getResponse().getStatus())
                    .as("HTTP defines If-Match as carrying entity tags, which are quoted — a "
                            + "client library that formats it correctly is not wrong")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("an unparseable If-Match is a 400, not a 409")
        void aMalformedPreconditionIsNotAConflict() throws Exception {
            // 409 would send a client with a malformed header into a reload-and-retry loop that
            // cannot succeed.
            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Nope"),
                    "not-a-version");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("If-Match")).isTrue();
        }

        @Test
        @DisplayName("no If-Match at all is last-write-wins")
        void anAbsentPreconditionIsAllowed() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Importer"), null)
                    .getResponse().getStatus())
                    .as("right for an importer with no prior read; the SPA always sends one")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a rename writes an audit row")
        void theRenameIsAudited() throws Exception {
            patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Audited rename"), null);

            assertThat(auditEvents(ACME).get("suite.renamed")).contains("Audited rename");
        }

        @Test
        @DisplayName("a suite that does not exist is a 404")
        void anUnknownSuiteIsNotFound() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, UUID.randomUUID(),
                    suiteInput("Ghost"), null).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // Delete — and the refusal that is the feature
    // =====================================================================

    @Nested
    @DisplayName("Delete — soft, and only when the folder is genuinely empty")
    class Delete {

        @Test
        @DisplayName("an empty suite is deleted, answers 204 with no body, and leaves the tree")
        void anEmptySuiteIsDeleted() throws Exception {
            MvcResult deleted = delete(LEAD, ACME, ACME_BANKING, SPARE);

            assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
            assertThat(deleted.getResponse().getContentAsString()).isEmpty();
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).doesNotContain(SPARE);
        }

        @Test
        @DisplayName("the delete is soft — the row survives with deleted_at set")
        void theRowIsKept() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, SPARE);

            // Hard-deleting would rewrite what past runs covered: executions reference the cases
            // beneath a suite and suite_run_summary keys on the id.
            assertThat(deletedAtOf(ACME, SPARE)).isNotNull();
        }

        @Test
        @DisplayName("a suite holding live cases is refused with the count, and keeps them")
        void aSuiteWithCasesIsRefused() throws Exception {
            MvcResult refused = delete(LEAD, ACME, ACME_BANKING, REFUNDS);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText())
                    .as("a user looking at a collapsed folder cannot see what is in it, so the "
                            + "count is the actionable part")
                    .contains("2 cases");
            assertThat(liveSuiteIds(ACME, ACME_BANKING))
                    .as("a cascade would soft-delete somebody else's cases through a folder "
                            + "action, with no preview")
                    .contains(REFUNDS);
            assertThat(liveCaseCount(ACME, ACME_BANKING, REFUNDS)).isEqualTo(2);
        }

        @Test
        @DisplayName("a suite that only CLAIMS cases deletes — the claim goes, the cases stay")
        void aSuiteThatOnlyClaimsCasesIsDeletable() throws Exception {
            /*
              §98 replaced two tests here, and this is the decision that replaced them.

              They were "a suite holding child suites is refused with the count" and "a suite whose
              only occupant is an EMPTY child is still refused" — the latter existing because every
              other non-empty folder in this fixture also held cases somewhere beneath, so the
              subtree case count refused those too and a deleted child check SURVIVED the mutation.
              Both are gone with the tree.

              What is left is the narrowing that came with it: only the FILED arm blocks. PAYMENTS
              files nothing; it is given a tag rule here that claims a case filed in REFUNDS, so it
              resolves to one member and still deletes — and that case must still be there
              afterwards. Deleting a collection takes a claim, never a case. If this 409s, the gate
              has widened from the filed arm to the resolved membership, which would make a
              tag-defined suite undeletable because somebody else tagged a case.
            */
            tagCase(ACME, seededCase(0), "claimed");
            assertThat(patch(LEAD, ACME, ACME_BANKING, PAYMENTS,
                    "{\"name\":\"Payments\",\"tagRules\":[\"claimed\"]}", null)
                    .getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(delete(LEAD, ACME, ACME_BANKING, PAYMENTS).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).doesNotContain(PAYMENTS);
            assertThat(liveCaseCount(ACME, ACME_BANKING, REFUNDS))
                    .as("the claimed case survives the collection that claimed it")
                    .isEqualTo(2);
        }


        @Test
        @DisplayName("emptying a suite makes it deletable — the refusal is about state, not identity")
        void clearingTheFolderUnblocksTheDelete() throws Exception {
            // Without this the 409s above pass equally well against a delete that refuses
            // everything, which is a working endpoint and a useless one.
            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUNDS).getResponse().getStatus())
                    .isEqualTo(409);

            softDeleteCasesIn(ACME, REFUNDS);

            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUNDS).getResponse().getStatus())
                    .as("the cases are gone, so the folder is empty and the same call succeeds")
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a soft-deleted case does not keep its folder alive")
        void deletedCasesDoNotCount() throws Exception {
            // Counting them would make a folder whose cases were all deleted permanently
            // undeletable, with the UI showing it empty and the server disagreeing.
            softDeleteCasesIn(ACME, REFUNDS);

            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUNDS).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a deleted suite cannot be deleted twice — the second call is 404")
        void deletingTwiceIsNotFound() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(delete(LEAD, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .as("204 twice would report success for a delete this request did not do")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a deleted suite cannot be renamed either")
        void aDeletedSuiteIsGoneFromEveryWrite() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, SPARE);

            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Zombie"), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a delete writes an audit row naming the suite")
        void theDeleteIsAudited() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, SPARE);

            // The name is in the row because a deleted suite's name is otherwise only recoverable
            // by reading a soft-deleted row, which is exactly what an audit trail should not need.
            assertThat(auditEvents(ACME).get("suite.deleted")).contains("Spare").contains("TS-3");
        }

        @Test
        @DisplayName("cases in ANOTHER project's suite do not block this delete")
        void theEmptinessCheckIsScopedToTheProject() throws Exception {
            // The subtree count is a path match, and paths are not unique across projects — two
            // projects can both have a suite at path `s1`. Without the project predicate, a folder
            // is undeletable because a different project's folder has cases in it, which is
            // unexplainable from the screen the user is on.
            assertThat(delete(LEAD, ACME, ACME_MOBILE, mobileSpare()).getResponse().getStatus())
                    .isEqualTo(204);
        }
    }

    // =====================================================================
    // The ladder — PUT /suites/{suiteId}/status
    //
    // Suites have had exactly one writer of lifecycle_status since V1 (Suite.create, writing
    // DRAFT), so three of the four values were unreachable and every suite on every real instance
    // has been a draft for its entire life. These tests are the first thing that moves one.
    // =====================================================================

    @Nested
    @DisplayName("The ladder — the gate is decided by the edge, not by the target")
    class Ladder {

        @Test
        @DisplayName("an engineer submits a draft for review — §6g: submitting needs only edit rights")
        void engineerSubmitsForReview() throws Exception {
            MvcResult result = putStatus(ENGINEER, ACME, ACME_BANKING, DRAFTED, "IN_REVIEW");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).get("lifecycleStatus").asText()).isEqualTo("IN_REVIEW");
            assertThat(storedStatus(ACME, DRAFTED)).isEqualTo("IN_REVIEW");
            assertThat(auditEvents(ACME)).containsKey("suite.submitted");
        }

        @Test
        @DisplayName("an engineer withdraws their own submission — nobody had signed anything off")
        void engineerWithdrawsFromReview() throws Exception {
            assertThat(putStatus(ENGINEER, ACME, ACME_BANKING, REVIEWING, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(storedStatus(ACME, REVIEWING)).isEqualTo("DRAFT");
            assertThat(auditEvents(ACME)).containsKey("suite.withdrawn");
        }

        @Test
        @DisplayName("an engineer cannot approve — suite:approve is the lead's, and it is 403")
        void engineerCannotApprove() throws Exception {
            assertThat(putStatus(ENGINEER, ACME, ACME_BANKING, REVIEWING, "APPROVED")
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(storedStatus(ACME, REVIEWING)).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("a lead approves a reviewed suite")
        void leadApproves() throws Exception {
            MvcResult result = putStatus(LEAD, ACME, ACME_BANKING, REVIEWING, "APPROVED");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(storedStatus(ACME, REVIEWING)).isEqualTo("APPROVED");
            assertThat(auditEvents(ACME).get("suite.approved"))
                    .contains("TS-95", "IN_REVIEW", "APPROVED");
        }

        /**
         * The arm a target-decides gate misses, and the whole reason
         * {@link LifecycleLadder#permissionFor} takes both ends.
         *
         * <p>{@code DRAFT} is not an approval-shaped target, so a rule reading the target alone
         * answers {@code suite:manage} — which this caller holds. The move is nonetheless the
         * unmaking of a lead's sign-off.
         */
        @Test
        @DisplayName("an engineer cannot take a lead's approval back by asking for DRAFT")
        void engineerCannotRescindAnApproval() throws Exception {
            assertThat(putStatus(ENGINEER, ACME, ACME_BANKING, SIGNED_OFF, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(storedStatus(ACME, SIGNED_OFF)).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("a lead takes an approval back, and the trail says so was not a withdrawal")
        void leadRescindsAnApproval() throws Exception {
            assertThat(putStatus(LEAD, ACME, ACME_BANKING, SIGNED_OFF, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(auditEvents(ACME))
                    .containsKey("suite.unapproved")
                    .doesNotContainKey("suite.withdrawn");
        }

        /**
         * ~~An engineer cannot deprecate — retiring is the soft form of a delete, and
         * {@code suite:delete} is withheld from Test Engineer outright, so leaving the retire at the
         * edit key would route around it with a status change.~~ → <strong>inverted in §208.</strong>
         *
         * <p>The routing-around argument does not survive being stated precisely on a suite:
         * {@code suite:delete} <em>removes</em> the folder, and retiring keeps it, keeps its members
         * and can be undone. Retiring a draft folder is the same kind of act as renaming one, and
         * {@code suite:manage} is the key a contributor already uses for that. What retiring routes
         * around, if anything, is nothing — and it is the only way out of a folder that cannot be
         * deleted while it holds cases.
         */
        @Test
        @DisplayName("an engineer deprecates a draft suite — suite:manage is the structure key")
        void engineerDeprecatesADraft() throws Exception {
            assertThat(putStatus(ENGINEER, ACME, ACME_BANKING, DRAFTED, "DEPRECATED")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(storedStatus(ACME, DRAFTED)).isEqualTo("DEPRECATED");
        }

        /**
         * The half §208 keeps. Retiring a folder somebody signed off ends that sign-off, so it costs
         * what ending it costs, whatever the target is called.
         */
        @Test
        @DisplayName("an engineer cannot deprecate an APPROVED suite")
        void engineerCannotDeprecateAnApprovedSuite() throws Exception {
            assertThat(putStatus(ENGINEER, ACME, ACME_BANKING, SIGNED_OFF, "DEPRECATED")
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(storedStatus(ACME, SIGNED_OFF)).isEqualTo("APPROVED");
        }

        /**
         * §208's symmetry, and the reason retiring is not a one-way door for the contributor who may
         * now walk through it. Nothing is unmade: {@code DEPRECATED} already stands
         * {@code OUTSIDE_REVIEW}, so whatever sign-off existed ended at the retirement — performed
         * by somebody who held {@code suite:approve} to do it — and what comes back is a draft.
         */
        @Test
        @DisplayName("an engineer revives a retired suite, and it comes back as a DRAFT")
        void engineerRevivesARetiredSuite() throws Exception {
            assertThat(putStatus(ENGINEER, ACME, ACME_BANKING, RETIRED, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(storedStatus(ACME, RETIRED)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("approval is reachable only from review — the one gate guards the only door")
        void approvalOnlyFromReview() throws Exception {
            MvcResult result = putStatus(LEAD, ACME, ACME_BANKING, DRAFTED, "APPROVED");

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(result).get("detail").asText())
                    .contains("reviewed")
                    .contains("suite");
            assertThat(storedStatus(ACME, DRAFTED)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("review is reachable only from draft — an approved suite loses its approval first")
        void reviewOnlyFromDraft() throws Exception {
            MvcResult result = putStatus(LEAD, ACME, ACME_BANKING, SIGNED_OFF, "IN_REVIEW");

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(result).get("detail").asText()).contains("draft");
            assertThat(storedStatus(ACME, SIGNED_OFF)).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("a deprecated suite comes back as a draft, never straight to approval")
        void aRetiredSuiteReturnsAsADraft() throws Exception {
            MvcResult refused = putStatus(LEAD, ACME, ACME_BANKING, RETIRED, "APPROVED");
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText()).contains("deprecated");

            assertThat(putStatus(LEAD, ACME, ACME_BANKING, RETIRED, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(auditEvents(ACME)).containsKey("suite.revived");
        }

        @Test
        @DisplayName("setting the status a suite already has writes nothing at all")
        void alreadyThereIsA200AndNoRow() throws Exception {
            MvcResult result = putStatus(LEAD, ACME, ACME_BANKING, SIGNED_OFF, "APPROVED");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).get("lifecycleStatus").asText()).isEqualTo("APPROVED");
            // No audit row: a repeated click must not bury the change that actually happened.
            assertThat(auditEvents(ACME)).isEmpty();
        }

        @Test
        @DisplayName("a status change writes no version row — a version is composition, not a verdict")
        void approvingWritesNoVersion() throws Exception {
            assertThat(putStatus(LEAD, ACME, ACME_BANKING, REVIEWING, "APPROVED")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(versionRowsFor(ACME, REVIEWING)).isZero();
        }

        @Test
        @DisplayName("a status that is not a status is a field error, not a 500 from Jackson")
        void anUnknownStatusIsAFieldError() throws Exception {
            MvcResult result = putStatus(LEAD, ACME, ACME_BANKING, DRAFTED, "SIGNED");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result).get("errors").has("status")).isTrue();
        }

        @Test
        @DisplayName("a viewer is refused before learning the vocabulary an unknown status would name")
        void aViewerGetsA403NotAFieldError() throws Exception {
            // The parse must happen before the gate (the permission is a function of the edge), so
            // an unparseable status falls back to the LOWER of the two keys — otherwise this
            // caller would be told what the ladder accepts.
            assertThat(putStatus(VIEWER, ACME, ACME_BANKING, DRAFTED, "SIGNED")
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a sibling company's member gets 404, not 403 — the id is not theirs to confirm")
        void anOutsiderGets404() throws Exception {
            assertThat(putStatus(OUTSIDER, UMBRA, ACME_BANKING, DRAFTED, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(storedStatus(ACME, DRAFTED)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a suite in another project of the same company is 404, not moved")
        void aSiblingProjectsSuiteIsNotReachable() throws Exception {
            // findLiveInProject, not findById: the latter is scoped by RLS to the COMPANY and would
            // accept this id while every cross-company test in the suite still passed (§69).
            assertThat(putStatus(LEAD, ACME, ACME_BANKING, LOCKED_SUITE, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(storedStatus(ACME, LOCKED_SUITE)).isEqualTo("DRAFT");
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private MvcResult putStatus(
            UUID userId, UUID companyId, UUID projectId, UUID suiteId, String status)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/suites/" + suiteId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andReturn();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult post(UUID userId, UUID companyId, UUID projectId, String payload)
            throws Exception {
        return post(userId, companyId, projectId, payload, null);
    }

    private MvcResult post(
            UUID userId, UUID companyId, UUID projectId, String payload, String idempotencyKey)
            throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/suites")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult patch(
            UUID userId, UUID companyId, UUID projectId, UUID suiteId, String payload,
            String ifMatch) throws Exception {

        var request = MockMvcRequestBuilders
                .patch("/api/v1/projects/" + projectId + "/suites/" + suiteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult delete(UUID userId, UUID companyId, UUID projectId, UUID suiteId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/suites/" + suiteId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("expected a body, got none (status %d)",
                result.getResponse().getStatus()).isNotEmpty();
        return json.readTree(content);
    }

    private String keyOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("a key is only allocated on a successful create; body was %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result).get("key").asText();
    }

    private static String suiteInput(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    /** {@link #suiteInput(String)} with both condition lists — always both, never one. */
    private static String suiteInput(String name, List<UUID> pre, List<UUID> post) {
        return "{\"name\":\"" + name + "\",\"preconditions\":" + refs(pre)
                + ",\"postconditions\":" + refs(post) + "}";
    }

    private static String refs(List<UUID> conditionIds) {
        return conditionIds.stream()
                .map(id -> "{\"conditionId\":\"" + id + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<String> titlesOf(JsonNode array) {
        List<String> titles = new ArrayList<>();
        array.forEach(entry -> titles.add(entry.get("title").asText()));
        return titles;
    }

    private UUID idOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("expected a created suite; body was %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(result).get("id").asText());
    }

    /** The stored positions of one suite's attachments of one kind, in position order. */
    private List<Integer> positionsOf(UUID suiteId, String kind) throws SQLException {
        List<Integer> positions = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT position FROM suite_condition WHERE suite_id = CAST(? AS uuid) "
                             + "AND kind = ? ORDER BY position")) {
            statement.setString(1, suiteId.toString());
            statement.setString(2, kind);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    positions.add(rows.getInt(1));
                }
            }
        }
        return positions;
    }

    /** The attachment row's own id — what an opt-out points at, and what a reorder must not change. */
    private UUID attachmentIdOf(UUID suiteId, UUID conditionId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM suite_condition WHERE suite_id = CAST(? AS uuid) "
                             + "AND condition_id = CAST(? AS uuid) AND kind = \'PRE\'")) {
            statement.setString(1, suiteId.toString());
            statement.setString(2, conditionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no PRE attachment of that condition").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    /**
     * One case declining one inherited attachment — written by hand because nothing in the product
     * writes {@code case_condition_optout} yet either. That gap is real and is named in the handoff;
     * it does not block asserting that the toggle's row survives a reorder, which is the property
     * the reconcile is shaped around.
     */
    private void optOut(UUID caseId, UUID suiteConditionId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO case_condition_optout (company_id, case_id, suite_condition_id)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                     ON CONFLICT DO NOTHING
                     """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, suiteConditionId.toString());
            statement.executeUpdate();
        }
    }

    private int optOutsAgainst(UUID suiteConditionId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM case_condition_optout "
                             + "WHERE suite_condition_id = CAST(? AS uuid)")) {
            statement.setString(1, suiteConditionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    // =====================================================================
    // helpers — reading the database back
    //
    // Through the APP role and the company's own context, not the container superuser: it is the
    // policed path production reads through, so an assertion that could not be made under RLS is an
    // assertion about a database the product never runs on.
    // =====================================================================

    private List<UUID> liveSuiteIds(UUID companyId, UUID projectId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT id FROM suite WHERE project_id = CAST(? AS uuid) "
                             + "AND deleted_at IS NULL")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private String nameOf(UUID companyId, UUID suiteId) throws SQLException {
        return suiteColumn(companyId, suiteId, "name");
    }

    private String deletedAtOf(UUID companyId, UUID suiteId) throws SQLException {
        return suiteColumn(companyId, suiteId, "CAST(deleted_at AS text)");
    }

    private long lockVersionOf(UUID companyId, UUID suiteId) throws SQLException {
        return Long.parseLong(suiteColumn(companyId, suiteId, "CAST(lock_version AS text)"));
    }

    /** {@code column} is a literal from this class only — never request input. */
    private String suiteColumn(UUID companyId, UUID suiteId, String column) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM suite WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("suite %s is not visible to company %s", suiteId,
                        companyId).isTrue();
                return rows.getString(1);
            }
        }
    }

    private List<Integer> versionNumbersOf(UUID companyId, UUID suiteId) throws SQLException {
        List<Integer> numbers = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT version_no FROM suite_version WHERE suite_id = CAST(? AS uuid) "
                             + "ORDER BY version_no")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    numbers.add(rows.getInt(1));
                }
            }
        }
        return numbers;
    }

    private long liveCaseCount(UUID companyId, UUID projectId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM test_case WHERE project_id = CAST(? AS uuid) "
                             + "AND suite_id = CAST(? AS uuid) AND deleted_at IS NULL")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * The stored status, read back rather than taken from the response.
     *
     * <p>A response echoing what it was sent proves the controller can serialize, not that anything
     * was written; and the refusal assertions need the row to be <em>unchanged</em>, which no
     * response body can show.
     */
    private String storedStatus(UUID companyId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT lifecycle_status FROM suite WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("suite %s is not readable in %s", suiteId, companyId)
                        .isTrue();
                return rows.getString(1);
            }
        }
    }

    private long versionRowsFor(UUID companyId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM suite_version WHERE suite_id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private Map<String, String> auditEvents(UUID companyId) throws SQLException {
        Map<String, String> events = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action, CAST(changes AS text) FROM audit_event ORDER BY occurred_at")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return events;
    }

    private void softDeleteCasesIn(UUID companyId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "UPDATE test_case SET deleted_at = now() WHERE suite_id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
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
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("suite-write-endpoint:" + marker).getBytes());
    }

    /**
     * Seeds the vocabularies, statuses and — the part this class needs — {@code key_sequence}.
     * Without it the allocator finds no row and every create is a 500, which is migration V5's
     * defect reproduced in a fixture.
     */
    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    /** The one spare folder in the sibling project, for the project-scoped emptiness assertion. */
    private static UUID mobileHolder() {
        return UUID.fromString("f1004000-0000-4000-8000-000000000020");
    }

    private static UUID mobileSpare() {
        return UUID.fromString("f1004000-0000-4000-8000-000000000006");
    }

    /**
     * The trees, written as raw SQL through the app role.
     *
     * <p><strong>The sibling project's suite is deliberately given the same {@code path} as this
     * project's</strong> — both {@code s1} — because paths are unique per project and nothing stops
     * two projects sharing one. That collision is what makes the subtree emptiness check's
     * {@code project_id} predicate observable; without it the assertion would pass either way.
     */
    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1",
                    "DRAFT");
            suite(connection, REFUNDS, ACME, ACME_BANKING, null, "TS-2", 2, "Refunds", "s1.s2",
                    "DRAFT");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3", "DRAFT");
            suite(connection, NEST_PARENT, ACME, ACME_BANKING, null, "TS-92", 92, "Nest", "s92",
                    "DRAFT");
            suite(connection, NEST_CHILD, ACME, ACME_BANKING, null, "TS-93", 93,
                    "Nest child", "s92.s93", "DRAFT");

            // One suite per status, so every edge of the ladder has a start row and no test has to
            // move a suite twice to reach the state it is about to assert on.
            suite(connection, DRAFTED, ACME, ACME_BANKING, null, "TS-94", 94, "Drafted", "s94",
                    "DRAFT");
            suite(connection, REVIEWING, ACME, ACME_BANKING, null, "TS-95", 95, "Reviewing", "s95",
                    "IN_REVIEW");
            suite(connection, SIGNED_OFF, ACME, ACME_BANKING, null, "TS-96", 96, "Signed off",
                    "s96", "APPROVED");
            suite(connection, RETIRED, ACME, ACME_BANKING, null, "TS-97", 97, "Retired", "s97",
                    "DEPRECATED");

            // Same path as ACME_BANKING's PAYMENTS, in the same company — see the header.
            suite(connection, mobileSpare(), ACME, ACME_MOBILE, null, "TS-90", 90, "Mobile spare",
                    "s1", "DRAFT");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, null, "TS-91", 91, "Internal", "s91",
                    "DRAFT");
            // A second sibling folder, holding the sibling case §96's caseIds bound is refused
            // with — deliberately not `mobileSpare()`, which the delete section empties.
            suite(connection, mobileHolder(), ACME, ACME_MOBILE, null, "TS-89", 89, "Mobile holder",
                    "s89", "DRAFT");

            // Two live cases under Refunds — at path `s1.s2`, which is INSIDE the subtree of
            // ACME_BANKING's `s1`. The sibling project's spare folder is also at `s1` and is
            // deliberately left empty: a subtree count that drops its project predicate matches
            // these two cases when asked about the sibling, and refuses a delete of an empty folder
            // for reasons nothing on the user's screen can explain. Both folders are in the same
            // company, so only project_id separates them.
            cases(connection, ACME, ACME_BANKING, REFUNDS, "sw-ref", 2);
            // A case in the SIBLING project, so `caseIds` has something to be refused with. It
            // satisfies suite_case's foreign key, the company column and RLS — only the project
            // predicate refuses it, which is why it needs a row rather than a random UUID.
            cases(connection, ACME, ACME_MOBILE, mobileHolder(), "sw-mob", 1);

            // The counter was reset to 1 and the fixture occupies 1..3, so the next allocation must
            // be 4. Set explicitly rather than left implicit: TS-90/TS-91 exist to keep the sibling
            // trees out of the sequence a create walks, and MAX(key_seq)+1 would answer 92 — which
            // is one of the two implementations DisplayKeys exists to refuse.
            try (var statement = connection.prepareStatement(
                    "UPDATE key_sequence SET next_value = 4 WHERE entity_type = 'SUITE'")) {
                statement.executeUpdate();
            }
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // TS-1 again: keys are numbered per COMPANY, so the collision is the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1",
                    "DRAFT");
            try (var statement = connection.prepareStatement(
                    "UPDATE key_sequence SET next_value = 2 WHERE entity_type = 'SUITE'")) {
                statement.executeUpdate();
            }
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

    /**
     * Seeds a suite in a named status.
     *
     * <p>The status used to be hard-coded {@code 'APPROVED'} here, and that was §72's fixture
     * defect one aggregate over: <strong>no suite on any real instance has ever been anything but
     * {@code DRAFT}</strong>, because until §75 nothing but {@code Suite.create} wrote the column.
     * A fixture that never sits in the state a fresh install produces hides exactly the gap the
     * product has — so the main tree is drafted below, and the statuses a ladder test needs are
     * asked for explicitly by the four suites that need them.
     */
    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path, String status) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, status);
            statement.executeUpdate();
        }
    }

    /** The id {@link #cases} assigned to the {@code index}-th REFUNDS case. */
    private static UUID seededCase(int index) {
        return UUID.nameUUIDFromBytes(("sw-ref" + index).getBytes());
    }

    /** The sibling project's only case — same company, different project. */
    private static UUID siblingCase() {
        return UUID.nameUUIDFromBytes(("sw-mob" + 0).getBytes());
    }

    private void tagCase(UUID companyId, UUID caseId, String tag) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "INSERT INTO case_tag (company_id, case_id, tag) "
                                + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?) "
                                + "ON CONFLICT DO NOTHING")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private List<String> storedTagRules(UUID companyId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT tag FROM suite_tag_rule WHERE suite_id = CAST(? AS uuid) "
                                + "ORDER BY tag")) {
            statement.setString(1, suiteId.toString());
            List<String> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
            }
            return found;
        }
    }

    private void cases(
            Connection connection, UUID companyId, UUID projectId, UUID suiteId, String marker,
            int live) throws SQLException {

        for (int index = 0; index < live; index++) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO test_case
                        (id, company_id, project_id, suite_id, key, key_seq, title)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                            ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1,
                        UUID.nameUUIDFromBytes((marker + index).getBytes()).toString());
                statement.setString(2, companyId.toString());
                statement.setString(3, projectId.toString());
                statement.setString(4, suiteId.toString());
                // Unique per company, so the marker keeps two fixtures from colliding on a key and
                // failing in a way that reads like a tenancy bug.
                statement.setString(5, "TC-" + marker + index);
                statement.setLong(6, 900L + index);
                statement.setString(7, "Seeded " + marker + " " + index);
                statement.executeUpdate();
            }
        }
    }
}
