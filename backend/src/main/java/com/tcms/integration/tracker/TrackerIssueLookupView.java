package com.tcms.integration.tracker;

/**
 * {@code TrackerIssueLookup} — one issue, or the reason it cannot be shown.
 *
 * <p>{@link #issue} is present exactly when {@link #state} is
 * {@link TrackerIssueAvailability#AVAILABLE}. The 200-not-401 rule is
 * {@link TrackerIssueSearchView}'s and applies here identically.
 *
 * <p>An issue that <em>does not exist</em> and an issue the viewer <em>cannot see</em> are both
 * {@link TrackerIssueAvailability#UNAVAILABLE}, deliberately (D16): the difference is the only thing
 * a preview could disclose about a tracker the viewer has no access to, and telling them apart would
 * make this endpoint an existence oracle for it.
 */
public record TrackerIssueLookupView(TrackerIssueAvailability state, TrackerIssue issue) {

    static TrackerIssueLookupView unavailable(TrackerIssueAvailability state) {
        return new TrackerIssueLookupView(state, null);
    }
}
