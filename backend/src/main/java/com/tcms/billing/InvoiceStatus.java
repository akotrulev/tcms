package com.tcms.billing;

/**
 * The two states an invoice has, and <strong>they are two different shapes</strong> (019 D-c).
 *
 * <p>A {@link #DRAFT} is a header — client, period, terms — and stores <em>no</em> lines: its lines
 * are computed on every read from the approved entries that exist now, which is what makes
 * {@code invoice.html}'s <em>"Approve them and this draft recalculates"</em> true with no recalculate
 * endpoint, no staleness flag, and no window in which a draft disagrees with the timesheets behind
 * it. An {@link #ISSUED} invoice's lines are {@code invoice_line} rows written once, by one
 * transaction, carrying the rate <em>as applied</em> as a number.
 *
 * <p>{@code ck_invoice_issued} holds the difference in the database: a {@code DRAFT} has no
 * {@code seq}, no {@code number}, no {@code issued_at} and no {@code total_amount}, and an
 * {@code ISSUED} invoice has all four. So this is not a label on a row — it is which of two row
 * shapes that row is.
 *
 * <p>There is no {@code SENT}, {@code PAID} or {@code VOID} member and none is planned (019 D-q):
 * issuing sends nothing — <em>"sending is your mail client's job"</em> — and nothing in this product
 * records that an invoice was paid.
 */
public enum InvoiceStatus {

    /** A header whose lines are a query. Discardable; carries no number. */
    DRAFT,

    /** A record. Its lines are rows, and no operation on this surface updates one. */
    ISSUED
}
