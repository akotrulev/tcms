package com.tcms.execution;

import java.util.UUID;

/**
 * {@code ExecutionAssigneeInput} in {@code docs/specs/openapi.yaml} — one field, and the two states
 * it can be in are both meaningful.
 *
 * @param assigneeId the person this row of the checklist is for, or {@code null} for nobody.
 *                   <strong>Null is a value here, not an omission</strong>: the operation states the
 *                   assignee rather than changing it, so unassigning has to be expressible, and a
 *                   body that simply left the field out would make "take this off my plate" the one
 *                   thing the endpoint could not say. A body with no {@code assigneeId} at all is
 *                   therefore indistinguishable from an explicit null and unassigns — which the
 *                   contract's {@code required} array declares, so a client that means to leave the
 *                   row alone sends no request rather than an empty one.
 */
public record ExecutionAssigneeRequest(UUID assigneeId) {
}
