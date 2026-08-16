package com.tcms.reporting;

/**
 * Where a report's numbers came from — <strong>E1 of
 * {@code docs/audits/2026-08-15-flow-review-and-improvements.md}</strong>.
 *
 * <p>The audit's finding: <em>"Three surfaces answer 'pass rate' with different freshness and
 * denominators — run summary (live), plan statistics (live, policy echoed), aggregate execution
 * (≤15 min stale). The fact-based report should carry its projection watermark the way plan
 * statistics already echoes its aggregation policy."</em>
 *
 * <p>Two values, because there are two answers this product can give. It is deliberately not a
 * duration or a boolean: {@link ReportHeaders} needs to print a <em>sentence</em>, and the sentence
 * differs in kind rather than in degree — one says the numbers are the database's as of the moment
 * of computation, the other says they are a projection's, refreshed at a stated instant.
 *
 * <p><strong>Not on the wire and not a parameter.</strong> A caller cannot ask for a report to be
 * fresher; the report type decides, which is why {@link ReportComputation#freshness()} declares it
 * and the default is the honest one for a computation that reads tables directly.
 */
enum DataFreshness {

    /**
     * Computed from the tables themselves, inside the job's own transaction — so the numbers are
     * the database's as at {@code generatedAt} and nothing sits between them and the rows.
     * {@code RunSummaryReport} and {@code CoverageMatrixReport}.
     */
    LIVE,

    /**
     * Computed from {@code execution_fact}, the projection.
     *
     * <p>{@code ReportJobRunner} refreshes that projection <strong>inside the same transaction,
     * immediately before computing</strong> (slice-plan decision A), so a generated report's
     * watermark is exact rather than up to a scheduler tick old — which is precisely the fact worth
     * printing, because the same table read <em>outside</em> a job is up to
     * {@code tcms.reporting.fact-refresh-interval} behind. A reader holding two documents cannot
     * otherwise tell a report that refreshed first from one that did not.
     */
    PROJECTED
}
