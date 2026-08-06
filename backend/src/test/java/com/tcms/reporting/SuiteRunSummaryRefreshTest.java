package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.repository.SuiteMembership;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code suite_run_summary} rollup — the first writer this {@code V1} table has ever had (§101).
 *
 * <h2>What this class is guarding, and why every failure here looks like data</h2>
 *
 * <p>§6p specifies this table as the source of the suite history page's pass percentages, and a
 * percentage computed from a subtly wrong rollup renders exactly like one computed from a right one.
 * Three failures are worth catching and none of them raises anything:
 *
 * <ol>
 *   <li><strong>The one-column membership arm.</strong> {@code c.suite_id = s.id} is what the schema
 *       looks like it wants and resolves to <em>nothing</em> for a suite defined by a tag rule — a
 *       summary of {@code 0 of 0, 100%} over a run that covered forty of that suite's cases. §98
 *       found this between a count and a grid, §99 as an empty checklist; this is the third door.</li>
 *   <li><strong>Membership moving after the run.</strong> The slice's finding. A recompute that
 *       re-derives today's membership rewrites last March's pass rate the day somebody adds a tag,
 *       and both the old number and the new one look equally correct (§6p open question #32).</li>
 *   <li><strong>Counts NOT moving.</strong> The mirror. An amended verdict that the rollup never
 *       learns is a suite history wrong for ever and right-looking.</li>
 * </ol>
 *
 * <p><strong>The fixture's non-vacuity is asserted first and by its own test.</strong> Without a
 * suite that claims a case it does not file, every assertion below is equally true of the defective
 * one-column implementation — §96's device, and the repair that made
 * {@code SuiteScopeAgreementTest} mean anything.
 *
 * <p>Ids are prefixed {@code 5f} and slugs {@code srs-}. Both, not just the ids:
 * {@code CaseHealthRecomputeTest}'s header records renaming one and leaving the other behind, which
 * failed all its tests in the full suite only and pointed the stack trace at the base class. And the
 * prefix is <strong>hex</strong> — {@code ExecutionFactRefreshTest} lost a whole gate run to
 * {@code rf…}, where {@code r} is not a hex digit, so {@code UUID.fromString} threw in the static
 * initialiser and every test failed with {@code NoClassDefFoundError} before one ran (§19's family).
 */
@DisplayName("Suite-run rollup — the counts freeze what the suite held when the run happened")
class SuiteRunSummaryRefreshTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("5f000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("5f000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("5f001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("5f001000-0000-4000-8000-000000000002");

    /**
     * Defined by a tag rule and by nothing else — <strong>zero cases carry its id in
     * {@code suite_id}</strong>. This is the suite the whole class turns on, and
     * {@link Fixture#aTagDefinedSuiteFilesNothing()} is what stops it from silently becoming an
     * ordinary folder.
     */
    private static final UUID TAGGED_SUITE = UUID.fromString("5f002000-0000-4000-8000-000000000001");
    /** An ordinary folder, so the filed arm is exercised too and the union is not just the tag arm. */
    private static final UUID FILED_SUITE = UUID.fromString("5f002000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("5f002000-0000-4000-8000-000000000003");

    private static final UUID RUN_A = UUID.fromString("5f003000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_RUN = UUID.fromString("5f003000-0000-4000-8000-000000000002");

    /** Tagged {@code smoke}, filed in FILED_SUITE, Passed. */
    private static final UUID CASE_PASS_A = UUID.fromString("5f004000-0000-4000-8000-000000000001");
    /** Tagged {@code smoke}, filed nowhere at all (legal since V31), Passed. */
    private static final UUID CASE_PASS_B = UUID.fromString("5f004000-0000-4000-8000-000000000002");
    /** Tagged {@code smoke}, filed nowhere, Failed — the row the amendment test moves. */
    private static final UUID CASE_FAIL = UUID.fromString("5f004000-0000-4000-8000-000000000003");
    /**
     * Ran in RUN_A and carries NO tag when the rollup is first written. The freeze test tags it
     * afterwards; a rollup that re-derives membership would take its total from 3 to 4.
     */
    private static final UUID CASE_FOURTH = UUID.fromString("5f004000-0000-4000-8000-000000000004");
    /**
     * In the run with no verdict at all — §42 writes one empty execution per case at run creation.
     * It must land in {@code not_run} rather than vanish from {@code total}, or a pass percentage
     * over three of forty-seven cases reads as 100% (§6p).
     */
    private static final UUID CASE_UNRUN = UUID.fromString("5f004000-0000-4000-8000-000000000005");
    private static final UUID FOREIGN = UUID.fromString("5f004000-0000-4000-8000-000000000006");

    /**
     * A fixed instant, whole seconds. Nothing in this rollup is time-relative, and whole seconds
     * keep the microsecond truncation §60 paid for from being what a failure is about.
     */
    private static final Instant EXECUTED_AT = Instant.parse("2026-03-01T09:15:00Z");

    @Autowired
    private RollupRefreshService rollups;

    /**
     * The canonical union, borrowed across the module boundary — legal in a test and not in main.
     * {@link Agreement} is what holds {@code ExecutionSource}'s third copy of it honest.
     */
    @Autowired
    private SuiteMembership membership;

    /**
     * {@code SuiteMembership}'s queries carry no {@code @Transactional} of their own, so calling one
     * straight from a test runs it outside any transaction, {@code app.current_company} is never
     * set, and RLS answers <strong>zero rows</strong> — which is fail-closed and silent.
     * {@code SuiteScopeAgreementTest}'s first draft compared an empty list to an empty list and
     * reported agreement (§96).
     */
    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "srs-acme", "Acme", now);
        company(UMBRA, "srs-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "SRAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "SRUM", "Umbra Product", now));

        clearRollupsAndExecutions();

        try (Connection acme = appConnection(ACME)) {
            // Without the project defaults there are no case_status rows and no configuration, so
            // every execution below would carry status_id NULL and the whole class would pass by
            // summarising nothing while looking like it discriminated.
            seedCompanyDefaults(acme, ACME);
            seedProjectDefaults(acme, ACME, ACME_PROJECT);

            suite(acme, TAGGED_SUITE, ACME, ACME_PROJECT, "Smoke", 1);
            suite(acme, FILED_SUITE, ACME, ACME_PROJECT, "Checkout", 2);

            testCase(acme, CASE_PASS_A, ACME, ACME_PROJECT, FILED_SUITE, "TC-1", 1);
            testCase(acme, CASE_PASS_B, ACME, ACME_PROJECT, null, "TC-2", 2);
            testCase(acme, CASE_FAIL, ACME, ACME_PROJECT, null, "TC-3", 3);
            testCase(acme, CASE_FOURTH, ACME, ACME_PROJECT, null, "TC-4", 4);
            testCase(acme, CASE_UNRUN, ACME, ACME_PROJECT, FILED_SUITE, "TC-5", 5);

            // TAGGED_SUITE claims `smoke` and files nothing. Three cases carry the tag; the fourth
            // deliberately does not yet.
            tagRule(acme, ACME, TAGGED_SUITE, "smoke");
            tag(acme, ACME, CASE_PASS_A, "smoke");
            tag(acme, ACME, CASE_PASS_B, "smoke");
            tag(acme, ACME, CASE_FAIL, "smoke");

            run(acme, RUN_A, ACME, ACME_PROJECT, "TR-1", 1);

            execution(acme, ACME, ACME_PROJECT, RUN_A, CASE_PASS_A, "Passed", EXECUTED_AT);
            execution(acme, ACME, ACME_PROJECT, RUN_A, CASE_PASS_B, "Passed", EXECUTED_AT);
            execution(acme, ACME, ACME_PROJECT, RUN_A, CASE_FAIL, "Failed", EXECUTED_AT);
            execution(acme, ACME, ACME_PROJECT, RUN_A, CASE_FOURTH, "Passed", EXECUTED_AT);
            execution(acme, ACME, ACME_PROJECT, RUN_A, CASE_UNRUN, null, null);
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedCompanyDefaults(umbra, UMBRA);
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "Root", 1);
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1);
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, FOREIGN, "Passed", EXECUTED_AT);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The fixture — asserted before anything is asserted over it")
    class Fixture {

        @Test
        @DisplayName("the tag-defined suite files NOTHING, so the one-column arm would answer zero")
        void aTagDefinedSuiteFilesNothing() {
            // Without this, every assertion in this class is equally true of `c.suite_id = s.id`,
            // and the class would report success over the exact implementation it exists to refuse.
            assertThat(count(ACME, "SELECT count(*) FROM test_case WHERE suite_id = CAST(? AS uuid)",
                    TAGGED_SUITE))
                    .as("a suite whose members are entirely tag-matched")
                    .isZero();
            assertThat(count(ACME,
                    "SELECT count(*) FROM suite_tag_rule WHERE suite_id = CAST(? AS uuid)",
                    TAGGED_SUITE))
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("What lands in the rollup")
    class WhatIsSummarised {

        @Test
        @DisplayName("a tag-defined suite is summarised over the runs its cases ran in")
        void summarisesATagDefinedSuite() {
            refreshFor(ACME);

            Map<String, Object> row = summary(ACME, RUN_A, TAGGED_SUITE);
            assertThat(row.get("passed")).isEqualTo(2);
            assertThat(row.get("failed")).isEqualTo(1);
            assertThat(row.get("total"))
                    .as("three cases carry `smoke` and all three ran; the fourth carries no tag yet")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the filed arm is summarised too — the union is not just the tag arm")
        void summarisesAFiledSuite() {
            refreshFor(ACME);

            Map<String, Object> row = summary(ACME, RUN_A, FILED_SUITE);
            assertThat(row.get("passed")).isEqualTo(1);
            assertThat(row.get("total")).isEqualTo(2);
        }

        @Test
        @DisplayName("an execution with no verdict counts as not_run, not as absent from total")
        void anUnrecordedExecutionIsNotRunRatherThanMissing() {
            // The one place this rollup differs from execution_fact, whose inner join to case_status
            // means it structurally cannot hold a planned execution — and whose header therefore
            // says coverage denominators can never come from it. This table IS a coverage
            // denominator: §6p wants "87% of 47 executed — 6 not run", and a not_run that vanished
            // from total would render a run of three cases out of forty-seven as 100%.
            refreshFor(ACME);

            Map<String, Object> row = summary(ACME, RUN_A, FILED_SUITE);
            assertThat(row.get("not_run")).isEqualTo(1);
            assertThat(row.get("total")).isEqualTo(2);
        }

        @Test
        @DisplayName("suite_version_no is the version in force at the run, and defaults to 1")
        void suiteVersionIsCarried() {
            refreshFor(ACME);
            assertThat(summary(ACME, RUN_A, TAGGED_SUITE).get("suite_version_no"))
                    .as("the fixture writes no suite_version rows, and 1 is the column default on "
                            + "every suite row — the honest answer, not a placeholder")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a second refresh writes no second row and reports no insert")
        void isIdempotent() {
            refreshFor(ACME);
            long first = count(ACME, "SELECT count(*) FROM suite_run_summary");

            refreshFor(ACME);

            assertThat(count(ACME, "SELECT count(*) FROM suite_run_summary"))
                    .as("the scheduler reruns every fifteen minutes for ever")
                    .isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("The freeze — a later tag must not rewrite a past run")
    class Freeze {

        @Test
        @DisplayName("tagging a case today does NOT change what a March run covered")
        void aLaterTagDoesNotRewriteAPastRun() throws SQLException {
            // THE slice's assertion. A recompute that re-derives membership on every tick is
            // shorter, is what anybody would write, and moves this suite's March total from 3 to 4
            // the moment somebody labels a case — with both numbers looking equally correct and
            // nobody able to name when it changed. §6p open question #32 settled as-executed
            // membership as the default before this table had a writer; membership_snapshot is the
            // V1 column that exists to hold it.
            refreshFor(ACME);
            assertThat(summary(ACME, RUN_A, TAGGED_SUITE).get("total")).isEqualTo(3);

            tag(ACME, CASE_FOURTH, "smoke");
            refreshFor(ACME);

            assertThat(summary(ACME, RUN_A, TAGGED_SUITE).get("total"))
                    .as("the run covered three cases in March and always will have")
                    .isEqualTo(3);
            assertThat(membershipSnapshot(ACME, RUN_A, TAGGED_SUITE))
                    .doesNotContain(CASE_FOURTH.toString());
        }

        @Test
        @DisplayName("an amended verdict IS corrected, within the frozen membership")
        void anAmendedVerdictIsCorrected() throws SQLException {
            // The mirror of the test above, and the reason the freeze is two statements rather than
            // "never update anything": a result re-recorded from Failed to Passed must move the
            // row, or the rollup preserves the first thing anyone typed for ever.
            refreshFor(ACME);
            assertThat(summary(ACME, RUN_A, TAGGED_SUITE).get("failed")).isEqualTo(1);

            amendResult(ACME, CASE_FAIL, "Passed");
            refreshFor(ACME);

            Map<String, Object> row = summary(ACME, RUN_A, TAGGED_SUITE);
            assertThat(row.get("passed")).isEqualTo(3);
            assertThat(row.get("failed")).isEqualTo(0);
            assertThat(row.get("total")).as("the membership did not move").isEqualTo(3);
        }

        @Test
        @DisplayName("a tick that changes nothing leaves updated_at alone")
        void updatedAtDoesNotMoveWhenNothingChanges() throws SQLException {
            // updated_at is what the contract serves as `computedAt`, so a statement that rewrote
            // every row on every tick would make the freshness field always say "just now" — which
            // is a freshness field that says nothing, on the screen §6p calls the most dangerous in
            // the product. The trailing IS DISTINCT FROM is what holds this.
            refreshFor(ACME);
            Instant first = (Instant) summary(ACME, RUN_A, TAGGED_SUITE).get("updated_at");

            refreshFor(ACME);

            assertThat((Instant) summary(ACME, RUN_A, TAGGED_SUITE).get("updated_at"))
                    .isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("Agreement — the third copy of the membership union")
    class Agreement {

        @Test
        @DisplayName("the frozen snapshot is exactly the repository's union, bounded by the run")
        void theSnapshotAgreesWithTheRepositorysUnion() {
            // ExecutionSource now holds a THIRD copy of `filed ∪ added ∪ tagged`, beside
            // repository.SuiteMembership and execution.CaseFacts#memberOf. All three are forced
            // rather than chosen: ERD §0.2 lets a module read another's tables only through its own
            // seam, so sharing the string would be sharing a dependency ModuleStructureTest
            // refuses. SuiteScopeAgreementTest holds the first two to each other; this holds the
            // third to the canonical one.
            //
            // Asserted through the rollup rather than through a resolution method added for the
            // purpose: a `suiteMemberCaseIds` on ExecutionSource would be production code with no
            // production caller, which is the thing §28's orientation question exists to find. The
            // membership_snapshot column IS the resolution, written by the statement that ships.
            //
            // Bounded by the run on the repository side, because that is the only difference
            // between the two: this rollup resolves membership ∩ "ran in this run", and the
            // repository's union has no run to intersect with.
            refreshFor(ACME);

            List<String> ranInRunA = List.of(
                    CASE_PASS_A.toString(), CASE_PASS_B.toString(), CASE_FAIL.toString());

            assertThat(membershipSnapshot(ACME, RUN_A, TAGGED_SUITE))
                    .as("a resolution missing the tag arm answers the EMPTY set here, and a "
                            + "summary of 0 of 0 renders as 100%% complete")
                    .containsExactlyInAnyOrderElementsOf(ranInRunA);

            List<UUID> viaRepository =
                    inCompany(ACME, () -> membership.caseIdsIn(ACME, ACME_PROJECT, TAGGED_SUITE));
            assertThat(viaRepository)
                    .as("the canonical union agrees, and also holds CASE_FOURTH only after it is "
                            + "tagged — which is exactly what the freeze must not let through")
                    .containsExactlyInAnyOrder(CASE_PASS_A, CASE_PASS_B, CASE_FAIL);
        }
    }

    @Nested
    @DisplayName("Isolation — the statements carry no company predicate and do not need one")
    class Isolation {

        @Test
        @DisplayName("refreshing one company summarises nothing in another")
        void companiesAreIsolated() {
            // FORCE ROW LEVEL SECURITY covers both sides of the INSERT … SELECT. That is the right
            // design and an unasserted one is indistinguishable from a leak.
            refreshFor(ACME);

            assertThat(count(UMBRA, "SELECT count(*) FROM suite_run_summary"))
                    .as("Umbra's run must be invisible to a refresh in Acme's context")
                    .isZero();

            refreshFor(UMBRA);

            assertThat(count(UMBRA, "SELECT count(*) FROM suite_run_summary"))
                    .as("and reachable from its own")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a refresh with no company open refuses loudly rather than writing nothing")
        void noCompanyIsAnError() {
            // With the GUC unset, V4's nullif makes every statement match zero rows — so an
            // unguarded job would log a successful tick having done nothing, for ever.
            assertThatThrownBy(() -> rollups.refresh())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No company context is active");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void refreshFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            rollups.refresh();
        }
    }

    /** One rollup row as a column → value map, so an assertion reads as a sentence about a column. */
    private Map<String, Object> summary(UUID companyId, UUID runId, UUID suiteId) {
        Map<String, Object> row = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT suite_version_no, passed, failed, blocked, skipped, not_run, total,
                               updated_at
                          FROM suite_run_summary
                         WHERE run_id = CAST(? AS uuid) AND suite_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, runId.toString());
            statement.setString(2, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("no suite_run_summary row for run %s / suite %s", runId, suiteId)
                        .isTrue();
                row.put("suite_version_no", rows.getInt("suite_version_no"));
                row.put("passed", rows.getInt("passed"));
                row.put("failed", rows.getInt("failed"));
                row.put("blocked", rows.getInt("blocked"));
                row.put("skipped", rows.getInt("skipped"));
                row.put("not_run", rows.getInt("not_run"));
                row.put("total", rows.getInt("total"));
                row.put("updated_at", rows.getTimestamp("updated_at").toInstant());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private List<String> membershipSnapshot(UUID companyId, UUID runId, UUID suiteId) {
        List<String> ids = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT jsonb_array_elements_text(membership_snapshot) AS case_id
                          FROM suite_run_summary
                         WHERE run_id = CAST(? AS uuid) AND suite_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, runId.toString());
            statement.setString(2, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString("case_id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    private long count(UUID companyId, String sql, UUID... parameters) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i].toString());
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void tag(UUID companyId, UUID caseId, String value) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            tag(connection, companyId, caseId, value);
        }
    }

    private void amendResult(UUID companyId, UUID caseId, String statusName) throws SQLException {
        update(companyId, """
                UPDATE test_execution
                   SET status_id = (SELECT s.id FROM case_status s
                                     WHERE s.project_id = CAST(? AS uuid) AND s.name = ?)
                 WHERE case_id = CAST(? AS uuid)
                """, ACME_PROJECT.toString(), statusName, caseId.toString());
    }

    private void update(UUID companyId, String sql, String... parameters) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private void clearRollupsAndExecutions() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("suite_run_summary", "plan_snapshot", "execution_fact",
                        "case_health", "step_result", "test_execution", "test_run", "case_tag",
                        "suite_tag_rule", "suite_case", "case_classification", "test_case_version",
                        "test_case", "suite")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    /**
     * In a transaction, not merely in a company context — see {@link #transactions}. The context
     * names the company; the transaction is what puts it on the connection.
     */
    private <T> T inCompany(UUID companyId, java.util.function.Supplier<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return transactions.execute(status -> work.get());
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
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

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String name,
            long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED',
                        NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, "TS-" + keySeq);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq) throws SQLException {
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
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            // setString(null) is what makes CAST(? AS uuid) land as NULL rather than as the string
            // "null" — an unfiled case, legal since V31.
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
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void tagRule(Connection connection, UUID companyId, UUID suiteId, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite_tag_rule (company_id, suite_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                    ON CONFLICT (company_id, suite_id, tag) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, value);
            statement.executeUpdate();
        }
    }

    private void tag(Connection connection, UUID companyId, UUID caseId, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                    ON CONFLICT (case_id, tag) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, value);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Suite rollup fixture run', 'IN_PROGRESS', 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, projectId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One execution row.
     *
     * @param statusName null for a planned-but-unrecorded row, which also leaves {@code executed_at}
     *     null: the two move together in {@code TestExecution.record()}, and a fixture that set one
     *     without the other would test a state the application cannot produce.
     */
    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, String statusName, Instant executedAt) throws SQLException {
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
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, projectId.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedAt == null ? null : executedAt.toString());
            statement.executeUpdate();
        }
    }
}
