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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>The half of R6 the cross-check cannot see.</strong>
 *
 * <p>{@code AggregateExecutionCrossCheckTest} is the oracle for the <em>policy resolution</em>: it
 * compares this report's per-status counts with the plan page's, policy by policy. Writing it left
 * a gap that phase 1 recorded in the mutation queue rather than leaving to be inferred — four of
 * its seven queued rows were expected to redden <strong>nothing</strong>, because the cross-check
 * reads one section of a report over one plan on a fixture with one configuration:
 *
 * <ul>
 *   <li><strong>R6-5</strong> — {@code LATEST_PER_CONFIG}'s partition could drop
 *       {@code configuration_id} and the class would stay green. §46 already paid for this once:
 *       resolving latest <em>globally</em> collapses a per-configuration breakdown to a single
 *       column, <em>"which is wrong in a way that looks like a working screen."</em>
 *   <li><strong>R6-7</strong> — an empty facet array read as "select nothing" gives a clean report
 *       of nothing. §99's defect, one layer down.
 *   <li><strong>R6-8</strong> — the failures-by-feature section is not read at all by the oracle,
 *       so its {@code LEFT JOIN LATERAL} could become a plain {@code unnest} and the unclassified
 *       row would vanish, leaving a report of a project that classified everything (§96's
 *       {@code JOIN suite}, second shape).
 *   <li><strong>R6-9</strong> — a malformed date swallowed rather than refused gives a report over
 *       <em>every</em> execution ever recorded, printed under a header still naming the range.
 * </ul>
 *
 * <p>This class closes them, plus <strong>R6-3</strong> (a recorded non-verdict, which the
 * cross-check fixture does not contain, so {@code WORST}'s verdict-first clause was unguarded).
 *
 * <h2>The fixture asserts its own non-vacuity first, and that is not ceremony</h2>
 *
 * <p>Every narrowing assertion below is of the form <em>"this selection returns fewer rows than
 * that one"</em>, and every one of them is trivially satisfied by a fixture where both are zero —
 * which is precisely what an RLS-scoped read returns when it is called outside a transaction
 * (§96's {@code SuiteScopeAgreementTest} shipped in exactly that state). {@link #theFixtureHolds()}
 * runs first and pins what has to be there: two configurations, two plans, a run in neither, a case
 * classified under nothing, and a recorded {@code RETEST}.
 *
 * <p>Ids are prefixed {@code c2} and slugs {@code aes-}. Hex, for {@code ExecutionFactRefreshTest}'s
 * reason.
 */
@DisplayName("Aggregate execution report — the selection, and what it must not quietly drop")
class AggregateSelectionTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("c2001000-0000-4000-8000-000000000001");

    private static final UUID BILLING = UUID.fromString("c2002000-0000-4000-8000-000000000001");
    private static final UUID CHECKOUT = UUID.fromString("c2002000-0000-4000-8000-000000000002");

    private static final UUID PLAN_A = UUID.fromString("c2005000-0000-4000-8000-000000000001");
    private static final UUID PLAN_B = UUID.fromString("c2005000-0000-4000-8000-000000000002");

    /** The second configuration. Its absence is what made R6-5 unobservable. */
    private static final UUID CONFIG_B = UUID.fromString("c2006000-0000-4000-8000-000000000001");

    private static final UUID PAYMENTS = UUID.fromString("c2007000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("c2007000-0000-4000-8000-000000000002");

    private static final UUID RUN_A_DEFAULT = UUID.fromString("c2003000-0000-4000-8000-000000000001");
    private static final UUID RUN_A_OTHER = UUID.fromString("c2003000-0000-4000-8000-000000000002");
    private static final UUID RUN_B = UUID.fromString("c2003000-0000-4000-8000-000000000003");
    /** A run created outside any plan — {@code plan_id} NULL, which the plan facet must exclude. */
    private static final UUID RUN_ADHOC = UUID.fromString("c2003000-0000-4000-8000-000000000004");
    /**
     * A second plan-less run, and it exists because {@code uq_execution_run_case} is real: one
     * execution per (run, case), so a case that was retested was retested in a <em>later run</em>.
     * The fixture's first draft put both rows in one run and every test in the class failed on the
     * insert — the constraint is the product's, and a fixture that could violate it would be
     * describing a history the schema cannot hold.
     */
    private static final UUID RUN_ADHOC_LATER =
            UUID.fromString("c2003000-0000-4000-8000-000000000005");

    /** Classified Payments. Executed on BOTH configurations, which is what R6-5 needs. */
    private static final UUID PAY_CASE = UUID.fromString("c2004000-0000-4000-8000-000000000001");
    /** Classified Refunds. */
    private static final UUID REFUND_CASE = UUID.fromString("c2004000-0000-4000-8000-000000000002");
    /** Classified under both — the row that makes the feature table exceed the headline. */
    private static final UUID BOTH_CASE = UUID.fromString("c2004000-0000-4000-8000-000000000003");
    /** Classified under nothing. Its row is {@code (no feature)} and never an omission. */
    private static final UUID UNCLASSIFIED_CASE =
            UUID.fromString("c2004000-0000-4000-8000-000000000004");
    /** Retest, then Passed — the pair that makes {@code WORST}'s verdict-first clause observable. */
    private static final UUID RETESTED_CASE = UUID.fromString("c2004000-0000-4000-8000-000000000005");
    /** One execution with no timestamp at all, which every date bound must exclude. */
    private static final UUID UNDATED_CASE = UUID.fromString("c2004000-0000-4000-8000-000000000006");

    private static final Instant MARCH = Instant.parse("2026-03-10T09:00:01Z");
    private static final Instant MARCH_LATER = Instant.parse("2026-03-10T09:00:02Z");
    private static final Instant JUNE = Instant.parse("2026-06-10T09:00:03Z");
    private static final Instant JUNE_LATER = Instant.parse("2026-06-10T09:00:04Z");
    private static final Instant JUNE_LATEST = Instant.parse("2026-06-10T09:00:05Z");
    private static final Instant APRIL = Instant.parse("2026-04-01T00:00:00Z");

    @Autowired
    private AggregateExecutionReport report;

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
            companies.save(Company.provision(ACME, "aes-acme", "Selection", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "AESL", "Selection Product", null, now));
            }
        }

        try (Connection connection = appConnection(ACME)) {
            clear(connection);
            seedCompanyDefaults(connection, ACME);
            seedProjectDefaults(connection, ACME, PROJECT);

            configuration(connection, CONFIG_B, "Firefox");

            suite(connection, BILLING, "AS-1", 1, "Billing");
            suite(connection, CHECKOUT, "AS-2", 2, "Checkout");

            featureValue(connection, PAYMENTS, "Payments", 0);
            featureValue(connection, REFUNDS, "Refunds", 1);

            testCase(connection, PAY_CASE, BILLING, "TC-1", 1);
            testCase(connection, REFUND_CASE, BILLING, "TC-2", 2);
            testCase(connection, BOTH_CASE, BILLING, "TC-3", 3);
            testCase(connection, UNCLASSIFIED_CASE, CHECKOUT, "TC-4", 4);
            testCase(connection, RETESTED_CASE, CHECKOUT, "TC-5", 5);
            testCase(connection, UNDATED_CASE, CHECKOUT, "TC-6", 6);

            classify(connection, PAY_CASE, PAYMENTS);
            classify(connection, REFUND_CASE, REFUNDS);
            classify(connection, BOTH_CASE, PAYMENTS);
            classify(connection, BOTH_CASE, REFUNDS);

            plan(connection, PLAN_A, "TP-1", 1);
            plan(connection, PLAN_B, "TP-2", 2);

            run(connection, RUN_A_DEFAULT, PLAN_A, null, "TR-1", 1);
            run(connection, RUN_A_OTHER, PLAN_A, CONFIG_B, "TR-2", 2);
            run(connection, RUN_B, PLAN_B, null, "TR-3", 3);
            run(connection, RUN_ADHOC, null, null, "TR-4", 4);
            run(connection, RUN_ADHOC_LATER, null, null, "TR-5", 5);

            // PLAN A, default configuration — March.
            execution(connection, RUN_A_DEFAULT, PAY_CASE, "Failed", MARCH);
            execution(connection, RUN_A_DEFAULT, REFUND_CASE, "Passed", MARCH_LATER);
            // PLAN A, the SECOND configuration — the same case again, so LATEST and
            // LATEST_PER_CONFIG have something to disagree about.
            execution(connection, RUN_A_OTHER, PAY_CASE, "Passed", JUNE);
            // PLAN B — June.
            execution(connection, RUN_B, BOTH_CASE, "Failed", JUNE_LATER);
            execution(connection, RUN_B, UNCLASSIFIED_CASE, "Failed", JUNE_LATEST);
            // No plan at all. Named-plan selections must not see this one.
            execution(connection, RUN_ADHOC, RETESTED_CASE, "Retest", MARCH);
            execution(connection, RUN_ADHOC_LATER, RETESTED_CASE, "Passed", MARCH_LATER);
            // Recorded, and carrying no time — outside every range by definition.
            execution(connection, RUN_ADHOC, UNDATED_CASE, "Passed", null);
        }

        refresh();
    }

    // =====================================================================

    @Test
    @DisplayName("the fixture holds what every assertion below depends on")
    void theFixtureHolds() {
        // Asserted before anything is asserted over it. Every narrowing check in this class is
        // "fewer than the whole", and a fixture that returned nothing would satisfy all of them.
        assertThat(executions(whole())).as("recorded executions").isEqualTo(8L);

        assertThat(statusCounts(whole()))
                .as("a recorded RETEST, which the cross-check fixture does not contain")
                .containsKey("RETEST");
        assertThat(featureRows(whole()))
                .as("a case classified under nothing")
                .containsKey(AggregateExecutionReport.UNCLASSIFIED);
        assertThat(featureRows(whole()).keySet())
                .as("and two features, so the union facet has something to union")
                .contains("Payments", "Refunds");
    }

    @Nested
    @DisplayName("an empty array is 'I did not narrow', never 'match nothing' (R6-7)")
    class EmptyFacets {

        @Test
        @DisplayName("an empty planIds selects everything, exactly as an absent one does")
        void emptyArraysDoNotNarrow() {
            long unnarrowed = executions(whole());
            long empties = executions(everyRow(
                    "{\"planIds\":[],\"featureIds\":[],\"configurationIds\":[],\"suitePaths\":[]}"));

            assertThat(empties)
                    .as("`IN ()` is a syntax error and `IN (NULL)` is never true — §99, in SQL")
                    .isEqualTo(unnarrowed);
            assertThat(empties).as("and the fixture is not empty").isPositive();
        }

        @Test
        @DisplayName("a selection that narrows on nothing says so in its own header block")
        void theHeaderSaysEverything() {
            assertThat(values(inTransaction(() -> compute(whole()))))
                    .contains("Every recorded execution in this project");
        }
    }

    @Nested
    @DisplayName("the facets narrow, and each is checked against a selection it must exclude")
    class Facets {

        @Test
        @DisplayName("naming a plan excludes the other plan AND the run that has none")
        void planNarrows() {
            long planA = executions(everyRow("{\"planIds\":[\"" + PLAN_A + "\"]}"));
            long both = executions(everyRow(
                    "{\"planIds\":[\"" + PLAN_A + "\",\"" + PLAN_B + "\"]}"));

            assertThat(planA).as("three executions in plan A").isEqualTo(3L);
            assertThat(both).as("plus plan B's two").isEqualTo(5L);
            // The interesting exclusion: `plan_id` is NULL for a run created outside any plan, and
            // an inner-list membership test is never true of NULL — correct here, and stated
            // because the same shape is a silent row-dropper everywhere else in this codebase.
            assertThat(both).as("and never the three ad-hoc rows").isLessThan(executions(whole()));
        }

        @Test
        @DisplayName("features UNION, and a case classified under nothing is in neither")
        void featuresUnion() {
            long payments = executions(everyRow("{\"featureIds\":[\"" + PAYMENTS + "\"]}"));
            long either = executions(everyRow(
                    "{\"featureIds\":[\"" + PAYMENTS + "\",\"" + REFUNDS + "\"]}"));

            // Payments: PAY_CASE twice + BOTH_CASE once.
            assertThat(payments).isEqualTo(3L);
            // Union, not intersection: + REFUND_CASE. Intersection would answer BOTH_CASE alone —
            // one row — which reads as a quiet project rather than as a misunderstood filter.
            assertThat(either).isEqualTo(4L);
            assertThat(either).isGreaterThan(payments);
            assertThat(executions(whole())).as("and the unclassified case is outside both")
                    .isGreaterThan(either);
        }

        @Test
        @DisplayName("a suite path matches descendant-or-self over the FROZEN path")
        void suitePathNarrows() {
            // ExecutionSource writes 's' || key_seq since §98, so Billing is `s1`. The path is what
            // the tree said when the result was recorded, which is why this facet is text and not
            // a suite id.
            long billing = executions(everyRow("{\"suitePaths\":[\"s1\"]}"));
            assertThat(billing).as("Billing's four").isEqualTo(4L);
            assertThat(billing).isLessThan(executions(whole()));
        }
    }

    @Nested
    @DisplayName("two configurations, which is what makes LATEST_PER_CONFIG observable (R6-5)")
    class Configurations {

        @Test
        @DisplayName("LATEST collapses a case run twice; LATEST_PER_CONFIG keeps one per configuration")
        void perConfigurationDiffersFromLatest() {
            long latest = executions(parameters("{\"policy\":\"LATEST\"}"));
            long perConfig = executions(parameters("{\"policy\":\"LATEST_PER_CONFIG\"}"));

            // PAY_CASE ran on both configurations, so exactly one row separates the two policies.
            assertThat(perConfig)
                    .as("dropping configuration_id from the partition makes these equal — §46's "
                            + "byConfiguration collapsing to a single column")
                    .isEqualTo(latest + 1);
        }

        @Test
        @DisplayName("naming one configuration excludes the other's results")
        void configurationNarrows() {
            long other = executions(everyRow("{\"configurationIds\":[\"" + CONFIG_B + "\"]}"));

            assertThat(other).as("only the run on Firefox").isEqualTo(1L);
            assertThat(other).isLessThan(executions(whole()));
        }
    }

    @Nested
    @DisplayName("the date range — half-open, and a bound excludes undated rows (R6-9)")
    class Dates {

        @Test
        @DisplayName("`to` is exclusive and `from` is inclusive, on the same instant")
        void theRangeIsHalfOpen() {
            String at = MARCH.toString();

            long fromThatInstant = executions(everyRow("{\"from\":\"" + at + "\"}"));
            long toThatInstant = executions(everyRow("{\"to\":\"" + at + "\"}"));

            // The two must not both contain the row at the boundary, or two adjacent monthly
            // reports double-count whatever landed exactly on midnight.
            assertThat(fromThatInstant + toThatInstant)
                    .as("every dated row counted exactly once across the split")
                    .isEqualTo(7L);
            assertThat(toThatInstant).as("nothing is recorded before March").isZero();
        }

        @Test
        @DisplayName("a bound excludes a row with no timestamp, which is what a range MEANS")
        void undatedRowsAreOutsideEveryRange() {
            long unnarrowed = executions(whole());
            long fromTheBeginningOfTime = executions(everyRow("{\"from\":\"2000-01-01T00:00:00Z\"}"));

            assertThat(fromTheBeginningOfTime)
                    .as("the undated execution is not in a range that starts before everything")
                    .isEqualTo(unnarrowed - 1);
        }

        @Test
        @DisplayName("a range splits the fixture into March and June with nothing lost")
        void aRangeSelectsItsOwnMonths() {
            long before = executions(everyRow("{\"to\":\"" + APRIL + "\"}"));
            long after = executions(everyRow("{\"from\":\"" + APRIL + "\"}"));

            assertThat(before).as("March").isEqualTo(4L);
            assertThat(after).as("June").isEqualTo(3L);
            assertThat(before + after).as("and the undated row is in neither").isEqualTo(7L);
        }

        @Test
        @DisplayName("a malformed date is refused, not swallowed")
        void aMalformedDateIsRefused() {
            // Swallowed, this is a report over EVERY execution ever recorded, printed under a
            // header still claiming the range the caller typed.
            assertThatThrownBy(() -> inTransaction(() -> compute(parameters("{\"from\":\"yesterday\"}"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from");
        }

        @Test
        @DisplayName("a backwards range is refused rather than swapped")
        void aBackwardsRangeIsRefused() {
            assertThatThrownBy(() -> inTransaction(() -> compute(parameters(
                    "{\"from\":\"2026-06-01T00:00:00Z\",\"to\":\"2026-03-01T00:00:00Z\"}"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("covers nothing");
        }

        @Test
        @DisplayName("an unknown policy is refused and the message names the five that exist")
        void anUnknownPolicyIsRefused() {
            assertThatThrownBy(() -> inTransaction(() -> compute(parameters("{\"policy\":\"NEWEST\"}"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LATEST");
        }
    }

    @Nested
    @DisplayName("the feature section — the one the oracle never reads (R6-8)")
    class Features {

        @Test
        @DisplayName("a case classified under nothing is a ROW, not an omission")
        void unclassifiedIsARow() {
            Map<String, Long> rows = featureRows(whole());

            assertThat(rows)
                    .as("a plain unnest drops this row and the report then reads as a project "
                            + "that classified everything — §96's JOIN suite, second shape")
                    // Four: the unclassified case's one, the retested case's two, and the undated
                    // one. Every execution whose case carries no feature lands in this single row.
                    .containsEntry(AggregateExecutionReport.UNCLASSIFIED, 4L);
        }

        @Test
        @DisplayName("a case with two features is counted under both, so the table exceeds the headline")
        void multiClassifiedCasesAreCountedTwice() {
            Map<String, Long> rows = featureRows(whole());
            long headline = executions(whole());

            assertThat(rows).containsEntry("Payments", 3L).containsEntry("Refunds", 2L);
            assertThat(rows.values().stream().mapToLong(Long::longValue).sum())
                    .as("stated in the narrative rather than left to be found by subtraction")
                    .isGreaterThan(headline);
            assertThat(narrative(inTransaction(() -> compute(whole())), "Failures by feature"))
                    .contains("add up to more than the headline");
        }

        @Test
        @DisplayName("a retired feature still names last quarter's failures")
        void labelsComeFromTheValueTableAndNotFromALiveLookup() throws SQLException {
            try (Connection connection = appConnection(ACME)) {
                softDelete(connection, PAYMENTS);
            }

            assertThat(featureRows(whole()))
                    .as("findAllById on purpose: history does not un-happen when a value retires")
                    .containsKey("Payments");
        }
    }

    @Nested
    @DisplayName("WORST prefers a recorded verdict to a non-verdict (R6-3)")
    class Verdicts {

        @Test
        @DisplayName("a case that was Retest and then Passed reads as PASSED under WORST")
        void aVerdictOutranksARetest() {
            Map<String, Long> worst = statusCounts(parameters(
                    "{\"policy\":\"WORST\",\"suitePaths\":[\"s2\"]}"));

            // Without the verdict-first clause, RETEST ranks worse than PASSED and this case reads
            // as owed a verdict — a case that has passed in every run it was recorded in showing up
            // as outstanding work.
            assertThat(worst.getOrDefault("PASSED", 0L)).isEqualTo(2L);
            assertThat(worst.getOrDefault("RETEST", 0L)).isZero();
        }
    }

    // =====================================================================

    private static ReportParameters parameters(String json) {
        return ReportParameters.parse(json);
    }

    /**
     * The same selection with {@code policy: ALL}.
     *
     * <p>Every count in this class is of <em>rows the selection contains</em>, and the default
     * policy is {@code LATEST} — which collapses a case's several results into one before anything
     * is counted. The first draft asserted 8 against a fixture of 8 executions over 6 cases and got
     * 6: a correct answer to a different question. Narrowing is a property of the {@code WHERE}
     * clause, so the tests that measure it turn the collapse off; the tests that are <em>about</em>
     * the collapse name their policy explicitly.
     */
    private static ReportParameters everyRow(String json) {
        String body = json.substring(1).trim();
        return parameters("{\"policy\":\"ALL\"" + (body.startsWith("}") ? "" : ",") + body);
    }

    private static ReportParameters whole() {
        return everyRow("{}");
    }

    private ReportModel compute(ReportParameters parameters) {
        return report.compute(PROJECT, parameters);
    }

    /** The headline "Contributing executions", read out of the rendered model. */
    private long executions(ReportParameters parameters) {
        ReportModel model = inTransaction(() -> compute(parameters));
        return Long.parseLong(model.metadata().stream()
                .filter(entry -> "Contributing executions".equals(entry.label()))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElseThrow());
    }

    private Map<String, Long> statusCounts(ReportParameters parameters) {
        return tableColumn(inTransaction(() -> compute(parameters)), "Results", 1);
    }

    /** Feature → executions, from the failures-by-feature table. */
    private Map<String, Long> featureRows(ReportParameters parameters) {
        return tableColumn(inTransaction(() -> compute(parameters)), "Failures by feature", 1);
    }

    private static Map<String, Long> tableColumn(ReportModel model, String heading, int column) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (ReportModel.Section section : model.sections()) {
            if (!heading.equals(section.heading()) || section.table() == null) {
                continue;
            }
            for (List<String> row : section.table().rows()) {
                values.put(row.get(0), Long.parseLong(row.get(column)));
            }
        }
        return values;
    }

    private static List<String> values(ReportModel model) {
        return model.metadata().stream().map(ReportModel.Entry::value).toList();
    }

    private static String narrative(ReportModel model, String heading) {
        return model.sections().stream()
                .filter(section -> heading.equals(section.heading()))
                .map(ReportModel.Section::narrative)
                .findFirst()
                .orElse("");
    }

    private void refresh() {
        try (var ignored = CompanyContext.open(ACME)) {
            facts.refresh();
        }
    }

    /**
     * In a transaction, not merely in a company context — outside one, {@code app.current_company}
     * is unset and RLS answers zero rows, which would satisfy every narrowing assertion here.
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
                "case_classification", "test_case_version", "test_case", "suite")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
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

    private void configuration(Connection connection, UUID id, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration (id, company_id, project_id, name, attributes, is_default)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST('{}' AS jsonb),
                        false)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, name);
            statement.executeUpdate();
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

    /** The Feature dimension is seeded empty (§6d), so a fixture that wants features authors them. */
    private void featureValue(Connection connection, UUID id, String name, int position)
            throws SQLException {
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
            statement.setString(2, ACME.toString());
            statement.setString(3, name);
            statement.setString(4, name);
            statement.setInt(5, position);
            statement.setString(6, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void classify(Connection connection, UUID caseId, UUID valueId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                  FROM classification_value v WHERE v.id = CAST(? AS uuid)
                    ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, valueId.toString());
            statement.executeUpdate();
        }
    }

    private void softDelete(Connection connection, UUID valueId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE classification_value SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, valueId.toString());
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

    private void plan(Connection connection, UUID id, String key, long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_plan
                    (id, company_id, project_id, key, key_seq, name, status, current_version_no,
                     lock_version, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Selection fixture plan', 'DRAFT', 1, 0, now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID planId, UUID configurationId, String key,
            long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, plan_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Selection fixture run', 'IN_PROGRESS', 'MANUAL',
                        COALESCE(CAST(? AS uuid),
                                 (SELECT c.id FROM configuration c
                                   WHERE c.project_id = CAST(? AS uuid) AND c.is_default)),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, planId == null ? null : planId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, configurationId == null ? null : configurationId.toString());
            statement.setString(8, PROJECT.toString());
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
