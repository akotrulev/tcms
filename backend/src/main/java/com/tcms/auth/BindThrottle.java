package com.tcms.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How many times one identifier may fail against one provider before this instance stops asking
 * the directory.
 *
 * <h2>Why this exists <s>when local login has no lockout</s></h2>
 *
 * <p><s>Not having one is a decision this product already took, and it is right for a credential
 * this product owns: an attacker guessing a TCMS password costs a bcrypt verification and nothing
 * else.</s> → superseded 2026-08-28 (005 S10): local login has {@link SignInThrottle} now, keyed per
 * address and silent. The argument below still holds and is the sharper one — the two bounds
 * protect different systems.
 * <strong>A failed LDAP bind increments the customer's own directory counter</strong> — with Active
 * Directory's default policy, an endpoint with no bound here is a way to lock a company's entire
 * staff out of everything, from the internet, in minutes. The harm lands on a system this product
 * does not own and cannot unlock.
 *
 * <p>{@link RateLimitFilter} cannot do this job: it counts API tokens, and its own header explains
 * why an unauthenticated request has no key that the caller does not control. This one is keyed on
 * something the caller <em>does</em> control, which is normally the objection — but here the key is
 * exactly the thing being protected, so an attacker who rotates the identifier is no longer
 * attacking any one directory account.
 *
 * <p>In memory, in one process, like {@link TokenRateLimiter}: TCMS is one deployable, and a second
 * replica multiplies the bound by the replica count.
 */
@Component
public class BindThrottle {

    private record Key(UUID providerId, String identifier) {
    }

    private record Failures(int count, Instant first) {
    }

    /** Bounded so a stranger cannot grow the map: 10k identifiers, expired entries evicted. */
    private static final int MAX_TRACKED = 10_000;

    private final Map<Key, Failures> failures = new ConcurrentHashMap<>();
    private final LdapProperties properties;
    private final Clock clock;

    public BindThrottle(LdapProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether a bind may be attempted at all. A false here never reaches the directory. */
    public boolean mayBind(UUID providerId, String identifier) {
        Failures held = failures.get(keyFor(providerId, identifier));
        if (held == null) {
            return true;
        }
        if (clock.instant().isAfter(held.first().plus(properties.bindLockout()))) {
            failures.remove(keyFor(providerId, identifier));
            return true;
        }
        return held.count() < properties.maxBindAttempts();
    }

    public void recordFailure(UUID providerId, String identifier) {
        if (failures.size() >= MAX_TRACKED) {
            failures.entrySet().removeIf(entry ->
                    clock.instant().isAfter(entry.getValue().first().plus(properties.bindLockout())));
        }
        Instant now = clock.instant();
        failures.compute(keyFor(providerId, identifier), (key, held) ->
                held == null || now.isAfter(held.first().plus(properties.bindLockout()))
                        ? new Failures(1, now)
                        : new Failures(held.count() + 1, held.first()));
    }

    /** A success forgets, so somebody who mistyped twice and then got it right is not locked out. */
    public void recordSuccess(UUID providerId, String identifier) {
        failures.remove(keyFor(providerId, identifier));
    }

    private static Key keyFor(UUID providerId, String identifier) {
        return new Key(providerId, identifier == null ? "" : identifier.toLowerCase(Locale.ROOT));
    }
}
