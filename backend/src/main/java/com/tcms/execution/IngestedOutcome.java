package com.tcms.execution;

import com.tcms.project.StatusKind;

/**
 * What a CI runner said about one test, before it becomes a project's own result status.
 *
 * <h2>This maps to a {@link StatusKind}, never to a status name</h2>
 *
 * <p>{@code case_status} is project-configurable — a client may rename "Failed" to "Defect raised"
 * and may add a second passing status — and {@link ResultStatuses}' header states the rule this
 * follows: <em>the kind is what counting reads, never the name</em>. A parser that produced the
 * string "Passed" would bind every ingesting project to one spelling of a word they own, and the
 * failure would be a silent one — no status matched, so nothing recorded, on a report that parsed.
 *
 * <h2>{@code ERRORED} is a fourth value that collapses to a third kind, deliberately</h2>
 *
 * <p>JUnit and TestNG both distinguish {@code <failure>} (an assertion the test made and lost) from
 * {@code <error>} (the test threw before it could assert anything), and the two carry different
 * messages that are worth keeping apart in the parse — "expected 3 but was 4" and
 * "ConnectException" send a reader to different places. What they must not do is create a fifth
 * bucket in a vocabulary the project owns: {@link StatusKind} has six values and none of them means
 * "errored", so both land on {@link StatusKind#FAILED} and the distinction survives in the message.
 */
public enum IngestedOutcome {

    PASSED(StatusKind.PASSED),
    FAILED(StatusKind.FAILED),

    /**
     * The test threw rather than asserted. {@link StatusKind#FAILED} — see the class header for why
     * this is not its own kind.
     */
    ERRORED(StatusKind.FAILED),

    /**
     * The runner did not execute it. {@link StatusKind#SKIPPED} rather than
     * {@link StatusKind#UNTESTED}: the difference is that somebody <em>decided</em> not to run this
     * one, which is a result, where untested is the absence of one — and an ingested report only
     * ever mentions tests the runner knew about.
     */
    SKIPPED(StatusKind.SKIPPED);

    private final StatusKind kind;

    IngestedOutcome(StatusKind kind) {
        this.kind = kind;
    }

    /** The aggregation class this outcome records as. */
    public StatusKind kind() {
        return kind;
    }
}
