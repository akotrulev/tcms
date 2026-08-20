package com.tcms.integration;

import com.tcms.project.ReviewSubjectType;
import java.util.UUID;

/**
 * {@code review.approved}, as this module reads it — the sign-off.
 *
 * <p>The consumer half of a pair; {@code project.ReviewApprovedEvent} is the other. See
 * {@link ReviewRequestedPayload} for why the subject type crosses as an enum.
 *
 * @param subjectVersionNo the version that was submitted and reviewed, never the artifact's current
 *                         one. Rendered as a fact because a sign-off that named the artifact alone
 *                         reads as approving whatever it says now
 */
record ReviewApprovedPayload(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        int subjectVersionNo,
        UUID approvedBy,
        UUID submittedBy) {
}
