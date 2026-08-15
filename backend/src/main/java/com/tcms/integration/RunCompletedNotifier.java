package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a run finished.
 *
 * <h2>The first handler in the product to read an outbox payload</h2>
 *
 * <p>Every event type before this one is coalesced and carries {@code "{}"}, so
 * {@code OutboxMessage#payload} has never been parsed outside a test. The parse is the boundary
 * plan §3 forbids the compiler to see across: {@link RunCompletedPayload} is this module's
 * declaration of a shape {@code execution} declares separately, and a test on each side is what
 * replaces the type check.
 *
 * <h2>Idempotent, and which write is keyed on what</h2>
 *
 * <p>Delivery is at least once, so this method runs again for an event it already delivered. The
 * one durable write it makes is a {@code notification_delivery} row keyed on
 * {@code (company_id, channel_id, event_id)} — where {@code event_id} is minted by the producer
 * and carried in the payload — and {@link NotificationLedger#beginAttempt} refuses a second
 * attempt against a row that is already {@code SENT}. Nothing else here writes anything, so a
 * repeat run of a fully delivered event is a handful of reads.
 *
 * <p><strong>A partial fan-out is the interesting case and it is covered by the same key.</strong>
 * Three channels where the second one's webhook is dead: the first is {@code SENT}, the second and
 * third are attempted, the handler throws, and the retry skips the first. Without the key the
 * retry would post to the first channel again on every one of the outbox's eight attempts.
 */
@Component
class RunCompletedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ObjectMapper json;

    RunCompletedNotifier(
            NotificationDispatchService dispatch,
            NotificationProjects projects,
            ObjectMapper json) {

        this.dispatch = dispatch;
        this.projects = projects;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.RUN_COMPLETED;
    }

    @Override
    public void handle(OutboxMessage message) {
        RunCompletedPayload payload = parse(message.payload());
        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.RUN_COMPLETED,
                payload.eventId(),
                // The threshold's subject: a subscription with min_failures = 3 wants this event
                // only when three results failed. Passed as a number rather than as a boolean so
                // the comparison stays with the subscription that owns it.
                payload.failed(),
                NotificationTexts.runCompleted(payload, projects.nameOf(payload.projectId())));
    }

    /**
     * <p>Throwing rather than logging and returning: an unparseable payload is not something a
     * retry fixes, and marking the row {@code SENT} for it would be the "claim with no evidence
     * behind it" {@code OutboxHandler} names. The outbox exhausts it into a visible terminal state
     * instead, which is the outcome that gets looked at.
     */
    private RunCompletedPayload parse(String payload) {
        try {
            return json.readValue(payload, RunCompletedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "An " + OutboxEvents.RUN_COMPLETED + " payload could not be read as the shape "
                            + "this module declares — see RunCompletedPayload", unreadable);
        }
    }
}
