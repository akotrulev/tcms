package com.tcms.reporting;

import jakarta.validation.constraints.NotNull;
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
public record ReportingProperties(@NotNull Integer failingRepeatedlyWindow, boolean enabled) {

    /**
     * Compact constructor, because the bound that matters is not "positive".
     *
     * <p>A window of {@code 1} parses, validates and produces a signal that duplicates the newest
     * result on every case that has ever failed — which reads on the dashboard as a repository in
     * catastrophic health rather than as a misconfigured number, and self-heals never, because it
     * is doing exactly what it was told. That is the shape that should refuse to start.
     */
    public ReportingProperties {
        if (failingRepeatedlyWindow != null && failingRepeatedlyWindow < 2) {
            throw new IllegalArgumentException(
                    "tcms.reporting.failing-repeatedly-window must be at least 2 — a window of 1 "
                            + "makes FAILING_REPEATEDLY mean 'failed', which every other surface "
                            + "already says. Was " + failingRepeatedlyWindow);
        }
    }
}
