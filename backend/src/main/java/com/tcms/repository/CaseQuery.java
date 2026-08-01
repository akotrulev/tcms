package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One case-list request, parsed — every query parameter the contract defines for
 * {@code GET /projects/{projectId}/cases}, normalized once at the edge.
 *
 * <p>A record between the controller and the service so the service never sees a raw
 * {@code String} it has to decide how to trim, and so the awkward parsing rules below have exactly
 * one home. Spring binds the constructor parameters by name.
 *
 * @param q               free text over title and description, or a case key — see
 *                        {@link #keySeq()}
 * @param suiteId         a folder; matches its whole subtree, per the contract
 * @param featureId       a Feature value; matches its whole subtree, per the contract (plan §6q)
 * @param testTypeId      a Test Type value. Flat in the seeded vocabulary, but matched as a
 *                        subtree anyway — a client may nest an unlocked dimension, and a filter
 *                        that silently stops including children the day someone does is worse
 *                        than one that never did
 * @param testLevelId     a Test Level value, on the same terms
 * @param ownerId         the case's owner. Exact, not a subtree and not a name search — it exists
 *                        to make the dashboard's by-owner segments reproduce their own counts
 *                        (plan §6o), and those segments carry an id
 * @param lifecycleStatus OR within the list, AND against the other filters. Empty means "no
 *                        opinion", never "match nothing" — an unset filter and an
 *                        explicitly-empty one arrive identically over HTTP, so they cannot be
 *                        told apart and the useful reading wins
 * @param releaseVersion  cases executed in a release. <strong>Matches nothing today</strong> — see
 *                        {@link CaseSpecifications} for why that is the honest answer rather than
 *                        an ignored parameter
 * @param page            zero-based, clamped at 0
 * @param size            clamped to {@code 1..200}, the contract's maximum
 * @param sort            {@code field,dir} — resolved by {@link CaseSort}
 */
public record CaseQuery(
        String q,
        UUID suiteId,
        UUID featureId,
        UUID testTypeId,
        UUID testLevelId,
        UUID ownerId,
        List<LifecycleStatus> lifecycleStatus,
        List<AutomationStatus> automationStatus,
        List<Priority> priority,
        List<HealthSignal> healthSignal,
        String releaseVersion,
        int page,
        int size,
        String sort) {

    /** The contract's default, and the SPA's: {@code DEFAULT_SIZE} in {@code useCaseFilters.ts}. */
    public static final int DEFAULT_SIZE = 50;

    /** The contract's stated maximum. A larger request is clamped, never refused. */
    public static final int MAX_SIZE = 200;

    /**
     * Every case in scope — no filter of any kind, which is what a whole-project tally counts over.
     *
     * <p>Exists so {@link DashboardService} can reuse {@link CaseSearch}'s tallies rather than write
     * its own {@code GROUP BY} over its own predicate. That reuse is the point and not a saving:
     * a dashboard tile is a <em>link into the case list</em>, so the tile's count and the list it
     * opens must be the same query. Sharing {@link CaseSpecifications} makes them the same query by
     * construction, instead of two predicates that agree until one of them learns about
     * soft-deleted suites and the other does not.
     *
     * <p>Paging is irrelevant here — the tallies and the count select aggregates, never a page — so
     * the defaults are left as they are rather than given meaningless values.
     */
    static CaseQuery unfiltered() {
        return new CaseQuery(
                null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(),
                null, 0, DEFAULT_SIZE, null);
    }

    /**
     * {@code TC-1}, and the underscore spelling the SPA's mock also accepts.
     *
     * <p>Case-insensitive because the string arrives from a paste out of a Jira ticket or a commit
     * message as often as from typing, and refusing {@code tc-1} would be refusing the same
     * identifier in different clothes.
     */
    private static final Pattern CASE_KEY = Pattern.compile("^TC[_-]?(\\d+)$", Pattern.CASE_INSENSITIVE);

    /** Normalizes at construction, so nothing downstream re-decides what a blank string means. */
    public CaseQuery {
        q = blankToNull(q);
        releaseVersion = blankToNull(releaseVersion);
        lifecycleStatus = List.copyOf(lifecycleStatus == null ? List.of() : lifecycleStatus);
        automationStatus = List.copyOf(automationStatus == null ? List.of() : automationStatus);
        priority = List.copyOf(priority == null ? List.of() : priority);
        healthSignal = List.copyOf(healthSignal == null ? List.of() : healthSignal);
        page = Math.max(0, page);
        size = Math.clamp(size <= 0 ? DEFAULT_SIZE : size, 1, MAX_SIZE);
    }

    /**
     * The numeric part when {@link #q} <em>is</em> a case key, or null when it is free text.
     *
     * <p>A key short-circuits to an exact lookup rather than searching for it as text (plan §6q):
     * someone who was handed {@code TC-7} means that case, and a substring search would also return
     * {@code TC-70} and every case whose title happens to mention it. The one it is <em>most</em>
     * likely to bury is the exact match, because there is only ever one of those.
     *
     * <p>Returned as a {@code Long} rather than parsed at the use site because
     * {@code Long.parseLong} on a 30-digit key would throw where a filter that matches nothing is
     * the right answer — a key that cannot exist should return no rows, not a 500.
     */
    Long keySeq() {
        if (q == null) {
            return null;
        }
        var match = CASE_KEY.matcher(q);
        if (!match.matches()) {
            return null;
        }
        try {
            return Long.parseLong(match.group(1));
        } catch (NumberFormatException overflow) {
            // A key too large to be a bigint cannot name a row. Long.MIN_VALUE is used as the
            // "matches nothing" sentinel because key_seq is allocated from 1 upward and no
            // negative value is reachable.
            return Long.MIN_VALUE;
        }
    }

    /** True when {@link #q} is free text rather than a key — the two are never both applied. */
    boolean hasFreeText() {
        return q != null && keySeq() == null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
