package com.tcms.execution;

/**
 * Which run transitions are legal — <strong>the whole lifecycle, decided in one pure function</strong>.
 *
 * <p>Pure and static on purpose, the shape {@code BulkOperations} and {@code PageContent} use: the
 * decision is a property of two statuses and a timestamp's presence, so it needs no row, no gate and
 * no transaction, and a test can enumerate the table without a database.
 *
 * <h2>"Closed" is a status pair, not a fifth value</h2>
 *
 * <p>{@code V1}'s comment on {@code evidence} says it is <em>immutable once the run closes</em> and
 * {@code V3} describes the permission as <em>"Close or abort a test run"</em> — two acts, and
 * {@link RunStatus} already has exactly two terminal values whose difference its own javadoc
 * explains. So <strong>closed ≡ terminal ≡ {@code COMPLETED} or {@code ABORTED}</strong>, and no
 * {@code CLOSED} value is added. A fifth value would give the product two ways to say a run is over,
 * and every reader — the run list, plan statistics, M2.5's reporting — would have to know both.
 *
 * <h2>Starting is automatic; finishing is not</h2>
 *
 * <p>{@code NOT_STARTED → IN_PROGRESS} happens in {@link ExecutionService} when the first result
 * lands, with no permission of its own: the caller has already passed {@code result:submit}, and a
 * second gate there would mean a tester records a result the run then declines to acknowledge. It is
 * the rule {@code TestRun.create}'s javadoc has asserted since §42 and nothing implemented.
 *
 * <p>The reverse is <strong>not</strong> symmetric. A run does not complete itself when the last
 * result lands, because "we are done" is a judgement rather than an arithmetic fact — a scope may be
 * deliberately part-executed — and auto-completion would let a Test Engineer close a run by
 * finishing it, which is precisely the boundary {@code run:close} draws (roles spec §A4: creative
 * versus destructive).
 *
 * <h2>Reopening exists because a mis-click must be repairable</h2>
 *
 * <p>A product whose only path out of a state is the operator's psql prompt is the bound §68 and
 * §69 each recorded from the other side. Reopening is the same permission that closed it — somebody
 * who may end a run may un-end it — and the target is <strong>derived, never chosen</strong>: a run
 * reopens to {@code IN_PROGRESS} if it ever started and to {@code NOT_STARTED} if it did not, so an
 * abort that happened before anybody touched the checklist cannot come back claiming to have run.
 * Both spellings are accepted from the client and normalized here, so neither can produce a lying
 * row.
 */
final class RunLifecycle {

    private RunLifecycle() {
    }

    /**
     * What a write refused by a closed run says — <strong>one sentence, three call sites</strong>.
     *
     * <p>Raised from {@code RunController} on a result and from {@code EvidenceController} on both
     * evidence writes. A copy per controller is §58's E1 and §70's verb list in miniature: two
     * copies under an instruction to keep them in step, and a tester told one thing when the verdict
     * refuses and another when the screenshot refuses reads them as two problems and reports the
     * second as a bug.
     */
    static final String CLOSED_MESSAGE =
            "This run has ended, so its results and evidence can no longer change. "
                    + "Reopen the run to make changes.";

    /** What the request does to the run. Sealed, so the caller's switch is exhaustive. */
    sealed interface Decision {

        /** A real move. The service applies it, stamps the timestamps and writes one audit row. */
        record Move(RunStatus target) implements Decision {
        }

        /**
         * The run is already in the requested state. 200 with the run, not 409 — two leads closing
         * the same run is agreement rather than a conflict, which is the call
         * {@code DocPageWriteService#setStatus} made for publishing a page and the same call here.
         * Nothing is stamped and nothing is audited, or a repeated click buries the change that
         * actually happened under rows recording that nothing did.
         */
        record Unchanged() implements Decision {
        }

        /**
         * The move is not one this lifecycle has. A <strong>409</strong>, not a 400: the payload is
         * well-formed and the permission is held — it is the run's current state that refuses, and
         * the client's own copy of it is out of date or its reasoning is wrong.
         *
         * @param message written for the person looking at the run, naming the way forward
         */
        record Refused(String message) implements Decision {
        }
    }

    /** Whether the run is over, in the only sense the product has — see the class header. */
    static boolean isClosed(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.ABORTED;
    }

    /**
     * The transition table.
     *
     * @param hasStarted whether {@code started_at} is set — what a reopen normalizes against, and
     *                   the reason this takes a boolean rather than the whole run: the decision must
     *                   not be able to read anything else about it
     */
    static Decision decide(RunStatus current, RunStatus requested, boolean hasStarted) {
        RunStatus target = switch (requested) {
            // Both live spellings mean "reopen", and which one the caller sent is not consulted.
            case NOT_STARTED, IN_PROGRESS -> hasStarted ? RunStatus.IN_PROGRESS
                    : RunStatus.NOT_STARTED;
            case COMPLETED, ABORTED -> requested;
        };

        if (target == current) {
            return new Decision.Unchanged();
        }
        if (isClosed(current) && isClosed(target)) {
            // Completed → aborted, or the reverse. Refused rather than allowed, because a run that
            // slid from one terminal state to the other leaves no record that it was ever in the
            // first: the audit trail would show one ending and the row would show another. Reopening
            // is one extra call and it makes both endings real events.
            return new Decision.Refused(
                    "This run has already ended. Reopen it before recording a different outcome.");
        }
        if (target == RunStatus.COMPLETED && current == RunStatus.NOT_STARTED) {
            // A run with no results is not a pass over its scope, and calling it one puts a 0%
            // completed run into every report beside runs that were actually executed. ABORTED is
            // the value that exists for exactly this, per RunStatus's own javadoc.
            return new Decision.Refused(
                    "Nothing has been recorded in this run yet, so it cannot be completed. "
                            + "Abort it instead.");
        }
        return new Decision.Move(target);
    }
}
