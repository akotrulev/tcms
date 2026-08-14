package com.tcms.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * One token bucket per API token — the arithmetic behind {@link RateLimitFilter}, kept apart from
 * it so that the part with a decision in it can be tested without a servlet, a container or a
 * database.
 *
 * <h2>The clock is the injected one</h2>
 *
 * <p>§60's rule ({@code ArchitectureRulesTest} enforces it): one time source, injected, never
 * {@code Instant.now()} at a call site. A monotonic source — {@code System.nanoTime} — is the
 * textbook choice for measuring an interval and is deliberately not used here: it cannot be
 * advanced from a test, so every assertion about refill would become a {@code Thread.sleep}, and a
 * suite that sleeps to prove a rate limit is a suite that is slow and flaky about the one property
 * this class has. What a wall clock costs in exchange is bounded and benign, and only in one
 * direction: a backwards step cannot hand anybody an allowance (see {@link Bucket}), and a forward
 * step refills a bucket early.
 *
 * <h2>Why the bucket is synchronized rather than lock-free</h2>
 *
 * <p>Read-modify-write over its one long, so a {@code compute} on the map or a
 * {@code synchronized} block is required for the count to mean anything under concurrency — and
 * the contention is per token, which is the granularity that already exists. A CAS loop would be
 * the same behaviour with a retry loop bolted on.
 */
@Component
// Non-web CLI runs have nothing to limit. Same condition as SecurityConfiguration, which is what
// puts the filter in front of this.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class TokenRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenRateLimiter.class);

    private static final long MILLIS_PER_MINUTE = Duration.ofMinutes(1).toMillis();

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    TokenRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Whether this token may spend one request, and how long to wait if it may not.
     *
     * @param retryAfter the wait a refused caller is told about, and {@code null} when allowed.
     *                   <strong>Rounded up, and never below one second</strong>: rounding down
     *                   produces the case where a client that obeys the header exactly is refused
     *                   again through no fault of its own, and a {@code Retry-After: 0} is an
     *                   instruction to retry immediately, which is the loop.
     */
    record Decision(boolean allowed, Duration retryAfter) {

        static Decision allow() {
            return new Decision(true, null);
        }

        static Decision refusedFor(Duration wait) {
            Duration atLeastASecond = wait.compareTo(Duration.ofSeconds(1)) < 0
                    ? Duration.ofSeconds(1)
                    : Duration.ofSeconds(ceilToSeconds(wait));
            return new Decision(false, atLeastASecond);
        }

        private static long ceilToSeconds(Duration wait) {
            return (wait.toMillis() + 999) / 1000;
        }
    }

    Decision check(UUID tokenId) {
        if (!properties.enabled()) {
            return Decision.allow();
        }
        return bucketFor(tokenId).spend(nowMillis());
    }

    /**
     * <strong>{@code clock.millis()} throws on this application's clock bean.</strong> Not "is
     * imprecise" — throws, on every call, with {@code ArithmeticException: / by zero}.
     *
     * <p>{@code TcmsApplication.clock()} is {@code Clock.tick(systemUTC(), Duration.ofNanos(1000))},
     * the microsecond truncation §58 added so that an in-memory {@code Instant} equals the one
     * Postgres round-trips. {@code Clock.TickClock.millis()} computes
     * {@code floorMod(millis, tickNanos / 1_000_000)}, and a sub-millisecond tick makes that
     * divisor <strong>zero</strong>. So the JDK's own convenience method is a 500 on the request
     * path here, and it was unreachable until this class: nothing in the codebase had ever called
     * {@code millis()} on the injected clock, so a bean every module depends on carried a method
     * that could not be called and no gate could have said so.
     *
     * <p>{@link Clock#instant()} is unaffected — the truncation is applied there and is exactly
     * what it is for.
     */
    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }

    private Bucket bucketFor(UUID tokenId) {
        Bucket existing = buckets.get(tokenId);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() >= properties.maxTrackedTokens()) {
            evictIdle();
        }
        return buckets.computeIfAbsent(
                tokenId, id -> new Bucket(properties.burst(), properties.requestsPerMinute()));
    }

    /**
     * Drops the buckets that have refilled completely.
     *
     * <p>A full bucket is one whose owner has been idle long enough to be indistinguishable from a
     * credential that has never been seen, so forgetting it changes no answer this class would
     * give. Nothing else is dropped: evicting a <em>partial</em> bucket would hand its owner a
     * fresh allowance, which is a bypass that arrives exactly when the map is under pressure —
     * that is, when somebody is hammering it.
     *
     * <p>If that frees nothing the map is allowed to grow past the cap, with a warning. The
     * alternative is to stop tracking, and an untracked caller is an unlimited one; the cap exists
     * to bound an assumption ({@code api_token} rows are few, and an unknown credential never
     * authenticates so it never gets a bucket), not to be defended at the cost of the limit
     * itself.
     */
    private void evictIdle() {
        long now = nowMillis();
        int before = buckets.size();
        Iterator<Bucket> candidates = buckets.values().iterator();
        while (candidates.hasNext()) {
            if (candidates.next().isFullAt(now)) {
                candidates.remove();
            }
        }
        if (buckets.size() >= properties.maxTrackedTokens()) {
            log.warn("Rate-limit bucket map is at its cap of {} with {} live entries after "
                            + "eviction — every entry is an api_token that is actively calling. "
                            + "The cap is being exceeded rather than the limit being dropped.",
                    properties.maxTrackedTokens(), buckets.size());
        } else {
            log.debug("Evicted {} idle rate-limit buckets", before - buckets.size());
        }
    }

    /** Visible for testing: how many tokens are currently being tracked. */
    int trackedTokens() {
        return buckets.size();
    }

    /**
     * A bucket held as <em>one timestamp</em> rather than as a running count of permits.
     *
     * <h2>The first version counted, and it was wrong in a way its own test caught</h2>
     *
     * <p>The obvious implementation keeps a fractional {@code available} and adds
     * {@code elapsed × rate} to it on every request. It is also a floating-point accumulator: a
     * caller polling ten times a second earns ten increments of {@code 0.1}, whose sum is
     * {@code 0.9999999999999999}, so the permit it has waited exactly one second for does not
     * arrive — <strong>a caller that obeys {@code Retry-After} is refused again</strong>, which is
     * precisely the loop this filter exists to interrupt, reached from the inside. The test named
     * {@code fractionsAccumulate} was written for the truncation bug in the same line and failed
     * on this one instead.
     *
     * <p>So the state is {@code nextFreeNanos} — the earliest instant at which the caller would be
     * <em>exactly</em> on its budget (GCRA's theoretical arrival time). One long, integer
     * arithmetic throughout, no accumulator to drift:
     *
     * <ul>
     *   <li>allowed while {@code nextFree - now} is within the burst tolerance, and each allowed
     *       request pushes {@code nextFree} out by one emission interval;</li>
     *   <li>refused otherwise, and the overshoot <em>is</em> the wait — no second formula that
     *       could disagree with the first;</li>
     *   <li>a bucket is full exactly when {@code nextFree <= now}, which is the same comparison
     *       rather than a second notion of idleness.</li>
     * </ul>
     */
    private static final class Bucket {

        private static final long NANOS_PER_MILLI = 1_000_000L;

        /** How long one request costs. Integer nanoseconds, so a rate of 7/min is expressible. */
        private final long intervalNanos;

        /**
         * How far ahead of {@code now} the budget may run — {@code (burst - 1)} intervals. A burst
         * of one is a strict one-at-a-time limiter, and the tolerance is correctly zero.
         */
        private final long burstToleranceNanos;

        private long nextFreeNanos = Long.MIN_VALUE;

        private Bucket(int burst, int perMinute) {
            this.intervalNanos = MILLIS_PER_MINUTE * NANOS_PER_MILLI / perMinute;
            this.burstToleranceNanos = (burst - 1L) * intervalNanos;
        }

        private synchronized Decision spend(long nowMillis) {
            long now = nowMillis * NANOS_PER_MILLI;
            // max(nextFree, now) is what makes an idle bucket refill to its capacity and NO
            // further: an hour of absence buys back the burst, not an hour of requests. It is also
            // what makes a clock that steps backwards harmless — the budget is not moved earlier.
            long budget = Math.max(nextFreeAt(now), now);

            if (budget - now <= burstToleranceNanos) {
                nextFreeNanos = budget + intervalNanos;
                return Decision.allow();
            }
            return Decision.refusedFor(Duration.ofNanos(budget - now - burstToleranceNanos));
        }

        private synchronized boolean isFullAt(long nowMillis) {
            long now = nowMillis * NANOS_PER_MILLI;
            return nextFreeAt(now) <= now;
        }

        private long nextFreeAt(long now) {
            return nextFreeNanos == Long.MIN_VALUE ? now : nextFreeNanos;
        }
    }
}
