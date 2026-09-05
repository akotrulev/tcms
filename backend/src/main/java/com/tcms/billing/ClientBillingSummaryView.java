package com.tcms.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code client.html}'s two tiles and the table under them — <strong>one path serving two readers
 * with different columns</strong> (019 D-i).
 *
 * <p>Gated on {@code client:read} and not on a money key, so an <strong>Account Manager</strong>
 * reads it in full: they own the client relationship, they see the client and the hours, and they
 * never see a rate. Finance reads the same path and gets the amounts.
 *
 * <h2>{@link #amountsVisible} is a field, because a null that means two things is a lie</h2>
 *
 * <p>It says whether this caller holds {@code billing:read}, evaluated <strong>server-side</strong>,
 * once. Without it, {@code amount: null} on a row would mean either <em>there is no invoice for this
 * period</em> or <em>this is none of your business</em>, and a renderer cannot tell them apart — so
 * it would either draw an empty column at an Account Manager, which {@code client.html} explicitly
 * does not, or hide the column whenever a single row happened to be unbilled.
 *
 * <p>The frontend never decides this from its own permission cache. A client computing visibility
 * locally would be one stale {@code /me/permissions} away from rendering a column of nulls and
 * calling it money.
 *
 * <p>{@link #invoiceCount} counts <strong>issued</strong> invoices, which is why it reads correctly
 * beside {@link #lastIssuedAt}: a draft is a working document and not an invoice anybody has raised.
 * Drafts still appear in {@link #rows}, because the question a row answers is <em>what is happening
 * to this month's hours</em>.
 */
public record ClientBillingSummaryView(
        boolean amountsVisible,
        BigDecimal approvedHoursToDate,
        BigDecimal approvedHoursThisQuarter,
        int invoiceCount,
        Instant lastIssuedAt,
        List<ClientBillingRowView> rows) {
}
