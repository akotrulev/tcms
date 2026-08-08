package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The bucket arithmetic — no Spring, no container, no clock that has to actually pass.
 *
 * <p>This is the half of phase 9 with a decision in it, and every property below is one that is
 * either invisible over HTTP or ruinously slow to assert there: the refill takes a minute of real
 * time, the rounding is one second wide, and the eviction only happens at a cap no integration
 * test would reach. {@code RateLimitTest} asserts the wiring; this asserts the behaviour.
 */
@DisplayName("The per-token bucket — burst, refill, and what a refused caller is told")
class TokenRateLimiterTest {

    private static final UUID ONE = UUID.fromString("d2000000-0000-4000-8000-000000000001");
    private static final UUID TWO = UUID.fromString("d2000000-0000-4000-8000-000000000002");

    /** 60 a minute — one a second, so a permit is worth exactly 1000 ms and the maths is legible. */
    private static RateLimitProperties generous() {
        return new RateLimitProperties(true, 60, 10, 1000);
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));

    @Nested
    @DisplayName("Spending")
    class Spending {

        @Test
        @DisplayName("the burst goes through at once, and the next request does not")
        void theBurstIsSpendable() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);

            for (int i = 0; i < 10; i++) {
                assertThat(limiter.check(ONE).allowed())
                        .as("request %d of the burst", i + 1)
                        .isTrue();
            }
            assertThat(limiter.check(ONE).allowed()).isFalse();
        }

        @Test
        @DisplayName("one token's loop does not touch another's allowance")
        void bucketsAreIndependent() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);

            for (int i = 0; i < 20; i++) {
                limiter.check(ONE);
            }

            // The reason the key is the token and not the company: a colleague's CI job must not
            // be refused because somebody's agent is in a loop.
            assertThat(limiter.check(TWO).allowed()).isTrue();
        }

        @Test
        @DisplayName("the allowance comes back as time passes, and not all at once")
        void itRefills() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);
            exhaust(limiter, ONE);

            clock.advance(Duration.ofMillis(999));
            assertThat(limiter.check(ONE).allowed())
                    .as("999 ms is not yet a permit at one per second")
                    .isFalse();

            clock.advance(Duration.ofMillis(1));
            assertThat(limiter.check(ONE).allowed()).isTrue();
            assertThat(limiter.check(ONE).allowed())
                    .as("and that permit was the only one earned")
                    .isFalse();
        }

        @Test
        @DisplayName("the bucket never refills past its capacity")
        void refillIsCapped() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);
            exhaust(limiter, ONE);

            clock.advance(Duration.ofHours(1));

            // An hour of absence must not buy an hour of requests. Without the clamp a token that
            // sat idle overnight would arrive with a 600-request allowance, which is the load
            // spike the limit exists to prevent, delivered by the limit itself.
            for (int i = 0; i < 10; i++) {
                assertThat(limiter.check(ONE).allowed()).isTrue();
            }
            assertThat(limiter.check(ONE).allowed()).isFalse();
        }

        @Test
        @DisplayName("a clock that steps backwards adds nothing")
        void aBackwardsClockDoesNotRefill() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);
            exhaust(limiter, ONE);

            clock.advance(Duration.ofMinutes(-5));

            assertThat(limiter.check(ONE).allowed()).isFalse();
        }

        @Test
        @DisplayName("fractions of a permit accumulate rather than being lost per request")
        void fractionsAccumulate() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);
            exhaust(limiter, ONE);

            // Nine refusals a hundred milliseconds apart. If each refill were truncated to whole
            // permits the caller would be refused forever while asking once per 100 ms — the
            // starvation bug this shape has, and the reason `available` is fractional.
            for (int i = 0; i < 9; i++) {
                clock.advance(Duration.ofMillis(100));
                assertThat(limiter.check(ONE).allowed())
                        .as("100 ms × %d is not yet a whole permit", i + 1)
                        .isFalse();
            }

            clock.advance(Duration.ofMillis(100));
            assertThat(limiter.check(ONE).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("What the refusal says")
    class RetryAfter {

        @Test
        @DisplayName("the wait is rounded UP, so a client that obeys it is not refused again")
        void theWaitIsRoundedUp() {
            TokenRateLimiter limiter = new TokenRateLimiter(
                    // 40 a minute: a permit is 1500 ms, so an exhausted bucket's honest wait is
                    // 1.5 s — the case where rounding down produces a second refusal for a client
                    // that did exactly what it was told.
                    new RateLimitProperties(true, 40, 10, 1000), clock);
            exhaust(limiter, ONE, 10);

            assertThat(limiter.check(ONE).retryAfter()).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("and is never zero, whatever the arithmetic says")
        void theWaitIsNeverZero() {
            TokenRateLimiter limiter = new TokenRateLimiter(
                    // 60,000 a minute — a permit every millisecond, so the true wait rounds to
                    // nothing. `Retry-After: 0` is an instruction to retry immediately, which is
                    // the loop this whole slice exists to interrupt.
                    new RateLimitProperties(true, 60_000, 10, 1000), clock);
            exhaust(limiter, ONE, 10);

            assertThat(limiter.check(ONE).retryAfter()).isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("an allowed request carries no wait at all")
        void anAllowedDecisionHasNoWait() {
            TokenRateLimiter limiter = new TokenRateLimiter(generous(), clock);

            assertThat(limiter.check(ONE).retryAfter()).isNull();
        }
    }

    @Nested
    @DisplayName("The map")
    class Tracking {

        @Test
        @DisplayName("disabled means nothing is counted and nothing is remembered")
        void disabledTracksNothing() {
            TokenRateLimiter limiter =
                    new TokenRateLimiter(new RateLimitProperties(false, 60, 10, 1000), clock);

            for (int i = 0; i < 100; i++) {
                assertThat(limiter.check(ONE).allowed()).isTrue();
            }
            // Not merely "always allowed": an operator who turns the limit off must not be paying
            // for a map that grows for the length of the process.
            assertThat(limiter.trackedTokens()).isZero();
        }

        @Test
        @DisplayName("a full bucket is forgotten at the cap; a partial one is not")
        void idleBucketsAreEvicted() {
            TokenRateLimiter limiter =
                    new TokenRateLimiter(new RateLimitProperties(true, 60, 10, 2), clock);

            limiter.check(ONE);                       // ONE is now one permit short.
            limiter.check(TWO);                       // so is TWO.
            clock.advance(Duration.ofMinutes(1));     // both refill completely.
            limiter.check(ONE);                       // ONE spends again — partial, and hot.

            UUID three = UUID.fromString("d2000000-0000-4000-8000-000000000003");
            limiter.check(three);

            // TWO was full and is gone; ONE was mid-spend and must not be, because dropping a
            // partial bucket hands its owner a fresh allowance exactly when the map is under
            // pressure — that is, exactly when somebody is hammering it.
            assertThat(limiter.trackedTokens()).isEqualTo(2);
            for (int i = 0; i < 9; i++) {
                assertThat(limiter.check(ONE).allowed()).isTrue();
            }
            assertThat(limiter.check(ONE).allowed())
                    .as("ONE kept the permit it had already spent")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Configuration this product refuses to start with")
    class Configuration {

        @Test
        @DisplayName("a burst larger than one minute of refill is refused at bind time")
        void aBurstAboveTheRateIsRefused() {
            assertThat(catchThrowable(
                    () -> new RateLimitProperties(true, 60, 61, 1000)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed");
        }

        @Test
        @DisplayName("and so is a rate, a burst or a cap below one")
        void nonsenseNumbersAreRefused() {
            assertThat(catchThrowable(
                    () -> new RateLimitProperties(true, 0, 1, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(
                    () -> new RateLimitProperties(true, 60, 0, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(
                    () -> new RateLimitProperties(true, 60, 10, 0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void exhaust(TokenRateLimiter limiter, UUID token) {
        exhaust(limiter, token, 10);
    }

    private static void exhaust(TokenRateLimiter limiter, UUID token, int burst) {
        for (int i = 0; i < burst; i++) {
            limiter.check(token);
        }
    }

    /**
     * A clock a test can move.
     *
     * <p>The reason {@link TokenRateLimiter} takes the injected {@code Clock} rather than
     * {@code System.nanoTime}: a monotonic source is the textbook choice for measuring an interval
     * and cannot be advanced, so every assertion above would be a {@code Thread.sleep} and this
     * class would take four minutes to prove what it proves in milliseconds.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
