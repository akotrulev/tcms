package com.tcms.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How much one API token may ask for — the MCP plan's phase 9, and its §4 answer in full:
 * <em>per token, generous</em>.
 *
 * <h2>Why a token bucket and not a window</h2>
 *
 * <p>A fixed window lets a caller spend its whole allowance at the end of one window and again at
 * the start of the next, so the limit an operator configures is not the limit anybody gets — and it
 * refuses a caller for <em>where the clock was</em> rather than for what it did. A bucket has one
 * behaviour: {@link #burst} at once, then {@link #requestsPerMinute} forever.
 *
 * <h2>The counter is in memory, in one process</h2>
 *
 * <p>TCMS is one deployable, so today that is exact. <strong>A second replica multiplies every
 * limit here by the replica count</strong> — that is the price of not keeping the counter in
 * Postgres, and keeping it there would mean a write per request on the hot path against the
 * database this limit exists to protect. Written down here rather than discovered by an operator
 * whose limit stopped meaning anything when they scaled out.
 *
 * <h2>Sessions are not counted, and unauthenticated requests cannot be</h2>
 *
 * <p>See {@link RateLimitFilter}. The short form: the failure being prevented is an agent in a
 * loop, which is a shape a person clicking cannot produce; and in front of authentication there is
 * no key to count against that the caller does not control.
 *
 * @param enabled            whether {@link RateLimitFilter} counts anything. Its real job is the
 *                           test suite and a deployment debugging a refusal — an operator who
 *                           turns this off is choosing to have no bound at all, which is the state
 *                           every release before this one shipped in.
 * @param requestsPerMinute  the sustained rate one token is refilled at. Generous on purpose: this
 *                           is a bound on an accident, not a quota anybody is meant to plan
 *                           against, and a number a legitimate caller can reach is a number that
 *                           produces support conversations instead of preventing incidents.
 * @param burst              how many requests may be spent at once — the bucket's capacity. A
 *                           batch of list calls opening an agent's session is exactly this shape,
 *                           and refusing it would make the first thing every agent does fail.
 * @param maxTrackedTokens   the cap on how many buckets are held. Bounded already by the number of
 *                           live {@code api_token} rows, because an unknown credential never
 *                           authenticates and therefore never gets a bucket; the cap is what keeps
 *                           that from being an assumption the map depends on. Full buckets — the
 *                           ones whose owner has been idle long enough to be indistinguishable
 *                           from a caller that has never been seen — are dropped first.
 * @param signInAttempts     how many failed local sign-ins one address may make inside
 *                           {@code signInLockout} before the next is refused unverified — 005 S10's
 *                           lockout, kept on this record by decision (§8.4) rather than given a
 *                           table. See {@link SignInThrottle} for why it is silent and per address.
 * @param signInLockout      how long the count lives, and therefore how long a locked address
 *                           waits. Measured from the first failure, not the last, so a guesser
 *                           cannot keep the window open by keeping guessing.
 */
@ConfigurationProperties(prefix = "tcms.rate-limit")
@Validated
public record RateLimitProperties(
        boolean enabled,
        int requestsPerMinute,
        int burst,
        int maxTrackedTokens,
        int signInAttempts,
        Duration signInLockout) {

    /**
     * Compact constructor. Bean Validation can express the first three of these and not the
     * fourth, and splitting the checks across two mechanisms is how one of them ends up unenforced
     * — {@code OutboxProperties} records the same decision.
     */
    public RateLimitProperties {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException(
                    "tcms.rate-limit.requests-per-minute must be at least 1, was "
                            + requestsPerMinute);
        }
        if (burst < 1) {
            throw new IllegalArgumentException(
                    "tcms.rate-limit.burst must be at least 1, was " + burst);
        }
        if (maxTrackedTokens < 1) {
            throw new IllegalArgumentException(
                    "tcms.rate-limit.max-tracked-tokens must be at least 1, was " + maxTrackedTokens);
        }
        if (signInAttempts < 1) {
            throw new IllegalArgumentException(
                    "tcms.rate-limit.sign-in-attempts must be at least 1, was " + signInAttempts);
        }
        if (signInLockout == null || signInLockout.isZero() || signInLockout.isNegative()) {
            throw new IllegalArgumentException(
                    "tcms.rate-limit.sign-in-lockout must be a positive duration, was "
                            + signInLockout);
        }
        // A burst below the per-minute rate is legal and normal — it is the instantaneous
        // allowance, not the sustained one. A burst ABOVE it is the configuration that reads as
        // generous and is not: the bucket empties in one batch and then refills at a rate the
        // operator never intended to be the real limit, so the first caller of the minute gets a
        // different API from the second. Refused at startup rather than explained afterwards.
        if (burst > requestsPerMinute) {
            throw new IllegalArgumentException(
                    "tcms.rate-limit.burst (" + burst + ") must not exceed "
                            + "tcms.rate-limit.requests-per-minute (" + requestsPerMinute
                            + "): a bucket larger than one minute of refill spends a batch that "
                            + "takes longer than a minute to earn back");
        }
    }
}
