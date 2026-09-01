package com.tcms.tracker.release;

/**
 * The contract's {@code ReadinessSummary} — the row-sized answer, carried inline on every
 * {@link ReleaseSummaryView}.
 *
 * <p>{@code casesPassed} and {@code casesFailed} are {@link Integer} and not {@code int}, and that
 * is the whole point of the type choice: when {@code hasRun} is false they are <strong>null</strong>,
 * because an untested version and a clean version are not the same claim and a zero drawn as a meter
 * looks like the second.
 */
public record ReadinessSummaryView(
        ReleaseVerdict verdict,
        int storiesDone,
        int storiesTotal,
        int linkedCases,
        boolean hasRun,
        Integer casesPassed,
        Integer casesFailed) {
}
