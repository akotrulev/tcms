package com.tcms.reporting;

/**
 * Where a generation request has got to — {@code report_job.status}'s four values ({@code V27}).
 *
 * <p>Stored as its name, and the migration's {@code CHECK} is the backstop: an enum with no
 * constraint behind it is a constraint that holds only for the writers that happen to go through
 * Java.
 *
 * <h2>Four, and there is deliberately no {@code CANCELLED}</h2>
 *
 * <p>Nothing can cancel a job in v1 — the worker is single-threaded and a claimed job finishes
 * inside one tick — and a state no code can reach renders <em>identically</em> to a state the
 * product cannot produce yet. The client's own enum is these four (the contract's
 * {@code ReportJob.status}), and the mock's {@code QUEUED} was a fifth spelling that was never one
 * of them — recorded in the past tense because the mock layer was deleted on 2026-08-06, so there
 * is no second vocabulary left to disagree with this one.
 */
public enum ReportJobStatus {

    /** Written by the request, inside its own transaction. What the worker looks for. */
    PENDING,

    /** Claimed. {@code started_at} is set and {@code attempts} has been incremented. */
    RUNNING,

    /**
     * Done, and <strong>both</strong> artifacts are downloadable. Never written before the bytes are
     * in the store — a {@code COMPLETED} job whose download 404s is the failure
     * {@code ReportJobRunner}'s ordering exists to prevent.
     */
    COMPLETED,

    /**
     * Ended without an artifact, with {@code error} saying why. There is no retry policy: the user
     * re-requests, which is honest and costs one click.
     */
    FAILED
}
