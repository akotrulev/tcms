package com.tcms.integration;

import com.tcms.project.ReviewSubjectType;
import java.util.UUID;

/**
 * {@code review.endorsed} and {@code review.changes_requested}, as this module reads them.
 *
 * <p>One record for two event types, because the two occurrences differ only in what a peer said
 * and the name carries that. Two handlers claim the two names and both parse this; see
 * {@code project.ReviewVerdictEvent} for why the record has no verdict field, and
 * {@link ReviewRequestedPayload} for why the subject type crosses as an enum.
 *
 * @param submittedBy the person a "changes requested" is addressed to, or null on an unattributable
 *                    review. The renderer then omits the fact rather than naming nobody
 */
record ReviewVerdictPayload(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID reviewerId,
        UUID submittedBy,
        String comment) {
}
