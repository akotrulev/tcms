package com.tcms.work;

import java.util.UUID;

/**
 * {@code work.item_assigned}, as this module writes it.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ItemAssignedPayload} is the other.
 * See {@code execution.RunCompletedEvent}'s header for why the two cannot be one type and what
 * keeps them honest — the same argument, one module over.
 *
 * <h2>One producer, because there is one assignment</h2>
 *
 * <p>{@link WorkItem} has exactly one mutator and it moves a card between columns. Its own header
 * records why: editing a task's title, description, due date or assignee "has no affordance
 * anywhere in the SPA", and an entity that permitted it would model a capability the product does
 * not have. So creation is where a task acquires an assignee, and creation is the only place this
 * event is recorded — which is also why the payload carries no previous assignee.
 *
 * <p>The name stays correct when the field edit lands: that slice adds a second call site here,
 * not a second event type.
 *
 * @param eventId    minted per occurrence, and the consumer's idempotency key against at-least-once
 *                   delivery. Not the outbox row's id, which no handler is given.
 * @param title      the task's own words. A stored task has no display key — {@code work_item} has
 *                   no such column, and a derived card borrows the key of the thing it mirrors —
 *                   so this is the only handle a reader has on it.
 * @param assigneeId the person, never their name: what somebody is called is a display decision
 *                   that plan §6u makes per company membership, and a name frozen into a payload
 *                   would be the one they had when the task was created.
 */
record ItemAssignedEvent(UUID eventId, UUID itemId, String title, UUID projectId, UUID assigneeId) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for this event. */
    static final String AGGREGATE = "work_item";
}
