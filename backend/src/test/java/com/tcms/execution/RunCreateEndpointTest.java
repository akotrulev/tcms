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
import com.tcms.project.EditAfterApproval;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ReviewImpactSource;
import com.tcms.project.ReviewSettings;
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
 * {@code POST /projects/{projectId}/runs} over HTTP — <strong>and the executions it materializes</strong>.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The pin resolved late.</strong> The whole module turns on an execution naming the
 *       version it ran. So a case here is edited to v2 <em>before</em> the run is created, and the
 *       run's execution must pin <strong>v2</strong> — while an execution created before the edit
 *       must still pin v1. One assertion each, and a "read the current version at render time"
 *       implementation passes neither.</li>
 *   <li><strong>A run created with no checklist.</strong> Zero executions renders as 0 of 0, 100%,
 *       complete — a run that looks finished and never started.</li>
 *   <li><strong>{@code release_sort_key} left null or disagreeing with the release.</strong> §6q's
 *       silent failure: "1.10" sorts before "1.9" and every latest-release query is quietly wrong,
 *       with no error and a plausible number on the screen.</li>
 *   <li><strong>A retry materializing a second checklist.</strong> The same tester's results split
 *       across two runs, each reporting a pass rate over a denominator nobody intended.</li>
 *   <li><strong>{@code run:create} narrowed to humans.</strong> Automation Service holds it (roles
 *       spec §A4) — a CI job opens the run it is about to post into — and a gate copied from
 *       {@code plan:create} would break exactly that.</li>
 * </ol>
 *
 * <p>Ids are unique to this class — {@code eb…}.
 */
@AutoConfigureMockMvc
@DisplayName("POST /runs — creating a run, and materializing the executions it will be worked from")
class RunCreateEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("eb000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("eb000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("eb001000-0000-4000-8000-000000000001");
    private static final UUID ACME_MOBILE = UUID.fromString("eb001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("eb002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("eb003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("eb003000-0000-4000-8000-000000000002");
    /** Viewer — the only role that does NOT hold {@code run:create}. */
    private static final UUID VIEWER = UUID.fromString("eb003000-0000-4000-8000-000000000003");
    private static final UUID NO_GRANT = UUID.fromString("eb003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("eb003000-0000-4000-8000-000000000005");
    /** Automation Service — holds {@code run:create} and {@code result:submit} and nothing else. */
    private static final UUID ROBOT = UUID.fromString("eb003000-0000-4000-8000-000000000006");

    private static final UUID PAYMENTS = UUID.fromString("eb004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("eb004000-0000-4000-8000-000000000002");
    private static final UUID SPARE = UUID.fromString("eb004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("eb004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("eb004000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("eb00ffff-0000-4000-8000-00000000ffff");

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

    @Autowired
    private PlanRunGateImpact runGateImpact;

    /** In REFUNDS, beneath PAYMENTS. Edited to v2 by one test, before its run is created. */
    private UUID caseA;
    /** In SPARE, outside the PAYMENTS subtree. */
    private UUID caseB;
    private UUID mobileCase;

    /** Scope: the PAYMENTS folder (covering caseA) and caseB directly — two distinct cases. */
    private UUID plan;
    /** In ACME_MOBILE. Named from ACME_BANKING's path it must be a field error, not a 404. */
    private UUID mobilePlan;
    /** A plan whose scope is empty — the run that would have nothing to execute. */
    private UUID emptyPlan;

    private UUID defaultConfiguration;
    private UUID mobileConfiguration;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "rc-acme", "Acme", now);
            company(UMBRA, "rc-umbra", "Umbra", now);

            activeUser(LEAD, "lead@rc-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@rc-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@rc-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@rc-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@rc-umbra.test", "O. Outsider", now);
            activeUser(ROBOT, "ci@rc-acme.test", "CI", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "RCAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "RCAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ROBOT, "CI", now);

            grant("rc-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("rc-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("rc-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("rc-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
            grant("rc-robot", ACME, Role.AUTOMATION_SERVICE_ID, ROBOT, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "RCUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("rc-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", REFUNDS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", SPARE);

        // What PAYMENTS' tag rule claims (§98) — caseA only. caseB stays in SPARE and untagged,
        // so the two scope arms answer different sets and a run scoped on the suite is not the
        // same list as a run scoped on the plan's cases.
        tagCase(caseA, "refunds");
        mobileCase = createCase(LEAD, ACME, ACME_MOBILE, "Mobile login", MOBILE_SUITE);

        // The plans are authored through the REAL POST /plans, so this class's fixture is checked
        // against the other half of the same slice rather than against SQL nobody else runs.
        plan = createPlan(ACME_BANKING, "Release 4.3",
                List.of(PAYMENTS), List.of(caseB));
        mobilePlan = createPlan(ACME_MOBILE, "Mobile 1.0", List.of(MOBILE_SUITE), List.of());
        emptyPlan = createPlan(ACME_BANKING, "Not scoped yet", List.of(), List.of());

        defaultConfiguration = configurationOf(ACME_BANKING);
        mobileConfiguration = configurationOf(ACME_MOBILE);

        resetRunCounter();

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
    @DisplayName("The gate — run:create, held by every role except Viewer")
    class Gate {

        @Test
        @DisplayName("a Test Lead and a Test Engineer both create runs")
        void humansCreate() throws Exception {
            assertThat(post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), null)
                    .getResponse().getStatus()).isEqualTo(201);
            assertThat(post(ENGINEER, ACME, ACME_BANKING, fromPlan("Second pass"), null)
                    .getResponse().getStatus()).isEqualTo(201);
        }

        /**
         * <strong>This test was written expecting 404, to pin a defect. {@code V16} repaired it and
         * the expectation is now 201 — which is the whole point of having written it that way.</strong>
         *
         * <p>Roles spec §A6 gives Automation Service five capabilities — {@code case:read},
         * {@code run:create}, {@code result:submit}, {@code evidence:attach} and a quarantine-only
         * {@code case:create} — and {@code V3} seeded precisely those and <em>no</em>
         * {@code project:read}.
         *
         * <p>{@code ProjectService.writeGranted} runs the read gate first, and
         * {@code ProjectAccess.canView} <em>is</em> "holds {@code project:read}". So every
         * project-scoped path answered this role <strong>404 before its permission was asked</strong>
         * — {@code run:create} here, and equally {@code result:submit}, {@code evidence:attach} and
         * {@code case:read}. The role whose entire justification is "a leaked CI token can post
         * results and break nothing" could not post a result. Not degraded: inert.
         *
         * <p>{@code V16} grants the missing {@code project:read}, which is the visibility
         * precondition rather than a sixth capability — that migration carries the argument for why
         * it does not widen what a stolen token can do, and why the alternative repair (weakening
         * what {@code canView} asks) was refused.
         *
         * <p>The general form is now a computed check:
         * {@code SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition} derives the rule
         * from the catalogue, so the next role added is covered without anyone recalling it. This
         * gap and §43's {@code company:read} gap were the same shape — a permission the spec grants,
         * made unreachable by a gate ordering nobody checked against the catalogue — and two in one
         * session is what turned a reading into a script.
         */
        @Test
        @DisplayName("an Automation Service creates a run — the §A6 role reaches its own permission")
        void theRobotCanReachRunCreate() throws Exception {
            assertThat(post(ROBOT, ACME, ACME_BANKING, fromPlan("Nightly"), null)
                    .getResponse().getStatus())
                    .as("If this is 404 again, project:read has been dropped from Automation "
                            + "Service — read this test's javadoc and V16 before 'fixing' the gate.")
                    .isEqualTo(201);
            assertThat(runCount(ACME)).isOne();
        }

        @Test
        @DisplayName("a Viewer READS the runs and is refused the create — 403, nothing written")
        void viewerIsRefused() throws Exception {
            assertThat(get(VIEWER, ACME, "/projects/" + ACME_BANKING + "/runs")
                    .getResponse().getStatus()).isEqualTo(200);

            MvcResult refused = post(VIEWER, ACME, ACME_BANKING, fromPlan("Not mine"), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).doesNotContain("run:create");
            assertThat(runCount(ACME)).isZero();
            assertThat(executionCount(ACME)).isZero();
        }

        @Test
        @DisplayName("no grant is 404; the other client is 404; an absent project is 404")
        void unreachableTargetsAre404() throws Exception {
            assertThat(post(NO_GRANT, ACME, ACME_BANKING, fromPlan("Probe"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(post(OUTSIDER, UMBRA, ACME_BANKING, fromPlan("Theirs"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(post(LEAD, ACME, ABSENT, fromPlan("Nowhere"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("the permission is checked BEFORE the payload — a Viewer's bad body is 403")
        void thePermissionIsCheckedFirst() throws Exception {
            Map<String, Object> payload = fromPlan("");
            payload.put("planId", ABSENT.toString());
            assertThat(post(VIEWER, ACME, ACME_BANKING, payload, null).getResponse().getStatus())
                    .as("400 here would tell a refused caller whether a plan id names a live row")
                    .isEqualTo(403);
        }
    }

    // =====================================================================
    // Materialization — the point of the slice
    // =====================================================================

    @Nested
    @DisplayName("Materialization — one execution per case in scope, each pinned")
    class Materialization {

        @Test
        @DisplayName("a run from a plan gets one execution per case in the EXPANDED scope")
        void thePlansScopeBecomesTheChecklist() throws Exception {
            // The plan's scope is the PAYMENTS folder (whose subtree holds caseA) plus caseB
            // directly. A count over plan_scope_item would give 2 by coincidence, so the assertion
            // below names the case ids rather than the number.
            JsonNode run = body(post(LEAD, ACME, ACME_BANKING, fromPlan("Regression 4.3"), null));
            UUID runId = UUID.fromString(run.get("id").asText());

            assertThat(caseIdsOf(runId)).containsExactlyInAnyOrder(caseA, caseB);
            assertThat(run.get("counts").get("total").asInt()).isEqualTo(2);
            assertThat(run.get("counts").get("notRun").asInt())
                    .as("nothing has been recorded, so everything is still owed a verdict")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the executions are visible through GET /runs/{id} immediately")
        void theChecklistIsReadable() throws Exception {
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), null))
                            .get("id").asText());

            JsonNode detail = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId));
            assertThat(detail.get("executions")).hasSize(2);
            JsonNode first = detail.get("executions").get(0);
            assertThat(first.get("status").isNull())
                    .as("no verdict, which is what makes the first recording a submit not an amend")
                    .isTrue();
            // Both null is the ordinary state of a run nobody has started, and it is the state that
            // made ExecutionPeople.resolve a 500 in §38 — so it is asserted rather than assumed.
            assertThat(first.get("executedBy").isNull()).isTrue();
            assertThat(first.get("executedAt").isNull()).isTrue();
        }

        @Test
        @DisplayName("THE assertion — the pin is the case's CURRENT version at creation time")
        void thePinIsResolvedAtCreation() throws Exception {
            // Edit caseA to v2 BEFORE creating the run. A run created now must record v2; an
            // implementation that pinned v1 (or that resolved the version at render time) fails
            // here, and an implementation that pinned the latest at RENDER time fails the next test.
            editCaseToV2(caseA);

            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, onCases("After the edit", List.of(caseA)),
                            null)).get("id").asText());

            JsonNode execution = firstExecution(runId);
            assertThat(execution.get("caseVersionNo").asInt()).isEqualTo(2);
            assertThat(execution.get("caseTitle").asText()).isEqualTo("Rewritten before the run");
        }

        @Test
        @DisplayName("and editing the case AFTER the run does not move it — the pin holds")
        void aLaterEditDoesNotMoveThePin() throws Exception {
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, onCases("Before the edit", List.of(caseA)),
                            null)).get("id").asText());

            editCaseToV2(caseA);

            JsonNode execution = firstExecution(runId);
            assertThat(execution.get("caseVersionNo").asInt())
                    .as("resolving the version at render time answers 2 here and passes the "
                            + "previous test — only both together pin the behaviour")
                    .isEqualTo(1);
            assertThat(execution.get("caseTitle").asText())
                    .isEqualTo("Refund the whole captured amount");
        }

        @Test
        @DisplayName("explicit caseIds win over the plan's scope, and are not unioned with it")
        void anExplicitSelectionNarrows() throws Exception {
            Map<String, Object> payload = fromPlan("Narrowed pass");
            payload.put("caseIds", List.of(caseB.toString()));

            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());

            assertThat(caseIdsOf(runId))
                    .as("unioning would silently add back the case somebody narrowed away")
                    .containsExactly(caseB);
            // The association is kept, so the narrowing reads as a short run rather than a lost plan.
            assertThat(planIdOf(runId)).isEqualTo(plan);
        }

        @Test
        @DisplayName("a deleted case leaves the plan's scope, so the checklist is shorter")
        void deletedCasesAreNotMaterialised() throws Exception {
            softDeleteCase(ACME, caseB);
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, fromPlan("After the delete"), null))
                            .get("id").asText());
            assertThat(caseIdsOf(runId)).containsExactly(caseA);
        }

        @Test
        @DisplayName("assigneeId lands on EVERY execution, and an absent one is the normal state")
        void theAssigneeIsApplied() throws Exception {
            Map<String, Object> assigned = fromPlan("Assigned");
            assigned.put("assigneeId", ENGINEER.toString());
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, assigned, null)).get("id").asText());

            JsonNode detail = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId));
            detail.get("executions").forEach(execution -> assertThat(
                    execution.get("assignee").get("id").asText()).isEqualTo(ENGINEER.toString()));

            UUID unassigned = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, fromPlan("Unassigned"), null))
                            .get("id").asText());
            assertThat(firstExecution(unassigned).get("assignee").isNull())
                    .as("a run the team picks up from is the ordinary case, not a missing value")
                    .isTrue();
        }
    }

    // =====================================================================
    // §99 — the scope's three arms
    // =====================================================================

    /**
     * §99. A run is scoped by cases, suites and tags, unioned — and every test here is written
     * against a fixture where the three arms answer <strong>different</strong> sets, because in one
     * where they agree an implementation that reads only the first arm passes all of them.
     *
     * <p>The load-bearing fixture fact is that <strong>PAYMENTS files nothing</strong>: it claims
     * {@code caseA} through a tag rule (§98's seeding, and §96's model). So the tempting suite arm —
     * {@code c.suite_id IN (:suiteIds)} — resolves it to the empty set, and a run scoped on it would
     * be refused as empty or, worse, created empty. {@link #paymentsFilesNothing()} asserts that
     * property directly rather than leaving it as a comment somebody later "tidies".
     */
    @Nested
    @DisplayName("Scope — cases, suites and tags, unioned and resolved once")
    class Scope {

        @Test
        @DisplayName("the fixture's non-vacuity: PAYMENTS claims a case it does not file")
        void paymentsFilesNothing() throws Exception {
            assertThat(filedIn(PAYMENTS))
                    .as("if anything were filed here, every assertion below would also pass "
                            + "against a suite arm that only read test_case.suite_id")
                    .isEmpty();
            assertThat(filedIn(REFUNDS)).containsExactly(caseA);
        }

        @Test
        @DisplayName("a suite arm resolves MEMBERSHIP — the tag-claimed case, not the filed column")
        void aSuiteArmResolvesMembership() throws Exception {
            UUID runId = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING,
                    onScope("Payments pass", List.of(), List.of(PAYMENTS), List.of()), null))
                    .get("id").asText());

            assertThat(caseIdsOf(runId)).containsExactly(caseA);
        }

        @Test
        @DisplayName("a tag arm resolves the cases carrying it")
        void aTagArmResolves() throws Exception {
            UUID runId = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING,
                    onScope("Everything tagged refunds", List.of(), List.of(), List.of("refunds")),
                    null)).get("id").asText());

            assertThat(caseIdsOf(runId)).containsExactly(caseA);
        }

        @Test
        @DisplayName("the three arms UNION, and a case reached twice is materialised once")
        void theArmsUnionAndDedupe() throws Exception {
            // caseA arrives through the tag AND through the named id; caseB only through SPARE. A
            // second execution for caseA would give the tester the same case twice on one checklist
            // and a denominator one larger than the set they chose.
            UUID runId = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING,
                    onScope("Everything", List.of(caseA), List.of(SPARE), List.of("refunds")),
                    null)).get("id").asText());

            assertThat(caseIdsOf(runId)).containsExactlyInAnyOrder(caseA, caseB);
            assertThat(executionCount(ACME)).isEqualTo(2);
        }

        @Test
        @DisplayName("an arm SUBSTITUTES for the plan's scope and is not unioned with it")
        void theArmsReplaceThePlansScope() throws Exception {
            // The plan expands to caseA + caseB. Scoping on the tag narrows the pass to caseA — and
            // unioning would silently hand back the case somebody just narrowed away, which is
            // `anExplicitSelectionNarrows` one arm over.
            Map<String, Object> payload = fromPlan("Narrowed to the tagged ones");
            payload.put("tags", List.of("refunds"));

            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());

            assertThat(caseIdsOf(runId)).containsExactly(caseA);
            assertThat(planIdOf(runId))
                    .as("the association is kept, so this reads as a short run and not a lost plan")
                    .isEqualTo(plan);
        }

        @Test
        @DisplayName("resolved ONCE: a case tagged after the run does not join it")
        void theScopeIsResolvedAtCreation() throws Exception {
            UUID runId = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING,
                    onScope("Tagged now", List.of(), List.of(), List.of("refunds")), null))
                    .get("id").asText());

            tagCase(caseB, "refunds");

            assertThat(caseIdsOf(runId))
                    .as("a run's executions are written at create; a plan's scope is references "
                            + "re-resolved on every read, and this is where the two differ")
                    .containsExactly(caseA);
        }

        @Test
        @DisplayName("an unknown tag beside a matching one is accepted, not a field error")
        void anUnknownTagIsNotAnError() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    onScope("Refunds and whatever", List.of(), List.of(),
                            List.of("refunds", "never-used-by-anybody")), null);

            assertThat(created.getResponse().getStatus())
                    .as("there is no tag vocabulary to check against, and a run is not wrong "
                            + "because one of its three tags is currently unused")
                    .isEqualTo(201);
            assertThat(caseIdsOf(UUID.fromString(body(created).get("id").asText())))
                    .containsExactly(caseA);
        }

        @Test
        @DisplayName("a tag matching NOTHING, alone, is refused on tags — the field they typed in")
        void aTagMatchingNothingIsRefusedOnItsOwnField() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onScope("Typo", List.of(), List.of(), List.of("refnuds")), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused))
                    .as("answering on caseIds would send somebody to a control they never touched")
                    .containsKey("tags")
                    .doesNotContainKey("caseIds");
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("two empty arms are both named — nothing here can know which one to change")
        void everyArmTheCallerUsedCarriesTheRefusal() throws Exception {
            softDeleteCase(ACME, caseB);

            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onScope("Nothing left", List.of(), List.of(SPARE), List.of("refnuds")), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKeys("suiteIds", "tags");
        }

        @Test
        @DisplayName("a deleted case is not reached by a tag it still carries")
        void aDeletedCaseIsNotReachedByItsTag() throws Exception {
            softDeleteCase(ACME, caseA);

            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onScope("Gone", List.of(), List.of(), List.of("refunds")), null);

            assertThat(refused.getResponse().getStatus())
                    .as("case_tag rows outlive a soft delete, so deleted_at has to be in the "
                            + "union query and not only in the arms it was written for")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a suite from a SIBLING project is a 400 on suiteIds, not a silent empty run")
        void aSiblingProjectsSuiteIsRefused() throws Exception {
            // LEAD holds both projects, so no access check refuses this and RLS does not either.
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onScope("Wrong suite", List.of(), List.of(MOBILE_SUITE), List.of()), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("suiteIds");
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("a suite id naming nothing is a 400 on suiteIds")
        void anAbsentSuiteIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onScope("Ghost", List.of(), List.of(ABSENT), List.of()), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("suiteIds");
        }

        @Test
        @DisplayName("a tag longer than a tag can be is a 400 — it could never match anything")
        void anOverLongTagIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onScope("Too long", List.of(), List.of(),
                            List.of("t".repeat(RunEdit.MAX_TAG + 1))), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("tags");
        }

        @Test
        @DisplayName("tags are trimmed, so a pasted \" refunds \" still matches")
        void tagsAreTrimmed() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    onScope("Pasted", List.of(), List.of(), List.of("  refunds  ")), null);

            assertThat(created.getResponse().getStatus())
                    .as("the tags that are validated and the tags that are queried come from one "
                            + "function; trimming in only one place resolves this to nothing")
                    .isEqualTo(201);
        }
    }

    // =====================================================================
    // The run's own columns
    // =====================================================================

    @Nested
    @DisplayName("The created run — key, status, source, release and its sort key")
    class TheRun {

        @Test
        @DisplayName("TR-1, NOT_STARTED, MANUAL, not yet begun, and Location points at it")
        void theShapeOfACreatedRun() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, fromPlan("Regression 4.3"), null);
            JsonNode run = body(created);

            assertThat(run.get("key").asText()).isEqualTo("TR-1");
            assertThat(run.get("status").asText())
                    .as("the run starts when somebody records the first result, not when it is "
                            + "named in a meeting")
                    .isEqualTo("NOT_STARTED");
            assertThat(run.get("source").asText()).isEqualTo("MANUAL");
            // 005 S1 — stored since V1 and served since. The name comes from the caller's COMPANY
            // MEMBERSHIP (§6u), which is what makes "Opened by" the same words every other screen
            // in this company uses for the same person.
            assertThat(run.get("createdBy").get("id").asText()).isEqualTo(LEAD.toString());
            assertThat(run.get("createdBy").get("displayName").asText()).isNotBlank();
            assertThat(run.get("startedAt").isNull()).isTrue();
            assertThat(run.get("completedAt").isNull()).isTrue();
            assertThat(run.get("planKey").asText()).isEqualTo("TP-1");
            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/runs/"
                            + run.get("id").asText());
        }

        @Test
        @DisplayName("the release version is stored and release_sort_key is computed from it")
        void theSortKeyIsComputed() throws Exception {
            Map<String, Object> payload = fromPlan("Regression 4.3.0");
            payload.put("releaseVersion", "4.3.0");
            payload.put("buildNumber", "4471");

            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());

            // §6q's silent failure: text collation puts "1.10" before "1.9". Left null, the column
            // test_run_release_idx leads on is empty and every latest-release query is wrong with
            // no error anywhere.
            assertThat(sortKeyOf(runId)).isEqualTo("00004.00003.00000.1");
        }

        @Test
        @DisplayName("the sort key ORDERS correctly across the 1.9 / 1.10 boundary")
        void theSortKeyOrders() throws Exception {
            Map<String, Object> nine = fromPlan("1.9");
            nine.put("releaseVersion", "1.9");
            Map<String, Object> ten = fromPlan("1.10");
            ten.put("releaseVersion", "1.10");

            UUID nineId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, nine, null)).get("id").asText());
            UUID tenId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, ten, null)).get("id").asText());

            assertThat(sortKeyOf(nineId)).isLessThan(sortKeyOf(tenId));
            assertThat("1.9").isGreaterThan("1.10");
        }

        @Test
        @DisplayName("a non-semver release falls back to itself rather than to null")
        void nonSemverFallsBack() throws Exception {
            Map<String, Object> payload = fromPlan("Nightly");
            payload.put("releaseVersion", "nightly-2026-08-01");
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());
            assertThat(sortKeyOf(runId)).isEqualTo("nightly-2026-08-01");
        }

        @Test
        @DisplayName("no release means no sort key — an ad-hoc run is not version zero")
        void noReleaseMeansNoSortKey() throws Exception {
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, fromPlan("Ad hoc"), null))
                            .get("id").asText());
            assertThat(sortKeyOf(runId)).isNull();
        }

        @Test
        @DisplayName("the release is denormalized onto every execution, sort key and all")
        void theReleaseReachesTheExecutions() throws Exception {
            Map<String, Object> payload = fromPlan("Regression 4.3.0");
            payload.put("releaseVersion", "4.3.0");
            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());

            assertThat(executionReleases(runId))
                    .as("test_execution_release_idx leads on the sort key; nearly every report "
                            + "filters executions by release (§6q)")
                    .containsOnly("4.3.0|00004.00003.00000.1");
        }

        @Test
        @DisplayName("the run's configuration defaults to the project's default")
        void theConfigurationDefaults() throws Exception {
            JsonNode run = body(post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), null));
            assertThat(UUID.fromString(run.get("configuration").get("id").asText()))
                    .isEqualTo(defaultConfiguration);
            assertThat(run.get("configuration").get("isDefault").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an audit row names the run, the checklist size and the plan")
        void anAuditRowIsWritten() throws Exception {
            post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), null);

            Map<String, String> events = auditActions(ACME);
            assertThat(events).containsOnlyKeys("run.created");
            assertThat(events.get("run.created"))
                    .contains("\"key\": \"TR-1\"")
                    .contains("\"executions\": 2")
                    .contains(plan.toString());
        }

        @Test
        @DisplayName("keys are per company — both clients hold TR-1 and see only their own run")
        void isolation() throws Exception {
            JsonNode acme = body(post(LEAD, ACME, ACME_BANKING, fromPlan("Acme's"), null));
            JsonNode umbra = body(post(OUTSIDER, UMBRA, UMBRA_BANKING,
                    onCases("Umbra's", List.of(umbraCase)), null));

            assertThat(acme.get("key").asText()).isEqualTo("TR-1");
            assertThat(umbra.get("key").asText()).isEqualTo("TR-1");
            assertThat(get(OUTSIDER, UMBRA,
                    "/projects/" + ACME_BANKING + "/runs/" + acme.get("id").asText())
                    .getResponse().getStatus()).isEqualTo(404);
        }
    }

    // =====================================================================
    // Git traceability — §177, and the two columns V1 created for it
    // =====================================================================

    /**
     * {@code commit_sha} and {@code branch}: written, served, and refused when the sha is not one.
     *
     * <p>Every test here would have passed vacuously before §177 in the way §170 describes — the
     * request bound the fields, nothing read them, and the response never carried them. So the pair
     * that matters is {@link #bothAreStoredAndServed} and {@link #theColumnsAreActuallyWritten}: one
     * asserts the value comes back out of the endpoint, the other that it is in the row rather than
     * echoed from the request.
     */
    @Nested
    @DisplayName("Git traceability — the commit and branch a run tested")
    class GitTraceability {

        @Test
        @DisplayName("both are stored and served back on the created run")
        void bothAreStoredAndServed() throws Exception {
            Map<String, Object> payload = fromPlan("Regression 4.3.0");
            payload.put("commitSha", "4f9c2b1e8a7d");
            payload.put("branch", "release/4.3");

            JsonNode run = body(post(LEAD, ACME, ACME_BANKING, payload, null));

            assertThat(run.get("commitSha").asText()).isEqualTo("4f9c2b1e8a7d");
            assertThat(run.get("branch").asText()).isEqualTo("release/4.3");
        }

        @Test
        @DisplayName("…in the row, not echoed from the request")
        void theColumnsAreActuallyWritten() throws Exception {
            Map<String, Object> payload = fromPlan("Regression 4.3.0");
            payload.put("commitSha", "  4f9c2b1e8a7d  ");
            payload.put("branch", "  release/4.3  ");

            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());

            // Trimmed, and stored as given otherwise — NOT lower-cased. RunEdit says why: the other
            // git-bearing column in the product stores what it was handed too, and one value with
            // two normalizations is the worse defect.
            assertThat(column(runId, "commit_sha")).isEqualTo("4f9c2b1e8a7d");
            assertThat(column(runId, "branch")).isEqualTo("release/4.3");
        }

        @Test
        @DisplayName("case is preserved — the columns are not lower-cased on the way in")
        void caseIsPreserved() throws Exception {
            Map<String, Object> payload = fromPlan("Regression");
            payload.put("commitSha", "4F9C2B1");

            UUID runId = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, payload, null)).get("id").asText());

            assertThat(column(runId, "commit_sha")).isEqualTo("4F9C2B1");
        }

        @Test
        @DisplayName("a run that names neither serves null for both, not empty strings")
        void absentMeansNull() throws Exception {
            JsonNode run = body(post(LEAD, ACME, ACME_BANKING, fromPlan("Ad hoc"), null));

            // The contract marks both required-and-nullable, so a client's own type says the key is
            // present. An empty string would render as an unclickable blank coordinate.
            assertThat(run.has("commitSha")).isTrue();
            assertThat(run.get("commitSha").isNull()).isTrue();
            assertThat(run.get("branch").isNull()).isTrue();
        }

        @Test
        @DisplayName("a BRANCH NAME in commitSha is refused on commitSha, and nothing is written")
        void aBranchNameIsNotACommitId() throws Exception {
            Map<String, Object> payload = fromPlan("Regression");
            payload.put("commitSha", "release/4.3");

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);

            // THE failure the check exists for, and the one ExternalLinkService's own header names:
            // an unchecked value is accepted, stored, and discovered to be a branch name by its
            // reader. Here the field it belonged in is the next one along, so the message says so.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused))
                    .containsKey("commitSha")
                    .doesNotContainKey("branch");
            assertThat(errorsOf(refused).get("commitSha").getFirst())
                    .contains("record it as the branch instead");
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("six hex characters is below the bound; seven is not")
        void theBoundIsLooseAtTheBottomButNotOpen() throws Exception {
            Map<String, Object> tooShort = fromPlan("Six");
            tooShort.put("commitSha", "4f9c2b");
            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, tooShort, null)))
                    .containsKey("commitSha");

            Map<String, Object> abbreviated = fromPlan("Seven");
            abbreviated.put("commitSha", "4f9c2b1");
            assertThat(post(LEAD, ACME, ACME_BANKING, abbreviated, null)
                    .getResponse().getStatus())
                    .as("teams paste abbreviated shas out of terminals; refusing a seven-character "
                            + "one would be this product deciding how somebody's git is configured")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a branch is length-bounded and otherwise anything the team's git allows")
        void theBranchIsBoundedAndNotShaped() throws Exception {
            Map<String, Object> generated = fromPlan("Dependabot");
            generated.put("branch", "dependabot/gradle/org.springframework.boot-3.5.6");
            assertThat(post(LEAD, ACME, ACME_BANKING, generated, null).getResponse().getStatus())
                    .as("a ref name is a path a tool generates, not a short identifier a human types")
                    .isEqualTo(201);

            Map<String, Object> tooLong = fromPlan("Too long");
            tooLong.put("branch", "b".repeat(RunEdit.MAX_BRANCH + 1));
            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, tooLong, null)))
                    .containsKey("branch");
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — and the run that would have had nothing in it")
    class Validation {

        @Test
        @DisplayName("a run with an EMPTY scope is refused on planId, and nothing is written")
        void anEmptyRunIsRefused() throws Exception {
            Map<String, Object> payload = fromPlan("Nothing to do");
            payload.put("planId", emptyPlan.toString());

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);

            // An empty run renders as 0 of 0, 100%, complete: it looks finished and never started,
            // and the creator would have to notice the ABSENCE of rows to know.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("planId");
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("no plan and no cases is refused on caseIds — the field the caller can change")
        void noScopeAtAllIsRefused() throws Exception {
            Map<String, Object> payload = fromPlan("Nothing at all");
            payload.put("planId", null);

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("caseIds");
        }

        @Test
        @DisplayName("a blank name is a 400, and an over-long one too")
        void nameIsValidated() throws Exception {
            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, fromPlan("   "), null)))
                    .containsKey("name");
            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING,
                    fromPlan("n".repeat(RunEdit.MAX_NAME + 1)), null))).containsKey("name");
        }

        @Test
        @DisplayName("a plan from a SIBLING project is a 400 on planId, not a 404")
        void aSiblingProjectsPlanIsRefused() throws Exception {
            // LEAD holds both projects, so no access check refuses this. The project predicate in
            // TestPlanRepository is the only thing that does — and without it the run would be
            // created against another product's plan, in this project.
            Map<String, Object> payload = fromPlan("Wrong plan");
            payload.put("planId", mobilePlan.toString());

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("planId");
        }

        @Test
        @DisplayName("a case from a SIBLING project is a 400 on caseIds")
        void aSiblingProjectsCaseIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    onCases("Wrong case", List.of(mobileCase)), null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("caseIds");
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("a configuration from a SIBLING project is a 400 on configurationId")
        void aSiblingProjectsConfigurationIsRefused() throws Exception {
            Map<String, Object> payload = fromPlan("Wrong environment");
            payload.put("configurationId", mobileConfiguration.toString());

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("configurationId");
        }

        @Test
        @DisplayName("an assignee who is not a member of this company is a 400")
        void aForeignAssigneeIsRefused() throws Exception {
            Map<String, Object> payload = fromPlan("Assigned to nobody here");
            payload.put("assigneeId", OUTSIDER.toString());

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("assigneeId");
        }

        @Test
        @DisplayName("a REFUSED create burns no key — the next run is still TR-1")
        void aRefusalBurnsNoKey() throws Exception {
            post(LEAD, ACME, ACME_BANKING, fromPlan(""), null);
            post(LEAD, ACME, ACME_BANKING, fromPlan(""), null);
            assertThat(body(post(LEAD, ACME, ACME_BANKING, fromPlan("Finally"), null))
                    .get("key").asText()).isEqualTo("TR-1");
        }
    }

    // =====================================================================
    // Idempotency
    // =====================================================================

    @Nested
    @DisplayName("Idempotency — a retry must not make a SECOND checklist")
    class Idempotency {

        @Test
        @DisplayName("the same key twice: 201 then 200, one run, and still N executions")
        void aRetryReplays() throws Exception {
            MvcResult first = post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), "intent-1");
            MvcResult second = post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), "intent-1");

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(second).get("id").asText()).isEqualTo(body(first).get("id").asText());
            assertThat(second.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
            assertThat(runCount(ACME)).isEqualTo(1);
            // The assertion this whole mechanism exists for: two checklists would split one
            // tester's results across two runs, each reporting over a denominator nobody intended.
            assertThat(executionCount(ACME)).isEqualTo(2);
        }

        @Test
        @DisplayName("a retry with a DRIFTED body answers the original run, not a 400")
        void aDriftedRetryStillReplays() throws Exception {
            MvcResult first = post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), "intent-2");
            MvcResult retry = post(LEAD, ACME, ACME_BANKING, fromPlan(""), "intent-2");

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("name").asText()).isEqualTo("Regression");
            assertThat(body(retry).get("id").asText()).isEqualTo(body(first).get("id").asText());
        }

        @Test
        @DisplayName("distinct keys and no key at all each make their own run")
        void distinctIntentsAreDistinct() throws Exception {
            post(LEAD, ACME, ACME_BANKING, fromPlan("A"), "intent-a");
            post(LEAD, ACME, ACME_BANKING, fromPlan("B"), "intent-b");
            post(LEAD, ACME, ACME_BANKING, fromPlan("C"), null);
            assertThat(runCount(ACME)).isEqualTo(3);
            assertThat(executionCount(ACME)).isEqualTo(6);
        }

        @Test
        @DisplayName("an over-long key is a 400 on the header, and no run is made")
        void anOverLongKeyIsRefused() throws Exception {
            MvcResult refused =
                    post(LEAD, ACME, ACME_BANKING, fromPlan("Regression"), "k".repeat(201));
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("Idempotency-Key");
            assertThat(runCount(ACME)).isZero();
        }
    }

    // =====================================================================
    // §6g's gating rule
    // =====================================================================

    /**
     * §6g: <em>"a project may require APPROVED status before a case can be added to a run … Powerful
     * and genuinely obstructive — must be an explicit choice, with a documented lead override for
     * the 2am release."</em> (§82.)
     *
     * <p>The cases in this class's fixture are authored through the real {@code POST /cases}, so
     * they are {@code DRAFT} — which is what every case on a real instance is when it is created,
     * and the state §72's rule says a fixture must be able to sit in. Approving one is therefore two
     * real bulk operations, and {@link #approve} does them rather than writing the column.
     *
     * <p><strong>The two personas discriminate.</strong> {@code V3} gives Test Lead
     * {@code case:approve} and withholds it from Test Engineer, who holds {@code run:create} — so
     * the engineer is refused and cannot override while the lead can.
     */
    @Nested
    @DisplayName("§6g's gating rule — a gated project runs approved cases, on the RESOLVED set")
    class Gating {

        @Test
        @DisplayName("with gating ON, a run of draft cases is refused — 409 naming them, nothing written")
        void draftCasesAreRefused() throws Exception {
            gating(ACME_BANKING, true);

            MvcResult refused = post(ENGINEER, ACME, ACME_BANKING,
                    onCases("Regression", List.of(caseA, caseB)), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused))
                    .contains("only runs approved test cases")
                    // Both numbers, because "2 of 2" and "2 of 200" are the same message and two
                    // completely different next actions.
                    .contains("2 of the 2")
                    .contains(keyOf(caseA))
                    .contains(keyOf(caseB));
            assertThat(runCount(ACME)).isZero();
            assertThat(executionCount(ACME)).isZero();
        }

        @Test
        @DisplayName("the review impact names the plans the gate would block, and only while it is on")
        void blockedPlansAreNamedWhileTheGateIsOn() throws Exception {
            // The `execution` half of the ReviewImpactSource SPI (005 N7). The endpoint's fold is
            // ReviewImpactEndpointTest's; what only this class can assert is WHICH plans, because
            // the plan, its scope and its cases' statuses all have to be real.
            ReviewSettings on = new ReviewSettings(
                    false, false, false, false, 0, false, EditAfterApproval.RETAIN, true);
            ReviewSettings off = new ReviewSettings(
                    false, false, false, false, 0, false, EditAfterApproval.RETAIN, false);

            assertThat(blocked(ACME_BANKING, off))
                    .as("with the gate off no run is refused for this reason, so the honest answer "
                            + "is an empty list and the query is not worth running")
                    .isEmpty();

            assertThat(blocked(ACME_BANKING, on))
                    .as("the plan's scope holds caseA and caseB, both DRAFT")
                    .extracting(ReviewImpactSource.BlockedPlan::planId)
                    .contains(plan);

            approve(List.of(caseA, caseB));

            assertThat(blocked(ACME_BANKING, on))
                    .as("approve the scope and the plan stops being blocked — the predicate is "
                            + "`<> APPROVED` over the RESOLVED set, the same reading the run "
                            + "refusal uses")
                    .extracting(ReviewImpactSource.BlockedPlan::planId)
                    .doesNotContain(plan);
        }

        @Test
        @DisplayName("approve them all and the same run is created")
        void approvedCasesRunNormally() throws Exception {
            approve(List.of(caseA, caseB));
            gating(ACME_BANKING, true);

            MvcResult created = post(ENGINEER, ACME, ACME_BANKING,
                    onCases("Regression", List.of(caseA, caseB)), null);

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(caseIdsOf(UUID.fromString(body(created).get("id").asText())))
                    .containsExactlyInAnyOrder(caseA, caseB);
        }

        /**
         * THE test of this half. A run names its cases one of two ways and the plan's scope is the
         * one that gets forgotten — so a rule bound to {@code caseIds} would leave "create the run
         * from the plan instead" as a bypass that costs no permission and leaves no trace, on the
         * very path a release run is most likely to take.
         */
        @Test
        @DisplayName("and a run from a PLAN is gated too — the resolved set, or the plan is the bypass")
        void thePlansScopeIsGatedAsWell() throws Exception {
            gating(ACME_BANKING, true);

            MvcResult refused = post(ENGINEER, ACME, ACME_BANKING, fromPlan("Regression"), null);

            assertThat(refused.getResponse().getStatus())
                    .as("the plan's scope expands to draft cases; naming the plan must not "
                            + "launder them past the gate")
                    .isEqualTo(409);
            assertThat(runCount(ACME)).isZero();
        }

        /**
         * §99's arms arrive at §82's bypass through two new doors. A gate bound to {@code caseIds}
         * would leave "run everything tagged smoke instead" and "run the suite instead" as ways to
         * launder unapproved cases into a release run — costing no permission and leaving no trace,
         * exactly as naming the plan would.
         *
         * <p>Both arms in one test on purpose: they are the same defect, and the reason they cannot
         * fail separately is that the gate reads the <em>resolved</em> set. That is the property
         * being pinned, so a run that split them would suggest each arm has its own gate.
         */
        @Test
        @DisplayName("and a run scoped by a TAG or a SUITE is gated too — §99's arms are not doors")
        void theNewScopeArmsAreGatedAsWell() throws Exception {
            gating(ACME_BANKING, true);

            assertThat(post(ENGINEER, ACME, ACME_BANKING,
                    onScope("By tag", List.of(), List.of(), List.of("refunds")), null)
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(post(ENGINEER, ACME, ACME_BANKING,
                    onScope("By suite", List.of(), List.of(PAYMENTS), List.of()), null)
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(runCount(ACME)).isZero();
        }

        /**
         * The whole run, never a trimmed one — this class's own header argues it for a different
         * cause: a checklist quietly shorter than the one that was asked for is indistinguishable
         * from a run whose scope was smaller, and reports a pass rate over a denominator nobody
         * chose.
         */
        @Test
        @DisplayName("one unapproved case refuses the WHOLE run — it is not trimmed to the approved ones")
        void oneUnapprovedCaseRefusesTheWholeRun() throws Exception {
            approve(List.of(caseA));
            gating(ACME_BANKING, true);

            MvcResult refused = post(ENGINEER, ACME, ACME_BANKING,
                    onCases("Regression", List.of(caseA, caseB)), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused))
                    .contains("1 of the 2")
                    .contains(keyOf(caseB))
                    .doesNotContain(keyOf(caseA));
            assertThat(runCount(ACME)).isZero();
        }

        @Test
        @DisplayName("a lead overrides it explicitly, and the audit row is where 'documented' happens")
        void theLeadOverrides() throws Exception {
            gating(ACME_BANKING, true);

            Map<String, Object> payload = onCases("Hotfix at 2am", List.of(caseA, caseB));
            payload.put("overrideApprovalGate", true);
            MvcResult created = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(executionCount(ACME)).isEqualTo(2);

            JsonNode row = json.readTree(auditActions(ACME).get("run.created"));
            assertThat(row.get("approvalGateOverridden").asBoolean())
                    .as("§6g asks for a DOCUMENTED override, and this row is the documentation")
                    .isTrue();
            assertThat(lifecycleOf(caseA))
                    .as("and the cases are left honestly unapproved — the workaround this replaces "
                            + "writes a sign-off nobody performed into the approval history")
                    .isEqualTo("DRAFT");
        }

        /**
         * The flag cannot widen anybody's reach. A Test Engineer holds {@code run:create} — enough
         * to make this exact run on an ungated project — and does not hold {@code case:approve}.
         *
         * <p>409 rather than 403, deliberately: they are authorized to create runs and are simply
         * not authorized to ignore the project's policy, so the policy's own sentence is the honest
         * answer. A 403 would say "you may not create runs here", which is false.
         */
        @Test
        @DisplayName("an engineer asking to override is refused all the same — the flag grants nothing")
        void theFlagGrantsNothing() throws Exception {
            gating(ACME_BANKING, true);

            Map<String, Object> payload = onCases("Hotfix", List.of(caseA));
            payload.put("overrideApprovalGate", true);

            assertThat(post(ENGINEER, ACME, ACME_BANKING, payload, null)
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(runCount(ACME)).isZero();
        }

        /**
         * "Overridden" means the gate <em>fired</em> and was overridden, which is narrower than "the
         * flag was sent" — auditing a flag that changed nothing records somebody bypassing a policy
         * they in fact complied with.
         */
        @Test
        @DisplayName("the flag on an UNGATED project is not an override, and writes nothing")
        void theFlagIsNotAnOverrideWhereNothingGates() throws Exception {
            Map<String, Object> payload = onCases("Regression", List.of(caseA));
            payload.put("overrideApprovalGate", true);

            assertThat(post(LEAD, ACME, ACME_BANKING, payload, null)
                    .getResponse().getStatus()).isEqualTo(201);
            assertThat(json.readTree(auditActions(ACME).get("run.created"))
                    .has("approvalGateOverridden")).isFalse();
        }

        /**
         * The gate is the LAST refusal, so a request that is both malformed and gated gets the field
         * errors — those are the ones the caller can act on, where "these cases are not approved"
         * about a list that also names a sibling project's case sends them to fix the wrong thing.
         */
        @Test
        @DisplayName("a bad payload still wins over the gate — the field error is the actionable one")
        void validationIsReportedBeforeTheGate() throws Exception {
            gating(ACME_BANKING, true);

            MvcResult refused = post(ENGINEER, ACME, ACME_BANKING,
                    onCases("Regression", List.of(caseA, umbraCase)), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("caseIds");
        }

        /**
         * §77's rule — <em>"off" is narrow: it suppresses the reviewer apparatus and not the status
         * ladder</em> — one setting over. ANDing the gate with {@code reviewEnabledCase} would
         * disable it on every project that never turned peer review on.
         */
        @Test
        @DisplayName("gating binds with review switched OFF — it reads the ladder, not the apparatus")
        void gatingIsIndependentOfTheReviewOptIn() throws Exception {
            gating(ACME_BANKING, true);

            assertThat(body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/settings"))
                    .get("reviewEnabledCase").asBoolean())
                    .as("the fixture must actually be in the state this test is about")
                    .isFalse();
            assertThat(post(ENGINEER, ACME, ACME_BANKING,
                    onCases("Regression", List.of(caseA)), null)
                    .getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("a sibling project's setting does not gate this one")
        void theSettingIsPerProject() throws Exception {
            gating(ACME_MOBILE, true);

            assertThat(post(ENGINEER, ACME, ACME_BANKING,
                    onCases("Regression", List.of(caseA)), null)
                    .getResponse().getStatus()).isEqualTo(201);
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private Map<String, Object> fromPlan(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", plan == null ? null : plan.toString());
        payload.put("configurationId", null);
        payload.put("releaseVersion", null);
        payload.put("buildNumber", null);
        payload.put("caseIds", List.of());
        payload.put("assigneeId", null);
        return payload;
    }

    private Map<String, Object> onCases(String name, List<UUID> caseIds) {
        return onScope(name, caseIds, List.of(), List.of());
    }

    /** §99's three arms, with no plan — the ad-hoc run. */
    private Map<String, Object> onScope(
            String name, List<UUID> caseIds, List<UUID> suiteIds, List<String> tags) {

        Map<String, Object> payload = fromPlan(name);
        payload.put("planId", null);
        payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());
        payload.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        payload.put("tags", tags);
        return payload;
    }

    /** The cases FILED in one suite — {@code Scope}'s non-vacuity check, and nothing else. */
    private List<UUID> filedIn(UUID suiteId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM test_case "
                             + "WHERE suite_id = CAST(? AS uuid) AND deleted_at IS NULL")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private MvcResult post(
            UUID userId, UUID companyId, UUID projectId, Map<String, Object> payload,
            String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
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

    private JsonNode firstExecution(UUID runId) throws Exception {
        JsonNode detail = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId));
        String executionId = detail.get("executions").get(0).get("id").asText();
        return body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId
                + "/executions/" + executionId));
    }

    private String detailOf(MvcResult result) throws Exception {
        return body(result).get("detail").asText();
    }

    /**
     * Turns §6g's case gating rule on, <strong>through the real settings endpoint</strong> — the
     * reason is on {@code PlanLifecycleEndpointTest#gating}, and so is the reason the review opt-ins
     * are deliberately left off.
     */
    private void gating(UUID projectId, boolean requireApprovedCaseForRun) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requiredPeerReviews", 0);
        payload.put("editAfterApproval", "RETAIN");
        payload.put("requireApprovedCaseForRun", requireApprovedCaseForRun);

        MvcResult saved = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(saved.getResponse().getStatus())
                .as("the fixture depends on the real settings endpoint; %s",
                        saved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /**
     * Walks cases to {@code APPROVED} <strong>through the real bulk endpoint</strong>, two moves at
     * a time because the ladder allows no short cut: {@code APPROVED} is reachable only from
     * {@code IN_REVIEW} (§62's call, re-derived in §75).
     *
     * <p>Not a SQL update, and the difference matters here more than usual: the state this fixture
     * has to produce is the one §6g's gate reads, so producing it the way the product does is what
     * makes the test evidence about the product rather than about the column. It also means these
     * tests would notice the day the two halves stop agreeing on what "approved" is.
     */
    private void approve(List<UUID> caseIds) throws Exception {
        for (String status : List.of("IN_REVIEW", "APPROVED")) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "SET_STATUS");
            payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());
            payload.put("dryRun", false);
            payload.put("payload", Map.of("lifecycleStatus", status));

            MvcResult applied = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_BANKING + "/cases/bulk")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andReturn();

            assertThat(applied.getResponse().getStatus())
                    .as("the fixture depends on the real bulk endpoint; %s",
                            applied.getResponse().getContentAsString())
                    .isEqualTo(200);
        }
        for (UUID caseId : caseIds) {
            assertThat(lifecycleOf(caseId))
                    .as("and the fixture must actually have got there")
                    .isEqualTo("APPROVED");
        }
    }

    /** {@link PlanRunGateImpact}'s answer, in a company scope and a transaction of its own. */
    private List<ReviewImpactSource.BlockedPlan> blocked(UUID projectId, ReviewSettings settings) {
        try (var ignored = CompanyContext.open(ACME)) {
            return tx.execute(status -> runGateImpact.plansBlockedFromRunning(projectId, settings));
        }
    }

    private String keyOf(UUID caseId) throws SQLException {
        return caseColumn(caseId, "key");
    }

    private String lifecycleOf(UUID caseId) throws SQLException {
        return caseColumn(caseId, "lifecycle_status");
    }

    private String caseColumn(UUID caseId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body(result).get("errors");
        if (node == null) {
            return errors;
        }
        node.properties().forEach(field -> {
            List<String> messages = new ArrayList<>();
            field.getValue().forEach(message -> messages.add(message.asText()));
            errors.put(field.getKey(), messages);
        });
        return errors;
    }

    // =====================================================================
    // helpers — fixture, authored through the REAL endpoints
    // =====================================================================

    private UUID umbraCase;

    private UUID createPlan(UUID projectId, String name, List<UUID> suiteIds, List<UUID> caseIds)
            throws Exception {

        UUID actor = projectId.equals(UMBRA_BANKING) ? OUTSIDER : LEAD;
        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", null);
        payload.put("milestoneName", null);
        payload.put("milestoneDate", null);
        payload.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(actor, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real plan endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(casePayload(title, suiteId, null))))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    /** Rewrites the case through the real PATCH, so a genuine v2 exists with different text. */
    private void editCaseToV2(UUID caseId) throws Exception {
        MvcResult edited = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                casePayload("Rewritten before the run", REFUNDS, "Rewritten"))))
                .andReturn();

        assertThat(edited.getResponse().getStatus())
                .as("%s", edited.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(edited).get("currentVersionNo").asInt()).isEqualTo(2);
    }

    private Map<String, Object> casePayload(String title, UUID suiteId, String changeComment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by RunCreateEndpointTest.");
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
        payload.put("steps", List.of(step));
        payload.put("changeComment", changeComment);
        return payload;
    }

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
        UUID id = UUID.nameUUIDFromBytes(("run-create:" + marker).getBytes());
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
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite");
                // §82. Written by the Gating class's helper and by nothing else, so leaving it lets
                // one test turn the gate on for every test that runs after it. The identical
                // omission in PlanLifecycleEndpointTest reddened five tests on its first run, three
                // of them written before the slice existed.
                execute(connection, "DELETE FROM project_settings");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void resetRunCounter() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection,
                        "UPDATE key_sequence SET next_value = 1 WHERE entity_type = 'RUN'");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws Exception {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, ACME_BANKING, null, "TS-2", 2, "Refunds", "s2");
            // §98: REFUNDS was a CHILD of PAYMENTS, and the plan below is scoped on PAYMENTS — so
            // every case in this fixture reached the plan through the subtree. Suites are flat, so
            // PAYMENTS claims them by TAG instead: the arm is different, the property is the same
            // one the fixture was built for (a suite arm reaching cases that are filed elsewhere),
            // and a tag rule is LIVE, which also preserves the later-authored case entering scope
            // with no edit to the plan.
            tagRule(connection, PAYMENTS, "refunds");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Mobile", "s4");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
        }
        umbraCase = createCase(OUTSIDER, UMBRA, UMBRA_BANKING, "Umbra's own", UMBRA_SUITE);
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

    /** One tag rule on a suite — the membership arm that replaced this fixture's subtree (§98). */
    private void tagRule(Connection connection, UUID suiteId, String tag) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_tag_rule (company_id, suite_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    /** One tag on a case, so a suite's rule can claim it. */
    private void tagCase(UUID caseId, String tag) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back, past the API
    // =====================================================================

    private UUID configurationOf(UUID projectId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM configuration WHERE project_id = CAST(? AS uuid) "
                             + "AND is_default")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must seed a default configuration").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private long runCount(UUID companyId) throws SQLException {
        return count(companyId, "SELECT COUNT(*) FROM test_run");
    }

    private long executionCount(UUID companyId) throws SQLException {
        return count(companyId, "SELECT COUNT(*) FROM test_execution");
    }

    private long count(UUID companyId, String sql) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<UUID> caseIdsOf(UUID runId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT case_id FROM test_execution WHERE run_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private List<String> executionReleases(UUID runId) throws SQLException {
        List<String> releases = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT release_version || '|' || release_sort_key FROM test_execution "
                             + "WHERE run_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    releases.add(rows.getString(1));
                }
            }
        }
        return releases;
    }

    private String sortKeyOf(UUID runId) throws SQLException {
        return column(runId, "release_sort_key");
    }

    private UUID planIdOf(UUID runId) throws SQLException {
        String value = column(runId, "CAST(plan_id AS text)");
        return value == null ? null : UUID.fromString(value);
    }

    private String column(UUID runId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_run WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
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

    private void softDeleteCase(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.executeUpdate();
        }
    }
}
