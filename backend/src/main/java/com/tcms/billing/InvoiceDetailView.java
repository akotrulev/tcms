package com.tcms.billing;

import com.tcms.project.ClientContactRef;
import java.util.List;

/**
 * {@code invoice.html} in one document — the header, the lines, the entries behind them, what was
 * excluded, and the budgets this invoice moves. Nothing here is paged: the screen renders all of it
 * together.
 *
 * <p><strong>{@link #lines} and {@link #entries} are a query for a draft and rows for an issued
 * invoice</strong> (019 D-c), and {@link #excluded} is a live statement in both cases — it says
 * which hours in this period are on <em>no</em> invoice, which is a fact about now rather than about
 * when the document was sent. An empty array means every approved hour in the period is on a line;
 * it never means the excluded hours were not looked for.
 *
 * <p>{@link #billTo} is {@code null} when the client records no contact, and that is a state and not
 * a blocker: issuing sends nothing, so there is nothing an absent address prevents. It arrives
 * through {@link com.tcms.project.ClientDirectory}, because this module may not import {@code crm}
 * (019 D-m, D-u).
 *
 * <p>{@link #budgetEffect} carries the budget of each project this invoice actually has a line on,
 * as it stands — {@code invoice.html}'s <em>Budget effect</em> panel, which draws
 * <em>Acme Storefront 418 / 600h</em> beside <em>Acme Loyalty €19 200 / €40 000</em>. Empty when
 * none of those projects has a budget, which is an ordinary state and draws no panel.
 */
public record InvoiceDetailView(
        InvoiceView invoice,
        ClientContactRef billTo,
        int termsDays,
        List<InvoiceLineView> lines,
        List<InvoiceEntryView> entries,
        List<ExcludedHoursView> excluded,
        List<BudgetView> budgetEffect) {
}
