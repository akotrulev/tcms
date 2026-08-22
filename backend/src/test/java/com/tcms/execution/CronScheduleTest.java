package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cron parser, in isolation (§247).
 *
 * <p>Here rather than inside {@code RunScheduleEndpointTest} because the failures are arithmetic,
 * not integration: a Testcontainers boot per DST case would cost minutes to assert something with
 * no database in it. No Spring context, no container.
 *
 * <p>Ids are not needed — nothing here touches a row — so this class takes no fixture prefix.
 */
@DisplayName("CronSchedule — the one place an expression and a zone become a time")
class CronScheduleTest {

    /** Every weekday at 07:00, six-field Spring cron. */
    private static final String WEEKDAY_MORNING = "0 0 7 * * MON-FRI";

    private static final String SOFIA = "Europe/Sofia";

    /**
     * <strong>The arm this class exists for.</strong>
     *
     * <p>Europe/Sofia is UTC+2 in January and UTC+3 in July. An implementation that does the
     * arithmetic in UTC lands 07:00 local for half the year and 08:00 local for the other half —
     * no error, no log line, and a regression pass that quietly moves an hour twice a year.
     *
     * <p>So this asserts the <strong>local</strong> hour on both sides of the boundary, and
     * separately asserts that the two UTC instants carry <em>different</em> offsets. Asserting the
     * local hour alone is not enough: a UTC implementation converted back for display would agree
     * with it, which is the shape §241's rule warns about.
     */
    @Test
    @DisplayName("a weekday morning is wall-clock in the schedule's zone, across a DST boundary")
    void aWeekdayMorningIsWallClockInTheSchedulesZone() {
        CronSchedule schedule = parsed(WEEKDAY_MORNING, SOFIA);
        ZoneId sofia = ZoneId.of(SOFIA);

        // A Monday in January (UTC+2) and a Monday in July (UTC+3).
        Instant winterFrom = ZonedDateTime.of(2026, 1, 12, 3, 0, 0, 0, sofia).toInstant();
        Instant summerFrom = ZonedDateTime.of(2026, 7, 13, 3, 0, 0, 0, sofia).toInstant();

        ZonedDateTime winter = schedule.nextAfter(winterFrom).orElseThrow().atZone(sofia);
        ZonedDateTime summer = schedule.nextAfter(summerFrom).orElseThrow().atZone(sofia);

        assertThat(winter.getHour())
                .as("07:00 local in January — the offset is +02:00 there")
                .isEqualTo(7);
        assertThat(summer.getHour())
                .as("07:00 local in July too — and this is the half a UTC implementation gets "
                        + "wrong, silently")
                .isEqualTo(7);

        assertThat(winter.getOffset())
                .as("the two fires are at DIFFERENT UTC offsets, which is what makes the local "
                        + "hour above a real claim rather than one a UTC implementation would "
                        + "also satisfy after converting back for display")
                .isNotEqualTo(summer.getOffset());
    }

    /** The next occurrence is strictly after the moment asked about, never the moment itself. */
    @Test
    @DisplayName("a fire at exactly the due instant advances to the NEXT slot, not to itself")
    void theNextOccurrenceIsStrictlyAfterTheMomentAskedAbout() {
        CronSchedule everyMinute = parsed("0 * * * * *", "UTC");
        Instant onTheMinute = Instant.parse("2026-08-22T09:00:00Z");

        assertThat(everyMinute.nextAfter(onTheMinute))
                .as("exclusive — otherwise a schedule fired at its own slot recomputes to that "
                        + "same slot and is due again on the very next tick, for ever")
                .contains(Instant.parse("2026-08-22T09:01:00Z"));
    }

    @Test
    @DisplayName("an unparseable expression is refused rather than stored")
    void anUnparseableExpressionIsRefusedRatherThanStored() {
        assertThat(CronSchedule.parse("every monday please", "UTC")).isEmpty();
        assertThat(CronSchedule.isExpression("every monday please")).isFalse();

        assertThat(CronSchedule.parse("  ", "UTC")).as("blank is not an expression").isEmpty();
        assertThat(CronSchedule.parse(null, "UTC")).as("nor is nothing at all").isEmpty();

        // Five fields is Unix cron and this parser takes six. Accepting it would shift every
        // field by one and produce a schedule that fires at a time nobody asked for.
        assertThat(CronSchedule.parse("0 7 * * MON-FRI", "UTC"))
                .as("five fields is the other dialect; taking it would shift every field")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown zone is refused, and refused separately from the expression")
    void anUnknownZoneIsRefused() {
        assertThat(CronSchedule.parse(WEEKDAY_MORNING, "Europe/Sofiaa")).isEmpty();
        assertThat(CronSchedule.isZone("Europe/Sofiaa")).isFalse();
        assertThat(CronSchedule.isZone("  ")).isFalse();
        assertThat(CronSchedule.isZone(null)).isFalse();

        assertThat(CronSchedule.isZone(SOFIA)).isTrue();
        assertThat(CronSchedule.isZone("UTC")).isTrue();

        // The two questions are asked apart so the validator can key its message to the field the
        // caller can act on. A single "the schedule is wrong" would send somebody to the cron box
        // over a typo in the zone.
        assertThat(CronSchedule.isExpression(WEEKDAY_MORNING))
                .as("the expression is fine; only the zone was wrong")
                .isTrue();
    }

    /**
     * Empty is a real answer, not a failure.
     *
     * <p>{@code next_fire_at} is nullable for exactly this, and the screen says "will not fire
     * again". An implementation that threw here would turn a legitimate schedule into a 500 on
     * every save.
     */
    @Test
    @DisplayName("an expression with no future occurrence answers empty")
    void anExpressionWithNoFutureOccurrenceAnswersEmpty() {
        // Pinned to a date that has passed: 03:00 on 1 January 2020.
        CronSchedule pinned = parsed("0 0 3 1 1 *", "UTC");

        Optional<Instant> next = pinned.nextAfter(Instant.parse("2200-01-02T00:00:00Z"));

        assertThat(next)
                .as("CronExpression legitimately answers null past its own bound; that is a "
                        + "nullable column, not an exception")
                .isEmpty();
    }

    @Test
    @DisplayName("an expression longer than the cap is refused before it is parsed")
    void anExpressionLongerThanTheCapIsRefusedBeforeItIsParsed() {
        String tooLong = "0 0 7 * * " + "MON,TUE,WED,THU,FRI,".repeat(20);

        assertThat(tooLong.length()).isGreaterThan(CronSchedule.MAX_EXPRESSION);
        assertThat(CronSchedule.parse(tooLong, "UTC")).isEmpty();
        assertThat(CronSchedule.isExpression(tooLong)).isFalse();
    }

    @Test
    @DisplayName("an expression and a zone are trimmed, so a stray space is not a 400")
    void surroundingWhitespaceIsNotAValidationFailure() {
        assertThat(CronSchedule.parse("  " + WEEKDAY_MORNING + "  ", "  " + SOFIA + " "))
                .isPresent();
    }

    private static CronSchedule parsed(String expression, String zoneId) {
        return CronSchedule.parse(expression, zoneId)
                .orElseThrow(() -> new AssertionError(
                        "the fixture's own expression must parse: " + expression + " / " + zoneId));
    }
}
