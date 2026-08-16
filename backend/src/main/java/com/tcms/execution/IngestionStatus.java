package com.tcms.execution;

/**
 * Where a posted report got to — the {@code CHECK} on {@code ingestion_job.status} in {@code V1}.
 *
 * <p><strong>The synchronous lane writes {@link #COMPLETED} and — since B6 — {@link #FAILED}</strong>,
 * and {@link IngestionJob}'s header says why the other two are not an omission. Briefly: the
 * idempotency key used to be consumed by whichever attempt wrote a row, so a {@code FAILED} row
 * would have made the corrected retry replay the failure; {@code V60} narrowed that uniqueness to
 * {@code COMPLETED} rows, so a rejected report is recorded and consumes nothing.
 *
 * <p>The enum is kept whole rather than narrowed to the one reachable value, and that is the
 * opposite call to {@code LinkSourceType} (§93), which is deliberately narrower than its column.
 * The difference is what a value <em>means</em>: {@code LinkSourceType}'s extra arms name link
 * targets that do not exist, so accepting one would be accepting a reference to nothing. These name
 * states of a job, and the states are real — a job is genuinely pending before an async runner picks
 * it up. Narrowing here would have to be widened again by the slice that adds the runner, which is
 * the churn the wide-{@code CHECK} rule exists to avoid.
 */
public enum IngestionStatus {

    /** Accepted, not yet processed. The async lane's; see the class header. */
    PENDING,

    /** A runner has it. The async lane's. */
    PROCESSING,

    /** Parsed, matched and written. The only value this slice writes. */
    COMPLETED,

    /** Processing threw after the row existed. The async lane's. */
    FAILED
}
