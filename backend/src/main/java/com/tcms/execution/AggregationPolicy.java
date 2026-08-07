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
 * <p>The four are not variations on a theme — they answer different questions, and {@link #WORST}
 * exists to be argued with:
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
 *   <li>{@link #ALL} — every execution counted separately; a case retried four times contributes
 *       four results. The only policy that is not a statement about cases.</li>
 * </ul>
 *
 * <h2>{@code BEST} was removed on 2026-08-07, and this paragraph is why it was ever here</h2>
 *
 * <p>A fifth value — "a case that passed anywhere counts as passed" — shipped in M2 on the argument
 * that <em>"it passed on the re-run" is a real thing somebody will say, and it is better said in the
 * open under a label than smuggled into a default.</em> That argument is not wrong about people and
 * it is wrong about products: <strong>a label does not merely surface the argument, it wins it</strong>,
 * because a dropdown value carries the product's endorsement and a policy named "best" reads as the
 * generous reading rather than the dishonest one. What it produces is a red suite reported green, on
 * a screen whose whole purpose (§6e) is that two teams quoting one pass rate mean the same thing.
 *
 * <p>It cost nothing to drop because the reading it would have been traded against — {@code WORST},
 * the release gate — is still here, and because the product has since grown the honest way to make
 * the same point: <strong>§121's flake detection says a case is unreliable without calling it
 * green</strong>, and §112's variability section shows the retries a policy would have collapsed.
 * A flaky case is now a fact with a name, rather than a number chosen by whoever picked the dropdown.
 *
 * <p>Plan §8 #17 decided this on 2026-08-01 and nothing applied it for six days; #47 is that
 * application. Recorded here rather than only in the plan because the next person to want a
 * generous policy will be reading this file, not §8.
 *
 * <p>Ordering matters for {@link #WORST} and it is deliberately <em>not</em> this enum's declaration
 * order — it is a severity order over {@link com.tcms.project.StatusKind}, which
 * {@code PlanStatisticsService} states explicitly.
 */
public enum AggregationPolicy {

    LATEST,
    LATEST_PER_CONFIG,
    WORST,
    ALL;

    /** The contract's {@code default: LATEST}, applied when the caller omits the parameter. */
    public static final AggregationPolicy DEFAULT = LATEST;
}
