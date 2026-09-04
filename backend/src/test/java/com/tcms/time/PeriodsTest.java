package com.tcms.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 016 D-a's arithmetic, and nothing else — no Spring context, no database.
 *
 * <p>The cadence is the one thing in this module that is derived rather than stored, so it is the
 * one thing that can be wrong identically on both sides of the wire with nothing to compare against.
 */
@DisplayName("Periods — the half-month cadence (016 D-a)")
class PeriodsTest {

    @Test
    @DisplayName("the first half of the month runs to the fifteenth")
    void firstHalfOfTheMonthRunsToTheFifteenth() {
        PeriodBounds bounds = Periods.containing(LocalDate.of(2026, 9, 7));

        assertThat(bounds.startsOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(bounds.endsOn()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("the second half runs to the last day of the month, February included")
    void secondHalfRunsToTheLastDayOfTheMonth() {
        assertThat(Periods.containing(LocalDate.of(2026, 2, 20)))
                .isEqualTo(new PeriodBounds(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 28)));
        assertThat(Periods.containing(LocalDate.of(2028, 2, 20)).endsOn())
                .as("a leap year is one more day of the same fortnight, not a different one")
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(Periods.containing(LocalDate.of(2026, 4, 30)).endsOn())
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("the fifteenth is in the first half and the sixteenth in the second")
    void theFifteenthIsInTheFirstHalfAndTheSixteenthInTheSecond() {
        assertThat(Periods.containing(LocalDate.of(2026, 9, 15)).startsOn().getDayOfMonth())
                .isEqualTo(1);
        assertThat(Periods.containing(LocalDate.of(2026, 9, 16)).startsOn().getDayOfMonth())
                .isEqualTo(16);
    }

    @Test
    @DisplayName("every day of a year falls in exactly one period, and the periods tile the year")
    void everyDayOfTheYearIsInExactlyOnePeriod() {
        // The property, rather than three more examples of it. A cadence with a gap or an overlap
        // loses or double-counts an hour somebody worked, and neither has a symptom on one date.
        LocalDate day = LocalDate.of(2026, 1, 1);
        while (day.getYear() == 2026) {
            PeriodBounds bounds = Periods.containing(day);
            assertThat(day).isBetween(bounds.startsOn(), bounds.endsOn());
            LocalDate next = bounds.endsOn().plusDays(1);
            assertThat(Periods.containing(next).startsOn())
                    .as("the day after a period ends opens the next one, with no gap")
                    .isEqualTo(next);
            day = day.plusDays(1);
        }
    }
}
