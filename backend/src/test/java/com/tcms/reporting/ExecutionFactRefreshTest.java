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
 * The {@code execution_fact} projection — the first writer this read model has ever had.
 *
 * <h2>What this class is really guarding</h2>
 *
 * <p>{@code execution_fact} has been in {@code V1} since M0 with five purpose-built indexes and no
 * rows, and every report in §6e's catalogue is defined over it. So the failures worth catching here
 * are all failures that <strong>look like data</strong> — a report computed from a subtly wrong fact
 * log renders exactly like a report computed from a right one, which is the property that has cost
 * this project four slices already (§47, §53, §72, §81).
 *
 * <ol>
 *   <li><strong>A planned execution counted as a result.</strong> Creating a run writes one empty
 *       execution per case in scope (§42). {@code execution_fact.status_kind} is {@code NOT NULL}
 *       while {@code test_execution.status_id} is nullable, so the fact log <em>structurally</em>
 *       cannot hold one — but only if the statement joins {@code case_status} rather than
 *       left-joining it and defaulting. Planning work would otherwise file it as history.</li>
 *   <li><strong>A frozen dimension moving.</strong> The whole reason the {@code ON CONFLICT} set-list
 *       names seven columns and not twenty-two. A case reclassified, re-automated or moved to
 *       another suite must not re-file the results it already produced: "failures by feature" for
 *       last quarter would change on every tick and nobody could name when. This is the most
 *       important test in the class and it is the one a refactor is most likely to break, because
 *       {@code EXCLUDED.*} everywhere looks tidier.</li>
 *   <li><strong>A result column NOT moving.</strong> The mirror. An amended verdict must be
 *       corrected, or the log preserves the first thing anyone typed for ever.</li>
 *   <li><strong>Writing across companies.</strong> The statement carries no company predicate at
 *       all — it relies entirely on {@code FORCE ROW LEVEL SECURITY} applying to both the
 *       {@code SELECT} and the {@code INSERT}. Correct, and an unasserted design of that kind is
 *       indistinguishable from a leak.</li>
 *   <li><strong>Every column having a source.</strong> The slice plan verified that on paper, column
 *       by column. {@link Columns#everyColumnIsFilledFromItsSource()} is where it stops being
 *       paper — {@code suite_path} is an {@code ltree}, {@code feature_ids} is a {@code UUID[]}, and
 *       both are the kind of type that compiles, inserts and turns out to hold the wrong thing.</li>
 * </ol>
 *
 * <p>Ids and slugs are unique to this class — {@code ef…} and {@code rf-…}. Both, not just the ids:
 * {@code CaseHealthRecomputeTest}'s header records renaming one and leaving the other behind, which
 * failed all eleven of its tests in the full suite only and pointed the stack trace at the base
 * class.
 *
 * <p><strong>And the id prefix has to be HEX, which cost this class its first gate run.</strong> The
 * obvious mnemonic for a reporting-facts fixture is {@code rf…}, and {@code r} is not a hex digit —
 * {@code UUID.fromString} throws in the static initialiser, so all eleven tests failed with
 * {@code NoClassDefFoundError} and none of them ran. The prefix is a namespace <em>and</em> a
 * literal, and every prefix already claimed in this suite ({@code ce}, {@code af}, {@code cf},
 * {@code da}, …) is hex for exactly this reason. {@code ef} is this class's, and the slug namespace
 * stays {@code rf-}, where no such constraint applies.
 */
@DisplayName("Execution-fact projection — the reporting read model's first writer")
class ExecutionFactRefreshTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ef000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("ef000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("ef001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("ef001000-0000-4000-8000-000000000002");

    private static final UUID ACME_SUITE = UUID.fromString("ef002000-0000-4000-8000-000000000001");
    /** Where {@link #MOVED} is filed after the reclassification, to prove the fact does not follow. */
    private static final UUID ACME_OTHER_SUITE = UUID.fromString("ef002000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("ef002000-0000-4000-8000-000000000003");

    private static final UUID ACME_RUN = UUID.fromString("ef003000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_RUN = UUID.fromString("ef003000-0000-4000-8000-000000000002");

    /** Recorded Passed, fully classified — the row every column assertion is read off. */
    private static final UUID RICH = UUID.fromString("ef004000-0000-4000-8000-000000000001");
    /** An execution row with no result: a planned run, not a performed one. */
    private static final UUID PLANNED = UUID.fromString("ef004000-0000-4000-8000-000000000002");
    /** Recorded, then reclassified and moved between refreshes. */
    private static final UUID MOVED = UUID.fromString("ef004000-0000-4000-8000-000000000003");
    /** Recorded, then amended between refreshes. */
    private static final UUID AMENDED = UUID.fromString("ef004000-0000-4000-8000-000000000004");
    /** Recorded, then soft-deleted. Its fact must survive — the log is append-only. */
    private static final UUID REMOVED = UUID.fromString("ef004000-0000-4000-8000-000000000005");
    /** Umbra's only case. Recorded, and must stay invisible to Acme's refresh. */
    private static final UUID FOREIGN = UUID.fromString("ef004000-0000-4000-8000-000000000006");
    /**
     * Filed in no suite at all ({@code suite_id IS NULL}, legal since {@code V31}), recorded.
     *
     * <p>The one row in this fixture that discriminates {@code LEFT JOIN suite} from {@code JOIN
     * suite}: with the inner spelling its whole fact — not its suite columns, its <em>row</em> — is
     * never inserted, the refresh reports success, and every report over this table is quietly
     * short by however many unfiled cases the project has. §96.
     */
    private static final UUID UNFILED = UUID.fromString("ef004000-0000-4000-8000-000000000007");

    private static final UUID FEATURE_BILLING = UUID.fromString("ef005000-0000-4000-8000-000000000001");
    private static final UUID FEATURE_SEARCH = UUID.fromString("ef005000-0000-4000-8000-000000000002");
    private static final UUID FEATURE_LATER = UUID.fromString("ef005000-0000-4000-8000-000000000003");

    /**
     * A fixed instant rather than one derived from a live {@link java.time.Clock}.
     *
     * <p>Nothing in this projection is time-relative — unlike the case-health cutoff, which is —
     * so a literal is both sufficient and the only thing that makes {@code executed_at} assertable
     * against an exact value rather than a window. Whole seconds, so the microsecond truncation
     * §60 paid for cannot be what a failure is about.
     */
    private static final Instant EXECUTED_AT = Instant.parse("2026-03-01T09:15:00Z");

    @Autowired
    private ExecutionFactRefreshService refresh;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "rf-acme", "Acme", now);
        company(UMBRA, "rf-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "RFAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "RFUM", "Umbra Product", now));

        clearFactsAndExecutions();

        try (Connection acme = appConnection(ACME)) {
            // Without the project defaults there are no case_status rows and no configuration, so
            // every execution below would carry status_id NULL — and the whole class would pass by
            // projecting nothing while looking like it discriminated.
            seedCompanyDefaults(acme, ACME);
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            featureValue(acme, FEATURE_BILLING, ACME, ACME_PROJECT, "Billing", 0);
            featureValue(acme, FEATURE_SEARCH, ACME, ACME_PROJECT, "Search", 1);
            featureValue(acme, FEATURE_LATER, ACME, ACME_PROJECT, "Reporting", 2);

            suite(acme, ACME_SUITE, ACME, ACME_PROJECT, "Root", "root");
            suite(acme, ACME_OTHER_SUITE, ACME, ACME_PROJECT, "Elsewhere", "elsewhere");

            testCase(acme, RICH, ACME, ACME_PROJECT, ACME_SUITE, "TC-1", 1, "AUTOMATED", false);
            testCase(acme, PLANNED, ACME, ACME_PROJECT, ACME_SUITE, "TC-2", 2, "MANUAL", false);
            testCase(acme, MOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-3", 3, "MANUAL", false);
            testCase(acme, AMENDED, ACME, ACME_PROJECT, ACME_SUITE, "TC-4", 4, "MANUAL", false);
            testCase(acme, REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-5", 5, "MANUAL", false);
            testCase(acme, UNFILED, ACME, ACME_PROJECT, null, "TC-6", 6, "MANUAL", false);

            // RICH carries one value on each of the three dimensions the report selects on, and
            // TWO features — a single-feature fixture cannot tell an array from a scalar.
            classify(acme, ACME, RICH, "test_type", "Performance");
            classify(acme, ACME, RICH, "test_level", "API");
            classifyValue(acme, ACME, RICH, FEATURE_BILLING);
            classifyValue(acme, ACME, RICH, FEATURE_SEARCH);
            classifyValue(acme, ACME, MOVED, FEATURE_BILLING);

            run(acme, ACME_RUN, ACME, ACME_PROJECT, "TR-1", 1);

            execution(acme, ACME, ACME_PROJECT, ACME_RUN, RICH, "Passed", EXECUTED_AT, 4200L);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, PLANNED, null, null, null);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, MOVED, "Passed", EXECUTED_AT, null);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, AMENDED, "Passed", EXECUTED_AT, null);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, REMOVED, "Failed", EXECUTED_AT, null);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, UNFILED, "Passed", EXECUTED_AT, null);
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedCompanyDefaults(umbra, UMBRA);
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "Root", "root");
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, "MANUAL", false);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1);
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, FOREIGN, "Passed", EXECUTED_AT, null);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("What lands in the log, and what deliberately does not")
    class WhatIsProjected {

        @Test
        @DisplayName("a recorded execution becomes exactly one fact")
        void recordedExecutionsAreProjected() {
            refreshFor(ACME);
            assertThat(factCaseIds(ACME))
                    .containsExactlyInAnyOrder(RICH, MOVED, AMENDED, REMOVED, UNFILED);
        }

        @Test
        @DisplayName("a case filed in NO suite is still projected — the LEFT JOIN, and it is silent")
        void anUnfiledCaseIsStillProjected() {
            // §96. `test_case.suite_id` is nullable since V31, so `JOIN suite s ON s.id =
            // c.suite_id` drops the ENTIRE fact row for an unfiled case: no error, no warning, a
            // tick that logs success, and every report over this table smaller than the truth by
            // however many unfiled cases the project has. Exactly the silently-short log this
            // class's other tests exist for, arriving through a join nobody would look at twice.
            //
            // Asserted as a row AND as its two columns, because those fail for different reasons:
            // the row is the join, the NULLs are V31's two DROP NOT NULLs. With the columns still
            // NOT NULL the insert raises instead — loud, but only after somebody files nothing.
            refreshFor(ACME);

            Map<String, Object> fact = factFor(ACME, UNFILED);

            assertThat(fact.get("status_kind"))
                    .as("the execution happened; where the case is filed is not what makes it a "
                            + "result")
                    .isEqualTo("PASSED");
            assertThat(fact.get("suite_id"))
                    .as("NULL is the honest value — the case was filed nowhere when it ran")
                    .isNull();
            assertThat(fact.get("suite_path")).isNull();
        }

        @Test
        @DisplayName("an execution with no result is NOT a fact, however many times the job runs")
        void aPlannedRunIsNotAResult() {
            // THE structural discrimination. Creating a run writes one empty execution per case in
            // scope (§42); if the statement left-joined case_status and defaulted the kind, every
            // case anybody scheduled would be filed as history and the aggregate report's execution
            // count would rise the moment somebody planned work they had not done.
            refreshFor(ACME);
            refreshFor(ACME);
            assertThat(factCaseIds(ACME))
                    .as("status_id IS NULL is work outstanding, not a result")
                    .doesNotContain(PLANNED);
        }

        @Test
        @DisplayName("a soft-deleted case keeps the facts it already produced")
        void softDeletesDoNotRemoveHistory() throws SQLException {
            // The append-only decision, stated in ExecutionSource's header. This writer never
            // deletes, so filtering deleted_at on insert would make the log's contents a function
            // of when the job last ran relative to the delete — silently short, and undetectable.
            // Deleting a run does not un-run it.
            refreshFor(ACME);
            softDeleteCase(ACME, REMOVED);

            refreshFor(ACME);

            assertThat(factCaseIds(ACME))
                    .as("a result somebody recorded stays recorded")
                    .contains(REMOVED);
        }

        @Test
        @DisplayName("a second refresh writes no second row")
        void isIdempotent() {
            refreshFor(ACME);
            List<UUID> first = factCaseIds(ACME);

            int second = refreshFor(ACME);

            assertThat(factCaseIds(ACME))
                    .as("the scheduler reruns every fifteen minutes forever; a duplicate here "
                            + "doubles an execution count that nothing else can audit")
                    .containsExactlyInAnyOrderElementsOf(first);
            assertThat(second)
                    .as("ON CONFLICT DO UPDATE still reports the rows it touched")
                    .isEqualTo(first.size());
        }
    }

    @Nested
    @DisplayName("Columns — every one of them has a source, and this is where that stops being paper")
    class Columns {

        @Test
        @DisplayName("every column is filled from its source, including the ltree and the array")
        void everyColumnIsFilledFromItsSource() {
            refreshFor(ACME);
            Map<String, Object> fact = factFor(ACME, RICH);

            assertThat(fact.get("project_id")).isEqualTo(ACME_PROJECT);
            assertThat(fact.get("run_id")).isEqualTo(ACME_RUN);
            assertThat(fact.get("plan_id"))
                    .as("the run belongs to no plan, and NULL is what makes it invisible to a "
                            + "plan-keyed selection — open question 3 of the slice plan")
                    .isNull();
            assertThat(fact.get("suite_id")).isEqualTo(ACME_SUITE);
            assertThat(fact.get("suite_path"))
                    .as("ltree, carried through as text by the driver — the GIST index on this "
                            + "column is what a suite-subtree selection reads")
                    .isEqualTo("root");
            assertThat(fact.get("configuration_id")).isNotNull();
            assertThat(fact.get("status_kind")).isEqualTo("PASSED");
            assertThat(fact.get("counts_as_pass")).isEqualTo(true);
            assertThat(fact.get("automation_status")).isEqualTo("AUTOMATED");
            assertThat(fact.get("source")).isEqualTo("MANUAL");
            assertThat(fact.get("duration_ms")).isEqualTo(4200L);
            assertThat((Instant) fact.get("executed_at")).isEqualTo(EXECUTED_AT);
            assertThat(fact.get("test_type_id")).isEqualTo(valueId(ACME, "test_type", "Performance"));
            assertThat(fact.get("test_level_id")).isEqualTo(valueId(ACME, "test_level", "API"));
        }

        @Test
        @DisplayName("feature_ids holds every feature the case carries, not the first one")
        void featuresAreMultiValued() {
            // The one MULTI-cardinality dimension (§6d), and the GIN index exists for it. A scalar
            // taken from the same aggregate would satisfy every other assertion in this class and
            // silently halve the "failures by feature" section of the aggregate report.
            refreshFor(ACME);
            assertThat(featureIds(ACME, RICH))
                    .containsExactlyInAnyOrder(FEATURE_BILLING, FEATURE_SEARCH);
        }

        @Test
        @DisplayName("an unclassified case gets an empty array, not a null")
        void anUnclassifiedCaseGetsAnEmptyArray() {
            // '{}' rather than NULL is "no features", where NULL would read as "unknown". Both
            // behave alike under a GIN containment query today, which is exactly why the choice
            // has to be asserted rather than left to whichever the aggregate happened to return.
            refreshFor(ACME);
            assertThat(featureIds(ACME, AMENDED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The ON CONFLICT set-list — seven columns move and fifteen must not")
    class FrozenDimensions {

        @Test
        @DisplayName("an amended result IS corrected on the next refresh")
        void resultColumnsAreUpdated() throws SQLException {
            refreshFor(ACME);
            assertThat(factFor(ACME, AMENDED).get("status_kind")).isEqualTo("PASSED");

            amendResult(ACME, AMENDED, "Failed", EXECUTED_AT.plusSeconds(3600), 900L);
            refreshFor(ACME);

            Map<String, Object> fact = factFor(ACME, AMENDED);
            assertThat(fact.get("status_kind"))
                    .as("a corrected verdict that the log never learns about is a report that is "
                            + "wrong for ever and looks right")
                    .isEqualTo("FAILED");
            assertThat(fact.get("counts_as_pass")).isEqualTo(false);
            assertThat(fact.get("duration_ms")).isEqualTo(900L);
            assertThat((Instant) fact.get("executed_at")).isEqualTo(EXECUTED_AT.plusSeconds(3600));
        }

        @Test
        @DisplayName("the case's classification, automation status and suite are FROZEN at the fact")
        void dimensionColumnsAreNeverRewritten() throws SQLException {
            // The slice's most important assertion. A DO UPDATE naming EXCLUDED.* everywhere is
            // tidier, passes every other test in this class, and retroactively re-files last
            // quarter's results under this quarter's taxonomy — so the aggregate report's "failures
            // by feature" and "failures by suite" sections change their own history every fifteen
            // minutes and nobody can name when. A fact's dimensions are as-of the fact; that is
            // what §6e means by "deliberately duplicating dimensions to avoid joins".
            refreshFor(ACME);
            UUID suiteAtExecution = (UUID) factFor(ACME, MOVED).get("suite_id");

            reclassify(ACME, MOVED, FEATURE_LATER);
            setAutomationStatus(ACME, MOVED, "AUTOMATED");
            moveToSuite(ACME, MOVED, ACME_OTHER_SUITE);

            refreshFor(ACME);

            Map<String, Object> fact = factFor(ACME, MOVED);
            assertThat(featureIds(ACME, MOVED))
                    .as("the result was produced by a case classified as Billing; reclassifying "
                            + "the case today does not change what was tested in March")
                    .containsExactly(FEATURE_BILLING);
            assertThat(fact.get("automation_status"))
                    .as("automating a case does not make its manual results automated")
                    .isEqualTo("MANUAL");
            assertThat(fact.get("suite_id")).isEqualTo(suiteAtExecution);
            assertThat(fact.get("suite_path"))
                    .as("suite_path is frozen for the same reason and the plan did not say so — "
                            + "a moved dimension that should have been frozen has already destroyed "
                            + "the history it rewrote")
                    .isEqualTo("root");
        }
    }

    @Nested
    @DisplayName("Isolation — the statement has no company predicate and does not need one")
    class Isolation {

        @Test
        @DisplayName("refreshing one company projects nothing in another")
        void companiesAreIsolated() {
            // FORCE ROW LEVEL SECURITY covers both sides of the INSERT … SELECT: the SELECT can
            // only see the open company's executions and the INSERT can only write that company's
            // rows. That is the right design and an unasserted one is indistinguishable from a leak.
            refreshFor(ACME);

            assertThat(factCaseIds(UMBRA))
                    .as("Umbra's execution must be invisible to a refresh run in Acme's context")
                    .isEmpty();

            refreshFor(UMBRA);

            assertThat(factCaseIds(UMBRA))
                    .as("and reachable from its own")
                    .containsExactly(FOREIGN);
        }

        @Test
        @DisplayName("a refresh with no company open refuses loudly rather than writing nothing")
        void noCompanyIsAnError() {
            // The alternative is the failure the whole design exists to avoid: with the GUC unset,
            // V4's nullif makes every statement match zero rows, so an unguarded job would log a
            // successful tick having done nothing and every report would read as a project where
            // nothing has ever been executed.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> refresh.refresh())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No company context is active");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private int refreshFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            return refresh.refresh();
        }
    }

    private List<UUID> factCaseIds(UUID companyId) {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT case_id FROM execution_fact ORDER BY case_id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getObject("case_id", UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    /** One fact row as a column → value map, so an assertion reads as a sentence about a column. */
    private Map<String, Object> factFor(UUID companyId, UUID caseId) {
        Map<String, Object> row = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT project_id, run_id, plan_id, suite_id, suite_path::text AS suite_path,
                               configuration_id, status_kind, counts_as_pass, automation_status,
                               source, duration_ms, executed_at, test_type_id, test_level_id
                          FROM execution_fact WHERE case_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no fact row for case %s", caseId).isTrue();
                row.put("project_id", rows.getObject("project_id", UUID.class));
                row.put("run_id", rows.getObject("run_id", UUID.class));
                row.put("plan_id", rows.getObject("plan_id", UUID.class));
                row.put("suite_id", rows.getObject("suite_id", UUID.class));
                row.put("suite_path", rows.getString("suite_path"));
                row.put("configuration_id", rows.getObject("configuration_id", UUID.class));
                row.put("status_kind", rows.getString("status_kind"));
                row.put("counts_as_pass", rows.getBoolean("counts_as_pass"));
                row.put("automation_status", rows.getString("automation_status"));
                row.put("source", rows.getString("source"));
                row.put("duration_ms", rows.getObject("duration_ms") == null
                        ? null : rows.getLong("duration_ms"));
                row.put("executed_at", rows.getTimestamp("executed_at") == null
                        ? null : rows.getTimestamp("executed_at").toInstant());
                row.put("test_type_id", rows.getObject("test_type_id", UUID.class));
                row.put("test_level_id", rows.getObject("test_level_id", UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private List<UUID> featureIds(UUID companyId, UUID caseId) {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT unnest(feature_ids) AS feature_id FROM execution_fact "
                                + "WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getObject("feature_id", UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    private UUID valueId(UUID companyId, String dimensionKey, String name) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT v.id FROM classification_value v
                          JOIN classification_dimension d ON d.id = v.dimension_id
                         WHERE d.key = ? AND v.name = ?
                        """)) {
            statement.setString(1, dimensionKey);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no %s value named %s", dimensionKey, name).isTrue();
                return rows.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void amendResult(UUID companyId, UUID caseId, String statusName, Instant executedAt,
            long durationMs) throws SQLException {
        update(companyId, """
                UPDATE test_execution
                   SET status_id = (SELECT s.id FROM case_status s
                                     WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                       executed_at = CAST(? AS timestamptz),
                       duration_ms = CAST(? AS bigint)
                 WHERE case_id = CAST(? AS uuid)
                """, ACME_PROJECT.toString(), statusName, executedAt.toString(),
                String.valueOf(durationMs), caseId.toString());
    }

    private void reclassify(UUID companyId, UUID caseId, UUID valueId) throws SQLException {
        update(companyId, "DELETE FROM case_classification WHERE case_id = CAST(? AS uuid)",
                caseId.toString());
        try (Connection connection = appConnection(companyId)) {
            classifyValue(connection, companyId, caseId, valueId);
        }
    }

    private void setAutomationStatus(UUID companyId, UUID caseId, String status)
            throws SQLException {
        update(companyId, "UPDATE test_case SET automation_status = ? WHERE id = CAST(? AS uuid)",
                status, caseId.toString());
    }

    private void moveToSuite(UUID companyId, UUID caseId, UUID suiteId) throws SQLException {
        update(companyId, "UPDATE test_case SET suite_id = CAST(? AS uuid) WHERE id = CAST(? AS uuid)",
                suiteId.toString(), caseId.toString());
    }

    private void softDeleteCase(UUID companyId, UUID caseId) throws SQLException {
        update(companyId, "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)",
                caseId.toString());
    }

    /** One parameterised statement, every parameter bound as text — the fixtures all cast. */
    private void update(UUID companyId, String sql, String... parameters) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private void clearFactsAndExecutions() throws SQLException {
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
     * The Feature dimension is seeded <em>empty</em> by {@code tcms_seed_project_defaults} — §6d's
     * "inventing a client's feature taxonomy for them is how a controlled vocabulary gets worked
     * around" — so a fixture that wants features has to author them.
     */
    private void featureValue(Connection connection, UUID id, UUID companyId, UUID projectId,
            String name, int position) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO classification_value
                    (id, company_id, dimension_id, parent_id, path, name, position)
                SELECT CAST(? AS uuid), CAST(? AS uuid), d.id, NULL,
                       CAST(tcms_ltree_label(?) AS ltree), ?, ?
                  FROM classification_dimension d
                 WHERE d.project_id = CAST(? AS uuid) AND d.key = 'feature'
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, name);
            statement.setString(4, name);
            statement.setInt(5, position);
            statement.setString(6, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void classify(Connection connection, UUID companyId, UUID caseId, String dimensionKey,
            String valueName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, d.id
                  FROM classification_value v
                  JOIN classification_dimension d ON d.id = v.dimension_id
                 WHERE d.key = ? AND v.name = ?
                    ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, dimensionKey);
            statement.setString(4, valueName);
            statement.executeUpdate();
        }
    }

    private void classifyValue(Connection connection, UUID companyId, UUID caseId, UUID valueId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                  FROM classification_value v WHERE v.id = CAST(? AS uuid)
                    ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, valueId.toString());
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String name,
            String path) throws SQLException {
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
            statement.setString(4, "TS-" + path);
            statement.setLong(5, path.hashCode() & 0xffff);
            statement.setString(6, path);
            statement.setString(7, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, String automationStatus, boolean deleted)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', ?, now(), now(),
                        CAST(? AS timestamptz))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            // Null since V31: an unfiled case. setString(null) is what makes the CAST(? AS uuid)
            // land as NULL rather than as the string "null", which is a runtime error and not a
            // silent one — but only if the caller is allowed to pass it in the first place.
            statement.setString(4, suiteId == null ? null : suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, automationStatus);
            statement.setString(9, deleted ? "2026-01-01T00:00:00Z" : null);
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
                        'Fact projection fixture run', 'IN_PROGRESS', 'MANUAL',
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
     * @param statusName null for a planned-but-unrecorded row — which also leaves
     *                   {@code executed_at} null, because the two move together in
     *                   {@code TestExecution.record()} and a fixture that set one without the other
     *                   would be testing a state the application cannot produce.
     */
    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, String statusName, Instant executedAt, Long durationMs)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, duration_ms, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS timestamptz), ?, now(), now())
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, projectId.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedAt == null ? null : executedAt.toString());
            if (durationMs == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, durationMs);
            }
            statement.executeUpdate();
        }
    }
}
