package com.tcms.tracker;

import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Refreshes this company's mirror of the links from its issues into documentation.
 *
 * <p>Second claimant of {@code documentation.links_changed}, beside
 * {@code repository.HealthRecomputeOnLinksChanged}. A shared event with two handlers is what the
 * outbox is for, and it is why the name is a <em>state</em> recorded through
 * {@link com.tcms.tenancy.Outbox#recordCoalesced} rather than an occurrence: several link edits
 * collapse into one delivery, and both handlers rebuild from current facts either way.
 *
 * <p><strong>No new outbox name was minted for this.</strong> The event already fires whenever a link
 * or a linked page moves, and an event whose only difference from an existing one is which module
 * listens is a second name for one fact — which is how two consumers come to disagree about when
 * something happened.
 *
 * <p><strong>Replaced whole per company</strong>, exactly as {@code case_doc_link} is. Rebuilding
 * rather than patching is what makes at-least-once delivery cost a repeated statement instead of a
 * wrong answer — and it is the only way to notice a link that was <em>removed</em>, which a per-row
 * upsert cannot see.
 *
 * <p>{@link OutboxMessage} is read for nothing, and there is nothing in it to read: {@code
 * recordCoalesced} refuses to carry a payload. Reading one here would be the beginning of a second,
 * drifting copy of what a link is.
 */
@Component
class DocLinkRefreshOnLinksChanged implements OutboxHandler {

    private final DocLinkProjection projection;

    DocLinkRefreshOnLinksChanged(DocLinkProjection projection) {
        this.projection = projection;
    }

    @Override
    public String eventType() {
        return OutboxEvents.DOC_LINKS_CHANGED;
    }

    @Override
    public void handle(OutboxMessage message) {
        projection.refresh();
    }
}
