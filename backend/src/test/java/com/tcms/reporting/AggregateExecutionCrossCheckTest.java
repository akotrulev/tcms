package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.execution.PlanStatisticsService;
import com.tcms.execution.PlanStatisticsView;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.StatusKind;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>Decision D's guard, and the reason the aggregation policy may be implemented twice.</strong>
 *
 * <p>{@code execution.PlanStatisticsService.resolve} resolves the policy in Java over
 * {@code test_execution}; {@link AggregateExecutionReport} resolves it in SQL over
 * {@code execution_fact}. Two implementations of one rule is what this codebase deletes on sight,
 * and the slice plan tolerates it only with this class attached:
 *
 * <blockquote>the report's per-status counts under each policy must equal
 * {@code GET /plans/{planId}/statistics}'s under the same policy, asserted <em>against each other</em>
 * rather than against literals — §46's stated reason: <em>a literal agreeing with a wrong
 * implementation is how both screens end up wrong together.</em></blockquote>
 *
 * <p>The defect it prevents is a plan page reading 94% and the release report reading 91%, for the
 * same plan, on the same day, with no way to tell which is lying.
 *
 * <h2>The fixture has to discriminate between all four policies or the oracle is a tautology</h2>
 *
 * <p>A plan whose cases each ran once answers identically under every policy, so a comparison over
 * it would pass against a report that ignored the parameter entirely. {@link #theFixtureDiscriminates()}
 * asserts the four give four different pictures <strong>before</strong> any agreement is checked —
 * §96's {@code SuiteScopeAgreementTest} shipped vacuous on its first draft and this is the repair
 * carried forward.
 *
 * <h2>Two asymmetries are asserted rather than avoided</h2>
 *
 * <p>Decision D names the first in advance and {@link Asymmetries} pins both, because a cross-check
 * that quietly arranged for them not to arise would be agreement bought by fixture design:
 *
 * <ol>
 *   <li>{@code PlanStatisticsService} sees <em>unrecorded</em> executions and the fact log
 *       structurally cannot — {@link ExecutionSource}'s projection joins {@code case_status} inner.
 *       So the two agree on recorded results, and the plan's {@code notRun} carries a number this
 *       report has no way to know.</li>
 *   <li>A case in the plan's scope that nobody ever executed is in the plan's denominator and absent
 *       from the fact log altogether — the report's denominator is contributing executions, which
 *       is what its metadata and its narrative both say.</li>
 * </ol>
 *
 * <p>Every execution is given a <strong>distinct</strong> {@code executed_at}. That is deliberate:
 * the {@code execution_id} tiebreak orders differently in Java (signed 64-bit halves) and Postgres
 * (unsigned bytes), so a fixture with tied timestamps would be asserting the coin toss rather than
 * the rule. {@link AggregateExecutionReport}'s header records the divergence and its bound.
 *
 * <p>Ids are prefixed {@code c1} and slugs {@code aer-}. Hex, for the reason
 * {@code ExecutionFactRefreshTest}'s header records.
 */
@DisplayName("Aggregate execution report — agrees with the plan page under every policy")
class AggregateExecutionCrossCheckTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("c1001000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("c100a000-0000-4000-8000-000000000001");

    private static final UUID BILLING = UUID.fromString("c1002000-0000-4000-8000-000000000001");
    private static final UUID CHECKOUT = UUID.fromString("c1002000-0000-4000-8000-000000000002");

    private static final UUID PLAN = UUID.fromString("c1005000-0000-4000-8000-000000000001");

    private static final UUID RUN_ONE = UUID.fromString("c1003000-0000-4000-8000-000000000001");
    private static final UUID RUN_TWO = UUID.fromString("c1003000-0000-4000-8000-000000000002");

    /** Failed, then passed — {@code LATEST} and {@code WORST} must disagree about this one. */
    private static final UUID RECOVERED = UUID.fromString("c1004000-0000-4000-8000-000000000001");
    /** Passed, then failed — the mirror of RECOVERED, so no two policies rank the pair alike. */
    private static final UUID REGRESSED = UUID.fromString("c1004000-0000-4000-8000-000000000002");
    /** Blocked once. Neither a pass nor a failure under any policy. */
    private static final UUID BLOCKED = UUID.fromString("c1004000-0000-4000-8000-000000000003");
    /** Passed once and never retried — the row every policy must agree about. */
    private static final UUID STEADY = UUID.fromString("c1004000-0000-4000-8000-000000000004");
    /**
     * Blocked, then passed — and it is here because <strong>mutation testing found the fixture
     * vacuous without it</strong>.
     *
     * <p>{@link #RECOVERED} and {@link #REGRESSED} are exact mirrors (fail→pass and pass→fail), so
     * reversing the direction of time swaps <em>which</em> case is passing and leaves every total
     * identical. A mutant whose {@code LATEST} ordering read {@code ASC} — picking each case's
     * <em>oldest</em> result, the whole meaning of the policy inverted — passed the entire class.
     *
     * <p>This case breaks the symmetry: {@code BLOCKED → PASSED} moves a row between two different
     * buckets, so oldest-wins and newest-wins produce different <em>counts</em> rather than the same
     * counts about different cases.
     */
    private static final UUID UNBLOCKED = UUID.fromString("c1004000-0000-4000-8000-000000000006");

    /** Distinct to the second, so no tiebreak is ever consulted. See the class header. */
    private static final Instant T1 = Instant.parse("2026-03-01T09:00:01Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:02Z");
    private static final Instant T3 = Instant.parse("2026-03-01T09:00:03Z");
    private static final Instant T4 = Instant.parse("2026-03-02T09:00:04Z");
    private static final Instant T5 = Instant.parse("2026-03-02T09:00:05Z");
    private static final Instant T6 = Instant.parse("2026-03-02T09:00:06Z");
    private static final Instant T7 = Instant.parse("2026-03-02T09:00:07Z");
    private static final Instant T8 = Instant.parse("2026-03-02T09:00:08Z");

    @Autowired
    private AggregateExecutionReport report;

    @Autowired
    private PlanStatisticsService statistics;

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
            companies.save(Company.provision(ACME, "aer-acme", "Acme", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "AERC", "Acme Product", null, now));
            }
        }

        try (Connection connection = appConnection(ACME)) {
            clear(connection);
            seedCompanyDefaults(connection, ACME);
            seedProjectDefaults(connection, ACME, PROJECT);
            grantEverything(connection);

            suite(connection, BILLING, "AS-1", 1, "Billing");
            suite(connection, CHECKOUT, "AS-2", 2, "Checkout");

            testCase(connection, RECOVERED, BILLING, "TC-1", 1);
            testCase(connection, REGRESSED, BILLING, "TC-2", 2);
            testCase(connection, BLOCKED, CHECKOUT, "TC-3", 3);
            testCase(connection, STEADY, CHECKOUT, "TC-4", 4);
            testCase(connection, UNBLOCKED, CHECKOUT, "TC-6", 6);

            plan(connection);
            scopeCase(connection, RECOVERED);
            scopeCase(connection, REGRESSED);
            scopeCase(connection, BLOCKED);
            scopeCase(connection, STEADY);
            scopeCase(connection, UNBLOCKED);

            run(connection, RUN_ONE, "TR-1", 1);
            run(connection, RUN_TWO, "TR-2", 2);

            // Eight recorded executions over five cases. Every scoped case has at least one, so the
            // plan's `notRun` is zero here and the two denominators coincide — the asymmetry that
            // makes them differ gets its own test rather than being smuggled into this fixture.
            execution(connection, RUN_ONE, RECOVERED, "Failed", T1);
            execution(connection, RUN_ONE, REGRESSED, "Passed", T2);
            execution(connection, RUN_ONE, BLOCKED, "Blocked", T3);
            execution(connection, RUN_TWO, RECOVERED, "Passed", T4);
            execution(connection, RUN_TWO, REGRESSED, "Failed", T5);
            execution(connection, RUN_ONE, STEADY, "Passed", T6);
            // Asymmetric on purpose — see UNBLOCKED. Without this pair the class is blind to the
            // direction of time, which a mutant proved before this line existed.
            execution(connection, RUN_ONE, UNBLOCKED, "Blocked", T7);
            execution(connection, RUN_TWO, UNBLOCKED, "Passed", T8);
        }

        refresh();
    }

    // =====================================================================

    @Test
    @DisplayName("the fixture discriminates: four policies, four different pictures")
    void theFixtureDiscriminates() {
        Map<String, Long> latest = inTransaction(() -> statusCounts(policy("LATEST")));
        Map<String, Long> worst = inTransaction(() -> statusCounts(policy("WORST")));
        Map<String, Long> perConfig = inTransaction(() -> statusCounts(policy("LATEST_PER_CONFIG")));
        Map<String, Long> all = inTransaction(() -> statusCounts(policy("ALL")));

        // Without this the agreement below would pass against a report that ignored the policy
        // parameter entirely, which is exactly the defect decision D is protecting against.
        // BEST was the fourth picture here until 2026-08-07 (plan §8 #47); LATEST_PER_CONFIG takes
        // its place in the discrimination, and RECOVERED/REGRESSED still make LATEST and WORST
        // rank the same two cases in opposite orders.
        assertThat(latest).as("LATEST vs WORST").isNotEqualTo(worst);
        assertThat(all).as("ALL counts every execution, not every case").isNotEqualTo(latest);
        assertThat(all).as("ALL vs WORST").isNotEqualTo(worst);
        assertThat(perConfig).as("LATEST_PER_CONFIG counts per configuration").isNotEqualTo(all);

        assertThat(all.values().stream().mapToLong(Long::longValue).sum())
                .as("ALL contributes one row per recorded execution")
                .isEqualTo(8L);
        assertThat(latest.values().stream().mapToLong(Long::longValue).sum())
                .as("LATEST contributes one row per case")
                .isEqualTo(5L);
    }

    /**
     * The cross-check itself, over every policy the contract offers.
     *
     * <p>Asserted <em>against each other</em> and never against literals. The numbers this fixture
     * happens to produce are checked for discrimination above and are deliberately not restated
     * here — a literal agreeing with a wrong implementation is how both screens end up wrong
     * together.
     */
    @ParameterizedTest
    @EnumSource(AggregationPolicy.class)
    @DisplayName("the report's per-status counts equal the plan page's, policy by policy")
    void theReportAgreesWithThePlanPage(AggregationPolicy policy) {
        PlanStatisticsView plan = inTransaction(() -> plan(policy));
        Map<String, Long> reported = inTransaction(() -> statusCounts(policy));

        assertThat(reported.getOrDefault("PASSED", 0L))
                .as("passed, under %s", policy).isEqualTo(plan.counts().passed());
        assertThat(reported.getOrDefault("FAILED", 0L))
                .as("failed, under %s", policy).isEqualTo(plan.counts().failed());
        assertThat(reported.getOrDefault("BLOCKED", 0L))
                .as("blocked, under %s", policy).isEqualTo(plan.counts().blocked());
        assertThat(reported.getOrDefault("SKIPPED", 0L))
                .as("skipped, under %s", policy).isEqualTo(plan.counts().skipped());

        // The non-verdict kinds are the plan's `notRun`, and over this fixture both are zero. The
        // test that makes them differ on purpose is in Asymmetries below.
        long nonVerdicts = reported.getOrDefault("UNTESTED", 0L)
                + reported.getOrDefault("RETEST", 0L)
                + reported.getOrDefault("WIP", 0L);
        assertThat(nonVerdicts)
                .as("not-run, under %s", policy).isEqualTo(plan.counts().notRun());
    }

    /**
     * The severity ladder names every {@link StatusKind}, so nothing can fall through to the
     * {@code ELSE}.
     *
     * <p><strong>This class was named as the thing that pins it and did not pin it.</strong>
     * {@code AggregateExecutionReport.WORST_FIRST}'s javadoc said it was *"pinned by
     * ~~AggregateExecutionPolicyTest~~ against {@code StatusKind}'s own value set"* — a class that
     * has never existed, found by §206's rule reading {@code {@code}} citations. Everything else in
     * this file compares two implementations of one rule; this compares one implementation against
     * the vocabulary it claims to cover, which nothing did.
     *
     * <p>The failure it prevents is the one the comment describes and is worth restating, because
     * it is silent: {@code WORST_FIRST} becomes a SQL {@code CASE} whose {@code ELSE} is the
     * best-possible rank. A kind added to {@code StatusKind} and forgotten here does not error —
     * it ranks as the <em>best</em> result a case has, so the worst-first callout starts reporting
     * green for the rows that carry it. {@code StatusKind} has grown a member before —
     * {@code WIP}, seeded by {@code V32}.
     *
     * <p>No Spring, no fixture, no transaction: it is two lists. It sits in this class rather than
     * in a new one because this is the class the comment names, and a check nobody can find from
     * the code it guards is the shape §206 exists to end.
     */
    @Test
    @DisplayName("the severity ladder covers every StatusKind — nothing reaches the ELSE")
    void theLadderNamesEveryKind() {
        assertThat(AggregateExecutionReport.WORST_FIRST)
                .as("every StatusKind must be ranked, or it falls through the SQL CASE to the "
                        + "best-possible rank and the worst-first callout under-reports it")
                .containsExactlyInAnyOrder(StatusKind.values());
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("the two asymmetries, asserted rather than arranged away")
    class Asymmetries {

        @Test
        @DisplayName("an unrecorded execution is the plan's not-run and is absent from the fact log")
        void anUnrecordedExecutionIsInvisibleToTheReport() throws SQLException {
            Map<String, Long> before = inTransaction(() -> statusCounts(policy("LATEST")));

            try (Connection connection = appConnection(ACME)) {
                // A run opened over a case nobody has touched: status_id NULL. ExecutionSource's
                // inner join to case_status drops it, so the fact log never sees it at all.
                testCase(connection, UNTOUCHED, CHECKOUT, "TC-5", 5);
                scopeCase(connection, UNTOUCHED);
                execution(connection, RUN_ONE, UNTOUCHED, null, null);
            }
            refresh();

            PlanStatisticsView plan = inTransaction(() -> plan(policy("LATEST")));
            Map<String, Long> after = inTransaction(() -> statusCounts(policy("LATEST")));

            assertThat(plan.counts().notRun())
                    .as("the plan page counts it, because the plan has a scope")
                    .isEqualTo(1L);
            assertThat(after)
                    .as("the report cannot see it, and says so by not moving")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the report's denominator is contributing executions, and it says so")
        void theDenominatorIsNamedInTheModel() {
            ReportModel model = inTransaction(() -> compute(policy("LATEST")));

            assertThat(labels(model))
                    .as("both counts, always — §6e names conflating them as the defect")
                    .contains("Contributing executions", "Distinct cases");
            assertThat(value(model, "Pass rate (of contributing executions)"))
                    .as("the denominator is in the label, not only in a javadoc")
                    .isNotBlank();
            assertThat(resultsNarrative(model))
                    .as("and again where somebody reading the table will meet it")
                    .contains("Cases with no recorded result do not appear here at all");
        }
    }

    // =====================================================================

    private static final UUID UNTOUCHED = UUID.fromString("c1004000-0000-4000-8000-000000000005");

    /** {@code policy} by name, so the two same-named enums do not have to be imported together. */
    private static AggregationPolicy policy(String name) {
        return AggregationPolicy.valueOf(name);
    }

    private PlanStatisticsView plan(AggregationPolicy policy) {
        return statistics
                .statistics(USER, PROJECT, PLAN,
                        com.tcms.execution.AggregationPolicy.valueOf(policy.name()))
                .orElseThrow();
    }

    private ReportModel compute(AggregationPolicy policy) {
        return report.compute(PROJECT, ReportParameters.parse(
                "{\"planIds\":[\"" + PLAN + "\"],\"policy\":\"" + policy.name() + "\"}"));
    }

    /**
     * The Results table as a status → executions map — read out of the rendered model rather than
     * from a private method.
     *
     * <p>That is the stronger reading: the claim being cross-checked is that the two <em>screens</em>
     * agree, so the number this test compares is the one a reader sees in the table.
     */
    private Map<String, Long> statusCounts(AggregationPolicy policy) {
        ReportModel model = compute(policy);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReportModel.Section section : model.sections()) {
            if (!"Results".equals(section.heading()) || section.table() == null) {
                continue;
            }
            for (List<String> row : section.table().rows()) {
                counts.put(row.get(0), Long.parseLong(row.get(1)));
            }
        }
        return counts;
    }

    private static List<String> labels(ReportModel model) {
        return model.metadata().stream().map(ReportModel.Entry::label).toList();
    }

    private static String value(ReportModel model, String label) {
        return model.metadata().stream()
                .filter(entry -> label.equals(entry.label()))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElse("");
    }

    private static String resultsNarrative(ReportModel model) {
        return model.sections().stream()
                .filter(section -> "Results".equals(section.heading()))
                .map(ReportModel.Section::narrative)
                .findFirst()
                .orElse("");
    }

    /** One scheduler tick's fact-log half. The rollups are not involved in this report. */
    private void refresh() {
        try (var ignored = CompanyContext.open(ACME)) {
            facts.refresh();
        }
    }

    /**
     * In a transaction, not merely in a company context — a computation called straight from a test
     * runs outside any transaction, {@code app.current_company} is unset, and RLS answers zero rows.
     * §96's vacuous first draft is the reason this is spelled out.
     */
    private <T> T inTransaction(Supplier<T> work) {
        try (var ignored = CompanyContext.open(ACME)) {
            return transactions.execute(status -> work.get());
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

    private void grantEverything(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user (id, email, canonical_name, status, created_at, updated_at)
                VALUES (CAST(? AS uuid), 'aer@aer.test', 'Aggregate', 'ACTIVE', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, USER.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_membership (company_id, user_id, display_name, status,
                                                invited_at, accepted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), 'Aggregate', 'ACTIVE', now(), now())
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

    private void plan(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_plan
                    (id, company_id, project_id, key, key_seq, name, status, current_version_no,
                     lock_version, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TP-1', 1,
                        'Cross-check fixture plan', 'DRAFT', 1, 0, now(), now())
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
                        'Cross-check fixture run', 'IN_PROGRESS', 'MANUAL',
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
