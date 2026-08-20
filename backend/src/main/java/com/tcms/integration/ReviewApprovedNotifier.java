package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a review was signed off.
 *
 * <p><strong>One message per artifact, including inside a bulk approval.</strong>
 * {@code OutboxEvents#REVIEW_APPROVED} carries the whole argument: a bulk operation moves up to 500
 * cases and records one occurrence for each, so a channel subscribed to this in a project that
 * bulk-approves will be told 500 times. Each is true and names a different subject; the honest fix
 * is a digest on the subscriber's side and it does not exist yet.
 *
 * <p>{@link ReviewRequestedNotifier}'s shape otherwise, including its resolution rules.
 */
@Component
class ReviewApprovedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewApprovedNotifier(
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
        return OutboxEvents.REVIEW_APPROVED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ReviewApprovedPayload payload = parse(message.payload());

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_APPROVED,
                payload.eventId(),
                null,
                NotificationTexts.reviewApproved(
                        payload,
                        projects.nameOf(payload.projectId()),
                        subjects.describe(
                                payload.subjectType(),
                                payload.projectId(),
                                payload.subjectId()).orElse(null),
                        notices.nameOf(payload.approvedBy()),
                        notices.nameOf(payload.submittedBy())));
    }

    /** Throws for {@link RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    private ReviewApprovedPayload parse(String payload) {
        try {
            return json.readValue(payload, ReviewApprovedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.REVIEW_APPROVED + " payload could not be read as the "
                            + "shape this module declares — see ReviewApprovedPayload", unreadable);
        }
    }
}
