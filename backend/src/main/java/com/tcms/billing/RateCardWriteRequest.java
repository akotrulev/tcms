package com.tcms.billing;

import java.util.List;
import java.util.UUID;

/**
 * The body of {@code POST /rate-cards} and {@code PUT /rate-cards/{rateCardId}}.
 *
 * <p><strong>The {@code PUT} is a full replacement including the entries</strong> — the entries sent
 * are the entries the card then has, and one omitted is one removed. {@code PUT /clients/{clientId}}
 * is the same shape for the same reason: the screen shows and submits the whole card.
 *
 * <p>{@link #clientId} is <strong>ignored on the {@code PUT}</strong>. A card does not move between
 * clients, and moving one would silently reprice every draft on both sides;
 * {@code RateCard.clientId} is {@code updatable = false} so the mapping cannot be talked into it
 * either.
 */
public record RateCardWriteRequest(UUID clientId, String currency,
        List<RateCardEntryInput> entries) {
}
