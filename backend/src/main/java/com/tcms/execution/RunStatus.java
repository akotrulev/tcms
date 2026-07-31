package com.tcms.execution;

/**
 * A run's lifecycle — the {@code CHECK} on {@code test_run.status} in {@code V1}.
 *
 * <p>{@code ABORTED} is not {@code COMPLETED}: a run stopped halfway has results that are real and
 * a scope that was never covered, and reporting has to be able to tell the two apart or an
 * abandoned run reads as a finished one with a poor pass rate.
 */
public enum RunStatus {

    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    ABORTED
}
