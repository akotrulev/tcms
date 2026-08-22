package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionResultInput} in {@code docs/specs/openapi.yaml} — the body of the one write.
 *
 * <p><strong>No {@code lockVersion} component, deliberately.</strong> The version the client read is
 * a precondition about the state it started from, and it travels as {@code If-Match}. A body field
 * would be a value on the input schema that must never be persisted — the divergence §30 found on
 * the suite rename and the SPA has carried on this endpoint since Phase 1, uncorrected only because
 * no server read it. Adding it here would make the divergence permanent by making it true.
 *
 * @param statusId the verdict. Required: this endpoint records a result, and a PATCH that changed
 *                 only a comment would silently be a different operation with a different permission
 *                 attached to it.
 * @param comment  the tester's note. <strong>Null means clear it</strong>, not "leave it alone": the
 *                 contract's own account of a PATCH body is that it states the desired state, the
 *                 SPA sends the field on every save, and a stale comment left beside a new verdict
 *                 describes the wrong run.
 * @param durationMs how long the test took, in milliseconds, or null. <strong>Null means clear
 *                 it</strong> — the same total-state rule {@link #comment} carries, and for the same
 *                 reason: the SPA sends the field on every save, so a duration left behind by an
 *                 earlier attempt would describe the wrong run. <strong>Zero is a duration</strong>,
 *                 not an absence, so the assignment is guarded on null and never on truthiness.
 *                 This is {@code test_execution.duration_ms}'s <em>first</em> writer — the column
 *                 has existed since {@code V1} and is read in five places, but the ingestion
 *                 parsers put the duration they recover into the ingestion job's payload JSON and
 *                 never into the column, so every one of those reads answered null until now.
 * @param stepResults optional and partial — a step the caller does not mention keeps what it had.
 *                 Partial here and total for {@link #comment} because the two differ in what the
 *                 client can see: the comment is one field on the screen the tester is looking at,
 *                 and a step list may legitimately be sent for only the steps they touched.
 */
public record ExecutionResultRequest(
        UUID statusId, String comment, Long durationMs, List<StepResultRequest> stepResults) {

    /**
     * One step's verdict.
     *
     * @param stepId must be a step of the <strong>pinned</strong> case version. One that is not is a
     *               400 on the field rather than a dropped entry: silently ignoring it is a save that
     *               reports success and discards what the tester marked.
     */
    public record StepResultRequest(UUID stepId, UUID statusId, String comment) {
    }
}
