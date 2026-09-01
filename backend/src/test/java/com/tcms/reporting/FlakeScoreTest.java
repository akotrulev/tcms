package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code case_flake}'s derived half — §6l's flakiness rule computed rather than described.
 *
 * <h2>What this class is guarding, and why the fixture is bigger than the last one's</h2>
 *
 * <p>§6l states the rule in one sentence and every clause of it is load-bearing:
 *
 * <blockquote>"Flakiness — pass/fail alternation <em>within a single version and configuration</em>,
 * which is the only scoping under which flakiness means anything. Alternation across versions is
 * just change, and reporting it as flake is a false signal."</blockquote>
 *
 * <p>So the fixture has to be able to express <em>four</em> things that a naive implementation
 * scores identically and that mean four different things: a test that really flaps, a test that was
 * changed, a test that behaves differently on two browsers, and a test nobody could run. Three of
 * the four are cases the signal must leave <strong>alone</strong>, and each is a case somebody
 * would look at first if it were flagged — which is what makes a false positive here expensive
 * rather than merely noisy.
 *
 * <p>Ten runs, not four. {@code uq_execution_run_case} makes a case appear at most once per run, so
 * a sequence of ten outcomes is structurally ten runs — and ten is the smallest number that lets
 * {@link Cells#aBlockedRunBetweenTwoPassesIsNotAnAlternation()} clear the evidence floor <em>after</em>
 * the non-outcomes are removed. At eight it would come out below the floor and score null, which
 * the wrong implementation also does for a different reason: the assertion would pass on a broken
 * statement. That is the trap §120 named in its own words — <em>mutate to the rival implementation,
 * not to nothing</em> — arriving one level earlier, in the fixture rather than in the mutation.
 *
 * <p>Ids are {@code fc…} — hex, which is not optional — and slugs are {@code fk-…}. The window and
 * the floor are the shipped defaults (90 days, 4) read from {@code application.yml} rather than
 * overridden here: a class-level property override forks the Spring context for the whole suite,
 * and every boundary below is expressible at the shipped numbers.
 */
@DisplayName("case_flake — §6l's flakiness rule, scoped to one cell")
class FlakeScoreTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("fc000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("fc000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("fc001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("fc001000-0000-4000-8000-000000000002");
    private static final UUID ACME_SUITE = UUID.fromString("fc002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("fc002000-0000-4000-8000-000000000002");

    /** A second configuration, so "the same test on two browsers" is expressible at all. */
    private static final UUID FIREFOX = UUID.fromString("fc005000-0000-4000-8000-000000000001");

    private static final int RUN_COUNT = 10;
    private static final UUID UMBRA_RUN = UUID.fromString("fc003000-0000-4000-8000-000000000099");

    /** Alternates every run on one config at one version. The one case that must score 1.0. */
    private static final UUID FLAPPER = UUID.fromString("fc004000-0000-4000-8000-000000000001");
    /** Passed every time. Must score 0.0 — <em>evidently stable</em>, which is not null. */
    private static final UUID STABLE = UUID.fromString("fc004000-0000-4000-8000-000000000002");
    /** Pass, blocked, pass, blocked… The non-outcome trap; must score 0.0. */
    private static final UUID INTERRUPTED = UUID.fromString("fc004000-0000-4000-8000-000000000003");
    /** Passed at v1, fails at v2. A regression after an edit, not a flake — must score 0.0. */
    private static final UUID EDITED = UUID.fromString("fc004000-0000-4000-8000-000000000004");
    /** Passes on the default config, fails on Firefox. A platform bug, not a flake — 0.0. */
    private static final UUID BROWSER_SPECIFIC = UUID.fromString("fc004000-0000-4000-8000-000000000005");
    /** Three results, two of them flips. Real alternation, below the floor — score is NULL. */
    private static final UUID THIN = UUID.fromString("fc004000-0000-4000-8000-000000000006");
    /** Never executed. Gets a row with zeroes and no score. */
    private static final UUID UNTOUCHED = UUID.fromString("fc004000-0000-4000-8000-000000000007");
    /** Flaps badly, then soft-deleted. Gets no row at all. */
    private static final UUID REMOVED = UUID.fromString("fc004000-0000-4000-8000-000000000008");
    /** Umbra's only case, flapping, and invisible to Acme. */
    private static final UUID FOREIGN = UUID.fromString("fc004000-0000-4000-8000-000000000009");

    /** The person who makes the quarantine decision — {@code quarantined_by} cannot be null. */
    private static final UUID QUARANTINER = UUID.fromString("fc006000-0000-4000-8000-000000000001");

    /**
     * Oldest first, and <strong>relative to now</strong>: the ten daily results run from 30 days
     * ago to 21, so the whole fixture sits inside the shipped 90-day window with two months of
     * slack on either side; {@link Window} moves one case outside it deliberately.
     *
     * <p>This was {@code Instant.parse("2026-06-01T08:00:00Z")} until 2026-09-01, and the absolute
     * date is what broke it. An execution enters the score only if it is inside a window measured
     * back from {@code now()}, so a fixed base is not a fixture constant at all — it is a countdown.
     * Written on 2026-08-07 it had 23 days of margin; on 2026-08-30 the oldest three results
     * crossed the boundary and six tests went red at once, each reporting a count three lower than
     * it expected, on a tree whose only change was an import. A window-scoped statement can only
     * be seeded against the clock the statement itself reads.
     */
    private static final Instant BASE = Instant.now().minusSeconds(86_400L * 30);

    @Autowired
    private ExecutionFactRefreshService refresh;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    private List<UUID> acmeRuns;
    private List<UUID> firefoxRuns;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "fk-acme", "Acme", now);
        company(UMBRA, "fk-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "FKAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "FKUM", "Umbra Product", now));

        clearEverything();

        acmeRuns = runIds(1);
        firefoxRuns = runIds(50);

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            user(acme, QUARANTINER, "quarantiner@fk-acme.test", "Q. Uarantiner");
            configuration(acme, FIREFOX, ACME, ACME_PROJECT, "Firefox");
            suite(acme, ACME_SUITE, ACME, ACME_PROJECT);

            int key = 0;
            for (UUID caseId : List.of(FLAPPER, STABLE, INTERRUPTED, EDITED, BROWSER_SPECIFIC,
                    THIN, UNTOUCHED)) {
                testCase(acme, caseId, ACME, ACME_PROJECT, ACME_SUITE, "TC-" + (++key), key, false);
            }
            testCase(acme, REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-" + (++key), key, true);
            // EDITED is the only case with a second version — the fixture's way of saying "somebody
            // changed the test", which is the thing §6l insists is not a flake.
            caseVersion(acme, ACME, EDITED, 2);

            for (int i = 0; i < RUN_COUNT; i++) {
                run(acme, acmeRuns.get(i), ACME, ACME_PROJECT, "TR-" + (i + 1), i + 1, null);
                run(acme, firefoxRuns.get(i), ACME, ACME_PROJECT, "TR-F" + (i + 1), 50 + i, FIREFOX);
            }

            results(acme, acmeRuns, FLAPPER, 1,
                    "Passed", "Failed", "Passed", "Failed", "Passed",
                    "Failed", "Passed", "Failed", "Passed", "Failed");
            results(acme, acmeRuns, STABLE, 1,
                    "Passed", "Passed", "Passed", "Passed", "Passed",
                    "Passed", "Passed", "Passed", "Passed", "Passed");
            results(acme, acmeRuns, INTERRUPTED, 1,
                    "Passed", "Blocked", "Passed", "Blocked", "Passed",
                    "Blocked", "Passed", "Blocked", "Passed", "Blocked");
            results(acme, acmeRuns, REMOVED, 1,
                    "Passed", "Failed", "Passed", "Failed", "Passed",
                    "Failed", "Passed", "Failed", "Passed", "Failed");
            results(acme, acmeRuns, THIN, 1, "Passed", "Failed", "Passed");

            // Five passes at v1, then five failures at v2. Inside each cell nothing ever changes;
            // across the pair everything does, exactly once.
            results(acme, acmeRuns.subList(0, 5), EDITED, 1,
                    "Passed", "Passed", "Passed", "Passed", "Passed");
            results(acme, acmeRuns.subList(5, 10), EDITED, 2,
                    "Failed", "Failed", "Failed", "Failed", "Failed");

            // Green on the default configuration, red on Firefox, at the same version throughout.
            results(acme, acmeRuns, BROWSER_SPECIFIC, 1,
                    "Passed", "Passed", "Passed", "Passed", "Passed",
                    "Passed", "Passed", "Passed", "Passed", "Passed");
            results(acme, firefoxRuns, BROWSER_SPECIFIC, 1,
                    "Failed", "Failed", "Failed", "Failed", "Failed",
                    "Failed", "Failed", "Failed", "Failed", "Failed");
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT);
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, false);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1, null);
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, FOREIGN, 1, "Failed", BASE);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The score itself — and the difference between zero and null")
    class Score {

        @Test
        @DisplayName("a test that alternates every run scores 1.0")
        void alternationIsTheScore() {
            refreshFor(ACME);
            assertThat(scoreOf(ACME, FLAPPER)).isEqualByComparingTo("1.0000");
            assertThat(intOf(ACME, FLAPPER, "transitions")).isEqualTo(9);
            assertThat(intOf(ACME, FLAPPER, "alternations")).isEqualTo(9);
        }

        @Test
        @DisplayName("a test that always passes scores 0.0 — which is not null")
        void stableIsZeroNotNull() {
            // The distinction the whole nullable column exists for. 0.0 is a CLAIM — "we looked at
            // nine transitions and none of them flipped" — and null is the absence of one. A
            // statement that produced null here would be indistinguishable, on screen, from one
            // that produced null for THIN, and the two mean opposite things about how much to trust
            // the test.
            refreshFor(ACME);
            assertThat(scoreOf(ACME, STABLE)).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("real alternation below the evidence floor scores NULL, and keeps its counts")
        void belowTheFloorThereIsNoScore() {
            // THIN flipped on both of its two transitions — a perfect 1.0 on any ratio definition,
            // from three results. That number would be the one a human used to decide whether to
            // quarantine, and it is noise. The counts are stored anyway so the screen can say WHY
            // there is no score rather than showing an unexplained blank.
            refreshFor(ACME);
            assertThat(scoreOf(ACME, THIN)).isNull();
            assertThat(intOf(ACME, THIN, "executions")).isEqualTo(3);
            assertThat(intOf(ACME, THIN, "transitions")).isEqualTo(2);
            assertThat(intOf(ACME, THIN, "alternations")).isEqualTo(2);
        }

        @Test
        @DisplayName("a never-executed case gets a row of zeroes and no score")
        void neverExecutedIsScoredAsNothing() {
            // The row must exist with nothing in it: a case with no row is indistinguishable from a
            // case nobody has computed, which is the distinction computed_at exists to draw and the
            // one §47's empty "Needs attention" panel got wrong.
            refreshFor(ACME);
            assertThat(rowExists(ACME, UNTOUCHED)).isTrue();
            assertThat(scoreOf(ACME, UNTOUCHED)).isNull();
            assertThat(intOf(ACME, UNTOUCHED, "executions")).isZero();
            assertThat(intOf(ACME, UNTOUCHED, "transitions")).isZero();
        }

        @Test
        @DisplayName("a soft-deleted case gets no row at all")
        void softDeletedCasesAreNotScored() {
            // The opposite call from the fact log, and consistent with it rather than in tension:
            // the fact log is history and history does not un-happen, while case_flake is a
            // worklist about cases that exist.
            refreshFor(ACME);
            assertThat(rowExists(ACME, REMOVED)).isFalse();
        }
    }

    @Nested
    @DisplayName("The cell — §6l's scoping rule, which is three separate ways to be wrong")
    class Cells {

        @Test
        @DisplayName("a change between VERSIONS is not a flake")
        void alternationAcrossVersionsIsJustChange() {
            // §6l names this one explicitly: "alternation across versions is just change, and
            // reporting it as flake is a false signal." EDITED passed five times, was edited, and
            // has failed five times since — a test that caught a regression, which is the single
            // most valuable thing a test does. Drop case_version_id from the PARTITION BY and it is
            // reported as flaky, sending somebody to quarantine the test that found the bug.
            refreshFor(ACME);
            assertThat(scoreOf(ACME, EDITED))
                    .as("nothing changed inside either version's cell")
                    .isEqualByComparingTo("0.0000");
            assertThat(intOf(ACME, EDITED, "cells")).isEqualTo(2);
            assertThat(intOf(ACME, EDITED, "transitions"))
                    .as("four pairs inside each of the two cells, and no pair across them")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("a difference between CONFIGURATIONS is not a flake either")
        void alternationAcrossConfigurationsIsAPlatformBug() {
            // The other half of §6l's sentence, and the failure it prevents is a different one:
            // "fails only on Safari" is a real, reproducible, fixable defect, and quarantining the
            // test is precisely the wrong response to it. §6l's stability-by-configuration matrix
            // is the surface that exists to make it readable; this is the same fact at one number.
            refreshFor(ACME);
            assertThat(scoreOf(ACME, BROWSER_SPECIFIC))
                    .as("green on one browser and red on the other, reliably, is not alternation")
                    .isEqualByComparingTo("0.0000");
            assertThat(intOf(ACME, BROWSER_SPECIFIC, "cells")).isEqualTo(2);
            assertThat(intOf(ACME, BROWSER_SPECIFIC, "executions")).isEqualTo(20);
        }

        @Test
        @DisplayName("a status that counts as a pass but is not a PASSED verdict stays out")
        void thePredicateIsTheKindAndNotThePassRateColumn() throws SQLException {
            // The THIRD implementation of §6l's sentence, and the one divergence that would have
            // shipped. execution.CaseHistoryService.flakiness has computed this since §49, under the
            // same name, from the same sentence — and it keeps only PASSED and FAILED, while this
            // statement was first written over counts_as_pass because R7's callout uses it.
            //
            // case_status.kind and case_status.counts_as_pass are INDEPENDENT columns. On the six
            // seeded defaults they agree exactly, so the divergence is invisible until a project
            // customises its status vocabulary — and then the case page and this worklist quote
            // different numbers with nothing to say which is right.
            //
            // "Passed with known issues" is the realistic shape: a RETEST-kind status a team marks
            // as counting toward the pass rate. Under counts_as_pass it is a pass, so STABLE's run
            // of ten reads as pass, pass-with-issues, pass — unchanged, 0.0 — while INTERRUPTED,
            // whose blocked rows this replaces, would gain five spurious verdicts. Under
            // status_kind it is not a verdict at all and stays out of the sequence entirely.
            try (Connection acme = appConnection(ACME)) {
                lenientStatus(acme, ACME, ACME_PROJECT);
                // Overwrite INTERRUPTED's blocked rows with the lenient status: every odd run.
                for (int i = 1; i < RUN_COUNT; i += 2) {
                    execution(acme, ACME, ACME_PROJECT, acmeRuns.get(i), INTERRUPTED, 1,
                            "Passed with known issues", BASE.plusSeconds(86_400L * i));
                }
            }
            refreshFor(ACME);

            assertThat(intOf(ACME, INTERRUPTED, "executions"))
                    .as("a RETEST-kind status is not a verdict, whatever the pass rate counts it as")
                    .isEqualTo(5);
            assertThat(scoreOf(ACME, INTERRUPTED)).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("a BLOCKED run between two passes is not an alternation")
        void aBlockedRunBetweenTwoPassesIsNotAnAlternation() {
            // THE predicate decision, and the mutation this statement most needs. The tempting
            // implementation keeps every row and treats "not a pass" as the other value — which is
            // the shape of the pass-rate column sitting right there — and INTERRUPTED then reads as
            // pass, blocked, pass, blocked: nine transitions, eight of them flips, 0.8889 for a
            // test that has passed every single time it was actually run.
            //
            // Non-outcomes are removed BEFORE the sequence is formed, so the five passes are
            // adjacent. This is R7's "a pass and a BLOCK is not an inconsistency" applied to a
            // sequence instead of a set, and FailingRepeatedlyTest's notPassingIsNotFailing in its
            // third shape.
            refreshFor(ACME);
            assertThat(scoreOf(ACME, INTERRUPTED))
                    .as("five passes with blocked runs between them are five passes")
                    .isEqualByComparingTo("0.0000");
            assertThat(intOf(ACME, INTERRUPTED, "executions"))
                    .as("the blocked rows never enter the window at all")
                    .isEqualTo(5);
            assertThat(intOf(ACME, INTERRUPTED, "transitions")).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("The window, the boundary and the other company")
    class Window {

        @Test
        @DisplayName("executions older than the window are not scored")
        void theWindowIsBounded() throws SQLException {
            // §120's rule from the other direction: an unbounded score can never go false, so a
            // test that flapped badly last spring and has been solid since stays flagged for ever
            // with nothing anybody can do about it. Moving FLAPPER's whole history outside the
            // 90-day window must take its score with it.
            refreshFor(ACME);
            assertThat(scoreOf(ACME, FLAPPER)).isEqualByComparingTo("1.0000");

            backdate(ACME, FLAPPER, BASE.minusSeconds(86_400L * 400));
            refreshFor(ACME);

            assertThat(scoreOf(ACME, FLAPPER))
                    .as("a bad week two years ago is not a claim about the test today")
                    .isNull();
            assertThat(intOf(ACME, FLAPPER, "executions")).isZero();
        }

        @Test
        @DisplayName("a second refresh changes nothing")
        void isIdempotent() {
            refreshFor(ACME);
            BigDecimal first = scoreOf(ACME, FLAPPER);
            Instant firstAlternation = lastAlternationAt(ACME, FLAPPER);

            refreshFor(ACME);

            // The execution_id tie-break in the ORDER BY is what this rests on. Without it, two
            // results recorded in the same microsecond — which is what a CI run posting a batch
            // produces — resolve differently per tick and the score flickers with no input moving.
            assertThat(scoreOf(ACME, FLAPPER)).isEqualByComparingTo(first);
            assertThat(lastAlternationAt(ACME, FLAPPER)).isEqualTo(firstAlternation);
        }

        @Test
        @DisplayName("scoring one company writes nothing in another")
        void companiesAreIsolated() {
            refreshFor(ACME);
            assertThat(rowExists(UMBRA, FOREIGN))
                    .as("the statement carries no company predicate — FORCE RLS is the whole "
                            + "mechanism, on the SELECT and on the INSERT")
                    .isFalse();

            refreshFor(UMBRA);

            assertThat(rowExists(UMBRA, FOREIGN)).isTrue();
        }

        @Test
        @DisplayName("computed_at is stamped even on a case with no evidence")
        void computedAtIsStamped() {
            Instant before = Instant.now().minusSeconds(5);
            refreshFor(ACME);
            assertThat(computedAt(ACME, UNTOUCHED)).isNotNull().isAfter(before);
        }
    }

    @Nested
    @DisplayName("The row is shared, the columns are not — inside ONE table this time")
    class Boundaries {

        @Test
        @DisplayName("a quarantine survives every subsequent recompute")
        void theAuthoredHalfIsUntouchable() throws SQLException {
            // THE test this table's shape exists for. §120 was the first slice where the
            // own-your-own-columns rule bound two live writers; here it binds two writers of one
            // ROW, and the failure it prevents is worse than §120's because it is silent and in the
            // reassuring direction: an EXCLUDED.* set-list releases every quarantined test on the
            // next tick, presenting as a board whose quarantine column empties itself overnight
            // with no action recorded by anyone.
            refreshFor(ACME);
            quarantine(ACME, FLAPPER, "Flaky on the payment gateway stub");

            refreshFor(ACME);

            assertThat(booleanOf(ACME, FLAPPER, "quarantined"))
                    .as("the recompute names seven columns and none of them is this one")
                    .isTrue();
            assertThat(stringOf(ACME, FLAPPER, "quarantine_reason"))
                    .isEqualTo("Flaky on the payment gateway stub");
            assertThat(scoreOf(ACME, FLAPPER))
                    .as("and the derived half is still derived")
                    .isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("failing_repeatedly still lands — the two statements share a transaction")
        void theSiblingSignalIsUnaffected() {
            // Both run inside ExecutionFactRefreshService.refresh(), in that order, under one GUC.
            // A regression that made the flake statement throw would take the fact projection and
            // the health signal down with it, and this is the cheapest place to notice.
            refreshFor(ACME);
            assertThat(rowExists(ACME, FLAPPER)).isTrue();
            assertThat(caseHealthRowExists(ACME, FLAPPER))
                    .as("the flake statement is a third step, not a replacement for the second")
                    .isTrue();
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

    private static List<UUID> runIds(int offset) {
        return java.util.stream.IntStream.range(0, RUN_COUNT)
                .mapToObj(i -> UUID.fromString(
                        String.format("fc003000-0000-4000-8000-%012d", offset + i)))
                .toList();
    }

    private BigDecimal scoreOf(UUID companyId, UUID caseId) {
        return query(companyId, caseId, "flake_score",
                (rows, column) -> rows.getBigDecimal(column));
    }

    private Integer intOf(UUID companyId, UUID caseId, String column) {
        return query(companyId, caseId, column, ResultSet::getInt);
    }

    private Boolean booleanOf(UUID companyId, UUID caseId, String column) {
        return query(companyId, caseId, column, ResultSet::getBoolean);
    }

    private String stringOf(UUID companyId, UUID caseId, String column) {
        return query(companyId, caseId, column, ResultSet::getString);
    }

    private Instant computedAt(UUID companyId, UUID caseId) {
        return instantAt(companyId, caseId, "computed_at");
    }

    private Instant lastAlternationAt(UUID companyId, UUID caseId) {
        return instantAt(companyId, caseId, "last_alternation_at");
    }

    private Instant instantAt(UUID companyId, UUID caseId, String column) {
        var stamp = query(companyId, caseId, column, ResultSet::getTimestamp);
        return stamp == null ? null : stamp.toInstant();
    }

    @FunctionalInterface
    private interface ColumnReader<T> {
        T read(ResultSet rows, String column) throws SQLException;
    }

    private <T> T query(UUID companyId, UUID caseId, String column, ColumnReader<T> reader) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + column + " FROM case_flake WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                T value = reader.read(rows, column);
                return rows.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean rowExists(UUID companyId, UUID caseId) {
        return exists(companyId, caseId, "case_flake");
    }

    private boolean caseHealthRowExists(UUID companyId, UUID caseId) {
        return exists(companyId, caseId, "case_health");
    }

    private boolean exists(UUID companyId, UUID caseId, String table) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM " + table + " WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Straight SQL rather than the service, which does not exist until phase 2.
     *
     * <p>All four authored columns together — which is not this helper being tidy, it is
     * {@code case_flake_quarantine_complete} refusing anything else. Writing this helper with a
     * {@code quarantined_by} that resolved to null is how the constraint first proved it does
     * something: a quarantine that cannot say who made the decision is debt nobody can evaluate,
     * and the fixture had to seed a user rather than the table having to accept one without.
     */
    private void quarantine(UUID companyId, UUID caseId, String reason) throws SQLException {
        try (Connection connection = appConnection(companyId);
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
            statement.executeUpdate();
        }
    }

    private void user(Connection connection, UUID id, String email, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user (id, email, canonical_name, status)
                VALUES (CAST(? AS uuid), CAST(? AS citext), ?, 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, email);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    /** Moves a case's whole history, in both the source table and the fact log. */
    private void backdate(UUID companyId, UUID caseId, Instant to) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            for (String table : List.of("test_execution", "execution_fact")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table + " SET executed_at = CAST(? AS timestamptz) "
                                + "WHERE case_id = CAST(? AS uuid)")) {
                    statement.setString(1, to.toString());
                    statement.setString(2, caseId.toString());
                    statement.executeUpdate();
                }
            }
        }
    }

    /** One result per run, oldest first; a shorter array simply stops early. */
    private void results(Connection connection, List<UUID> runs, UUID caseId, int versionNo,
            String... statusPerRun) throws SQLException {
        for (int i = 0; i < statusPerRun.length; i++) {
            execution(connection, ACME, ACME_PROJECT, runs.get(i), caseId, versionNo,
                    statusPerRun[i], BASE.plusSeconds(86_400L * i));
        }
    }

    private void clearEverything() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("execution_fact", "case_flake", "case_health",
                        "step_result", "test_execution", "test_run", "case_classification",
                        "test_case_version", "test_case", "suite")) {
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

    /**
     * A project-custom status that counts toward the pass rate and is not a {@code PASSED} verdict.
     *
     * <p>The whole point is that {@code kind} and {@code counts_as_pass} are independent columns —
     * this row is the only thing in the suite that tells the two spellings apart.
     */
    private void lenientStatus(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_status
                    (id, company_id, project_id, name, kind, counts_as_pass, is_final, position)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid),
                        'Passed with known issues', 'RETEST', true, true, 99)
                    ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void configuration(Connection connection, UUID id, UUID companyId, UUID projectId,
            String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration (id, company_id, project_id, name, is_default)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, false)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, name);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TS-1', 1, 'Root', 0, 'APPROVED', NULL)
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
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'AUTOMATED', now(), now(),
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
        caseVersion(connection, companyId, id, 1);
    }

    private void caseVersion(Connection connection, UUID companyId, UUID caseId, int versionNo)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), ?,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setInt(3, versionNo);
            statement.setString(4, "hash-" + caseId + "-v" + versionNo);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, UUID configurationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Flake fixture run', 'IN_PROGRESS', 'MANUAL',
                        coalesce(CAST(? AS uuid),
                                 (SELECT c.id FROM configuration c
                                   WHERE c.project_id = CAST(? AS uuid) AND c.is_default LIMIT 1)),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, configurationId == null ? null : configurationId.toString());
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, int versionNo, String statusName, Instant executedAt) throws SQLException {
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
                    ON CONFLICT (run_id, case_id) DO UPDATE
                    SET status_id = EXCLUDED.status_id, executed_at = EXCLUDED.executed_at
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setInt(6, versionNo);
            statement.setString(7, projectId.toString());
            statement.setString(8, statusName);
            statement.setString(9, executedAt.toString());
            statement.executeUpdate();
        }
    }
}
