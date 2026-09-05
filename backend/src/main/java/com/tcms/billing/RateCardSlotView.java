package com.tcms.billing;

import com.tcms.api.ClientRef;

/**
 * A client and its card, <strong>or a client and {@code null}</strong>.
 *
 * <p>{@code billing.html}'s Kessler Health row, which reads <em>no rate card yet</em> beside a
 * {@code Set} button. The absence is a row rather than a missing one, because that is the state
 * every new client is in — and a table that simply omitted them would give a Finance user no way to
 * reach the button that fixes it.
 *
 * <p>A company with no clients answers {@code []}, which is a complete answer and the owner's own
 * case (scope D1 rule 2).
 */
public record RateCardSlotView(ClientRef client, RateCardView card) {
}
