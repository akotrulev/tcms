package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxMessage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * <strong>The wiring between a payload and the message, which neither pin observes.</strong>
 *
 * <h2>Why this class exists, stated as the hole it fills</h2>
 *
 * <p>{@link ReviewPayloadTest} proves the document parses. {@link ReviewRenderingTest} proves that
 * <em>given</em> a subject and two names the copy is right — it hands them in directly.
 * {@link ReviewNotificationTest} drives the real path but asserts {@code outbox} and
 * {@code notification_delivery} rows, because its webhook is unreachable by design and no rendered
 * message ever reaches anywhere a test can read.
 *
 * <p>So nothing between them asserts that a handler <strong>asks the right question and passes the
 * answer on</strong>: a notifier that resolved the assignee's id through the <em>team</em>
 * directory, or ignored {@link ReviewSubjects} and rendered a null subject, would be green in all
 * three. Both are one-line mistakes and both ship a message naming nobody and nothing.
 *
 * <p>No Spring context — §150's pure lane. The collaborators are this package's own types, so they
 * mock without a container.
 *
 * <p>Ids are unique to this class — {@code bc…}.
 */
@DisplayName("Review notifiers — the question each one asks, and what it does with the answer")
class ReviewNotifierTest {

    private static final UUID EVENT = UUID.fromString("bc000000-0000-4000-8000-000000000001");
    private static final UUID REVIEW = UUID.fromString("bc000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("bc000000-0000-4000-8000-000000000003");
    private static final UUID SUBJECT = UUID.fromString("bc000000-0000-4000-8000-000000000004");
    private static final UUID PERSON = UUID.fromString("bc000000-0000-4000-8000-000000000005");
    private static final UUID TEAM = UUID.fromString("bc000000-0000-4000-8000-000000000006");
    private static final UUID ASKER = UUID.fromString("bc000000-0000-4000-8000-000000000007");

    private static final ReviewSubjectDirectory.Subject PLAN_7 =
            new ReviewSubjectDirectory.Subject("PLAN-7", "Release 2.4");

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final NotificationProjects projects = mock(NotificationProjects.class);
    private final ReviewSubjects subjects = mock(ReviewSubjects.class);
    private final ReviewNotices notices = mock(ReviewNotices.class);
    private final ObjectMapper json = new ObjectMapper();

    private final ReviewRequestedNotifier requested =
            new ReviewRequestedNotifier(dispatch, projects, subjects, notices, json);
    private final ReviewApprovedNotifier approved =
            new ReviewApprovedNotifier(dispatch, projects, subjects, notices, json);

    @Test
    @DisplayName("a person's request resolves the PERSON, and the subject reaches the message")
    void thePersonArmAsksThePeopleDirectory() throws Exception {
        when(projects.nameOf(PROJECT)).thenReturn("Online Banking");
        when(subjects.describe(ReviewSubjectType.PLAN, PROJECT, SUBJECT))
                .thenReturn(Optional.of(PLAN_7));
        when(notices.nameOf(PERSON)).thenReturn("P. Peer");
        when(notices.nameOf(ASKER)).thenReturn("L. Lead");

        requested.handle(message(OutboxEvents.REVIEW_REQUESTED, new ReviewRequestedPayload(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.PLAN, SUBJECT, PERSON, null, ASKER)));

        RenderedNotification sent = captureDispatched(OutboxEvents.REVIEW_REQUESTED);
        assertThat(sent.title()).isEqualTo("Review requested from P. Peer");
        assertThat(sent.asPlainText())
                .as("""
                        The port's whole purpose, asserted where it is actually used: a handler \
                        that ignored ReviewSubjects would render "a test plan that has since been \
                        deleted" and every other test in this slice would stay green.""")
                .contains("PLAN-7 — Release 2.4");
    }

    @Test
    @DisplayName("a team's request resolves the TEAM — not the team id through the people lookup")
    void theTeamArmAsksTheTeamDirectory() throws Exception {
        when(projects.nameOf(PROJECT)).thenReturn("Online Banking");
        when(subjects.describe(any(), any(), any())).thenReturn(Optional.of(PLAN_7));
        when(notices.teamNameOf(TEAM)).thenReturn("Payments QA");
        when(notices.nameOf(ASKER)).thenReturn("L. Lead");

        requested.handle(message(OutboxEvents.REVIEW_REQUESTED, new ReviewRequestedPayload(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.PLAN, SUBJECT, null, TEAM, ASKER)));

        verify(notices).teamNameOf(TEAM);
        assertThat(captureDispatched(OutboxEvents.REVIEW_REQUESTED).title())
                .as("""
                        A team id put through nameOf resolves to nobody, and the message then says \
                        the review was requested from "someone who is no longer on the instance" — \
                        which is both false and unfixable by the person reading it.""")
                .isEqualTo("Review requested from Payments QA");
    }

    @Test
    @DisplayName("the occurrence's own id is what the delivery is keyed on, never a fresh one")
    void theEventIdIsCarriedThrough() throws Exception {
        when(projects.nameOf(any())).thenReturn("Online Banking");
        when(subjects.describe(any(), any(), any())).thenReturn(Optional.of(PLAN_7));

        approved.handle(message(OutboxEvents.REVIEW_APPROVED, new ReviewApprovedPayload(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.PLAN, SUBJECT, 2, PERSON, null)));

        verify(dispatch).dispatch(
                eq(PROJECT),
                eq(OutboxEvents.REVIEW_APPROVED),
                eq(EVENT),
                // Null rather than a threshold: a review carries no verdict counts, and null
                // matches every subscription rather than none.
                eq(null),
                any(RenderedNotification.class));
    }

    @Test
    @DisplayName("an unreadable document throws rather than settling the row as delivered")
    void aBadShapeIsNotSwallowed() {
        assertThatThrownBy(() -> approved.handle(new OutboxMessage(
                UUID.randomUUID(), "review", REVIEW, OutboxEvents.REVIEW_APPROVED, "{ not json")))
                .as("a handler that returned normally would mark the row SENT — a claim with no "
                        + "evidence behind it, and invisible by construction")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ReviewApprovedPayload");
    }

    private RenderedNotification captureDispatched(String eventType) {
        ArgumentCaptor<RenderedNotification> captor =
                ArgumentCaptor.forClass(RenderedNotification.class);
        verify(dispatch).dispatch(any(), eq(eventType), any(), any(), captor.capture());
        return captor.getValue();
    }

    private OutboxMessage message(String eventType, Object payload) throws Exception {
        return new OutboxMessage(
                UUID.randomUUID(),
                "review",
                REVIEW,
                eventType,
                json.writeValueAsString(payload));
    }
}
