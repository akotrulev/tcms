package com.tcms.execution;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;

/**
 * The one place a cron expression and a zone become a time (§247).
 *
 * <h2>One parser, called by both the write path and the poller</h2>
 *
 * <p>Scattering parsing across a validator and a poller is how a schedule gets accepted with an
 * expression the poller later reads differently — and the visible symptom of that is not an error,
 * it is a schedule that never fires and never says why. {@code RunScheduleEdit} validates through
 * this class and {@code RunScheduleService#recomputeNextFire} computes through it, so the two
 * cannot come to disagree about what {@code 0 0 7 * * MON-FRI} means.
 *
 * <h2>Spring's own expression, and no new dependency</h2>
 *
 * <p>{@link CronExpression} is already on the classpath through {@code spring-context} —
 * {@code @Scheduled} parses with it, including this feature's own poller — and a second cron
 * dialect in one application is two answers to the same string. Six fields, so the leading one is
 * seconds.
 *
 * <h2>The arithmetic happens in the schedule's zone, which is the whole reason a zone is stored</h2>
 *
 * <p>"Every weekday at 07:00" is a wall-clock statement. Doing the arithmetic in UTC is right for
 * half the year and silently an hour out for the other half, with nothing on any screen to explain
 * it — {@code CronScheduleTest.aWeekdayMorningIsWallClockInTheSchedulesZone} is the observer.
 *
 * <h2>{@link #nextAfter} may answer empty, and that is an answer rather than a failure</h2>
 *
 * <p>An expression naming a date that never arrives — the 30th of February — yields nothing at
 * all; {@link CronExpression} says so by returning null. (Not "a date that has passed": a
 * six-field cron carries no year, so every calendar expression recurs.) {@code run_schedule.next_fire_at} is nullable for it and the screen
 * says "will not fire again" rather than rendering a blank cell, which reads as "loading" or
 * "soon" — both false.
 */
final class CronSchedule {

    /**
     * The longest expression accepted.
     *
     * <p>A cron field set is short and this is not a script. The bound is checked <em>before</em>
     * the parse, so a pathological string is refused rather than handed to a parser to think about,
     * and it is the same number the contract declares as {@code maxLength} on the field.
     */
    static final int MAX_EXPRESSION = 100;

    private final CronExpression expression;
    private final ZoneId zone;

    private CronSchedule(CronExpression expression, ZoneId zone) {
        this.expression = expression;
        this.zone = zone;
    }

    /**
     * Parses an expression and a zone, or answers empty.
     *
     * <p>Empty for every way either can be wrong — blank, over {@link #MAX_EXPRESSION}, unreadable
     * as cron, or a zone {@link ZoneId#of} refuses. The caller turns that into a <strong>400 field
     * error</strong> and never into a stored row, which is the point of parsing at write time at
     * all: an expression this server cannot read must be refused to the person who typed it, not
     * discovered months later as a schedule that has never fired.
     *
     * <p>It does not say <em>which</em> of the two was wrong, deliberately — the caller knows,
     * because {@code RunScheduleEdit} asks the zone separately in order to key its message.
     */
    static Optional<CronSchedule> parse(String expression, String zoneId) {
        ZoneId zone = zoneOf(zoneId);
        if (zone == null) {
            return Optional.empty();
        }
        CronExpression parsed = expressionOf(expression);
        return parsed == null ? Optional.empty() : Optional.of(new CronSchedule(parsed, zone));
    }

    /**
     * Whether {@code zoneId} names a zone this JVM knows.
     *
     * <p>Separate from {@link #parse} so the validator can put its message on the {@code zoneId}
     * field rather than on {@code cronExpression}. One implementation, asked twice, rather than two
     * spellings of "is this a zone".
     */
    static boolean isZone(String zoneId) {
        return zoneOf(zoneId) != null;
    }

    /**
     * Whether {@code expression} is a cron expression this server can read, and short enough.
     *
     * <p>{@link #parse}'s other half, for the same reason: a message on the field the caller can
     * act on.
     */
    static boolean isExpression(String expression) {
        return expressionOf(expression) != null;
    }

    /**
     * The first occurrence strictly after {@code moment}, or empty when there is none.
     *
     * <p>Converted into the schedule's zone, advanced there, and converted back — see the class
     * header. {@link CronExpression#next} is exclusive of its argument, which is what makes a fire
     * at exactly the due instant advance to the <em>next</em> slot rather than to itself.
     */
    Optional<Instant> nextAfter(Instant moment) {
        ZonedDateTime from = moment.atZone(zone);
        ZonedDateTime next = expression.next(from);
        return next == null ? Optional.empty() : Optional.of(next.toInstant());
    }

    private static ZoneId zoneOf(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (DateTimeException unknown) {
            return null;
        }
    }

    private static CronExpression expressionOf(String expression) {
        if (expression == null || expression.isBlank()
                || expression.trim().length() > MAX_EXPRESSION) {
            return null;
        }
        try {
            return CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
