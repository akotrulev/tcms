package com.tcms.tracker;

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
 * The producer half of the second payload pin — {@code com.tcms.execution.RunCompletedPayloadTest}'s
 * shape exactly, one module over, and that file's header carries the argument for both.
 *
 * <p>{@code com.tcms.integration.ItemAssignedPayloadTest} is the consumer half.
 *
 * <p><strong>It moved from {@code com.tcms.work} with its producer</strong> (011 T4): the record it
 * pins is {@code tracker.IssueAssignedEvent} now, because {@code POST /work-items} is gone and the
 * row that acquires an assignee is an issue. The FIELD NAMES did not move, which is the whole point
 * of a pin — a consumer compiled against the old five is still correct.
 */
@DisplayName("work.item_assigned — the shape this module writes (the producer pin)")
class IssueAssignedPayloadTest {

    private static final UUID EVENT = UUID.fromString("2f000000-0000-4000-8000-000000000001");
    private static final UUID ITEM = UUID.fromString("2f000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("2f000000-0000-4000-8000-000000000003");
    private static final UUID ASSIGNEE = UUID.fromString("2f000000-0000-4000-8000-000000000004");

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("the document carries exactly these field names — and no assignee's NAME")
    void theFieldNamesArePinned() {
        JsonNode document = json.valueToTree(
                new IssueAssignedEvent(EVENT, ITEM, "Write the migration", PROJECT, ASSIGNEE));

        List<String> names = new ArrayList<>();
        for (Iterator<String> fields = document.fieldNames(); fields.hasNext(); ) {
            names.add(fields.next());
        }

        assertThat(names)
                .as("what somebody is called is a display decision plan §6u makes per company "
                        + "membership, and a name frozen into a payload is the one they had when "
                        + "the work was assigned")
                .containsExactlyInAnyOrder("eventId", "itemId", "title", "projectId", "assigneeId");
    }

    @Test
    @DisplayName("each value is the issue's")
    void theValuesAreTheIssues() {
        JsonNode document = json.valueToTree(
                new IssueAssignedEvent(EVENT, ITEM, "Write the migration", PROJECT, ASSIGNEE));

        assertThat(document.get("eventId").asText()).isEqualTo(EVENT.toString());
        assertThat(document.get("itemId").asText()).isEqualTo(ITEM.toString());
        assertThat(document.get("title").asText()).isEqualTo("Write the migration");
        assertThat(document.get("projectId").asText()).isEqualTo(PROJECT.toString());
        assertThat(document.get("assigneeId").asText()).isEqualTo(ASSIGNEE.toString());
    }
}
