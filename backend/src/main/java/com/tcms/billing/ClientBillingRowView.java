package com.tcms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code client.html}'s <em>Hours and invoices to date</em> table: a month, a project,
 * the approved hours on it, and the invoice those hours are on — if any.
 *
 * <p>{@link #amount} is <strong>always {@code null} when
 * {@link ClientBillingSummaryView#amountsVisible()} is false</strong>, and the flag is what makes
 * that {@code null} readable (019 D-i). Without it, an absent amount would mean either <em>no
 * invoice for this period</em> or <em>not your business</em>, which a renderer cannot tell apart.
 *
 * <p>{@link #invoiceNumber} is {@code null} for a draft — the mockup renders the word <em>Draft</em>
 * in that column, which {@link #invoiceStatus} is what makes possible. Both are {@code null} where
 * the month's hours are on no invoice at all, which is the ordinary state of the current month.
 */
public record ClientBillingRowView(
        String periodLabel,
        LocalDate periodStart,
        UUID projectId,
        String projectName,
        BigDecimal approvedHours,
        UUID invoiceId,
        String invoiceNumber,
        InvoiceStatus invoiceStatus,
        Money amount) {
}
