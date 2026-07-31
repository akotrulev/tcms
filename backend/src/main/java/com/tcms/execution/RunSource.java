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
    INGESTED
}
