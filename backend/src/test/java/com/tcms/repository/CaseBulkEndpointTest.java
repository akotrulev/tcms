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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /projects/{projectId}/cases/bulk} — mass edits, previewed before they happen.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A Test Engineer approving two hundred cases.</strong> The roles spec grants
 *       {@code case:approve} to Lead and up and explicitly not to Test Engineer, while
 *       {@code case:edit} — the obvious gate, and the one every sibling write here uses — is granted
 *       to both. Gating on {@code case:edit} passes every other test in this file. Nothing about the
 *       widening is visible at the gate, which is why it gets its own persona and its own
 *       assertions.</li>
 *   <li><strong>Approval reachable without review.</strong> §6g's state machine has exactly one gate
 *       and it stands between {@code IN_REVIEW} and {@code APPROVED}. If {@code DRAFT → APPROVED}
 *       were legal the gate would guard a door beside an open one.</li>
 *   <li><strong>A preview that writes.</strong> The entire contract of this endpoint.</li>
 *   <li><strong>An id that vanishes from the accounting.</strong> {@code changed + unchanged +
 *       skipped} must equal {@code total} for every request. The MSW mock dropped ids it could not
 *       resolve, so a user who selected 44 was shown an accounting of 42.</li>
 *   <li><strong>A bulk change to authored content with no version row.</strong> Silent, and it
 *       surfaces as the NEXT person's save being credited with this change. Pinned by a round trip
 *       rather than by reading, because the failure is a hash equality nobody can eyeball.</li>
 *   <li><strong>An argument from another project.</strong> A suite, a person or a condition the
 *       caller can name but the project does not contain.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — sharing them is how §19's first defect
 * happened, green alone and red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case bulk endpoint — preview, commit, and the gate that depends on the argument")
class CaseBulkEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("4b000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("4b000000-0000-4000-8000-00000000000b");

    private static final UUID BANKING = UUID.fromString("4b001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling whose cases must not be writable. */
    private static final UUID MOBILE = UUID.fromString("4b001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("4b002000-0000-4000-8000-000000000001");

    /** Test Lead on BANKING. Holds {@code case:edit} AND {@code case:approve}. */
    private static final UUID LEAD = UUID.fromString("4b003000-0000-4000-8000-000000000001");
    /** Test Engineer on BANKING. Holds {@code case:edit} and NOT {@code case:approve}. */
    private static final UUID ENGINEER = UUID.fromString("4b003000-0000-4000-8000-000000000002");
    /** Viewer on BANKING. Holds {@code project:read} and neither write permission. */
    private static final UUID VIEWER = UUID.fromString("4b003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing at all — must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("4b003000-0000-4000-8000-000000000004");
    /** In Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("4b003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("4b004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("4b004000-0000-4000-8000-000000000002");
    private static final UUID MOBILE_SUITE = UUID.fromString("4b004000-0000-4000-8000-000000000003");

    private static final UUID PRECONDITION = UUID.fromString("4b005000-0000-4000-8000-000000000001");
    /** A condition in the sibling project — nameable, and not attachable here. */
    private static final UUID FOREIGN_CONDITION =
            UUID.fromString("4b005000-0000-4000-8000-000000000002");

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
            company(ACME, "bulk-acme", "Acme", now);
            company(UMBRA, "bulk-umbra", "Umbra", now);

            activeUser(LEAD, "lead@bulk-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@bulk-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@bulk-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@bulk-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@bulk-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "BAOLB", "Online Banking", now);
            seedProject(MOBILE, ACME, "BAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("bulk-lead", ACME, Role.TEST_LEAD_ID, LEAD, BANKING, now);
            grantOnProject("bulk-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, BANKING, now);
            grantOnProject("bulk-view", ACME, Role.VIEWER_ID, VIEWER, BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "BUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("bulk-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedTrees();
        resetMutableRows();
        seedCases();
        seedConditions();
    }

    /** Deletes everything a test can write, so each one starts from the seeded state (§19). */
    private void resetMutableRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            delete(connection, "DELETE FROM audit_event");
            delete(connection, "DELETE FROM case_condition");
            delete(connection, "DELETE FROM case_tag");
            delete(connection, "DELETE FROM condition");
            delete(connection, "DELETE FROM test_case");
        }
        try (Connection connection = appConnection(UMBRA)) {
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
    // The gate that depends on the argument — the reason this slice touched authorization
    // =====================================================================

    @Nested
    @DisplayName("The approve gate — case:approve, not case:edit")
    class TheApproveGate {

        @Test
        @DisplayName("a Test Engineer may submit their drafts for review — that needs only case:edit")
        void engineerMaySubmitForReview() throws Exception {
            MvcResult result = commit(ENGINEER, setStatus("IN_REVIEW", draft(), secondDraft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(changed(result)).containsExactly("TC-1", "TC-5");
            assertThat(statusOf(draft())).isEqualTo("IN_REVIEW");
            assertThat(statusOf(secondDraft())).isEqualTo("IN_REVIEW");
        }

        /**
         * §75 reversed this test's original assertion, and the reversal is the finding.
         *
         * <p>It used to assert that the same request moved an <strong>approved</strong> case back to
         * review as well, on the recorded grounds that <em>"sending an APPROVED case BACK to review
         * is an edit and not an un-approval a lead must sign off"</em>. Both halves of that were
         * wrong: §6g draws no {@code APPROVED → IN_REVIEW} edge at all, and leaving an approval is
         * an approval act whatever the target happens to be called. The old rule read the target
         * only, so it answered {@code case:edit} — and a Test Engineer could unmake a lead's
         * sign-off in one click over a filtered selection.
         */
        @Test
        @DisplayName("an engineer cannot pull an APPROVED case back — the row is skipped, not moved")
        void engineerCannotUnapproveThroughABulkStatusChange() throws Exception {
            MvcResult result = commit(ENGINEER, setStatus("IN_REVIEW", draft(), approved()));

            // 200, not 403: the selection is a filter's output and mixes statuses, so the rows the
            // caller MAY move still move. The refused one comes back named.
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(changed(result)).containsExactly("TC-1");
            assertThat(skippedKeys(result)).containsExactly("TC-3");
            assertThat(skippedReasons(result).getFirst())
                    .isEqualTo("Only a test lead can change a case that is already approved.");
            assertThat(statusOf(approved())).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("an engineer cannot deprecate — retiring is the soft form of a delete")
        void engineerCannotDeprecate() throws Exception {
            MvcResult result = commit(ENGINEER, setStatus("DEPRECATED", draft()));

            assertThat(skippedKeys(result)).containsExactly("TC-1");
            assertThat(skippedReasons(result).getFirst())
                    .isEqualTo("Only a test lead can deprecate cases.");
            assertThat(statusOf(draft())).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a Test Engineer is REFUSED approving, and nothing is written")
        void engineerMayNotApprove() throws Exception {
            // THE defect this slice exists to prevent. A Test Engineer holds case:edit, so a gate
            // naming that permission lets this through — and every other test in this class still
            // passes. Roles spec's Review table and plan §6g both put this transition behind
            // case:approve, held by Lead and up.
            MvcResult result = commit(ENGINEER, setStatus("APPROVED", inReview()));

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(statusOf(inReview()))
                    .as("a refused approval must not have written anything")
                    .isEqualTo("IN_REVIEW");
            assertThat(auditRows()).isEmpty();
        }

        @Test
        @DisplayName("the engineer's refusal says to submit for review; a Viewer's does not")
        void theTwoRefusalsGiveOppositeAdvice() throws Exception {
            // "You cannot change cases here" is FALSE for an engineer who edits cases every day, and
            // would send them to ask for a permission they already hold. A Viewer holds neither, so
            // advice about submitting for review would send THEM to the wrong place.
            JsonNode engineers = body(commit(ENGINEER, setStatus("APPROVED", inReview())));
            JsonNode viewers = body(commit(VIEWER, setStatus("APPROVED", inReview())));

            assertThat(engineers.get("detail").asText()).contains("test lead", "review");
            assertThat(viewers.get("detail").asText()).doesNotContain("review");

            // Neither may name the key: it is not actionable by someone who cannot grant it.
            assertThat(engineers.toString()).doesNotContain("case:approve");
            assertThat(viewers.toString()).doesNotContain("case:edit", "case:approve");
        }

        @Test
        @DisplayName("a Test Lead approves a case that is in review")
        void leadMayApprove() throws Exception {
            MvcResult result = commit(LEAD, setStatus("APPROVED", inReview()));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(changed(result)).containsExactly("TC-2");
            assertThat(statusOf(inReview())).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("a Viewer is refused every action, and gets 403 rather than 404 — it can read")
        void viewerIsForbidden() throws Exception {
            MvcResult result = commit(VIEWER, setStatus("IN_REVIEW", draft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(statusOf(draft())).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a member with no grant gets 404, NOT 403 — persona 3")
        void noGrantIsNotFound() throws Exception {
            MvcResult result = commit(NO_GRANT, setStatus("IN_REVIEW", draft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("a caller in another company gets 404 on this project")
        void outsiderIsNotFound() throws Exception {
            MvcResult result = mvc.perform(post(BANKING)
                            .header("Authorization", "Bearer " + tokenFor(OUTSIDER, UMBRA))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(setStatus("IN_REVIEW", draft()))))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
        }
    }

    // =====================================================================
    // The state machine
    // =====================================================================

    @Nested
    @DisplayName("The state machine — APPROVED is reachable only from IN_REVIEW (§6g)")
    class TheStateMachine {

        @Test
        @DisplayName("a DRAFT cannot be approved directly, and is skipped with the reason")
        void draftCannotJumpToApproved() throws Exception {
            // Without this the case:approve gate guards a door standing beside an open one: a lead
            // could approve anything without it ever passing review, and the review workflow would
            // be advisory in a product whose whole point is that it is not.
            MvcResult result = commit(LEAD, setStatus("APPROVED", draft(), inReview()));

            assertThat(changed(result)).containsExactly("TC-2");
            assertThat(skippedKeys(result)).containsExactly("TC-1");
            // LifecycleLadder's wording since §75 — one sentence for a suite and a case alike, and
            // the mock copies this one rather than keeping a second of its own (§52).
            assertThat(skippedReasons(result).getFirst())
                    .isEqualTo("A case is approved after it has been reviewed. Submit it for "
                            + "review first.");
            assertThat(statusOf(draft())).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a case already in the target status is unchanged, not skipped")
        void alreadyThereIsUnchangedNotSkipped() throws Exception {
            // Compliant is not failed. Folding the two together puts a warning on an operation that
            // did exactly what was asked.
            MvcResult result = commit(LEAD, setStatus("APPROVED", approved(), inReview()));

            assertThat(unchanged(result)).containsExactly("TC-3");
            assertThat(changed(result)).containsExactly("TC-2");
            assertThat(skippedKeys(result)).isEmpty();
        }

        @Test
        @DisplayName("SUBMIT_FOR_REVIEW is SET_STATUS(IN_REVIEW) — one implementation, two spellings")
        void submitForReviewIsAnAlias() throws Exception {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("action", "SUBMIT_FOR_REVIEW");
            operation.put("caseIds", List.of(draft().toString()));
            operation.put("dryRun", false);

            MvcResult result = post(ENGINEER, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(statusOf(draft())).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("SUBMIT_FOR_REVIEW carrying a contradicting status is refused, not silently applied")
        void submitForReviewRefusesAContradiction() throws Exception {
            // Otherwise the request is applied as something other than what its action says — and
            // an APPROVED slipped in here would bypass the permission SET_STATUS would have asked.
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("action", "SUBMIT_FOR_REVIEW");
            operation.put("caseIds", List.of(inReview().toString()));
            operation.put("dryRun", false);
            operation.put("payload", Map.of("lifecycleStatus", "APPROVED"));

            MvcResult result = post(ENGINEER, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("lifecycleStatus");
            assertThat(statusOf(inReview())).isEqualTo("IN_REVIEW");
        }
    }

    // =====================================================================
    // Preview and the accounting invariant
    // =====================================================================

    @Nested
    @DisplayName("Preview, commit, and the arithmetic the dialog renders")
    class PreviewAndAccounting {

        @Test
        @DisplayName("a dry run reports what WOULD change and writes nothing at all")
        void previewWritesNothing() throws Exception {
            MvcResult result = preview(LEAD, setStatus("APPROVED", inReview()));

            assertThat(body(result).get("dryRun").asBoolean()).isTrue();
            assertThat(changed(result)).containsExactly("TC-2");
            assertThat(statusOf(inReview()))
                    .as("a preview that writes is the whole defect this endpoint prevents")
                    .isEqualTo("IN_REVIEW");
            assertThat(auditRows()).isEmpty();
            assertThat(body(result).get("auditEventId").isNull()).isTrue();
        }

        @Test
        @DisplayName("dryRun defaults to TRUE — a client that forgets the flag gets a preview")
        void theDefaultIsSafe() throws Exception {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("action", "SET_STATUS");
            operation.put("caseIds", List.of(inReview().toString()));
            operation.put("payload", Map.of("lifecycleStatus", "APPROVED"));
            // No dryRun member at all.

            MvcResult result = post(LEAD, operation);

            assertThat(body(result).get("dryRun").asBoolean()).isTrue();
            assertThat(statusOf(inReview())).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("the preview and the commit reach the same verdicts for the same state")
        void previewAgreesWithCommit() throws Exception {
            MvcResult previewed = preview(LEAD, setStatus("APPROVED", draft(), inReview(), approved()));
            MvcResult committed = commit(LEAD, setStatus("APPROVED", draft(), inReview(), approved()));

            assertThat(changed(committed)).isEqualTo(changed(previewed));
            assertThat(unchanged(committed)).isEqualTo(unchanged(previewed));
            assertThat(skippedKeys(committed)).isEqualTo(skippedKeys(previewed));
        }

        @Test
        @DisplayName("every requested id lands in exactly one bucket — changed + unchanged + skipped == total")
        void nothingVanishesFromTheAccounting() throws Exception {
            // The MSW mock's `if (!row) continue` dropped unresolvable ids, so a selection of 44 was
            // reported as 42 and the user was told nothing about the other two. An id that is
            // deleted, in a sibling project, or fictional is SKIPPED with a reason, never dropped.
            UUID fictional = UUID.fromString("4b009000-0000-4000-8000-0000000000ff");

            MvcResult result = commit(LEAD, setStatus(
                    "APPROVED", draft(), inReview(), approved(), mobileCase(), fictional));

            JsonNode result0 = body(result);
            int total = result0.get("total").asInt();
            int accounted = changed(result).size() + unchanged(result).size()
                    + skippedKeys(result).size();

            assertThat(total).isEqualTo(5);
            assertThat(accounted).isEqualTo(total);
            // The sibling project's case and the fictional one are both "no longer in this project"
            // — told apart from an absent row would be an oracle for what exists elsewhere (§6t).
            assertThat(skippedKeys(result)).contains(mobileCase().toString(), fictional.toString());
            assertThat(statusOf(mobileCase()))
                    .as("a case in a project the caller was not granted must not be edited")
                    .isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a duplicated id is collapsed and reported once")
        void duplicatesAreCollapsed() throws Exception {
            MvcResult result = commit(LEAD, setStatus("APPROVED", inReview(), inReview(), inReview()));

            assertThat(body(result).get("total").asInt()).isEqualTo(1);
            assertThat(changed(result)).containsExactly("TC-2");
        }

        @Test
        @DisplayName("one audit event names every changed case, not one row per case")
        void oneAuditEventForTheWholeOperation() throws Exception {
            // Two DRAFTS, not a draft and an approved one: since §75 the ladder has no
            // APPROVED → IN_REVIEW edge, so the second row would be skipped and this would be
            // asserting one target id while claiming to assert two.
            MvcResult result = commit(LEAD, setStatus("IN_REVIEW", draft(), secondDraft()));

            List<AuditRow> rows = auditRows();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().action()).isEqualTo("case.bulk.status_changed");
            assertThat(rows.getFirst().targetIds()).hasSize(2);
            assertThat(body(result).get("auditEventId").asText())
                    .isEqualTo(rows.getFirst().id().toString());
        }

        @Test
        @DisplayName("approving gets its own audit action, so 'who signed off' is one query")
        void approvalIsItsOwnAuditAction() throws Exception {
            commit(LEAD, setStatus("APPROVED", inReview()));

            assertThat(auditRows().getFirst().action()).isEqualTo("case.bulk.approved");
        }

        @Test
        @DisplayName("a commit that changes nothing writes no audit row")
        void nothingChangedMeansNothingRecorded() throws Exception {
            MvcResult result = commit(LEAD, setStatus("APPROVED", approved()));

            assertThat(unchanged(result)).containsExactly("TC-3");
            assertThat(auditRows())
                    .as("an audit row for an operation that touched nothing records what did not happen")
                    .isEmpty();
        }
    }

    // =====================================================================
    // Versions — the hash interaction
    // =====================================================================

    @Nested
    @DisplayName("Versions — the two actions inside the content hash")
    class Versions {

        @Test
        @DisplayName("a status change writes NO version — lifecycle_status is not authored content")
        void statusChangeWritesNoVersion() throws Exception {
            commit(LEAD, setStatus("APPROVED", inReview()));

            assertThat(versionsOf(inReview()))
                    .as("CaseEdit does not carry lifecycleStatus, so its hash cannot have moved")
                    .isEmpty();
        }

        @Test
        @DisplayName("a suite move DOES write a version — suiteId is in the content hash")
        void suiteMoveWritesAVersion() throws Exception {
            // Skipping it is silent: the next single-case save would compute a hash that no longer
            // matches the newest stored version and would write a version crediting THAT person
            // with this move.
            MvcResult result = commit(LEAD, moveToSuite(REFUNDS, draft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(changed(result)).containsExactly("TC-1");
            assertThat(versionsOf(draft())).hasSize(1);
        }

        @Test
        @DisplayName("the reconstructed hash matches the edit path's — a save straight after a bulk move writes no version")
        void theReconstructedHashAgreesWithTheEditPath() throws Exception {
            // THE round trip, and the only way to check this property: the failure is a hash
            // inequality between two normalizers, which no amount of reading the two finds. If
            // CaseEdit.of diverged from CaseEdit.validate by so much as a trim, this second version
            // would appear with an empty diff — and it would appear on EVERY case ever bulk-edited.
            commit(LEAD, moveToSuite(REFUNDS, draft()));
            assertThat(versionsOf(draft())).hasSize(1);

            // Now save the case through the ordinary edit path, changing nothing.
            MvcResult saved = patchUnchanged(LEAD, draft(), stepId("draft"));

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(versionsOf(draft()))
                    .as("a no-op save after a bulk move must add no version — if it does, "
                            + "CaseEdit.of normalizes differently from CaseEdit.validate")
                    .hasSize(1);
        }

        @Test
        @DisplayName("an automation-status change writes a version too, and carries its reason")
        void automationStatusWritesAVersion() throws Exception {
            Map<String, Object> operation = operation("SET_AUTOMATION_STATUS", false, draft());
            operation.put("payload", Map.of(
                    "automationStatus", "NOT_AUTOMATABLE",
                    "notAutomatableReason", "Needs a card terminal"));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(changed(result)).containsExactly("TC-1");
            assertThat(versionsOf(draft())).hasSize(1);
        }

        @Test
        @DisplayName("NOT_AUTOMATABLE without a reason is a 400 on the field, not a 500 from the constraint")
        void notAutomatableNeedsItsReason() throws Exception {
            Map<String, Object> operation = operation("SET_AUTOMATION_STATUS", false, draft());
            operation.put("payload", Map.of("automationStatus", "NOT_AUTOMATABLE"));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("notAutomatableReason");
        }
    }

    // =====================================================================
    // The remaining actions, and the arguments they validate
    // =====================================================================

    @Nested
    @DisplayName("Owners, suites, tags and conditions — the argument is checked once, not per case")
    class TheOtherActions {

        @Test
        @DisplayName("SET_OWNER assigns a project member")
        void setOwnerAssigns() throws Exception {
            Map<String, Object> operation = operation("SET_OWNER", false, draft(), inReview());
            operation.put("payload", mapOf("ownerId", ENGINEER.toString()));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(changed(result)).containsExactly("TC-1", "TC-2");
            assertThat(ownerOf(draft())).isEqualTo(ENGINEER);
        }

        @Test
        @DisplayName("SET_OWNER with null clears the owner rather than being read as absent")
        void setOwnerClears() throws Exception {
            Map<String, Object> assign = operation("SET_OWNER", false, draft());
            assign.put("payload", mapOf("ownerId", ENGINEER.toString()));
            post(LEAD, assign);

            Map<String, Object> clear = operation("SET_OWNER", false, draft());
            clear.put("payload", mapOf("ownerId", null));

            MvcResult result = post(LEAD, clear);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(ownerOf(draft())).isNull();
        }

        @Test
        @DisplayName("SET_OWNER refuses somebody who is not a member of THIS project")
        void setOwnerRefusesANonMember() throws Exception {
            // §54's predicate. It cannot be a query over role_assignment: that finds only direct
            // grants and would silently omit everyone staffed through a team and every Company
            // Admin — answering 400 about a person the single-case path would have accepted.
            Map<String, Object> operation = operation("SET_OWNER", false, draft());
            operation.put("payload", mapOf("ownerId", OUTSIDER.toString()));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("ownerId");
            assertThat(ownerOf(draft())).isNull();
        }

        @Test
        @DisplayName("MOVE_TO_SUITE refuses a suite from the sibling project")
        void moveRefusesAForeignSuite() throws Exception {
            // Unchecked, this moves the case into a folder no read of either project's tree walks,
            // and it disappears from both.
            MvcResult result = commit(LEAD, moveToSuite(MOBILE_SUITE, draft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("suiteId");
        }

        @Test
        @DisplayName("ADD_TAGS is additive and set-valued — a tag already there leaves the case unchanged")
        void addTagsIsASet() throws Exception {
            Map<String, Object> operation = operation("ADD_TAGS", false, draft(), inReview());
            operation.put("payload", Map.of("tags", List.of("regression", "payments")));
            post(LEAD, operation);

            assertThat(tagsOf(draft())).containsExactly("payments", "regression");

            // The same tags again: nothing to do, and no duplicate rows.
            MvcResult again = post(LEAD, operation);

            assertThat(unchanged(again)).containsExactly("TC-1", "TC-2");
            assertThat(tagsOf(draft())).containsExactly("payments", "regression");
        }

        @Test
        @DisplayName("ATTACH_CONDITIONS attaches once, and re-attaching is unchanged")
        void attachIsIdempotent() throws Exception {
            Map<String, Object> operation = operation("ATTACH_CONDITIONS", false, draft(), inReview());
            operation.put("payload", Map.of("conditionId", PRECONDITION.toString()));

            MvcResult first = post(LEAD, operation);
            assertThat(changed(first)).containsExactly("TC-1", "TC-2");
            assertThat(conditionCountOf(draft())).isEqualTo(1);

            MvcResult second = post(LEAD, operation);
            assertThat(unchanged(second)).containsExactly("TC-1", "TC-2");
            assertThat(conditionCountOf(draft())).isEqualTo(1);
        }

        @Test
        @DisplayName("DETACH_CONDITIONS removes it, and a case that never had it is unchanged")
        void detachRemoves() throws Exception {
            Map<String, Object> attach = operation("ATTACH_CONDITIONS", false, draft());
            attach.put("payload", Map.of("conditionId", PRECONDITION.toString()));
            post(LEAD, attach);

            Map<String, Object> detach = operation("DETACH_CONDITIONS", false, draft(), inReview());
            detach.put("payload", Map.of("conditionId", PRECONDITION.toString()));

            MvcResult result = post(LEAD, detach);

            assertThat(changed(result)).containsExactly("TC-1");
            assertThat(unchanged(result)).containsExactly("TC-2");
            assertThat(conditionCountOf(draft())).isZero();
        }

        @Test
        @DisplayName("a condition from the sibling project is refused, not stapled onto 500 cases")
        void attachRefusesAForeignCondition() throws Exception {
            Map<String, Object> operation = operation("ATTACH_CONDITIONS", false, draft());
            operation.put("payload", Map.of("conditionId", FOREIGN_CONDITION.toString()));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("conditionId");
            assertThat(conditionCountOf(draft())).isZero();
        }
    }

    // =====================================================================
    // Request validation
    // =====================================================================

    @Nested
    @DisplayName("The request itself")
    class RequestValidation {

        @Test
        @DisplayName("an empty selection is a 400 on caseIds, not a 200 over nothing")
        void emptySelectionIsRefused() throws Exception {
            MvcResult result = post(LEAD, operation("SET_STATUS", false));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("caseIds");
        }

        @Test
        @DisplayName("a selection over the cap is refused before anything is read")
        void theCapIsEnforced() throws Exception {
            List<String> tooMany = new ArrayList<>();
            for (int i = 0; i <= BulkOperations.MAX_SELECTION; i++) {
                tooMany.add(UUID.randomUUID().toString());
            }
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("action", "SET_STATUS");
            operation.put("caseIds", tooMany);
            operation.put("dryRun", false);
            operation.put("payload", Map.of("lifecycleStatus", "IN_REVIEW"));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("caseIds");
        }

        @Test
        @DisplayName("SET_STATUS with no status names the field rather than defaulting one")
        void aMissingArgumentNamesItsField() throws Exception {
            MvcResult result = post(LEAD, operation("SET_STATUS", false, draft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("lifecycleStatus");
        }

        @Test
        @DisplayName("MOVE_TO_SUITE with no suite names the field")
        void moveNeedsASuite() throws Exception {
            MvcResult result = post(LEAD, operation("MOVE_TO_SUITE", false, draft()));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("suiteId");
        }

        @Test
        @DisplayName("ADD_TAGS with only blanks is refused rather than writing empty labels")
        void blankTagsAreRefused() throws Exception {
            Map<String, Object> operation = operation("ADD_TAGS", false, draft());
            operation.put("payload", Map.of("tags", List.of("  ", "")));

            MvcResult result = post(LEAD, operation);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("tags");
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private static String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/cases/bulk";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            UUID projectId) {
        return MockMvcRequestBuilders.post(path(projectId));
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult post(UUID userId, Map<String, Object> operation) throws Exception {
        return mvc.perform(post(BANKING)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(operation)))
                .andReturn();
    }

    private MvcResult commit(UUID userId, Map<String, Object> operation) throws Exception {
        operation.put("dryRun", false);
        return post(userId, operation);
    }

    private MvcResult preview(UUID userId, Map<String, Object> operation) throws Exception {
        operation.put("dryRun", true);
        return post(userId, operation);
    }

    /**
     * A genuinely no-op save through the ordinary edit path — used to prove the hash agrees.
     *
     * <p>The step's <strong>id</strong> is the part that matters and the part easy to leave out. A
     * step submitted without one is a NEW step to {@code normalizeSteps}, so the content really has
     * changed and a version really should be written — which would make this test fail for a reason
     * that has nothing to do with the two normalizers agreeing. It failed exactly that way when
     * first written.
     */
    private MvcResult patchUnchanged(UUID userId, UUID caseId, UUID stepId) throws Exception {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", stepId.toString());
        step.put("action", "Open the payments screen");

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("title", titleOf(caseId));
        edit.put("suiteId", suiteOf(caseId).toString());
        edit.put("stepFormat", "STRUCTURED");
        edit.put("priority", "CRITICAL");
        edit.put("severity", "HIGH");
        edit.put("automationStatus", automationStatusOf(caseId));
        edit.put("steps", List.of(step));

        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + BANKING + "/cases/" + caseId)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(edit)))
                .andReturn();
    }

    private Map<String, Object> operation(String action, boolean dryRun, UUID... caseIds) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("action", action);
        operation.put("caseIds", java.util.Arrays.stream(caseIds).map(UUID::toString).toList());
        operation.put("dryRun", dryRun);
        return operation;
    }

    private Map<String, Object> setStatus(String status, UUID... caseIds) {
        Map<String, Object> operation = operation("SET_STATUS", false, caseIds);
        operation.put("payload", Map.of("lifecycleStatus", status));
        return operation;
    }

    private Map<String, Object> moveToSuite(UUID suiteId, UUID... caseIds) {
        Map<String, Object> operation = operation("MOVE_TO_SUITE", false, caseIds);
        operation.put("payload", Map.of("suiteId", suiteId.toString()));
        return operation;
    }

    /** {@code Map.of} rejects a null value, and a null ownerId is the clear-the-owner case. */
    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("the endpoint answered with no body").isNotEmpty();
        return json.readTree(content);
    }

    private List<String> changed(MvcResult result) throws Exception {
        return strings(body(result).get("changed"));
    }

    private List<String> unchanged(MvcResult result) throws Exception {
        return strings(body(result).get("unchanged"));
    }

    private List<String> skippedKeys(MvcResult result) throws Exception {
        List<String> keys = new ArrayList<>();
        body(result).get("skipped").forEach(node -> keys.add(node.get("key").asText()));
        return keys;
    }

    private List<String> skippedReasons(MvcResult result) throws Exception {
        List<String> reasons = new ArrayList<>();
        body(result).get("skipped").forEach(node -> reasons.add(node.get("reason").asText()));
        return reasons;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body(result).get("errors");
        assertThat(node).as("a 400 must name the fields it refused").isNotNull();
        node.properties().forEach(entry -> errors.put(entry.getKey(), strings(entry.getValue())));
        return errors;
    }

    // =====================================================================
    // helpers — reading the database through the ORDINARY APP ROLE
    // =====================================================================

    private String statusOf(UUID caseId) throws SQLException {
        return scalar("SELECT lifecycle_status FROM test_case WHERE id = '" + caseId + "'");
    }

    private String titleOf(UUID caseId) throws SQLException {
        return scalar("SELECT title FROM test_case WHERE id = '" + caseId + "'");
    }

    private String automationStatusOf(UUID caseId) throws SQLException {
        return scalar("SELECT automation_status FROM test_case WHERE id = '" + caseId + "'");
    }

    private UUID suiteOf(UUID caseId) throws SQLException {
        String value = scalar("SELECT suite_id FROM test_case WHERE id = '" + caseId + "'");
        return value == null ? null : UUID.fromString(value);
    }

    private UUID ownerOf(UUID caseId) throws SQLException {
        String value = scalar("SELECT owner_id FROM test_case WHERE id = '" + caseId + "'");
        return value == null ? null : UUID.fromString(value);
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private List<String> tagsOf(UUID caseId) throws SQLException {
        List<String> tags = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT tag FROM case_tag WHERE case_id = '" + caseId + "' ORDER BY tag");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                tags.add(rows.getString(1));
            }
        }
        return tags;
    }

    private int conditionCountOf(UUID caseId) throws SQLException {
        String value = scalar(
                "SELECT count(*) FROM case_condition WHERE case_id = '" + caseId + "'");
        return Integer.parseInt(value);
    }

    private List<UUID> versionsOf(UUID caseId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT id FROM test_case_version WHERE case_id = '" + caseId + "'");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(UUID.fromString(rows.getString(1)));
            }
        }
        return ids;
    }

    private record AuditRow(UUID id, String action, List<UUID> targetIds) {
    }

    private List<AuditRow> auditRows() throws SQLException {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT id, action, target_ids FROM audit_event ORDER BY occurred_at");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                List<UUID> targets = new ArrayList<>();
                java.sql.Array array = result.getArray(3);
                if (array != null) {
                    for (Object value : (Object[]) array.getArray()) {
                        targets.add(UUID.fromString(value.toString()));
                    }
                }
                rows.add(new AuditRow(
                        UUID.fromString(result.getString(1)), result.getString(2), targets));
            }
        }
        return rows;
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
        UUID id = UUID.nameUUIDFromBytes(("case-bulk:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private static UUID draft() {
        return caseId("draft");
    }

    private static UUID inReview() {
        return caseId("in-review");
    }

    private static UUID approved() {
        return caseId("approved");
    }

    /**
     * A second DRAFT in the same project as {@link #draft()}.
     *
     * <p>Added in §75 for the tests that need <em>two rows that both legitimately move</em>. Before
     * the ladder was shared there was exactly one such pair — a draft and an approved case, both
     * moved to {@code IN_REVIEW} — and that pair stopped existing when the {@code APPROVED →
     * IN_REVIEW} edge was removed. Reaching for {@link #mobileCase()} instead would have looked
     * right and silently asserted nothing: it is in another project, so the endpoint reports it as
     * an id that resolved to no case.
     */
    private static UUID secondDraft() {
        return caseId("second-draft");
    }

    private static UUID mobileCase() {
        return caseId("mobile");
    }

    private static UUID caseId(String marker) {
        return UUID.nameUUIDFromBytes(("case-bulk-case:" + marker).getBytes());
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, BANKING);
            seedProjectDefaults(connection, ACME, MOBILE);
            suite(connection, PAYMENTS, ACME, BANKING, null, "BAOLB-S1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, BANKING, null, "BAOLB-S2", 2, "Refunds", "s2");
            suite(connection, MOBILE_SUITE, ACME, MOBILE, null, "BAMOB-S1", 1, "Login", "s1");
        }
    }

    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            testCase(connection, draft(), ACME, BANKING, PAYMENTS, "TC-1", 1,
                    "Full refund to card", "DRAFT");
            testCase(connection, inReview(), ACME, BANKING, PAYMENTS, "TC-2", 2,
                    "Partial refund", "IN_REVIEW");
            testCase(connection, approved(), ACME, BANKING, PAYMENTS, "TC-3", 3,
                    "Refund to wallet", "APPROVED");
            testCase(connection, mobileCase(), ACME, MOBILE, MOBILE_SUITE, "TC-4", 4,
                    "Sign in", "DRAFT");
            testCase(connection, secondDraft(), ACME, BANKING, PAYMENTS, "TC-5", 5,
                    "Refund a cancelled order", "DRAFT");

            // Every case gets one step: CaseEdit.validate refuses a STRUCTURED case with none, so a
            // stepless fixture would make every version-writing action skip instead of change — and
            // the version tests would pass for the wrong reason.
            step(connection, stepId("draft"), ACME, draft(), 0, "Open the payments screen");
            step(connection, stepId("in-review"), ACME, inReview(), 0, "Open the payments screen");
            step(connection, stepId("approved"), ACME, approved(), 0, "Open the payments screen");
            step(connection, stepId("mobile"), ACME, mobileCase(), 0, "Open the app");
            step(connection, stepId("second-draft"), ACME, secondDraft(), 0,
                    "Open the payments screen");
        }
    }

    private static UUID stepId(String marker) {
        return UUID.nameUUIDFromBytes(("case-bulk-step:" + marker).getBytes());
    }

    private void seedConditions() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            condition(connection, PRECONDITION, ACME, BANKING, "A card is registered");
            condition(connection, FOREIGN_CONDITION, ACME, MOBILE, "The app is installed");
        }
    }

    private void condition(
            Connection connection, UUID id, UUID companyId, UUID projectId, String title)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, body, type, usage_count, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?,
                        CAST(? AS jsonb), 'PRE', 0, NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, "[\"" + title + "\"]");
            statement.executeUpdate();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {

        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.executeQuery();
        }
    }

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
            String key, long keySeq, String title, String lifecycle) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description,
                     objective, expected_outcome, step_format, gherkin_source, priority, severity,
                     estimated_duration_sec, lifecycle_status, automation_status, automation_key,
                     not_automatable_reason, owner_id, current_version_no, lock_version,
                     updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, NULL, NULL, NULL, 'STRUCTURED', NULL, 'CRITICAL', 'HIGH',
                        NULL, CAST(? AS text), 'MANUAL', NULL, NULL, NULL, 1, 1,
                        CAST(? AS timestamptz), NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, lifecycle);
            statement.setString(9, "2026-01-01T00:00:00Z");
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
