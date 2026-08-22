package com.tcms.execution;

import com.tcms.tenancy.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one speller of an assignment's audit row — <strong>both writers of
 * {@code test_execution.assignee_id} go through it.</strong>
 *
 * <p>Extracted in §244 rather than copied, because the copy would have been the shape §72 already
 * paid for once here: two sites building the same JSON document by concatenation, under an
 * instruction to keep them in step. A bulk act and a single assignment are the same fact about a
 * row and must read identically in the trail — {@code
 * ExecutionBulkAssignTest.theTrailReadsTheSameWhicheverDoorWasUsed} is the assertion, and it fails
 * the day somebody adds an {@code execution.bulk_assigned} verb.
 *
 * <p>The document names <strong>both ends</strong> deliberately: the question an auditor asks is
 * about a MOVE, and "who took this off Ana" is unanswerable from a row that only records where it
 * landed.
 */
final class ExecutionAssignmentAudit {

    /** The target type both writers file under — one vocabulary, so one filter finds both. */
    static final String TARGET_TYPE = "TEST_EXECUTION";

    private ExecutionAssignmentAudit() {
    }

    /** {@code execution.assigned}, or {@code execution.unassigned} when nobody now holds the row. */
    static String action(UUID to) {
        return to == null ? "execution.unassigned" : "execution.assigned";
    }

    /** {@code {"from":…,"to":…}}, with both ends spelled as a JSON null when absent. */
    static String changes(UUID from, UUID to) {
        return "{\"from\":" + id(from) + ",\"to\":" + id(to) + "}";
    }

    private static String id(UUID value) {
        return value == null ? "null" : AuditEvent.jsonString(value.toString());
    }

    /** One row of a single assignment — the door {@link ExecutionAssignmentService} uses. */
    static AuditEvent row(UUID projectId, UUID actorUserId, UUID executionId, UUID from, UUID to,
            Instant now) {

        return AuditEvent.record(
                projectId, actorUserId, action(to), TARGET_TYPE, List.of(executionId),
                changes(from, to), now);
    }

    /** One row of a bulk act. {@code bulkOperationId} is what makes it groupable in the trail. */
    static AuditEvent bulkRow(
            UUID projectId, UUID actorUserId, UUID executionId, UUID from, UUID to,
            Instant now, UUID bulkOperationId) {

        return AuditEvent.record(
                projectId, actorUserId, action(to), TARGET_TYPE, List.of(executionId),
                changes(from, to), now, bulkOperationId);
    }
}
