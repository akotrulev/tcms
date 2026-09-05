package com.tcms.billing;

import java.math.BigDecimal;

/**
 * <strong>The project-scope view, and it has no amount member and no currency member at all</strong>
 * (019 D-h).
 *
 * <p>Gated on {@code project:read}, and safe for exactly that reason: there is nothing on it to
 * withhold. A {@code MONEY}-basis budget reaches this endpoint as {@link #burnPercent} alone —
 * {@link #budgetHours} is {@code null} for one, because the ceiling is an amount and the amount is
 * Finance's.
 *
 * <p>{@code project-overview.html} draws it under the hint <em>"hours, no rates"</em> with the copy
 * <em>"A Project Manager sees the ceiling and the burn. What an hour costs is Finance's, and is not
 * on this page."</em>
 *
 * <p><strong>Two different nulls, and the difference is the whole record.</strong> A {@code null}
 * {@link #basis} means the project has no budget, which is a state and not an error. A {@code null}
 * {@link #burnPercent} on a {@code MONEY} budget means the burn is <em>unknown</em> because the
 * project's client has no rate card — a zero there would read as <em>nothing spent</em> on a project
 * that has spent a quarter.
 *
 * <p>{@code BudgetBurnTest} scans this record's serialized body for the five money words. That test
 * is a Test Engineer's whole view of this module, so it asserts over the bytes rather than over the
 * components: the failure it guards against is a field reaching the wire — a member renamed on the
 * way out, or a nested view added below the ones named here.
 */
public record BudgetBurnView(
        BudgetBasis basis,
        BigDecimal budgetHours,
        BigDecimal approvedHours,
        BigDecimal burnPercent) {
}
