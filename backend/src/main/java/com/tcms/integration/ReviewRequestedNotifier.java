package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that somebody was asked to review an artifact.
 *
 * <p>{@link ItemAssignedNotifier}'s shape exactly, including its idempotency argument — the durable
 * write is a {@code notification_delivery} row keyed on {@code (company_id, channel_id, event_id)},
 * and nothing else here writes.
 *
 * <p><strong>Every name and the artifact itself are resolved here, not carried.</strong> See
 * {@link ReviewSubjects} and {@link ReviewNotices} for the reason, which is the one
 * {@link NotificationProjects} states: a value frozen into a payload is the one it had when the
 * occurrence was recorded, and a retry announces it.
 */
@Component
class ReviewRequestedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewRequestedNotifier(
            NotificationDispatchService dispatch,
            NotificationProjects projects,
            ReviewSubjects subjects,
            ReviewNotices notices,
            ObjectMapper json) {

        this.dispatch = dispatch;
        this.projects = projects;
        this.subjects = subjects;
        this.notices = notices;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.REVIEW_REQUESTED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ReviewRequestedPayload payload = parse(message.payload());

        // Exactly one of the two ids is set — ReviewAssigneeEdit refuses both and neither — so this
        // is one name and never a choice between two.
        String askedOf = payload.assigneeUserId() != null
                ? notices.nameOf(payload.assigneeUserId())
                : notices.teamNameOf(payload.assigneeTeamId());

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_REQUESTED,
                payload.eventId(),
                // No verdicts to threshold. Null matches every subscription rather than none — see
                // NotificationSubscription#wants, where a filter that silently silenced a channel
                // would be indistinguishable from a channel that never fires.
                null,
                NotificationTexts.reviewRequested(
                        payload,
                        projects.nameOf(payload.projectId()),
                        subjects.describe(
                                payload.subjectType(),
                                payload.projectId(),
                                payload.subjectId()).orElse(null),
                        askedOf,
                        notices.nameOf(payload.requestedBy())));
    }

    /** Throws for {@link RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    private ReviewRequestedPayload parse(String payload) {
        try {
            return json.readValue(payload, ReviewRequestedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.REVIEW_REQUESTED + " payload could not be read as the "
                            + "shape this module declares — see ReviewRequestedPayload", unreadable);
        }
    }
}
