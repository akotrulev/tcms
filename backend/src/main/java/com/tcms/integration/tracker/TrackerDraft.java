package com.tcms.integration.tracker;

/**
 * What to create in the tracker — the client's title and body, plus whatever the binding fixes.
 *
 * <h2>The body is composed by the SPA and not here</h2>
 *
 * <p>For a failed step it carries the case key, the step, the verdict, the run, the environment and
 * a permalink back. That is a <strong>product decision about what a defect report says</strong>, and
 * it is made visibly in the client rather than in a template buried in an adapter where nobody
 * reviewing the feature would find it. The adapter's job is the wire shape, and this record is the
 * boundary between the two.
 *
 * @param issueType the tracker's own type name — read from {@code tracker_binding.config}, because
 *                  a Jira project's issue types are that project's and this product knows none of
 *                  them. Null means the adapter applies its own default.
 * @param areaPath  ADO's area path, likewise from the binding's config; ignored by the Jira
 *                  adapters, which is why it is a field here and not a per-provider draft type.
 */
public record TrackerDraft(String title, String body, String issueType, String areaPath) {
}
