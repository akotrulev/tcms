package com.tcms.billing;

import java.math.BigDecimal;

/**
 * The body of {@code PUT /projects/{projectId}/budget}.
 *
 * <p>A {@code MONEY} basis <strong>requires</strong> a currency and an {@code HOURS} basis
 * <strong>forbids</strong> one. {@code ck_budget_shape} holds the same rule in the database, so the
 * application cannot drift from it — and {@link BudgetService} checks it first so the caller gets a
 * field error naming {@code currency} rather than a constraint violation.
 *
 * <p>The target state, not a verb: sending a budget for a project that already has one replaces it,
 * exactly as {@code PUT /projects/{projectId}/client} does one path over.
 */
public record BudgetWriteRequest(BudgetBasis basis, BigDecimal amount, String currency) {
}
