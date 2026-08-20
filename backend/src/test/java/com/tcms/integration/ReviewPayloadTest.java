package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.ReviewSubjectType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The consumer half of the three review payload pins — {@link ItemAssignedPayloadTest}'s shape,
 * three events over, and its header carries the argument.
 *
 * <p>{@code com.tcms.project.ReviewEventPayloadTest} is the producer half.
 *
 * <p>The asymmetry is deliberate and is the one the other pins have: an <em>unknown</em> field is
 * tolerated, because a producer may widen before every consumer wants it, and an <em>absent</em>
 * one is not, because it deserializes to null or zero in silence and a renderer then announces a
 * sign-off "at v0".
 *
 * <p>Ids are unique to this class — {@code b8…}.
 */
@DisplayName("review.* — the shapes this module requires (the consumer pin)")
class ReviewPayloadTest {

    private static final String REQUESTED = """
            {
              "eventId": "b8000000-0000-4000-8000-000000000001",
              "reviewId": "b8000000-0000-4000-8000-000000000002",
              "projectId": "b8000000-0000-4000-8000-000000000003",
              "subjectType": "CASE",
              "subjectId": "b8000000-0000-4000-8000-000000000004",
              "assigneeUserId": "b8000000-0000-4000-8000-000000000005",
              "assigneeTeamId": null,
              "requestedBy": "b8000000-0000-4000-8000-000000000006"
            }
            """;

    private static final String VERDICT = """
            {
              "eventId": "b8000000-0000-4000-8000-000000000011",
              "reviewId": "b8000000-0000-4000-8000-000000000012",
              "projectId": "b8000000-0000-4000-8000-000000000013",
              "subjectType": "SUITE",
              "subjectId": "b8000000-0000-4000-8000-000000000014",
              "reviewerId": "b8000000-0000-4000-8000-000000000015",
              "submittedBy": null,
              "comment": "Step 3 asserts nothing"
            }
            """;

    private static final String APPROVED = """
            {
              "eventId": "b8000000-0000-4000-8000-000000000021",
              "reviewId": "b8000000-0000-4000-8000-000000000022",
              "projectId": "b8000000-0000-4000-8000-000000000023",
              "subjectType": "PLAN",
              "subjectId": "b8000000-0000-4000-8000-000000000024",
              "subjectVersionNo": 4,
              "approvedBy": "b8000000-0000-4000-8000-000000000025",
              "submittedBy": "b8000000-0000-4000-8000-000000000026"
            }
            """;

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("review.requested reads whole, with the subject type as an enum")
    void theRequestIsRead() throws Exception {
        ReviewRequestedPayload payload = json.readValue(REQUESTED, ReviewRequestedPayload.class);

        assertThat(payload.eventId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000001"));
        assertThat(payload.reviewId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000002"));
        assertThat(payload.projectId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000003"));
        assertThat(payload.subjectType())
                .as("the index this module keys its subject directories on is built on this value")
                .isEqualTo(ReviewSubjectType.CASE);
        assertThat(payload.assigneeUserId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000005"));
        assertThat(payload.assigneeTeamId())
                .as("exactly one of the two arms is set on every row the product writes")
                .isNull();
        assertThat(payload.requestedBy())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000006"));

        assertThat(components(ReviewRequestedPayload.class))
                .containsExactly("eventId", "reviewId", "projectId", "subjectType", "subjectId",
                        "assigneeUserId", "assigneeTeamId", "requestedBy");
    }

    @Test
    @DisplayName("a verdict reads whole, and a null submitter stays null rather than throwing")
    void theVerdictIsRead() throws Exception {
        ReviewVerdictPayload payload = json.readValue(VERDICT, ReviewVerdictPayload.class);

        assertThat(payload.subjectType()).isEqualTo(ReviewSubjectType.SUITE);
        assertThat(payload.reviewerId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000015"));
        assertThat(payload.submittedBy())
                .as("review.created_by is nullable and the renderer omits the fact rather than "
                        + "naming nobody")
                .isNull();
        assertThat(payload.comment()).isEqualTo("Step 3 asserts nothing");

        assertThat(components(ReviewVerdictPayload.class))
                .as("no verdict component — the event name is the verdict, and a second copy of it "
                        + "here is the one that can disagree")
                .containsExactly("eventId", "reviewId", "projectId", "subjectType", "subjectId",
                        "reviewerId", "submittedBy", "comment");
    }

    @Test
    @DisplayName("review.approved reads whole, version included")
    void theApprovalIsRead() throws Exception {
        ReviewApprovedPayload payload = json.readValue(APPROVED, ReviewApprovedPayload.class);

        assertThat(payload.subjectType()).isEqualTo(ReviewSubjectType.PLAN);
        assertThat(payload.subjectVersionNo())
                .as("an absent int deserializes to 0 in silence, and the message would then "
                        + "announce a sign-off at v0")
                .isEqualTo(4);
        assertThat(payload.approvedBy())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000025"));
        assertThat(payload.submittedBy())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000026"));

        assertThat(components(ReviewApprovedPayload.class))
                .containsExactly("eventId", "reviewId", "projectId", "subjectType", "subjectId",
                        "subjectVersionNo", "approvedBy", "submittedBy");
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
