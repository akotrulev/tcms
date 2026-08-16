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
 * {@code suite_version} advancing — §238, the C1 slice, over HTTP end to end.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Until §238 {@code SuiteVersions} wrote v1 at create and never again, so
 * {@code suite.current_version_no} was the constant 1 on every suite in every deployment — and
 * {@code SuiteStatusService} pinned that constant to every review it recorded, under a comment
 * saying the pin captured "what was in it on the day". The regression that would restore that is
 * not a compile error and not a 500; it is a number that stops moving. So the assertions here are
 * about <strong>which acts move it</strong>, and — at least as important — which acts must not.
 *
 * <ol>
 *   <li><strong>A losing suite left behind.</strong> The failure the two-phase read exists for. A
 *       suite that <em>gains</em> a case can be found after the write; one that loses a case cannot,
 *       because nothing relates the two rows any more. An implementation that reconciles only the
 *       after-set passes every gaining assertion in this class and leaves every emptied folder
 *       pinned to a composition it no longer has.</li>
 *   <li><strong>A version per save.</strong> {@code SuiteMembershipWriter} is set-semantic and
 *       idempotent, so an unconditional bump mints a row every time anybody presses Save on the
 *       suite dialog — plan §6j's version-list-as-a-log-of-who-looked-at-it.</li>
 *   <li><strong>The definition read instead of the membership.</strong> The one that only a hash
 *       over the resolved union catches: filing a case into a suite that already held it through a
 *       tag rule changes the filed arm and changes nothing about what the suite holds.</li>
 *   <li><strong>The tagged arm forgotten.</strong> It is the arm with no write to the suite at all,
 *       so it is the one an implementation that looks only at suite-owned tables misses entirely.</li>
 *   <li><strong>An approval minting its own pin.</strong> {@code Suite#applyStatus} declines the
 *       bump; if it did not, the review would name a version the transition created rather than the
 *       composition that was approved.</li>
 * </ol>
 *
 * <p>Ids use the {@code 8b…} prefix, checked against every other test class rather than assumed —
 * two classes sharing a company and a user collide on {@code uq_role_assignment_user} in whichever
 * runs second, a failure that names neither of them.
 *
 * <h2>Every tag rule here uses a tag unique to its own test, and that is not tidiness</h2>
 *
 * <p>These tests share one project and nothing rolls back between them, so cases accumulate. A tag
 * is <strong>project-wide</strong> — that is the whole nature of the third arm — so a suite whose
 * rule says {@code payments} claims every case in the project carrying that tag, including ones
 * some other test created. Two tests using the same tag makes the second one's "a rule matching
 * nothing" a rule matching something, and it fails with a version it cannot account for. Found the
 * hard way while writing this class, which is the same fact the production code is about, arriving
 * from the fixture's side.
 */
@AutoConfigureMockMvc
@DisplayName("suite_version advances when the resolved membership does — and only then")
class SuiteCompositionVersionTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("8b000000-0000-4000-8000-00000000000a");

    private static final UUID BANKING = UUID.fromString("8b001000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("8b003000-0000-4000-8000-000000000001");

    /**
     * A second lead, and the fixture cannot do without one: author-cannot-approve-own-work defaults
     * on (§6g), so the person who created the suite gets a 409 on APPROVED and the pin tests would
     * be asserting against a transition that never happened.
     */
    private static final UUID APPROVER = UUID.fromString("8b003000-0000-4000-8000-000000000002");

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
            company(ACME, "scomp-acme", "Acme", now);
            activeUser(LEAD, "lead@scomp-acme.test", "L. Lead", now);
            activeUser(APPROVER, "approver@scomp-acme.test", "A. Approver", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "SCOMP", "Online Banking", now);
            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, APPROVER, "A. Approver", now);
            grantOnProject("scomp-lead", ACME, Role.TEST_LEAD_ID, LEAD, BANKING, now);
            grantOnProject("scomp-approver", ACME, Role.TEST_LEAD_ID, APPROVER, BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedProjectDefaults();
        enableSuiteReview();
    }

    /**
     * §6g's opt-in is OFF by default, per artifact type, and with it off no review row is written at
     * all — so the pin tests would read an empty table and pass or fail for the wrong reason. This
     * is the setting that makes {@code review.subject_version_no} observable, which is the column
     * the whole slice is about.
     */
    private void enableSuiteReview() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO project_settings (project_id, company_id, review_enabled_suite)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), true)
                     ON CONFLICT (project_id) DO UPDATE SET review_enabled_suite = true
                     """)) {
            statement.setString(1, BANKING.toString());
            statement.setString(2, ACME.toString());
            statement.execute();
        }
    }

    // =====================================================================
    // Fixture
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
        UUID id = UUID.nameUUIDFromBytes(("suite-composition-version:" + marker).getBytes());
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

    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, ACME.toString());
            statement.setString(2, BANKING.toString());
            statement.execute();
        }
    }

    // =====================================================================
    // Creation — the v1 that used to be a false statement
    // =====================================================================

    @Nested
    @DisplayName("v1 records what the create actually put in the folder")
    class Creation {

        @Test
        @DisplayName("an empty create is v1 of nothing")
        void anEmptyCreate() throws Exception {
            UUID suite = createSuite("Payments");

            assertThat(versionNumbers(suite)).containsExactly(1);
            assertThat(snapshotCaseIds(suite, 1)).isEmpty();
        }

        /**
         * The second defect this slice found, and it was not in the audit. {@code writeInitial}
         * hard-coded {@code Composition(List.of())} under the comment "a suite is created before
         * anything can be put in it" — true when it was written and false since §98, which let a
         * create carry {@code caseIds}. So v1 asserted emptiness about folders that were born with
         * members.
         */
        @Test
        @DisplayName("a create carrying cases records them, not an empty composition")
        void aCreateWithMembers() throws Exception {
            UUID first = createCase("Capture a payment", null);
            UUID second = createCase("Void an authorization", null);

            UUID suite = createSuite("Payments", first, second);

            assertThat(versionNumbers(suite))
                    .as("still exactly one version — the members arrive WITH the folder, so there "
                            + "is no second composition to record")
                    .containsExactly(1);
            assertThat(snapshotCaseIds(suite, 1)).containsExactlyInAnyOrder(first, second);
        }
    }

    // =====================================================================
    // The acts that advance it
    // =====================================================================

    @Nested
    @DisplayName("The acts that change what a folder holds")
    class Advancing {

        @Test
        @DisplayName("filing a case into a suite advances it")
        void filingACase() throws Exception {
            UUID suite = createSuite("Payments");
            UUID testCase = createCase("Capture a payment", suite);

            assertThat(versionNumbers(suite)).containsExactly(1, 2);
            assertThat(currentVersionNo(suite))
                    .as("the counter and the row move together, or a review pins a version that "
                            + "does not exist")
                    .isEqualTo(2);
            assertThat(snapshotCaseIds(suite, 2)).containsExactly(testCase);
        }

        @Test
        @DisplayName("adding a case through the suite's own edit advances it")
        void addingACaseToTheSuite() throws Exception {
            UUID suite = createSuite("Payments");
            UUID testCase = createCase("Capture a payment", null);

            claim(suite, testCase);

            assertThat(versionNumbers(suite)).containsExactly(1, 2);
            assertThat(snapshotCaseIds(suite, 2)).containsExactly(testCase);
        }

        /**
         * The arm with no write to the suite at all. Nobody touched the folder — somebody tagged a
         * case three screens away — and its membership changed, which is why "the suite's
         * definition changed" was the wrong reading of C1.
         */
        @Test
        @DisplayName("tagging a case into a tag rule's reach advances that suite")
        void taggingACase() throws Exception {
            UUID suite = createSuite("Payments");
            tagRule(suite, "cv-tagged-arm");
            assertThat(versionNumbers(suite))
                    .as("a rule matching nothing changes no membership and writes no version")
                    .containsExactly(1);

            UUID testCase = createCase("Capture a payment", null);
            retag(testCase, "cv-tagged-arm");

            assertThat(versionNumbers(suite)).containsExactly(1, 2);
            assertThat(snapshotCaseIds(suite, 2)).containsExactly(testCase);
        }

        /**
         * <strong>The assertion the two-phase read exists for.</strong> Reconciling only the suites
         * that hold the case after the write passes every other test in this class.
         */
        @Test
        @DisplayName("moving a case out advances the suite it LEFT, not only the one it joined")
        void theLosingSuiteAdvancesToo() throws Exception {
            UUID from = createSuite("Payments");
            UUID to = createSuite("Refunds");
            UUID testCase = createCase("Capture a payment", from);

            refile(testCase, to);

            assertThat(versionNumbers(from))
                    .as("Payments held this case and now holds nothing — a composition change, and "
                            + "the one that is unfindable a line after the write")
                    .containsExactly(1, 2, 3);
            assertThat(snapshotCaseIds(from, 3)).isEmpty();
            assertThat(versionNumbers(to)).containsExactly(1, 2);
            assertThat(snapshotCaseIds(to, 2)).containsExactly(testCase);
        }

        @Test
        @DisplayName("deleting a case advances every suite that held it")
        void deletingACase() throws Exception {
            UUID filed = createSuite("Payments");
            UUID claimed = createSuite("Regression");
            UUID testCase = createCase("Capture a payment", filed);
            claim(claimed, testCase);

            delete("/api/v1/projects/" + BANKING + "/cases/" + testCase);

            assertThat(versionNumbers(filed)).containsExactly(1, 2, 3);
            assertThat(snapshotCaseIds(filed, 3)).isEmpty();
            assertThat(versionNumbers(claimed))
                    .as("a soft-deleted case leaves all three arms, so the folder that CLAIMED it "
                            + "lost it too")
                    .containsExactly(1, 2, 3);
            assertThat(snapshotCaseIds(claimed, 3)).isEmpty();
        }
    }

    // =====================================================================
    // The acts that must not advance it — where decide() earns its existence
    // =====================================================================

    @Nested
    @DisplayName("The acts that leave it alone")
    class Unchanged {

        @Test
        @DisplayName("saving the suite dialog without changing membership writes nothing")
        void aNoOpSuiteSave() throws Exception {
            UUID suite = createSuite("Payments");
            UUID testCase = createCase("Capture a payment", null);
            claim(suite, testCase);

            claim(suite, testCase);
            claim(suite, testCase);

            assertThat(versionNumbers(suite))
                    .as("the membership writer is set-semantic, so an unconditional bump would "
                            + "mint a version every time anybody pressed Save")
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("renaming a suite writes nothing — a name is content")
        void aRename() throws Exception {
            UUID suite = createSuite("Payments");
            createCase("Capture a payment", suite);

            rename(suite, "Payments and refunds");

            assertThat(versionNumbers(suite)).containsExactly(1, 2);
        }

        @Test
        @DisplayName("editing a case's title writes nothing on the suites holding it")
        void aCaseContentEdit() throws Exception {
            UUID suite = createSuite("Payments");
            UUID testCase = createCase("Capture a payment", suite);

            retitle(testCase, "Capture a payment in full");

            assertThat(versionNumbers(suite))
                    .as("the case write path declares its suites unconditionally, so this is "
                            + "decide() taking its false branch on the commonest write in the "
                            + "product")
                    .containsExactly(1, 2);
        }

        /**
         * <strong>The one only a hash over the RESOLVED membership gets right.</strong> The filed
         * arm genuinely changed and the suite's contents did not, because the tag rule was already
         * pulling the case in. An implementation snapshotting the suite's <em>definition</em> —
         * {@code suite_case} rows plus tag rules — records a new version here and is wrong in the
         * direction nobody checks: the version list grows entries whose composition is identical.
         */
        @Test
        @DisplayName("filing a case into a suite that already claimed it by tag writes nothing")
        void filingWhatIsAlreadyTagged() throws Exception {
            UUID suite = createSuite("Payments");
            tagRule(suite, "cv-already-tagged");
            UUID testCase = createCase("Capture a payment", null);
            retag(testCase, "cv-already-tagged");
            assertThat(versionNumbers(suite)).containsExactly(1, 2);

            refile(testCase, suite);

            assertThat(versionNumbers(suite))
                    .as("it was already a member through the tag arm; what the folder holds is "
                            + "unchanged, so there is no new composition")
                    .containsExactly(1, 2);
        }
    }

    // =====================================================================
    // The sharp edge itself — what a review pins
    // =====================================================================

    @Nested
    @DisplayName("The review pin — the thing C1 was actually about")
    class ThePin {

        /**
         * The whole slice, stated once. Before §238 both numbers here were 1, and they were 1 for
         * every suite in every deployment — so an auditor asking what was approved got a number
         * that could not distinguish a folder of four cases from a folder of four hundred.
         */
        @Test
        @DisplayName("an approval pins the composition standing at the time, not the constant 1")
        void thePinMoves() throws Exception {
            UUID suite = createSuite("Payments");
            createCase("Capture a payment", suite);
            createCase("Void an authorization", suite);

            setStatus(LEAD, suite, "IN_REVIEW");
            setStatus(APPROVER, suite, "APPROVED");

            assertThat(pinnedVersionNo(suite, "APPROVED"))
                    .as("two cases filed after the create, so the composition in force is v3")
                    .isEqualTo(3);
        }

        /**
         * §238 diverged from C1's wording — "membership/status change" — on the status half, and
         * this is the reason in an assertion. A bump here would make the pin name a version the
         * approval itself minted, when what an auditor needs back is the composition that WAS
         * approved.
         */
        @Test
        @DisplayName("the transition itself writes no version, so the pin names what was approved")
        void approvingDoesNotMintItsOwnVersion() throws Exception {
            UUID suite = createSuite("Payments");
            createCase("Capture a payment", suite);

            setStatus(LEAD, suite, "IN_REVIEW");
            setStatus(APPROVER, suite, "APPROVED");

            assertThat(versionNumbers(suite))
                    .as("approving a folder does not change what is in it — TestPlan.applyStatus "
                            + "declined the same bump in §73")
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("a composition change after the approval leaves the pin where it was")
        void aLaterChangeDoesNotRewriteThePin() throws Exception {
            UUID suite = createSuite("Payments");
            createCase("Capture a payment", suite);
            setStatus(LEAD, suite, "IN_REVIEW");
            setStatus(APPROVER, suite, "APPROVED");

            createCase("Void an authorization", suite);

            assertThat(currentVersionNo(suite)).isEqualTo(3);
            assertThat(pinnedVersionNo(suite, "APPROVED"))
                    .as("the record of what was signed off is a past claim and stays true")
                    .isEqualTo(2);
        }
    }

    // =====================================================================
    // Bulk — the fan-out, and the reason reconciliation is over a SET
    // =====================================================================

    @Nested
    @DisplayName("A bulk move — one version per suite, not one per case")
    class Bulk {

        @Test
        @DisplayName("moving three cases between two suites writes one version on each")
        void oneVersionPerSuite() throws Exception {
            UUID from = createSuite("Payments");
            UUID to = createSuite("Refunds");
            UUID a = createCase("Capture a payment", from);
            UUID b = createCase("Void an authorization", from);
            UUID c = createCase("Settle a batch", from);

            List<Integer> before = versionNumbers(from);
            bulkMoveToSuite(to, a, b, c);

            assertThat(versionNumbers(from))
                    .as("one row for the whole operation — the reconcile runs once per transaction "
                            + "over a set, so this is 4 and not 7")
                    .hasSize(before.size() + 1);
            assertThat(snapshotCaseIds(from, versionNumbers(from).getLast())).isEmpty();
            assertThat(snapshotCaseIds(to, versionNumbers(to).getLast()))
                    .containsExactlyInAnyOrder(a, b, c);
        }

        @Test
        @DisplayName("a dry run writes nothing at all")
        void aPreviewIsNotAWrite() throws Exception {
            UUID from = createSuite("Payments");
            UUID to = createSuite("Refunds");
            UUID testCase = createCase("Capture a payment", from);

            List<Integer> fromBefore = versionNumbers(from);
            List<Integer> toBefore = versionNumbers(to);

            bulkMoveToSuite(to, true, testCase);

            assertThat(versionNumbers(from)).isEqualTo(fromBefore);
            assertThat(versionNumbers(to))
                    .as("a preview that wrote a composition would be the one place this endpoint "
                            + "mutates behind a flag whose whole promise is that it does not")
                    .isEqualTo(toBefore);
        }
    }

    // ---------------------------------------------------------------- driving ----

    private UUID createSuite(String name, UUID... caseIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        if (caseIds.length > 0) {
            payload.put("caseIds", idStrings(caseIds));
        }
        return UUID.fromString(post("/api/v1/projects/" + BANKING + "/suites", payload)
                .get("id").asText());
    }

    private UUID createCase(String title, UUID suiteId) throws Exception {
        Map<String, Object> payload = casePayload(title);
        payload.put("suiteId", suiteId == null ? null : suiteId.toString());
        return UUID.fromString(post("/api/v1/projects/" + BANKING + "/cases", payload)
                .get("id").asText());
    }

    private Map<String, Object> casePayload(String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by SuiteCompositionVersionTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing");
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);
        return payload;
    }

    /**
     * Adds a case through the suite's own {@code caseIds} arm.
     *
     * <p>The name is carried because the endpoint requires it — a suite PATCH is a whole-shape edit,
     * so omitting it is a 400 on {@code name} rather than a partial update. Read back rather than
     * remembered, so a helper cannot rename a folder as a side effect of claiming a case.
     */
    private void claim(UUID suiteId, UUID... caseIds) throws Exception {
        Map<String, Object> payload = suiteEdit(suiteId);
        payload.put("caseIds", idStrings(caseIds));
        patch("/api/v1/projects/" + BANKING + "/suites/" + suiteId, payload);
    }

    private void tagRule(UUID suiteId, String tag) throws Exception {
        Map<String, Object> payload = suiteEdit(suiteId);
        payload.put("tagRules", List.of(tag));
        patch("/api/v1/projects/" + BANKING + "/suites/" + suiteId, payload);
    }

    private Map<String, Object> suiteEdit(UUID suiteId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", nameOf(suiteId));
        return payload;
    }

    private String nameOf(UUID suiteId) throws Exception {
        for (JsonNode suite : get("/api/v1/projects/" + BANKING + "/suites")) {
            if (suite.get("id").asText().equals(suiteId.toString())) {
                return suite.get("name").asText();
            }
        }
        throw new IllegalStateException("no such suite: " + suiteId);
    }

    private void rename(UUID suiteId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        patch("/api/v1/projects/" + BANKING + "/suites/" + suiteId, payload);
    }

    private void setStatus(UUID actor, UUID suiteId, String status) throws Exception {
        put(actor, "/api/v1/projects/" + BANKING + "/suites/" + suiteId + "/status",
                Map.of("status", status));
    }

    private void retitle(UUID caseId, String title) throws Exception {
        patch("/api/v1/projects/" + BANKING + "/cases/" + caseId, casePayloadFor(caseId, title));
    }

    private void refile(UUID caseId, UUID suiteId) throws Exception {
        Map<String, Object> payload = casePayloadFor(caseId, null);
        payload.put("suiteId", suiteId.toString());
        patch("/api/v1/projects/" + BANKING + "/cases/" + caseId, payload);
    }

    private void retag(UUID caseId, String... tags) throws Exception {
        Map<String, Object> payload = casePayloadFor(caseId, null);
        payload.put("tags", List.of(tags));
        patch("/api/v1/projects/" + BANKING + "/cases/" + caseId, payload);
    }

    /**
     * The stored case as an edit payload, so a PATCH changes only what the caller names.
     *
     * <p>Read back over HTTP rather than assembled from memory: the update endpoint validates the
     * whole shape, and a hand-built payload that omitted a required field would fail as a 400 that
     * looks like the assertion under test.
     */
    private Map<String, Object> casePayloadFor(UUID caseId, String title) throws Exception {
        JsonNode stored = get("/api/v1/projects/" + BANKING + "/cases/" + caseId);

        Map<String, Object> payload = casePayload(
                title == null ? stored.get("title").asText() : title);
        payload.put("suiteId", stored.get("suiteId").isNull()
                ? null
                : stored.get("suiteId").asText());

        List<Map<String, Object>> steps = new ArrayList<>();
        for (JsonNode step : stored.get("steps")) {
            Map<String, Object> carried = new LinkedHashMap<>();
            carried.put("id", step.get("id").asText());
            carried.put("keyword", null);
            carried.put("action", step.get("action").asText());
            carried.put("testData", null);
            carried.put("expectedResult", null);
            carried.put("sharedStepId", null);
            steps.add(carried);
        }
        payload.put("steps", steps);

        List<String> tags = new ArrayList<>();
        for (JsonNode tag : stored.get("tags")) {
            tags.add(tag.asText());
        }
        payload.put("tags", tags);
        return payload;
    }

    private void bulkMoveToSuite(UUID suiteId, UUID... caseIds) throws Exception {
        bulkMoveToSuite(suiteId, false, caseIds);
    }

    private void bulkMoveToSuite(UUID suiteId, boolean dryRun, UUID... caseIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "MOVE_TO_SUITE");
        payload.put("caseIds", idStrings(caseIds));
        payload.put("dryRun", dryRun);
        payload.put("payload", Map.of("suiteId", suiteId.toString()));

        post("/api/v1/projects/" + BANKING + "/cases/bulk", payload);
    }

    private static List<String> idStrings(UUID... ids) {
        List<String> strings = new ArrayList<>();
        for (UUID id : ids) {
            strings.add(id.toString());
        }
        return strings;
    }

    // ---------------------------------------------------------------- reading ----

    private List<Integer> versionNumbers(UUID suiteId) throws SQLException {
        List<Integer> numbers = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
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

    private int currentVersionNo(UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT current_version_no FROM suite WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the suite must exist").isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** The case ids one recorded composition names — the snapshot, parsed. */
    private List<UUID> snapshotCaseIds(UUID suiteId, int versionNo) throws Exception {
        String snapshot;
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT snapshot FROM suite_version WHERE suite_id = CAST(? AS uuid) "
                             + "AND version_no = ?")) {
            statement.setString(1, suiteId.toString());
            statement.setInt(2, versionNo);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("suite %s has no v%s", suiteId, versionNo).isTrue();
                snapshot = rows.getString(1);
            }
        }

        List<UUID> caseIds = new ArrayList<>();
        for (JsonNode id : json.readTree(snapshot).get("caseIds")) {
            caseIds.add(UUID.fromString(id.asText()));
        }
        return caseIds;
    }

    /**
     * The composition version this suite's review pinned itself to — {@code review
     * .subject_version_no}, whose own schema comment is "an approval applies to a SPECIFIC version".
     *
     * <p>Stamped when the review OPENS, at the submission, and carried through the approval on the
     * same row. That is the reading C1 wanted and the reason the approval itself must not bump: the
     * number has to name the composition that was put up for review.
     */
    private int pinnedVersionNo(UUID suiteId, String state) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT subject_version_no FROM review WHERE subject_id = CAST(? AS uuid) "
                             + "AND subject_type = 'SUITE' AND state = ? "
                             + "ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, suiteId.toString());
            statement.setString(2, state);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the suite must have a review in state %s", state).isTrue();
                return rows.getInt(1);
            }
        }
    }

    // ---------------------------------------------------------------- transport ----

    private JsonNode post(String path, Map<String, Object> payload) throws Exception {
        return body(mvc.perform(request(MockMvcRequestBuilders.post(path), LEAD, payload)).andReturn());
    }

    private void patch(String path, Map<String, Object> payload) throws Exception {
        body(mvc.perform(request(MockMvcRequestBuilders.patch(path), LEAD, payload)).andReturn());
    }

    private void put(UUID actor, String path, Map<String, Object> payload) throws Exception {
        body(mvc.perform(request(MockMvcRequestBuilders.put(path), actor, payload)).andReturn());
    }

    private JsonNode get(String path) throws Exception {
        return body(mvc.perform(MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                .accept(MediaType.APPLICATION_JSON)).andReturn());
    }

    private void delete(String path) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.delete(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture depends on this delete: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(204);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            UUID actor,
            Map<String, Object> payload) throws Exception {

        return builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(actor, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
    }

    private JsonNode body(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("the fixture depends on this call succeeding: %s",
                        result.getResponse().getContentAsString())
                .isBetween(200, 299);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }
}
