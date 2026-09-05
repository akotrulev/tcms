package com.tcms.billing;

/**
 * A ceiling in hours or in money.
 *
 * <p>{@code settings-general.html} draws {@code 600h}; {@code billing.html} draws both, one project
 * apart. Either way what a Project Manager reads is the burn and never the rate (019 D-h).
 *
 * <p>{@code ck_budget_shape} pairs this with the currency column in the database: {@code MONEY}
 * requires one and {@code HOURS} forbids one. A shape the database holds is one the application
 * cannot drift from, so {@link BudgetService} validates the same rule rather than relying on either
 * side alone — the service so the caller gets a field error, the constraint so no other writer can
 * get past it.
 */
public enum BudgetBasis {
    HOURS,
    MONEY
}
