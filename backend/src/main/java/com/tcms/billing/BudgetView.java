package com.tcms.billing;

import com.tcms.api.ClientRef;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code billing.html}'s <em>Project budgets</em> table — <strong>the money view</strong>, gated on
 * {@code billing:read}.
 *
 * <p>The percentage-only view a Project Manager reads is {@link BudgetBurnView}, and the two are
 * separate schemas rather than one with fields withheld, because a schema whose meaning depends on
 * who asked is one no reader can check (019 D-h).
 *
 * <p>{@link #client} is {@code null} for an internal project and that is not a deficiency —
 * {@code billing.html} renders it as <em>None (internal)</em> with no warning tone (scope D1 rule 1,
 * 019 D-p). {@link #pricedToDate} is then {@code null} too: no client means no rate card, so the
 * money value of those hours is <em>unknown</em> rather than zero.
 *
 * <p>{@link #burnPercent} is <strong>not capped at 100</strong>. {@code billing.html} draws Bergstrom
 * Portal at 102% in a danger tone, and clamping it would hide the one row the screen exists to
 * surface.
 */
public record BudgetView(
        UUID projectId,
        String projectKey,
        String projectName,
        ClientRef client,
        BudgetBasis basis,
        BigDecimal amountHours,
        Money amountMoney,
        BigDecimal approvedHours,
        Money pricedToDate,
        BigDecimal burnPercent) {
}
