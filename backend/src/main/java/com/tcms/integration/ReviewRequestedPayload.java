package com.tcms.integration;

import com.tcms.project.ReviewSubjectType;
import java.util.UUID;

/**
 * {@code review.requested}, as this module reads it.
 *
 * <p>The consumer half of a pair; {@code project.ReviewRequestedEvent} is the other. See
 * {@link RunCompletedPayload}'s header for why they are two records and what keeps them honest.
 *
 * <h2>{@link ReviewSubjectType} is shared, and that is not the sharing plan §3 forbids</h2>
 *
 * <p>The rule is that the producing and consuming modules may not share the <em>payload</em> class,
 * because that is the shape the wire carries and a compiler agreeing about it hides a drift the
 * wire does not survive. A subject type is a vocabulary this module legitimately depends on —
 * {@code integration} declares {@code project} — and it is the key {@link ReviewSubjects} builds
 * its index on. Reading it as a {@code String} and parsing it here would be the same coupling with
 * the compiler switched off.
 *
 * @param assigneeUserId who was asked, or null when a whole team was. Exactly one of this and
 *                       {@link #assigneeTeamId()} is set on every row the product writes, and the
 *                       renderer has two arms for that reason and no third
 */
record ReviewRequestedPayload(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID assigneeUserId,
        UUID assigneeTeamId,
        UUID requestedBy) {
}
