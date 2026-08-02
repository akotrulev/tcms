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
 * The {@code execution} module over HTTP — plans, runs, executions, and the one write.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>An execution rendering the case as it is now.</strong> The entire design turns on
 *       the pin: {@code case_version_id} names the version that was executed, and the steps a tester
 *       sees must be that version's. So the fixture <em>edits the case after the execution exists</em>
 *       and asserts the execution still shows the old text. A live read passes every other assertion
 *       in this class.</li>
 *   <li><strong>The snapshot coupling breaking silently.</strong> {@code CaseFacts} reads
 *       {@code test_case_version.snapshot} by field name, and nothing fails to compile if
 *       {@code repository} renames one. So the cases here are authored through the <em>real</em>
 *       {@code POST /cases}, not by hand-written JSON — the two modules are checked against each
 *       other rather than against a fixture's idea of the format.</li>
 *   <li><strong>Recording and amending gated on one permission.</strong> {@code result:amend} is
 *       Lead-only (roles spec §A4). Collapse it into {@code result:submit} and a Test Engineer — or
 *       a leaked CI token — can overwrite somebody else's verdict. One test carries it.</li>
 *   <li><strong>{@code If-Match} ignored.</strong> A run is worked by several testers at once by
 *       design, so a lost update here is the ordinary failure rather than the exotic one.</li>
 *   <li><strong>A plan's case count taken from its scope items.</strong> A scope item naming a
 *       folder means the folder and everything beneath it, and a case can be in scope twice.</li>
 *   <li><strong>The second gate missing from a two-segment path.</strong> A run in a sibling
 *       project, and an execution belonging to a different run, must both be 404.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code e2…}, checked against every prefix
 * already in {@code src/test/java} before writing. Sharing them is how handoff §19's first defect
 * happened, and §29 hit it again: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Execution endpoints — plans, runs, the pinned execution, and recording a result")
class ExecutionEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("e2000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("e2000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. Carries the plan, the run and the executions. */
    private static final UUID ACME_BANKING = UUID.fromString("e2001000-0000-4000-8000-000000000001");
    /** Same company, granted to LEAD only. Holds the sibling run the path checks must refuse. */
    private static final UUID ACME_MOBILE = UUID.fromString("e2001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("e2002000-0000-4000-8000-000000000001");

    /** Test Lead. Holds {@code result:submit} AND {@code result:amend}. */
    private static final UUID LEAD = UUID.fromString("e2003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer. Holds {@code result:submit} and <strong>not</strong> {@code result:amend} — the
     * one persona that separates the two permissions, and the reason this class has two writers.
     */
    private static final UUID ENGINEER = UUID.fromString("e2003000-0000-4000-8000-000000000002");
    /** Viewer. Reads everything here and writes nothing. */
    private static final UUID VIEWER = UUID.fromString("e2003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("e2003000-0000-4000-8000-000000000004");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("e2003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("e2004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("e2004000-0000-4000-8000-000000000002");
    /** Outside the plan's suite scope, so a case here counts only via its own scope item. */
    private static final UUID SPARE = UUID.fromString("e2004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("e2004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("e2004000-0000-4000-8000-000000000005");

    private static final UUID PLAN = UUID.fromString("e2005000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_PLAN = UUID.fromString("e2005000-0000-4000-8000-000000000002");
    private static final UUID RUN = UUID.fromString("e2006000-0000-4000-8000-000000000001");
    /** A second run on the same plan, one release older — the release filter's other side. */
    private static final UUID OLD_RUN = UUID.fromString("e2006000-0000-4000-8000-000000000002");
    /** In ACME_MOBILE. Reached through ACME_BANKING's path it must be a 404. */
    private static final UUID MOBILE_RUN = UUID.fromString("e2006000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_RUN = UUID.fromString("e2006000-0000-4000-8000-000000000004");

    private static final UUID EXEC_A = UUID.fromString("e2007000-0000-4000-8000-000000000001");
    private static final UUID EXEC_B = UUID.fromString("e2007000-0000-4000-8000-000000000002");
    /** Belongs to OLD_RUN. Asked for through RUN's path it must be a 404. */
    private static final UUID EXEC_OLD = UUID.fromString("e2007000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_EXEC = UUID.fromString("e2007000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_EXEC = UUID.fromString("e2007000-0000-4000-8000-000000000005");

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

    /** Authored through {@code POST /cases}, so its v1 snapshot is the real one. */
    private UUID caseA;
    private UUID caseB;
    private UUID mobileCase;
    private UUID umbraCase;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ex-acme", "Acme", now);
            company(UMBRA, "ex-umbra", "Umbra", now);

            activeUser(LEAD, "lead@ex-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@ex-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@ex-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@ex-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@ex-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "XAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "XAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("ex-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("ex-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grantOnProject("ex-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("ex-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "XUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("ex-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        // THE fixture decision: the cases are authored through the real endpoint, so their v1
        // snapshots are written by repository.CaseVersions rather than by this file's idea of the
        // format. That is what makes the snapshot coupling machine-checked — see the class header.
        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", REFUNDS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", SPARE);
        mobileCase = createCase(LEAD, ACME, ACME_MOBILE, "Mobile login", MOBILE_SUITE);
        umbraCase = createCase(OUTSIDER, UMBRA, UMBRA_BANKING, "Umbra's own", UMBRA_SUITE);

        seedPlansRunsAndExecutions();

        // The fixture's own case creates wrote audit rows. Cleared LAST, so a test asserting that a
        // refusal writes nothing is asserting about its own request rather than about the fixture.
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

    // =====================================================================
    // The gates, and the fact that an unreachable target is always 404
    // =====================================================================

    @Nested
    @DisplayName("The gates — company, then the project grant, then the path's own segments")
    class Gates {

        @Test
        @DisplayName("a Test Lead reads plans, runs and one execution")
        void leadReadsEverything() throws Exception {
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + PLAN)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(get(LEAD, ACME, executionPath(RUN, EXEC_A))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a Viewer reads all five too — the read gate is the one every role passes")
        void viewerReads() throws Exception {
            // Without this the 403s below prove nothing: they would pass just as well against a
            // route that does not exist or a token that is wrong.
            assertThat(get(VIEWER, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(get(VIEWER, ACME, executionPath(RUN, EXEC_A))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a company member with no grant gets 404 on every read — never 403")
        void noGrantSeesNothing() throws Exception {
            // Persona 3. These rows ARE in the caller's company, so every cross-company assertion
            // in the suite would still pass while this leaked.
            assertThat(get(NO_GRANT, ACME, "/projects/" + ACME_BANKING + "/plans")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(NO_GRANT, ACME, "/projects/" + ACME_BANKING + "/runs")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(NO_GRANT, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(NO_GRANT, ACME, executionPath(RUN, EXEC_A))
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the other client sees neither the project, the run nor the execution")
        void theOtherCompanyIsRefused() throws Exception {
            assertThat(get(OUTSIDER, UMBRA, "/projects/" + ACME_BANKING + "/runs")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(OUTSIDER, UMBRA, "/projects/" + ACME_BANKING + "/runs/" + RUN)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(OUTSIDER, UMBRA, executionPath(RUN, EXEC_A))
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("Umbra's own run is invisible to Acme, and Umbra sees only its own")
        void isolationBothWays() throws Exception {
            assertThat(get(LEAD, ACME, "/projects/" + UMBRA_BANKING + "/runs/" + UMBRA_RUN)
                    .getResponse().getStatus()).isEqualTo(404);

            JsonNode umbraRuns = body(get(OUTSIDER, UMBRA, "/projects/" + UMBRA_BANKING + "/runs"));
            assertThat(ids(umbraRuns)).containsExactly(UMBRA_RUN).doesNotContain(RUN, OLD_RUN);
        }

        @Test
        @DisplayName("a run in a SIBLING project is 404 through this project's path")
        void aSiblingProjectsRunIsNotReachable() throws Exception {
            // LEAD holds both projects, so the company gate and the grant gate both pass. Only the
            // repository's project predicate refuses this — drop it and a run resolves through any
            // path the caller happens to hold.
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + MOBILE_RUN)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + MOBILE_PLAN)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("an execution from ANOTHER RUN is 404 through this run's path")
        void anotherRunsExecutionIsNotReachable() throws Exception {
            // Both runs are in the same project and the caller may read both. Only the run
            // predicate on the execution refuses it — and without it a tester would be shown a
            // result from a run they never opened.
            assertThat(get(LEAD, ACME, executionPath(RUN, EXEC_OLD))
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(LEAD, ACME, executionPath(OLD_RUN, EXEC_OLD))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an absent id is 404, on all three segments")
        void absentIdsAre404() throws Exception {
            UUID absent = UUID.fromString("e200ffff-0000-4000-8000-00000000ffff");
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + absent)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + absent)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(get(LEAD, ACME, executionPath(RUN, absent))
                    .getResponse().getStatus()).isEqualTo(404);
        }
    }

    // =====================================================================
    // The pin — the property the whole module turns on
    // =====================================================================

    @Nested
    @DisplayName("The pin — an execution shows the case as it WAS")
    class ThePin {

        @Test
        @DisplayName("the execution serves the pinned version's steps, authored by POST /cases")
        void stepsComeFromTheSnapshot() throws Exception {
            JsonNode execution = body(get(LEAD, ACME, executionPath(RUN, EXEC_A)));

            assertThat(execution.get("caseVersionNo").asInt()).isEqualTo(1);
            assertThat(execution.get("stepFormat").asText()).isEqualTo("STRUCTURED");
            assertThat(stepActions(execution))
                    .as("the steps must be the ones the real create endpoint wrote into v1")
                    .containsExactly("Open the refund screen", "Submit");
            // Every step arrives with the stable test_step id, which is what a PATCH names.
            assertThat(execution.get("steps").get(0).get("stepId").asText()).isNotBlank();
        }

        @Test
        @DisplayName("editing the case afterwards does NOT change the execution — THE assertion")
        void anEditDoesNotRewriteHistory() throws Exception {
            // Edit through the real endpoint, so a v2 exists and the live case genuinely differs.
            List<String> pinnedStepIds = stepIdsOf(EXEC_A);
            MvcResult edited = editCase(
                    LEAD, ACME, ACME_BANKING, caseA, REFUNDS, "Rewritten after the run",
                    pinnedStepIds);
            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(edited).get("currentVersionNo").asInt()).isEqualTo(2);

            JsonNode execution = body(get(LEAD, ACME, executionPath(RUN, EXEC_A)));

            assertThat(execution.get("caseVersionNo").asInt())
                    .as("the execution is pinned to v1 and must stay there")
                    .isEqualTo(1);
            assertThat(execution.get("caseTitle").asText())
                    .as("the pinned title, not the live one — v1 beside today's text is two "
                            + "contradictory claims on one line")
                    .isEqualTo("Refund the whole captured amount");
            assertThat(stepActions(execution))
                    .as("a live read would answer the edited steps here")
                    .containsExactly("Open the refund screen", "Submit");
        }

        @Test
        @DisplayName("the case key is served from the snapshot and links back to the live case")
        void theCaseKeyAndIdAreServed() throws Exception {
            JsonNode execution = body(get(LEAD, ACME, executionPath(RUN, EXEC_A)));
            assertThat(execution.get("caseKey").asText()).startsWith("TC-");
            assertThat(UUID.fromString(execution.get("caseId").asText())).isEqualTo(caseA);
        }
    }

    // =====================================================================
    // Plans
    // =====================================================================

    @Nested
    @DisplayName("Plans — the scope expands, and what is not computed says so")
    class Plans {

        @Test
        @DisplayName("caseCount expands a suite scope item to its subtree, and dedupes")
        void caseCountExpandsTheScope() throws Exception {
            // Scope: the PAYMENTS folder (whose subtree holds caseA, under REFUNDS), caseB directly,
            // and caseA AGAIN directly. Two distinct cases. A COUNT over plan_scope_item says 3, and
            // a count that forgets the subtree says 2 for the wrong reason — so the fixture makes
            // those two wrong answers different numbers.
            JsonNode plan = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + PLAN));
            assertThat(plan.get("caseCount").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("a soft-deleted case leaves the plan's scope count")
        void deletedCasesAreNotCounted() throws Exception {
            softDeleteCase(ACME, caseB);
            JsonNode plan = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + PLAN));
            assertThat(plan.get("caseCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("the criteria are parsed into evaluable predicates, unevaluated")
        void criteriaAreParsed() throws Exception {
            JsonNode plan = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + PLAN));

            assertThat(plan.get("exitCriteria")).hasSize(1);
            JsonNode criterion = plan.get("exitCriteria").get(0);
            assertThat(criterion.get("metric").asText()).isEqualTo("pass_rate");
            assertThat(criterion.get("op").asText()).isEqualTo(">=");
            assertThat(criterion.get("value").asDouble()).isEqualTo(95.0);
            assertThat(criterion.get("met").isNull())
                    .as("nothing evaluates a criterion in this slice, and false would claim the "
                            + "gate had been checked and failed")
                    .isTrue();
        }

        @Test
        @DisplayName("passRate and coverage are null rather than an invented zero")
        void aggregatesAreNull() throws Exception {
            JsonNode plan = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + PLAN));
            assertThat(plan.get("passRate").isNull()).isTrue();
            assertThat(plan.get("coverage").isNull()).isTrue();
        }

        @Test
        @DisplayName("the plan carries the project's configurations, default first")
        void configurationsAreServed() throws Exception {
            JsonNode plan = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + PLAN));
            assertThat(plan.get("configurations")).isNotEmpty();
            assertThat(plan.get("configurations").get(0).get("isDefault").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("the list holds only this project's plans")
        void theListIsProjectScoped() throws Exception {
            JsonNode plans = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans"));
            assertThat(ids(plans)).containsExactly(PLAN).doesNotContain(MOBILE_PLAN);
        }
    }

    // =====================================================================
    // Runs
    // =====================================================================

    @Nested
    @DisplayName("Runs — counts, filters, and the checklist")
    class Runs {

        @Test
        @DisplayName("the counts come from the executions and read by KIND, not by status name")
        void countsAreTallied() throws Exception {
            JsonNode run = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN));
            JsonNode counts = run.get("counts");

            assertThat(counts.get("total").asLong()).isEqualTo(2);
            assertThat(counts.get("passed").asLong()).isEqualTo(1);
            assertThat(counts.get("notRun").asLong()).isEqualTo(1);
            assertThat(counts.get("failed").asLong()).isZero();
            // The SPA's results arithmetic depends on this identity.
            assertThat(counts.get("passed").asLong() + counts.get("failed").asLong()
                    + counts.get("blocked").asLong() + counts.get("skipped").asLong()
                    + counts.get("notRun").asLong())
                    .isEqualTo(counts.get("total").asLong());
        }

        @Test
        @DisplayName("the run resolves its plan key in one query, and its configuration")
        void planKeyAndConfigurationAreResolved() throws Exception {
            JsonNode run = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN));
            assertThat(run.get("planKey").asText()).isEqualTo("TP-1");
            assertThat(run.get("configuration").get("name").asText()).isEqualTo("Default");
            assertThat(run.get("configuration").get("attributes").isObject()).isTrue();
        }

        @Test
        @DisplayName("the checklist is ordered by the pinned case's key SEQUENCE, not its text")
        void executionsAreOrderedByKeySequence() throws Exception {
            JsonNode run = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN));
            assertThat(run.get("executions")).hasSize(2);
            assertThat(ids(run.get("executions"))).containsExactly(EXEC_A, EXEC_B);
        }

        @Test
        @DisplayName("planId narrows the list; a plan from another project narrows it to nothing")
        void thePlanFilterNarrows() throws Exception {
            assertThat(ids(body(get(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/runs?planId=" + PLAN))))
                    .containsExactlyInAnyOrder(RUN, OLD_RUN);

            // Not a 404: "which of my runs belong to that plan" honestly answers none, and a 404
            // would make the query string an oracle for plan ids in projects the caller cannot open.
            MvcResult foreign = get(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/runs?planId=" + MOBILE_PLAN);
            assertThat(foreign.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(foreign).size()).isZero();
        }

        @Test
        @DisplayName("releaseVersion narrows, and an EMPTY one is no filter at all")
        void theReleaseFilterNarrows() throws Exception {
            assertThat(ids(body(get(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/runs?releaseVersion=4.3.0"))))
                    .containsExactly(RUN);

            // A hand-edited URL or a form submitting its empty input arrives as `releaseVersion=`.
            // Taken literally it matches runs whose release is the empty string — of which there are
            // none — so the list would come back empty for a filter the user believes they cleared.
            assertThat(ids(body(get(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/runs?releaseVersion="))))
                    .containsExactlyInAnyOrder(RUN, OLD_RUN);
        }

        @Test
        @DisplayName("an unexecuted execution has no status, no executor and no result timestamp")
        void anUnexecutedRowIsHonestlyEmpty() throws Exception {
            JsonNode run = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN));
            JsonNode unexecuted = run.get("executions").get(0);

            assertThat(unexecuted.get("status").isNull()).isTrue();
            assertThat(unexecuted.get("executedBy").isNull()).isTrue();
            assertThat(unexecuted.get("executedAt").isNull()).isTrue();
            assertThat(unexecuted.get("assignee").isNull())
                    .as("nullable in the schema and nullable on the wire since this slice — a bare "
                            + "$ref claimed every execution has an assignee")
                    .isTrue();
            assertThat(unexecuted.get("defects")).isEmpty();
        }
    }

    // =====================================================================
    // The write — recording a result
    // =====================================================================

    @Nested
    @DisplayName("Recording a result — two permissions, and the lock that makes them safe")
    class Recording {

        @Test
        @DisplayName("a Test Engineer records a first result and gets the whole execution back")
        void engineerRecords() throws Exception {
            MvcResult result = patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), "Worked"), "0");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode execution = body(result);
            assertThat(execution.get("status").get("kind").asText()).isEqualTo("PASSED");
            assertThat(execution.get("comment").asText()).isEqualTo("Worked");
            assertThat(execution.get("executedBy").get("id").asText()).isEqualTo(ENGINEER.toString());
            assertThat(execution.get("lockVersion").asLong())
                    .as("the response must carry the NEW version, or the caller's next save 409s "
                            + "against their own change")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the run's counts move with the result")
        void theRunCountsMove() throws Exception {
            patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(failedStatus(), null), "0");

            JsonNode counts = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + RUN))
                    .get("counts");
            assertThat(counts.get("failed").asLong()).isEqualTo(1);
            assertThat(counts.get("notRun").asLong()).isZero();
        }

        @Test
        @DisplayName("a Test Engineer may NOT amend an existing result — the two permissions are two")
        void engineerCannotAmend() throws Exception {
            // THE assertion of this class. EXEC_B already carries a verdict, so this is an amend.
            // Gate both verbs on result:submit and this is the only test in the suite that goes red.
            MvcResult refused =
                    patch(ENGINEER, ACME, RUN, EXEC_B, resultPayload(failedStatus(), null), "0");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(refused).get("detail").asText())
                    .as("and it must not be the record-results sentence — a Test Engineer holds "
                            + "that permission and records results all day")
                    .isEqualTo("A result has already been recorded here, and changing one needs a "
                            + "test lead.");

            assertThat(statusKindOf(EXEC_B))
                    .as("a 403 that wrote anyway is worse than either")
                    .isEqualTo("PASSED");
        }

        @Test
        @DisplayName("the same Engineer, refused the amend, DOES reach the first-time record")
        void theRefusalIsThePermissionAndNotABrokenRoute() throws Exception {
            assertThat(patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), null), "0")
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Lead may amend, and the audit row says what it changed FROM")
        void leadAmends() throws Exception {
            MvcResult amended =
                    patch(LEAD, ACME, RUN, EXEC_B, resultPayload(failedStatus(), "Re-checked"), "0");

            assertThat(amended.getResponse().getStatus()).isEqualTo(200);
            assertThat(statusKindOf(EXEC_B)).isEqualTo("FAILED");

            Map<String, String> audit = auditEvents(ACME);
            assertThat(audit).containsKey("execution.amended");
            assertThat(audit.get("execution.amended"))
                    .as("an amendment recorded as just 'to: Failed' is indistinguishable from the "
                            + "original submission — which is the distinction the Lead-only "
                            + "permission exists to make visible")
                    // Postgres normalizes a JSONB document's whitespace, so the stored text reads
                    // `{"to": "Failed", ...}` rather than the string this service concatenated.
                    .contains("\"from\": \"Passed\"")
                    .contains("\"to\": \"Failed\"");
        }

        @Test
        @DisplayName("a first record writes execution.recorded, with the case key")
        void aFirstRecordIsAudited() throws Exception {
            patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), null), "0");

            Map<String, String> audit = auditEvents(ACME);
            assertThat(audit).containsKey("execution.recorded");
            assertThat(audit.get("execution.recorded"))
                    .contains("\"from\": null")
                    .contains("\"to\": \"Passed\"")
                    .contains("TC-");
        }

        @Test
        @DisplayName("a Viewer is refused, with the record-results sentence, and nothing is written")
        void viewerIsRefused() throws Exception {
            MvcResult refused =
                    patch(VIEWER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), null), "0");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(refused).get("detail").asText())
                    .isEqualTo("You do not have permission to record results in this project.");
            assertThat(statusKindOf(EXEC_A)).isNull();
            assertThat(auditEvents(ACME)).isEmpty();
        }

        @Test
        @DisplayName("neither 403 names a permission key")
        void theRefusalsNameNoKey() throws Exception {
            // A key is not actionable by someone who cannot grant it to themselves, and naming it
            // publishes the catalogue to exactly the caller not entitled to it.
            assertThat(body(patch(VIEWER, ACME, RUN, EXEC_A,
                    resultPayload(passedStatus(), null), "0")).get("detail").asText())
                    .doesNotContain("result:");
            assertThat(body(patch(ENGINEER, ACME, RUN, EXEC_B,
                    resultPayload(failedStatus(), null), "0")).get("detail").asText())
                    .doesNotContain("result:");
        }

        @Test
        @DisplayName("the permission is checked BEFORE the payload")
        void thePermissionIsCheckedFirst() throws Exception {
            // A Viewer sending a status id that does not exist gets 403, not 400. The other order
            // hands "that status is not one this project uses" — a probe for the project's
            // vocabulary — to exactly the caller who was just refused.
            UUID nonsense = UUID.fromString("e200dead-0000-4000-8000-00000000dead");
            assertThat(patch(VIEWER, ACME, RUN, EXEC_A, resultPayload(nonsense, null), "0")
                    .getResponse().getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("an unreachable execution is 404 even for a caller who could write")
        void unreachableBeatsForbidden() throws Exception {
            assertThat(patch(LEAD, ACME, RUN, EXEC_OLD, resultPayload(passedStatus(), null), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // If-Match, and the payload
    // =====================================================================

    @Nested
    @DisplayName("If-Match and validation")
    class Preconditions {

        @Test
        @DisplayName("a stale If-Match is 409 with advice that fits THIS screen")
        void staleIsRefused() throws Exception {
            MvcResult stale =
                    patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), null), "7");

            assertThat(stale.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(stale).get("title").asText())
                    .isEqualTo("Someone else recorded a result first");
            assertThat(body(stale).get("detail").asText())
                    .as("'reload the case' would send the reader to the wrong page")
                    .contains("Reload the execution");
            assertThat(statusKindOf(EXEC_A)).isNull();
        }

        @Test
        @DisplayName("a second tester's save is refused after the first one lands")
        void theLostUpdateIsPrevented() throws Exception {
            // Both testers read version 0. The first records; the second's save must not silently
            // overwrite it. This is the ordinary case on this endpoint, not the exotic one.
            assertThat(patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), "Mine"), "0")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(patch(LEAD, ACME, RUN, EXEC_A, resultPayload(failedStatus(), "Theirs"), "0")
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(statusKindOf(EXEC_A)).isEqualTo("PASSED");
        }

        @Test
        @DisplayName("no If-Match at all is last-write-wins — correct for an importer")
        void theHeaderIsOptional() throws Exception {
            assertThat(patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), null), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a malformed If-Match is 400, not 409 — a retry loop cannot fix it")
        void aMalformedHeaderIs400() throws Exception {
            MvcResult bad =
                    patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(passedStatus(), null), "v3");
            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(bad)).containsKey("If-Match");
        }

        @Test
        @DisplayName("a missing statusId is a field error — this endpoint records a verdict")
        void statusIsRequired() throws Exception {
            MvcResult bad = patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(null, "just a note"), "0");
            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(bad)).containsKey("statusId");
        }

        @Test
        @DisplayName("a status from ANOTHER project is refused, not silently stored")
        void aForeignStatusIsRefused() throws Exception {
            // Statuses are project-scoped, so the sibling project's Passed is a real row in the same
            // company that this project does not use. Stored, it would render as a blank result.
            UUID foreign = statusIdOf(ACME, ACME_MOBILE, "PASSED");
            MvcResult bad = patch(ENGINEER, ACME, RUN, EXEC_A, resultPayload(foreign, null), "0");

            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(bad)).containsKey("statusId");
            assertThat(statusKindOf(EXEC_A)).isNull();
        }

        @Test
        @DisplayName("a step that is not in the pinned version is refused, never dropped")
        void aForeignStepIsRefused() throws Exception {
            // The tester marked something. A save that reports success while discarding a mark is
            // the worst failure this endpoint has, because the mark is a record of work already done.
            String payload = resultWithSteps(passedStatus(),
                    List.of(Map.of("stepId", UUID.randomUUID().toString(),
                            "statusId", passedStatus().toString())));

            MvcResult bad = patch(ENGINEER, ACME, RUN, EXEC_A, payload, "0");
            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(bad)).containsKey("stepResults");
            assertThat(statusKindOf(EXEC_A)).isNull();
        }
    }

    // =====================================================================
    // Step results
    // =====================================================================

    @Nested
    @DisplayName("Step results — written, read back, and amendable")
    class Steps {

        @Test
        @DisplayName("step verdicts are stored and merged back into the pinned steps")
        void stepResultsRoundTrip() throws Exception {
            List<String> stepIds = stepIdsOf(EXEC_A);
            String payload = resultWithSteps(failedStatus(), List.of(
                    Map.of("stepId", stepIds.get(0), "statusId", passedStatus().toString()),
                    Map.of("stepId", stepIds.get(1), "statusId", failedStatus().toString(),
                            "comment", "Balance was 45.00")));

            JsonNode recorded = body(patch(ENGINEER, ACME, RUN, EXEC_A, payload, "0"));

            assertThat(recorded.get("steps")).hasSize(2);
            assertThat(recorded.get("steps").get(0).get("statusId").asText())
                    .isEqualTo(passedStatus().toString());
            assertThat(recorded.get("steps").get(1).get("comment").asText())
                    .isEqualTo("Balance was 45.00");
            // The pinned text is still there beside the result — one shape, not two lists to join.
            assertThat(recorded.get("steps").get(1).get("action").asText()).isEqualTo("Submit");
        }

        @Test
        @DisplayName("saving the same step twice updates rather than violating the unique index")
        void aSecondSaveOnTheSameStepUpdates() throws Exception {
            List<String> stepIds = stepIdsOf(EXEC_A);
            String first = resultWithSteps(passedStatus(),
                    List.of(Map.of("stepId", stepIds.get(0), "statusId", passedStatus().toString())));
            assertThat(patch(LEAD, ACME, RUN, EXEC_A, first, "0").getResponse().getStatus())
                    .isEqualTo(200);

            String second = resultWithSteps(failedStatus(),
                    List.of(Map.of("stepId", stepIds.get(0), "statusId", failedStatus().toString())));
            // uq_step_result (execution_id, step_id) makes an unconditional insert a constraint
            // violation, which arrives as a 500 in the middle of recording a result.
            JsonNode amended = body(patch(LEAD, ACME, RUN, EXEC_A, second, "1"));

            assertThat(amended.get("steps").get(0).get("statusId").asText())
                    .isEqualTo(failedStatus().toString());
            assertThat(stepResultRowCount(EXEC_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("an unmarked step still appears — the checklist is the version's, not the results'")
        void unmarkedStepsStillRender() throws Exception {
            List<String> stepIds = stepIdsOf(EXEC_A);
            String payload = resultWithSteps(passedStatus(),
                    List.of(Map.of("stepId", stepIds.get(0), "statusId", passedStatus().toString())));
            patch(LEAD, ACME, RUN, EXEC_A, payload, "0");

            JsonNode execution = body(get(LEAD, ACME, executionPath(RUN, EXEC_A)));
            assertThat(execution.get("steps")).hasSize(2);
            assertThat(execution.get("steps").get(1).get("statusId").isNull())
                    .as("driven by the snapshot's step list, or a tester sees only the steps they "
                            + "already did")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Evidence — attaching the proof, and removing only your own")
    class EvidenceAttachment {

        @Test
        @DisplayName("a tester attaches a screenshot and it comes back on the execution")
        void attaches() throws Exception {
            byte[] bytes = "PNG-ish bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

            JsonNode attached = body(attach(ENGINEER, ACME, EXEC_A, "shot.png", "image/png", bytes,
                    "Balance read 45.00"));

            assertThat(attached.get("filename").asText()).isEqualTo("shot.png");
            assertThat(attached.get("contentType").asText()).isEqualTo("image/png");
            assertThat(attached.get("sizeBytes").asLong()).isEqualTo(bytes.length);
            assertThat(attached.get("caption").asText()).isEqualTo("Balance read 45.00");
            assertThat(attached.get("createdBy").get("id").asText())
                    .isEqualTo(ENGINEER.toString());
            // thumbnailUrl is null rather than absent or guessed — no image processing in this
            // slice, and null is the honest answer for a field the contract declares nullable.
            assertThat(attached.get("thumbnailUrl").isNull()).isTrue();

            JsonNode execution = body(get(ENGINEER, ACME, executionPath(RUN, EXEC_A)));
            assertThat(execution.get("evidence")).hasSize(1);
            assertThat(execution.get("evidence").get(0).get("id").asText())
                    .isEqualTo(attached.get("id").asText());
        }

        @Test
        @DisplayName("the bytes come back through the url the execution advertises")
        void downloadsThroughTheAdvertisedUrl() throws Exception {
            byte[] bytes = "the actual screenshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            JsonNode attached = body(attach(LEAD, ACME, EXEC_B, "log.txt", "text/plain", bytes, null));

            // Read the url OFF THE RESPONSE rather than composing it here. A url this test builds
            // and the server also builds would agree with itself while pointing nowhere real —
            // which is precisely the class of defect §52 found by having two hand-made shapes.
            String url = attached.get("url").asText();
            MvcResult download = get(LEAD, ACME, url.replace("/api/v1", ""));

            assertThat(download.getResponse().getStatus()).isEqualTo(200);
            assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(bytes);
            assertThat(download.getResponse().getContentType()).startsWith("text/plain");
            assertThat(download.getResponse().getHeader("Content-Disposition"))
                    .contains("inline").contains("log.txt");
        }

        @Test
        @DisplayName("a Viewer cannot attach evidence, and nothing is written when refused")
        void refusesWithoutThePermission() throws Exception {
            MvcResult refused = attach(VIEWER, ACME, EXEC_B, "shot.png", "image/png",
                    new byte[] {1, 2, 3}, null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            // Asserted through a second read, because a refusal that answered 403 AND stored the
            // row is the failure this test exists for — the status alone cannot tell them apart.
            assertThat(body(get(LEAD, ACME, executionPath(RUN, EXEC_B))).get("evidence"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a company member with no project grant gets 404, never 403")
        void unreachableProjectIs404() throws Exception {
            MvcResult refused = attach(NO_GRANT, ACME, EXEC_B, "shot.png", "image/png",
                    new byte[] {1}, null);

            // §6t: an unreachable resource answers 404 identically to an absent one, so the
            // response cannot be used as an oracle for what exists.
            assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the uploaded filename never becomes a path — a traversal name is stored as text")
        void filenameIsDataNotAPath() throws Exception {
            JsonNode attached = body(attach(LEAD, ACME, EXEC_B, "../../../etc/passwd", "text/plain",
                    new byte[] {9}, null));

            // Reduced to its last segment and kept in a column. The storage key is three
            // server-generated UUIDs, so this string never reaches the filesystem at all; the
            // assertion pins that the product does not simply echo it back as a path either.
            assertThat(attached.get("filename").asText()).isEqualTo("passwd");
            assertThat(get(LEAD, ACME, attached.get("url").asText().replace("/api/v1", ""))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a tester removes evidence they attached themselves")
        void deletesOwn() throws Exception {
            UUID id = attachedId(ENGINEER, EXEC_A, "mine.png");

            MvcResult removed = deleteEvidence(ENGINEER, ACME, EXEC_A, id);

            assertThat(removed.getResponse().getStatus()).isEqualTo(204);
            assertThat(body(get(ENGINEER, ACME, executionPath(RUN, EXEC_A))).get("evidence"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a tester cannot remove a colleague's evidence, and is told the right reason")
        void refusesSomeoneElses() throws Exception {
            UUID id = attachedId(LEAD, EXEC_A, "the-leads.png");

            MvcResult refused = deleteEvidence(ENGINEER, ACME, EXEC_A, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            // The sentence matters as much as the status. "You do not have permission to remove
            // evidence" is FALSE for this caller — they remove their own all day — and false in
            // the direction that sends them to ask an administrator for something they hold.
            assertThat(body(refused).get("detail").asText())
                    .contains("only remove evidence you attached");

            // And the row survives. A refusal that answered 403 and deleted anyway is the failure
            // this test exists for, and the status alone cannot tell the two apart.
            assertThat(body(get(LEAD, ACME, executionPath(RUN, EXEC_A))).get("evidence"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("a test lead removes anyone's evidence — the grant is unqualified above ENGINEER")
        void aLeadRemovesAnyones() throws Exception {
            UUID id = attachedId(ENGINEER, EXEC_A, "the-engineers.png");

            assertThat(deleteEvidence(LEAD, ACME, EXEC_A, id).getResponse().getStatus())
                    .isEqualTo(204);

            // Without this test, the two above pass equally well against a page that refuses
            // EVERYBODY — which would lock every lead in the product out of their own runs, and
            // look exactly like a working OWN gate from the refused side.
        }

        @Test
        @DisplayName("evidence with no recorded author is nobody's own work, and OWN does not cover it")
        void refusesUnattributedForAnOwnGrant() throws Exception {
            UUID id = attachedId(ENGINEER, EXEC_A, "orphan.png");
            // Nothing the API offers can produce this row — created_by is always the caller. Which
            // is precisely why it is the arm no fixture contains and the one most likely to be
            // wrong: in a real installation these are the OLDEST rows, written before authorship
            // was recorded, so getting it wrong reads as a defect in the import rather than in the
            // gate.
            try (Connection connection = appConnection(ACME)) {
                execute(connection,
                        "UPDATE evidence SET created_by = NULL WHERE id = '" + id + "'");
            }

            MvcResult refused = deleteEvidence(ENGINEER, ACME, EXEC_A, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(refused).get("detail").asText())
                    .contains("only remove evidence you attached");

            // The lead is still not blocked by it: an unqualified grant does not consult
            // authorship at all, so a null author is irrelevant rather than disqualifying.
            assertThat(deleteEvidence(LEAD, ACME, EXEC_A, id).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a Viewer's refusal is the ORDINARY one — authorship never replaces the plain gate")
        void aViewerGetsThePlainRefusal() throws Exception {
            UUID id = attachedId(ENGINEER, EXEC_A, "mine.png");

            MvcResult refused = deleteEvidence(VIEWER, ACME, EXEC_A, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(refused).get("detail").asText())
                    .contains("do not have permission")
                    .doesNotContain("only remove evidence you attached");
        }

        @Test
        @DisplayName("removing evidence takes its bytes with it — a hard delete, not a soft one")
        void deleteRemovesTheBytes() throws Exception {
            JsonNode attached = body(attach(ENGINEER, ACME, EXEC_A, "gone.txt", "text/plain",
                    new byte[] {7, 7, 7}, null));
            String url = attached.get("url").asText().replace("/api/v1", "");
            assertThat(get(ENGINEER, ACME, url).getResponse().getStatus()).isEqualTo(200);

            deleteEvidence(ENGINEER, ACME, EXEC_A, UUID.fromString(attached.get("id").asText()));

            // A soft-deleted row pointing at retained bytes is a deletion that did not delete —
            // the wrong answer for the one thing here a customer may need removed for privacy.
            assertThat(get(ENGINEER, ACME, url).getResponse().getStatus()).isEqualTo(404);
        }

        /** Attaches as {@code userId} and returns the new evidence's id. */
        private UUID attachedId(UUID userId, UUID executionId, String filename) throws Exception {
            MvcResult result = attach(userId, ACME, executionId, filename, "image/png",
                    new byte[] {1, 2, 3}, null);
            assertThat(result.getResponse().getStatus())
                    .as("fixture setup must not be the thing that fails")
                    .isEqualTo(201);
            return UUID.fromString(body(result).get("id").asText());
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private MvcResult attach(
            UUID userId, UUID companyId, UUID executionId, String filename, String contentType,
            byte[] bytes, String caption) throws Exception {

        var request = MockMvcRequestBuilders
                .multipart("/api/v1" + executionPath(RUN, executionId) + "/evidence")
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "file", filename, contentType, bytes))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .accept(MediaType.APPLICATION_JSON);
        if (caption != null) {
            request = request.param("caption", caption);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult deleteEvidence(
            UUID userId, UUID companyId, UUID executionId, UUID evidenceId) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1" + executionPath(RUN, executionId) + "/evidence/"
                                + evidenceId)
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

    private MvcResult patch(
            UUID userId, UUID companyId, UUID runId, UUID executionId, String payload,
            String ifMatch) throws Exception {

        var request = MockMvcRequestBuilders
                .patch("/api/v1" + executionPath(runId, executionId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private static String executionPath(UUID runId, UUID executionId) {
        return "/projects/" + ACME_BANKING + "/runs/" + runId + "/executions/" + executionId;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    private static List<String> stepActions(JsonNode execution) {
        List<String> actions = new ArrayList<>();
        execution.get("steps").forEach(step -> actions.add(step.get("action").asText()));
        return actions;
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

    private String resultPayload(UUID statusId, String comment) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusId", statusId == null ? null : statusId.toString());
        payload.put("comment", comment);
        return json.writeValueAsString(payload);
    }

    private String resultWithSteps(UUID statusId, List<Map<String, String>> steps) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusId", statusId.toString());
        payload.put("comment", null);
        payload.put("stepResults", steps);
        return json.writeValueAsString(payload);
    }

    // =====================================================================
    // helpers — cases, authored through the REAL endpoints
    // =====================================================================

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by ExecutionEndpointTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        payload.put("steps", List.of(
                step("Open the refund screen"),
                step("Submit")));
        payload.put("changeComment", null);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
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

    /**
     * Edits a case through the real {@code PATCH /cases/{caseId}}, rewriting its title and the text
     * of the steps it already has.
     *
     * <p>The existing step ids are reused rather than sending new steps: the update endpoint refuses
     * a step id that is not the case's, so a fresh-id payload would fail validation and this test
     * would prove the edit never happened rather than that the pin held.
     */
    private MvcResult editCase(
            UUID userId, UUID companyId, UUID projectId, UUID caseId, UUID suiteId, String title,
            List<String> stepIds) throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        // Required by the update endpoint: a save that omitted it would be a silent move to no
        // folder, so it refuses rather than defaulting.
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it, differently.");
        payload.put("description", "Edited after the run.");
        payload.put("expectedOutcome", "It still works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int index = 0; index < stepIds.size(); index++) {
            Map<String, Object> rewritten = step("Rewritten step " + (index + 1));
            rewritten.put("id", stepIds.get(index));
            steps.add(rewritten);
        }
        payload.put("steps", steps);
        payload.put("changeComment", "Rewritten");

        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private static Map<String, Object> step(String action) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", action);
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        return step;
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
        UUID id = UUID.nameUUIDFromBytes(("execution-endpoint:" + marker).getBytes());
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

    /**
     * Puts back everything a test can write.
     *
     * <p>Deleted in foreign-key order, and through <em>each company's own</em> context: RLS means
     * the other's connection could not see these rows to delete them, which is isolation working
     * rather than a leftover row.
     *
     * <p>The counters are reset too. {@code tcms_seed_company_defaults} is {@code ON CONFLICT DO
     * NOTHING}, correct for provisioning and therefore no reset — without this, every {@code TC-n}
     * and {@code TP-n} assertion would depend on the order the class ran in, which is handoff §19's
     * defect again.
     */
    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM defect_link");
                // evidence would cascade from test_execution below, but attachment would NOT —
                // it is polymorphic and holds no foreign key to anything, so its rows would
                // accumulate across every test in the class and a count assertion would drift.
                // Both are cleared explicitly, evidence first because it references attachment.
                execute(connection, "DELETE FROM evidence");
                execute(connection, "DELETE FROM attachment");
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
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2, "Refunds", "s1.s2");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Mobile", "s4");

            try (var statement = connection.prepareStatement(
                    "UPDATE key_sequence SET next_value = 5 WHERE entity_type = 'SUITE'")) {
                statement.executeUpdate();
            }
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // TS-1 again: keys are per COMPANY, so the collision is the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
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

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, path, name,
                     position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED')
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

    /**
     * The plans, runs and executions, written as raw SQL — there is no write endpoint for any of
     * them, which is exactly the boundary of this slice.
     *
     * <p>The plan's scope is deliberately <strong>overlapping</strong>: the PAYMENTS folder (whose
     * subtree holds caseA), caseB directly, and caseA <em>again</em> directly. Three scope items,
     * two distinct cases — so a count over scope items and a count that forgets the subtree give two
     * different wrong answers, and neither can be mistaken for the right one.
     */
    private void seedPlansRunsAndExecutions() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            plan(connection, PLAN, ACME, ACME_BANKING, "TP-1", 1, "Release 4.3");
            plan(connection, MOBILE_PLAN, ACME, ACME_MOBILE, "TP-2", 2, "Mobile 1.0");

            scopeItem(connection, ACME, PLAN, PAYMENTS, null);
            scopeItem(connection, ACME, PLAN, null, caseB);
            scopeItem(connection, ACME, PLAN, null, caseA);

            run(connection, RUN, ACME, ACME_BANKING, PLAN, "TR-1", 1, "Regression 4.3.0", "4.3.0");
            run(connection, OLD_RUN, ACME, ACME_BANKING, PLAN, "TR-2", 2, "Regression 4.2.0",
                    "4.2.0");
            run(connection, MOBILE_RUN, ACME, ACME_MOBILE, MOBILE_PLAN, "TR-3", 3, "Mobile smoke",
                    null);

            execution(connection, EXEC_A, ACME, ACME_BANKING, RUN, caseA, null);
            execution(connection, EXEC_B, ACME, ACME_BANKING, RUN, caseB,
                    statusIdOf(ACME, ACME_BANKING, "PASSED"));
            execution(connection, EXEC_OLD, ACME, ACME_BANKING, OLD_RUN, caseA, null);
            execution(connection, MOBILE_EXEC, ACME, ACME_MOBILE, MOBILE_RUN, mobileCase, null);
        }
        try (Connection connection = appConnection(UMBRA)) {
            run(connection, UMBRA_RUN, UMBRA, UMBRA_BANKING, null, "TR-1", 1, "Umbra's run", null);
            execution(connection, UMBRA_EXEC, UMBRA, UMBRA_BANKING, UMBRA_RUN, umbraCase, null);
        }
    }

    private void plan(
            Connection connection, UUID id, UUID companyId, UUID projectId, String key, long keySeq,
            String name) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_plan
                    (id, company_id, project_id, key, key_seq, name, description, status,
                     milestone_name, milestone_date, entry_criteria, exit_criteria)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, 'ACTIVE',
                        'GA', DATE '2026-09-01',
                        CAST(? AS jsonb), CAST(? AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, "Seeded by ExecutionEndpointTest.");
            statement.setString(8, "[{\"metric\":\"cases_approved\",\"op\":\">=\",\"value\":100,"
                    + "\"label\":\"All cases approved\"}]");
            statement.setString(9, "[{\"metric\":\"pass_rate\",\"op\":\">=\",\"value\":95,"
                    + "\"label\":\"95% of executed cases pass\"}]");
            statement.executeUpdate();
        }
    }

    private void scopeItem(Connection connection, UUID companyId, UUID planId, UUID suiteId,
            UUID caseId) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_scope_item (id, company_id, plan_id, suite_id, case_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid))
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, planId.toString());
            statement.setString(4, suiteId == null ? null : suiteId.toString());
            statement.setString(5, caseId == null ? null : caseId.toString());
            statement.executeUpdate();
        }
    }

    private void run(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID planId, String key,
            long keySeq, String name, String releaseVersion) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, plan_id, configuration_id, key, key_seq, name,
                     status, release_version, build_number, source, started_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) AND c.is_default),
                        ?, ?, ?, 'IN_PROGRESS', ?, '4471', 'MANUAL', now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, planId == null ? null : planId.toString());
            statement.setString(5, projectId.toString());
            statement.setString(6, key);
            statement.setLong(7, keySeq);
            statement.setString(8, name);
            statement.setString(9, releaseVersion);
            statement.executeUpdate();
        }
    }

    /**
     * One execution, pinned to the case's <strong>v1</strong> — the version {@code POST /cases}
     * wrote with the case itself (§29), which is why these fixtures need no hand-written snapshot.
     */
    private void execution(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID runId, UUID caseId,
            UUID statusId) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     release_version, executed_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        CAST(? AS uuid), '4.3.0',
                        CASE WHEN CAST(? AS uuid) IS NULL THEN NULL ELSE now() END)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, runId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, caseId.toString());
            statement.setString(7, statusId == null ? null : statusId.toString());
            statement.setString(8, statusId == null ? null : statusId.toString());
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back through the app role
    // =====================================================================

    private UUID statusIdOf(UUID companyId, UUID projectId, String kind) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT id FROM case_status WHERE project_id = CAST(? AS uuid) AND kind = ?")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, kind);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must have seeded a %s status", kind)
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID passedStatus() throws SQLException {
        return statusIdOf(ACME, ACME_BANKING, "PASSED");
    }

    private UUID failedStatus() throws SQLException {
        return statusIdOf(ACME, ACME_BANKING, "FAILED");
    }

    /** The stored verdict's KIND, or null — read past the API so a green response cannot fake it. */
    private String statusKindOf(UUID executionId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT s.kind FROM test_execution e
                       LEFT JOIN case_status s ON s.id = e.status_id
                      WHERE e.id = CAST(? AS uuid)
                     """)) {
            statement.setString(1, executionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private long stepResultRowCount(UUID executionId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM step_result WHERE execution_id = CAST(? AS uuid)")) {
            statement.setString(1, executionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private List<String> stepIdsOf(UUID executionId) throws Exception {
        JsonNode execution = body(get(LEAD, ACME, executionPath(RUN, executionId)));
        List<String> ids = new ArrayList<>();
        execution.get("steps").forEach(step -> ids.add(step.get("stepId").asText()));
        return ids;
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

    private void softDeleteCase(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.executeUpdate();
        }
    }
}
