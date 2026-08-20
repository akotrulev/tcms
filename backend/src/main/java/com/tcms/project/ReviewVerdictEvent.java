package com.tcms.project;

import java.util.UUID;

/**
 * {@code review.endorsed} and {@code review.changes_requested}, as this module writes them.
 *
 * <p><strong>One record, two event names, and the record carries no verdict field.</strong> The two
 * occurrences differ only in what a peer said, and that is what the name is for: a subscriber picks
 * the one it wants at the subscription and never has to filter. A verdict field beside the name
 * would be the same fact twice, and the copy in the payload is the one that can disagree.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ReviewVerdictPayload} is the other.
 *
 * @param eventId     minted per occurrence — see {@link ReviewRequestedEvent#eventId()}
 * @param reviewerId  the peer who spoke, resolved to a name at delivery
 * @param submittedBy who put the artifact forward — the person a "changes requested" is addressed
 *                    to. <strong>Nullable</strong>: {@code review.created_by} permits null and
 *                    {@link ReviewService#refuses} already reasons about an unattributable review,
 *                    so the renderer says the artifact was judged without naming a recipient
 * @param comment     the peer's own words, trimmed, or null when they left none. Carried rather
 *                    than resolved, because unlike a name and a title a remark is immutable —
 *                    {@link ReviewParticipation} is append-only — so the value cannot go stale
 */
record ReviewVerdictEvent(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID reviewerId,
        UUID submittedBy,
        String comment) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for every {@code review.*} event. */
    static final String AGGREGATE = "review";
}
