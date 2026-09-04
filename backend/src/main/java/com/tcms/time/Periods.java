package com.tcms.time;

import java.time.LocalDate;

/**
 * 016 D-a — the half-month cadence, derived rather than configured.
 *
 * <p>{@code 1-15} and {@code 16-end of month}, which is what both timesheet mockups show. There is
 * no cadence setting because no mockup has a control that sets one, and a configurable cadence would
 * be a settings surface with no screen, a column nothing writes and a migration for generality
 * nobody asked for. A period <em>row</em> is created lazily by the first read or write into it; the
 * bounds themselves are arithmetic and cannot disagree between two clients.
 */
final class Periods {

    static PeriodBounds containing(LocalDate date) {
        return date.getDayOfMonth() <= 15
                ? new PeriodBounds(date.withDayOfMonth(1), date.withDayOfMonth(15))
                : new PeriodBounds(date.withDayOfMonth(16), date.withDayOfMonth(date.lengthOfMonth()));
    }

    private Periods() {
    }
}
