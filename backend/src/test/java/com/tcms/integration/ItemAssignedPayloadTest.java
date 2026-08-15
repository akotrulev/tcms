package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The consumer half of the second payload pin — {@link RunCompletedPayloadTest}'s shape exactly,
 * one event over, and its header carries the argument for both.
 *
 * <p>{@code com.tcms.work.ItemAssignedPayloadTest} is the producer half.
 */
@DisplayName("work.item_assigned — the shape this module requires (the consumer pin)")
class ItemAssignedPayloadTest {

    private static final String DOCUMENT = """
            {
              "eventId": "a2000000-0000-4000-8000-000000000001",
              "itemId": "a2000000-0000-4000-8000-000000000002",
              "title": "Write the migration",
              "projectId": "a2000000-0000-4000-8000-000000000003",
              "assigneeId": "a2000000-0000-4000-8000-000000000004"
            }
            """;

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("every field the producer writes lands in the record, with its value")
    void theWholeDocumentIsRead() throws Exception {
        ItemAssignedPayload payload = json.readValue(DOCUMENT, ItemAssignedPayload.class);

        assertThat(payload.eventId())
                .isEqualTo(UUID.fromString("a2000000-0000-4000-8000-000000000001"));
        assertThat(payload.itemId())
                .isEqualTo(UUID.fromString("a2000000-0000-4000-8000-000000000002"));
        assertThat(payload.title()).isEqualTo("Write the migration");
        assertThat(payload.projectId())
                .isEqualTo(UUID.fromString("a2000000-0000-4000-8000-000000000003"));
        assertThat(payload.assigneeId())
                .isEqualTo(UUID.fromString("a2000000-0000-4000-8000-000000000004"));
    }

    @Test
    @DisplayName("the record declares these components and no others — no previous assignee")
    void theShapeIsPinnedFromBothEnds() {
        // The absent component is the interesting one. The plan named a previousAssigneeId and
        // there is no reassignment to have one from: work.WorkItem has a single mutator and it
        // moves a card between columns. A field null on every row the product can produce is the
        // writerless shape, and here a renderer would branch on it into unreachable code.
        assertThat(Arrays.stream(ItemAssignedPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("eventId", "itemId", "title", "projectId", "assigneeId");
    }
}
