package com.tcms.integration;

import java.util.UUID;

/**
 * {@code execution.run_completed}, as this module reads it.
 *
 * <h2>This is the consumer half of a pair, and the pair is the point</h2>
 *
 * <p>{@code execution.RunCompletedEvent} is the producer's declaration of the same shape.
 * <strong>They are two records on purpose and cannot be one:</strong> plan §3 forbids this module
 * from importing anything of {@code execution}'s, so the payload crosses as a JSON document and
 * the compiler is structurally unable to check that the two agree.
 *
 * <p>{@code Outbox.record}'s javadoc has named that debt since §131 and stated what the first real
 * caller owes: a test on each side pinning the shape, because a test is what replaces the compiler
 * here. Both exist — {@code RunCompletedPayloadTest} in {@code execution} and in
 * {@code integration} — and a third, {@code NotificationDeliveryTest}, drives producer to handler
 * through the real outbox, which is the one that still fails when somebody updates both pins
 * together.
 *
 * <h2>Everything a notice says is in here, because nothing else is reachable</h2>
 *
 * <p>The handler may not read {@code test_run}: {@code execution} is not a dependency of this
 * module and never may be. So the payload is not a pointer to a row, it is the message — which is
 * also why {@code Outbox#recordCoalesced} was the wrong arm for this event, and why the two
 * pre-existing event types could get away with carrying nothing.
 *
 * @param eventId the occurrence's own id, minted by the producer. It is the delivery log's
 *                idempotency key: outbox delivery is at least once, and this is what lets a
 *                handler that already posted tell that it did. Not the outbox row's id — a
 *                handler is deliberately given no row identity.
 * @param runId   which run. Carried even though nothing here can look it up, because a notice
 *                naming an id is one a person can paste into a search.
 * @param runKey  the display key, {@code RUN-14}. What a message actually shows.
 * @param status  {@code COMPLETED}. Present rather than assumed: the name of the event says which
 *                move it was, and a subscriber that later wants aborted runs too gets a field
 *                rather than a second event type.
 * @param total   every execution in the run, so a message can say "12 of 15" — the counts do not
 *                sum to it, because a run holds executions with no verdict yet.
 */
record RunCompletedPayload(
        UUID eventId,
        UUID runId,
        String runKey,
        String runName,
        UUID projectId,
        String status,
        int passed,
        int failed,
        int blocked,
        int total) {
}
