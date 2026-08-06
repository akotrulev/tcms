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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The {@code plan_snapshot} rollup — the second {@code V1} table §101 gives a writer, and the one
 * that is <strong>not</strong> the same shape as its sibling.
 *
 * <h2>The asymmetry is what this class exists for</h2>
 *
 * <p>{@code suite_run_summary} is history: a row says how one suite did in one run, so nothing
 * deletes and membership freezes. {@code plan_snapshot} is current state — {@code V1}'s own comment
 * is "the CURRENT latest result per case per configuration" — so it needs the delete pass its
 * sibling must never grow. Two tables created in the same migration, discussed in the same paragraph
 * of §6p, and a recompute written the same way for both is wrong for one of them <em>in a way no row
 * looks wrong for</em>: the plan's totals simply stay too high for ever.
 *
 * <p>{@code PlanStatisticsService.rowsInScope} has done that filter in Java since §46, and its own
 * header calls the mirror case "easier to miss". Moving the computation into a table moves the
 * filter into a {@code DELETE}, and losing it in the move is the whole risk of this slice's second
 * phase.
 *
 * <h2>The fixture's non-vacuity, and it is a different one from the sibling's</h2>
 *
 * <p>What matters here is <strong>a case that ran more than once with different verdicts</strong>.
 * Over a fixture where every case ran exactly once, a snapshot that keeps the wrong row — the first
 * instead of the last, or an arbitrary one — is indistinguishable from a correct one, and every
 * assertion below passes against it. {@link Fixture#aCaseRanTwiceWithDifferentVerdicts()} is
 * asserted before anything else.
 *
 * <p>Ids are prefixed {@code 6f} and slugs {@code pns-}. Hex, for
 * {@code ExecutionFactRefreshTest}'s recorded reason.
 */
@DisplayName("Plan snapshot — current state, and therefore the prune its sibling must not have")
class PlanSnapshotRefreshTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("6f000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("6f000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("6f001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("6f001000-0000-4000-8000-000000000002");

    private static final UUID SUITE = UUID.fromString("6f002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("6f002000-0000-4000-8000-000000000002");

    private static final UUID PLAN = UUID.fromString("6f005000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PLAN = UUID.fromString("6f005000-0000-4000-8000-000000000002");

    private static final UUID RUN_EARLY = UUID.fromString("6f003000-0000-4000-8000-000000000001");
    private static final UUID RUN_LATE = UUID.fromString("6f003000-0000-4000-8000-000000000002");
    private static final UUID RUN_SAFARI = UUID.fromString("6f003000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_RUN = UUID.fromString("6f003000-0000-4000-8000-000000000004");

    /**
     * Ran three times: Failed then Passed on the default configuration, and Failed on the second.
     * The whole class turns on this case — with one execution apiece, "keeps the latest" and "keeps
     * whichever" are the same assertion.
     */
    private static final UUID CASE_TWICE = UUID.fromString("6f004000-0000-4000-8000-000000000001");
    /** In scope, ran once. */
    private static final UUID CASE_ONCE = UUID.fromString("6f004000-0000-4000-8000-000000000002");
    /**
     * In the plan's scope and never executed. Must be <strong>absent</strong> from this table rather
     * than present with a null status: coverage and not-run come from the plan's own scope (§46,
     * §6p), and the reader takes the difference.
     */
    private static final UUID CASE_NEVER_RUN = UUID.fromString("6f004000-0000-4000-8000-000000000003");
    /**
     * Executed inside a run of this plan and <strong>not in the plan's scope</strong> — the case a
     * scope-blind statement would file as readiness evidence for a plan that does not cover it.
     */
    private static final UUID CASE_OUT_OF_SCOPE =
            UUID.fromString("6f004000-0000-4000-8000-000000000004");
    private static final UUID FOREIGN = UUID.fromString("6f004000-0000-4000-8000-000000000005");

    private static final Instant EARLY = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-03-02T09:00:00Z");

    private UUID defaultConfiguration;
    private UUID secondConfiguration;

    @Autowired
    private RollupRefreshService rollups;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "pns-acme", "Acme", now);
        company(UMBRA, "pns-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "PNAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "PNUM", "Umbra Product", now));

        clearRollupsAndExecutions();

        try (Connection acme = appConnection(ACME)) {
            seedCompanyDefaults(acme, ACME);
            seedProjectDefaults(acme, ACME, ACME_PROJECT);

            defaultConfiguration = configuration(acme, ACME_PROJECT, null);
            // A second configuration, so "latest per case" and "latest per case per configuration"
            // are distinguishable. With one, the two policies answer identically and the grain of
            // this table is untested.
            secondConfiguration = UUID.fromString("6f006000-0000-4000-8000-000000000001");
            configuration(acme, ACME_PROJECT, secondConfiguration);

            suite(acme, SUITE, ACME, ACME_PROJECT, "Root", 1);

            testCase(acme, CASE_TWICE, ACME, ACME_PROJECT, SUITE, "TC-1", 1);
            testCase(acme, CASE_ONCE, ACME, ACME_PROJECT, SUITE, "TC-2", 2);
            testCase(acme, CASE_NEVER_RUN, ACME, ACME_PROJECT, SUITE, "TC-3", 3);
            testCase(acme, CASE_OUT_OF_SCOPE, ACME, ACME_PROJECT, SUITE, "TC-4", 4);

            plan(acme, PLAN, ACME, ACME_PROJECT, "TP-1", 1);
            // Scoped case by case rather than by the suite, so CASE_OUT_OF_SCOPE can sit in the
            // same folder and still be out of scope — which is what makes the scope bound a real
            // predicate here rather than a synonym for the suite join.
            scopeCase(acme, ACME, PLAN, CASE_TWICE);
            scopeCase(acme, ACME, PLAN, CASE_ONCE);
            scopeCase(acme, ACME, PLAN, CASE_NEVER_RUN);

            run(acme, RUN_EARLY, ACME, ACME_PROJECT, PLAN, "TR-1", 1, defaultConfiguration);
            run(acme, RUN_LATE, ACME, ACME_PROJECT, PLAN, "TR-2", 2, defaultConfiguration);
            run(acme, RUN_SAFARI, ACME, ACME_PROJECT, PLAN, "TR-3", 3, secondConfiguration);

            execution(acme, ACME, ACME_PROJECT, RUN_EARLY, CASE_TWICE, "Failed", EARLY);
            execution(acme, ACME, ACME_PROJECT, RUN_LATE, CASE_TWICE, "Passed", LATE);
            execution(acme, ACME, ACME_PROJECT, RUN_SAFARI, CASE_TWICE, "Failed", LATE);
            execution(acme, ACME, ACME_PROJECT, RUN_EARLY, CASE_ONCE, "Passed", EARLY);
            execution(acme, ACME, ACME_PROJECT, RUN_EARLY, CASE_OUT_OF_SCOPE, "Passed", EARLY);
            // Planned and never recorded — §42 writes one of these per case at run creation.
            execution(acme, ACME, ACME_PROJECT, RUN_EARLY, CASE_NEVER_RUN, null, null);
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedCompanyDefaults(umbra, UMBRA);
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            UUID umbraConfiguration = configuration(umbra, UMBRA_PROJECT, null);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "Root", 1);
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1);
            plan(umbra, UMBRA_PLAN, UMBRA, UMBRA_PROJECT, "TP-1", 1);
            scopeCase(umbra, UMBRA, UMBRA_PLAN, FOREIGN);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, UMBRA_PLAN, "TR-1", 1, umbraConfiguration);
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, FOREIGN, "Passed", EARLY);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The fixture — asserted before anything is asserted over it")
    class Fixture {

        @Test
        @DisplayName("a case ran more than once, with different verdicts")
        void aCaseRanTwiceWithDifferentVerdicts() {
            // Without this, "keeps the latest" and "keeps an arbitrary one" are the same assertion
            // and every test below is satisfied by a statement with no ordering at all.
            assertThat(count(ACME, """
                    SELECT count(*) FROM test_execution
                     WHERE case_id = CAST(? AS uuid) AND status_id IS NOT NULL
                    """, CASE_TWICE))
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("a case executed inside the plan's runs is NOT in the plan's scope")
        void aCaseRanInThePlansRunsWithoutBeingInScope() {
            // The bound cannot be tested without one: with every executed case in scope, a
            // statement that ignores plan_scope_item entirely passes the whole class.
            assertThat(count(ACME, """
                    SELECT count(*) FROM plan_scope_item
                     WHERE plan_id = CAST(? AS uuid) AND case_id = CAST(? AS uuid)
                    """, PLAN, CASE_OUT_OF_SCOPE))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("What the snapshot holds")
    class WhatIsSnapshotted {

        @Test
        @DisplayName("the LATEST execution per case per configuration, not the first")
        void keepsTheLatestPerCasePerConfiguration() {
            refresh();

            assertThat(snapshot(ACME, PLAN, CASE_TWICE, defaultConfiguration).get("status_kind"))
                    .as("Failed in March, Passed the day after — the plan's readiness is the "
                            + "second one")
                    .isEqualTo("PASSED");
            assertThat(snapshot(ACME, PLAN, CASE_TWICE, defaultConfiguration).get("counts_as_pass"))
                    .isEqualTo(true);
        }

        @Test
        @DisplayName("one row per configuration — a plan green on Chrome and red on Safari is both")
        void keepsOneRowPerConfiguration() {
            // §6p: "a plan passing on Chrome and failing on Safari must not average into a single
            // reassuring number". The grain of this table is what makes that expressible at all.
            refresh();

            assertThat(snapshot(ACME, PLAN, CASE_TWICE, secondConfiguration).get("status_kind"))
                    .isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a case in scope that has never run is ABSENT, not present with a null status")
        void anUnexecutedCaseHasNoRow() {
            // §46's direction, and §6p's: coverage and not-run come from the plan's own scope, so
            // the reader takes the difference. A row with a null status here would give the plan
            // page a second, disagreeing source for the same number.
            refresh();

            assertThat(snapshotOrNull(ACME, PLAN, CASE_NEVER_RUN, defaultConfiguration))
                    .as("in scope, planned, never executed")
                    .isNull();
        }

        @Test
        @DisplayName("a case executed in the plan's runs but outside its scope is not snapshotted")
        void anOutOfScopeCaseIsNotSnapshotted() {
            refresh();

            assertThat(snapshotOrNull(ACME, PLAN, CASE_OUT_OF_SCOPE, defaultConfiguration))
                    .as("readiness is argued from the plan's scope and never from whatever ran "
                            + "inside its runs")
                    .isNull();
        }

        @Test
        @DisplayName("execution_id and executed_at come from the execution that was kept")
        void carriesTheExecutionItKept() {
            refresh();

            Map<String, Object> row = snapshot(ACME, PLAN, CASE_TWICE, defaultConfiguration);
            assertThat((Instant) row.get("executed_at")).isEqualTo(LATE);
            assertThat(row.get("execution_id"))
                    .as("the blockers list resolves these ids back to executions — a null here is a "
                            + "failed case a release meeting cannot open")
                    .isNotNull();
        }

        @Test
        @DisplayName("a second refresh writes no second row")
        void isIdempotent() {
            refresh();
            long first = count(ACME, "SELECT count(*) FROM plan_snapshot");

            refresh();

            assertThat(count(ACME, "SELECT count(*) FROM plan_snapshot")).isEqualTo(first);
        }

        @Test
        @DisplayName("an amended verdict is corrected on the next refresh")
        void anAmendedVerdictIsCorrected() throws SQLException {
            refresh();
            assertThat(snapshot(ACME, PLAN, CASE_ONCE, defaultConfiguration).get("status_kind"))
                    .isEqualTo("PASSED");

            amendResult(ACME, CASE_ONCE, "Failed");
            refresh();

            assertThat(snapshot(ACME, PLAN, CASE_ONCE, defaultConfiguration).get("status_kind"))
                    .as("every column on this table is part of the answer to *which result is "
                            + "current*, so unlike execution_fact none of them is frozen")
                    .isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("The prune — and its sibling must never grow one")
    class Prune {

        @Test
        @DisplayName("a case removed from the plan's scope loses its row")
        void prunesACaseThatLeftTheScope() throws SQLException {
            // THE second finding. Without the delete pass the row survives for ever, the plan's
            // totals stay too high, and there is no row anywhere that looks wrong — the failure
            // mode a recompute-with-no-prune has and a live computation structurally cannot.
            refresh();
            assertThat(snapshotOrNull(ACME, PLAN, CASE_ONCE, defaultConfiguration)).isNotNull();

            update(ACME, """
                    DELETE FROM plan_scope_item
                     WHERE plan_id = CAST(? AS uuid) AND case_id = CAST(? AS uuid)
                    """, PLAN.toString(), CASE_ONCE.toString());
            refresh();

            assertThat(snapshotOrNull(ACME, PLAN, CASE_ONCE, defaultConfiguration))
                    .as("a plan does not argue readiness from a case it no longer covers")
                    .isNull();
        }

        @Test
        @DisplayName("a soft-deleted case loses its row")
        void prunesASoftDeletedCase() throws SQLException {
            refresh();
            assertThat(snapshotOrNull(ACME, PLAN, CASE_ONCE, defaultConfiguration)).isNotNull();

            update(ACME, "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)",
                    CASE_ONCE.toString());
            refresh();

            assertThat(snapshotOrNull(ACME, PLAN, CASE_ONCE, defaultConfiguration)).isNull();
        }

        @Test
        @DisplayName("the prune does not touch the suite rollup, which is history")
        void theSiblingIsNotPruned() throws SQLException {
            // The asymmetry, asserted rather than described. A suite that stops claiming a case
            // does not un-run it, so the same scope change that empties a plan_snapshot row must
            // leave suite_run_summary alone — and the natural mistake is one delete pass over both.
            refresh();
            long summaries = count(ACME, "SELECT count(*) FROM suite_run_summary");
            assertThat(summaries).as("the fixture produced suite summaries at all").isPositive();

            update(ACME, "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)",
                    CASE_ONCE.toString());
            refresh();

            assertThat(count(ACME, "SELECT count(*) FROM suite_run_summary"))
                    .as("history does not shrink when somebody deletes a case today")
                    .isEqualTo(summaries);
        }
    }

    @Nested
    @DisplayName("Isolation")
    class Isolation {

        @Test
        @DisplayName("refreshing one company snapshots nothing in another")
        void companiesAreIsolated() {
            refresh();

            assertThat(count(UMBRA, "SELECT count(*) FROM plan_snapshot")).isZero();

            try (var ignored = CompanyContext.open(UMBRA)) {
                rollups.refresh();
            }

            assertThat(count(UMBRA, "SELECT count(*) FROM plan_snapshot")).isEqualTo(1L);
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void refresh() {
        try (var ignored = CompanyContext.open(ACME)) {
            rollups.refresh();
        }
    }

    private Map<String, Object> snapshot(UUID companyId, UUID planId, UUID caseId,
            UUID configurationId) {
        Map<String, Object> row = snapshotOrNull(companyId, planId, caseId, configurationId);
        assertThat(row).as("no plan_snapshot row for case %s", caseId).isNotNull();
        return row;
    }

    private Map<String, Object> snapshotOrNull(UUID companyId, UUID planId, UUID caseId,
            UUID configurationId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT execution_id, status_kind, counts_as_pass, executed_at,
                               release_version
                          FROM plan_snapshot
                         WHERE plan_id = CAST(? AS uuid)
                           AND case_id = CAST(? AS uuid)
                           AND configuration_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, planId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, configurationId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("execution_id", rows.getObject("execution_id", UUID.class));
                row.put("status_kind", rows.getString("status_kind"));
                row.put("counts_as_pass", rows.getObject("counts_as_pass") == null
                        ? null : rows.getBoolean("counts_as_pass"));
                row.put("executed_at", rows.getTimestamp("executed_at") == null
                        ? null : rows.getTimestamp("executed_at").toInstant());
                row.put("release_version", rows.getString("release_version"));
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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
                for (String table : List.of("plan_snapshot", "suite_run_summary", "execution_fact",
                        "case_health", "step_result", "test_execution", "test_run",
                        "plan_scope_item", "test_plan_version", "test_plan", "case_tag",
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

    /**
     * The project's default configuration when {@code id} is null, or a second one created with
     * that id.
     */
    private UUID configuration(Connection connection, UUID projectId, UUID id) throws SQLException {
        if (id == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT c.id FROM configuration c WHERE c.project_id = CAST(? AS uuid) "
                            + "ORDER BY c.is_default DESC LIMIT 1")) {
                statement.setString(1, projectId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("project defaults seed a configuration").isTrue();
                    return rows.getObject(1, UUID.class);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration (id, company_id, project_id, name, is_default)
                SELECT CAST(? AS uuid), p.company_id, p.id, 'Safari', false
                  FROM project p WHERE p.id = CAST(? AS uuid)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, projectId.toString());
            statement.executeUpdate();
        }
        return id;
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

    private void plan(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_plan
                    (id, company_id, project_id, key, key_seq, name, status, current_version_no,
                     lock_version, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Plan snapshot fixture', 'DRAFT', 1, 0, now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.executeUpdate();
        }
    }

    private void scopeCase(Connection connection, UUID companyId, UUID planId, UUID caseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_scope_item (id, company_id, plan_id, suite_id, case_id, tag,
                                             section_name, ordinal)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), NULL,
                        CAST(? AS uuid), NULL, NULL, 0)
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, planId.toString());
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, UUID planId,
            String key, long keySeq, UUID configurationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, plan_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Plan snapshot fixture run', 'IN_PROGRESS', 'MANUAL', CAST(? AS uuid),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, planId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, configurationId.toString());
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
