package com.tcms.integration.tracker;

import java.util.List;

/**
 * {@code TrackerIssueSearch} — the typeahead's answer, or the reason there is none.
 *
 * <p><strong>No identity is a 200 with {@code NOT_CONNECTED} and an empty list, never a 401.</strong>
 * The viewer is authenticated to TCMS perfectly well; the thing they lack is a credential for
 * somebody else's server. A 401 here would trip the SPA's session-recovery path
 * ({@code app/queryClient.ts}, which {@code tcmsFaults.expire()} exists to exercise) for a condition
 * that has nothing to do with their TCMS session — a specific, easy defect, which is why the plan
 * spends a paragraph on it and why this comment repeats it at the type that would carry it.
 */
public record TrackerIssueSearchView(TrackerIssueAvailability state, List<TrackerIssue> issues) {

    static TrackerIssueSearchView unavailable(TrackerIssueAvailability state) {
        return new TrackerIssueSearchView(state, List.of());
    }
}
