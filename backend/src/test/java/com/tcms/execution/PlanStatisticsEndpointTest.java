package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
import java.time.temporal.ChronoUnit;
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
 * {@code GET /projects/{projectId}/plans/{planId}/statistics} over HTTP.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The two denominators disagreeing.</strong> The plan detail's {@code caseCount} and
 *       this endpoint's {@code counts.total} are two renderings of one scope expansion, on two
 *       screens a click apart. If they drift there is nothing on either screen to identify the wrong
 *       one — the §32 defect, one entity over. Asserted directly against the other endpoint rather
 *       than against a literal.</li>
 *   <li><strong>Double-counting a case sitting in an unfinished run.</strong> Such a case has an
 *       execution row with no verdict, so it contributes an {@code UNTESTED} result <em>and</em>
 *       looks un-executed. Counting both makes {@code total} exceed the scope by roughly the size of
 *       every open run. Found while writing this class, before it ever ran.</li>
 *   <li><strong>The four policies collapsing into one.</strong> The fixture is built so that
 *       {@code LATEST}, {@code WORST}, {@code LATEST_PER_CONFIG} and {@code ALL} give <em>four
 *       different</em> count vectors over the same rows. An implementation that ignored the
 *       parameter would pass any one of these assertions and fails the set. <strong>There were five
 *       until 2026-08-07</strong>, when {@code BEST} was removed (plan §8 #47) — and the fixture
 *       still discriminates without it, because caseA's latest ({@code BLOCKED}) and worst
 *       ({@code FAILED}) were never the same verdict.</li>
 *   <li><strong>Coverage moving with the policy.</strong> It must not: a case is covered or it is
 *       not. Coverage is the figure §6p puts beside the pass rate precisely so the pass rate cannot
 *       be read alone, and a coverage that changed with a dropdown would be worthless for that.</li>
 *   <li><strong>An unevaluated criterion rendering as a failed gate.</strong> {@code met: null} is
 *       "we did not look" and {@code met: false} is "this blocks the release". A plan carries both
 *       here, and the response must distinguish them.</li>
 *   <li><strong>A case added to the plan after the last run deflating the pass rate</strong> instead
 *       of landing in coverage (§6p). {@code caseD} is authored after all four runs exist.</li>
 * </ol>
 *
 * <h2>Why the timestamps are rewritten after the results are recorded</h2>
 *
 * <p>Results are recorded through the <strong>real</strong> {@code PATCH …/executions/{id}}, so this
 * fixture is checked against the writer rather than against SQL nobody else runs. But that writer
 * stamps {@code executed_at} with {@code Instant.now()}, and four runs recorded inside one test
 * method land microseconds apart — which makes every {@code LATEST} assertion a race and every trend
 * ordering a coin flip. So the rows are recorded through the API and then <em>moved in time</em>
 * through SQL, which keeps the write path under test and makes the read path deterministic.
 *
 * <p>Ids are unique to this class — {@code fa…}.
 */
@AutoConfigureMockMvc
@DisplayName("GET /plans/{id}/statistics — where a plan stands, under each aggregation policy")
class PlanStatisticsEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("fa000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("fa000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("fa001000-0000-4000-8000-000000000001");
    private static final UUID ACME_MOBILE = UUID.fromString("fa001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("fa002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("fa003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("fa003000-0000-4000-8000-000000000002");
    private static final UUID VIEWER = UUID.fromString("fa003000-0000-4000-8000-000000000003");
    private static final UUID NO_GRANT = UUID.fromString("fa003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("fa003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("fa004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("fa004000-0000-4000-8000-000000000002");
    private static final UUID SPARE = UUID.fromString("fa004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("fa004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("fa004000-0000-4000-8000-000000000005");

    private static final UUID SECOND_CONFIG = UUID.fromString("fa005000-0000-4000-8000-000000000001");

    private static final UUID ABSENT = UUID.fromString("fa00ffff-0000-4000-8000-00000000ffff");

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

    /** In REFUNDS. Failed, then passed, then failed again — the case the policies disagree about. */
    private UUID caseA;
    /** In SPARE. Passed, never touched again in run 2, passed again in run 3. */
    private UUID caseB;
    /** In REFUNDS. In every checklist, recorded in none — the un-verdicted execution row. */
    private UUID caseC;
    /** In REFUNDS, and authored AFTER every run. In scope, with no execution row at all. */
    private UUID caseD;

    /** Scope: the PAYMENTS suite (claiming the `refunds`-tagged cases) plus caseB directly. */
    private UUID plan;
    /** In ACME_MOBILE — reachable to LEAD, and still a 404 from ACME_BANKING's path. */
    private UUID mobilePlan;
    /** Scoped, never run. The "not executed yet" branch. */
    private UUID untouchedPlan;
    /** No scope at all, and one exit criterion — the plan-list row's two null branches (005 S5). */
    private UUID emptyScopePlan;

    private UUID defaultConfiguration;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ps-acme", "Acme", now);
            company(UMBRA, "ps-umbra", "Umbra", now);

            activeUser(LEAD, "lead@ps-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@ps-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@ps-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@ps-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@ps-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "PSAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "PSAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("ps-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("ps-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("ps-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("ps-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "PSUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("ps-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", REFUNDS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", SPARE);
        caseC = createCase(LEAD, ACME, ACME_BANKING, "Refund a disputed charge", REFUNDS);

        // What PAYMENTS' tag rule claims. caseB is in SPARE and untagged: it reaches the plan
        // through the explicit caseIds arm, which is what keeps the two arms distinguishable.
        tagCase(caseA, "refunds");
        tagCase(caseC, "refunds");

        plan = createPlan(ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of(caseB));
        mobilePlan = createPlan(ACME_MOBILE, "Mobile 1.0", List.of(MOBILE_SUITE), List.of());
        untouchedPlan = createPlan(ACME_BANKING, "Release 4.4", List.of(PAYMENTS), List.of());
        emptyScopePlan = createPlan(ACME_BANKING, "Nothing chosen yet", List.of(), List.of());
        // Two criteria on the run plan: one this endpoint can evaluate and one it cannot, which is
        // what makes `exitCriteriaMet` a narrowing rather than a copy of `exitCriteria.length`.
        setExitCriteria(plan, "Release 4.3", """
                [{"metric":"coverage","op":">=","value":0},
                 {"metric":"open_critical","op":"<=","value":0}]
                """);

        defaultConfiguration = configurationOf(ACME_BANKING, true);
        secondConfiguration();

        recordTheFixtureRuns();

        // AFTER every run — §6p's "cases added to the plan after the last run appear as not-run".
        // It carries PAYMENTS' tag rule, which the plan covers, so it enters the
        // scope without anybody touching plan_scope_item.
        caseD = createCase(LEAD, ACME, ACME_BANKING, "Refund a partially shipped order", REFUNDS);
        tagCase(caseD, "refunds");
    }

    /**
     * Four runs, five recorded verdicts, and the timestamps that make them orderable.
     *
     * <p>The shape is chosen so that no two policies agree — see the class header. R4 is recorded
     * against nothing, which is the run the trend line must omit rather than plot at zero.
     */
    private void recordTheFixtureRuns() throws Exception {
        UUID r1 = createRun("Nightly 1", defaultConfiguration);
        record(r1, caseA, failedStatus());
        record(r1, caseB, passedStatus());
        moveRunInTime(r1, 3);

        UUID r2 = createRun("Re-run of failures", defaultConfiguration);
        record(r2, caseA, passedStatus());
        moveRunInTime(r2, 2);

        // caseA is now FAILED then PASSED then BLOCKED, which is the point: its latest and its
        // worst are DIFFERENT verdicts, so LATEST and WORST cannot agree about it. Two policies
        // agreeing is how a policy parameter gets ignored without any test noticing. The middle
        // PASSED is kept deliberately even though BEST is gone (#47): it is what makes caseA a case
        // that HAS passed and is still a blocker, which is the property the removal protects.
        UUID r3 = createRun("Second environment", SECOND_CONFIG);
        record(r3, caseA, blockedStatus());
        record(r3, caseB, passedStatus());
        moveRunInTime(r3, 1);

        // Materialised, never recorded. Its three executions are UNTESTED and it has no pass rate.
        createRun("Opened, not started", defaultConfiguration);
    }

    // =====================================================================
    // The scope, and the denominator two screens share
    // =====================================================================

    @Nested
    @DisplayName("The scope — one expansion, and both endpoints must render the same number")
    class Scope {

        /**
         * <strong>The assertion this class was written for.</strong> Not against a literal: a literal
         * agreeing with a wrong implementation is how both screens end up wrong together.
         */
        @Test
        @DisplayName("counts.total equals the plan detail's own caseCount")
        void theTwoDenominatorsAgree() throws Exception {
            int fromDetail = body(get(LEAD, ACME, planPath())).get("caseCount").asInt();
            int fromStatistics = statistics(LEAD, null).get("counts").get("total").asInt();

            assertThat(fromStatistics)
                    .as("the plan header and its statistics page are a click apart; if these "
                            + "drift, nothing on either screen identifies the wrong one")
                    .isEqualTo(fromDetail)
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("a case added after the last run is in scope and not-run — it does not vanish")
        void aLateCaseLandsInCoverage() throws Exception {
            JsonNode stats = statistics(LEAD, null);

            assertThat(keysOf(stats.get("notRun")))
                    .as("caseD has no execution row at all; caseC has one with no verdict. "
                            + "Both are work outstanding and both belong here.")
                    .containsExactly(keyOf(caseC), keyOf(caseD));
        }

        @Test
        @DisplayName("a case sitting in an unfinished run is counted ONCE, not twice")
        void anUnverdictedRowIsNotDoubleCounted() throws Exception {
            JsonNode counts = statistics(LEAD, null).get("counts");

            // caseC has three execution rows and no verdict. Counting it as both an UNTESTED
            // contribution and an un-executed phantom gives total 5 over a scope of 4.
            assertThat(counts.get("total").asInt()).isEqualTo(4);
            assertThat(sumOf(counts))
                    .as("passed + failed + blocked + skipped + notRun must equal total — the "
                            + "arithmetic the SPA's denominator toggle computes from")
                    .isEqualTo(counts.get("total").asInt());
        }

        @Test
        @DisplayName("a plan nobody has run reports its whole scope as not-run, and is NOT stale")
        void anUnstartedPlanIsNotStale() throws Exception {
            JsonNode stats = statistics(LEAD, untouchedPlan, null);

            assertThat(stats.get("counts").get("total").asInt()).isEqualTo(3);
            assertThat(stats.get("counts").get("notRun").asInt()).isEqualTo(3);
            assertThat(stats.get("coveragePercent").asDouble()).isZero();
            assertThat(stats.get("latestExecutionAt").isNull()).isTrue();
            assertThat(stats.get("stale").asBoolean())
                    .as("un-started is not stale; a freshness warning on the one page where "
                            + "freshness is not the problem is noise")
                    .isFalse();
            assertThat(stats.get("trend")).isEmpty();
        }
    }

    // =====================================================================
    // The four policies
    // =====================================================================

    @Nested
    @DisplayName("Aggregation — four policies, four different answers over the same rows")
    class Aggregation {

        /** caseA's latest is R3's BLOCKED; caseB's is R3's PASSED; caseC and caseD are owed. */
        @Test
        @DisplayName("LATEST — each case once, by its most recent result across every run")
        void latest() throws Exception {
            assertThat(countsOf(statistics(LEAD, "LATEST")))
                    .containsExactly(1L, 0L, 1L, 0L, 2L, 4L);
        }

        @Test
        @DisplayName("LATEST is the default — an absent parameter is not a different answer")
        void latestIsTheDefault() throws Exception {
            assertThat(countsOf(statistics(LEAD, null)))
                    .isEqualTo(countsOf(statistics(LEAD, "LATEST")));
            assertThat(statistics(LEAD, null).get("aggregationPolicy").asText())
                    .as("the policy is echoed so a screenshot of this page carries its arithmetic")
                    .isEqualTo("LATEST");
        }

        /** caseA failed in R1, which outranks its later PASSED and BLOCKED. */
        @Test
        @DisplayName("WORST — a case that failed anywhere counts as failed")
        void worst() throws Exception {
            assertThat(countsOf(statistics(LEAD, "WORST")))
                    .containsExactly(1L, 1L, 0L, 0L, 2L, 4L);
        }

        /**
         * <strong>An un-started run must not degrade a plan.</strong> caseB has an unrecorded
         * execution row in two of the four runs. If an unrecorded row could outrank a recorded
         * PASSED, caseB would count as owed a verdict here and the act of opening a run would move
         * the plan's numbers — which is the defect the first run of this class found.
         */
        @Test
        @DisplayName("WORST reads a case's worst VERDICT, not its emptiest execution row")
        void worstIgnoresUnrecordedRows() throws Exception {
            assertThat(countsOf(statistics(LEAD, "WORST")).get(0))
                    .as("caseB passed in every run it was recorded in and sits unrecorded in two "
                            + "others; it is passed, not owed")
                    .isEqualTo(1L);
        }

        /**
         * <strong>The removal, pinned from the outside.</strong>
         *
         * <p>{@code BEST} answered {@code [2, 0, 0, 0, 2, 4]} here until 2026-08-07 — caseA failed,
         * then passed, then was blocked, and {@code BEST} was the one policy that called it green.
         * That is the count vector the plan page can no longer produce, and this test is what says
         * so rather than the value's absence from an enum.
         *
         * <p><strong>400 and not a silent fall back to {@code LATEST}</strong>: a saved URL, a
         * bookmark or a screenshot's query string carrying {@code BEST} must not answer a different
         * question under the same name. The refusal names the four that remain, so a reader fixes it
         * in one attempt.
         */
        @Test
        @DisplayName("BEST is gone — a request for it is refused, not quietly answered as LATEST")
        void bestIsRefused() throws Exception {
            MvcResult refused = raw(LEAD, ACME, planPath() + "/statistics?aggregation=BEST");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            String problem = refused.getResponse().getContentAsString();
            assertThat(problem)
                    .as("the four that remain, so the caller does not have to guess")
                    .contains("LATEST", "LATEST_PER_CONFIG", "WORST", "ALL");
        }

        @Test
        @DisplayName("LATEST_PER_CONFIG — the total EXCEEDS the case count, which is the point")
        void latestPerConfiguration() throws Exception {
            JsonNode counts = statistics(LEAD, "LATEST_PER_CONFIG").get("counts");

            assertThat(counts.get("total").asInt())
                    .as("caseA and caseB were each executed on two configurations, so they "
                            + "contribute twice — a plan passing on one environment and failing "
                            + "on another must not average into one number")
                    .isGreaterThan(4);
            assertThat(sumOf(counts)).isEqualTo(counts.get("total").asInt());
        }

        @Test
        @DisplayName("ALL — every execution counted separately, unrecorded rows included")
        void all() throws Exception {
            JsonNode counts = statistics(LEAD, "ALL").get("counts");

            // Four runs x three cases in each checklist = 12 rows, plus caseD, which was authored
            // after the last run and so has no execution row anywhere.
            assertThat(counts.get("total").asInt()).isEqualTo(13);
            // Five of the twelve carry a verdict: three passed, one failed, one blocked.
            assertThat(counts.get("passed").asInt()
                    + counts.get("failed").asInt()
                    + counts.get("blocked").asInt()).isEqualTo(5);
            assertThat(counts.get("notRun").asInt())
                    .as("seven unrecorded rows, plus the one case no run materialised")
                    .isEqualTo(8);
            assertThat(sumOf(counts)).isEqualTo(13);
        }

        @Test
        @DisplayName("THE cross-check — no two policies give the same count vector")
        void thePoliciesActuallyDiffer() throws Exception {
            List<List<Long>> vectors = new ArrayList<>();
            for (String policy : List.of("LATEST", "WORST", "LATEST_PER_CONFIG", "ALL")) {
                vectors.add(countsOf(statistics(LEAD, policy)));
            }
            assertThat(vectors)
                    .as("all FOUR, which the fixture distinguishes because caseA's latest and "
                            + "worst verdicts are different ones and the two per-execution "
                            + "policies count rows rather than cases. An implementation that "
                            + "ignored the parameter passes any single assertion above and "
                            + "fails this one.")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("coverage does NOT move with the policy — a case is covered or it is not")
        void coverageIsPolicyIndependent() throws Exception {
            List<Double> coverages = new ArrayList<>();
            for (String policy : List.of("LATEST", "WORST", "LATEST_PER_CONFIG", "ALL")) {
                coverages.add(statistics(LEAD, policy).get("coveragePercent").asDouble());
            }
            // caseA and caseB carry verdicts; caseC and caseD do not. Two of four.
            assertThat(coverages).allSatisfy(coverage ->
                    assertThat(coverage).isCloseTo(50.0, within(0.01)));
        }

        @Test
        @DisplayName("an unknown policy is 400 and names the four, not a Java type")
        void anUnknownPolicyIsRefused() throws Exception {
            MvcResult refused = raw(LEAD, ACME, planPath() + "/statistics?aggregation=AVERAGE");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            String problem = refused.getResponse().getContentAsString();
            assertThat(problem).contains("LATEST", "WORST", "ALL");
            assertThat(problem)
                    .as("the rejected value's Java type is an internal detail")
                    .doesNotContain("AggregationPolicy");
        }

        @Test
        @DisplayName("a lower-case policy is accepted — the SPA's URL is a user-editable place")
        void theParameterIsCaseInsensitive() throws Exception {
            assertThat(raw(LEAD, ACME, planPath() + "/statistics?aggregation=worst")
                    .getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("The plan LIST's own statistics — one batched pass, agreeing with this endpoint")
    class ListStatistics {

        /**
         * <strong>The agreement assertion 005 S5 owes.</strong> {@link PlanListStatistics} is a
         * second implementation of the arithmetic in {@link PlanStatisticsService}, forced by the
         * same thing that forces {@code rollupTrend}: a per-row answer for a page cannot be
         * assembled from a per-plan service without running it per row. A duplicated computation
         * the shape of the read makes unavoidable is kept honest by a test or not at all — the
         * device {@code SuiteScopeAgreementTest} and {@code RollupAgreementTest} already use.
         */
        @Test
        @DisplayName("the list's passRate and coverage equal the statistics page under LATEST")
        void theListAgreesWithTheDetail() throws Exception {
            JsonNode detail = statistics(LEAD, null);
            JsonNode counts = detail.get("counts");
            double expectedPassRate = counts.get("passed").asDouble() * 100.0
                    / counts.get("total").asDouble();

            JsonNode row = planRow(plan);

            assertThat(row.get("passRate").asDouble())
                    .as("LATEST is what the list reports, fixed — the list has nowhere to state a "
                            + "policy, so it may not offer a choice of one")
                    .isCloseTo(expectedPassRate, within(1e-9));
            assertThat(row.get("coverage").asDouble())
                    .isCloseTo(detail.get("coveragePercent").asDouble(), within(1e-9));
        }

        @Test
        @DisplayName("a plan nobody has run has a null passRate, and a coverage of 0 — not the same thing")
        void anUnrunPlanHasNoPassRate() throws Exception {
            // Null and not zero: `0` says every case failed. Coverage IS zero, because the
            // denominator exists and nothing is covered — the two nullabilities are different
            // questions and the contract spells both.
            JsonNode row = planRow(untouchedPlan);

            assertThat(row.get("passRate").isNull()).isTrue();
            assertThat(row.get("coverage").asDouble()).isZero();
        }

        @Test
        @DisplayName("an empty scope has a null coverage — the denominator is zero")
        void anEmptyScopeHasNoCoverage() throws Exception {
            JsonNode row = planRow(emptyScopePlan);

            assertThat(row.get("coverage").isNull()).isTrue();
            assertThat(row.get("passRate").isNull()).isTrue();
        }

        @Test
        @DisplayName("exitCriteriaTotal counts what the plan declares; met counts what is TRUE")
        void exitCriteriaAreCountedNotEvaluatedTwice() throws Exception {
            JsonNode detail = statistics(LEAD, null);
            long metOnTheDetail = 0;
            for (JsonNode criterion : detail.get("exitCriteria")) {
                if (criterion.get("met").isBoolean() && criterion.get("met").asBoolean()) {
                    metOnTheDetail++;
                }
            }

            JsonNode row = planRow(plan);

            assertThat(row.get("exitCriteriaTotal").asInt())
                    .isEqualTo(detail.get("exitCriteria").size());
            assertThat(row.get("exitCriteriaMet").asInt())
                    .as("a criterion the endpoint cannot evaluate is NOT met here — \"2 of 3\" has "
                            + "no spelling for \"and one we did not check\", and the detail keeps "
                            + "the three-valued answer")
                    .isEqualTo(Math.toIntExact(metOnTheDetail));
        }

        @Test
        @DisplayName("a plan with no exit criteria reports 0 of 0, never a null")
        void noCriteriaIsZeroOfZero() throws Exception {
            JsonNode row = planRow(emptyScopePlan);

            assertThat(row.get("exitCriteriaMet").asInt()).isZero();
            assertThat(row.get("exitCriteriaTotal").asInt()).isZero();
        }

        private JsonNode planRow(UUID planId) throws Exception {
            JsonNode list = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans"));
            for (JsonNode row : list) {
                if (row.get("id").asText().equals(planId.toString())) {
                    return row;
                }
            }
            throw new AssertionError("plan " + planId + " is not on the list");
        }
    }

    // =====================================================================
    // The breakdowns and the lists
    // =====================================================================

    @Nested
    @DisplayName("Breakdowns — every row against the same denominator")
    class Breakdowns {

        @Test
        @DisplayName("bySuite covers every suite holding an in-scope case, executed or not")
        void bySuiteIsDrivenByTheScope() throws Exception {
            JsonNode bySuite = statistics(LEAD, "LATEST").get("bySuite");

            Map<String, JsonNode> rows = new LinkedHashMap<>();
            bySuite.forEach(row -> rows.put(row.get("suiteName").asText(), row));

            assertThat(rows.keySet())
                    .as("PAYMENTS files no cases of its own — it claims them by tag — and the "
                            + "breakdown groups by where a case is FILED, so every row is REFUNDS")
                    .containsExactlyInAnyOrder("Refunds", "Spare");

            // Refunds: caseA (failed), caseC (owed), caseD (owed).
            assertThat(rows.get("Refunds").get("counts").get("total").asInt()).isEqualTo(3);
            assertThat(rows.get("Refunds").get("counts").get("notRun").asInt()).isEqualTo(2);
            // Spare: caseB, passed.
            assertThat(rows.get("Spare").get("counts").get("passed").asInt()).isEqualTo(1);
            assertThat(rows.get("Spare").get("counts").get("total").asInt()).isEqualTo(1);
        }

        /**
         * <strong>Every configuration that ran must have a row under every policy.</strong> Slicing
         * a globally-resolved set instead of resolving within each configuration puts every
         * {@code LATEST} winner in the newest run, so the default environment vanishes from the
         * table entirely — a plan passing on one and failing on the other, showing one. That is what
         * this looked like before the fix, and it looked like a working screen.
         */
        @Test
        @DisplayName("both configurations have a row under EVERY policy — the matrix is a matrix")
        void neitherEnvironmentDisappears() throws Exception {
            for (String policy : List.of("LATEST", "WORST", "LATEST_PER_CONFIG", "ALL")) {
                JsonNode byConfiguration = statistics(LEAD, policy).get("byConfiguration");

                List<String> names = new ArrayList<>();
                byConfiguration.forEach(row -> names.add(row.get("configuration").asText()));

                assertThat(names)
                        .as("under %s, one environment fell off the screen", policy)
                        .containsExactly("Default", "Safari");
            }
        }

        @Test
        @DisplayName("every byConfiguration row is denominated over the WHOLE scope")
        void configurationRowsAreComparable() throws Exception {
            JsonNode byConfiguration = statistics(LEAD, "LATEST").get("byConfiguration");

            byConfiguration.forEach(row -> assertThat(row.get("counts").get("total").asInt())
                    .as("a configuration that ran four cases and passed them all must not show "
                            + "100%% beside one that ran three hundred")
                    .isEqualTo(4));
        }

        @Test
        @DisplayName("the two environments disagree about caseA, and the matrix shows it")
        void theMatrixSeparatesTheEnvironments() throws Exception {
            Map<String, JsonNode> rows = new LinkedHashMap<>();
            statistics(LEAD, "LATEST").get("byConfiguration")
                    .forEach(row -> rows.put(row.get("configuration").asText(), row));

            // Default ran R1, R2 and R4: caseA's latest verdict there is R2's PASSED.
            assertThat(rows.get("Default").get("counts").get("passed").asInt()).isEqualTo(2);
            assertThat(rows.get("Default").get("counts").get("blocked").asInt()).isZero();
            // Safari ran only R3, where caseA was BLOCKED.
            assertThat(rows.get("Safari").get("counts").get("blocked").asInt())
                    .as("this is the number a single averaged figure would have hidden")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("blockers name the failing AND blocked cases, with the version that ran")
        void blockersAreNamedNotCounted() throws Exception {
            JsonNode blockers = statistics(LEAD, "LATEST").get("blockers");

            assertThat(blockers).hasSize(1);
            assertThat(blockers.get(0).get("caseId").asText())
                    .as("caseA's latest verdict is BLOCKED, which belongs on this list as much "
                            + "as a failure — both are cases standing between here and a release")
                    .isEqualTo(caseA.toString());
            assertThat(blockers.get(0).get("caseVersionNo").asInt()).isEqualTo(1);
            assertThat(blockers.get(0).get("defects")).isEmpty();
        }

        /**
         * <strong>What #47 bought, asserted as a property rather than as an absence.</strong>
         *
         * <p>This test used to say the opposite: under {@code BEST} caseA was green and the blocker
         * list was empty, which was correct behaviour for a policy that should not have existed.
         * caseA failed, later passed, and is currently blocked — the exact case a generous policy
         * clears — and under every policy the product still offers, it stays on the list a release
         * meeting reads.
         *
         * <p>The list is not policy-independent, and this does not claim it is: it is driven by the
         * resolved contributions, so caseA appears as blocked under {@code LATEST} and as failed
         * under {@code WORST}. What no longer exists is a policy under which it appears at all.
         */
        @Test
        @DisplayName("a case that failed and later passed is a blocker under EVERY policy that remains")
        void noPolicyClearsTheBlockerList() throws Exception {
            for (String policy : List.of("LATEST", "WORST", "LATEST_PER_CONFIG", "ALL")) {
                JsonNode blockers = statistics(LEAD, policy).get("blockers");

                List<String> caseIds = new ArrayList<>();
                blockers.forEach(blocker -> caseIds.add(blocker.get("caseId").asText()));

                assertThat(caseIds)
                        .as("under %s, the case standing between here and a release vanished", policy)
                        .contains(caseA.toString());
            }
        }

        @Test
        @DisplayName("the trend has one point per run WITH results, oldest first")
        void trendOmitsTheRunWithNothingRecorded() throws Exception {
            JsonNode trend = statistics(LEAD, "LATEST").get("trend");

            assertThat(trend)
                    .as("the fourth run was opened and never recorded; it has no pass rate, and "
                            + "a point at the bottom of the chart is the strongest possible "
                            + "statement of failure")
                    .hasSize(3);

            List<Double> rates = new ArrayList<>();
            List<String> at = new ArrayList<>();
            trend.forEach(point -> {
                rates.add(point.get("passRate").asDouble());
                at.add(point.get("executedAt").asText());
            });

            // R1: one of two passed. R2: the single re-run passed. R3: one of two passed.
            assertThat(rates).containsExactly(50.0, 100.0, 50.0);
            assertThat(at).isSorted();
        }

        @Test
        @DisplayName("the trend ignores the aggregation policy — a per-run series cannot resolve one")
        void trendIsPolicyIndependent() throws Exception {
            assertThat(statistics(LEAD, "ALL").get("trend").toString())
                    .isEqualTo(statistics(LEAD, "WORST").get("trend").toString());
        }
    }

    // =====================================================================
    // Exit criteria
    // =====================================================================

    @Nested
    @DisplayName("Exit criteria — evaluated where the numbers allow, null where they do not")
    class ExitCriteria {

        @BeforeEach
        void writeCriteria() throws SQLException {
            setExitCriteria(plan, """
                    [{"metric":"pass_rate","op":">=","value":95,"label":"Pass rate at least 95%"},
                     {"metric":"coverage","op":">=","value":100,"label":"Every case executed"},
                     {"metric":"open_critical","op":"=","value":0,"label":"No open Critical defects"}]
                    """);
        }

        @Test
        @DisplayName("pass_rate and coverage are evaluated against the in-scope denominator")
        void theTwoAnswerableMetricsAreEvaluated() throws Exception {
            Map<String, JsonNode> criteria = criteriaOf(statistics(LEAD, "LATEST"));

            // One passed of four in scope. NOT one of two executed — the criteria panel states its
            // denominator precisely because it is the stricter one.
            assertThat(criteria.get("pass_rate").get("actual").asDouble()).isEqualTo(25.0);
            assertThat(criteria.get("pass_rate").get("met").asBoolean()).isFalse();

            assertThat(criteria.get("coverage").get("actual").asDouble()).isEqualTo(50.0);
            assertThat(criteria.get("coverage").get("met").asBoolean()).isFalse();
        }

        /** <strong>null is not false.</strong> The distinction this endpoint made reachable. */
        @Test
        @DisplayName("open_critical is NOT evaluated — met is null, which is not 'unmet'")
        void anUnanswerableMetricStaysNull() throws Exception {
            JsonNode criterion = criteriaOf(statistics(LEAD, "LATEST")).get("open_critical");

            assertThat(criterion.get("met").isNull())
                    .as("false would say the gate was checked and failed; nothing in the product "
                            + "knows what an open Critical defect is until the M4 issue tracker")
                    .isTrue();
            assertThat(criterion.get("actual").isNull()).isTrue();
        }

        /**
         * <p>Asserted against the response's <em>own</em> counts rather than a literal, which is
         * both stronger and what the claim actually is: the gate reads the numbers printed above
         * it. A literal would pass an implementation that computed the criterion from a different
         * denominator that happened to land on the same figure.
         *
         * <p>{@code ALL} rather than {@code WORST} since #47 removed {@code BEST}: under
         * {@code LATEST} and {@code WORST} caseA is un-passed either way, so both give 25% and
         * neither would show movement. {@code ALL} counts executions — thirteen rows, three of them
         * passed — so the same gate reads a different number under a different dropdown, which is
         * the whole point.
         */
        @Test
        @DisplayName("the criteria move with the policy, as the counts above them do")
        void criteriaFollowTheDisplayedCounts() throws Exception {
            JsonNode latest = statistics(LEAD, "LATEST");
            JsonNode all = statistics(LEAD, "ALL");

            double allRate = criteriaOf(all).get("pass_rate").get("actual").asDouble();

            // The tolerance is 0.05 and not 0.01 because `actual` is rounded to one decimal for
            // display — three of thirteen is 23.0769… and the response says 23.1. That rounding is
            // the endpoint's, it is deliberate, and a test asserting the unrounded value would be
            // asserting against a number no screen ever shows. It still discriminates by a wide
            // margin: LATEST answers 25.0 on the same fixture.
            assertThat(allRate)
                    .as("the gate is computed from the counts on the same screen, not from a "
                            + "denominator only the criteria panel knows about")
                    .isCloseTo(100.0 * all.get("counts").get("passed").asDouble()
                            / all.get("counts").get("total").asDouble(), within(0.05));
            assertThat(allRate)
                    .as("a criterion pinned to one policy while the counts above it followed "
                            + "another would be the contradiction this asserts against")
                    .isNotEqualTo(criteriaOf(latest).get("pass_rate").get("actual").asDouble());
        }

        @Test
        @DisplayName("a criterion with an unparseable operator is not evaluated rather than failed")
        void anUnknownOperatorIsNotAFailedGate() throws Exception {
            setExitCriteria(plan,
                    "[{\"metric\":\"pass_rate\",\"op\":\"=>\",\"value\":95,\"label\":\"Typo\"}]");

            assertThat(criteriaOf(statistics(LEAD, "LATEST")).get("pass_rate").get("met").isNull())
                    .as("'=>' is a typo in a text field, not a release blocker")
                    .isTrue();
        }

        @Test
        @DisplayName("the plan DETAIL still serves criteria unevaluated — it computes nothing")
        void thePlanDetailDoesNotEvaluate() throws Exception {
            JsonNode criteria = body(get(LEAD, ACME, planPath())).get("exitCriteria");

            assertThat(criteria).isNotEmpty();
            criteria.forEach(criterion -> assertThat(criterion.get("met").isNull())
                    .as("a plan list resolving every plan's results would be this endpoint run "
                            + "once per row")
                    .isTrue());
        }
    }

    // =====================================================================
    // Freshness
    // =====================================================================

    @Nested
    @DisplayName("Freshness — the most dangerous screen in the product (§6p)")
    class Freshness {

        @Test
        @DisplayName("a plan executed yesterday is not stale, and reports when")
        void recentIsNotStale() throws Exception {
            JsonNode stats = statistics(LEAD, "LATEST");

            assertThat(stats.get("stale").asBoolean()).isFalse();
            assertThat(stats.get("latestExecutionAt").isNull())
                    .as("the newest of the plan's results, which is R3's")
                    .isFalse();
        }

        @Test
        @DisplayName("nothing executed in a month IS stale")
        void oldIsStale() throws Exception {
            ageEveryExecution(40);

            assertThat(statistics(LEAD, "LATEST").get("stale").asBoolean())
                    .as("94%% from three weeks ago, presented as current, is what this flag exists "
                            + "to stop")
                    .isTrue();
        }
    }

    // =====================================================================
    // The gates
    // =====================================================================

    @Nested
    @DisplayName("The gates — visibility, the project predicate, and the other client")
    class Gates {

        @Test
        @DisplayName("a Viewer reads statistics — this is a read, gated on visibility alone")
        void aViewerReads() throws Exception {
            assertThat(raw(VIEWER, ACME, planPath() + "/statistics").getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("no grant is 404; the other client is 404; an absent plan is 404")
        void unreachableTargetsAre404() throws Exception {
            assertThat(raw(NO_GRANT, ACME, planPath() + "/statistics")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(raw(OUTSIDER, UMBRA, planPath() + "/statistics")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(raw(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/plans/" + ABSENT + "/statistics")
                    .getResponse().getStatus()).isEqualTo(404);
        }

        /**
         * The second gate, and the one a cross-company suite structurally cannot catch: both rows
         * are in ACME and LEAD holds a grant on both projects.
         */
        @Test
        @DisplayName("a plan from a SIBLING project is 404 through this project's path")
        void theProjectPredicateHolds() throws Exception {
            assertThat(raw(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/plans/" + mobilePlan + "/statistics")
                    .getResponse().getStatus())
                    .as("LEAD can see both projects, so only the query's project predicate "
                            + "refuses this")
                    .isEqualTo(404);

            assertThat(raw(LEAD, ACME,
                    "/projects/" + ACME_MOBILE + "/plans/" + mobilePlan + "/statistics")
                    .getResponse().getStatus())
                    .as("and it is reachable through its own project, so the 404 above is the "
                            + "predicate and not a broken fixture")
                    .isEqualTo(200);
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private String planPath() {
        return "/projects/" + ACME_BANKING + "/plans/" + plan;
    }

    private JsonNode statistics(UUID userId, String policy) throws Exception {
        return statistics(userId, plan, policy);
    }

    private JsonNode statistics(UUID userId, UUID planId, String policy) throws Exception {
        String path = "/projects/" + ACME_BANKING + "/plans/" + planId + "/statistics"
                + (policy == null ? "" : "?aggregation=" + policy);
        MvcResult result = raw(userId, ACME, path);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(result);
    }

    private MvcResult raw(UUID userId, UUID companyId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1" + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, String path) throws Exception {
        MvcResult result = raw(userId, companyId, path);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString()).isEqualTo(200);
        return result;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    /** The count vector in the contract's own order, so an assertion reads like the wire shape. */
    private static List<Long> countsOf(JsonNode statistics) {
        JsonNode counts = statistics.get("counts");
        return List.of(
                counts.get("passed").asLong(),
                counts.get("failed").asLong(),
                counts.get("blocked").asLong(),
                counts.get("skipped").asLong(),
                counts.get("notRun").asLong(),
                counts.get("total").asLong());
    }

    private static long sumOf(JsonNode counts) {
        return counts.get("passed").asLong()
                + counts.get("failed").asLong()
                + counts.get("blocked").asLong()
                + counts.get("skipped").asLong()
                + counts.get("notRun").asLong();
    }

    private static Map<String, JsonNode> criteriaOf(JsonNode statistics) {
        Map<String, JsonNode> byMetric = new LinkedHashMap<>();
        statistics.get("exitCriteria").forEach(
                criterion -> byMetric.put(criterion.get("metric").asText(), criterion));
        return byMetric;
    }

    private static List<String> keysOf(JsonNode array) {
        List<String> keys = new ArrayList<>();
        array.forEach(entry -> keys.add(entry.get("key").asText()));
        return keys;
    }

    private String keyOf(UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT key FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    // =====================================================================
    // helpers — fixture, authored through the REAL endpoints
    // =====================================================================

    private UUID createPlan(UUID projectId, String name, List<UUID> suiteIds, List<UUID> caseIds)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", null);
        payload.put("milestoneName", null);
        payload.put("milestoneDate", null);
        payload.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());

        return UUID.fromString(created(
                "/api/v1/projects/" + projectId + "/plans", LEAD, ACME, payload).get("id").asText());
    }

    /**
     * Sets a plan's exit criteria through the real {@code PATCH}, so the fixture exercises the one
     * writer either JSONB column has — {@code PlanCriteria}, which reads them back too.
     */
    private void setExitCriteria(UUID planId, String name, String criteria) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        // `name` is required on the PATCH body (PlanEdit) and every other field is leave-alone, so
        // this is the smallest edit that sets the criteria and touches nothing else.
        payload.put("name", name);
        payload.put("exitCriteria", json.readTree(criteria));

        MvcResult saved = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(saved.getResponse().getStatus())
                .as("the fixture depends on the real plan editor; %s",
                        saved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private UUID createRun(String name, UUID configurationId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", plan.toString());
        payload.put("configurationId", configurationId.toString());
        payload.put("releaseVersion", null);
        payload.put("buildNumber", null);
        payload.put("caseIds", List.of());
        payload.put("assigneeId", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + ACME_BANKING + "/runs", LEAD, ACME, payload)
                .get("id").asText());
    }

    /** Records a verdict through the real PATCH, so the fixture exercises the writer. */
    private void record(UUID runId, UUID caseId, UUID statusId) throws Exception {
        UUID executionId = executionOf(runId, caseId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusId", statusId.toString());
        payload.put("comment", null);

        MvcResult recorded = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/runs/" + runId
                                + "/executions/" + executionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ENGINEER, ACME))
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(recorded.getResponse().getStatus())
                .as("the fixture depends on the real result endpoint; %s",
                        recorded.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by PlanStatisticsEndpointTest.");
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
        payload.put("changeComment", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + projectId + "/cases", userId, companyId, payload)
                .get("id").asText());
    }

    private JsonNode created(String path, UUID userId, UUID companyId, Map<String, Object> payload)
            throws Exception {

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture depends on the real endpoint at %s; %s",
                        path, result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result);
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
        UUID id = UUID.nameUUIDFromBytes(("plan-statistics:" + marker).getBytes());
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
                execute(connection, "DELETE FROM configuration WHERE NOT is_default");
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

    /** A second environment, so the per-configuration matrix has something to be a matrix of. */
    private void secondConfiguration() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO configuration (id, company_id, project_id, name, is_default,
                                                attributes)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'Safari', false,
                             CAST('{"browser":"Safari"}' AS jsonb))
                     ON CONFLICT (id) DO NOTHING
                     """)) {
            statement.setString(1, SECOND_CONFIG.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back, and moving them in time
    // =====================================================================

    /**
     * Moves a run's recorded results {@code days} into the past.
     *
     * <p>See the class header: recorded through the API for realism, then displaced through SQL for
     * determinism. Only rows that carry a verdict are moved — an unrecorded execution has no
     * {@code executed_at} and must keep not having one, or {@code coverage} would start counting it.
     */
    private void moveRunInTime(UUID runId, int days) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_execution SET executed_at = ? "
                             + "WHERE run_id = CAST(? AS uuid) AND executed_at IS NOT NULL")) {
            statement.setObject(1, Instant.now().minus(days, ChronoUnit.DAYS)
                    .atOffset(java.time.ZoneOffset.UTC));
            statement.setString(2, runId.toString());
            statement.executeUpdate();
        }
    }

    private void ageEveryExecution(int days) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_execution SET executed_at = ? WHERE executed_at IS NOT NULL")) {
            statement.setObject(1, Instant.now().minus(days, ChronoUnit.DAYS)
                    .atOffset(java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void setExitCriteria(UUID planId, String criteria) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_plan SET exit_criteria = CAST(? AS jsonb) "
                             + "WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, criteria);
            statement.setString(2, planId.toString());
            statement.executeUpdate();
        }
    }

    private UUID executionOf(UUID runId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM test_execution WHERE run_id = CAST(? AS uuid) "
                             + "AND case_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.setString(2, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the run's checklist must hold this case, or the fixture is wrong "
                                + "before any assertion runs")
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID configurationOf(UUID projectId, boolean isDefault) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM configuration WHERE project_id = CAST(? AS uuid) "
                             + "AND is_default = ?")) {
            statement.setString(1, projectId.toString());
            statement.setBoolean(2, isDefault);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must seed a default configuration").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID statusIdOf(String kind) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM case_status WHERE project_id = CAST(? AS uuid) AND kind = ?")) {
            statement.setString(1, ACME_BANKING.toString());
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
        return statusIdOf("PASSED");
    }

    private UUID failedStatus() throws SQLException {
        return statusIdOf("FAILED");
    }

    private UUID blockedStatus() throws SQLException {
        return statusIdOf("BLOCKED");
    }
}
