package com.tcms.execution;

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
 * {@code PUT /plans/{planId}/status} and {@code DELETE /plans/{planId}} over HTTP, against real
 * Postgres with RLS in force.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A gate decided by the target instead of the edge.</strong> The obvious reading of
 *       plan §6g — {@code plan:approve} guards the move <em>into</em> APPROVED — compiles, passes a
 *       "an engineer cannot approve" test, and lets that same engineer <em>rescind</em> a lead's
 *       approval by asking for DRAFT. {@link Gate#engineerCannotRescindAnApproval} is the test the
 *       cheaper implementation fails, and it is the reason {@link PlanLifecycle#permissionFor} takes
 *       both ends of the move.</li>
 *   <li><strong>Approval reachable without review.</strong> The shape {@code CaseBulkService}
 *       refuses: a gate standing beside an open door. §6g's one gate.</li>
 *   <li><strong>A delete that orphans runs.</strong> {@code test_run.plan_id} references the plan and
 *       {@code findPlanKeys} is not filtered on {@code deleted_at}, so the runs keep rendering
 *       {@code TP-n} with a link that 404s. Nothing fails loudly, which is why the refusal is
 *       asserted rather than assumed.</li>
 *   <li><strong>An idempotent set that writes anyway.</strong> Re-stamping {@code updated_at} or
 *       adding an audit row on a repeated click buries the change that actually happened.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code ec…}, checked against every prefix
 * already in {@code src/test/java} before writing (handoff §19, and it has collided five times
 * since).
 */
@AutoConfigureMockMvc
@DisplayName("PUT /plans/{id}/status + DELETE /plans/{id} — the ladder, and the delete that refuses")
class PlanLifecycleEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ec000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("ec000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("ec001000-0000-4000-8000-000000000001");
    /** Same company, LEAD only — the source of the sibling-project plan ids the paths must refuse. */
    private static final UUID ACME_MOBILE = UUID.fromString("ec001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("ec002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code plan:edit}, {@code plan:approve} and {@code plan:delete}. */
    private static final UUID LEAD = UUID.fromString("ec003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer — holds {@code plan:edit} and <strong>neither</strong> {@code plan:approve} nor
     * {@code plan:delete}. THE persona of this class: §71 had to record that its two keys were
     * granted to identical roles, so no persona could tell them apart. This one can.
     */
    private static final UUID ENGINEER = UUID.fromString("ec003000-0000-4000-8000-000000000002");
    /** Viewer — reads the plan and is refused every write on it. */
    private static final UUID VIEWER = UUID.fromString("ec003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding no grant on the project — 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("ec003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("ec003000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("ec00ffff-0000-4000-8000-00000000ffff");

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
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "pl-acme", "Acme", now);
            company(UMBRA, "pl-umbra", "Umbra", now);

            activeUser(LEAD, "lead@pl-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@pl-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@pl-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@pl-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@pl-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "PLAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "PLAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("pl-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("pl-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("pl-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("pl-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "PLUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("pl-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedProjectDefaults();

        // Cleared LAST, so "a refusal writes no audit row" is about the request under test.
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — decided by the EDGE, because leaving an approval is an approval act")
    class Gate {

        @Test
        @DisplayName("a Test Engineer submits a plan for review — plan:edit, and they hold it")
        void engineerSubmitsForReview() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            MvcResult moved = setStatus(ENGINEER, ACME, ACME_BANKING, plan, "IN_REVIEW");

            assertThat(moved.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(moved).get("status").asText()).isEqualTo("IN_REVIEW");
            assertThat(statusOf(plan)).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("and is refused the approval — 403, the row unchanged, no audit row")
        void engineerCannotApprove() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW");
            clearAudit(ACME);

            MvcResult refused = setStatus(ENGINEER, ACME, ACME_BANKING, plan, "APPROVED");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .as("a permission key is not actionable by somebody who cannot grant it (§32)")
                    .doesNotContain("plan:approve");
            assertThat(statusOf(plan)).isEqualTo("IN_REVIEW");
            assertThat(auditActions(ACME)).isEmpty();
        }

        /**
         * <strong>The test a target-decides gate fails.</strong> The engineer is asking for
         * {@code DRAFT}, which is the target every draft plan of their own moves through freely — so
         * a rule reading only the requested status hands them the power to unmake a lead's sign-off,
         * and no other test in this class would notice.
         */
        @Test
        @DisplayName("a Test Engineer cannot RESCIND an approval by asking for DRAFT")
        void engineerCannotRescindAnApproval() throws Exception {
            UUID plan = approvedPlan();

            MvcResult refused = setStatus(ENGINEER, ACME, ACME_BANKING, plan, "DRAFT");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(statusOf(plan)).isEqualTo("APPROVED");
        }

        /*
         * 'nor un-start a plan somebody is executing against' was the second half of the edge rule.
         * §97 (M7) removed ACTIVE, so rescinding an approval is the only move down the ladder left
         * — which is the one above, and it is still the one a target-decides gate fails.
         */

        @Test
        @DisplayName("a Test Lead approves, and a lead may take it back")
        void leadApprovesAndRescinds() throws Exception {
            UUID plan = approvedPlan();

            assertThat(setStatus(LEAD, ACME, ACME_BANKING, plan, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(statusOf(plan)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a Viewer READS the plan and is refused every move — 403, nothing written")
        void viewerIsRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            clearAudit(ACME);

            // Without the read this proves nothing: a 403 would pass just as well against a route
            // that does not exist or a token that is wrong.
            assertThat(get(VIEWER, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan)
                    .getResponse().getStatus()).isEqualTo(200);

            assertThat(setStatus(VIEWER, ACME, ACME_BANKING, plan, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(statusOf(plan)).isEqualTo("DRAFT");
            assertThat(auditActions(ACME)).isEmpty();
        }

        @Test
        @DisplayName("an unreadable status is gated BEFORE it is parsed — 403 for a Viewer, 400 for a lead")
        void theVocabularyIsNotAnOracle() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // A 400 here would tell a caller who may not touch plans at all what this project's
            // ladder accepts — the disclosure §72 avoided by parsing inside its gate.
            assertThat(setStatus(VIEWER, ACME, ACME_BANKING, plan, "SHIPPED")
                    .getResponse().getStatus()).isEqualTo(403);

            MvcResult invalid = setStatus(LEAD, ACME, ACME_BANKING, plan, "SHIPPED");
            assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
            assertThat(invalid.getResponse().getContentAsString()).contains("status");
            assertThat(statusOf(plan)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("a company member with no grant gets 404, never 403")
        void noGrantIs404() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // The project IS in this caller's company, so every cross-company assertion in the
            // suite would still pass while this leaked the project's existence.
            assertThat(setStatus(NO_GRANT, ACME, ACME_BANKING, plan, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(delete(NO_GRANT, ACME, ACME_BANKING, plan)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the other client, an absent plan and a SIBLING project's plan are all 404")
        void unreachablePlansAre404() throws Exception {
            UUID mine = plan(LEAD, ACME_BANKING, "Release 4.3");
            UUID theirs = plan(LEAD, ACME_MOBILE, "Mobile 1.0");

            assertThat(setStatus(OUTSIDER, UMBRA, ACME_BANKING, mine, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(setStatus(LEAD, ACME, ACME_BANKING, ABSENT, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(404);
            // The plan is real, the project is real, the caller holds BOTH projects — only the
            // second predicate in findLiveInProject refuses this one.
            assertThat(setStatus(LEAD, ACME, ACME_BANKING, theirs, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(statusOf(theirs)).isEqualTo("DRAFT");
        }
    }

    // =====================================================================
    // The ladder
    // =====================================================================

    @Nested
    @DisplayName("The ladder — three states since §97, and the one move it refuses")
    class Ladder {

        @Test
        @DisplayName("DRAFT → IN_REVIEW → APPROVED is the whole ladder, and DRAFT is reachable from both")
        void theThreeStates() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            assertThat(setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(setStatus(LEAD, ACME, ACME_BANKING, plan, "APPROVED")
                    .getResponse().getStatus()).isEqualTo(200);
            // Withdrawing lands on DRAFT and never on IN_REVIEW: a plan sent straight back into
            // review would be one nobody edited, sitting in a reviewer's queue a second time with
            // the same content. §6g's one gate, read forwards.
            assertThat(setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(setStatus(LEAD, ACME, ACME_BANKING, plan, "DRAFT")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(statusOf(plan)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("APPROVED is reachable only from IN_REVIEW — 409 straight from draft")
        void approvalRequiresReview() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            MvcResult refused = setStatus(LEAD, ACME, ACME_BANKING, plan, "APPROVED");

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused)).contains("Submit it for review first.");
            assertThat(statusOf(plan)).isEqualTo("DRAFT");
        }

        /*
         * Three tests lived here and went with the two states §97 removed:
         *
         *   - 'a plan in review is not started behind the reviewers' backs',
         *   - 'CLOSED is reachable only from ACTIVE — a plan nobody executed is deleted, not
         *     closed', which was RunLifecycle's argument for refusing NOT_STARTED → COMPLETED,
         *   - 'closing stamps closed_at, and reopening to ACTIVE clears it again', and
         *   - 'a closed plan is reopened to ACTIVE, not dropped to DRAFT'.
         *
         * None of them is wrong; all four are about states a plan no longer has. `closed_at` is
         * dropped in V32, so there is no column left to derive in both directions.
         */

        @Test
        @DisplayName("setting the status a plan already has is 200 and writes NOTHING")
        void theIdempotentSetWritesNothing() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW");
            clearAudit(ACME);
            String updatedBefore = updatedAtOf(plan);

            MvcResult again = setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW");

            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(again).get("status").asText()).isEqualTo("IN_REVIEW");
            assertThat(updatedAtOf(plan))
                    .as("re-stamping makes a repeated click look like a change")
                    .isEqualTo(updatedBefore);
            assertThat(auditActions(ACME))
                    .as("and an audit row would bury the change that actually happened")
                    .isEmpty();
        }

        @Test
        @DisplayName("every move is one audit row naming BOTH ends, under its own verb")
        void theAuditTrailNamesBothEnds() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            clearAudit(ACME);

            setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW");
            setStatus(LEAD, ACME, ACME_BANKING, plan, "APPROVED");
            setStatus(LEAD, ACME, ACME_BANKING, plan, "DRAFT");

            Map<String, String> events = auditActions(ACME);
            // Three verbs since §97 (M7). plan.started, plan.closed and plan.reopened are no longer
            // written by anything and are the only surviving record of which releases were executed
            // on a deployment that ran them — V32 dropped the column that said so.
            assertThat(events.keySet())
                    .containsExactly("plan.submitted", "plan.approved", "plan.withdrawn");
            // Read as JSON rather than as text: the column is JSONB, so Postgres returns it in its
            // own normalized form (keys reordered, a space after each colon) and a substring
            // assertion would be testing the driver's formatting rather than what was written.
            JsonNode withdrawn = json.readTree(events.get("plan.withdrawn"));
            assertThat(withdrawn.get("from").asText()).isEqualTo("APPROVED");
            assertThat(withdrawn.get("to").asText()).isEqualTo("DRAFT");
            assertThat(withdrawn.get("key").asText()).startsWith("TP-");
        }
    }

    // =====================================================================
    // §6g's gating rule for plans — REMOVED with the edge it bound on (§97, M7)
    // =====================================================================
    //
    // A `Gating` nested class of eight tests lived here: a draft plan refused with gating on, an
    // approved one starting normally, a CLOSED plan still reopening (THE test of §82, and the one a
    // target-keyed reading of §6g failed), the lead's documented override, the flag granting
    // nothing to an engineer, the flag not counting as an override where nothing gates, gating
    // binding with review switched off, and the setting being per-project.
    //
    // Every one of them is about `DRAFT → ACTIVE`. §97 removed ACTIVE, so the rule has no edge, the
    // setting is dropped from `project_settings` and `overrideApprovalGate` is off the contract.
    // §82's finding — the plan rule is an EDGE and not a target — is recorded on `PlanLifecycle`
    // rather than deleted with these, because it cost a slice to discover and is moot rather than
    // wrong. §6g's CASE gate is untouched: `RunCreateEndpointTest` still covers it, including the
    // override and the not-an-override arm, so the vocabulary these tests pinned is still asserted
    // somewhere.

    // =====================================================================
    // The delete
    // =====================================================================

    @Nested
    @DisplayName("The delete — Lead-only, soft, and refused while a run names the plan")
    class Delete {

        @Test
        @DisplayName("a Test Lead deletes a plan nobody executed; the next read is 404")
        void leadDeletes() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            assertThat(delete(LEAD, ACME, ACME_BANKING, plan).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(deletedAtOf(plan))
                    .as("soft — plan_scope_item and the suites it names are untouched")
                    .isNotNull();
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(auditActions(ACME)).containsKey("plan.deleted");
        }

        @Test
        @DisplayName("a Test Engineer deletes a plan a LEAD authored — granted by V51")
        void engineerDeletesAnybodysPlan() throws Exception {
            // Asserted 403 until V51 (§207). This is the TIER half of that slice and the one with
            // no precedent anywhere else: Test Engineer never held plan:delete at all, so this
            // endpoint has never once answered a contributor with a 204 before now. The plan is
            // authored by LEAD deliberately — "no matter who first created it" is the requirement,
            // and a self-authored plan would prove only half of it.
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // The same caller moves this plan's status all day. That used to be what made the
            // refusal a statement about plan:delete rather than about the project; it now rules out
            // the mirror reading, that the 204 came from some blanket project-level allowance.
            assertThat(setStatus(ENGINEER, ACME, ACME_BANKING, plan, "IN_REVIEW")
                    .getResponse().getStatus()).isEqualTo(200);

            assertThat(delete(ENGINEER, ACME, ACME_BANKING, plan).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(deletedAtOf(plan)).isNotNull();
        }

        @Test
        @DisplayName("a plan with a live run is refused — 409 naming the count, and it stays readable")
        void aPlanWithRunsIsRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            run(ACME, ACME_BANKING, plan, "TR-1", 1, false);
            run(ACME, ACME_BANKING, plan, "TR-2", 2, false);

            MvcResult refused = delete(LEAD, ACME, ACME_BANKING, plan);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused))
                    .contains("2 test runs have", "Delete those runs first");
            assertThat(deletedAtOf(plan)).isNull();
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan)
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a run that was itself deleted does not block it — the refusal protects runs somebody can open")
        void aDeletedRunDoesNotBlock() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            run(ACME, ACME_BANKING, plan, "TR-1", 1, true);

            assertThat(delete(LEAD, ACME, ACME_BANKING, plan).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a second delete is 404, not a second 204")
        void theSecondDeleteIs404() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            delete(LEAD, ACME, ACME_BANKING, plan);

            assertThat(delete(LEAD, ACME, ACME_BANKING, plan).getResponse().getStatus())
                    .as("reporting success for a delete this request did not perform is the same "
                            + "untruth as a 201 on a replayed create")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a sibling project's plan is 404 — the path's second predicate, not the company's")
        void aSiblingProjectsPlanIs404() throws Exception {
            UUID theirs = plan(LEAD, ACME_MOBILE, "Mobile 1.0");

            assertThat(delete(LEAD, ACME, ACME_BANKING, theirs).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(deletedAtOf(theirs)).isNull();
        }
    }

    // =====================================================================
    // Archiving (V65, audit D2)
    // =====================================================================

    /**
     * {@code PUT /plans/{planId}/archive} — <strong>the way out for the plan the delete above is
     * right to refuse.</strong>
     *
     * <p>This lives here rather than in a class of its own for {@code CaseBulkEndpointTest}'s
     * reason (§227 phase 2): the fixture it needs is exactly the one already seeded above — a
     * lead, an engineer and a viewer on one project, a sibling project, and a run that makes a plan
     * undeletable — and a second class would be a copy of that seeding under an instruction to keep
     * it in step. It also puts the delete's 409 and the act that replaces it in one file.
     *
     * <p>What these catch, none of it visible to the compiler:
     *
     * <ol>
     *   <li><strong>An archive that changes the status.</strong> The tempting implementation is a
     *       fourth {@code PlanStatus}, and the cost is that archiving an approved plan silently
     *       unsays the approval.</li>
     *   <li><strong>An archive that is a lock.</strong> {@code CLOSED} under another name. An
     *       archived plan must still be editable, approvable and runnable.</li>
     *   <li><strong>A list that hides the plan from the endpoint that has to show it.</strong> The
     *       detail read, and {@code ?archived=true}, must both still serve it — and the un-archive
     *       is unreachable if the lookup excludes archived rows.</li>
     *   <li><strong>An idempotent set that writes anyway</strong>, re-stamping provenance on a
     *       second click.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Archiving — a visibility fact, and not a fourth status")
    class Archiving {

        @Test
        @DisplayName("an archived plan leaves the list, keeps its status, and comes back on request")
        void archivingRemovesItFromTheWorkingList() throws Exception {
            UUID plan = approvedPlan();

            MvcResult archived = setArchived(LEAD, ACME, ACME_BANKING, plan, true);

            assertThat(archived.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(archived).get("archived").asBoolean()).isTrue();
            assertThat(body(archived).get("status").asText())
                    .as("archiving says nothing about approval, and must not unsay one")
                    .isEqualTo("APPROVED");
            assertThat(statusOf(plan)).isEqualTo("APPROVED");

            assertThat(planKeysIn(LEAD, ACME_BANKING, false))
                    .as("gone from the working list, which is the whole of what archiving does")
                    .doesNotContain(keyOf(plan));
            assertThat(planKeysIn(LEAD, ACME_BANKING, true))
                    .as("and present when the archive is asked for — a widening, not a filter")
                    .contains(keyOf(plan));
        }

        @Test
        @DisplayName("the DETAIL still serves it, and says it is archived")
        void theDetailStillServesIt() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            setArchived(LEAD, ACME, ACME_BANKING, plan, true);

            MvcResult read = get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan);

            assertThat(read.getResponse().getStatus())
                    .as("a bookmark and a link from a run both land here")
                    .isEqualTo(200);
            assertThat(body(read).get("archived").asBoolean())
                    .as("a plan that looks live on the one screen showing it in full is how "
                            + "somebody edits a plan they believe is current")
                    .isTrue();
        }

        @Test
        @DisplayName("un-archiving puts it back — reachable, which a filtered lookup would not be")
        void unarchivingPutsItBack() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            setArchived(LEAD, ACME, ACME_BANKING, plan, true);

            MvcResult restored = setArchived(LEAD, ACME, ACME_BANKING, plan, false);

            assertThat(restored.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(restored).get("archived").asBoolean()).isFalse();
            assertThat(planKeysIn(LEAD, ACME_BANKING, false)).contains(keyOf(plan));
        }

        @Test
        @DisplayName("AN ARCHIVED PLAN IS NOT FROZEN — it still edits and still moves")
        void archivingIsNotALock() throws Exception {
            // The point of not making this a status. A flag that also froze the plan would be
            // CLOSED under another name, and a team that archives a plan and then needs one more
            // pass against it should get the pass rather than a refusal citing a checkbox.
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            setArchived(LEAD, ACME, ACME_BANKING, plan, true);

            assertThat(setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW")
                    .getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(statusOf(plan)).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("archiving twice writes nothing the second time")
        void theSecondArchiveIsANoOp() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            setArchived(LEAD, ACME, ACME_BANKING, plan, true);
            String stamped = archivedAtOf(plan);
            String updated = updatedAtOf(plan);
            clearAudit(ACME);

            assertThat(setArchived(LEAD, ACME, ACME_BANKING, plan, true)
                    .getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(archivedAtOf(plan))
                    .as("a repeated click must not rewrite provenance nobody meant to change")
                    .isEqualTo(stamped);
            assertThat(updatedAtOf(plan)).isEqualTo(updated);
            assertThat(auditActions(ACME))
                    .as("nor bury the act that actually happened under a row saying it happened again")
                    .isEmpty();
        }

        @Test
        @DisplayName("a Viewer is refused, and the refusal names no key")
        void viewerCannotArchive() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            clearAudit(ACME);

            MvcResult refused = setArchived(VIEWER, ACME, ACME_BANKING, plan, true);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).doesNotContain("plan:edit");
            assertThat(archivedAtOf(plan)).isNull();
            assertThat(auditActions(ACME)).isEmpty();
        }

        @Test
        @DisplayName("a Test Engineer CAN — it is plan:edit, not plan:delete")
        void engineerCanArchive() throws Exception {
            // Charging the delete key would put a reversible tidying act behind the product's most
            // destructive plan permission. This is the persona that tells the two apart.
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            assertThat(setArchived(ENGINEER, ACME, ACME_BANKING, plan, true)
                    .getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(archivedAtOf(plan)).isNotNull();
            assertThat(auditActions(ACME)).containsKey("plan.archived");
        }

        @Test
        @DisplayName("archiving does NOT make the refused delete succeed")
        void archivingIsNotAWayToDelete() throws Exception {
            // The relationship between the two acts, stated. Archiving is what to do INSTEAD of
            // deleting a plan runs were executed against — not a way to get that delete through.
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            run(ACME, ACME_BANKING, plan, "TR-A65", 6501, false);
            setArchived(LEAD, ACME, ACME_BANKING, plan, true);

            MvcResult refused = delete(LEAD, ACME, ACME_BANKING, plan);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused)).contains("test run");
            assertThat(deletedAtOf(plan)).isNull();
        }

        @Test
        @DisplayName("an absent `archived` is a 400 on the field, never a default")
        void theFlagIsRequired() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            MvcResult refused = mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/projects/" + ACME_BANKING + "/plans/" + plan + "/archive")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("archived");
            assertThat(archivedAtOf(plan)).isNull();
        }

        @Test
        @DisplayName("a sibling project's plan is 404 — the path's second predicate")
        void aSiblingProjectsPlanIs404() throws Exception {
            UUID theirs = plan(LEAD, ACME_MOBILE, "Mobile 1.0");

            assertThat(setArchived(LEAD, ACME, ACME_BANKING, theirs, true)
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(archivedAtOf(theirs)).isNull();
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private MvcResult setArchived(
            UUID userId, UUID companyId, UUID projectId, UUID planId, boolean archived)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("archived", archived);

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/plans/" + planId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    /** The keys the plan list serves, with or without the archive. */
    private List<String> planKeysIn(UUID userId, UUID projectId, boolean includeArchived)
            throws Exception {

        MvcResult listed = get(userId, ACME,
                "/projects/" + projectId + "/plans" + (includeArchived ? "?archived=true" : ""));
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);

        List<String> keys = new java.util.ArrayList<>();
        body(listed).forEach(plan -> keys.add(plan.get("key").asText()));
        return keys;
    }

    private String keyOf(UUID planId) throws SQLException {
        return planColumn(planId, "key");
    }

    private String archivedAtOf(UUID planId) throws SQLException {
        return planColumn(planId, "CAST(archived_at AS text)");
    }

    /** A plan created through the real endpoint, so its shape is never a fixture's opinion. */
    private UUID plan(UUID userId, UUID projectId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("suiteIds", List.of());
        payload.put("caseIds", List.of());

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    /** DRAFT → IN_REVIEW → APPROVED, through the endpoint, as a lead. */
    private UUID approvedPlan() throws Exception {
        UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
        setStatus(LEAD, ACME, ACME_BANKING, plan, "IN_REVIEW");
        setStatus(LEAD, ACME, ACME_BANKING, plan, "APPROVED");
        assertThat(statusOf(plan)).isEqualTo("APPROVED");
        clearAudit(ACME);
        return plan;
    }

    private MvcResult setStatus(
            UUID userId, UUID companyId, UUID projectId, UUID planId, String status)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/plans/" + planId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult delete(UUID userId, UUID companyId, UUID projectId, UUID planId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/plans/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1" + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String detailOf(MvcResult result) throws Exception {
        return body(result).get("detail").asText();
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

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("plan-lifecycle:" + marker).getBytes());
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

    /** The default configuration every run needs comes from here. */
    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
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
     * A run against the plan, written directly.
     *
     * <p>Through SQL rather than {@code POST /runs}, deliberately: what the delete refuses on is a
     * <em>row</em> in {@code test_run} with this {@code plan_id}, and going through the create
     * endpoint would make the fixture depend on that endpoint's own gate and payload rules for a
     * fact this class does not test.
     */
    private void run(UUID companyId, UUID projectId, UUID planId, String key, long keySeq,
            boolean deleted) throws SQLException {

        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("""
                     INSERT INTO test_run
                         (id, company_id, project_id, plan_id, configuration_id, key, key_seq,
                          name, status, source, deleted_at)
                     VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                             (SELECT id FROM configuration WHERE project_id = CAST(? AS uuid)
                              ORDER BY is_default DESC LIMIT 1),
                             ?, ?, ?, 'NOT_STARTED', 'MANUAL',
                             CASE WHEN ? THEN now() ELSE NULL END)
                     """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, planId.toString());
            statement.setString(4, projectId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Run of " + key);
            statement.setBoolean(8, deleted);
            statement.executeUpdate();
        }
    }

    /** Everything a test can write, in foreign-key order, through each company's own context. */
    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM defect_link");
                execute(connection, "DELETE FROM step_result");
                execute(connection, "DELETE FROM test_execution");
                execute(connection, "DELETE FROM test_run");
                execute(connection, "DELETE FROM plan_scope_item");
                execute(connection, "DELETE FROM test_plan_version");
                execute(connection, "DELETE FROM test_plan");
                // §82. The settings row is written by the Gating class's own helper and by nothing
                // else here, so leaving it would let ONE test turn the gate on for every test that
                // ran after it — which is exactly what happened on the first run: five tests went
                // red, three of them written before this slice existed and none of them about
                // gating. A per-project policy row is mutable state and belongs on this list.
                execute(connection, "DELETE FROM project_settings");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void clearAudit(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back through the app role, past the API
    // =====================================================================

    private String statusOf(UUID planId) throws SQLException {
        return planColumn(planId, "status");
    }

    private String deletedAtOf(UUID planId) throws SQLException {
        return planColumn(planId, "CAST(deleted_at AS text)");
    }

    private String updatedAtOf(UUID planId) throws SQLException {
        return planColumn(planId, "CAST(updated_at AS text)");
    }

    private String planColumn(UUID planId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private Map<String, String> auditActions(UUID companyId) throws SQLException {
        Map<String, String> events = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action, CAST(changes AS text) FROM audit_event ORDER BY occurred_at");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.put(rows.getString(1), rows.getString(2));
            }
        }
        return events;
    }
}
