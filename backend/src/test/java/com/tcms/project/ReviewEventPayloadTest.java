package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * <strong>The producer half of the three review payload pins.</strong>
 *
 * <p>{@code com.tcms.integration.ReviewPayloadTest} is the other, and
 * {@code com.tcms.integration.ReviewNotificationTest} is the third leg — the only one that fails
 * when a field is renamed on both sides at once. {@code execution.RunCompletedPayloadTest}'s header
 * carries the argument for the arrangement.
 *
 * <p>No database and no context: the subject is a serialization. Whether {@link ReviewService}
 * calls it, in the right transaction, on the right verdict, is answered where that is driven for
 * real.
 *
 * <p>Ids are unique to this class — {@code b7…}.
 */
@DisplayName("review.* — the shapes this module writes (the producer pin)")
class ReviewEventPayloadTest {

    private static final UUID EVENT = UUID.fromString("b7000000-0000-4000-8000-000000000001");
    private static final UUID REVIEW = UUID.fromString("b7000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("b7000000-0000-4000-8000-000000000003");
    private static final UUID SUBJECT = UUID.fromString("b7000000-0000-4000-8000-000000000004");
    private static final UUID PERSON = UUID.fromString("b7000000-0000-4000-8000-000000000005");
    private static final UUID OTHER = UUID.fromString("b7000000-0000-4000-8000-000000000006");
    private static final UUID TEAM = UUID.fromString("b7000000-0000-4000-8000-000000000007");

    /** Built as Boot builds the injected one — {@code Outbox} serializes with that mapper. */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("review.requested carries exactly these fields, the subject type as its name")
    void theRequestIsPinned() {
        JsonNode document = json.valueToTree(new ReviewRequestedEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, PERSON, null, OTHER));

        assertThat(fieldNames(document))
                .as("outbox.payload is a wire format: rows written before a rename are still in "
                        + "the table when the consumer that expects the new name deploys")
                .containsExactlyInAnyOrder("eventId", "reviewId", "projectId", "subjectType",
                        "subjectId", "assigneeUserId", "assigneeTeamId", "requestedBy");
        assertThat(document.get("subjectType").asText())
                .as("the enum crosses as its name — the consumer indexes its subject directories "
                        + "on it, so the spelling is load-bearing")
                .isEqualTo("CASE");
        assertThat(document.get("assigneeUserId").asText()).isEqualTo(PERSON.toString());
        assertThat(document.get("assigneeTeamId").isNull())
                .as("exactly one of the two is set — ReviewAssigneeEdit refuses both and neither")
                .isTrue();
        assertThat(document.get("requestedBy").asText()).isEqualTo(OTHER.toString());
    }

    @Test
    @DisplayName("a team request is the other arm — the team id set and the user id null")
    void aTeamRequestIsTheOtherArm() {
        JsonNode document = json.valueToTree(new ReviewRequestedEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, null, TEAM, OTHER));

        assertThat(document.get("assigneeUserId").isNull()).isTrue();
        assertThat(document.get("assigneeTeamId").asText()).isEqualTo(TEAM.toString());
    }

    @Test
    @DisplayName("review verdicts carry no verdict field — the event name is the verdict")
    void theVerdictIsPinned() {
        JsonNode document = json.valueToTree(new ReviewVerdictEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.SUITE, SUBJECT, PERSON, OTHER,
                "The third step does not say what to assert"));

        assertThat(fieldNames(document))
                .as("a verdict field beside the event name would be the same fact twice, and the "
                        + "copy in the payload is the one that can disagree with the name")
                .containsExactlyInAnyOrder("eventId", "reviewId", "projectId", "subjectType",
                        "subjectId", "reviewerId", "submittedBy", "comment");
        assertThat(document.get("comment").asText())
                .isEqualTo("The third step does not say what to assert");
        assertThat(document.get("submittedBy").asText()).isEqualTo(OTHER.toString());
    }

    @Test
    @DisplayName("an unattributable review serializes a null submitter rather than dropping it")
    void theSubmitterMayBeAbsent() {
        JsonNode document = json.valueToTree(new ReviewVerdictEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, PERSON, null, null));

        assertThat(document.get("submittedBy").isNull())
                .as("review.created_by permits null and ReviewService#refuses already reasons "
                        + "about it — the consumer must be able to tell 'nobody to address' from "
                        + "'field not sent'")
                .isTrue();
        assertThat(document.get("comment").isNull()).isTrue();
    }

    @Test
    @DisplayName("review.approved carries the version somebody was asked to read")
    void theApprovalIsPinned() {
        JsonNode document = json.valueToTree(new ReviewApprovedEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.PLAN, SUBJECT, 3, PERSON, OTHER));

        assertThat(fieldNames(document))
                .containsExactlyInAnyOrder("eventId", "reviewId", "projectId", "subjectType",
                        "subjectId", "subjectVersionNo", "approvedBy", "submittedBy");
        assertThat(document.get("subjectVersionNo").asInt())
                .as("stamped at submission and never re-stamped, so a sign-off notice names the "
                        + "version that was read rather than whatever the artifact says now")
                .isEqualTo(3);
        assertThat(document.get("approvedBy").asText()).isEqualTo(PERSON.toString());
    }

    @Test
    @DisplayName("nothing in any of the three documents could be a credential")
    void thePayloadsCarryNoSecret() throws Exception {
        // OutboxEntry#getPayload's rule, checked rather than trusted: these are rows at rest that
        // an operator browses and a backup copies. The payload names the event; the handler looks
        // the channel — and its webhook URL — up.
        List<Object> events = List.of(
                new ReviewRequestedEvent(EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT,
                        PERSON, null, OTHER),
                new ReviewVerdictEvent(EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT,
                        PERSON, OTHER, "no"),
                new ReviewApprovedEvent(EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, 1,
                        PERSON, OTHER));

        for (Object event : events) {
            assertThat(json.writeValueAsString(event).toLowerCase(Locale.ROOT))
                    .as("%s", event.getClass().getSimpleName())
                    .doesNotContain("secret")
                    .doesNotContain("token")
                    .doesNotContain("http");
        }
    }

    private static List<String> fieldNames(JsonNode document) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> fields = document.fieldNames(); fields.hasNext(); ) {
            names.add(fields.next());
        }
        return names;
    }
}
