package com.tcms.repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One cross-project search request, parsed — {@link CaseQuery}'s sibling for the read that names no
 * project (§249).
 *
 * <p>A separate record rather than a wider {@link CaseQuery}, for the reason {@link CaseScope}'s
 * header gives about itself: a record whose field names have started lying is worse than two
 * records. {@code CaseQuery} carries {@code suiteId}, {@code unfiled} and {@code releaseVersion},
 * none of which has a referent across projects, and the "at least one narrowing predicate" rule
 * below is true here and false there.
 *
 * @param projectIds narrows WITHIN the caller's visible set and never widens it — the service
 *                   intersects, and this record makes no claim about visibility at all
 */
record CrossProjectSearchQuery(
        String q,
        List<UUID> projectIds,
        UUID ownerId,
        List<String> tags,
        List<LifecycleStatus> lifecycleStatus,
        List<AutomationStatus> automationStatus,
        List<Priority> priority,
        int page,
        int size,
        String sort) {

    /** The contract's stated minimum. One character over a whole company is not a filter. */
    static final int MIN_QUERY = 2;

    CrossProjectSearchQuery {
        q = blankToNull(q);
        projectIds = projectIds == null ? List.of() : projectIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // Trimmed and de-duplicated on CaseQuery's terms: `?tag=smoke&tag=smoke` is one condition,
        // and `?tag=` is a client clearing the filter rather than asking for a blank tag.
        tags = tags == null ? List.of() : tags.stream()
                .map(CrossProjectSearchQuery::blankToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        lifecycleStatus = List.copyOf(lifecycleStatus == null ? List.of() : lifecycleStatus);
        automationStatus = List.copyOf(automationStatus == null ? List.of() : automationStatus);
        priority = List.copyOf(priority == null ? List.of() : priority);
        page = Math.max(0, page);
        size = Math.clamp(size <= 0 ? CaseQuery.DEFAULT_SIZE : size, 1, CaseQuery.MAX_SIZE);
    }

    /**
     * Whether this request narrows by anything at all.
     *
     * <p>False is a <strong>400</strong>, not an unfiltered search. The enum filters do not count:
     * "every APPROVED case in every project I can see" is still company-sized, and the three that
     * do count are the three that select a small set. {@code /work-items} makes the same refusal in
     * the same words — there is no board that is every project's work unfiltered.
     */
    boolean isBounded() {
        return q != null || ownerId != null || !tags.isEmpty();
    }

    /**
     * True when {@code q} is present and too short to be a filter over a whole company.
     *
     * <p>A case key is exempt however short it is: {@code TC-4} is a whole identifier resolved by
     * equality, not a substring scanned for, so the argument for the minimum does not apply to it.
     */
    boolean queryTooShort() {
        return q != null && q.length() < MIN_QUERY && CaseQuery.keySeqOf(q) == null;
    }

    /**
     * The {@link CaseQuery} this request means — the fields with a cross-project referent.
     *
     * <p>One factory rather than a construction at the call site, so the mapping cannot drift: the
     * omitted fields are omitted on purpose ({@code suiteId} and {@code unfiled} name one project's
     * folders, {@code releaseVersion} matches nothing on any endpoint, {@code healthSignal} is a
     * dashboard drill-down and a dashboard is per project) and a later reader adding one has to
     * come here to do it.
     */
    CaseQuery asCaseQuery() {
        return new CaseQuery(
                q, List.of(), null, null, null, null, ownerId,
                lifecycleStatus, automationStatus, priority, List.of(),
                tags, false, null, page, size, sort);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
