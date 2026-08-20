package com.tcms.project;

import java.util.UUID;

/**
 * {@code review.approved}, as this module writes it — the sign-off, for a case, a suite or a plan
 * alike.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ReviewApprovedPayload} is the other.
 *
 * <h2>{@link #subjectVersionNo()} is on the payload, and it is the only derived field here</h2>
 *
 * <p>{@link Review#getSubjectVersionNo()} is stamped at submission and never re-stamped, so it is
 * the version somebody was actually asked to read. A sign-off notice that named the artifact
 * without it would be read as approving whatever the artifact says now, which is the exact
 * confusion §6g's diff-since-approval rule exists to prevent.
 *
 * <h2>No bulk operation id, because nothing here is told about one</h2>
 *
 * <p>{@code repository.CaseBulkService} approves up to 500 cases in one operation and calls
 * {@link ReviewService#moved} for each, so one act produces up to 500 of these. A
 * {@code bulkOperationId} would let a future digest group them — and {@code moved} is not given one
 * by any of its four call sites, so the field would be null on every row this product can produce,
 * which is the writerless shape {@code integration.ItemAssignedPayload}'s header refuses. It
 * arrives with the digest, or not at all.
 *
 * @param eventId     minted per occurrence — see {@link ReviewRequestedEvent#eventId()}
 * @param approvedBy  who signed off — the caller of the ladder, holding {@code *:approve}
 * @param submittedBy who had put it forward. Nullable, for {@link ReviewVerdictEvent}'s reason
 */
record ReviewApprovedEvent(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        int subjectVersionNo,
        UUID approvedBy,
        UUID submittedBy) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for every {@code review.*} event. */
    static final String AGGREGATE = "review";
}
