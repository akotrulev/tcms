package com.tcms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a peer asked for changes.
 *
 * <p>The twin of {@link ReviewEndorsedNotifier}; see its header for why the two verdicts are two
 * event types and one payload record.
 *
 * <p><strong>Advisory.</strong> A changes-requested verdict does not move the artifact off
 * {@code IN_REVIEW} ({@code project.ReviewParticipation.Verdict}), and the message says so by
 * asking for changes rather than announcing a rejection — a channel that read it as one would
 * report work as sent back that is still in review and still has an open request against it.
 */
@Component
class ReviewChangesRequestedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewChangesRequestedNotifier(
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
        return OutboxEvents.REVIEW_CHANGES_REQUESTED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ReviewVerdictPayload payload = ReviewVerdicts.parse(
                json, message.payload(), OutboxEvents.REVIEW_CHANGES_REQUESTED);

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_CHANGES_REQUESTED,
                payload.eventId(),
                null,
                NotificationTexts.reviewChangesRequested(
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
