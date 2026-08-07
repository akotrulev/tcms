package com.tcms.reporting;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs the reporting read models are derived against (plan §6o, §6e).
 *
 * <h2>Instance-wide, and §6o says they should be per project</h2>
 *
 * <p>Stated here rather than discovered later, and it is the same standing gap
 * {@code repository.CaseHealthProperties} records: §6o is explicit that "thresholds are project
 * settings, not constants", and plan §8 open question #30 is still open. §77 gave the product a
 * {@code project_settings} table, so the storage now exists where it did not when
 * {@code CaseHealthProperties} was written — but the *question* #30 asks (what the defaults should
 * be) is still unanswered, and putting an unanswered number in a per-project row makes it a setting
 * every client is asked to configure before anybody has decided what it means.
 *
 * <p>Typed configuration is the honest middle: the number has a name, a documented default and one
 * place to change it, and the day #30 lands this record becomes the <em>fallback</em> a project
 * setting overrides rather than something that has to be found and untangled. The alternative — a
 * {@code 3} inlined in the SQL — is the version that gets copied to a second query and then
 * disagrees with itself.
 *
 * @param failingRepeatedlyWindow how many of a case's most recent recorded results must all be
 *        failures before {@code HealthSignal#FAILING_REPEATEDLY} is raised. §6o words the signal as
 *        "failed the last N executions" and names no N.
 *        <p><strong>Three is the smallest number that means anything.</strong> At {@code 1} the
 *        signal is "failed", which the case grid, the run page and the dashboard's own result
 *        counts all already say — a health signal that restates the newest result is a second,
 *        slower copy of a fact the reader has. At {@code 2} a flake plus a real failure raises it,
 *        which is the coincidence §6o's "decaying" severity is trying to filter out. Above three
 *        the signal needs a history a young project does not have, and a signal that cannot fire in
 *        the first month of a project is one nobody learns to trust.
 *        <p>An {@code int} rather than a {@code Duration}, unlike {@code longUnexecuted}, because
 *        §6o words this one as a <em>count of executions</em> and not as an age: a case run nightly
 *        and a case run quarterly are both failing repeatedly after three failures, and expressing
 *        it in time would make the signal mean something different per project without saying so.
 * @param flakeWindow how far back {@code case_flake}'s scoring reads (§6l, M3 slice 6). Ninety days.
 *        <p><strong>Bounded for §120's reason, arriving from the other direction.</strong> An
 *        unbounded flake score is one that <em>can never go false</em>: a test that flapped badly
 *        last spring and has been solid ever since keeps a high score for ever, with nothing anybody
 *        can do about it. §120 rejected exactly that shape for {@code UNDECLARED_AUTOMATION} and
 *        gave the rule — a derived claim with no route back is as dishonest as a stored one.
 *        <p>A {@link Duration} rather than a count of executions, and it is the opposite call to
 *        {@link #failingRepeatedlyWindow} two fields up. That one is a count because §6o words it as
 *        one and because a nightly case and a quarterly case are both failing repeatedly after three
 *        failures. This one is a duration because it answers "is this test flaky <em>now</em>", and
 *        a fixed count of results means a week of history for a nightly suite and two years for a
 *        quarterly one — the same knob meaning different things per project without saying so.
 * @param flakeMinimumTransitions how many adjacent pairs of outcomes inside one cell are needed
 *        before a score is claimed at all. Below it {@code flake_score} is <strong>null</strong>,
 *        which is a different claim from {@code 0}.
 *        <p>Two results that went pass then fail score {@code 1.0} on any ratio definition, and that
 *        is noise rather than a flake — the number a human would then use to decide whether to
 *        quarantine a test. Four is the smallest floor that survives one coincidence: at {@code 1} a
 *        single flip is a perfect score, and at {@code 2} a flip and a recovery still is.
 * @param enabled whether {@link ExecutionFactScheduler} is registered at all.
 *        <p><strong>Its real job is the test suite</strong>, exactly as
 *        {@code tcms.case-health.enabled}'s is, and the reason is worth restating because the
 *        failure would be blamed on something else. The refresh is a <em>write</em> on a timer over
 *        every company in the database. Left on under {@code @SpringBootTest} it fires partway
 *        through a multi-minute run and projects facts for whichever test classes have seeded by
 *        then — presenting as a test that passes alone and fails in the suite at a different point
 *        each run, which is §19's first defect and the hardest kind to attribute. It is
 *        <em>worse</em> here than for case-health, because the fact log is append-only and its
 *        dimensions are frozen on insert: a row written early is not corrected by a later refresh,
 *        so the damage outlives the tick that caused it. {@code IntegrationTestBase} sets this false
 *        for every integration test and the reporting tests drive the service directly, which is
 *        what a scheduled job's logic should be tested through anyway.
 */
@ConfigurationProperties(prefix = "tcms.reporting")
@Validated
public record ReportingProperties(
        @NotNull Integer failingRepeatedlyWindow,
        @NotNull Duration flakeWindow,
        @NotNull Integer flakeMinimumTransitions,
        boolean enabled) {

    /**
     * Compact constructor, because the bound that matters is not "positive".
     *
     * <p>A window of {@code 1} parses, validates and produces a signal that duplicates the newest
     * result on every case that has ever failed — which reads on the dashboard as a repository in
     * catastrophic health rather than as a misconfigured number, and self-heals never, because it
     * is doing exactly what it was told. That is the shape that should refuse to start.
     *
     * <p>The same argument covers all three checks below: each rejects a value that <em>works</em>
     * and produces a confidently wrong number, rather than one that fails.
     */
    public ReportingProperties {
        if (failingRepeatedlyWindow != null && failingRepeatedlyWindow < 2) {
            throw new IllegalArgumentException(
                    "tcms.reporting.failing-repeatedly-window must be at least 2 — a window of 1 "
                            + "makes FAILING_REPEATEDLY mean 'failed', which every other surface "
                            + "already says. Was " + failingRepeatedlyWindow);
        }
        // Bean Validation has no validator for Duration — @Positive is defined over numbers, and
        // annotating a Duration with it fails CONTEXT STARTUP with HV000030 rather than the value.
        // CaseHealthProperties records that it looked right and was caught only by booting.
        if (flakeWindow != null && (flakeWindow.isZero() || flakeWindow.isNegative())) {
            throw new IllegalArgumentException(
                    "tcms.reporting.flake-window must be a positive duration — a non-positive "
                            + "window selects no executions, so every case scores null and the "
                            + "board's quarantine worklist is empty for a reason nobody can see. "
                            + "Was " + flakeWindow);
        }
        if (flakeMinimumTransitions != null && flakeMinimumTransitions < 1) {
            throw new IllegalArgumentException(
                    "tcms.reporting.flake-minimum-transitions must be at least 1 — a floor of 0 "
                            + "gives every case that ever ran twice a score, and the first case to "
                            + "go pass-then-fail reports a perfect 1.0. Was "
                            + flakeMinimumTransitions);
        }
    }
}
