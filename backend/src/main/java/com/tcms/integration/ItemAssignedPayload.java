package com.tcms.integration;

import java.util.UUID;

/**
 * {@code work.item_assigned}, as this module reads it.
 *
 * <p>The consumer half of a pair; {@code work.ItemAssignedEvent} is the other. See
 * {@link RunCompletedPayload}'s header for why they are two records and what keeps them honest.
 *
 * <h2>No previous assignee, and that is a fact about the product rather than the payload</h2>
 *
 * <p>The plan named one. There is no reassignment to have a previous assignee <em>from</em>:
 * {@code work.WorkItem} has exactly one mutator, it moves a card between columns, and the entity's
 * own header records that editing a task's assignee "has no affordance anywhere in the SPA". A
 * field that is null on every row this product can produce is the writerless shape §116, §117,
 * §132 and §151 each found — and here it would be worse than usual, because a renderer would
 * legitimately branch on it and the branch would be unreachable.
 *
 * <p>It arrives with the field edit, which is the same slice that gives {@code work} its second
 * producer for this event type.
 *
 * <h2>No display key either, for a plainer reason: a task has none</h2>
 *
 * <p>{@code work_item} has no key column. A derived board card borrows the key of the thing it
 * mirrors, and a task mirrors nothing, so its title is the only handle a person has on it.
 *
 * @param eventId    the occurrence, and the delivery log's idempotency key — see
 *                   {@link RunCompletedPayload#eventId()}
 * @param assigneeId who it went to. Resolved to a name by the handler through
 *                   {@code project.PeopleDirectory}: the payload carries the id because a name is
 *                   a display decision and {@code work} is not the module that gets to make it.
 */
record ItemAssignedPayload(
        UUID eventId,
        UUID itemId,
        String title,
        UUID projectId,
        UUID assigneeId) {
}
