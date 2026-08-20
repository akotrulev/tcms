package com.tcms.project;

import java.util.UUID;

/**
 * {@code review.requested}, as this module writes it.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ReviewRequestedPayload} is the other.
 * See {@code execution.RunCompletedEvent}'s header for why the two cannot be one type and what
 * keeps them honest — the same argument, one module down.
 *
 * <h2>The assignee is one of two ids and never both</h2>
 *
 * <p>{@link ReviewAssigneeEdit} refuses a request naming neither and a request naming both, so
 * exactly one of {@link #assigneeUserId()} and {@link #assigneeTeamId()} is set on every row. That
 * is a validated invariant rather than a hopeful one, and the consumer's renderer therefore has two
 * reachable arms and no third.
 *
 * <h2>No names, and no subject key</h2>
 *
 * <p>What somebody is called is a display decision plan §6u makes per company membership, and an
 * artifact's title is edited — so both are resolved at delivery, through {@link PeopleDirectory} /
 * {@link TeamDirectory} and {@link ReviewSubjectDirectory}. A name frozen here is the one that was
 * true when the request was written, and a retry an hour later would announce it.
 *
 * @param eventId     minted per occurrence, and the consumer's idempotency key against
 *                    at-least-once delivery. Not the outbox row's id, which no handler is given
 * @param requestedBy who asked. Never null: making the request costs {@code *:review}, so it is
 *                    always an authenticated caller
 */
record ReviewRequestedEvent(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID assigneeUserId,
        UUID assigneeTeamId,
        UUID requestedBy) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for every {@code review.*} event. */
    static final String AGGREGATE = "review";
}
