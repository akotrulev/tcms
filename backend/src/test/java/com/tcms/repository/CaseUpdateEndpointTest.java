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
 * {@code PATCH /projects/{projectId}/cases/{caseId}} — the first write in the product.
 *
 * <h2>The failures this class exists to catch, and most of them are silent</h2>
 *
 * <ol>
 *   <li><strong>A write gated like a read.</strong> Every read in this module asks
 *       {@code project:read}, which all six roles hold — so a write gated the same way is one a
 *       Viewer may perform. A Viewer is the client's own stakeholder, invited specifically to read
 *       coverage without touching it. Nothing about that failure is visible in a test suite whose
 *       callers are all leads, so a Viewer gets its own assertions here.</li>
 *   <li><strong>Steps replaced instead of updated.</strong> {@code step_result} references
 *       {@code test_step.id}, so a save that deletes and reinserts orphans every recorded result
 *       and re-attaches historical evidence to the wrong action. The steps themselves come out
 *       <em>correct</em> either way — the only visible difference is the ids, so the ids are what is
 *       asserted.</li>
 *   <li><strong>A version written on every save.</strong> Plan §6j skips one whose content hash is
 *       unchanged; without that the version list becomes a log of who opened the case and stops
 *       being usable for finding the edit that broke a test.</li>
 *   <li><strong>A version written on no save.</strong> The opposite, and worse: an edit whose
 *       history row is missing is a hole exactly where something changed.</li>
 *   <li><strong>A stale editor overwriting a newer change.</strong> {@code @Version} cannot see it —
 *       the entity is loaded fresh inside the transaction, so its version always matches itself. The
 *       {@code If-Match} check is the one that catches it.</li>
 *   <li><strong>A field accepted and discarded.</strong> Six fields on the contract's input have no
 *       write path yet. Ignoring them is a save that reports success and drops what the user typed.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — sharing them with another test class is how
 * handoff §19's first defect happened, green alone and red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case update endpoint — the first write, and what it must refuse")
class CaseUpdateEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("e0000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("e0000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("e0001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling whose cases must not be writable. */
    private static final UUID ACME_MOBILE = UUID.fromString("e0001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("e0002000-0000-4000-8000-000000000001");

    /** Test Lead on ACME_BANKING. Holds {@code case:edit}. */
    private static final UUID LEAD = UUID.fromString("e0003000-0000-4000-8000-000000000001");
    /** Test Engineer on ACME_BANKING. Holds {@code case:edit} — unqualified, per the corrected §A5. */
    private static final UUID ENGINEER = UUID.fromString("e0003000-0000-4000-8000-000000000002");
    /** Viewer on ACME_BANKING. Holds {@code project:read} and NOT {@code case:edit}. */
    private static final UUID VIEWER = UUID.fromString("e0003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing at all. Persona 3 — must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("e0003000-0000-4000-8000-000000000004");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("e0003000-0000-4000-8000-000000000005");

    /** A live Feature value of ACME_BANKING — the ordinary case. */
    private static final UUID FEATURE_CARDS = UUID.fromString("e0005000-0000-4000-8000-000000000001");
    /** Its child, so a case can be classified at a leaf. */
    private static final UUID FEATURE_CARDS_3DS =
            UUID.fromString("e0005000-0000-4000-8000-000000000002");
    /** Retired in ACME_BANKING — the soft-deleted arm the reconcile must leave alone. */
    private static final UUID FEATURE_RETIRED =
            UUID.fromString("e0005000-0000-4000-8000-000000000003");
    /**
     * A live Feature value of ACME_MOBILE — the sibling project.
     *
     * <p>The whole reason this slice validates at all. It is in the same company, so it satisfies
     * {@code case_classification}'s foreign key, the company column, RLS, and every check V2's
     * trigger makes — it is refused only because the vocabulary this project was served does not
     * contain it.
     */
    private static final UUID FEATURE_MOBILE_ONLY =
            UUID.fromString("e0005000-0000-4000-8000-000000000004");

    private static final UUID PAYMENTS = UUID.fromString("e0004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("e0004000-0000-4000-8000-000000000002");
    private static final UUID MOBILE_SUITE = UUID.fromString("e0004000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_SUITE = UUID.fromString("e0004000-0000-4000-8000-000000000004");

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
            company(ACME, "update-acme", "Acme", now);
            company(UMBRA, "update-umbra", "Umbra", now);

            activeUser(LEAD, "lead@update-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@update-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@update-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@update-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@update-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "EAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "EAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("upd-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("upd-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("upd-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "EUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("upd-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedTrees();
        seedVocabularies();
        resetMutableRows();
        seedCases();
        seedSteps();
    }

    /**
     * The classification a case may be given — the company's three locked dimensions plus a Feature
     * value in each of the two projects.
     *
     * <p>The company-level values are seeded by {@code tcms_seed_company_defaults} with generated
     * ids, so they are looked up by name rather than pinned; the Feature values are inserted here
     * with fixed ids because the tests name them. {@code tcms_seed_project_defaults} has already
     * created each project's own (empty) Feature dimension in {@link #seedTrees()}.
     */
    private void seedVocabularies() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedCompanyDefaults(connection, ACME);
            featureValue(connection, FEATURE_CARDS, ACME_BANKING, null, "cards", "Cards", false);
            featureValue(connection, FEATURE_CARDS_3DS, ACME_BANKING, FEATURE_CARDS,
                    "cards.three_d_secure", "3-D Secure", false);
            featureValue(connection, FEATURE_RETIRED, ACME_BANKING, null,
                    "cheques", "Cheques", true);
            featureValue(connection, FEATURE_MOBILE_ONLY, ACME_MOBILE, null,
                    "biometrics", "Biometrics", false);
        }
    }

    /**
     * Deletes everything a test can write, so each one starts from the seeded state.
     *
     * <h2>The first test class in this project that needs this, and the reason is the obvious one</h2>
     *
     * <p>Every sibling endpoint test only reads, so {@code ON CONFLICT (id) DO NOTHING} was enough —
     * re-running the fixture against rows that could not have changed is a no-op. A write test's rows
     * <em>do</em> change: without this, a title renamed by one test is still renamed for the next, and
     * every version and audit row accumulates across the class. The first symptom is not a clear
     * failure either; it is a test asserting "no versions yet" that passes alone and fails in whatever
     * order the class happens to run.
     *
     * <p>Deleting the cases is enough for their children: {@code test_step.case_id} and
     * {@code test_case_version.case_id} both cascade. {@code audit_event} does not hang off a case, so
     * it is cleared explicitly. Suites, projects, memberships and grants are never written by any test
     * here and are left seeded.
     */
    private void resetMutableRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            delete(connection, "DELETE FROM audit_event");
            delete(connection, "DELETE FROM test_case");
        }
        try (Connection connection = appConnection(UMBRA)) {
            // Through the other company's own context, because RLS means the ACME connection above
            // could not see these rows to delete them — which is the isolation working, and is worth
            // noticing here rather than debugging as a leftover row later.
            delete(connection, "DELETE FROM audit_event");
            delete(connection, "DELETE FROM test_case");
        }
    }

    private void delete(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — three gates, and 403 only after the read gate has passed")
    class TheGate {

        @Test
        @DisplayName("a Test Lead holding case:edit updates the case")
        void leadCanEdit() throws Exception {
            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).get("title").asText()).isEqualTo("Renamed");
        }

        @Test
        @DisplayName("a Test Engineer edits a case they did not author — case:edit is NOT own-qualified")
        void engineerCanEditSomeoneElsesCase() throws Exception {
            // Roles spec §A5 listed case:edit among the OWN-qualified permissions, contradicting the
            // permission table and §A3's own principle: a Test Engineer is never blocked from
            // producing, only from unmaking someone else's work. The case here is owned by LEAD.
            // Corrected in the spec on 2026-07-31; this is the assertion that pins the reading.
            MvcResult result =
                    patch(ENGINEER, ACME, ACME_BANKING, caseId("full"), edit("Engineer's fix"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).get("title").asText()).isEqualTo("Engineer's fix");
        }

        @Test
        @DisplayName("a Viewer who CAN read the case gets 403, and the case is untouched")
        void viewerIsForbidden() throws Exception {
            // The failure this catches: gating the write on project:read, which every role holds.
            // A Viewer is the client's own stakeholder, invited to read coverage without touching it.
            assertThat(statusOf(VIEWER, ACME, ACME_BANKING, caseId("full")))
                    .as("a Viewer must not be able to read the case first and then rewrite it")
                    .isEqualTo(200);

            MvcResult result =
                    patch(VIEWER, ACME, ACME_BANKING, caseId("full"), edit("Viewer wrote this"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(titleOf(caseId("full"))).isEqualTo("Full refund to card");
        }

        @Test
        @DisplayName("the 403 body names the action and never the permission key")
        void forbiddenBodyIsActionable() throws Exception {
            MvcResult result =
                    patch(VIEWER, ACME, ACME_BANKING, caseId("full"), edit("nope"), null);

            JsonNode problem = body(result);
            assertThat(problem.get("status").asInt()).isEqualTo(403);
            assertThat(problem.get("detail").asText()).contains("permission to edit test cases");
            // Naming case:edit publishes the catalogue to the one caller who cannot use it.
            assertThat(result.getResponse().getContentAsString()).doesNotContain("case:edit");
        }

        @Test
        @DisplayName("a member with no grant gets 404, NOT 403 — persona 3")
        void noGrantIsNotFound() throws Exception {
            // 403 here would confirm the project and case exist, which is the inference channel §6t
            // closes. 403 is reachable only after the READ gate has passed, and this caller cannot
            // read the project at all.
            MvcResult result =
                    patch(NO_GRANT, ACME, ACME_BANKING, caseId("full"), edit("x"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("a case in a sibling project the caller was not granted answers 404")
        void aCaseOutsideTheGrantedProjectIsNotFound() throws Exception {
            // The second gate, and the one only a two-segment path needs. Without it this edits a
            // case in a project the caller holds nothing on — and every cross-company test in the
            // suite still passes, because the row genuinely is in the caller's company.
            MvcResult throughOwnPath =
                    patch(LEAD, ACME, ACME_MOBILE, caseId("mobile"), edit("x"), null);
            MvcResult throughGrantedPath =
                    patch(LEAD, ACME, ACME_BANKING, caseId("mobile"), edit("x"), null);

            assertThat(throughOwnPath.getResponse().getStatus()).isEqualTo(404);
            assertThat(throughGrantedPath.getResponse().getStatus()).isEqualTo(404);
            assertThat(titleOf(caseId("mobile"))).isEqualTo("Mobile login");
        }

        @Test
        @DisplayName("another client's case is 404 through either path, and nothing of theirs leaks")
        void crossCompanyIsNotFound() throws Exception {
            MvcResult theirs =
                    patch(LEAD, ACME, UMBRA_BANKING, caseId("umbra"), edit("x"), null);

            assertThat(theirs.getResponse().getStatus()).isEqualTo(404);
            assertThat(theirs.getResponse().getContentAsString())
                    .doesNotContain("RIVAL CO CONFIDENTIAL");
            assertThat(titleOf(caseId("umbra"))).isEqualTo("RIVAL CO CONFIDENTIAL refund");
        }

        @Test
        @DisplayName("a soft-deleted case answers 404")
        void deletedCaseIsNotFound() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("deleted"), edit("x"), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a nonexistent case answers 404")
        void unknownCaseIsNotFound() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, UUID.randomUUID(), edit("x"), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("unauthenticated is 401")
        void unauthenticatedIsRejected() throws Exception {
            int status = mvc.perform(MockMvcRequestBuilders
                            .patch(path(ACME_BANKING, caseId("full")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(edit("x"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(401);
        }
    }

    // =====================================================================
    // Versioning
    // =====================================================================

    @Nested
    @DisplayName("Versioning — written on change, skipped on none")
    class Versioning {

        @Test
        @DisplayName("the first edit writes a version and advances currentVersionNo")
        void firstEditWritesAVersion() throws Exception {
            assertThat(versionsOf(caseId("full"))).isEmpty();

            JsonNode after = body(patch(
                    LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed once"), null));

            assertThat(versionsOf(caseId("full"))).hasSize(1);
            assertThat(after.get("currentVersionNo").asInt()).isEqualTo(8);
        }

        @Test
        @DisplayName("that first version is numbered current+1, so the pre-versioning v1..v7 gap is visible")
        void theFirstVersionIsNumberedAfterTheStoredCounter() throws Exception {
            // Every case predates the write path: current_version_no defaults to 1 and no version
            // row has ever existed. Numbering this edit v7 would claim the edited text was what v7
            // said, in the one table whose purpose is that past claims stay true. The gap is honest.
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed once"), null);

            assertThat(versionsOf(caseId("full"))).singleElement()
                    .satisfies(row -> assertThat(row.versionNo()).isEqualTo(8));
        }

        @Test
        @DisplayName("saving identical content again writes NO version and does not advance the number")
        void anUnchangedSaveWritesNothing() throws Exception {
            // Plan §6j. Without this, opening a case and pressing Save produces an entry, and the
            // version list becomes a log of who looked at it.
            Map<String, Object> payload = edit("Stable title");

            JsonNode first = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null));
            JsonNode second = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null));

            assertThat(versionsOf(caseId("full"))).hasSize(1);
            assertThat(first.get("currentVersionNo").asInt())
                    .isEqualTo(second.get("currentVersionNo").asInt());
        }

        @Test
        @DisplayName("the lock version advances even when no content version was written")
        void lockVersionAdvancesOnEverySave() throws Exception {
            // The two counters are different numbers: one counts authored versions and is what a run
            // pins, the other counts writes and exists to make a concurrent edit a 409. Conflating
            // them puts a number beside the title that no history entry matches.
            Map<String, Object> payload = edit("Stable title");

            JsonNode first = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null));
            JsonNode second = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null));

            assertThat(second.get("lockVersion").asLong())
                    .isGreaterThan(first.get("lockVersion").asLong());
            assertThat(second.get("currentVersionNo").asInt())
                    .isEqualTo(first.get("currentVersionNo").asInt());
        }

        @Test
        @DisplayName("a changed field after an unchanged save writes the next version")
        void aRealChangeAfterANoOpStillVersions() throws Exception {
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("One"), null);
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("One"), null);
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Two"), null);

            assertThat(versionsOf(caseId("full")))
                    .extracting(VersionRow::versionNo)
                    .containsExactly(9, 8);
        }

        @Test
        @DisplayName("a new change comment alone is a change, because the comment is authored content")
        void aCommentOnlySaveWritesAVersion() throws Exception {
            // The comment is the sentence the version list displays. One that silently failed to
            // save because nothing else changed would be a note the author believes they left.
            Map<String, Object> first = edit("Same title");
            first.put("changeComment", "Initial tidy-up");
            Map<String, Object> second = edit("Same title");
            second.put("changeComment", "Actually, corrected the expected balance");

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), first, null);
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), second, null);

            assertThat(versionsOf(caseId("full")))
                    .extracting(VersionRow::changeComment)
                    .containsExactly("Actually, corrected the expected balance", "Initial tidy-up");
        }

        @Test
        @DisplayName("the version records its author and the comment, and is not pinned")
        void theVersionRecordsWhoAndWhy() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("changeComment", "Clarified the expected outcome");

            patch(ENGINEER, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(versionsOf(caseId("full"))).singleElement().satisfies(row -> {
                assertThat(row.createdBy()).isEqualTo(ENGINEER);
                assertThat(row.changeComment()).isEqualTo("Clarified the expected outcome");
                // Nothing pins yet — test_execution has no writer — so false is the honest value
                // rather than a placeholder.
                assertThat(row.pinned()).isFalse();
            });
        }

        @Test
        @DisplayName("the snapshot is the resolved case, not a delta or a field list")
        void theSnapshotHoldsTheWholeCase() throws Exception {
            // V1: a FULL snapshot including resolved steps. Reconstruction is O(1) and corruption
            // stays local; a delta chain means one malformed link silently changes what every later
            // version claims to have said.
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Snapshot me"), null);

            JsonNode snapshot = json.readTree(
                    versionsOf(caseId("full")).getFirst().snapshot());

            assertThat(snapshot.get("title").asText()).isEqualTo("Snapshot me");
            assertThat(snapshot.get("key").asText()).isEqualTo("TC-1");
            assertThat(snapshot.get("steps")).isNotEmpty();
            assertThat(snapshot.has("preconditions")).isTrue();
            assertThat(snapshot.has("postconditions")).isTrue();
        }

        @Test
        @DisplayName("the snapshot carries the version number it is a snapshot of")
        void theSnapshotAgreesWithItsOwnVersionNumber() throws Exception {
            // The response and the snapshot are ONE render. Two renders would differ by exactly this
            // field, which is the one a reader checks most closely.
            JsonNode after = body(patch(
                    LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), null));

            VersionRow row = versionsOf(caseId("full")).getFirst();
            JsonNode snapshot = json.readTree(row.snapshot());

            assertThat(snapshot.get("currentVersionNo").asInt()).isEqualTo(row.versionNo());
            assertThat(after.get("currentVersionNo").asInt()).isEqualTo(row.versionNo());
        }
    }

    // =====================================================================
    // Steps
    // =====================================================================

    @Nested
    @DisplayName("Steps — reconciled in place, because results reference their ids")
    class Steps {

        @Test
        @DisplayName("a step submitted with its id KEEPS that id")
        void aKeptStepKeepsItsId() throws Exception {
            // The property every recorded step result depends on. A delete-and-reinsert produces
            // correct-looking steps with new ids, orphaning every past result — invisible from the
            // steps themselves, which is why the ids are asserted rather than the actions.
            Map<String, Object> payload = edit("Renamed");
            payload.put("steps", List.of(
                    step(stepId("one"), "Open the refund screen"),
                    step(stepId("two"), "Enter the full amount"),
                    step(stepId("three"), "Submit")));

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(stepsOf(caseId("full"))).extracting(StepRow::id)
                    .containsExactly(stepId("one"), stepId("two"), stepId("three"));
        }

        @Test
        @DisplayName("reordering rewrites positions and changes no id")
        void reorderingPreservesIds() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("steps", List.of(
                    step(stepId("three"), "Submit"),
                    step(stepId("one"), "Open the refund screen"),
                    step(stepId("two"), "Enter the full amount")));

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(stepsOf(caseId("full")))
                    .extracting(StepRow::id, StepRow::position)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(stepId("three"), 0),
                            org.assertj.core.groups.Tuple.tuple(stepId("one"), 1),
                            org.assertj.core.groups.Tuple.tuple(stepId("two"), 2));
        }

        @Test
        @DisplayName("a step with no id is inserted, and gets one")
        void aNewStepIsInserted() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("steps", List.of(
                    step(stepId("one"), "Open the refund screen"),
                    step(stepId("two"), "Enter the full amount"),
                    step(stepId("three"), "Submit"),
                    step(null, "Check the merchant balance")));

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            List<StepRow> after = stepsOf(caseId("full"));
            assertThat(after).hasSize(4);
            assertThat(after.getLast().action()).isEqualTo("Check the merchant balance");
            assertThat(after.getLast().id()).isNotIn(stepId("one"), stepId("two"), stepId("three"));
        }

        @Test
        @DisplayName("a step left out of the payload is deleted")
        void anOmittedStepIsRemoved() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("steps", List.of(
                    step(stepId("one"), "Open the refund screen"),
                    step(stepId("three"), "Submit")));

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(stepsOf(caseId("full"))).extracting(StepRow::id)
                    .containsExactly(stepId("one"), stepId("three"));
        }

        @Test
        @DisplayName("a step's fields are updated in place")
        void aStepsContentIsUpdated() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            Map<String, Object> changed = step(stepId("one"), "Open the NEW refund screen");
            changed.put("expectedResult", "The refundable balance is shown");
            payload.put("steps", List.of(
                    changed, step(stepId("two"), "Enter the full amount"),
                    step(stepId("three"), "Submit")));

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(stepsOf(caseId("full")).getFirst())
                    .satisfies(row -> {
                        assertThat(row.id()).isEqualTo(stepId("one"));
                        assertThat(row.action()).isEqualTo("Open the NEW refund screen");
                        assertThat(row.expectedResult())
                                .isEqualTo("The refundable balance is shown");
                    });
        }

        @Test
        @DisplayName("a step id belonging to ANOTHER case is refused, not adopted")
        void aForeignStepIdIsRejected() throws Exception {
            // Honouring it would move that step's identity — and therefore its recorded results —
            // onto this case.
            Map<String, Object> payload = edit("Renamed");
            payload.put("steps", List.of(step(stepId("other-case"), "Steal this step")));

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("steps");
            assertThat(stepsOf(caseId("full"))).hasSize(3);
        }

        @Test
        @DisplayName("switching to Gherkin clears the structured steps")
        void gherkinClearsSteps() throws Exception {
            // Plan §6a: the two formats are alternatives. A case holding both leaves the execution
            // screen to pick one, and the next reader cannot tell which is authoritative.
            Map<String, Object> payload = edit("Now in Gherkin");
            payload.put("stepFormat", "GHERKIN");
            payload.put("gherkinSource", "Feature: Refunds\n\n  Scenario: Full\n    Given a payment");
            payload.put("steps", List.of());

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(stepsOf(caseId("full"))).isEmpty();
        }

        @Test
        @DisplayName("switching to Gherkin clears the steps even if the client still sends them")
        void gherkinIgnoresSubmittedSteps() throws Exception {
            Map<String, Object> payload = edit("Now in Gherkin");
            payload.put("stepFormat", "GHERKIN");
            payload.put("gherkinSource", "Feature: Refunds\n\n  Scenario: Full\n    Given a payment");
            payload.put("steps", List.of(step(stepId("one"), "Open the refund screen")));

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(stepsOf(caseId("full"))).isEmpty();
            assertThat(titleOf(caseId("full"))).isEqualTo("Now in Gherkin");
        }

        @Test
        @DisplayName("an unrecognized keyword is refused rather than sent to the check constraint")
        void aBadKeywordIsRejected() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            Map<String, Object> bad = step(stepId("one"), "Open the refund screen");
            bad.put("keyword", "PERHAPS");
            payload.put("steps", List.of(bad));

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("steps");
        }
    }

    // =====================================================================
    // Concurrency
    // =====================================================================

    @Nested
    @DisplayName("Concurrency — If-Match, and the stale editor @Version cannot see")
    class Concurrency {

        @Test
        @DisplayName("a matching If-Match is accepted and the lock version advances")
        void matchingIfMatchSucceeds() throws Exception {
            long stored = lockVersionOf(caseId("full"));

            MvcResult result = patch(
                    LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), String.valueOf(stored));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).get("lockVersion").asLong()).isGreaterThan(stored);
        }

        @Test
        @DisplayName("a stale If-Match is 409 and changes nothing")
        void staleIfMatchConflicts() throws Exception {
            // @Version alone cannot catch this: the entity is loaded fresh inside the transaction,
            // so its version always matches itself. This is the check that sees the stale editor.
            long stored = lockVersionOf(caseId("full"));

            MvcResult result = patch(
                    LEAD, ACME, ACME_BANKING, caseId("full"), edit("Overwritten"),
                    String.valueOf(stored - 1));

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(titleOf(caseId("full"))).isEqualTo("Full refund to card");
            assertThat(versionsOf(caseId("full"))).isEmpty();
        }

        @Test
        @DisplayName("the 409 body tells the caller to reload, and carries no field errors")
        void conflictBodyIsActionable() throws Exception {
            long stored = lockVersionOf(caseId("full"));

            MvcResult result = patch(
                    LEAD, ACME, ACME_BANKING, caseId("full"), edit("x"), String.valueOf(stored - 1));

            JsonNode problem = body(result);
            assertThat(problem.get("status").asInt()).isEqualTo(409);
            assertThat(problem.get("detail").asText()).contains("Reload");
            // A 409 is not about fields, and an empty errors object reads as "no fields were wrong".
            assertThat(problem.has("errors")).isFalse();
        }

        @Test
        @DisplayName("the second of two sequential saves with the SAME If-Match is refused")
        void anIfMatchIsGoodForOneSaveOnly() throws Exception {
            // The lost-update scenario as a user meets it: two editors both loaded version N.
            String both = String.valueOf(lockVersionOf(caseId("full")));

            MvcResult first = patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("First"), both);
            MvcResult second =
                    patch(ENGINEER, ACME, ACME_BANKING, caseId("full"), edit("Second"), both);

            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(second.getResponse().getStatus()).isEqualTo(409);
            assertThat(titleOf(caseId("full"))).isEqualTo("First");
        }

        @Test
        @DisplayName("no If-Match at all is last-write-wins, not a rejection")
        void anAbsentIfMatchSkipsTheCheck() throws Exception {
            // Correct for a caller with no prior read — an importer. The SPA always sends one.
            MvcResult result =
                    patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Imported"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a quoted If-Match is accepted, because that is what HTTP says an ETag looks like")
        void aQuotedIfMatchIsAccepted() throws Exception {
            long stored = lockVersionOf(caseId("full"));

            MvcResult result = patch(
                    LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), "\"" + stored + "\"");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an unparseable If-Match is 400, not a 409 the client can never satisfy")
        void aMalformedIfMatchIsABadRequest() throws Exception {
            // 409 would send a client with a broken header into a reload-and-retry loop that cannot
            // succeed, and the reload would look like it had worked.
            MvcResult result =
                    patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("x"), "not-a-number");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("If-Match");
        }

        @Test
        @DisplayName("a weak ETag is refused — weak comparison is the wrong semantics for a lost update")
        void aWeakEtagIsRefused() throws Exception {
            MvcResult result =
                    patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("x"), "W/\"3\"");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — every rule the SPA enforces, enforced again because curl runs no Zod")
    class Validation {

        @Test
        @DisplayName("a blank title is refused, keyed on title")
        void blankTitleIsRefused() throws Exception {
            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("   "), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result).get("title")).contains("Enter a title.");
        }

        @Test
        @DisplayName("an over-long title is refused")
        void longTitleIsRefused() throws Exception {
            MvcResult result =
                    patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("t".repeat(301)), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("title");
        }

        @Test
        @DisplayName("a suite from ANOTHER project is refused, so a case cannot vanish from both trees")
        void aForeignSuiteIsRefused() throws Exception {
            // Unchecked, this moves the case into another project's folder and no read then filters
            // it out of either tree — it simply disappears.
            Map<String, Object> payload = edit("Renamed");
            payload.put("suiteId", MOBILE_SUITE.toString());

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("suiteId");
        }

        @Test
        @DisplayName("another CLIENT's suite is refused too")
        void anotherClientsSuiteIsRefused() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("suiteId", UMBRA_SUITE.toString());

            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a case CAN be moved between suites of its own project")
        void aCaseMovesWithinItsProject() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("suiteId", PAYMENTS.toString());

            JsonNode after = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null));

            assertThat(after.get("suiteId").asText()).isEqualTo(PAYMENTS.toString());
            assertThat(after.get("suiteName").asText()).isEqualTo("Payments");
        }

        @Test
        @DisplayName("GHERKIN with no scenario is refused, keyed on gherkinSource")
        void gherkinNeedsAScenario() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("stepFormat", "GHERKIN");
            payload.put("gherkinSource", "  ");

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("gherkinSource");
        }

        @Test
        @DisplayName("STRUCTURED with no steps is refused, keyed on steps")
        void structuredNeedsAStep() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("steps", List.of());

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("steps");
        }

        @Test
        @DisplayName("NOT_AUTOMATABLE with no reason is refused — the DB check would 500 instead")
        void notAutomatableNeedsAReason() throws Exception {
            // ck_not_automatable_reason enforces this in the database. Reaching it means a 500 on a
            // form the user could have been told about, and it is what keeps automation coverage
            // honestly denominated.
            Map<String, Object> payload = edit("Renamed");
            payload.put("automationStatus", "NOT_AUTOMATABLE");
            payload.put("notAutomatableReason", null);

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("notAutomatableReason");
        }

        @Test
        @DisplayName("AUTOMATED with no key is refused — results would have nothing to match on")
        void automatedNeedsAKey() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", null);

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("automationKey");
        }

        @Test
        @DisplayName("an automation key another case holds is refused, not left to the unique index")
        void aDuplicateAutomationKeyIsRefused() throws Exception {
            // Company-wide unique (§6c), so the collision does not need to be in the same project.
            // Reaching the constraint means a 500 on a validated form.
            Map<String, Object> payload = edit("Renamed");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", "com.example.mobile.LoginIT#taken");

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("automationKey");
        }

        @Test
        @DisplayName("a case re-saving its OWN automation key is not rejected by itself")
        void aCaseKeepsItsOwnKey() throws Exception {
            // Without excluding the case under edit, every second save of an automated case fails.
            Map<String, Object> payload = edit("Renamed");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", "com.example.payments.RefundIT#fullRefund");

            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a key held by a SOFT-DELETED case is still refused, agreeing with the index")
        void aDeletedCaseStillHoldsItsKey() throws Exception {
            // The unique index is partial on automation_key IS NOT NULL and does NOT exclude deleted
            // rows. A check that excluded them would pass and the insert would then fail — a
            // validated form that 500s on submit.
            Map<String, Object> payload = edit("Renamed");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", "com.example.payments.WithdrawnIT#gone");

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("automationKey");
        }

        @Test
        @DisplayName("a negative duration is refused")
        void negativeDurationIsRefused() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("estimatedDurationSec", -1);

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("estimatedDurationSec");
        }

        @Test
        @DisplayName("every message is reported at once, so a form is not fixed one field per round trip")
        void allFailuresComeBackTogether() throws Exception {
            Map<String, Object> payload = edit("  ");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", null);
            payload.put("estimatedDurationSec", -5);

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(errorsOf(result))
                    .containsKeys("title", "automationKey", "estimatedDurationSec");
        }

        @Test
        @DisplayName("a cleared optional field becomes null, not an empty string")
        void blankOptionalsBecomeNull() throws Exception {
            // Otherwise "cleared" and "never filled in" are two values that render identically, and
            // the next query looking for unfilled objectives finds half of them.
            Map<String, Object> payload = edit("Renamed");
            payload.put("objective", "   ");

            JsonNode after = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null));

            assertThat(after.get("objective").isNull()).isTrue();
        }

        @Test
        @DisplayName("nothing is written when validation fails")
        void aRejectedSaveWritesNothing() throws Exception {
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("  "), null);

            assertThat(titleOf(caseId("full"))).isEqualTo("Full refund to card");
            assertThat(versionsOf(caseId("full"))).isEmpty();
            assertThat(auditRows()).isEmpty();
        }
    }

    // =====================================================================
    // The six refused fields
    // =====================================================================

    @Nested
    @DisplayName("The unimplemented fields — refused loudly, never accepted and dropped")
    class RefusedFields {

        @Test
        @DisplayName("each of the five answers 400 keyed on its own name")
        void eachUnsupportedFieldIsRefused() throws Exception {
            // Accepting and ignoring them is a save that reports success and discards what the user
            // typed — the user then discovers it only by reloading, and retypes it.
            //
            // classificationValueIds LEFT this list: it has a write path now, and its own nested
            // class below. That is what the removal of a boundary looks like from a test — a field
            // that moved from "refused" to "specified", not a line quietly deleted.
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("ownerId", ENGINEER.toString());
            values.put("tags", List.of("regression"));
            values.put("automationTags", List.of("@smoke"));
            values.put("preconditions", List.of(Map.of("conditionId", UUID.randomUUID().toString())));
            values.put("postconditions", List.of(Map.of("inlineBody", List.of("Clean up"))));

            for (Map.Entry<String, Object> field : values.entrySet()) {
                Map<String, Object> payload = edit("Renamed");
                payload.put(field.getKey(), field.getValue());

                MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

                assertThat(result.getResponse().getStatus())
                        .as("%s must be refused rather than silently dropped", field.getKey())
                        .isEqualTo(400);
                assertThat(errorsOf(result))
                        .as("the message must land on %s", field.getKey())
                        .containsKey(field.getKey());
            }
        }

        @Test
        @DisplayName("omitting them is fine — that is what every save does")
        void omittingThemIsTheNormalCase() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    // =====================================================================
    // Classification
    // =====================================================================

    @Nested
    @DisplayName("Classification — the values a case carries, and the three ways to get it wrong")
    class Classification {

        @Test
        @DisplayName("a live value of this project is stored, and comes back on the case")
        void aValueIsAssigned() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_CARDS.toString()));

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(storedClassification(caseId("full"))).containsExactly(FEATURE_CARDS);
            assertThat(idsOf(body(result))).containsExactly(FEATURE_CARDS);
            // The names must agree with the ids on the SAME response — the contract promises they
            // cannot disagree, and one filter implementation is what makes that true.
            assertThat(namesOf(body(result), "features")).containsExactly("Cards");
        }

        @Test
        @DisplayName("a PARENT value is assignable — nothing in the product requires a leaf")
        void aParentValueIsAssignable() throws Exception {
            // The editor claimed for two milestones that a flat select "would let someone set a
            // parent where a leaf is required". No such rule exists in the plan, the ERD, the roles
            // spec or V1 — §6q's descendant rule is about FILTERING. Asserted rather than assumed,
            // because the belief was load-bearing enough to keep the field read-only.
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_CARDS.toString()));

            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(storedClassification(caseId("full"))).containsExactly(FEATURE_CARDS);
        }

        @Test
        @DisplayName("a SIBLING PROJECT's value is refused — it passes every database check")
        void aSiblingProjectsValueIsRefused() throws Exception {
            // The defect this validation exists for. The FK accepts it, the company column matches,
            // RLS admits it and V2's trigger is satisfied, so without the vocabulary check it would
            // be STORED — and every later read of this case would resolve a classification through
            // a project the reader holds no grant on.
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_MOBILE_ONLY.toString()));

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("classificationValueIds");
            assertThat(storedClassification(caseId("full"))).isEmpty();
        }

        @Test
        @DisplayName("a RETIRED value cannot be newly assigned")
        void aRetiredValueIsRefused() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_RETIRED.toString()));

            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a value naming nothing is a field error, not the 500 the foreign key would give")
        void anUnknownValueIsAFieldError() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(UUID.randomUUID().toString()));

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("classificationValueIds");
        }

        @Test
        @DisplayName("the refusal does not say WHICH of the three reasons it was")
        void theRefusalDoesNotDistinguishItsCause() throws Exception {
            // Retired, another project's, and nonexistent must read identically: telling a caller
            // that an id they may not use nevertheless exists is the inference channel §6t closes.
            Map<String, Object> siblings = edit("A");
            siblings.put("classificationValueIds", List.of(FEATURE_MOBILE_ONLY.toString()));
            Map<String, Object> nowhere = edit("A");
            nowhere.put("classificationValueIds", List.of(UUID.randomUUID().toString()));

            assertThat(errorsOf(patch(LEAD, ACME, ACME_BANKING, caseId("full"), siblings, null)))
                    .isEqualTo(errorsOf(patch(LEAD, ACME, ACME_BANKING, caseId("full"), nowhere, null)));
        }

        @Test
        @DisplayName("two values on a SINGLE dimension are a 400, not the trigger's constraint violation")
        void twoValuesOnOneSingleAxisAreRefused() throws Exception {
            UUID functional = companyValueId("test_type", "Functional");
            UUID performance = companyValueId("test_type", "Performance");

            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds",
                    List.of(functional.toString(), performance.toString()));

            MvcResult result = patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            // 400 and not 500: V2's trigger raises 23505 for this, and a constraint violation
            // reaching the client is a 500 on a form that has a field to put a sentence on.
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("classificationValueIds");
            assertThat(storedClassification(caseId("full"))).isEmpty();
        }

        @Test
        @DisplayName("one value on each of two different SINGLE dimensions is fine")
        void oneValuePerSingleAxisIsAccepted() throws Exception {
            // The check must count per dimension, not across them. Counting globally would refuse
            // a Test Type AND a Test Level together, which is the normal case.
            UUID functional = companyValueId("test_type", "Functional");
            UUID api = companyValueId("test_level", "API");

            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(functional.toString(), api.toString()));

            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(storedClassification(caseId("full")))
                    .containsExactlyInAnyOrder(functional, api);
        }

        @Test
        @DisplayName("several values on a MULTI dimension are fine — Feature is the one that matters")
        void severalValuesOnAMultiAxisAreAccepted() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds",
                    List.of(FEATURE_CARDS.toString(), FEATURE_CARDS_3DS.toString()));

            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(storedClassification(caseId("full")))
                    .containsExactlyInAnyOrder(FEATURE_CARDS, FEATURE_CARDS_3DS);
        }

        @Test
        @DisplayName("a value left out of the payload is removed")
        void anOmittedValueIsRemoved() throws Exception {
            assign(FEATURE_CARDS, FEATURE_CARDS_3DS);

            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_CARDS.toString()));
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(storedClassification(caseId("full"))).containsExactly(FEATURE_CARDS);
        }

        @Test
        @DisplayName("an empty array clears the classification")
        void anEmptyArrayClearsIt() throws Exception {
            assign(FEATURE_CARDS);

            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of());
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            assertThat(storedClassification(caseId("full"))).isEmpty();
        }

        @Test
        @DisplayName("OMITTING the field leaves the classification alone — absent is not empty")
        void omittingTheFieldLeavesItAlone() throws Exception {
            assign(FEATURE_CARDS);

            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), null);

            assertThat(storedClassification(caseId("full"))).containsExactly(FEATURE_CARDS);
        }

        @Test
        @DisplayName("replacing the value on a SINGLE dimension works — delete must precede insert")
        void replacingASingleAxisValueWorks() throws Exception {
            UUID functional = companyValueId("test_type", "Functional");
            UUID performance = companyValueId("test_type", "Performance");
            assign(functional);

            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(performance.toString()));

            // With the insert flushed first, V2's trigger counts the OUTGOING value as an existing
            // one and refuses the incoming one as a second value on the axis — a 500 on the most
            // ordinary edit there is. Hibernate orders inserts before deletes within a flush, so
            // this passes only because the reconcile forces the other order explicitly.
            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(storedClassification(caseId("full"))).containsExactly(performance);
        }

        @Test
        @DisplayName("a RETIRED value already on the case survives a save that cannot mention it")
        void aRetiredAssignmentSurvives() throws Exception {
            // The reconcile's bound, and the defect it prevents. GET never serves this value, so
            // the client cannot resubmit it — and a reconcile that read "absent" as "delete" would
            // silently discard a classification nobody chose to drop, on the first save of any case
            // carrying one.
            assign(FEATURE_RETIRED, FEATURE_CARDS);

            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_CARDS.toString()));
            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(storedClassification(caseId("full")))
                    .containsExactlyInAnyOrder(FEATURE_RETIRED, FEATURE_CARDS);
        }

        @Test
        @DisplayName("a retired value is not SERVED, though it is stored — ids and names agree")
        void aRetiredAssignmentIsNotServed() throws Exception {
            assign(FEATURE_RETIRED, FEATURE_CARDS);

            JsonNode served = body(patch(LEAD, ACME, ACME_BANKING, caseId("full"),
                    edit("Renamed"), null));

            assertThat(idsOf(served)).containsExactly(FEATURE_CARDS);
            assertThat(namesOf(served, "features")).containsExactly("Cards");
        }

        @Test
        @DisplayName("the same values in a different order write NO second version")
        void reorderingIsNotAChange() throws Exception {
            Map<String, Object> first = edit("Stable");
            first.put("classificationValueIds",
                    List.of(FEATURE_CARDS.toString(), FEATURE_CARDS_3DS.toString()));
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), first, null);
            int after = versionsOf(caseId("full")).size();

            Map<String, Object> reversed = edit("Stable");
            reversed.put("classificationValueIds",
                    List.of(FEATURE_CARDS_3DS.toString(), FEATURE_CARDS.toString()));
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), reversed, null);

            // CaseVersions#hash digests the edit's JSON, so an unsorted list would make two equal
            // classifications hash differently and fill the version list with empty diffs — the
            // noise plan §6j added the hash to keep out.
            assertThat(versionsOf(caseId("full")).size()).isEqualTo(after);
        }

        @Test
        @DisplayName("changing only the classification DOES write a version")
        void aClassificationChangeIsAChange() throws Exception {
            Map<String, Object> first = edit("Stable");
            first.put("classificationValueIds", List.of(FEATURE_CARDS.toString()));
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), first, null);
            int after = versionsOf(caseId("full")).size();

            Map<String, Object> changed = edit("Stable");
            changed.put("classificationValueIds", List.of(FEATURE_CARDS_3DS.toString()));
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), changed, null);

            // The other half of the rule above, and the reason classification had to join the hash
            // rather than merely the snapshot: before this slice, a save that reclassified a case
            // recorded no version at all.
            assertThat(versionsOf(caseId("full")).size()).isEqualTo(after + 1);
        }

        @Test
        @DisplayName("a Viewer cannot classify — it is a write, gated by case:edit")
        void aViewerCannotClassify() throws Exception {
            Map<String, Object> payload = edit("Renamed");
            payload.put("classificationValueIds", List.of(FEATURE_CARDS.toString()));

            assertThat(patch(VIEWER, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(storedClassification(caseId("full"))).isEmpty();
        }

        /** Puts the case in a starting state, through the API, so no test seeds rows by hand. */
        private void assign(UUID... valueIds) throws Exception {
            Map<String, Object> payload = edit("Seeded");
            payload.put("classificationValueIds",
                    java.util.Arrays.stream(valueIds).map(UUID::toString).toList());

            if (java.util.Arrays.asList(valueIds).contains(FEATURE_RETIRED)) {
                // Except this one, which the API deliberately refuses to create — a retired value
                // can only be assigned by having been assigned before it was retired. Written
                // directly, for the same reason §67 wrote its null-author row in SQL: the state is
                // real and unreachable through any endpoint, which is what makes it the arm most
                // likely to be wrong.
                assignDirectly(valueIds);
                return;
            }
            assertThat(patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        private void assignDirectly(UUID... valueIds) throws SQLException {
            try (Connection connection = appConnection(ACME)) {
                for (UUID valueId : valueIds) {
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO case_classification
                                (company_id, case_id, value_id, dimension_id)
                            SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                              FROM classification_value v
                             WHERE v.id = CAST(? AS uuid)
                            ON CONFLICT DO NOTHING
                            """)) {
                        statement.setString(1, ACME.toString());
                        statement.setString(2, caseId("full").toString());
                        statement.setString(3, valueId.toString());
                        statement.executeUpdate();
                    }
                }
            }
        }

        private List<UUID> idsOf(JsonNode served) {
            List<UUID> ids = new ArrayList<>();
            served.get("classificationValueIds").forEach(node -> ids.add(UUID.fromString(node.asText())));
            return ids;
        }

        private List<String> namesOf(JsonNode served, String field) {
            List<String> names = new ArrayList<>();
            served.get(field).forEach(node -> names.add(node.asText()));
            return names;
        }
    }

    // =====================================================================
    // Audit
    // =====================================================================

    @Nested
    @DisplayName("Audit — one row per save, in the client's own trail")
    class Audit {

        @Test
        @DisplayName("a successful edit writes one audit_event naming actor, project and target")
        void anEditIsAudited() throws Exception {
            patch(ENGINEER, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), null);

            assertThat(auditRows()).singleElement().satisfies(row -> {
                assertThat(row.action()).isEqualTo("case.updated");
                assertThat(row.actorUserId()).isEqualTo(ENGINEER);
                assertThat(row.projectId()).isEqualTo(ACME_BANKING);
                assertThat(row.targetType()).isEqualTo("test_case");
                assertThat(row.targetIds()).containsExactly(caseId("full"));
                assertThat(row.companyId()).isEqualTo(ACME);
            });
        }

        @Test
        @DisplayName("the audit row records the resulting version and whether it was a new one")
        void theAuditRowRecordsTheVersion() throws Exception {
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("Renamed"), null);

            JsonNode changes = json.readTree(auditRows().getFirst().changes());
            assertThat(changes.get("versionNo").asInt()).isEqualTo(8);
            assertThat(changes.get("newVersion").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("a save that changed nothing is still audited, and says so")
        void aNoOpSaveIsAudited() throws Exception {
            // "Ana saved this and nothing changed" is a fact about who was in the case, which is
            // what an audit trail is for even when history has nothing to add.
            Map<String, Object> payload = edit("Stable");
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), payload, null);

            List<AuditRow> rows = auditRows();
            assertThat(rows).hasSize(2);
            assertThat(json.readTree(rows.getFirst().changes()).get("newVersion").asBoolean())
                    .isFalse();
        }

        @Test
        @DisplayName("a refused save writes no audit row")
        void aForbiddenSaveIsNotAudited() throws Exception {
            patch(VIEWER, ACME, ACME_BANKING, caseId("full"), edit("nope"), null);
            patch(LEAD, ACME, ACME_BANKING, caseId("full"), edit("  "), null);

            assertThat(auditRows()).isEmpty();
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private static String path(UUID projectId, UUID caseId) {
        return "/api/v1/projects/" + projectId + "/cases/" + caseId;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult patch(
            UUID userId, UUID companyId, UUID projectId, UUID caseId,
            Map<String, Object> payload, String ifMatch) throws Exception {

        var request = MockMvcRequestBuilders.patch(path(projectId, caseId))
                .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId, UUID caseId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path(projectId, caseId))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("the endpoint answered with no body").isNotEmpty();
        return json.readTree(content);
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        JsonNode errors = body(result).get("errors");
        assertThat(errors).as("a 400 must carry an errors map the form can land").isNotNull();

        Map<String, List<String>> byField = new LinkedHashMap<>();
        errors.propertyStream().forEach(entry -> {
            List<String> messages = new ArrayList<>();
            entry.getValue().forEach(node -> messages.add(node.asText()));
            byField.put(entry.getKey(), messages);
        });
        return byField;
    }

    /**
     * The payload the SPA's editor submits, with one title varied.
     *
     * <p>A mutable map rather than a record so each test overrides exactly the field it is about, and
     * a fresh one per call so a mutation in one test cannot reach another.
     */
    private static Map<String, Object> edit(String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", REFUNDS.toString());
        payload.put("objective", "Prove a merchant can recover funds.");
        payload.put("description", "Refunds the whole captured amount.");
        payload.put("expectedOutcome", "The payment reaches REFUNDED.");
        payload.put("priority", "CRITICAL");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("automationKey", null);
        payload.put("notAutomatableReason", null);
        payload.put("estimatedDurationSec", 240);
        payload.put("stepFormat", "STRUCTURED");
        payload.put("gherkinSource", null);
        payload.put("steps", List.of(
                step(stepId("one"), "Open the refund screen"),
                step(stepId("two"), "Enter the full amount"),
                step(stepId("three"), "Submit")));
        payload.put("changeComment", null);
        return payload;
    }

    private static Map<String, Object> step(UUID id, String action) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id == null ? null : id.toString());
        step.put("keyword", null);
        step.put("action", action);
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        return step;
    }

    // =====================================================================
    // helpers — reading the result back through the app role
    // =====================================================================

    private record VersionRow(
            int versionNo, String snapshot, String contentHash, String changeComment,
            boolean pinned, UUID createdBy) {
    }

    /** Newest first. */
    private List<VersionRow> versionsOf(UUID caseId) throws SQLException {
        List<VersionRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT version_no, snapshot::text, content_hash, change_comment, pinned,
                            created_by
                       FROM test_case_version
                      WHERE case_id = CAST(? AS uuid)
                      ORDER BY version_no DESC
                     """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String author = rs.getString(6);
                    rows.add(new VersionRow(
                            rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getBoolean(5), author == null ? null : UUID.fromString(author)));
                }
            }
        }
        return rows;
    }

    private record StepRow(UUID id, int position, String action, String expectedResult) {
    }

    private List<StepRow> stepsOf(UUID caseId) throws SQLException {
        List<StepRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT id, position, action, expected_result
                       FROM test_step
                      WHERE case_id = CAST(? AS uuid)
                      ORDER BY position ASC
                     """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StepRow(
                            UUID.fromString(rs.getString(1)), rs.getInt(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        }
        return rows;
    }

    private record AuditRow(
            UUID companyId, UUID projectId, UUID actorUserId, String action, String targetType,
            List<UUID> targetIds, String changes) {
    }

    private List<AuditRow> auditRows() throws SQLException {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT company_id, project_id, actor_user_id, action, target_type,
                            target_ids, changes::text
                       FROM audit_event
                      ORDER BY occurred_at DESC
                     """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                List<UUID> targets = new ArrayList<>();
                for (Object id : (Object[]) rs.getArray(6).getArray()) {
                    targets.add((UUID) id);
                }
                rows.add(new AuditRow(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2) == null ? null : UUID.fromString(rs.getString(2)),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getString(5), targets, rs.getString(7)));
            }
        }
        return rows;
    }

    private String titleOf(UUID caseId) throws SQLException {
        UUID companyId = caseId.equals(caseId("umbra")) ? UMBRA : ACME;
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT title FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("no case %s", caseId).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long lockVersionOf(UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT lock_version FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    // =====================================================================
    // helpers — fixture, Spring Data side
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
        UUID id = UUID.nameUUIDFromBytes(("case-update:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private static UUID caseId(String marker) {
        return UUID.nameUUIDFromBytes(("case-update-case:" + marker).getBytes());
    }

    private static UUID stepId(String marker) {
        return UUID.nameUUIDFromBytes(("case-update-step:" + marker).getBytes());
    }

    // =====================================================================
    // helpers — fixture, raw SQL through the ORDINARY APP ROLE
    //
    // Same reasoning as every sibling test: there is no authoring endpoint for suites or steps, and
    // the app role is the policed path production writes through — a fixture that could not be
    // written under RLS is one that lies about what the endpoint will see.
    // =====================================================================

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "p");
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2, "Refunds", "p.r");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-3", 3, "Login", "l");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "p");
        }
    }

    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            testCase(connection, caseId("full"), ACME, ACME_BANKING, REFUNDS, "TC-1", 1,
                    "Full refund to card", "AUTOMATED",
                    "com.example.payments.RefundIT#fullRefund", LEAD, 7, false);

            // Holds a key the case under edit must not be allowed to claim — company-wide unique.
            testCase(connection, caseId("mobile"), ACME, ACME_MOBILE, MOBILE_SUITE, "TC-20", 20,
                    "Mobile login", "AUTOMATED",
                    "com.example.mobile.LoginIT#taken", null, 1, false);

            // Soft-deleted AND holding an automation key: the unique index does not exclude deleted
            // rows, so the key stays reserved.
            testCase(connection, caseId("deleted"), ACME, ACME_BANKING, REFUNDS, "TC-5", 5,
                    "Withdrawn case", "AUTOMATED",
                    "com.example.payments.WithdrawnIT#gone", null, 1, true);

            // Owns a step whose id must not be adoptable by the case under edit.
            testCase(connection, caseId("other"), ACME, ACME_BANKING, PAYMENTS, "TC-6", 6,
                    "Another case", "MANUAL", null, null, 1, false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            testCase(connection, caseId("umbra"), UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-1", 1,
                    "RIVAL CO CONFIDENTIAL refund", "MANUAL", null, null, 1, false);
        }
    }

    private void seedSteps() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // Written in an order that disagrees with their positions, so an unordered read cannot
            // come back right by accident (§27).
            step(connection, stepId("three"), ACME, caseId("full"), 2, "Submit");
            step(connection, stepId("one"), ACME, caseId("full"), 0, "Open the refund screen");
            step(connection, stepId("two"), ACME, caseId("full"), 1, "Enter the full amount");

            step(connection, stepId("other-case"), ACME, caseId("other"), 0, "Someone else's step");
        }
    }

    private void seedCompanyDefaults(Connection connection, UUID companyId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    /**
     * One value on a project's Feature dimension.
     *
     * <p>The dimension is resolved by {@code (company, project, key)} rather than by id because
     * {@code tcms_seed_project_defaults} generated it — pinning the id here would mean asserting
     * against a value the product did not create.
     *
     * @param retired sets {@code deleted_at}, which is a SOFT delete: the value stops being offered
     *                and every assignment to it survives, which is the state the reconcile's bound
     *                exists for
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private void featureValue(
            Connection connection, UUID id, UUID projectId, UUID parentId,
            String path, String name, boolean retired) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO classification_value
                    (id, company_id, dimension_id, parent_id, path, name, color, position, deleted_at)
                SELECT CAST(? AS uuid), CAST(? AS uuid), d.id, CAST(? AS uuid), CAST(? AS ltree),
                       ?, NULL, 0, CAST(? AS timestamptz)
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
            // A fixed instant rather than now(): nothing asserts the value, and a deterministic
            // fixture is one less thing that can differ between runs.
            statement.setString(6, retired ? "2026-01-01T00:00:00Z" : null);
            statement.setString(7, ACME.toString());
            statement.setString(8, projectId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * A company-level value's generated id, by dimension key and name.
     *
     * <p>Looked up rather than pinned, so these tests run against the vocabulary
     * {@code tcms_seed_company_defaults} actually creates. A fixture that inserted its own
     * "Functional" would pass while the seeded one was broken.
     */
    private UUID companyValueId(String dimensionKey, String name) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement("""
                        SELECT v.id
                          FROM classification_value v
                          JOIN classification_dimension d ON d.id = v.dimension_id
                         WHERE d.company_id = CAST(? AS uuid)
                           AND d.project_id IS NULL
                           AND d.key = ?
                           AND v.name = ?
                        """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, dimensionKey);
            statement.setString(3, name);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the seeded vocabulary has no %s called %s", dimensionKey, name)
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    /** The values a case carries in the database, whatever the API chooses to serve. */
    private List<UUID> storedClassification(UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT value_id FROM case_classification WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            List<UUID> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(UUID.fromString(rows.getString(1)));
                }
            }
            return found;
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

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, path, name,
                     position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED', NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, parentId == null ? null : parentId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, path);
            statement.setString(8, name);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void testCase(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID suiteId,
            String key, long keySeq, String title, String automation, String automationKey,
            UUID ownerId, int currentVersionNo, boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description,
                     objective, expected_outcome, step_format, gherkin_source, priority, severity,
                     estimated_duration_sec, lifecycle_status, automation_status, automation_key,
                     not_automatable_reason, owner_id, current_version_no, lock_version,
                     updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, NULL, NULL, NULL, 'STRUCTURED', NULL, 'CRITICAL', CAST(? AS text),
                        NULL, 'APPROVED', ?, CAST(? AS text), NULL, CAST(? AS uuid), ?, 3,
                        CAST(? AS timestamptz), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, "HIGH");
            statement.setString(9, automation);
            statement.setString(10, automationKey);
            statement.setString(11, ownerId == null ? null : ownerId.toString());
            statement.setInt(12, currentVersionNo);
            statement.setString(13, "2026-01-01T00:00:00Z");
            statement.setString(14, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void step(
            Connection connection, UUID id, UUID companyId, UUID caseId, int position,
            String action) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step
                    (id, company_id, case_id, position, keyword, action, test_data,
                     expected_result, shared_step_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, NULL, ?, NULL,
                        NULL, NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, position);
            statement.setString(5, action);
            statement.executeUpdate();
        }
    }
}
