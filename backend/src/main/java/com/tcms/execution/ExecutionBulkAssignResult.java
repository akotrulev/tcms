package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionBulkResult} — every outcome as a list, never a count ({@code BulkResult}'s rule,
 * plan §6f).
 *
 * <p><strong>{@link #changed} carries whole rows where the case result carries keys</strong>, and
 * that divergence is the point: for a distribute, a list of keys says that something moved and not
 * where, which is the entire question the preview exists to answer.
 *
 * @param bulkOperationId the id every audit row of this act shares — null on a preview, and null on
 *                        a commit that changed nothing, because naming a group with no members is a
 *                        claim about rows that do not exist.
 */
public record ExecutionBulkAssignResult(
        boolean dryRun,
        int total,
        List<AssignmentChange> changed,
        List<String> unchanged,
        List<SkippedRow> skipped,
        UUID bulkOperationId) {

    /**
     * One row that moved, or would.
     *
     * @param assigneeName resolved at read time from this company's membership, never stored. Null
     *                     when the row is being unassigned and null when the id names a membership
     *                     that no longer exists at all — the client owns that copy, so the server
     *                     does not invent "Unknown".
     */
    public record AssignmentChange(
            UUID executionId, String caseKey, UUID assigneeId, String assigneeName) {
    }

    /** A selected id that could not be assigned, with a sentence a dialog can print. */
    public record SkippedRow(UUID executionId, String reason) {
    }
}
