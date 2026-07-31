package com.tcms.project;

import java.util.UUID;

/**
 * {@code CaseStatus} in {@code docs/specs/openapi.yaml} — the wire shape.
 *
 * <p>{@code companyId}, {@code projectId} and {@code position} are dropped for the reasons
 * {@link ClassificationDimensionView} gives.
 *
 * @param kind         the fixed aggregation class behind the configurable name — see
 *                     {@link StatusKind} for why reporting must read this and never the name
 * @param color        <strong>the one field where schema and contract disagree.</strong> The
 *                     contract marks it {@code required}, so the generated SPA type says it is
 *                     always a string, while {@code case_status.color} is nullable in {@code V1}.
 *                     The seed sets all six, and nothing else writes a status yet, so the
 *                     invariant holds today by luck rather than by construction — pinned by a test
 *                     rather than left to hold silently. Closing it properly is a {@code NOT NULL}
 *                     migration plus a colour validator, and both belong with the status-editing
 *                     endpoint that first makes a null reachable. Recorded, not fixed here: status
 *                     colour is load-bearing data and is not brandable (§6u), so a status with no
 *                     colour is a data defect rather than a state to render.
 * @param countsAsPass separate from {@code kind} on purpose — "Passed with comment" is of kind
 *                     PASSED and a stricter exit criterion may still exclude it
 * @param isFinal      false ⇒ a result in this status leaves the case still owed a verdict
 */
public record CaseStatusView(
        UUID id,
        String name,
        StatusKind kind,
        String color,
        boolean countsAsPass,
        boolean isFinal) {

    static CaseStatusView of(CaseStatus status) {
        return new CaseStatusView(
                status.getId(),
                status.getName(),
                status.getKind(),
                status.getColor(),
                status.isCountsAsPass(),
                status.isFinal());
    }
}
