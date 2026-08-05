package com.tcms.project;

/**
 * The fixed aggregation class behind a project's own status names — {@code StatusKind} in
 * {@code docs/specs/openapi.yaml}.
 *
 * <p>Statuses are project-configurable and this enum is not, and that split is the point: it is
 * what lets reporting roll up a project that renamed "Blocked" to "Environment down", or added a
 * second passing status, <strong>without knowing any of their names</strong>. A report that
 * branched on the name would silently stop counting the moment a client edited their vocabulary.
 *
 * <p>{@code countsAsPass} is deliberately a separate boolean on the row rather than derived from
 * {@link #PASSED} here: a project may legitimately add "Passed with comment" — a distinct status,
 * of kind {@link #PASSED}, that a stricter exit criterion may still want to exclude.
 */
public enum StatusKind {

    PASSED,
    FAILED,
    BLOCKED,
    SKIPPED,
    UNTESTED,
    RETEST,

    /**
     * Somebody is running this case right now — seeded as a seventh default in {@code V32} (M9),
     * matching Zephyr. Not final and not a pass, like {@link #RETEST}, so every reporting rollup
     * groups it with {@link #UNTESTED} as <em>still owed a verdict</em>: a case somebody has picked
     * up has no result, and counting it as anything else would move a pass rate on the strength of
     * a tester opening a screen.
     */
    WIP
}
