package com.tcms.execution;

/**
 * How a case executed many times resolves to one result — {@code AggregationPolicy} in
 * {@code docs/specs/openapi.yaml} (plan §6e).
 *
 * <p><strong>The policy is part of the answer, not a setting.</strong> A plan whose cases ran across
 * three configurations has several defensible pass rates, and two teams reading "89% pass" without
 * being told the arithmetic mean different things while both believe they agree. So the chosen value
 * is echoed on every response and the SPA is required to display it; that requirement is the reason
 * this is an enum on the wire rather than a server-side default nobody can see.
 *
 * <p>The five are not variations on a theme — they answer different questions, and the two at the
 * ends exist to be argued with:
 *
 * <ul>
 *   <li>{@link #LATEST} — <em>the default, and the one §6p specifies for a plan page.</em> Each case
 *       counts once, using its most recent result across every run in the plan. "Where does this
 *       plan stand right now?"</li>
 *   <li>{@link #LATEST_PER_CONFIG} — each case counts once <em>per configuration</em>, so a case run
 *       on three browsers contributes three results and the total exceeds the case count. The one
 *       policy under which that is correct rather than a bug: a plan passing on Chrome and failing
 *       on Safari must not average into a single reassuring number.</li>
 *   <li>{@link #WORST} — a case that failed anywhere counts as failed. The release-gate reading.</li>
 *   <li>{@link #BEST} — a case that passed anywhere counts as passed. <strong>Flatters a flaky
 *       suite</strong>, which is exactly why it is offered by name instead of arrived at by
 *       accident: "it passed on the re-run" is a real argument someone will make, and it is better
 *       made in the open under a label than smuggled into a default.</li>
 *   <li>{@link #ALL} — every execution counted separately; a case retried four times contributes
 *       four results. The only policy that is not a statement about cases.</li>
 * </ul>
 *
 * <p>Ordering matters for {@link #WORST} and {@link #BEST} and it is deliberately <em>not</em> this
 * enum's declaration order — it is a severity order over {@link com.tcms.project.StatusKind}, which
 * {@code PlanStatisticsService} states explicitly.
 */
public enum AggregationPolicy {

    LATEST,
    LATEST_PER_CONFIG,
    WORST,
    BEST,
    ALL;

    /** The contract's {@code default: LATEST}, applied when the caller omits the parameter. */
    public static final AggregationPolicy DEFAULT = LATEST;
}
