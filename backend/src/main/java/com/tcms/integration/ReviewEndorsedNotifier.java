package com.tcms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a peer said an artifact looks good.
 *
 * <p><strong>Not the sign-off.</strong> {@code OutboxEvents#REVIEW_ENDORSED} carries the argument:
 * recording a peer verdict costs {@code *:review} and signing off costs {@code *:approve}, so this
 * message must not read as an approval. {@link ReviewApprovedNotifier} is that one.
 *
 * <p>The twin of {@link ReviewChangesRequestedNotifier}: one payload record, two event names, two
 * handlers — because {@link OutboxHandler#eventType()} claims exactly one name and a subscriber
 * picks between the two at the subscription rather than filtering.
 */
@Component
class ReviewEndorsedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewEndorsedNotifier(
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
        return OutboxEvents.REVIEW_ENDORSED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ReviewVerdictPayload payload = ReviewVerdicts.parse(
                json, message.payload(), OutboxEvents.REVIEW_ENDORSED);

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_ENDORSED,
                payload.eventId(),
                null,
                NotificationTexts.reviewEndorsed(
                        payload,
                        projects.nameOf(payload.projectId()),
                        subjects.describe(
                                payload.subjectType(),
                                payload.projectId(),
                                payload.subjectId()).orElse(null),
                        notices.nameOf(payload.reviewerId()),
                        notices.nameOf(payload.submittedBy())));
    }
}
