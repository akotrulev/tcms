package com.tcms.auth;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes {@code sso_login_attempt} rows whose expiry has passed.
 *
 * <p>Nothing else ever removes one. {@code consume} stamps a row and leaves it, expiry is checked
 * and not enforced by anything — and the rows are written by an <strong>unauthenticated</strong>
 * surface: every {@code GET /auth/sso/{providerId}/authorize} that resolves an enabled provider
 * inserts one, so a crawler hitting a sign-in button's URL grows the table without bound. The
 * attempt itself is dead the instant {@code expires_at} passes ({@link SsoLoginAttempt#isUsable}),
 * so everything this deletes is already unreachable by the flow.
 *
 * <h2>No company loop, unlike every sibling scheduler</h2>
 *
 * <p>{@code OutboxScheduler} and its two siblings open a context per company because their tables
 * are under FORCE RLS and a context-less query sees nothing. {@code sso_login_attempt} is
 * instance-level by decision ({@code V53}, ERD §0.1) — the flow must read it before any company is
 * known — so the one delete below sees every row, and wrapping it in a company loop would be
 * cargo-culting the shape while its reason points the other way.
 *
 * <h2>The grace period is for the operator, not the flow</h2>
 *
 * <p>A failed sign-in is one log line and one opaque marker in a browser; the attempt row is the
 * only durable trace of which provider and which company the attempt was against. Keeping expired
 * rows for {@link #GRACE} costs a few rows and leaves that trace readable while somebody is still
 * likely to be chasing the failure.
 */
@Component
class SsoLoginAttemptPurge {

    private static final Logger log = LoggerFactory.getLogger(SsoLoginAttemptPurge.class);

    static final Duration GRACE = Duration.ofHours(1);

    private final SsoLoginAttemptRepository attempts;
    private final Clock clock;

    SsoLoginAttemptPurge(SsoLoginAttemptRepository attempts, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
    }

    /**
     * Hourly, with the fourth initial delay — a fixed delay rather than a cron, for
     * {@code CaseHealthScheduler}'s stated reason, and {@code PT4M} because the three sibling
     * schedulers already hold one, two and three minutes and the point of the stagger is that they
     * do not start in lockstep and stay there.
     */
    @Scheduled(initialDelayString = "PT4M", fixedDelayString = "PT1H")
    void purgeExpired() {
        int purged = attempts.deleteExpiredBefore(clock.instant().minus(GRACE));
        if (purged > 0) {
            log.debug("Purged {} expired SSO login attempts", purged);
        }
    }
}
