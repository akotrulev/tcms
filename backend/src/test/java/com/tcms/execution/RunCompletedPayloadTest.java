package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * <strong>The producer half of the payload pin.</strong>
 *
 * <p>{@code com.tcms.integration.RunCompletedPayloadTest} is the other, and its header carries the
 * argument for the pair: plan §3 forbids that module from importing anything of this one's, so the
 * payload crosses as a JSON document and the compiler cannot compare the two declarations.
 * {@code Outbox#record} named this as the debt the first real caller of the payload arm would
 * inherit, and it went unpaid from §131 until the notifications slice because every event type the
 * product had was coalesced and carried {@code "{}"}.
 *
 * <p>What this half asserts is what {@code outbox.payload} actually contains: the field names, in
 * one list, and the values that reach them from a {@link TestRunView}. What it cannot assert is
 * that anybody on the other side reads them — that is the consumer pin — or that the two still
 * meet, which is {@code com.tcms.integration.NotificationDeliveryTest}.
 *
 * <h2>No database and no context</h2>
 *
 * <p>The subject is a serialization. Whether {@code RunStatusService} calls it, inside the right
 * transaction, on the right status, is a question about the service and is answered where that is
 * driven for real.
 */
@DisplayName("execution.run_completed — the shape this module writes (the producer pin)")
class RunCompletedPayloadTest {

    private static final UUID EVENT = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID RUN = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("a1000000-0000-4000-8000-000000000003");

    /** Built as Boot builds the injected one — {@code Outbox} serializes with that mapper. */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("the document carries exactly these field names — renaming one is a migration")
    void theFieldNamesArePinned() throws Exception {
        JsonNode document = json.valueToTree(RunCompletedEvent.of(EVENT, PROJECT, view()));

        List<String> names = new ArrayList<>();
        for (Iterator<String> fields = document.fieldNames(); fields.hasNext(); ) {
            names.add(fields.next());
        }

        assertThat(names)
                .as("outbox.payload is a wire format: rows written before a rename are still in "
                        + "the table when the consumer that expects the new name deploys")
                .containsExactlyInAnyOrder("eventId", "runId", "runKey", "runName", "projectId",
                        "status", "passed", "failed", "blocked", "total");
    }

    @Test
    @DisplayName("each value comes from the run the caller closed")
    void theValuesAreTheRuns() throws Exception {
        JsonNode document = json.valueToTree(RunCompletedEvent.of(EVENT, PROJECT, view()));

        assertThat(document.get("eventId").asText()).isEqualTo(EVENT.toString());
        assertThat(document.get("runId").asText()).isEqualTo(RUN.toString());
        assertThat(document.get("runKey").asText()).isEqualTo("RUN-14");
        assertThat(document.get("runName").asText()).isEqualTo("Regression 4.3");
        assertThat(document.get("projectId").asText()).isEqualTo(PROJECT.toString());
        assertThat(document.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(document.get("passed").asInt()).isEqualTo(9);
        assertThat(document.get("failed").asInt()).isEqualTo(3);
        assertThat(document.get("blocked").asInt()).isEqualTo(1);
        assertThat(document.get("total").asInt())
                .as("total is every execution, not the sum of the verdicts — a run holds "
                        + "executions nobody has judged yet")
                .isEqualTo(15);
    }

    @Test
    @DisplayName("nothing in the document could be a credential")
    void thePayloadCarriesNoSecret() throws Exception {
        // OutboxEntry#getPayload's rule, checked rather than trusted: this is a row at rest that an
        // operator browses and a backup copies. §131 refused the invitation email as a producer on
        // exactly this ground, and the notifications slice keeps its webhook URLs out for the same
        // one — the payload names the event, and the handler looks the channel up.
        String document = json.writeValueAsString(RunCompletedEvent.of(EVENT, PROJECT, view()));

        assertThat(document).doesNotContain("http").doesNotContain("token").doesNotContain("secret");
    }

    private static TestRunView view() {
        return new TestRunView(
                RUN, "RUN-14", "Regression 4.3", null, null, null, RunStatus.COMPLETED,
                // createdBy null: a hand-built fixture, and the shape a SCHEDULED run is in.
                "4.3", null, null, null, RunSource.MANUAL, null, null, null,
                new ResultCountsView(9, 3, 1, 0, 2, 15),
                // No recorded request: this fixture is a view built by hand, which is what a run
                // created before `V61` looks like on the wire (B7).
                null);
    }
}
