package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic every capacity surface in the product depends on, tested without a container.
 *
 * <p>Pure JUnit — no Spring context, no database, no clock. That is the whole reason
 * {@link Capacity} is a function of three lists and a range rather than a method on a service:
 * "an absence zeroes capacity everywhere" is a claim about four screens, and the only way to make
 * it true rather than to check it four times is for one function to decide what the hours are.
 */
@DisplayName("Capacity — the one function four surfaces read")
class CapacityTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

    @Test
    @DisplayName("a 40-hour week over five working days is 40 hours")
    void fullWeek() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI, List.of(), List.of());

        assertThat(window.capacityHours()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a weekend contributes nothing, so Mon-Sun is still 40")
    void weekendIsNotCapacity() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, MON.plusDays(6), List.of(), List.of());

        assertThat(window.capacityHours()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a whole day of absence removes that day's eight hours")
    void absenceZeroesADay() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(), List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("1.00"))));

        assertThat(window.capacityHours()).isEqualByComparingTo("32.00");
        assertThat(window.absenceHours()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("a half day removes half of it")
    void halfDayAbsence() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(), List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("0.50"))));

        assertThat(window.capacityHours()).isEqualByComparingTo("36.00");
    }

    @Test
    @DisplayName("two overlapping allocations totalling 101 are the peak, and the flag")
    void overAllocationIsTheDailyPeak() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(
                        new Capacity.AllocatedSpan(MON, FRI, 50),
                        new Capacity.AllocatedSpan(MON, FRI, 51)),
                List.of());

        assertThat(window.peakPercent()).isEqualTo(101);
        assertThat(window.overAllocated()).isTrue();
        assertThat(window.peakOn()).isEqualTo(MON);
    }

    @Test
    @DisplayName("consecutive allocations are NOT summed — 100% then 100% is a peak of 100")
    void consecutiveIsNotOverAllocated() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(
                        new Capacity.AllocatedSpan(MON, MON.plusDays(1), 100),
                        new Capacity.AllocatedSpan(MON.plusDays(2), FRI, 100)),
                List.of());

        assertThat(window.peakPercent()).isEqualTo(100);
        assertThat(window.overAllocated()).isFalse();
    }

    @Test
    @DisplayName("a fully absent day is excluded from the peak — booked with no capacity behind it is not over-allocation")
    void fullyAbsentDayIsNotAPeak() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, MON,
                List.of(new Capacity.AllocatedSpan(MON, MON, 120)),
                List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("1.00"))));

        assertThat(window.overAllocated()).isFalse();
        assertThat(window.peakPercent()).isZero();
    }

    @Test
    @DisplayName("freeFrom is the first day under 100%, and null when there is none")
    void freeFrom() {
        Capacity.Window full = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(new Capacity.AllocatedSpan(MON, FRI, 100)), List.of());
        assertThat(full.freeFrom()).isNull();

        Capacity.Window partial = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(new Capacity.AllocatedSpan(MON, MON.plusDays(1), 100)), List.of());
        assertThat(partial.freeFrom()).isEqualTo(MON.plusDays(2));
    }

    @Test
    @DisplayName("allocated capacity is the allocated share of the hours that survive absence")
    void allocatedCapacityIsNetOfAbsence() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(new Capacity.AllocatedSpan(MON, FRI, 50)),
                List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("1.00"))));

        // 32 hours of capacity, half of them allocated. Not 20 — an allocation cannot claim a day
        // the person is away, and utilisation denominates over this figure (017 D-i).
        assertThat(window.allocatedCapacityHours()).isEqualByComparingTo("16.00");
        assertThat(window.allocatedPercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("overlapping absences cap at a whole day rather than driving capacity negative")
    void overlappingAbsencesCap() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, MON,
                List.of(),
                List.of(
                        new Capacity.AbsentSpan(MON, MON, new BigDecimal("0.50")),
                        new Capacity.AbsentSpan(MON, MON, new BigDecimal("0.50")),
                        new Capacity.AbsentSpan(MON, MON, new BigDecimal("0.50"))));

        assertThat(window.capacityHours()).isEqualByComparingTo("0.00");
        assertThat(window.absenceHours()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("a window with no working day in it answers zero rather than dividing by one")
    void weekendOnlyWindow() {
        LocalDate saturday = MON.minusDays(2);
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), saturday, saturday.plusDays(1),
                List.of(new Capacity.AllocatedSpan(saturday, saturday.plusDays(1), 100)),
                List.of());

        assertThat(window.capacityHours()).isEqualByComparingTo("0.00");
        assertThat(window.allocatedPercent()).isZero();
        assertThat(window.peakPercent()).isZero();
        assertThat(window.freeFrom()).isNull();
    }
}
