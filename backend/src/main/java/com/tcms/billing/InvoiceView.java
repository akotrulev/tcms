package com.tcms.billing;

import com.tcms.api.ClientRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A draft or an issued invoice, as {@code billing.html}'s Invoices panel lists it — two issued and
 * one draft in one list, the draft outlined rather than filtered away.
 *
 * <p>{@link #number} is {@code null} while {@code DRAFT}; the mockup's panel reads <em>assigned on
 * issue</em> in that field, which is a statement rather than a gap. Once issued it is
 * {@code INV-2026-041} and it never changes: uniqueness and immutability are the whole contract, and
 * gap-free numbering is neither guaranteed nor required (019 D-k).
 *
 * <p>{@link #total} is the <strong>live</strong> figure for a draft — the sum of the lines this
 * second — and the <strong>stored</strong> {@code total_amount} for an issued invoice. That is 019
 * D-c seen from the list: the same field, computed on one row shape and frozen on the other.
 *
 * <p>{@link #approvedHours} counts the hours actually on lines. Excluded hours are not in it, and
 * they are not meant to be — {@link ExcludedHoursView} is where they are counted, and adding them
 * here would make the list's hours disagree with its own total.
 *
 * <p>{@link #currency} is the client's, copied from their rate card at draft time and frozen on the
 * row (019 D-d). It is not a field anybody chooses, which is what makes a cross-client invoice
 * structurally impossible rather than merely refused.
 */
public record InvoiceView(
        UUID id,
        String number,
        ClientRef client,
        LocalDate periodStart,
        LocalDate periodEnd,
        InvoiceStatus status,
        String currency,
        Money total,
        int lineCount,
        BigDecimal approvedHours,
        Instant issuedAt,
        String issuedBy) {
}
