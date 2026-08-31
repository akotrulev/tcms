package com.tcms.tracker;

import java.util.UUID;

/**
 * {@code work.item_assigned}, as this module writes it.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ItemAssignedPayload} is the other. See
 * {@code execution.RunCompletedEvent}'s header for why the two cannot be one type and what keeps
 * them honest.
 *
 * <h2>The event name says {@code work} and the producer is here, and that is deliberate</h2>
 *
 * <p>~~{@code com.tcms.work.ItemAssignedEvent}~~ <strong>→ moved by 009 module M1.</strong> The
 * producer was {@code work.BoardService.create}, which created a {@code work_item}; that endpoint is
 * gone (D-a) and the row it made is an {@code issue} here. Deleting the producer instead would have
 * left {@code NotificationEventType}'s {@code work.item_assigned} declared on the contract,
 * subscribable in the channel picker, and <strong>fired by nothing</strong> — a channel that never
 * fires being the one outcome an administrator cannot debug, which is exactly what
 * {@code NotificationEventTypeTest}'s second rule exists to say.
 *
 * <p>The <em>name</em> did not move with it. Renaming it would be a contract change to
 * {@code NotificationEventType}, a migration over every {@code notification_subscription} row that
 * names it, and a silent gap for any channel not migrated — for a string an administrator reads once
 * in a picker. The field names did not move either, so the consumer half is untouched.
 *
 * <h2>Two producers now, because an issue can be reassigned</h2>
 *
 * <p>{@code WorkItem} had exactly one mutator and it moved a card between columns, so creation was
 * the only place a task acquired an assignee and its own header said the second call site would
 * arrive with the field edit. <strong>It has.</strong> {@link IssueService} records this on a create
 * that names somebody and on an edit that hands the work to somebody else — which is why the payload
 * still carries no previous assignee: what a watcher is being told is <em>this is yours now</em>,
 * and who had it before is the audit trail's question.
 *
 * @param eventId    minted per occurrence, and the consumer's idempotency key against at-least-once
 *                   delivery. Not the outbox row's id, which no handler is given.
 * @param itemId     the issue's id, not its key. The field name is the consumer's and predates the
 *                   issue; the value is what the consumer resolves.
 * @param title      the issue's own words.
 * @param assigneeId the person, never their name: what somebody is called is a display decision plan
 *                   §6u makes per company membership, and a name frozen into a payload would be the
 *                   one they had when the work was assigned.
 */
record IssueAssignedEvent(UUID eventId, UUID itemId, String title, UUID projectId, UUID assigneeId) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for this event. */
    static final String AGGREGATE = "issue";
}
