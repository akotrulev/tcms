package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionBulkPayload} — both members optional here, exactly one required by the server
 * depending on the action.
 *
 * <p>A flat record rather than a sealed hierarchy per action, matching {@code BulkPayload}: the
 * server has to validate the combination either way because it is the only side that knows the
 * action, so a typed union would buy a compile-time check the client cannot use.
 *
 * @param assigneeId  required by {@code ASSIGN}; <strong>null is a value</strong> and unassigns.
 *                    Because null is meaningful, "the caller sent no argument at all" is a
 *                    different state and is why the request's payload may itself be null.
 * @param assigneeIds required by {@code DISTRIBUTE}. <strong>Order is the caller's and decides who
 *                    takes the remainder</strong>; the service collapses duplicates keeping the
 *                    first occurrence and never sorts.
 */
public record ExecutionBulkAssignPayload(UUID assigneeId, List<UUID> assigneeIds) {
}
