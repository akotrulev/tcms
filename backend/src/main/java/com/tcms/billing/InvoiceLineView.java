package com.tcms.billing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One project, one matched rung of the rate ladder, and the hours at it — {@code invoice.html}'s
 * <em>Project · Who / role · Approved h · Rate · Amount</em> row.
 *
 * <p><strong>The same record serves both invoice states, and that is 019 D-c's whole point.</strong>
 * For a {@code DRAFT} it is computed on every read and stored nowhere; for an {@code ISSUED} invoice
 * it is an {@code invoice_line} row. A reader cannot tell them apart from the shape and does not
 * need to: what differs is that the second never moves again.
 *
 * <p>{@link #rate} is the rate <strong>as applied</strong> — a number, not a reference into
 * {@code rate_card_entry} resolved at read time. A card doubled next month must leave last month's
 * invoice exactly as it was sent, and a reference would do precisely the opposite.
 *
 * <p>{@link #appliesTo} is {@link EntryLabels}' resolved string — {@code Mira Sund}, {@code Test
 * Lead}, {@code All roles} — frozen into the row at issue so it still reads correctly after the
 * person leaves and the role is renamed. There is no person id or role id here: those stay on the
 * row for grouping, and they are the copies that may go stale.
 */
public record InvoiceLineView(
        UUID projectId,
        String projectName,
        String appliesTo,
        RateCardScope scope,
        BigDecimal hours,
        BigDecimal rate,
        Money amount) {
}
