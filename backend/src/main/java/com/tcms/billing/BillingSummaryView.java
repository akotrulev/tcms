package com.tcms.billing;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code billing.html}'s four tiles.
 *
 * <h2>Two of them are arrays, and that is 019 D-l</h2>
 *
 * <p>{@link #invoicedYearToDate} and {@link #draftableNow} carry <strong>one entry per
 * currency</strong>. The mockup draws a single {@code €52.4k} tile while its own rate-card table
 * carries a EUR client and a CHF client two rows apart — a single-currency render of a
 * multi-currency company — and one number summed across two currencies is wrong in both of them.
 * Recorded as a disagreement rather than resolved silently, per plan 009's precedence rule: on what
 * a screen shows the mockup wins, on what the system does the plan wins, and <em>what is the
 * company's invoiced total</em> is a question about the system. An empty array means nothing has
 * been issued this year, which is the state a new company is in.
 *
 * <p>{@link #unapprovedHours} is a plain number, because hours have no currency. The mockup labels
 * it <em>excluded from every draft</em>, which is exactly what it is: the {@code NOT_APPROVED}
 * bucket of {@link ExcludedHoursView}, summed over the whole company for the same window.
 */
public record BillingSummaryView(
        List<Money> invoicedYearToDate,
        List<Money> draftableNow,
        BigDecimal unapprovedHours,
        int clientsBilled) {
}
