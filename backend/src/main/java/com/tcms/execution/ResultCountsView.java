package com.tcms.execution;

/**
 * {@code ResultCounts} in {@code docs/specs/openapi.yaml} — counts, never percentages.
 *
 * <p>The contract's own description says why: the SPA offers a denominator toggle (pass rate over
 * everything, or over what has actually been executed), and it needs both numbers to compute
 * either. A server-computed percentage would fix the denominator for every reader (plan §6p).
 *
 * @param notRun <strong>every execution that does not yet carry a final verdict</strong> — no status
 *               recorded, or one whose {@code StatusKind} is {@code UNTESTED} or {@code RETEST}.
 *               Grouping {@code RETEST} here rather than inventing a seventh bucket follows the
 *               contract's shape and the meaning of {@code case_status.is_final}: a case marked for
 *               retest is still owed a result, and counting it as a pass or a fail states an outcome
 *               nobody reached. It also keeps the arithmetic the SPA's {@code lib/results.ts}
 *               depends on true — passed + failed + blocked + skipped + notRun = total.
 */
public record ResultCountsView(
        long passed, long failed, long blocked, long skipped, long notRun, long total) {
}
