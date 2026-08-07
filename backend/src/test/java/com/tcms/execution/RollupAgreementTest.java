package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.reporting.ExecutionFactRefreshService;
import com.tcms.reporting.RollupRefreshService;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>The rollup path and the live path must produce the same numbers.</strong> This class is
 * the only thing that says so, and it is the whole safety net under §101 phase 4.
 *
 * <h2>Written as an equality, not as a set of expected figures</h2>
 *
 * <p>{@code PlanStatisticsService}'s own header names the live computation as the specification —
 * <em>"the migration path is to feed the rollup and read it here, with this class as the definition
 * of what the rollup must produce"</em>. So the assertion is <strong>agreement between two
 * computations over one fixture</strong>. Fresh expected numbers would only re-state whatever the
 * new code happens to do, which is the failure mode of every test written after its implementation.
 *
 * <h2>The two defects that made §96's SuiteScopeAgreementTest vacuous on its first draft</h2>
 *
 * <p>Both are repaired here rather than rediscovered. It runs inside a {@link TransactionTemplate},
 * because a seam method called straight from a test runs outside any transaction — so
 * {@code app.current_company} is never set, RLS answers <strong>zero rows</strong>, and the first
 * draft of that class compared an empty list to an empty list and reported agreement. And the
 * fixture is asserted <strong>non-vacuous first</strong>: a plan whose cases each ran once cannot
 * tell "latest" from "whichever", and a suite that files everything it claims cannot tell the union
 * from one column.
 *
 * <h2>There is no direct seam for the live path, so the switch is driven by the POLICY</h2>
 *
 * <p>{@code PlanStatisticsService} chooses its source from the aggregation policy, so the two paths
 * are reached by asking for {@code LATEST} (rollup) and {@code ALL}/{@code WORST} (live). {@code LATEST} and {@code WORST} answer the same thing over a fixture where each case's
 * latest result <em>is</em> its worst — which this fixture deliberately is not, so the comparison
 * below is against {@code LATEST} computed before the rollup was ever refreshed. That is the only
 * way to see the live {@code LATEST} at all, and it is what makes this an oracle rather than a
 * tautology.
 *
 * <p>Ids are prefixed {@code 7f} and slugs {@code rga-}. Hex, for the reason
 * {@code ExecutionFactRefreshTest}'s header records.
 */
@DisplayName("Rollup agreement — the pre-aggregated path answers what the live path answered")
class RollupAgreementTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("7f000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("7f001000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("7f00a000-0000-4000-8000-000000000001");

    private static final UUID TAGGED_SUITE = UUID.fromString("7f002000-0000-4000-8000-000000000001");
    private static final UUID FILED_SUITE = UUID.fromString("7f002000-0000-4000-8000-000000000002");

    private static final UUID PLAN = UUID.fromString("7f005000-0000-4000-8000-000000000001");

    private static final UUID RUN_EARLY = UUID.fromString("7f003000-0000-4000-8000-000000000001");
    private static final UUID RUN_LATE = UUID.fromString("7f003000-0000-4000-8000-000000000002");

    /** Failed early, passed late — so LATEST and WORST disagree, which is what makes this an oracle. */
    private static final UUID CASE_RECOVERED = UUID.fromString("7f004000-0000-4000-8000-000000000001");
    /** Tagged into TAGGED_SUITE and filed nowhere — the suite union's discriminator. */
    private static final UUID CASE_TAGGED = UUID.fromString("7f004000-0000-4000-8000-000000000002");
    /** Filed in FILED_SUITE, passed once. */
    private static final UUID CASE_FILED = UUID.fromString("7f004000-0000-4000-8000-000000000003");
    /** In scope, in a run, never recorded — the checklist row byConfiguration depends on. */
    private static final UUID CASE_PLANNED = UUID.fromString("7f004000-0000-4000-8000-000000000004");

    private static final Instant EARLY = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-03-02T09:00:00Z");

    @Autowired
    private PlanStatisticsService statistics;

    @Autowired
    private SuiteHistoryService history;

    @Autowired
    private RollupRefreshService rollups;

    /**
     * The trend reads {@code execution_fact} on the rollup path, and that table is fed by a
     * different service on the same scheduler tick — so a fixture that refreshed only the rollups
     * would leave the sparkline empty and the disagreement would look like a trend defect.
     */
    @Autowired
    private ExecutionFactRefreshService facts;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        if (companies.findById(ACME).isEmpty()) {
            companies.save(Company.provision(ACME, "rga-acme", "Acme", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "RGAC", "Acme Product", null, now));
            }
        }

        try (Connection connection = appConnection(ACME)) {
            clear(connection);
            seedCompanyDefaults(connection, ACME);
            seedProjectDefaults(connection, ACME, PROJECT);
            grantEverything(connection);

            suite(connection, TAGGED_SUITE, "TS-1", 1, "Smoke");
            suite(connection, FILED_SUITE, "TS-2", 2, "Checkout");

            testCase(connection, CASE_RECOVERED, null, "TC-1", 1);
            testCase(connection, CASE_TAGGED, null, "TC-2", 2);
            testCase(connection, CASE_FILED, FILED_SUITE, "TC-3", 3);
            testCase(connection, CASE_PLANNED, FILED_SUITE, "TC-4", 4);

            tagRule(connection, TAGGED_SUITE, "smoke");
            tag(connection, CASE_TAGGED, "smoke");
            tag(connection, CASE_RECOVERED, "smoke");

            plan(connection);
            scopeCase(connection, CASE_RECOVERED);
            scopeCase(connection, CASE_TAGGED);
            scopeCase(connection, CASE_FILED);
            scopeCase(connection, CASE_PLANNED);

            run(connection, RUN_EARLY, "TR-1", 1);
            run(connection, RUN_LATE, "TR-2", 2);

            execution(connection, RUN_EARLY, CASE_RECOVERED, "Failed", EARLY);
            execution(connection, RUN_LATE, CASE_RECOVERED, "Passed", LATE);
            execution(connection, RUN_EARLY, CASE_TAGGED, "Passed", EARLY);
            execution(connection, RUN_EARLY, CASE_FILED, "Blocked", EARLY);
            execution(connection, RUN_EARLY, CASE_PLANNED, null, null);
        }
    }

    // =====================================================================

    @Test
    @DisplayName("the fixture discriminates: a case that recovered, and a suite that files nothing")
    void theFixtureIsNotVacuous() {
        // Without the first, LATEST and WORST answer identically and the oracle compares a
        // computation to itself. Without the second, the one-column suite arm passes everything.
        assertThat(count("SELECT count(*) FROM test_execution WHERE case_id = CAST(? AS uuid) "
                + "AND status_id IS NOT NULL", CASE_RECOVERED))
                .as("failed early, passed late")
                .isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM test_case WHERE suite_id = CAST(? AS uuid)",
                TAGGED_SUITE))
                .as("a suite defined entirely by a tag rule")
                .isZero();

        PlanStatisticsView live = inTransaction(() -> plan(AggregationPolicy.LATEST));
        PlanStatisticsView worst = inTransaction(() -> plan(AggregationPolicy.WORST));
        assertThat(live.counts().passed())
                .as("LATEST and WORST must not agree by accident over this fixture")
                .isNotEqualTo(worst.counts().passed());
        assertThat(live.counts().total()).isPositive();
    }

    @Test
    @DisplayName("plan statistics: the rollup answers exactly what the live path answered — LATEST")
    void planStatisticsAgreeUnderLatest() {
        // Computed BEFORE any refresh, so the rollup is empty and the service takes the live path —
        // the only way to observe live LATEST at all, since the policy is what selects the source.
        PlanStatisticsView live = inTransaction(() -> plan(AggregationPolicy.LATEST));
        assertThat(live.computedAt()).as("no rollup existed yet").isNull();

        refresh();
        PlanStatisticsView rolled = inTransaction(() -> plan(AggregationPolicy.LATEST));

        assertThat(rolled.computedAt()).as("and now it does").isNotNull();
        assertSameNumbers(live, rolled);
    }

    @Test
    @DisplayName("plan statistics: the same, per configuration")
    void planStatisticsAgreeUnderLatestPerConfig() {
        PlanStatisticsView live = inTransaction(() -> plan(AggregationPolicy.LATEST_PER_CONFIG));
        refresh();
        PlanStatisticsView rolled = inTransaction(() -> plan(AggregationPolicy.LATEST_PER_CONFIG));

        assertSameNumbers(live, rolled);
    }

    @Test
    @DisplayName("the trend from execution_fact matches the trend from the executions")
    void theTwoTrendImplementationsAgree() {
        // The one computation §101 genuinely writes twice: plan_snapshot keeps one row per case and
        // a trend is one point per RUN, so the rollup path reads execution_fact instead. Two
        // implementations the module boundary makes unavoidable, kept honest by a test or not at
        // all — SuiteScopeAgreementTest's device, applied to arithmetic rather than to a predicate.
        PlanStatisticsView live = inTransaction(() -> plan(AggregationPolicy.LATEST));
        refresh();
        PlanStatisticsView rolled = inTransaction(() -> plan(AggregationPolicy.LATEST));

        assertThat(rolled.trend()).as("two runs recorded results").isNotEmpty();
        assertThat(rolled.trend())
                .usingRecursiveComparison()
                .isEqualTo(live.trend());
    }

    @Test
    @DisplayName("WORST and ALL stay live, and say so with a null computedAt")
    void theUnservablePoliciesStayLive() {
        // Asserting the null is what stops a later refactor from quietly serving WORST out of a
        // latest-only table — which would be wrong in the one direction nobody checks, because it
        // would make a plan look BETTER than it is.
        refresh();

        for (AggregationPolicy policy
                : List.of(AggregationPolicy.WORST, AggregationPolicy.ALL)) {
            assertThat(inTransaction(() -> plan(policy)).computedAt())
                    .as("%s needs every execution and cannot come from plan_snapshot", policy)
                    .isNull();
        }
    }

    @Test
    @DisplayName("a cold rollup falls back to live rather than reporting the plan as un-executed")
    void anEmptyRollupDoesNotReportEveryCaseAsNotRun() {
        // THE defect this class was written before the implementation to catch, and it caught it.
        // The first cut chose the source from the aggregation policy alone — which is a claim about
        // what the rollup CAN serve, not about what it HAS been written for. On a plan whose first
        // results landed since the last tick, and on every plan on a deployment where the job has
        // never run, plan_snapshot is empty and the page reported every case as not-run.
        //
        // Not staleness, which this design accepts and declares through computedAt. A plan that has
        // been executed reporting that nothing has been executed, rendering exactly like a plan
        // nobody has started — §47, §53, §72 and §81's shape, reached through a merely cold cache.
        PlanStatisticsView cold = inTransaction(() -> plan(AggregationPolicy.LATEST));

        assertThat(cold.counts().passed())
                .as("two cases passed, and no tick has run")
                .isEqualTo(2L);
        assertThat(cold.computedAt())
                .as("and the response says these numbers are live, which they are")
                .isNull();
    }

    @Test
    @DisplayName("a PARTIALLY cold rollup falls back too — a result recorded since the tick")
    void aSnapshotMissingOneCaseFallsBackToLive() throws SQLException {
        // The half-cold case, which is the ordinary one: the tick ran, and then somebody recorded a
        // result for a case that had none. Reading the rollup would report that case as not-run
        // while the tester is looking at the result they just saved — the single most likely moment
        // for anybody to open this page.
        refresh();
        assertThat(inTransaction(() -> plan(AggregationPolicy.LATEST)).computedAt()).isNotNull();

        try (Connection connection = appConnection(ACME)) {
            execution(connection, RUN_LATE, CASE_PLANNED, "Passed", LATE.plusSeconds(60));
        }

        PlanStatisticsView after = inTransaction(() -> plan(AggregationPolicy.LATEST));
        assertThat(after.counts().passed())
                .as("the result just recorded must be counted")
                .isEqualTo(3L);
        assertThat(after.computedAt())
                .as("served live, and saying so")
                .isNull();
    }

    @Test
    @DisplayName("suite history: the rollup answers exactly what the live path answered")
    void suiteHistoryAgrees() {
        SuiteHistoryView live = inTransaction(() -> suite(TAGGED_SUITE));
        assertThat(live.computedAt()).isNull();
        assertThat(live.entries()).as("the tag-defined suite has history at all").isNotEmpty();

        refresh();
        SuiteHistoryView rolled = inTransaction(() -> suite(TAGGED_SUITE));

        assertThat(rolled.computedAt()).isNotNull();
        assertThat(rolled.entries())
                .usingRecursiveComparison()
                .isEqualTo(live.entries());
    }

    @Test
    @DisplayName("a run recorded after the last tick still appears — the tail, not a stale number")
    void aRunNewerThanTheRollupIsStillInTheHistory() throws SQLException {
        // The regression the tail exists to prevent, and it is worse than staleness: served from
        // the rollup alone the newest run would be ABSENT, not late. The person who has just
        // finished a run is the first to open this page.
        refresh();
        int before = inTransaction(() -> suite(TAGGED_SUITE)).entries().size();

        UUID lateRun = UUID.fromString("7f003000-0000-4000-8000-000000000009");
        try (Connection connection = appConnection(ACME)) {
            run(connection, lateRun, "TR-9", 9);
            execution(connection, lateRun, CASE_TAGGED, "Failed", LATE.plusSeconds(3600));
        }

        assertThat(inTransaction(() -> suite(TAGGED_SUITE)).entries())
                .as("no tick has run since; the run must be visible anyway")
                .hasSize(before + 1);
    }

    // =====================================================================

    /**
     * Everything the two paths must agree on, compared field by field rather than through one
     * recursive equality on the whole view.
     *
     * <p>{@code computedAt} is excluded because it is the one field that <em>must</em> differ — it
     * is the statement about which path answered. Comparing the views wholesale would either fail
     * on it or require ignoring a field by name, and a name-ignored field is one somebody deletes
     * the assertion for later.
     */
    private static void assertSameNumbers(PlanStatisticsView live, PlanStatisticsView rolled) {
        assertThat(rolled.counts()).usingRecursiveComparison().isEqualTo(live.counts());
        assertThat(rolled.coveragePercent()).isEqualTo(live.coveragePercent());
        assertThat(rolled.latestExecutionAt()).isEqualTo(live.latestExecutionAt());
        assertThat(rolled.stale()).isEqualTo(live.stale());
        assertThat(rolled.byConfiguration())
                .as("a configuration whose run was opened and not recorded must not vanish")
                .usingRecursiveComparison()
                .isEqualTo(live.byConfiguration());
        assertThat(rolled.bySuite()).usingRecursiveComparison().isEqualTo(live.bySuite());
        assertThat(rolled.notRun()).usingRecursiveComparison().isEqualTo(live.notRun());
        assertThat(rolled.exitCriteria()).usingRecursiveComparison().isEqualTo(live.exitCriteria());
        assertThat(rolled.blockers().stream().map(TestExecutionSummaryView::id).toList())
                .containsExactlyInAnyOrderElementsOf(
                        live.blockers().stream().map(TestExecutionSummaryView::id).toList());
    }

    private PlanStatisticsView plan(AggregationPolicy policy) {
        return statistics.statistics(USER, PROJECT, PLAN, policy).orElseThrow();
    }

    private SuiteHistoryView suite(UUID suiteId) {
        return history.history(USER, PROJECT, suiteId).orElseThrow();
    }

    /**
     * One scheduler tick, in the scheduler's own order: the fact log first, then the rollups. That
     * ordering is load-bearing rather than tidy — see {@code ExecutionFactScheduler}.
     */
    private void refresh() {
        try (var ignored = CompanyContext.open(ACME)) {
            facts.refresh();
            rollups.refresh();
        }
    }

    /**
     * In a transaction, not merely in a company context. The seam's queries carry no
     * {@code @Transactional} of their own, so without this {@code app.current_company} is unset and
     * RLS answers zero rows — see the class header.
     */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        try (var ignored = CompanyContext.open(ACME)) {
            return transactions.execute(status -> work.get());
        }
    }

    private long count(String sql, UUID parameter) {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter.toString());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ fixture ---

    private void clear(Connection connection) throws SQLException {
        for (String table : List.of("plan_snapshot", "suite_run_summary", "execution_fact",
                "case_health", "step_result", "test_execution", "test_run", "plan_scope_item",
                "test_plan_version", "test_plan", "case_tag", "suite_tag_rule", "suite_case",
                "case_classification", "test_case_version", "test_case", "suite",
                "role_assignment")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
    }

    /**
     * The caller needs a project grant, because both services go through
     * {@code ProjectService.readVisible} first — without one every assertion here would be
     * comparing two empty Optionals and this class would report agreement about nothing.
     */
    private void grantEverything(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user (id, email, canonical_name, status, created_at, updated_at)
                VALUES (CAST(? AS uuid), 'rga@acme.test', 'Agreement', 'ACTIVE', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, USER.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_membership (company_id, user_id, display_name, status,
                                                invited_at, accepted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), 'Agreement', 'ACTIVE', now(), now())
                    ON CONFLICT (company_id, user_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, USER.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO role_assignment (id, company_id, user_id, role_id, project_id,
                                             granted_at)
                SELECT gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), r.id,
                       CAST(? AS uuid), now()
                  FROM role r WHERE r.name = 'Test Lead' AND r.built_in
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, USER.toString());
            statement.setString(3, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void seedCompanyDefaults(Connection connection, UUID companyId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.execute();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void suite(Connection connection, UUID id, String key, long keySeq, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED',
                        NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID suiteId, String key, long keySeq)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now(), NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, suiteId == null ? null : suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void tagRule(Connection connection, UUID suiteId, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite_tag_rule (company_id, suite_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                    ON CONFLICT (company_id, suite_id, tag) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, value);
            statement.executeUpdate();
        }
    }

    private void tag(Connection connection, UUID caseId, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                    ON CONFLICT (case_id, tag) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, value);
            statement.executeUpdate();
        }
    }

    private void plan(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_plan
                    (id, company_id, project_id, key, key_seq, name, status, current_version_no,
                     lock_version, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TP-1', 1,
                        'Agreement fixture plan', 'DRAFT', 1, 0, now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, PLAN.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void scopeCase(Connection connection, UUID caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_scope_item (id, company_id, plan_id, suite_id, case_id, tag,
                                             section_name, ordinal)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), NULL,
                        CAST(? AS uuid), NULL, NULL, 0)
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, PLAN.toString());
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, String key, long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, plan_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Agreement fixture run', 'IN_PROGRESS', 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, PLAN.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID runId, UUID caseId, String statusName,
            Instant executedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS timestamptz), now(), now())
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, PROJECT.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, PROJECT.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedAt == null ? null : executedAt.toString());
            statement.executeUpdate();
        }
    }
}
