package com.tcms.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-address sign-in lockout (005 S10, N10) — the local login's counterpart to {@link BindThrottle}.
 *
 * <h2>Silent, on purpose</h2>
 *
 * <p>A locked address gets the same answer every failed sign-in gets: 401, no body. No counter, no
 * "3 attempts left", no {@code Retry-After}. Any of those would give a guessing client a progress
 * bar, and the reason the login form says nothing about <em>why</em> a sign-in failed (the account
 * directory argument on {@link AuthenticationService}) applies to saying how close it is to being
 * refused for a different reason. The one reader who needs to know is the operator, and they get a
 * log line.
 *
 * <h2>Keyed on the address, not the account</h2>
 *
 * <p>Keying on the submitted email would let anybody lock a colleague out by typing their address
 * wrong ten times — the denial-of-service a lockout must not hand out. The address is the thing
 * the guesser controls and the account holder does not; a runner fleet behind one egress sharing a
 * counter is the accepted cost, and the numbers are sized so a person mistyping does not reach it.
 * Behind the ingress {@code server.forward-headers-strategy: framework} makes
 * {@code getRemoteAddr()} the client's address rather than the proxy's.
 *
 * <h2>In memory, in one process, and reset by a restart</h2>
 *
 * <p>Decided 2026-08-28 (005 §8.4): the state lives in the existing {@code tcms.rate-limit}
 * machinery rather than in a table. TCMS is one deployable, so the bound is exact today and a second
 * replica multiplies it by the replica count — the same trade {@link TokenRateLimiter} and
 * {@link BindThrottle} already made. A restart forgets every lockout, which is acceptable for a
 * bound whose only job is pacing.
 */
@Component
public class SignInThrottle {

    private record Failures(int count, Instant first) {
    }

    /** Bounded so a stranger cannot grow the map: 10k addresses, expired entries evicted first. */
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final Clock clock;

    public SignInThrottle(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether a sign-in from this address may be verified at all. A false here costs no hash. */
    public boolean mayAttempt(String address) {
        if (!properties.enabled()) {
            return true;
        }
        Failures held = failures.get(keyFor(address));
        if (held == null) {
            return true;
        }
        if (expired(held, clock.instant())) {
            failures.remove(keyFor(address));
            return true;
        }
        return held.count() < properties.signInAttempts();
    }

    public void recordFailure(String address) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        if (failures.size() >= MAX_TRACKED) {
            failures.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        }
        failures.compute(keyFor(address), (key, held) ->
                held == null || expired(held, now)
                        ? new Failures(1, now)
                        : new Failures(held.count() + 1, held.first()));
    }

    /** A success forgets, so somebody who mistyped twice and then got it right is not locked out. */
    public void recordSuccess(String address) {
        failures.remove(keyFor(address));
    }

    private boolean expired(Failures held, Instant now) {
        return now.isAfter(held.first().plus(properties.signInLockout()));
    }

    private static String keyFor(String address) {
        return address == null ? "" : address;
    }
}
