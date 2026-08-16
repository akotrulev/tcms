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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
 * <strong>R7 — the three sections that are about the executions the aggregation policy collapses.</strong>
 *
 * <p>§6e calls the first of them <em>"the single most valuable section"</em>: cases that both passed
 * and failed within the selection, which is <em>"the flaky-test and environment-instability signal,
 * and it's invisible in any single-run report"</em>. Beside it, the manual-vs-automated split and the
 * first-attempt-vs-after-retry pass rate.
 *
 * <h2>What this class exists to catch, and it is one thing above all others</h2>
 *
 * <p>R6's sections read the <em>resolved</em> row set — one row per case under {@code LATEST}, which
 * is the default and the policy almost every report will be generated under. Written the obvious way,
 * over the same CTE the rest of the report uses, <strong>all three of R7's sections are
 * permanently empty</strong>: a case with one row cannot disagree with itself, cannot have a first
 * attempt distinct from its last, and its retries have already been discarded. The report would print
 * "no case both passed and failed" for every project, for ever — a reassuring sentence, rendered
 * perfectly, in the section the milestone was written for.
 *
 * <p>So {@link #theCalloutSurvivesTheDefaultPolicy()} and
 * {@link Retries#theRetryOutcomesIgnoreThePolicy()} are the two tests that matter here, and both are
 * written in the direction that fails against the tempting implementation. §66, §99 and R6's own
 * phase-3 finding are the same shape: an assertion written the natural way round is green against the
 * feature not existing.
 *
 * <h2>The fixture's six cases each exist to separate two readings</h2>
 *
 * <ul>
 *   <li>{@link #FLAKY} — failed then passed, <em>same configuration, same version</em>. The only
 *       genuine instability in the fixture.
 *   <li>{@link #CONFIG_SPLIT} — failed on one configuration, passed on the other. A disagreement,
 *       and <strong>not</strong> a flake: §6p's rule is that alternation means something only within
 *       one configuration and one version.
 *   <li>{@link #VERSION_SPLIT} — failed at version 1, passed at version 2. Same rule, other axis.
 *   <li>{@link #BLOCKED_THEN_PASSED} — the row that discriminates against the slice plan's own
 *       proposed query shape. A {@code HAVING} over distinct {@code counts_as_pass} values lists this
 *       case as an inconsistency, and it is not one: nothing failed. §105 settled the general form —
 *       blocked stays out of the failure count.
 *   <li>{@link #STEADY} — one pass. Must appear in no callout.
 *   <li>{@link #ALWAYS_FAILING} — two failures. Must appear in no callout either, which is the other
 *       half: the section is about <em>disagreement</em>, not about failure.
 * </ul>
 *
 * <p>Ids are prefixed {@code c3} and slugs {@code avr-}. Hex, for {@code ExecutionFactRefreshTest}'s
 * reason.
 */
@DisplayName("Aggregate execution report — inconsistency, the automation split, and retries")
class AggregateVariabilityTest extends IntegrationTestBase {

    private static final String INCONSISTENCY = "Inconsistent results";
    private static final String SPLIT = "Manual and automated";
    private static final String RETRIES = "First attempt and retries";
    private static final String QUARANTINE = "Quarantined cases";

    /** {@code case_flake.quarantined_by} is a foreign key, so the decision needs a decider. */
    private static final UUID QUARANTINER =
            UUID.fromString("c3007000-0000-4000-8000-000000000001");

    private static final UUID ACME = UUID.fromString("c3000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("c3001000-0000-4000-8000-000000000001");

    private static final UUID BILLING = UUID.fromString("c3002000-0000-4000-8000-000000000001");

    /** The second configuration — what makes "across configurations" expressible at all. */
    private static final UUID FIREFOX = UUID.fromString("c3006000-0000-4000-8000-000000000001");

    private static final UUID RUN_ONE = UUID.fromString("c3003000-0000-4000-8000-000000000001");
    private static final UUID RUN_TWO = UUID.fromString("c3003000-0000-4000-8000-000000000002");
    /** On the second configuration, and ingested — two facets at once, deliberately. */
    private static final UUID RUN_INGESTED = UUID.fromString("c3003000-0000-4000-8000-000000000003");

    /** Failed, then passed, one configuration, one version. The genuine flake. */
    private static final UUID FLAKY = UUID.fromString("c3004000-0000-4000-8000-000000000001");
    /** Failed on the default configuration, passed on Firefox. */
    private static final UUID CONFIG_SPLIT = UUID.fromString("c3004000-0000-4000-8000-000000000002");
    /** Failed at version 1, passed at version 2. */
    private static final UUID VERSION_SPLIT = UUID.fromString("c3004000-0000-4000-8000-000000000003");
    /** Blocked, then passed. No failure anywhere, so no inconsistency. */
    private static final UUID BLOCKED_THEN_PASSED =
            UUID.fromString("c3004000-0000-4000-8000-000000000004");
    /** One pass, never retried. */
    private static final UUID STEADY = UUID.fromString("c3004000-0000-4000-8000-000000000005");
    /** Failed twice. Consistent, and consistently bad. */
    private static final UUID ALWAYS_FAILING = UUID.fromString("c3004000-0000-4000-8000-000000000006");

    /** Distinct to the second, so no {@code execution_id} tiebreak is ever consulted. */
    private static final Instant T1 = Instant.parse("2026-05-01T09:00:01Z");
    private static final Instant T2 = Instant.parse("2026-05-01T09:00:02Z");
    private static final Instant T3 = Instant.parse("2026-05-01T09:00:03Z");
    private static final Instant T4 = Instant.parse("2026-05-01T09:00:04Z");
    private static final Instant T5 = Instant.parse("2026-05-02T09:00:05Z");
    private static final Instant T6 = Instant.parse("2026-05-02T09:00:06Z");
    private static final Instant T7 = Instant.parse("2026-05-02T09:00:07Z");
    private static final Instant T8 = Instant.parse("2026-05-02T09:00:08Z");
    private static final Instant T9 = Instant.parse("2026-05-02T09:00:09Z");
    private static final Instant T10 = Instant.parse("2026-05-02T09:00:10Z");
    private static final Instant T11 = Instant.parse("2026-05-02T09:00:11Z");

    /** Excludes every second attempt, so the whole fixture reads as consistent under it. */
    private static final Instant FIRST_ATTEMPTS_ONLY = Instant.parse("2026-05-02T00:00:00Z");

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
            companies.save(Company.provision(ACME, "avr-acme", "Variability", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(
                        Project.create(PROJECT, ACME, "AVAR", "Variability Product", null, now));
            }
        }

        try (Connection connection = appConnection(ACME)) {
            clear(connection);
            seedCompanyDefaults(connection, ACME);
            seedProjectDefaults(connection, ACME, PROJECT);

            // Instance-level and never cleared, so this is an upsert: `quarantined_by` is a foreign
            // key and `case_flake_quarantine_complete` will not take a quarantine without one.
            decider(connection);
            configuration(connection, FIREFOX, "Firefox");
            suite(connection, BILLING, "AS-1", 1, "Billing");

            testCase(connection, FLAKY, "TC-1", 1, "MANUAL");
            testCase(connection, CONFIG_SPLIT, "TC-2", 2, "AUTOMATED");
            testCase(connection, VERSION_SPLIT, "TC-3", 3, "AUTOMATED");
            testCase(connection, BLOCKED_THEN_PASSED, "TC-4", 4, "MANUAL");
            testCase(connection, STEADY, "TC-5", 5, "MANUAL");
            testCase(connection, ALWAYS_FAILING, "TC-6", 6, "AUTOMATED");

            // A second version of one case, and only of that one: the version axis has to be
            // separable from the configuration axis or the two readings cannot be told apart.
            version(connection, VERSION_SPLIT, 2);

            run(connection, RUN_ONE, null, "TR-1", 1, "MANUAL");
            run(connection, RUN_TWO, null, "TR-2", 2, "MANUAL");
            run(connection, RUN_INGESTED, FIREFOX, "TR-3", 3, "INGESTED");

            // First attempts — all before FIRST_ATTEMPTS_ONLY, so a date-narrowed selection can ask
            // what the report says when nothing has been retried yet.
            execution(connection, RUN_ONE, FLAKY, "Failed", T1, 1);
            execution(connection, RUN_ONE, CONFIG_SPLIT, "Failed", T2, 1);
            execution(connection, RUN_ONE, VERSION_SPLIT, "Failed", T3, 1);
            execution(connection, RUN_ONE, BLOCKED_THEN_PASSED, "Blocked", T4, 1);

            // Second attempts. uq_execution_run_case is real — one execution per (run, case) — so
            // every retry here is a later run, which is what a retry is in this product.
            execution(connection, RUN_TWO, FLAKY, "Passed", T5, 1);
            execution(connection, RUN_INGESTED, CONFIG_SPLIT, "Passed", T6, 1);
            execution(connection, RUN_TWO, VERSION_SPLIT, "Passed", T7, 2);
            execution(connection, RUN_TWO, BLOCKED_THEN_PASSED, "Passed", T8, 1);

            execution(connection, RUN_ONE, STEADY, "Passed", T9, 1);
            execution(connection, RUN_ONE, ALWAYS_FAILING, "Failed", T10, 1);
            execution(connection, RUN_TWO, ALWAYS_FAILING, "Failed", T11, 1);
        }

        refresh();
    }

    // =====================================================================

    @Test
    @DisplayName("the fixture holds what every assertion below depends on")
    void theFixtureHolds() {
        // Asserted before anything is asserted over it. A read called outside a transaction returns
        // zero rows under RLS and would satisfy most of the "is not listed" checks below (§96).
        assertThat(executions(policy("ALL"))).as("recorded executions").isEqualTo(11L);
        // And the default policy collapses them to one per case, which is precisely what makes R7's
        // three sections unanswerable from the resolved set.
        assertThat(executions(whole())).as("cases, under the default policy").isEqualTo(6L);

        assertThat(splitRows(whole()).keySet())
                .as("both automation statuses and both sources are represented")
                .contains("AUTOMATED · INGESTED", "AUTOMATED · MANUAL", "MANUAL · MANUAL");
    }

    @Nested
    @DisplayName("quarantine — a second pass rate, and a first that does not move")
    class Quarantine {

        @Test
        @DisplayName("with nothing quarantined both rates agree and the section says so")
        void anEmptyQuarantineIsStatedRatherThanOmitted() {
            // Computed and printed when there is nothing to report, for the reason every status row
            // here is: a line that appears only when there is something to say cannot be told from
            // a build that does not compute it.
            ReportModel model = inTransaction(() -> compute(policy("ALL")));

            assertThat(value(model, "Pass rate (of contributing executions)"))
                    .isEqualTo(value(model, "Pass rate (excluding quarantined cases)"));
            assertThat(value(model, "Quarantined cases in this selection"))
                    .startsWith("0 (0 executions");
            assertThat(narrative(model, QUARANTINE))
                    .contains("No case in this project is quarantined");
        }

        @Test
        @DisplayName("the headline pass rate and the status breakdown do not move")
        void theCrossCheckedNumbersAreUntouched() {
            // THE constraint of this slice. AggregateExecutionCrossCheckTest pins these counts
            // against GET /plans/{planId}/statistics, and PlanStatisticsService lives in `execution`
            // — a module that may not read case_flake at all. An exclusion applied HERE and not
            // there would break the guard §46 paid for in defects, by design, leaving nothing to
            // tell it from the drift it exists to catch.
            ReportModel before = inTransaction(() -> compute(policy("ALL")));
            String headline = value(before, "Pass rate (of contributing executions)");
            List<List<String>> statuses = table(before, "Results").rows();

            quarantine(ALWAYS_FAILING, "Red in every run since the migration");

            ReportModel after = inTransaction(() -> compute(policy("ALL")));

            assertThat(value(after, "Pass rate (of contributing executions)")).isEqualTo(headline);
            assertThat(value(after, "Contributing executions")).isEqualTo("11");
            assertThat(table(after, "Results").rows()).isEqualTo(statuses);
        }

        @Test
        @DisplayName("the excluded rate DOES move, or the feature is a note field with ceremony")
        void theSecondRateMoves() {
            // The other half, and the one a snapshot column on execution_fact would have failed: a
            // case is quarantined BECAUSE it went red, so every result worth discounting was filed
            // before the decision. A frozen dimension would read false on all of them and this
            // assertion would find the two rates still equal.
            ReportModel before = inTransaction(() -> compute(policy("ALL")));
            String both = value(before, "Pass rate (of contributing executions)");

            quarantine(ALWAYS_FAILING, "Red in every run since the migration");

            ReportModel after = inTransaction(() -> compute(policy("ALL")));

            assertThat(value(after, "Pass rate (excluding quarantined cases)"))
                    .as("two failures removed from the denominator must raise the rate")
                    .isNotEqualTo(both);
            assertThat(value(after, "Quarantined cases in this selection"))
                    .isEqualTo("1 (2 executions, not counted in the rate above)");
        }

        @Test
        @DisplayName("the section names the case and the sentence somebody wrote")
        void theReasonIsOnTheFaceOfTheReport() {
            // CaseQuarantineService's header: "when gating reads this column it must say so on the
            // face of the report". The reason is the column that makes the exclusion evaluable
            // without opening the test.
            quarantine(ALWAYS_FAILING, "Red in every run since the migration");

            ReportModel.Table table = table(inTransaction(() -> compute(policy("ALL"))), QUARANTINE);

            assertThat(table.columns()).containsExactly("Case", "Reason", "Quarantined at");
            assertThat(table.rows()).hasSize(1);
            assertThat(table.rows().getFirst().get(0)).isEqualTo("TC-6 — Case TC-6");
            assertThat(table.rows().getFirst().get(1))
                    .isEqualTo("Red in every run since the migration");
        }

        @Test
        @DisplayName("the exclusion follows the selection's policy rather than the raw fact log")
        void theExclusionIsCountedOverTheRESOLVEDSet() {
            // The FILTERs sit on `resolved`, not on `selected`. ALWAYS_FAILING has two executions
            // and the default policy collapses it to one, so the same quarantine must count 2 under
            // ALL and 1 under the default. Counting over `selected` would report two exclusions
            // from a denominator that only ever held one, and the arithmetic would stop closing.
            quarantine(ALWAYS_FAILING, "Red in every run since the migration");

            assertThat(value(inTransaction(() -> compute(policy("ALL"))),
                    "Quarantined cases in this selection"))
                    .isEqualTo("1 (2 executions, not counted in the rate above)");
            assertThat(value(inTransaction(() -> compute(whole())),
                    "Quarantined cases in this selection"))
                    .isEqualTo("1 (1 execution, not counted in the rate above)");
        }
    }

    @Nested
    @DisplayName("the inconsistency callout — §6e's most valuable section")
    class Inconsistency {

        @Test
        @DisplayName("a case that both passed and failed is listed, with both counts")
        void aCaseThatPassedAndFailedIsListed() {
            Map<String, List<String>> rows = inconsistencyRows(whole());

            assertThat(rows).containsKey("TC-1 — Case TC-1");
            List<String> flaky = rows.get("TC-1 — Case TC-1");
            assertThat(flaky.get(1)).as("executions").isEqualTo("2");
            assertThat(flaky.get(2)).as("passed").isEqualTo("1");
            assertThat(flaky.get(3)).as("failed").isEqualTo("1");
        }

        /**
         * <strong>The test this class was written for.</strong>
         *
         * <p>{@code LATEST} is the default policy and collapses every case to one row. A section
         * computed over the resolved set is therefore empty under it — always, in every project —
         * and the report reads as one with no instability to report.
         */
        @Test
        @DisplayName("the callout survives the DEFAULT policy, which collapses the very rows it reads")
        void theCalloutSurvivesTheDefaultPolicy() {
            assertThat(inconsistencyRows(policy("LATEST")).keySet())
                    .as("the policy collapses a case to one row; this section must not read that")
                    .contains("TC-1 — Case TC-1");
            assertThat(inconsistencyRows(policy("LATEST")))
                    .as("and it is the same answer under every policy, because it ignores all of them")
                    .isEqualTo(inconsistencyRows(policy("ALL")))
                    .isEqualTo(inconsistencyRows(policy("WORST")));
        }

        @Test
        @DisplayName("blocked then passed is not an inconsistency — nothing failed")
        void blockedThenPassedIsNotListed() {
            assertThat(inconsistencyRows(whole()).keySet())
                    .as("a HAVING over distinct counts_as_pass values would list it (§105)")
                    .doesNotContain("TC-4 — Case TC-4");
        }

        @Test
        @DisplayName("a steady case and an always-failing case are both absent")
        void agreementIsNotListedInEitherDirection() {
            assertThat(inconsistencyRows(whole()).keySet())
                    .doesNotContain("TC-5 — Case TC-5", "TC-6 — Case TC-6");
        }

        @Test
        @DisplayName("the row says where the disagreement is — within a cell, or across one axis")
        void theRowSaysWhereTheDisagreementIs() {
            Map<String, List<String>> rows = inconsistencyRows(whole());

            assertThat(rows.get("TC-1 — Case TC-1").get(4))
                    .as("one configuration, one version — the only genuine instability")
                    .isEqualTo("Within one configuration and version");
            assertThat(rows.get("TC-2 — Case TC-2").get(4))
                    .as("passed on one configuration and failed on another — a difference, not a flake")
                    .isEqualTo("Across configurations");
            assertThat(rows.get("TC-3 — Case TC-3").get(4))
                    .as("failed at one version and passed at the next — change, not a flake (§6p)")
                    .isEqualTo("Across versions");
        }

        @Test
        @DisplayName("the case is named by its key and title, never by a UUID")
        void theCaseIsNamedByItsKey() {
            assertThat(inconsistencyRows(whole()).keySet())
                    .allSatisfy(label -> assertThat(label).doesNotContain("c3004000"));
        }

        @Test
        @DisplayName("nothing inconsistent is a sentence saying so, never an empty table")
        void nothingInconsistentIsSaidOutLoud() {
            ReportModel model = inTransaction(() -> compute(everyRow(
                    "{\"to\":\"" + FIRST_ATTEMPTS_ONLY + "\"}")));

            assertThat(table(model, INCONSISTENCY))
                    .as("no case has been retried inside this range, so there is nothing to list")
                    .isNull();
            assertThat(narrative(model, INCONSISTENCY))
                    .as("and the section says that rather than rendering blank")
                    .contains("No case in this selection both passed and failed");
        }
    }

    @Nested
    @DisplayName("the manual and automated split — two columns that mean different things")
    class Split {

        @Test
        @DisplayName("the split is a cross-tab of the case's status and the result's source")
        void theSplitIsACrossTab() {
            Map<String, List<String>> rows = splitRows(policy("LATEST"));

            // Both dimensions on every row. Picking one column and calling it "manual vs automated"
            // is the thing this section refuses to do: automation_status is what the CASE was when it
            // ran, source is how the RESULT arrived, and they disagree in both directions.
            assertThat(rows.keySet()).contains("AUTOMATED · MANUAL");
            assertThat(rows.get("AUTOMATED · MANUAL").get(1))
                    .as("two automated cases whose latest result was recorded by hand")
                    .isEqualTo("2");
        }

        @Test
        @DisplayName("the pass rate is of the row's own executions")
        void thePassRateIsPerRow() {
            List<String> byHand = splitRows(policy("LATEST")).get("AUTOMATED · MANUAL");

            assertThat(byHand.get(2)).as("passed, of that row").isEqualTo("1");
            // 1 of 2, where the report's own headline is 5 of 6 — so a share computed against the
            // wrong denominator cannot pass this.
            assertThat(byHand.get(3)).as("and its share, not the report's").isEqualTo("50.0%");
        }

        @Test
        @DisplayName("the mismatch between the two columns is named, not left to be spotted")
        void theMismatchIsNamed() {
            assertThat(narrative(inTransaction(() -> compute(whole())), SPLIT))
                    .contains("automation status is the case's", "not a category");
        }
    }

    @Nested
    @DisplayName("first attempt and retries — the section the policy would erase")
    class Retries {

        @Test
        @DisplayName("the three outcomes are exhaustive and counted per case, configuration and version")
        void theThreeOutcomesAreExhaustive() {
            Map<String, Long> outcomes = retryOutcomes(whole());

            // Eight groups over eleven executions: FLAKY, CONFIG_SPLIT twice (two configurations),
            // VERSION_SPLIT twice (two versions), BLOCKED_THEN_PASSED, STEADY, ALWAYS_FAILING.
            assertThat(outcomes.values().stream().mapToLong(Long::longValue).sum())
                    .as("the unit is a group and not an execution, and the fixture makes them differ")
                    .isEqualTo(8L);
            assertThat(outcomes.get("Passed on the first attempt"))
                    .as("CONFIG_SPLIT on Firefox, VERSION_SPLIT at version 2, STEADY")
                    .isEqualTo(3L);
            assertThat(outcomes.get("Passed only after a retry"))
                    .as("FLAKY, and BLOCKED_THEN_PASSED — a retry is not only a retry of a failure")
                    .isEqualTo(2L);
            assertThat(outcomes.get("Never passed"))
                    .as("CONFIG_SPLIT on the default configuration, VERSION_SPLIT at v1, ALWAYS_FAILING")
                    .isEqualTo(3L);
        }

        /**
         * The second test written against the tempting implementation: under {@code LATEST} every
         * group has one row, so "passed only after a retry" is zero and the section reports a project
         * where every test passes first time.
         */
        @Test
        @DisplayName("the outcomes ignore the policy, because the policy discards the retries")
        void theRetryOutcomesIgnoreThePolicy() {
            assertThat(retryOutcomes(policy("LATEST")))
                    .as("the default policy must not erase this section")
                    .isEqualTo(retryOutcomes(policy("ALL")))
                    .isEqualTo(retryOutcomes(policy("WORST")));
            assertThat(retryOutcomes(policy("LATEST")).get("Passed only after a retry"))
                    .as("and it is non-zero, so the equality above is not two zeroes agreeing")
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("the unit is named in the column heading, not only in the narrative")
        void theUnitIsNamedInTheColumnHeading() {
            ReportModel.Table table = table(inTransaction(() -> compute(whole())), RETRIES);

            assertThat(table).isNotNull();
            assertThat(table.columns().get(1))
                    .as("a reader must not add these to the headline's execution count")
                    .isEqualTo("Case, configuration and version");
        }
    }

    @Test
    @DisplayName("a selection matching nothing says so in all three sections")
    void anEmptySelectionSaysSoEverywhere() {
        ReportModel model = inTransaction(() -> compute(everyRow(
                "{\"planIds\":[\"c3005000-0000-4000-8000-0000000000ff\"]}")));

        for (String heading : List.of(INCONSISTENCY, SPLIT, RETRIES)) {
            assertThat(table(model, heading))
                    .as("%s renders a sentence, never an empty table", heading)
                    .isNull();
            assertThat(narrative(model, heading)).as("%s", heading).isNotBlank();
        }
    }

    // =====================================================================

    private static ReportParameters whole() {
        return everyRow("{}");
    }

    private static ReportParameters policy(String name) {
        return everyRow("{\"policy\":\"" + name + "\"}");
    }

    private static ReportParameters everyRow(String json) {
        return ReportParameters.parse(json);
    }

    private ReportModel compute(ReportParameters parameters) {
        return report.compute(PROJECT, parameters);
    }

    private long executions(ReportParameters parameters) {
        ReportModel model = inTransaction(() -> compute(parameters));
        return Long.parseLong(model.metadata().stream()
                .filter(entry -> "Contributing executions".equals(entry.label()))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElseThrow());
    }

    /** First column → the whole row, so a test can assert any cell of it by position. */
    private Map<String, List<String>> rowsByLabel(ReportParameters parameters, String heading) {
        ReportModel.Table table = table(inTransaction(() -> compute(parameters)), heading);
        Map<String, List<String>> rows = new LinkedHashMap<>();
        if (table != null) {
            for (List<String> row : table.rows()) {
                rows.put(row.get(0), List.copyOf(row));
            }
        }
        return rows;
    }

    private Map<String, List<String>> inconsistencyRows(ReportParameters parameters) {
        return rowsByLabel(parameters, INCONSISTENCY);
    }

    /** Keyed {@code "<automation status> · <source>"} — the row's two dimensions, joined. */
    private Map<String, List<String>> splitRows(ReportParameters parameters) {
        ReportModel.Table table = table(inTransaction(() -> compute(parameters)), SPLIT);
        Map<String, List<String>> rows = new LinkedHashMap<>();
        if (table != null) {
            for (List<String> row : table.rows()) {
                // The two dimensions collapse into the key, so cell 1 of the value is the first
                // number — which is what every assertion in Split reads.
                List<String> cells = new ArrayList<>();
                cells.add(row.get(0) + " · " + row.get(1));
                cells.addAll(row.subList(2, row.size()));
                rows.put(row.get(0) + " · " + row.get(1), List.copyOf(cells));
            }
        }
        return rows;
    }

    private Map<String, Long> retryOutcomes(ReportParameters parameters) {
        Map<String, Long> outcomes = new LinkedHashMap<>();
        rowsByLabel(parameters, RETRIES)
                .forEach((label, row) -> outcomes.put(label, Long.parseLong(row.get(1))));
        return outcomes;
    }

    /** Null when the section is narrative-only — {@code findFirst} cannot carry a null itself. */
    private static ReportModel.Table table(ReportModel model, String heading) {
        return model.sections().stream()
                .filter(section -> heading.equals(section.heading()))
                .findFirst()
                .map(ReportModel.Section::table)
                .orElse(null);
    }

    private static String value(ReportModel model, String label) {
        return model.metadata().stream()
                .filter(entry -> label.equals(entry.label()))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No metadata entry '" + label + "'"));
    }

    /**
     * All four authored columns together — {@code case_flake_quarantine_complete} refuses anything
     * else. An {@code UPDATE}: {@link #refresh} writes a flake row for every live case, and the
     * fixture has already run it.
     */
    private void quarantine(UUID caseId, String reason) {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE case_flake
                           SET quarantined = true, quarantined_at = now(),
                               quarantined_by = CAST(? AS uuid),
                               quarantine_reason = ?
                         WHERE case_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, QUARANTINER.toString());
            statement.setString(2, reason);
            statement.setString(3, caseId.toString());
            assertThat(statement.executeUpdate())
                    .as("no flake row for that case — the refresh writes one per live case")
                    .isEqualTo(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
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
     * is unset and RLS answers zero rows, which would satisfy most of the absence checks here.
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

    private void decider(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user (id, email, canonical_name, status)
                VALUES (CAST(? AS uuid), CAST(? AS citext), ?, 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, QUARANTINER.toString());
            statement.setString(2, "quarantiner@avr.test");
            statement.setString(3, "Quinn Quarantiner");
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, String key, long keySeq,
            String automationStatus) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', ?, now(), now(), NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, BILLING.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, automationStatus);
            statement.executeUpdate();
        }
        version(connection, id, 1);
    }

    private void version(Connection connection, UUID caseId, int versionNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), ?,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setInt(3, versionNo);
            statement.setString(4, "hash-" + caseId + "-" + versionNo);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID configurationId, String key, long keySeq,
            String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, plan_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, ?,
                        'Variability fixture run', 'IN_PROGRESS', ?,
                        COALESCE(CAST(? AS uuid),
                                 (SELECT c.id FROM configuration c
                                   WHERE c.project_id = CAST(? AS uuid) AND c.is_default)),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, source);
            statement.setString(7, configurationId == null ? null : configurationId.toString());
            statement.setString(8, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID runId, UUID caseId, String statusName,
            Instant executedAt, int versionNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = ?),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS timestamptz), now(), now())
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, PROJECT.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setInt(6, versionNo);
            statement.setString(7, PROJECT.toString());
            statement.setString(8, statusName);
            statement.setString(9, executedAt == null ? null : executedAt.toString());
            statement.executeUpdate();
        }
    }
}
