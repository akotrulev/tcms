package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a task was assigned to somebody.
 *
 * <p>{@link RunCompletedNotifier}'s shape exactly, including its idempotency argument — the
 * durable write is a {@code notification_delivery} row keyed on
 * {@code (company_id, channel_id, event_id)}, and nothing else here writes.
 *
 * <h2>The name is resolved here rather than carried in the payload</h2>
 *
 * <p>{@code work} knows who the assignee is and could put their display name in the payload. It
 * must not, for two reasons that point the same way. A name is a <em>display</em> decision — plan
 * §6u makes it per-company-membership, so "what is this person called" has an answer that depends
 * on who is asking — and a name frozen into a payload is the name they had when the task was
 * created, which a retry three hours later would still be announcing.
 *
 * <p>{@link PeopleDirectory} is {@code project}'s port, and using it is what keeps this module off
 * {@code identity}: the arrow points at the foundation, and the question asked is <em>who is
 * this</em> and never <em>may they</em>.
 */
@Component
class ItemAssignedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final PeopleDirectory people;
    private final ObjectMapper json;

    ItemAssignedNotifier(
            NotificationDispatchService dispatch,
            NotificationProjects projects,
            PeopleDirectory people,
            ObjectMapper json) {

        this.dispatch = dispatch;
        this.projects = projects;
        this.people = people;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.ITEM_ASSIGNED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ItemAssignedPayload payload = parse(message.payload());
        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.ITEM_ASSIGNED,
                payload.eventId(),
                // No verdicts to threshold. Null matches every subscription rather than none —
                // see NotificationSubscription#wants, where a filter that silently silenced a
                // channel would be indistinguishable from a channel that never fires.
                null,
                NotificationTexts.itemAssigned(
                        payload,
                        projects.nameOf(payload.projectId()),
                        nameOf(payload.assigneeId())));
    }

    /**
     * @return the assignee's display name, or null when the id names no account — which is a state
     *         real rows are in, because a person can be off-boarded between the assignment and the
     *         delivery. {@link PeopleDirectory} yields no entry rather than inventing one, and the
     *         message then declines to name anybody
     */
    private String nameOf(UUID assigneeId) {
        Map<UUID, UserRef> resolved =
                people.resolve(CompanyContext.require(), List.of(assigneeId));
        UserRef person = resolved.get(assigneeId);
        return person == null ? null : person.displayName();
    }

    /** Throws for {@link RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    private ItemAssignedPayload parse(String payload) {
        try {
            return json.readValue(payload, ItemAssignedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.ITEM_ASSIGNED + " payload could not be read as the shape "
                            + "this module declares — see ItemAssignedPayload", unreadable);
        }
    }
}
