package com.tcms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry of a {@link RateCardWriteRequest}.
 *
 * <p>{@link #scope} decides which of the two ids is required and the other must be absent, matched
 * by {@code ck_rate_card_entry_shape} in the database: {@code PERSON} needs {@link #personId},
 * {@code ROLE} needs {@link #roleId}, {@code ALL_ROLES} needs neither.
 * {@link RateCardService} refuses the mismatch as a field error, so the caller is told which field
 * is wrong rather than meeting a constraint violation as a 500.
 */
public record RateCardEntryInput(
        RateCardScope scope,
        UUID personId,
        UUID roleId,
        BigDecimal hourlyRate,
        LocalDate effectiveFrom) {
}
