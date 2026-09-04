package com.tcms.staffing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * <strong>The one place capacity is computed, and every surface that shows it reads this.</strong>
 *
 * <p>That is not tidiness — it is how story S1's third acceptance criterion is met at all. "An
 * absence zeroes capacity <em>everywhere</em>" is a claim about four screens (the company staffing
 * view, the directory, the timesheet's KPI and My Work's meter), and the only way to make it true
 * rather than to check it four times is for none of them to compute anything. They ask for hours;
 * this decides what hours are.
 *
 * <h2>Derived on every read, never stored (017 D-a)</h2>
 *
 * <p>A stored capacity is a cache with four invalidators — the profile changing, an absence
 * recorded, an absence deleted, and the calendar rolling forward — and the last cannot be hooked by
 * anything. A capacity table that silently ages is the two-contradictory-percentages failure with a
 * schema behind it.
 *
 * <h2>A working day is Monday to Friday, and there is no holiday calendar (017 D-c)</h2>
 *
 * <p>A public holiday is an {@link AbsentSpan} of kind {@code HOLIDAY}. That is per person, which
 * is what a product serving several client companies from one instance needs, and it is already the
 * thing that zeroes capacity everywhere.
 *
 * <h2>Over-allocation is a daily peak, not a monthly sum (017 D-e)</h2>
 *
 * <p>{@code staffing.html} draws monthly bars and totals somebody at 130% for October; that is a
 * month's-eye view of a daily truth. 100% for the first half of a month and 100% for the second is
 * not over-allocation, and a monthly sum would report it as 200%. So the walk is per day and the
 * flag is read off the highest single day.
 *
 * <p>A day the person is <em>fully</em> absent is excluded from the peak. Being booked at 100% on a
 * day you are on leave is not an over-allocation — it is an allocation with no capacity behind it,
 * and the screen says a different thing about each.
 *
 * <h2>Pure, and that is what makes it testable</h2>
 *
 * <p>No Spring, no repository, no {@code LocalDate.now()}. Every date arrives as a parameter, which
 * is the fifth of the untestable seams {@code be-java-unit-testing} names — {@code CapacityTest}
 * runs in milliseconds with no container behind it, and it is the only test in this module that can.
 */
final class Capacity {

    /** One allocation's claim, flattened to what the walk needs. */
    record AllocatedSpan(LocalDate startsOn, LocalDate endsOn, int percent) {
    }

    /** One absence, flattened. {@code fraction} is the share of each day that is away. */
    record AbsentSpan(LocalDate startsOn, LocalDate endsOn, BigDecimal fraction) {
    }

    /**
     * Everything the four surfaces ask about one person over one window.
     *
     * @param peakOn   the day {@code peakPercent} was reached, or null when nothing is allocated
     * @param freeFrom the first day under 100%, or null when they are full for the whole window
     */
    record Window(
            BigDecimal capacityHours,
            BigDecimal absenceHours,
            int allocatedPercent,
            BigDecimal allocatedCapacityHours,
            int peakPercent,
            LocalDate peakOn,
            boolean overAllocated,
            LocalDate freeFrom) {
    }

    private static final BigDecimal WORKING_DAYS_A_WEEK = new BigDecimal("5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int FULL = 100;

    static Window of(
            BigDecimal weeklyHours,
            LocalDate from,
            LocalDate to,
            List<AllocatedSpan> allocations,
            List<AbsentSpan> absences) {

        BigDecimal daily = weeklyHours.divide(WORKING_DAYS_A_WEEK, 4, RoundingMode.HALF_UP);

        BigDecimal capacity = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        BigDecimal allocatedCapacity = BigDecimal.ZERO;
        long percentDaySum = 0;
        long workingDays = 0;
        int peak = 0;
        LocalDate peakOn = null;
        LocalDate freeFrom = null;

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!isWorkingDay(day)) {
                continue;
            }
            workingDays++;

            BigDecimal absentShare = absentShareOn(day, absences);
            BigDecimal present = BigDecimal.ONE.subtract(absentShare).max(BigDecimal.ZERO);
            BigDecimal dayCapacity = daily.multiply(present);

            capacity = capacity.add(dayCapacity);
            lost = lost.add(daily.subtract(dayCapacity));

            int allocatedToday = allocatedPercentOn(day, allocations);
            percentDaySum += allocatedToday;
            allocatedCapacity = allocatedCapacity.add(
                    dayCapacity.multiply(BigDecimal.valueOf(allocatedToday))
                            .divide(HUNDRED, 4, RoundingMode.HALF_UP));

            // A day with no capacity behind it cannot be a peak — see the class comment.
            if (dayCapacity.signum() > 0 && allocatedToday > peak) {
                peak = allocatedToday;
                peakOn = day;
            }
            if (freeFrom == null && allocatedToday < FULL) {
                freeFrom = day;
            }
        }

        int meanPercent = workingDays == 0 ? 0 : Math.toIntExact(percentDaySum / workingDays);

        return new Window(
                capacity.setScale(2, RoundingMode.HALF_UP),
                lost.setScale(2, RoundingMode.HALF_UP),
                meanPercent,
                allocatedCapacity.setScale(2, RoundingMode.HALF_UP),
                peak,
                peakOn,
                peak > FULL,
                freeFrom);
    }

    private static boolean isWorkingDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /**
     * Overlapping absences add up and are capped at one whole day.
     *
     * <p>Two half-day rows on the same date is a person who recorded a morning and an afternoon
     * separately, and it means a whole day. Three of them still means a whole day, which is what
     * the cap is for — capacity may not go negative and take an hours total with it.
     */
    private static BigDecimal absentShareOn(LocalDate day, List<AbsentSpan> absences) {
        BigDecimal share = BigDecimal.ZERO;
        for (AbsentSpan absence : absences) {
            if (!day.isBefore(absence.startsOn()) && !day.isAfter(absence.endsOn())) {
                share = share.add(absence.fraction());
            }
        }
        return share.min(BigDecimal.ONE);
    }

    private static int allocatedPercentOn(LocalDate day, List<AllocatedSpan> allocations) {
        int total = 0;
        for (AllocatedSpan allocation : allocations) {
            if (!day.isBefore(allocation.startsOn()) && !day.isAfter(allocation.endsOn())) {
                total += allocation.percent();
            }
        }
        return total;
    }

    private Capacity() {
    }
}
