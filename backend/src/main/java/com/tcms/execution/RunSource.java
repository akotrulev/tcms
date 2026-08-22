package com.tcms.execution;

/**
 * Where a run's results came from — the {@code CHECK} on {@code test_run.source} in {@code V1}.
 *
 * <p>On the wire because the two are not interchangeable to a reader: an {@code INGESTED} run's
 * executions carry {@code executed_by_token} rather than a person, and "who ran this" has a
 * different answer and a different level of trust for each.
 */
public enum RunSource {

    MANUAL,
    INGESTED,

    /**
     * A {@code run_schedule} fired — {@code V68}, §247.
     *
     * <p>The third door, and the one whose runs have <strong>no creator</strong>:
     * {@code test_run.created_by} is null and so is the actor on the {@code run.created} audit row,
     * because nobody performed the act at 03:00. The schedule's owner is the <em>authority</em> the
     * create ran under — re-checked on every fire, so a schedule outlives neither the grant nor the
     * account — and that is a different question with a different column.
     *
     * <p>Adding a member here rather than treating the value as a string is what makes an
     * exhaustive {@code switch} over this enum a compile error until it handles the new door,
     * under {@code -Werror}.
     */
    SCHEDULED
}
