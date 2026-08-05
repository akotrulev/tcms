package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The first report the product can compute — §6e's <em>run summary</em>, over the fact log §86 gave
 * a writer.
 *
 * <h2>What this class is guarding, and it is not the arithmetic</h2>
 *
 * <p>Counting rows by status is the easy half and would pass against three of the four defects that
 * matter here. What the fixture is built to catch:
 *
 * <ol>
 *   <li><strong>A pass rate whose denominator is not what the reader thinks.</strong> The fact log
 *       structurally holds only <em>recorded</em> executions, so {@link Meaning#theDenominatorIsRecordedExecutionsAndTheReportSaysSo()}
 *       puts a case in the run with no result and asserts that the report counts four of five and
 *       says which four. A report that silently counted the same four while calling it the run would
 *       print "50%" for something that is not 50% of anything a reader has in mind.</li>
 *   <li><strong>A clean report of nothing.</strong> A mistyped run id yields zero fact rows, which
 *       renders identically to a run nobody has executed. Two tests separate them.</li>
 *   <li><strong>A table whose rows move when the numbers move.</strong> Status order is
 *       {@code StatusKind}'s, not the counts', so two run summaries can be read side by side.</li>
 *   <li><strong>A report of somebody else's run.</strong> A sibling company's run id must be
 *       refused, and the mechanism is RLS rather than a predicate anybody wrote.</li>
 * </ol>
 *
 * <p>Ids are {@code bc…} — hex, which is not optional; {@code ExecutionFactRefreshTest}'s header
 * records the run a non-hex prefix cost. Slugs are {@code rs-…}.
 */
@DisplayName("Run summary — the first report computed from the fact log")
class RunSummaryReportTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("bc000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("bc000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("bc001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("bc001000-0000-4000-8000-000000000002");

    /** Two suites, so "results by suite" has something to group. */
    private static final UUID BILLING = UUID.fromString("bc002000-0000-4000-8000-000000000001");
    private static final UUID CHECKOUT = UUID.fromString("bc002000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("bc002000-0000-4000-8000-000000000009");

    private static final UUID RUN = UUID.fromString("bc003000-0000-4000-8000-000000000001");
    /** A run that exists and has been executed by nobody. */
    private static final UUID EMPTY_RUN = UUID.fromString("bc003000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_RUN = UUID.fromString("bc003000-0000-4000-8000-000000000009");

    private static final UUID PASSED_ONE = UUID.fromString("bc004000-0000-4000-8000-000000000001");
    private static final UUID PASSED_TWO = UUID.fromString("bc004000-0000-4000-8000-000000000002");
    private static final UUID FAILED = UUID.fromString("bc004000-0000-4000-8000-000000000003");
    private static final UUID BLOCKED = UUID.fromString("bc004000-0000-4000-8000-000000000004");
    /** In the run, with no result recorded. The denominator's whole proof. */
    private static final UUID UNRECORDED = UUID.fromString("bc004000-0000-4000-8000-000000000005");
    private static final UUID FOREIGN_CASE = UUID.fromString("bc004000-0000-4000-8000-000000000009");
    /** Filed in no suite (legal since {@code V31}) — the "(not filed)" bucket's only occupant. */
    private static final UUID UNFILED = UUID.fromString("bc004000-0000-4000-8000-000000000006");

    private static final UUID ALICE = UUID.fromString("bc005000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("bc005000-0000-4000-8000-000000000002");

    private static final Instant BASE = Instant.parse("2026-03-02T09:00:00Z");

    @Autowired
    private RunSummaryReport report;

    @Autowired
    private ExecutionFactRefreshService refresh;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "rs-acme", "Acme", now);
        company(UMBRA, "rs-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "RSAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "RSUM", "Umbra Product", now));

        clearEverything();

        person(ALICE, "alice@rs.test", "Alice Canonical");
        person(BOB, "bob@rs.test", "Bob Canonical");

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            // The display name is the COMPANY MEMBERSHIP's, not the account's (§6u), and the two
            // differ here so a report that reached for the wrong one is visible rather than equal.
            membership(acme, ACME, ALICE, "Alice Tester");
            membership(acme, ACME, BOB, "Bob Tester");

            suite(acme, BILLING, ACME, ACME_PROJECT, "TS-1", 1, "billing", "Billing");
            suite(acme, CHECKOUT, ACME, ACME_PROJECT, "TS-2", 2, "checkout", "Checkout");

            testCase(acme, PASSED_ONE, ACME, ACME_PROJECT, BILLING, "TC-1", 1);
            testCase(acme, PASSED_TWO, ACME, ACME_PROJECT, BILLING, "TC-2", 2);
            testCase(acme, FAILED, ACME, ACME_PROJECT, CHECKOUT, "TC-3", 3);
            testCase(acme, BLOCKED, ACME, ACME_PROJECT, CHECKOUT, "TC-4", 4);
            testCase(acme, UNRECORDED, ACME, ACME_PROJECT, CHECKOUT, "TC-5", 5);

            run(acme, RUN, ACME, ACME_PROJECT, "TR-1", 1, "Release 4.2 regression");
            run(acme, EMPTY_RUN, ACME, ACME_PROJECT, "TR-2", 2, "Nobody has started this");

            execution(acme, ACME, ACME_PROJECT, RUN, PASSED_ONE, "Passed", ALICE, 1_000L, BASE);
            execution(acme, ACME, ACME_PROJECT, RUN, PASSED_TWO, "Passed", ALICE, 2_000L,
                    BASE.plusSeconds(60));
            execution(acme, ACME, ACME_PROJECT, RUN, FAILED, "Failed", BOB, 500L,
                    BASE.plusSeconds(120));
            // Nobody attributed and no duration — an ordinary state, not a broken row: M3's
            // ingestion path records results nobody typed, and a manual verdict carries no timing.
            execution(acme, ACME, ACME_PROJECT, RUN, BLOCKED, "Blocked", null, null,
                    BASE.plusSeconds(180));
            // In the run and never executed. Creating a run writes one empty execution per case in
            // scope (§42), and THIS is the row the projection refuses — which is what makes the
            // report's denominator smaller than the run.
            execution(acme, ACME, ACME_PROJECT, RUN, UNRECORDED, null, null, null, null);
            execution(acme, ACME, ACME_PROJECT, EMPTY_RUN, PASSED_ONE, null, null, null, null);
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "TS-1", 1, "root", "Root");
            testCase(umbra, FOREIGN_CASE, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1, "Umbra's own run");
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, FOREIGN_CASE, "Passed", null, 900L,
                    BASE);
        }

        refreshFor(ACME);
        refreshFor(UMBRA);
    }

    // =====================================================================

    @Nested
    @DisplayName("What the numbers are OF")
    class Meaning {

        @Test
        @DisplayName("the denominator is recorded executions, and the report says so")
        void theDenominatorIsRecordedExecutionsAndTheReportSaysSo() {
            // THE test of this slice. Five cases are in the run and four have a verdict, so every
            // percentage here is out of four. A report that printed "of 4" while a reader assumed
            // "of the run" is the §6e failure in one line: "two teams reading 94% pass can mean
            // entirely different things".
            ReportModel model = compute(RUN);

            assertThat(entry(model, "Recorded executions")).isEqualTo("4");
            assertThat(entry(model, "Distinct cases")).isEqualTo("4");
            assertThat(entry(model, "Passed")).isEqualTo("2");
            assertThat(entry(model, "Pass rate (of recorded)")).isEqualTo("50.0%");
            assertThat(section(model, "Results").narrative())
                    .as("the sentence is the difference between a number and a claim")
                    .contains("do not appear here at all");
        }

        @Test
        @DisplayName("the status breakdown is ordered by kind, never by count")
        void statusesAreOrderedCanonically() {
            // FAILED (1) before BLOCKED (1) is the enum's order and not a tie-break; PASSED (2)
            // leading is both. Order by count would put PASSED first here too, which is why the
            // fixture has two kinds tied — a count-ordered table would be free to swap them, and
            // two run summaries read side by side would not line up.
            ReportModel.Table table = section(model(), "Results").table();

            assertThat(table.rows()).containsExactly(
                    List.of("PASSED", "2", "50.0%"),
                    List.of("FAILED", "1", "25.0%"),
                    List.of("BLOCKED", "1", "25.0%"));
        }

        @Test
        @DisplayName("duration sums only the executions that were timed")
        void durationIsOfTimedExecutionsOnly() {
            // 1000 + 2000 + 500 ms, with the blocked result contributing nothing because nobody
            // timed it. Labelled "recorded duration" rather than "duration" for that reason: this
            // is not how long the run took.
            assertThat(entry(model(), "Total recorded duration")).isEqualTo("3s");
        }

        @Test
        @DisplayName("no aggregation policy is claimed, because one run cannot need one")
        void theAggregationPolicyLineSaysItDoesNotApply() {
            // Slice-plan decision D refuses a report that quietly means LATEST. uq_execution_run_case
            // is what makes "not applicable" true here rather than convenient.
            assertThat(entry(model(), "Aggregation policy")).contains("Not applicable");
        }
    }

    @Nested
    @DisplayName("Who did the work — §6e's per-tester progress")
    class Testers {

        @Test
        @DisplayName("testers are named from their company membership, and the totals still add up")
        void testersAreNamedAndTotalsAdd() {
            ReportModel.Table table = section(model(), "Progress by tester").table();

            assertThat(table.rows()).containsExactly(
                    List.of("Alice Tester", "2", "2", "100.0%"),
                    List.of("Bob Tester", "1", "0", "0.0%"),
                    List.of("Unattributed", "1", "0", "0.0%"));
        }

        @Test
        @DisplayName("an execution whose account has gone reads as a former member, not as a uuid")
        void anUnresolvableTesterIsNamedHonestly() throws SQLException {
            // execution_fact carries no foreign keys — that is what a star schema is — so an
            // executed_by naming an account that no longer exists is a state real rows reach.
            // PeopleDirectory answers nothing for it, and inventing a name would attribute
            // somebody's work to a person.
            try (Connection acme = appConnection(ACME);
                    PreparedStatement statement = acme.prepareStatement(
                            "UPDATE execution_fact SET executed_by = gen_random_uuid() "
                                    + "WHERE case_id = CAST(? AS uuid)")) {
                statement.setString(1, FAILED.toString());
                statement.executeUpdate();
            }

            assertThat(section(model(), "Progress by tester").table().rows())
                    .anySatisfy(row -> assertThat(row.get(0)).isEqualTo("Former member"));
        }
    }

    @Nested
    @DisplayName("Where the work was — the frozen suite path")
    class Suites {

        @Test
        @DisplayName("results group by the path the fact carries, not by the tree as it stands")
        void resultsGroupBySuitePath() {
            ReportModel.Table table = section(model(), "Results by suite").table();

            assertThat(table.rows()).containsExactly(
                    List.of("billing", "2", "2", "0"),
                    List.of("checkout", "2", "0", "1"));
        }

        @Test
        @DisplayName("a case moved after its result was recorded stays under the old path")
        void theSuitePathIsAsOfTheResult() throws SQLException {
            // The dimension-freezing rule, observed from the report rather than from the statement:
            // ExecutionSource's ON CONFLICT deliberately never updates suite_path, so re-filing a
            // case does not re-file last quarter's results under this quarter's taxonomy.
            try (Connection acme = appConnection(ACME);
                    PreparedStatement statement = acme.prepareStatement(
                            "UPDATE test_case SET suite_id = CAST(? AS uuid) "
                                    + "WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, CHECKOUT.toString());
                statement.setString(2, PASSED_ONE.toString());
                statement.executeUpdate();
            }
            refreshFor(ACME);

            assertThat(section(model(), "Results by suite").table().rows())
                    .as("history does not move when the tree does")
                    .containsExactly(
                            List.of("billing", "2", "2", "0"),
                            List.of("checkout", "2", "0", "1"));
        }

        @Test
        @DisplayName("a case filed nowhere gets its own bucket rather than being left out")
        void theUnfiledBucket() throws SQLException {
            // §96: suite_path is nullable, because the alternative (an inner join in
            // ExecutionSource) drops the execution from the log entirely. A NULL group left
            // unhandled here is the same defect one layer up — this section's executions would
            // stop summing to the headline, in the direction nobody checks, and a reader
            // comparing the two would find the difference and never the reason.
            //
            // Seeded inside the test rather than in the fixture: every other assertion in this
            // class is over a headline the extra execution would move, and a fixture change that
            // quietly re-baselines six numbers is not a good trade for one row.
            try (Connection acme = appConnection(ACME)) {
                testCase(acme, UNFILED, ACME, ACME_PROJECT, null, "TC-6", 6);
                execution(acme, ACME, ACME_PROJECT, RUN, UNFILED, "Failed", ALICE, 300L,
                        BASE.plusSeconds(240));
            }
            refreshFor(ACME);

            assertThat(section(model(), "Results by suite").table().rows())
                    .as("counted and labelled, never omitted")
                    .containsExactly(
                            List.of("billing", "2", "2", "0"),
                            List.of("checkout", "2", "0", "1"),
                            List.of(RunSummaryReport.NOT_FILED, "1", "0", "1"));
        }
    }

    @Nested
    @DisplayName("The four ways of having nothing to report, and only two are the same")
    class Absences {

        @Test
        @DisplayName("a run nobody has executed renders, and says nothing has been recorded")
        void anUnexecutedRunSaysSo() {
            ReportModel model = compute(EMPTY_RUN);

            assertThat(entry(model, "Recorded executions")).isEqualTo("0");
            assertThat(entry(model, "Pass rate (of recorded)"))
                    .as("0/0 is an em dash, never 0.0% — the second is a claim")
                    .isEqualTo("—");
            assertThat(section(model, "Results").narrative()).contains("Nothing has been recorded");
            assertThat(section(model, "Results").table()).isNull();
        }

        @Test
        @DisplayName("an unknown run is REFUSED, not summarised as zero")
        void anUnknownRunIsRefused() {
            // Without ExecutionSource.findRun this is indistinguishable from the test above: a
            // clean report of nothing for a run that does not exist. Four slices of this project
            // have paid for that shape.
            UUID nothing = UUID.fromString("bc009000-0000-4000-8000-00000000000f");

            assertThatThrownBy(() -> compute(nothing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no run");
        }

        @Test
        @DisplayName("another company's run is refused, and RLS is the mechanism")
        void aSiblingCompanysRunIsRefused() {
            // Asked in Acme's context for Umbra's run id. Nothing in the query says "company":
            // FORCE ROW LEVEL SECURITY is what makes test_run empty here, exactly as it is for the
            // projection that filled the facts.
            assertThatThrownBy(() -> compute(UMBRA_RUN))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(entry(compute(UMBRA, UMBRA_RUN), "Recorded executions"))
                    .as("and it is a real run to its own company")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("a request with no runId is refused before anything is queried")
        void aMissingParameterIsRefused() {
            assertThatThrownBy(() -> readIn(ACME,
                    () -> report.compute(ACME_PROJECT, ReportParameters.none())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runId");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private ReportModel model() {
        return compute(RUN);
    }

    private ReportModel compute(UUID runId) {
        return compute(ACME, runId);
    }

    private ReportModel compute(UUID companyId, UUID runId) {
        UUID projectId = companyId.equals(ACME) ? ACME_PROJECT : UMBRA_PROJECT;
        return readIn(companyId, () -> report.compute(projectId,
                ReportParameters.parse("{\"runId\":\"" + runId + "\"}")));
    }

    private String entry(ReportModel model, String label) {
        return model.metadata().stream()
                .filter(candidate -> candidate.label().equals(label))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No metadata entry '" + label + "'"));
    }

    private ReportModel.Section section(ReportModel model, String heading) {
        return model.sections().stream()
                .filter(candidate -> candidate.heading().equals(heading))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No section '" + heading + "'"));
    }

    private void refreshFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            refresh.refresh();
        }
    }

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    /**
     * Deliberately not a second {@code inCompany} overload: an implicitly-typed lambda whose body is
     * a method call is compatible with both {@link Runnable} and {@link Supplier}, so the two
     * overloads would be ambiguous at every call site that returns something.
     */
    private <T> T readIn(UUID companyId, Supplier<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return work.get();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
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

    /** {@code app_user} is instance-level (ERD §0.1's exception list), so this needs no context. */
    private void person(UUID id, String email, String canonicalName) throws SQLException {
        try (Connection connection = appConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO app_user (id, email, canonical_name, status)
                        VALUES (CAST(? AS uuid), ?, ?, 'ACTIVE')
                            ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setString(1, id.toString());
            statement.setString(2, email);
            statement.setString(3, canonicalName);
            statement.executeUpdate();
        }
    }

    private void membership(Connection connection, UUID companyId, UUID userId, String displayName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_membership
                    (company_id, user_id, display_name, status, accepted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'ACTIVE', now())
                    ON CONFLICT (company_id, user_id) DO UPDATE SET display_name = EXCLUDED.display_name
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, userId.toString());
            statement.setString(3, displayName);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String path, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, parent_id, key, key_seq, path, name,
                                   position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED', NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, path);
            statement.setString(7, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            // Null is an unfiled case, legal since V31 — see theUnfiledBucket.
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

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?,
                        'IN_PROGRESS', 'MANUAL',
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
            statement.setString(6, name);
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One execution. A {@code null} status is the row a run's creation writes for a case in scope
     * that nobody has touched — the projection refuses it, which is what the denominator rests on.
     */
    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, String statusName, UUID executedBy, Long durationMs, Instant executedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_by, executed_at, duration_ms, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS uuid), CAST(? AS timestamptz), ?, now(), now())
                    ON CONFLICT (run_id, case_id) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, projectId.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedBy == null ? null : executedBy.toString());
            statement.setString(9, executedAt == null ? null : executedAt.toString());
            if (durationMs == null) {
                statement.setNull(10, java.sql.Types.BIGINT);
            } else {
                statement.setLong(10, durationMs);
            }
            statement.executeUpdate();
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

}
