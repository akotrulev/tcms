package com.tcms.tracker;

import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Feeds {@link IssueFactProjection} from {@code tracker.issues_changed}.
 *
 * <p>{@code reporting.FactRefreshOnResultsChanged}'s shape exactly, and for the same reason: the
 * event is a <em>state</em> that moved, the handler is a whole-company recompute that takes no
 * arguments, and {@link OutboxMessage} is therefore read for nothing. Reading a payload here would
 * be the beginning of a second, drifting copy of the issue shape — and there is no payload to read,
 * because {@code Outbox.recordCoalesced} refuses to carry one.
 *
 * <p>The delivery is at-least-once and the projection is an upsert, so a repeat costs a repeated
 * statement rather than a wrong answer.
 *
 * <p>Named for the event rather than for the plan's {@code IssueFactOnIssueChanged}: the singular
 * would name an occurrence, and {@link OutboxEvents#ISSUES_CHANGED} deliberately is not one.
 */
@Component
class IssueFactOnIssuesChanged implements OutboxHandler {

    private final IssueFactProjection projection;

    IssueFactOnIssuesChanged(IssueFactProjection projection) {
        this.projection = projection;
    }

    @Override
    public String eventType() {
        return OutboxEvents.ISSUES_CHANGED;
    }

    @Override
    public void handle(OutboxMessage message) {
        projection.refresh();
    }
}
