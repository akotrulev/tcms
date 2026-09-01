package com.tcms.tracker.release;

/**
 * The contract's {@code ReleaseStories} — the delivery half of readiness, counted over D8's set.
 *
 * <p><strong>There is no {@code blocked} count, and {@code release.html} draws one.</strong>
 * {@code IssueStatusKind} has exactly three members — {@code NOT_STARTED}, {@code IN_PROGRESS},
 * {@code DONE} (D2) — so "blocked" is a status <em>name</em> a project chose and not a class this
 * contract can count. The blockers list carries each story's real status by name, which is where a
 * reader sees the word; inventing a fourth kind to serve one mockup cell would put a vocabulary in
 * the schema that no project's workflow has to use.
 */
public record ReleaseStoriesView(
        int done, int total, int inProgress, int notStarted, int excludedSpikes) {
}
