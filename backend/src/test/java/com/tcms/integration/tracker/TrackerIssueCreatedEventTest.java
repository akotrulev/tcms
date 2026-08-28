package com.tcms.integration.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * <strong>The producer half of the payload pin</strong> for
 * {@code integration.tracker_issue_created}.
 *
 * <p>{@code com.tcms.execution.TrackerIssueCreatedPayloadTest} is the other half. Neither module may import the other, so the payload
 * crosses as a JSON document and the compiler is structurally unable to check that the two records
 * agree — {@code Outbox#record}'s standing debt, paid the way {@code RunCompletedPayloadTest} pays
 * it on both sides.
 *
 * <p><strong>What the two halves cannot catch:</strong> a rename made on both sides together
 * reddens neither. That is what {@code ExecutionEndpointTest} is for — it drives a real producer
 * through the real dispatcher into the handler, so "the shape changed here", "the shape changed
 * there" and "the two no longer meet" are three different failures.
 *
 * <p>The document is written out by hand rather than serialized from the record under test, because
 * a document built from that record proves only that Jackson is reflexive.
 */
@DisplayName("integration.tracker_issue_created — the producer pin")
class TrackerIssueCreatedEventTest {

    /**
     * The event as it appears in {@code outbox.payload}.
     *
     * <p>A <em>wire format</em>: rows written before a rename are still in the table when the code
     * expecting the new name deploys, so renaming a component here is a data migration.
     */
    private static final String DOCUMENT = """
            {
              "eventId": "db000000-0000-4000-8000-000000000001",
              "companyId": "db000000-0000-4000-8000-000000000002",
              "projectId": "db000000-0000-4000-8000-000000000003",
              "executionId": "db000000-0000-4000-8000-000000000004",
              "bindingId": "db000000-0000-4000-8000-000000000005",
              "provider": "JIRA_CLOUD",
              "externalId": "10042",
              "externalKey": "PAY-991",
              "url": "https://acme.atlassian.net/browse/PAY-991",
              "createdBy": "db000000-0000-4000-8000-000000000006"
            }
            """;

    /** Built the way Boot builds the injected one — see {@code RunCompletedPayloadTest}. */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("every field lands in the record, with its value")
    void theWholeDocumentIsRead() throws Exception {
        TrackerIssueCreatedEvent payload = json.readValue(DOCUMENT, TrackerIssueCreatedEvent.class);

        assertThat(payload.eventId())
                .isEqualTo(UUID.fromString("db000000-0000-4000-8000-000000000001"));
        assertThat(payload.companyId())
                .isEqualTo(UUID.fromString("db000000-0000-4000-8000-000000000002"));
        assertThat(payload.projectId())
                .isEqualTo(UUID.fromString("db000000-0000-4000-8000-000000000003"));
        assertThat(payload.executionId())
                .isEqualTo(UUID.fromString("db000000-0000-4000-8000-000000000004"));
        assertThat(payload.bindingId())
                .isEqualTo(UUID.fromString("db000000-0000-4000-8000-000000000005"));
        assertThat(payload.provider())
                .as("IN the payload because it is in defect_link_execution_key_idx — the handler's "
                        + "row and the client's row must agree on it or one defect renders twice")
                .isEqualTo("JIRA_CLOUD");
        assertThat(payload.externalId()).isEqualTo("10042");
        assertThat(payload.externalKey()).isEqualTo("PAY-991");
        assertThat(payload.url()).isEqualTo("https://acme.atlassian.net/browse/PAY-991");
        assertThat(payload.createdBy())
                .isEqualTo(UUID.fromString("db000000-0000-4000-8000-000000000006"));
    }

    @Test
    @DisplayName("the record declares these components and no others")
    void theShapeIsPinnedFromBothEnds() {
        // The assertion above would still pass if somebody ADDED a component: an absent field
        // deserializes to null without complaint, and the backstop would then write a defect_link
        // with a null provider that collides with nothing.
        assertThat(Arrays.stream(TrackerIssueCreatedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("eventId", "companyId", "projectId", "executionId", "bindingId",
                        "provider", "externalId", "externalKey", "url", "createdBy");
    }

    @Test
    @DisplayName("a null executionId parses — an issue filed from nowhere in particular")
    void aNullExecutionParses() throws Exception {
        // Not a defensive case: TrackerIssueCreateInput.executionId is nullable by contract, and
        // the handler settles such an event as delivered rather than retrying it into a DEAD row
        // that would mean "this event was fine".
        String detached = DOCUMENT.replace(
                "\"executionId\": \"db000000-0000-4000-8000-000000000004\"",
                "\"executionId\": null");

        assertThat(json.readValue(detached, TrackerIssueCreatedEvent.class).executionId()).isNull();
    }

    @Test
    @DisplayName("a field the producer adds later is ignored rather than fatal")
    void anUnknownFieldIsTolerated() throws Exception {
        // Deliberate asymmetry with the component pin above: a producer may add a field before
        // every consumer wants it, and failing on it would make an additive change a breaking one.
        String widened = DOCUMENT.replace(
                "\"provider\": \"JIRA_CLOUD\"",
                "\"provider\": \"JIRA_CLOUD\", \"reporterEmail\": \"x@y.test\"");

        assertThat(json.readValue(widened, TrackerIssueCreatedEvent.class).provider()).isEqualTo("JIRA_CLOUD");
    }
}
