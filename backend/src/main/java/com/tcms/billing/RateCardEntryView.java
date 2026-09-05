package com.tcms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One rung on the wire — {@code billing.html}'s <em>Applies to · Rate · Effective</em> columns.
 *
 * <p>{@link #appliesTo} is <strong>resolved server-side</strong>: {@code Mira Sund}, {@code Test
 * Lead}, {@code All roles}. The client renders that string and never joins a person or a role to
 * build it, which is what keeps one label rule in one place — {@link EntryLabels} — rather than one
 * per screen.
 *
 * <p>{@link #personId} and {@link #roleId} stay on the wire beside it for grouping and for the edit
 * form, which has to re-select the person or role the entry names. They are the copies that may go
 * stale; {@link #appliesTo} is the copy that renders.
 */
public record RateCardEntryView(
        UUID id,
        RateCardScope scope,
        UUID personId,
        UUID roleId,
        BigDecimal hourlyRate,
        LocalDate effectiveFrom,
        String appliesTo) {
}
