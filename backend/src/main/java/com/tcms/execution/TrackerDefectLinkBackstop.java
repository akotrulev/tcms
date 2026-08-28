package com.tcms.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the {@code defect_link} for an issue that was filed in a tracker, if nobody else did.
 *
 * <h2>It is a backstop and it is not the path</h2>
 *
 * <p>The SPA files the issue ({@code POST …/tracker/issues}) and then writes the link
 * ({@code POST …/executions/{id}/defects}), in two calls, because {@code execution} may not import
 * {@code integration} and the create therefore cannot write this table. That seam is forced by the
 * module boundary rather than chosen, and the ordinary path is the client's two calls.
 *
 * <p>This handler covers the crash between them: the browser closed, the network dropped, the
 * process died. It writes the same row from the payload and needs <strong>no credential at all</strong>,
 * which is what makes it survive the per-user inversion — it works even if the filer's token is
 * revoked one second after the issue was created.
 *
 * <h2>Idempotent, and which write is keyed on what</h2>
 *
 * <p>Delivery is at least once, so this runs again for an event it already handled. Its one durable
 * write is a {@code defect_link} row, and the lookup names exactly the four columns of
 * {@code defect_link_execution_key_idx} — {@code (company_id, execution_id, provider, external_key)}
 * — so a repeat is a read. <strong>The same key is what makes the client's row and this row the same
 * row</strong>: both derive {@code provider} from the binding, so whichever arrives second finds the
 * first and writes nothing. A client that omits its {@code bindingId} breaks that, deliberately
 * visibly, and {@code ExecutionEndpointTest} asserts both halves.
 *
 * <h2>A null {@code executionId} is a delivery, not a failure</h2>
 *
 * <p>An issue filed from nowhere in particular has no execution to link to. Throwing would retry it
 * eight times and then leave a {@code DEAD} row that means "this event was fine". Returning settles
 * it, and the ledger row in {@code tracker_issue_creation} is where an operator finds the issue
 * itself.
 *
 * <h2>What this still does not give you</h2>
 *
 * <p>An issue created in a tracker for an execution the event cannot name — a payload defect, a
 * dead-lettered event. {@code OutboxStatus.DEAD} is where that lands, visibly, and
 * {@code tracker_issue_creation} is how you find the orphan. Stated rather than solved.
 */
@Component
class TrackerDefectLinkBackstop implements OutboxHandler {

    private final DefectLinkRepository links;
    private final TestExecutionRepository executions;
    private final ObjectMapper json;
    private final java.time.Clock clock;

    TrackerDefectLinkBackstop(
            DefectLinkRepository links,
            TestExecutionRepository executions,
            ObjectMapper json,
            java.time.Clock clock) {

        this.links = links;
        this.executions = executions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String eventType() {
        return OutboxEvents.TRACKER_ISSUE_CREATED;
    }

    @Override
    @Transactional
    public void handle(OutboxMessage message) {
        TrackerIssueCreatedPayload payload = parse(message.payload());
        if (payload.executionId() == null) {
            return;
        }
        // The execution may have been deleted with its run since the issue was filed. Nothing to
        // link to is a delivery for the same reason a null id is: retrying cannot make it exist.
        if (executions.findById(payload.executionId()).isEmpty()) {
            return;
        }

        Optional<DefectLink> existing = links.findByExecutionIdAndProviderAndExternalKey(
                payload.executionId(), payload.provider(), payload.externalKey());
        if (existing.isPresent()) {
            return;
        }

        links.saveAndFlush(DefectLink.to(
                CompanyContext.require(),
                payload.executionId(),
                payload.provider(),
                payload.externalKey(),
                payload.url(),
                payload.createdBy(),
                clock.instant()));
    }

    /**
     * <p>Throwing rather than logging and returning: an unparseable payload is not something a retry
     * fixes, and marking the row {@code SENT} for it would be the "claim with no evidence behind it"
     * {@code OutboxHandler} names. The outbox exhausts it into a visible terminal state instead.
     */
    private TrackerIssueCreatedPayload parse(String payload) {
        try {
            return json.readValue(payload, TrackerIssueCreatedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.TRACKER_ISSUE_CREATED + " payload could not be read: "
                            + unreadable.getOriginalMessage(), unreadable);
        }
    }
}
