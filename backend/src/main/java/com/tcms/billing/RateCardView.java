package com.tcms.billing;

import com.tcms.api.ClientRef;
import java.util.List;
import java.util.UUID;

/**
 * A rate card on the wire — one per client.
 *
 * <p>The {@link #currency} lives here rather than on an invoice, which is what makes an invoice's
 * currency a property of its client rather than a field on a form (019 D-d).
 *
 * <p>A client with no card is not an error and is not this record: it is a
 * {@link RateCardSlotView} whose card is {@code null}. Its approved hours are still recorded, they
 * just cannot be priced, and they land in the {@code NO_RATE} exclusion bucket, counted and named.
 */
public record RateCardView(UUID id, ClientRef client, String currency,
        List<RateCardEntryView> entries) {
}
