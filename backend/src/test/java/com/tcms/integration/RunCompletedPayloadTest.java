package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * <strong>The consumer half of the payload pin.</strong>
 *
 * <h2>Why this test exists, in one sentence from a file it does not import</h2>
 *
 * <p>{@code OutboxMessage#payload} says it: the producing and consuming modules may not share a
 * class (plan §3), so the consumer parses the document into a record of its own, <em>"and the thing
 * that would keep them honest is a test on each side rather than the compiler"</em>.
 * {@code Outbox#record} has carried that as a debt since §131, naming it as what the first real
 * caller owes. This is one half of the payment; {@code com.tcms.execution.RunCompletedPayloadTest}
 * is the other.
 *
 * <h2>What the two halves can and cannot catch, stated so nobody over-trusts them</h2>
 *
 * <p>A rename on one side reddens that side's pin. <strong>A rename on both sides, updated
 * together, reddens neither</strong> — and that is not a hole in the design, it is the reason
 * {@code NotificationDeliveryTest} drives a real producer through the real dispatcher into this
 * handler. Three tests, three different failures: the shape changed here, the shape changed there,
 * and the two no longer meet.
 *
 * <p>The document below is written out in full rather than built from a record, because a document
 * built from the record under test proves only that Jackson is reflexive.
 */
@DisplayName("execution.run_completed — the shape this module requires (the consumer pin)")
class RunCompletedPayloadTest {

    /**
     * The event as it appears in {@code outbox.payload}.
     *
     * <p>Copied by hand, not generated. It is a <em>wire format</em>: rows written before a rename
     * are still in the table when the code that expects the new name deploys, so a component
     * renamed here is a data migration.
     */
    private static final String DOCUMENT = """
            {
              "eventId": "a1000000-0000-4000-8000-000000000001",
              "runId": "a1000000-0000-4000-8000-000000000002",
              "runKey": "RUN-14",
              "runName": "Regression 4.3",
              "projectId": "a1000000-0000-4000-8000-000000000003",
              "status": "COMPLETED",
              "passed": 9,
              "failed": 3,
              "blocked": 1,
              "total": 15
            }
            """;

    /**
     * Built the way Boot builds the injected one — <strong>not {@code new ObjectMapper()}</strong>,
     * which fails on an unknown property where the application's tolerates it. A pin that used a
     * differently-configured mapper would be asserting about a parse the product never performs.
     */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("every field the producer writes lands in the record, with its value")
    void theWholeDocumentIsRead() throws Exception {
        RunCompletedPayload payload = json.readValue(DOCUMENT, RunCompletedPayload.class);

        assertThat(payload.eventId())
                .isEqualTo(UUID.fromString("a1000000-0000-4000-8000-000000000001"));
        assertThat(payload.runId())
                .isEqualTo(UUID.fromString("a1000000-0000-4000-8000-000000000002"));
        assertThat(payload.runKey()).isEqualTo("RUN-14");
        assertThat(payload.runName()).isEqualTo("Regression 4.3");
        assertThat(payload.projectId())
                .isEqualTo(UUID.fromString("a1000000-0000-4000-8000-000000000003"));
        assertThat(payload.status()).isEqualTo("COMPLETED");
        assertThat(payload.passed()).isEqualTo(9);
        assertThat(payload.failed()).isEqualTo(3);
        assertThat(payload.blocked()).isEqualTo(1);
        assertThat(payload.total()).isEqualTo(15);
    }

    @Test
    @DisplayName("the record declares these components and no others")
    void theShapeIsPinnedFromBothEnds() {
        // The assertion above would still pass if somebody ADDED a component: an absent field
        // deserializes to null or zero without complaint, and a renderer would then say "0
        // failures" about a run that failed. This is the half that catches it.
        assertThat(Arrays.stream(RunCompletedPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("eventId", "runId", "runKey", "runName", "projectId", "status",
                        "passed", "failed", "blocked", "total");
    }

    @Test
    @DisplayName("a field the producer adds later is ignored rather than fatal")
    void anUnknownFieldIsTolerated() throws Exception {
        // Deliberate asymmetry with the rule above. A producer may add a field before every
        // consumer wants it, and failing on it would make an additive change a breaking one — but
        // a consumer that quietly stopped reading a field it needs is the failure this file is
        // for, which is why the component list is pinned and the document is not.
        String widened = DOCUMENT.replace("\"total\": 15", "\"total\": 15, \"skipped\": 2");

        assertThat(json.readValue(widened, RunCompletedPayload.class).total()).isEqualTo(15);
    }
}
