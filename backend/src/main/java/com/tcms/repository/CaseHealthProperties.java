package com.tcms.repository;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The thresholds the health recompute derives its signals from (plan §6o).
 *
 * <h2>These are instance-wide, and §6o says they should be per project</h2>
 *
 * <p>Stated here rather than discovered later: §6o is explicit that "thresholds are project
 * settings, not constants", and plan §8 open question #30 — the defaults for stale automation and
 * long-unexecuted — is <strong>still open</strong>. There is no project settings table in {@code V1}
 * at all ({@code settings JSONB} exists on {@code company}, not on {@code project}), so a per-project
 * threshold cannot be stored today without inventing schema for a question nobody has answered.
 *
 * <p>Typed configuration is the honest middle: the number has a name, a documented default and one
 * place to change it, and the day #30 lands this record becomes the <em>fallback</em> a project
 * setting overrides rather than something that has to be found and untangled. The alternative — a
 * {@code 90} inlined in the SQL — is the version that gets copied to a second query and then
 * disagrees with itself, which is the failure mode {@code lib/labels.ts} exists to prevent on the
 * other side of the wire.
 *
 * @param longUnexecuted how old a case's newest recorded result may be before
 *                       {@link HealthSignal#LONG_UNEXECUTED} is raised. §6o suggests 90 days.
 *                       <strong>Deliberately a {@link Duration} rather than an int of days</strong>:
 *                       a bare number invites the reader to guess the unit, and the one test that
 *                       pins the boundary has to state a unit anyway.
 * @param staleAutomation how long CI may go without posting a result for a case before
 *                       {@link HealthSignal#STALE_AUTOMATION} is raised — and, on the other side of
 *                       the same cutoff, how recent an ingested result must be for
 *                       {@link HealthSignal#UNDECLARED_AUTOMATION} to fire on a case that does not
 *                       claim automation. <strong>Open question #30 resolved this to 14 days</strong>
 *                       (2026-08-02), instance-wide, so there is no number being chosen here.
 *                       <p><strong>One duration for both signals is deliberate.</strong> They read
 *                       one fact — the newest ingested result — on opposite sides of one cutoff, so
 *                       a second threshold would let the pair overlap (a case raising both) or leave
 *                       a gap (a case raising neither while its automation is plainly drifting).
 *                       Open question #34 made the same choice for the stale-plan threshold and said
 *                       why: <em>one figure to remember rather than two that drift</em>.
 * @param enabled        whether {@link CaseHealthScheduler} is registered at all.
 *                       <p><strong>Its real job is the test suite, and the reason is worth stating
 *                       because the failure would have been blamed on something else.</strong> The
 *                       recompute is a <em>write</em> on a timer, against every company in the
 *                       database. Left on under {@code @SpringBootTest}, it fires partway through a
 *                       multi-minute run and rewrites {@code case_health} for fixtures belonging to
 *                       whichever test classes happen to have seeded by then — including
 *                       {@code DashboardEndpointTest}, whose whole point is that
 *                       {@code healthComputedAt} is null. The symptom would be a test that passes
 *                       alone and fails in the suite, at a different point each run: §19's first
 *                       defect exactly, and the hardest kind to attribute. {@code IntegrationTestBase}
 *                       sets this false for every integration test, and
 *                       {@code CaseHealthRecomputeTest} drives the service directly instead, which
 *                       is what a scheduled job's logic should be tested through anyway.
 */
@ConfigurationProperties(prefix = "tcms.case-health")
@Validated
public record CaseHealthProperties(
        @NotNull Duration longUnexecuted, @NotNull Duration staleAutomation, boolean enabled) {

    /**
     * Compact constructor, because {@code @Positive} cannot express this.
     *
     * <p>Bean Validation has no validator for {@link Duration} — {@code @Positive} is defined over
     * numbers, and annotating a {@code Duration} with it fails the <em>context startup</em> with
     * {@code HV000030}, not the value. It looked right and was caught only by booting.
     *
     * <p>The check is worth keeping in some form: a zero or negative window makes the cutoff
     * {@code now} or later, so every case with any recorded result becomes {@code LONG_UNEXECUTED}
     * at once. That reads as a data catastrophe on the dashboard rather than as a misconfigured
     * duration, which is exactly the kind of thing that should refuse to start.
     */
    public CaseHealthProperties {
        if (longUnexecuted != null && (longUnexecuted.isZero() || longUnexecuted.isNegative())) {
            throw new IllegalArgumentException(
                    "tcms.case-health.long-unexecuted must be a positive duration, was "
                            + longUnexecuted);
        }
        // The same check, and it fails in a more confusing direction than its neighbour: a
        // non-positive window puts the cutoff at now or later, so EVERY case with any ingested
        // result becomes STALE_AUTOMATION and every case without one becomes clean — the pair
        // inverts rather than merely over-firing, and the dashboard reads as a project whose
        // automation all died at once while the unautomated cases look healthy.
        if (staleAutomation != null && (staleAutomation.isZero() || staleAutomation.isNegative())) {
            throw new IllegalArgumentException(
                    "tcms.case-health.stale-automation must be a positive duration, was "
                            + staleAutomation);
        }
    }
}
