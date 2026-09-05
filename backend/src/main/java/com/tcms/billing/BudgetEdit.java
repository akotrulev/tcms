package com.tcms.billing;

import java.math.BigDecimal;

/**
 * A validated ceiling on its way into {@link Budget}.
 *
 * <p>The same device as {@code crm.ClientEdit}: the entity takes a record whose every field has
 * already been checked, so "what a caller may omit" stays a property of the request record and never
 * becomes a nullable column with two meanings.
 */
record BudgetEdit(BudgetBasis basis, BigDecimal amount, String currency) {
}
