package com.tcms.tracker;

/**
 * The body of {@code PUT /issues/{key}/watchers} — the state the caller wants, not the one they
 * think it is in.
 *
 * <p>A {@code Boolean} rather than a {@code boolean}: an absent field must be a 400 naming it, and a
 * primitive would bind it to {@code false} and quietly unfollow the issue instead.
 */
public record IssueWatchInput(Boolean watching) {
}
