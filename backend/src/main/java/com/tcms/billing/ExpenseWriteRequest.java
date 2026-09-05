package com.tcms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /expenses}.
 *
 * <p>The amount and the currency arrive as two fields rather than as a {@link Money}, which is the
 * contract's shape and is the right one for an input: a caller sending {@code {"amount": …}} with no
 * currency should meet a field error on {@code currency}, not a half-built nested object. What comes
 * back is a {@link Money}, because by then both halves are known.
 */
public record ExpenseWriteRequest(
        UUID projectId,
        LocalDate incurredOn,
        String description,
        BigDecimal amount,
        String currency) {
}
