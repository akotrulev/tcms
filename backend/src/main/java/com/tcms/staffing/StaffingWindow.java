package com.tcms.staffing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The date range a staffing read answers over, with the defaults and the ceiling in one place.
 *
 * <p>The default is <strong>the first of this month to three months on</strong>, which is what
 * {@code staffing.html}'s "Next 3 months" range shows. It is computed from the injected
 * {@link Clock} rather than {@code LocalDate.now()}: nothing in this codebase reads the wall clock
 * directly, and {@code ArchitectureRulesTest.nothingCallsNowDirectly} is why that stays true.
 *
 * <h2>The ceiling, and why it is a clamp rather than a refusal</h2>
 *
 * <p>{@link Capacity} walks one day at a time per person, so the cost of a read is
 * <em>days × people</em>. A caller asking for a century would walk 36,500 days for each member of
 * the company. Only {@code GET /staffing/capacity/me} declares a {@code 400}, so the other three
 * operations have no honest way to refuse one — and inventing a 400 they do not declare is a
 * response the generated types say cannot happen. The clamp is the answer that stays inside the
 * contract: a year is longer than any screen asks for, and a caller that wanted more gets the first
 * year of it rather than a timeout.
 */
record StaffingWindow(LocalDate from, LocalDate to) {

    /** A year and a day, so a whole calendar year requested inclusively is not shortened by one. */
    private static final int MAX_DAYS = 366;

    static StaffingWindow of(LocalDate from, LocalDate to, Clock clock) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(3) : to;
        return new StaffingWindow(start, capped(start, end));
    }

    /** True when the caller asked for a range that runs backwards — the only 400 this shape has. */
    static boolean isBackwards(LocalDate from, LocalDate to) {
        return from != null && to != null && to.isBefore(from);
    }

    private static LocalDate capped(LocalDate start, LocalDate end) {
        LocalDate ceiling = start.plusDays(MAX_DAYS);
        return end.isAfter(ceiling) ? ceiling : end;
    }
}
