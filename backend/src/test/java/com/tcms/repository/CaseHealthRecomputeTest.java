package com.tcms.repository;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The {@code case_health} recompute — the first writer this read model has ever had, and since M3
 * slice 5 the writer of <strong>four</strong> signals rather than two: §6c's automation-drift pair
 * joined the execution pair once §117 made {@code test_run.source = 'INGESTED'} a thing that
 * exists.
 *
 * <h2>What this class is really guarding</h2>
 *
 * <p>The dashboard has rendered a "Needs attention" panel since §47 with nothing behind it, and
 * {@code DashboardService} already draws the consequence in its own javadoc: an empty panel is a
 * <em>claim</em> that nothing needs attention, and that claim was false in every deployment because
 * nobody had looked. This job is what makes it true or false on purpose. So the failures worth
 * catching here are all failures that <strong>look like data</strong>:
 *
 * <ol>
 *   <li><strong>An unrecorded execution counted as an execution.</strong> Creating a run writes one
 *       empty execution per case in scope (§42), so if "has a {@code test_execution} row" were the
 *       predicate, planning a run would silently mark every case in it as executed — and
 *       {@code NEVER_EXECUTED} would drop to zero across the project the moment somebody planned
 *       work they had not done. The predicate is {@code status_id IS NOT NULL}, which is the same
 *       definition of "recorded" the case grid, plan statistics and §52's suite timeline use.</li>
 *   <li><strong>The wrong execution winning.</strong> A case executed 200 days ago and again last
 *       week is not stale. Taking any row rather than the newest inverts the answer for exactly the
 *       cases that are being actively maintained.</li>
 *   <li><strong>A result counted as evidence about the wrong thing.</strong> The drift pair's
 *       predicate is the newest <em>ingested</em> result, and the statement directly above it in
 *       {@code ExecutionFacts} reads the newest result of any kind. A case CI abandoned but a
 *       person still runs by hand satisfies one and not the other, and copying the neighbouring
 *       lateral — the likeliest edit anybody makes here — reports it as healthy.</li>
 *   <li><strong>The job asserting signals it did not compute.</strong> Five of the ten signals
 *       have no writer, and one more ({@code failing_repeatedly}) has a writer in another module
 *       writing the same rows on its own schedule. An {@code ON CONFLICT} that set every column would make this job the author
 *       of claims it has no evidence for, and would blank a future approval computation's work on
 *       every tick. The columns are owned individually even though the row is shared.</li>
 *   <li><strong>Writing across companies.</strong> The statement carries no company predicate — it
 *       relies entirely on {@code FORCE ROW LEVEL SECURITY} applying to both the {@code SELECT} and
 *       the {@code INSERT}. That is a correct design and an untested one is indistinguishable from
 *       a leak, so it is asserted with two companies rather than argued in a comment.</li>
 *   <li><strong>A soft-deleted case getting a row.</strong> It is absent from every count on the
 *       dashboard; a health row for it would be a signal nothing can navigate to.</li>
 * </ol>
 *
 * <h2>Why it drives the service instead of the scheduler</h2>
 *
 * <p>{@code CaseHealthScheduler} is disabled in the suite ({@code IntegrationTestBase} says why: a
 * write on a timer rewrites other test classes' fixtures partway through a run). What the scheduler
 * adds over the service is the company loop, and that is asserted here directly by opening two
 * companies in turn — which is also the only part of it that could be wrong.
 *
 * <p>Ids are unique to this class — {@code ce…}. Sharing them with another class is how §19's first
 * defect happened: green alone, red in the full suite.
 *
 * <p><strong>And the ids are not the only thing that has to be unique — this class proved it the
 * hard way.</strong> Its first draft took {@code ch…} ids and {@code ch-acme} / {@code ch-umbra}
 * slugs; the ids were renamed to {@code ce…} for an unrelated reason and the <em>slugs were left
 * behind</em>, where {@code execution.CaseHistoryEndpointTest} already owned them.
 * {@code uq_company_slug} then failed all eleven tests — in the full suite only, exactly the §19
 * shape, and with a stack trace pointing at an {@code INSERT INTO company} that looks like the base
 * class's fault. Every naturally-keyed fixture value needs the class prefix, not just the primary
 * keys.
 */
@DisplayName("Case-health recompute — the read model's first writer")
class CaseHealthRecomputeTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ce000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("ce000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("ce001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("ce001000-0000-4000-8000-000000000002");
    private static final UUID ACME_SUITE = UUID.fromString("ce002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("ce002000-0000-4000-8000-000000000002");
    private static final UUID ACME_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000001");
    /**
     * A second run, and it is not fixture padding.
     *
     * <p>{@code uq_execution_run_case} makes a case appear at most once per run, so "executed long
     * ago and again recently" is <em>structurally</em> two runs — there is no way to express it
     * otherwise. The first attempt at this fixture put both results in one run and the constraint
     * refused it, which is the schema being right about the domain.
     */
    private static final UUID ACME_OLD_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000002");
    /**
     * A run CI posted — {@code source = 'INGESTED'}, which is the whole predicate of the automation
     * drift pair. §117 pinned that column to <em>the door the request came through</em>, so this is
     * what {@code IngestionService} writes and what nothing else does.
     */
    private static final UUID ACME_CI_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000003");
    /** The same, long ago. Separate because {@code uq_execution_run_case} allows a case one row per run. */
    private static final UUID ACME_OLD_CI_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000004");

    /** No execution row at all. */
    private static final UUID NEVER = UUID.fromString("ce004000-0000-4000-8000-000000000001");
    /** An execution row with no result — a planned run, not a performed one. */
    private static final UUID PLANNED = UUID.fromString("ce004000-0000-4000-8000-000000000002");
    /** Recorded well inside the window. */
    private static final UUID RECENT = UUID.fromString("ce004000-0000-4000-8000-000000000003");
    /** Recorded well outside it. */
    private static final UUID ANCIENT = UUID.fromString("ce004000-0000-4000-8000-000000000004");
    /** Recorded long ago AND recently — the newest must win. */
    private static final UUID REVISITED = UUID.fromString("ce004000-0000-4000-8000-000000000005");
    /** Soft-deleted, never executed. Must get no row at all. */
    private static final UUID REMOVED = UUID.fromString("ce004000-0000-4000-8000-000000000006");
    /** Umbra's only case. Never executed, and must stay invisible to Acme's recompute. */
    private static final UUID FOREIGN = UUID.fromString("ce004000-0000-4000-8000-000000000007");

    // ---- the automation-drift pair (M3 slice 5, plan §6c) ----------------------------------
    //
    // Every one of these is AUTOMATED-or-not crossed with an ingested result inside-or-outside the
    // window, and each is exercised ALONE — §117's ladder rule. A fixture where one case satisfies
    // two rungs passes against an implementation that only computes one of them.

    /** AUTOMATED, CI last reported outside the window. The signal's whole point. */
    private static final UUID CI_STOPPED = UUID.fromString("ce004000-0000-4000-8000-000000000008");
    /** AUTOMATED, CI reported inside the window. Clean. */
    private static final UUID CI_REPORTING = UUID.fromString("ce004000-0000-4000-8000-000000000009");
    /**
     * AUTOMATED, CI silent for 120 days, but a person ran it by hand 10 days ago.
     *
     * <p>The case that separates the two statements in {@code ExecutionFacts}. It is <em>not</em>
     * long-unexecuted (its newest recorded result is recent) and it <em>is</em> stale automation
     * (its newest INGESTED result is not) — so an implementation reading "the last result" for both
     * signals gets exactly one of the two answers wrong, whichever it picks.
     */
    private static final UUID CI_STOPPED_RUN_BY_HAND =
            UUID.fromString("ce004000-0000-4000-8000-00000000000a");
    /** AUTOMATED, CI has NEVER reported. D3's hole, asserted rather than left to be discovered. */
    private static final UUID CI_NEVER_REPORTED =
            UUID.fromString("ce004000-0000-4000-8000-00000000000b");
    /** MANUAL, taking CI results right now. §6c's second direction, as §6c words it. */
    private static final UUID UNDECLARED = UUID.fromString("ce004000-0000-4000-8000-00000000000c");
    /** MANUAL, took CI results 120 days ago and none since. Expired, by the same cutoff. */
    private static final UUID UNDECLARED_LAPSED =
            UUID.fromString("ce004000-0000-4000-8000-00000000000d");
    /** NOT_AUTOMATABLE, taking CI results. The state §6c did not name and the coverage denominator does. */
    private static final UUID UNDECLARED_IMPOSSIBLE =
            UUID.fromString("ce004000-0000-4000-8000-00000000000e");

    /**
     * Comfortably either side of the 90-day default rather than exactly on it — <strong>and either
     * side of the 14-day stale-automation default too</strong>, which is what lets one pair of
     * offsets drive both jobs. 10 days is inside both windows and 120 is outside both; nothing here
     * lands between 14 and 90, because a fixture that did would make one signal's answer depend on
     * the other's threshold.
     *
     * <p>The boundary itself is deliberately not asserted against a live {@link java.time.Clock}: a
     * test that inserts a result at exactly {@code now - 90d} and asserts a strict {@code <} is a
     * test whose result depends on how long the seed took. Pinning the boundary properly needs a
     * fixed clock, which is a different fixture; asserting it loosely here and precisely nowhere
     * would be worse than asserting the two sides, which is what actually discriminates the signal.
     */
    private static final Duration WELL_INSIDE = Duration.ofDays(10);
    private static final Duration WELL_OUTSIDE = Duration.ofDays(120);

    @Autowired
    private CaseHealthRecomputeService recompute;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "cehealth-acme", "Acme", now);
        company(UMBRA, "cehealth-umbra", "Umbra", now);

        inCompany(ACME, () -> {
            seedProject(ACME_PROJECT, ACME, "CEHAC", "Acme Product", now);
            return null;
        });
        inCompany(UMBRA, () -> {
            seedProject(UMBRA_PROJECT, UMBRA, "CEHUM", "Umbra Product", now);
            return null;
        });

        clearHealthAndExecutions();

        try (Connection acme = appConnection(ACME)) {
            // Without this the project has no case_status rows, every execution below would insert
            // status_id NULL, and EVERY case would report never-executed — a fixture that makes the
            // whole class pass for the wrong reason while looking like it discriminates.
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            suite(acme, ACME_SUITE, ACME, ACME_PROJECT, "Root");
            testCase(acme, NEVER, ACME, ACME_PROJECT, ACME_SUITE, "TC-1", 1, false, "MANUAL");
            testCase(acme, PLANNED, ACME, ACME_PROJECT, ACME_SUITE, "TC-2", 2, false, "MANUAL");
            testCase(acme, RECENT, ACME, ACME_PROJECT, ACME_SUITE, "TC-3", 3, false, "MANUAL");
            testCase(acme, ANCIENT, ACME, ACME_PROJECT, ACME_SUITE, "TC-4", 4, false, "MANUAL");
            testCase(acme, REVISITED, ACME, ACME_PROJECT, ACME_SUITE, "TC-5", 5, false, "MANUAL");
            testCase(acme, REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-6", 6, true, "MANUAL");

            testCase(acme, CI_STOPPED, ACME, ACME_PROJECT, ACME_SUITE, "TC-7", 7, false,
                    "AUTOMATED");
            testCase(acme, CI_REPORTING, ACME, ACME_PROJECT, ACME_SUITE, "TC-8", 8, false,
                    "AUTOMATED");
            testCase(acme, CI_STOPPED_RUN_BY_HAND, ACME, ACME_PROJECT, ACME_SUITE, "TC-9", 9, false,
                    "AUTOMATED");
            testCase(acme, CI_NEVER_REPORTED, ACME, ACME_PROJECT, ACME_SUITE, "TC-10", 10, false,
                    "AUTOMATED");
            testCase(acme, UNDECLARED, ACME, ACME_PROJECT, ACME_SUITE, "TC-11", 11, false, "MANUAL");
            testCase(acme, UNDECLARED_LAPSED, ACME, ACME_PROJECT, ACME_SUITE, "TC-12", 12, false,
                    "MANUAL");
            testCase(acme, UNDECLARED_IMPOSSIBLE, ACME, ACME_PROJECT, ACME_SUITE, "TC-13", 13, false,
                    "NOT_AUTOMATABLE");

            run(acme, ACME_RUN, ACME, ACME_PROJECT, "TR-1", 1, "MANUAL");
            run(acme, ACME_OLD_RUN, ACME, ACME_PROJECT, "TR-2", 2, "MANUAL");
            run(acme, ACME_CI_RUN, ACME, ACME_PROJECT, "TR-3", 3, "INGESTED");
            run(acme, ACME_OLD_CI_RUN, ACME, ACME_PROJECT, "TR-4", 4, "INGESTED");

            // The discrimination that matters most: a row, no result.
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, PLANNED, null);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, RECENT, now.minus(WELL_INSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, ANCIENT, now.minus(WELL_OUTSIDE));
            // Two results across two runs. Inserted oldest-last, so "the last row wins" and "any
            // row wins" both produce the stale answer that theNewestResultWins() rejects.
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, REVISITED, now.minus(WELL_INSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_RUN, REVISITED, now.minus(WELL_OUTSIDE));

            // ---- the drift pair --------------------------------------------------------------
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_CI_RUN, CI_STOPPED, now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_CI_RUN, CI_REPORTING, now.minus(WELL_INSIDE));
            // Old from CI, recent by hand. The MANUAL row is the one that must NOT count.
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_CI_RUN, CI_STOPPED_RUN_BY_HAND,
                    now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, CI_STOPPED_RUN_BY_HAND,
                    now.minus(WELL_INSIDE));
            // Declared AUTOMATED, and CI has never once posted for it.
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_RUN, CI_NEVER_REPORTED,
                    now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_CI_RUN, UNDECLARED, now.minus(WELL_INSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_CI_RUN, UNDECLARED_LAPSED,
                    now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_CI_RUN, UNDECLARED_IMPOSSIBLE,
                    now.minus(WELL_INSIDE));
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "Root");
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, false, "MANUAL");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The two execution signals this job owns")
    class Signals {

        @Test
        @DisplayName("a case with no execution row at all is NEVER_EXECUTED")
        void noExecutionsAtAll() {
            recomputeFor(ACME);
            assertThat(signalsOf(NEVER)).containsExactly("never_executed");
        }

        @Test
        @DisplayName("a case whose only execution has no result is STILL never executed")
        void aPlannedRunIsNotAnExecution() {
            // THE defect this class exists for. Creating a run writes one empty execution per case
            // in scope (§42). If the predicate were "an execution row exists", planning work would
            // mark it done — NEVER_EXECUTED would fall to zero across a project the day somebody
            // scheduled a run, and the dashboard would report improvement caused by nothing.
            recomputeFor(ACME);
            assertThat(signalsOf(PLANNED))
                    .as("an execution with status_id NULL is work outstanding, not history")
                    .containsExactly("never_executed");
        }

        @Test
        @DisplayName("a recently recorded case raises nothing")
        void recentlyExecuted() {
            recomputeFor(ACME);
            assertThat(signalsOf(RECENT)).isEmpty();
        }

        @Test
        @DisplayName("a case last recorded outside the window is LONG_UNEXECUTED, not NEVER")
        void executedLongAgo() {
            // Both, or the wrong one, is the plausible failure: "never executed" and "not executed
            // lately" are one predicate away from each other, and a case that HAS been run is not
            // one that never has. The panel would show the same case under two headings.
            recomputeFor(ACME);
            assertThat(signalsOf(ANCIENT)).containsExactly("long_unexecuted");
        }

        @Test
        @DisplayName("the NEWEST result decides, not any result")
        void theNewestResultWins() {
            // Executed 120 days ago and again 10 days ago. Anything other than MAX(executed_at)
            // reports the actively-maintained case as stale — inverting the signal for exactly the
            // cases somebody is looking after.
            recomputeFor(ACME);
            assertThat(signalsOf(REVISITED)).isEmpty();
        }

        @Test
        @DisplayName("a soft-deleted case gets no row at all")
        void softDeletedCasesAreNotDerived() {
            recomputeFor(ACME);
            assertThat(healthRowExists(ACME, REMOVED))
                    .as("a deleted case is absent from every dashboard count; a health row for it "
                            + "would be a signal with nothing to navigate to")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Automation drift — §6c, both directions")
    class AutomationDrift {

        @Test
        @DisplayName("AUTOMATED, and CI stopped reporting, is STALE_AUTOMATION")
        void ciStopped() {
            recomputeFor(ACME);
            // Also long-unexecuted: nothing has run it at all in 120 days. Both are true and both
            // are this job's, so the assertion names both rather than filtering to the one under
            // test — a containsExactly that quietly ignored a sibling signal would pass against an
            // implementation that raised it wrongly.
            assertThat(signalsOf(CI_STOPPED))
                    .containsExactlyInAnyOrder("stale_automation", "long_unexecuted");
        }

        @Test
        @DisplayName("AUTOMATED, and CI reported inside the window, raises nothing")
        void ciReporting() {
            recomputeFor(ACME);
            assertThat(signalsOf(CI_REPORTING)).isEmpty();
        }

        @Test
        @DisplayName("a MANUAL re-run does not clear stale automation")
        void aPersonRunningItByHandProvesNothingAboutTheAutomation() {
            // THE test of this pair. The case was run 10 days ago, so it is not long-unexecuted;
            // CI has not posted for 120, so its automation IS stale. An implementation that read
            // "the newest result" for both signals — which is the shape of the statement directly
            // above it in ExecutionFacts, and therefore the one somebody will copy — reports this
            // case as healthy, and the automation nobody is watching stays unwatched.
            recomputeFor(ACME);
            assertThat(signalsOf(CI_STOPPED_RUN_BY_HAND))
                    .as("the predicate is the newest INGESTED result, not the newest result")
                    .containsExactly("stale_automation");
        }

        @Test
        @DisplayName("AUTOMATED with no ingested result EVER is not stale")
        void automationThatNeverStartedHasNotStopped() {
            // D3, and it is a deliberate hole rather than an oversight. §6c makes automationKey
            // optional precisely so a case can be marked AUTOMATED before its automation exists;
            // without this clause an ERROR-severity signal fires on every case in the project the
            // day somebody starts declaring intent, loudest on the teams doing the work.
            recomputeFor(ACME);
            assertThat(signalsOf(CI_NEVER_REPORTED))
                    .as("'stopped reporting' presupposes started; no results at all is "
                            + "NEVER_EXECUTED's question, and only manual ones is nobody's yet")
                    .containsExactly("long_unexecuted");
        }

        @Test
        @DisplayName("a MANUAL case taking CI results is UNDECLARED_AUTOMATION")
        void undeclaredAutomation() {
            recomputeFor(ACME);
            assertThat(signalsOf(UNDECLARED)).containsExactly("undeclared_automation");
        }

        @Test
        @DisplayName("NOT_AUTOMATABLE taking CI results is undeclared too, though §6c says MANUAL")
        void undeclaredIsWiderThanManual() {
            // The widening, tested alone because it is the arm a literal reading of §6c drops. It
            // is also the loudest: automation coverage is denominated AUTOMATED / (total −
            // NOT_AUTOMATABLE), so this case is subtracted from the denominator of the very number
            // the signal family exists to keep honest.
            recomputeFor(ACME);
            assertThat(signalsOf(UNDECLARED_IMPOSSIBLE)).containsExactly("undeclared_automation");
        }

        @Test
        @DisplayName("undeclared automation expires on the same cutoff staleness uses")
        void undeclaredLapses() {
            // D5. Unbounded, this signal could never go false: a case automated, discovered, and
            // then legitimately returned to manual when the automation was deleted would stay
            // flagged for ever with nothing anybody could do — HealthSignal's "no button for
            // lying" rule arriving from the other side, as a claim with no route back.
            recomputeFor(ACME);
            assertThat(signalsOf(UNDECLARED_LAPSED))
                    .as("CI last posted 120 days ago; whatever was automating this has stopped too")
                    .containsExactly("long_unexecuted");
        }

        @Test
        @DisplayName("no case can raise both halves of the pair")
        void theTwoAreMutuallyExclusive() {
            // A property of the statement rather than of the schema — one fact, two sides of one
            // cutoff — so it is worth asserting over the whole fixture rather than per case. Two
            // thresholds, or an automation_status predicate dropped from either arm, and a case
            // reads as both "the automation stopped" and "the automation is undeclared".
            recomputeFor(ACME);

            for (UUID caseId : List.of(NEVER, PLANNED, RECENT, ANCIENT, REVISITED, CI_STOPPED,
                    CI_REPORTING, CI_STOPPED_RUN_BY_HAND, CI_NEVER_REPORTED, UNDECLARED,
                    UNDECLARED_LAPSED, UNDECLARED_IMPOSSIBLE)) {
                List<String> raised = signalsOf(caseId);
                assertThat(raised.contains("stale_automation")
                                && raised.contains("undeclared_automation"))
                        .as("case %s raised both halves of a pair that reads one fact on two sides "
                                + "of one cutoff: %s", caseId, raised)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("What the job must not touch")
    class Boundaries {

        @Test
        @DisplayName("signals this job does not compute survive a recompute")
        void otherSignalsAreNotClobbered() throws SQLException {
            // The ON CONFLICT clause names three columns. If it named all of them, this job would
            // blank the approval computation's work on every tick once that lands — a defect that
            // appears an hour after the other feature ships and looks like the other feature's bug.
            recomputeFor(ACME);
            setSignal(ACME, RECENT, "stale_approval", true);

            recomputeFor(ACME);

            assertThat(signalsOf(RECENT))
                    .as("stale_approval belongs to a computation this job knows nothing about")
                    .containsExactly("stale_approval");
        }

        @Test
        @DisplayName("recomputing one company writes nothing in another")
        void companiesAreIsolated() {
            // The statement carries no company predicate at all — it relies entirely on FORCE ROW
            // LEVEL SECURITY covering both the SELECT and the INSERT. That is the right design and
            // an unasserted one is indistinguishable from a leak.
            recomputeFor(ACME);

            assertThat(healthRowExists(UMBRA, FOREIGN))
                    .as("Umbra's case must be invisible to a recompute run in Acme's context")
                    .isFalse();

            recomputeFor(UMBRA);

            assertThat(healthRowExists(UMBRA, FOREIGN))
                    .as("and reachable from its own")
                    .isTrue();
        }

        @Test
        @DisplayName("a second run changes nothing")
        void isIdempotent() {
            recomputeFor(ACME);
            List<String> first = signalsOf(ANCIENT);

            recomputeFor(ACME);

            assertThat(signalsOf(ANCIENT))
                    .as("the scheduler reruns hourly forever; drift here compounds silently")
                    .isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("computed_at — the field that makes an empty panel honest")
    class ComputedAt {

        @Test
        @DisplayName("is stamped, so the dashboard can stop saying 'nobody has looked'")
        void isStamped() {
            // DashboardEndpointTest asserts healthComputedAt is null while no writer exists. This
            // is the other side of that: once a company has been recomputed, an empty "Needs
            // attention" panel finally means what it says.
            Instant before = Instant.now().minusSeconds(5);
            recomputeFor(ACME);

            assertThat(computedAt(ACME, RECENT))
                    .as("a row derived just now must carry when")
                    .isNotNull()
                    .isAfter(before);
        }

        @Test
        @DisplayName("a clean case still gets a row")
        void cleanCasesAreRecordedToo() {
            // Not an optimisation to skip: a case with no signals and NO ROW is indistinguishable
            // from a case nobody has computed, which is the exact distinction computed_at exists
            // to draw. The row is the evidence that the question was asked.
            recomputeFor(ACME);
            assertThat(healthRowExists(ACME, RECENT)).isTrue();
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void recomputeFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            recompute.recompute();
        }
    }

    /** The signal columns this row asserts, so an assertion reads as a set rather than nine booleans. */
    private List<String> signalsOf(UUID caseId) {
        UUID companyId = caseId.equals(FOREIGN) ? UMBRA : ACME;
        List<String> columns = List.of("outdated_spec_changed", "stale_approval", "condition_drift",
                "stale_automation", "never_executed", "long_unexecuted", "unlinked",
                "failing_repeatedly", "orphaned", "undeclared_automation");
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + String.join(", ", columns)
                                + " FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return List.of();
                }
                return columns.stream().filter(column -> {
                    try {
                        return rows.getBoolean(column);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    private void clearHealthAndExecutions() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("case_health", "step_result", "test_execution",
                        "test_run", "test_case_version", "test_case", "suite")) {
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

    private <T> T inCompany(UUID companyId, java.util.concurrent.Callable<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return work.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
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

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 1, ?, 0, 'APPROVED', NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, "TS-1");
            statement.setString(5, name);
            statement.executeUpdate();
        }
    }

    /**
     * One case.
     *
     * @param automationStatus what the case <em>claims</em> about its automation — the left-hand
     *                         side of both drift signals. {@code NOT_AUTOMATABLE} carries a reason
     *                         because {@code V1}'s {@code CHECK} requires one, which is the schema
     *                         being right about the domain: "we will never automate this" is a
     *                         statement somebody has to justify.
     */
    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, boolean deleted, String automationStatus)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, not_automatable_reason,
                     created_at, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', ?, ?, now(), now(),
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
            statement.setString(8, automationStatus);
            statement.setString(9,
                    "NOT_AUTOMATABLE".equals(automationStatus) ? "Requires visual judgement" : null);
            statement.setString(10, deleted ? "2026-01-01T00:00:00Z" : null);
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
            statement.setString(3, "hash-" + key);
            statement.executeUpdate();
        }
    }

    /**
     * One run.
     *
     * @param source {@code 'MANUAL'} or {@code 'INGESTED'} — the right-hand side of both drift
     *               signals, and §117's rule: this is the <em>door the request came through</em>,
     *               so a fixture cannot express "a token posted this through {@code POST /runs}" as
     *               {@code INGESTED}, because the product cannot either.
     */
    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Recompute fixture run', 'IN_PROGRESS', ?,
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
            statement.setString(6, source);
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One execution row.
     *
     * @param executedAt null for a planned-but-unrecorded row — which also leaves {@code status_id}
     *                   null, because the two move together in {@code TestExecution.record()} and a
     *                   fixture that set one without the other would be testing a state the
     *                   application cannot produce.
     */
    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, Instant executedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        CASE WHEN CAST(? AS timestamptz) IS NULL THEN NULL
                             ELSE (SELECT s.id FROM case_status s
                                    WHERE s.project_id = CAST(? AS uuid) LIMIT 1) END,
                        CAST(? AS timestamptz), now(), now())
                """)) {
            String at = executedAt == null ? null : executedAt.toString();
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, at);
            statement.setString(7, projectId.toString());
            statement.setString(8, at);
            statement.executeUpdate();
        }
    }
}
