package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code case_health.failing_repeatedly} — the first health signal derived from the fact log, and
 * the first column of {@code case_health} written by a module other than {@code repository}.
 *
 * <h2>What this class is really guarding</h2>
 *
 * <p>Two things, and the second is the reason R2 exists as its own slice rather than as a line in
 * R1.
 *
 * <ol>
 *   <li><strong>A signal that means something other than what its tile says.</strong>
 *       {@code FAILING_REPEATEDLY} has been in {@code HealthSignal} since §47, labelled
 *       <em>"Failing repeatedly"</em> in the dashboard's "Needs attention" panel and renderable as a
 *       {@code CASE_MAINTENANCE} card on the board since §53 — with <strong>nothing asserting
 *       it</strong>. An unwritten boolean column reads as {@code false}, so every deployment has
 *       been quietly claiming that nothing is failing repeatedly. That is §47's and §53's
 *       empty-panel class exactly: a claim with no evidence behind it renders identically to a true
 *       one. Making it real means the two ways of being wrong now matter — raising it for cases that
 *       are not failing, and not raising it for cases that are.</li>
 *   <li><strong>One module blanking another module's columns.</strong>
 *       {@code repository.ExecutionFacts} states the rule its own {@code ON CONFLICT} follows —
 *       <em>"a signal's owner writes its own column and no others; the row is shared, the columns
 *       are not"</em> — and until this slice nothing exercised it, because {@code repository} was
 *       the only writer. {@link Boundaries#theOtherModulesSignalsSurvive()} is the first test in the
 *       product that can fail if a second writer forgets, and the defect it prevents would present
 *       an hour after this ships, as a bug in the case-health recompute, which would be innocent.</li>
 * </ol>
 *
 * <p>Ids are {@code fe…} — hex, which is not optional (see {@code ExecutionFactRefreshTest}'s header
 * for the run this cost) — and slugs are {@code fr-…}. The window is the shipped default of
 * <strong>3</strong>, read from {@code application.yml} rather than overridden here: a class-level
 * property override forks the Spring context for the whole suite, and the fixture can express every
 * boundary at 3 without one.
 */
@DisplayName("failing_repeatedly — the first signal derived from the fact log")
class FailingRepeatedlyTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("fe000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("fe000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("fe001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("fe001000-0000-4000-8000-000000000002");
    private static final UUID ACME_SUITE = UUID.fromString("fe002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("fe002000-0000-4000-8000-000000000002");

    /**
     * Four runs, and that is forced by the schema rather than chosen.
     *
     * <p>{@code uq_execution_run_case} makes a case appear at most once per run, so "failed three
     * times in a row" is <em>structurally</em> three runs — there is no way to express it otherwise.
     * The fourth exists so a recovery can be recorded after a full window of failures.
     */
    private static final List<UUID> ACME_RUNS = List.of(
            UUID.fromString("fe003000-0000-4000-8000-000000000001"),
            UUID.fromString("fe003000-0000-4000-8000-000000000002"),
            UUID.fromString("fe003000-0000-4000-8000-000000000003"),
            UUID.fromString("fe003000-0000-4000-8000-000000000004"));
    private static final UUID UMBRA_RUN = UUID.fromString("fe003000-0000-4000-8000-000000000009");

    /** Failed the last three. The one case that must raise it. */
    private static final UUID THRICE = UUID.fromString("fe004000-0000-4000-8000-000000000001");
    /** Failed twice and has only ever been run twice — the window is not full. */
    private static final UUID TWICE = UUID.fromString("fe004000-0000-4000-8000-000000000002");
    /** Failed, failed, then PASSED. The newest result breaks the streak. */
    private static final UUID RECOVERED = UUID.fromString("fe004000-0000-4000-8000-000000000003");
    /** Blocked three times running. Nothing failed — the {@code counts_as_pass} trap. */
    private static final UUID BLOCKED = UUID.fromString("fe004000-0000-4000-8000-000000000004");
    /** Passed long ago, then failed the last three. Only the window is considered. */
    private static final UUID DEEP = UUID.fromString("fe004000-0000-4000-8000-000000000005");
    /** Never executed. Gets a row and no signal. */
    private static final UUID UNTOUCHED = UUID.fromString("fe004000-0000-4000-8000-000000000006");
    /** Failed three times, then soft-deleted. Gets no row at all. */
    private static final UUID REMOVED = UUID.fromString("fe004000-0000-4000-8000-000000000007");
    /** Umbra's only case, failing repeatedly, and invisible to Acme. */
    private static final UUID FOREIGN = UUID.fromString("fe004000-0000-4000-8000-000000000008");

    /** Oldest first, so index 0 is the earliest run. Fixed, for {@code ExecutionFact}'s reason. */
    private static final Instant BASE = Instant.parse("2026-02-01T08:00:00Z");

    @Autowired
    private ExecutionFactRefreshService refresh;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "fr-acme", "Acme", now);
        company(UMBRA, "fr-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "FRAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "FRUM", "Umbra Product", now));

        clearEverything();

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            suite(acme, ACME_SUITE, ACME, ACME_PROJECT);
            int key = 0;
            for (UUID caseId : List.of(THRICE, TWICE, RECOVERED, BLOCKED, DEEP, UNTOUCHED)) {
                testCase(acme, caseId, ACME, ACME_PROJECT, ACME_SUITE, "TC-" + (++key), key, false);
            }
            testCase(acme, REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-" + (++key), key, true);
            for (int i = 0; i < ACME_RUNS.size(); i++) {
                run(acme, ACME_RUNS.get(i), ACME, ACME_PROJECT, "TR-" + (i + 1), i + 1);
            }

            // Run index 0 is the oldest. The window is the newest THREE, so what sits in run 0
            // decides nothing for a case executed in all four — which is what DEEP proves.
            results(acme, ACME, ACME_PROJECT, THRICE, null, "Failed", "Failed", "Failed");
            results(acme, ACME, ACME_PROJECT, TWICE, null, null, "Failed", "Failed");
            results(acme, ACME, ACME_PROJECT, RECOVERED, null, "Failed", "Failed", "Passed");
            results(acme, ACME, ACME_PROJECT, BLOCKED, null, "Blocked", "Blocked", "Blocked");
            results(acme, ACME, ACME_PROJECT, DEEP, "Passed", "Failed", "Failed", "Failed");
            results(acme, ACME, ACME_PROJECT, REMOVED, null, "Failed", "Failed", "Failed");
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT);
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, false);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1);
            // One failure is enough here: this case must be invisible either way, and a fixture
            // that needed three runs to prove isolation would be proving something else.
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, FOREIGN, "Failed", BASE);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("What the signal means — and the three readings that are wrong")
    class Meaning {

        @Test
        @DisplayName("failing the whole window raises it")
        void aFullWindowOfFailuresRaisesIt() {
            refreshFor(ACME);
            assertThat(signalsOf(ACME, THRICE)).containsExactly("failing_repeatedly");
        }

        @Test
        @DisplayName("a shorter history than the window does NOT raise it")
        void anIncompleteWindowDoesNotRaiseIt() {
            // The reading that would raise it — "all of the results we have are failures" — is the
            // natural one and is wrong where it matters most: on a young project every case has one
            // result, so the first failure anybody records lights the tile, and a panel headed
            // "Needs attention" reads as a repository in crisis on the day it was created.
            refreshFor(ACME);
            assertThat(signalsOf(ACME, TWICE))
                    .as("two failures out of two ever is not a pattern yet")
                    .isEmpty();
        }

        @Test
        @DisplayName("the NEWEST result breaks the streak, not any result")
        void aRecoveryClearsIt() {
            refreshFor(ACME);
            assertThat(signalsOf(ACME, RECOVERED))
                    .as("a case fixed yesterday is not failing repeatedly today")
                    .isEmpty();
        }

        @Test
        @DisplayName("only the window is considered — an old pass does not rescue a failing case")
        void resultsOlderThanTheWindowAreIgnored() {
            // The mirror of the test above, and the one that fails if the LIMIT is dropped: with
            // the whole history in view, DEEP has a pass and the signal never fires for any case
            // that has ever worked — which is every case worth having.
            refreshFor(ACME);
            assertThat(signalsOf(ACME, DEEP)).containsExactly("failing_repeatedly");
        }

        @Test
        @DisplayName("a case BLOCKED three times running is not failing — it is blocked")
        void notPassingIsNotFailing() {
            // THE predicate decision. `counts_as_pass` is the column an aggregate report's pass
            // rate uses and it is right there, so `NOT counts_as_pass` is the tempting spelling —
            // and BLOCKED, SKIPPED, RETEST and UNTESTED all satisfy it. A case nobody could run
            // would be reported as a broken test, which sends the lead to fix the case instead of
            // the environment. §6o's wording is "failed", and the tile says "Failing repeatedly".
            refreshFor(ACME);
            assertThat(signalsOf(ACME, BLOCKED))
                    .as("status_kind = 'FAILED', never NOT counts_as_pass")
                    .isEmpty();
        }

        @Test
        @DisplayName("a never-executed case gets a row and no signal")
        void neverExecutedRaisesNothingHere() {
            // The row must exist even with nothing raised: a case with no signals and NO ROW is
            // indistinguishable from a case nobody has computed, which is the exact distinction
            // computed_at exists to draw.
            refreshFor(ACME);
            assertThat(healthRowExists(ACME, UNTOUCHED)).isTrue();
            assertThat(signalsOf(ACME, UNTOUCHED)).isEmpty();
        }

        @Test
        @DisplayName("a soft-deleted case gets no row at all")
        void softDeletedCasesAreNotDerived() {
            // The opposite call from the fact log, and the two are consistent rather than in
            // tension: the fact log is history and history does not un-happen, while case_health is
            // a worklist about cases that exist. CaseHealthRecomputeTest pins the same rule for
            // repository's two signals.
            refreshFor(ACME);
            assertThat(healthRowExists(ACME, REMOVED)).isFalse();
        }
    }

    @Nested
    @DisplayName("Boundaries — the row is shared, the columns are not")
    class Boundaries {

        @Test
        @DisplayName("the signals repository owns survive a reporting refresh")
        void theOtherModulesSignalsSurvive() throws SQLException {
            // THE test this slice exists for, and the first in the product that can catch a second
            // module clobbering a first's columns. An EXCLUDED.* set-list here resets
            // never_executed and long_unexecuted — computed correctly by CaseHealthRecomputeService
            // an hour earlier — to their defaults on every tick. It would present as a defect in
            // that job, which would be innocent, and the two would then race to blank each other.
            refreshFor(ACME);
            setSignal(ACME, THRICE, "never_executed", true);
            setSignal(ACME, THRICE, "long_unexecuted", true);

            refreshFor(ACME);

            assertThat(signalsOf(ACME, THRICE))
                    .as("reporting owns failing_repeatedly and nothing else on this row")
                    .containsExactlyInAnyOrder(
                            "never_executed", "long_unexecuted", "failing_repeatedly");
        }

        @Test
        @DisplayName("the signal self-heals when the case starts passing again")
        void theSignalClearsWhenTheCaseRecovers() throws SQLException {
            // §6o's whole argument for deriving rather than authoring: "no 'mark as not-stale'
            // button exists to lie with", and the corollary is that the computation must actually
            // take it back. An INSERT that only ever set true would leave every case that once had
            // a bad week flagged for ever, and nothing in the UI could clear it.
            refreshFor(ACME);
            assertThat(signalsOf(ACME, THRICE)).containsExactly("failing_repeatedly");

            try (Connection acme = appConnection(ACME)) {
                execution(acme, ACME, ACME_PROJECT, ACME_RUNS.get(0), THRICE, "Passed",
                        BASE.plusSeconds(86_400 * 10));
            }
            refreshFor(ACME);

            assertThat(signalsOf(ACME, THRICE))
                    .as("a derived signal that cannot come back down is an authored one")
                    .isEmpty();
        }

        @Test
        @DisplayName("a second refresh changes nothing")
        void isIdempotent() {
            refreshFor(ACME);
            List<String> first = signalsOf(ACME, THRICE);

            refreshFor(ACME);

            assertThat(signalsOf(ACME, THRICE)).isEqualTo(first);
        }

        @Test
        @DisplayName("recomputing one company writes nothing in another")
        void companiesAreIsolated() {
            refreshFor(ACME);
            assertThat(healthRowExists(UMBRA, FOREIGN))
                    .as("the statement carries no company predicate — FORCE RLS is the whole "
                            + "mechanism, on both the SELECT and the INSERT")
                    .isFalse();

            refreshFor(UMBRA);

            assertThat(healthRowExists(UMBRA, FOREIGN)).isTrue();
        }

        @Test
        @DisplayName("computed_at is stamped, so an empty panel finally means what it says")
        void computedAtIsStamped() {
            Instant before = Instant.now().minusSeconds(5);
            refreshFor(ACME);
            assertThat(computedAt(ACME, UNTOUCHED)).isNotNull().isAfter(before);
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void refreshFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            refresh.refresh();
        }
    }

    /** The signal columns this row asserts, so an assertion reads as a set rather than nine booleans. */
    private List<String> signalsOf(UUID companyId, UUID caseId) {
        List<String> columns = List.of("outdated_spec_changed", "stale_approval", "condition_drift",
                "stale_automation", "never_executed", "long_unexecuted", "unlinked",
                "failing_repeatedly", "orphaned");
        List<String> raised = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + String.join(", ", columns)
                                + " FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return List.of();
                }
                for (String column : columns) {
                    if (rows.getBoolean(column)) {
                        raised.add(column);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return raised;
    }

    private boolean healthRowExists(UUID companyId, UUID caseId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant computedAt(UUID companyId, UUID caseId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT computed_at FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getTimestamp("computed_at").toInstant() : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setSignal(UUID companyId, UUID caseId, String column, boolean value)
            throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE case_health SET " + column + " = ? WHERE case_id = CAST(? AS uuid)")) {
            statement.setBoolean(1, value);
            statement.setString(2, caseId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One result per run, oldest first; {@code null} means the case was not in that run at all.
     *
     * <p>Timestamps are a day apart and derived from the run's position, so "newest" is
     * unambiguous — a fixture whose results share an instant would be testing the tie-break rather
     * than the window.
     */
    private void results(Connection connection, UUID companyId, UUID projectId, UUID caseId,
            String... statusPerRun) throws SQLException {
        for (int i = 0; i < statusPerRun.length; i++) {
            if (statusPerRun[i] != null) {
                execution(connection, companyId, projectId, ACME_RUNS.get(i), caseId,
                        statusPerRun[i], BASE.plusSeconds(86_400L * i));
            }
        }
    }

    private void clearEverything() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("execution_fact", "case_health", "step_result",
                        "test_execution", "test_run", "case_classification", "test_case_version",
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

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
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

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, parent_id, key, key_seq, path, name,
                                   position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, 'TS-1', 1,
                        CAST('root' AS ltree), 'Root', 0, 'APPROVED', NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, boolean deleted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now(),
                        CAST(? AS timestamptz))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, deleted ? "2026-01-01T00:00:00Z" : null);
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

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Failing-repeatedly fixture run', 'IN_PROGRESS', 'MANUAL',
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
                    ON CONFLICT (run_id, case_id) DO UPDATE
                    SET status_id = EXCLUDED.status_id, executed_at = EXCLUDED.executed_at
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, projectId.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedAt.toString());
            statement.executeUpdate();
        }
    }
}
