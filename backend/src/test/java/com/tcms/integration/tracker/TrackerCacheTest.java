package com.tcms.integration.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TrackerCache} — <strong>the two-user leak</strong>.
 *
 * <p>Plan 004 names this as the single most likely defect in the whole feature, and it says which
 * test it wants: <em>"If one test from this plan survives a rewrite, it is this one."</em>
 *
 * <p>The defect is a cache keyed by binding rather than by person. It is the obvious thing to write,
 * it makes every other test pass, and it is invisible unless two users with <em>different tracker
 * entitlements</em> exercise the same issue inside one TTL window. Nothing about the running product
 * reveals it: user B's screen shows an issue title, which is exactly what it shows when B is
 * entitled to see it.
 *
 * <p>No Spring context and no database — §150's pure lane. The cache is in-process by design (the
 * class header says why, and says the key rule travels with it if that ever changes), so a container
 * would add minutes and prove nothing this cannot.
 */
@DisplayName("TrackerCache — one user's answer is never served to another")
class TrackerCacheTest {

    private static final UUID USER_A = UUID.fromString("d9000000-0000-4000-8000-00000000000a");
    private static final UUID USER_B = UUID.fromString("d9000000-0000-4000-8000-00000000000b");
    private static final UUID BINDING = UUID.fromString("d9001000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BINDING =
            UUID.fromString("d9001000-0000-4000-8000-000000000002");

    private static final String ISSUE_ID = "10042";

    private final TrackerCache cache = new TrackerCache();
    private final Instant now = Instant.parse("2026-08-29T10:00:00Z");

    private static TrackerIssue issue(String title) {
        return new TrackerIssue(
                TrackerProvider.JIRA_CLOUD, ISSUE_ID, "PAY-991", title,
                TrackerIssueStatusCategory.IN_PROGRESS, "In progress",
                "https://acme.atlassian.net/browse/PAY-991", BINDING);
    }

    @Test
    @DisplayName("B gets nothing after A got a title — the same issue, the same binding, one TTL")
    void oneUsersAnswerIsNotServedToAnother() {
        Optional<TrackerIssue> forA = cache.get(
                USER_A, BINDING, ISSUE_ID, now, () -> Optional.of(issue("Fix the rounding")));

        assertThat(forA).map(TrackerIssue::title).contains("Fix the rounding");

        // B has no access in the tracker, so B's fetch answers empty. If the key omitted the user,
        // this call would never run and B would be handed A's title — inside the same second.
        Optional<TrackerIssue> forB = cache.get(
                USER_B, BINDING, ISSUE_ID, now, Optional::empty);

        assertThat(forB)
                .as("B must see \"Preview unavailable\". A cache keyed on (binding, issue) serves "
                        + "A's copy here and every other test in this feature still passes")
                .isEmpty();
    }

    @Test
    @DisplayName("B's fetch is actually invoked — the leak's mechanism, asserted directly")
    void bsFetchRuns() {
        // The assertion above is about the VALUE. This one is about the CALL, because a cache that
        // returned empty for B by accident — an expiry bug, say — would satisfy the first test
        // while still being keyed wrongly.
        AtomicInteger fetches = new AtomicInteger();

        cache.get(USER_A, BINDING, ISSUE_ID, now, () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("Fix the rounding"));
        });
        cache.get(USER_B, BINDING, ISSUE_ID, now, () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("Fix the rounding"));
        });

        assertThat(fetches)
                .as("two users, two calls to the tracker. One call means one cache entry is "
                        + "serving both of them")
                .hasValue(2);
    }

    @Test
    @DisplayName("the same user inside the window does not call the tracker again")
    void oneUserIsCached() {
        // The cache has to actually cache, or the isolation above is trivially satisfied by not
        // caching at all — which would be a correct product and a pointless class.
        AtomicInteger fetches = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            cache.get(USER_A, BINDING, ISSUE_ID, now.plusSeconds(i), () -> {
                fetches.incrementAndGet();
                return Optional.of(issue("Fix the rounding"));
            });
        }

        assertThat(fetches).hasValue(1);
    }

    @Test
    @DisplayName("the same id on two hosts is two issues — identity is (provider, host, id)")
    void theBindingIsInTheKey() {
        // 004 D17. An id is only unique within a host, so two bindings to different sites holding
        // the same numeric id are different issues. A key of (user, issueId) collides them and one
        // case renders another tracker's issue.
        cache.get(USER_A, BINDING, ISSUE_ID, now, () -> Optional.of(issue("Acme's issue")));

        Optional<TrackerIssue> other = cache.get(
                USER_A, OTHER_BINDING, ISSUE_ID, now, () -> Optional.of(issue("Umbra's issue")));

        assertThat(other).map(TrackerIssue::title).contains("Umbra's issue");
    }

    @Test
    @DisplayName("a failure is cached negatively, per user, and briefly")
    void failuresAreCachedNegativelyAndBriefly() {
        AtomicInteger fetches = new AtomicInteger();

        // A 403 from the tracker on every keystroke of a typeahead is a retry storm. Cached — but
        // for a shorter window than a hit, because the remedy (reconnect an account) has to be
        // visible on the next reload rather than a minute later.
        cache.get(USER_B, BINDING, ISSUE_ID, now, () -> {
            fetches.incrementAndGet();
            return Optional.empty();
        });
        cache.get(USER_B, BINDING, ISSUE_ID, now.plusSeconds(5), () -> {
            fetches.incrementAndGet();
            return Optional.empty();
        });

        assertThat(fetches).as("the second read inside the negative window did not call out")
                .hasValue(1);

        cache.get(USER_B, BINDING, ISSUE_ID, now.plusSeconds(30), () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("Now visible"));
        });

        assertThat(fetches)
                .as("thirty seconds later the refusal has expired — a person who reconnected must "
                        + "not be told for a full minute that it is still broken")
                .hasValue(2);
    }

    @Test
    @DisplayName("a hit expires, so a status change in the tracker is a reload away")
    void hitsExpire() {
        AtomicInteger fetches = new AtomicInteger();

        cache.get(USER_A, BINDING, ISSUE_ID, now, () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("Fix the rounding"));
        });
        cache.get(USER_A, BINDING, ISSUE_ID, now.plusSeconds(61), () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("Fix the rounding"));
        });

        assertThat(fetches).hasValue(2);
    }

    @Test
    @DisplayName("forget() drops one person's entries and leaves everybody else's")
    void forgetIsScopedToThePerson() {
        // Called when somebody rotates or disconnects a credential. Without it, disconnecting an
        // account leaves a minute in which the product still previews issues with a token the
        // person has just told it to forget. Scoped, because everyone else's entries are their own
        // answers and are still true.
        cache.get(USER_A, BINDING, ISSUE_ID, now, () -> Optional.of(issue("A's view")));
        cache.get(USER_B, BINDING, ISSUE_ID, now, () -> Optional.of(issue("B's view")));

        cache.forget(USER_A);

        AtomicInteger fetches = new AtomicInteger();
        cache.get(USER_A, BINDING, ISSUE_ID, now, () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("A refetched"));
        });
        cache.get(USER_B, BINDING, ISSUE_ID, now, () -> {
            fetches.incrementAndGet();
            return Optional.of(issue("B refetched"));
        });

        assertThat(fetches).as("A refetched, B did not").hasValue(1);
    }
}
