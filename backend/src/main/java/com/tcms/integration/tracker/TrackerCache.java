package com.tcms.integration.tracker;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * A short, bounded, <strong>per-user</strong> cache of issue previews.
 *
 * <h2>THE KEY INCLUDES THE REQUESTING USER, AND THAT IS THE ISOLATION BOUNDARY OF THIS FEATURE</h2>
 *
 * <p>Plan 004 names this as the single most likely defect in the whole feature, and it is worth
 * restating in full rather than by reference. Revision 2 specified this class as <em>"Caffeine, per
 * binding, TTL 60s, bounded"</em>. Under per-user credentials (D2) that is a cross-user data leak
 * with a sixty-second window: user A opens a case, the issue is cached against the binding, user B —
 * same project, no Jira access at all — opens the same case and is served A's copy, title, status
 * and all.
 *
 * <p>It is the obvious thing to write, it makes every test pass, and it is invisible unless two
 * users with <em>different tracker entitlements</em> exercise the same case inside the TTL. That is
 * why {@link FakeTrackerAdapter} carries a per-user entitlement map: the regression test for this
 * has to exist and could not be written against a fake that answers everybody the same.
 *
 * <h2>A failure is cached too, per user, briefly</h2>
 *
 * <p>A 403 from the tracker for user B must not become a retry storm on every keystroke of a
 * typeahead — and it must not be shared with user A, for whom it is not true. So the negative entry
 * is keyed identically and lives for a shorter window: long enough to absorb a page's worth of
 * repeated renders, short enough that reconnecting an account is visible on the next reload rather
 * than after a minute of a screen insisting it is still broken.
 *
 * <h2>In-process and bounded, and nothing tracker-shaped goes in a shared store</h2>
 *
 * <p>No Redis, no second-level Hibernate cache, no {@code @Cacheable} on a method whose key omits
 * the person. TCMS is one deployable, so a {@link LinkedHashMap} in LRU mode is the whole
 * implementation and its bound is what keeps a company's browsing from evicting another's. <strong>If
 * this instance ever grows a shared cache, the key rule travels with it</strong> — the rule is about
 * who the answer belongs to, not about where it is stored.
 */
@Component
public class TrackerCache {

    /**
     * Long enough to serve a case detail's several chips and the re-render after it, short enough
     * that a status change in the tracker is a page reload away rather than a minute away.
     */
    private static final Duration HIT_TTL = Duration.ofSeconds(60);

    /** Shorter than a hit: a person who has just reconnected should not be told it is still broken. */
    private static final Duration MISS_TTL = Duration.ofSeconds(10);

    /**
     * Entries, across every company and person in this process. A bound rather than a target: an
     * unbounded map here is a heap leak whose size is decided by how many issues people look at.
     */
    private static final int MAX_ENTRIES = 2000;

    /**
     * The key. <strong>{@code userId} is first, and it is not there for hashing</strong> — it is
     * what makes the entry the requesting person's answer rather than the project's.
     *
     * <p>{@code bindingId} is in it because an id is only unique within a host (D17): two bindings
     * to different sites holding the same numeric id are two different issues, and a key without
     * the binding would serve one for the other.
     */
    private record Key(UUID userId, UUID bindingId, String issueId) {
    }

    private record Entry(TrackerIssue issue, Instant expiresAt) {
    }

    private final Map<Key, Entry> entries = new LinkedHashMap<>(256, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * Serves this person's cached answer, or fetches one and keeps it for them.
     *
     * @param userId <strong>the caller</strong>, from the token. Never a parameter a request could
     *               choose — the same rule the company context follows, for the same reason.
     * @param fetch  runs only on a miss. An empty result — the tracker says no such issue, or this
     *               person cannot see it — is cached negatively, per user, for {@link #MISS_TTL}.
     */
    public synchronized Optional<TrackerIssue> get(
            UUID userId, UUID bindingId, String issueId, Instant now,
            Supplier<Optional<TrackerIssue>> fetch) {

        Key key = new Key(userId, bindingId, issueId);
        Entry cached = entries.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return Optional.ofNullable(cached.issue());
        }
        Optional<TrackerIssue> fetched = fetch.get();
        entries.put(key, new Entry(
                fetched.orElse(null),
                now.plus(fetched.isPresent() ? HIT_TTL : MISS_TTL)));
        return fetched;
    }

    /**
     * Drops everything this person has cached — called when they rotate or disconnect a credential.
     *
     * <p>Without it, disconnecting an account leaves a minute in which the product still previews
     * issues with a token the person has just told it to forget. Scoped to the one person for the
     * same reason everything else here is: another user's entries are their own answers and are
     * still true.
     */
    public synchronized void forget(UUID userId) {
        entries.keySet().removeIf(key -> key.userId().equals(userId));
    }
}
