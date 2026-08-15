package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BindThrottleTest {

    private static final UUID PROVIDER = UUID.fromString("f3000000-0000-0000-0000-000000000010");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-15T10:00:00Z"));
    private final BindThrottle throttle =
            new BindThrottle(new LdapProperties(Duration.ofSeconds(3), Duration.ofSeconds(5), 3,
                    Duration.ofSeconds(60)), clock);

    @Test
    @DisplayName("the fourth failure is refused without reaching the directory")
    void itStopsAtTheConfiguredCount() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isTrue();
            throttle.recordFailure(PROVIDER, "dana@acme.test");
        }
        assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isFalse();
    }

    @Test
    @DisplayName("the identifier is compared case-insensitively")
    void caseDoesNotBuyMoreAttempts() {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure(PROVIDER, "dana@acme.test");
        }
        assertThat(throttle.mayBind(PROVIDER, "DANA@ACME.TEST")).isFalse();
    }

    @Test
    @DisplayName("a success clears the count, so a person who mistypes twice is not locked out")
    void successForgets() {
        throttle.recordFailure(PROVIDER, "dana@acme.test");
        throttle.recordFailure(PROVIDER, "dana@acme.test");
        throttle.recordSuccess(PROVIDER, "dana@acme.test");
        throttle.recordFailure(PROVIDER, "dana@acme.test");

        assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isTrue();
    }

    @Test
    @DisplayName("the lockout expires")
    void itForgetsAfterTheWindow() {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure(PROVIDER, "dana@acme.test");
        }
        clock.advance(Duration.ofSeconds(61));

        assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
