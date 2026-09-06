/**
 * Delivery analytics — burn-up, cycle time and cumulative flow (009 module M9, plan 020).
 *
 * <h2>A sub-package of {@code reporting}, not a fourteenth module</h2>
 *
 * <p>Spring Modulith detects modules at {@code com.tcms.*}, so this package is part of
 * {@code reporting} and inherits its {@code allowedDependencies} — the five foundation modules —
 * exactly as {@code tracker.release} and {@code tracker.imports} sit inside theirs. It is a
 * sub-package rather than seven more classes in {@code reporting} because the two halves answer
 * different kinds of question: everything above is a report <em>document</em> a client generates,
 * polls for and downloads, and everything here is a figure a screen draws now.
 *
 * <h2>What this package may read, and the one file that reads it</h2>
 *
 * <p>{@code issue_fact} — the projection whose own table comment says it exists "for reporting, time
 * and billing to join WITHOUT importing tracker" — and two columns of {@code release}. Both are
 * {@code tracker}'s tables, and the crossing lives entirely in {@link
 * com.tcms.reporting.analytics.DeliveryFacts}, per the rule {@code tracker}'s own package comment
 * states: the whole edge in one class, issuing SQL that names the foreign tables and returning
 * records declared here. Nothing else in this package may name one, and
 * {@code ReportingReadsAndNeverWritesTest} reads the source text to prove it — ArchUnit cannot,
 * because SQL is not a Java dependency.
 *
 * <p><strong>Nothing here writes anything.</strong> {@code issue_fact}'s single writer is
 * {@code tracker.IssueFactProjection}, and a second one is the failure scope §3 names by name.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><strong>No velocity, and there will not be one</strong> (scope D6). This product has no sprint,
 * so there is nothing to measure velocity over, and a chart implying otherwise would be a lie about
 * how the team plans. {@code NoVelocityAnywhereTest} asserts the absence across the whole tree
 * rather than leaving it to this paragraph.
 *
 * <p><strong>No utilisation.</strong> It landed with M7 as {@code approved hours ÷ allocated
 * capacity} (017 D-i), is served on {@code PersonCapacity.utilisationPercent}, and is rendered on
 * {@code /staffing} with that denominator printed beside it. A second definition on a second screen
 * is the two-contradictory-percentages failure waiting for its first divergent edit.
 *
 * <p><strong>No estimate-accuracy arm.</strong> Both halves of that panel are {@code time}'s —
 * {@code GET /time/rollup/epics/&#123;key&#125;} carries the estimate and the approved hours — and
 * the screen composes it. An endpoint here would mean either reading {@code issue_time_rollup},
 * which 016 D-c placed off-limits to every module but {@code time}, or re-deriving what
 * <em>approved</em> means, which is the same failure in a different table (020 D-i).
 *
 * <p><strong>No {@code ReportComputation}.</strong> These are not registered in the job pipeline,
 * and 020 D-b records the argument on both sides.
 */
package com.tcms.reporting.analytics;
