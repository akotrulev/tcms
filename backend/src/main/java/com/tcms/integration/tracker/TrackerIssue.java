package com.tcms.integration.tracker;

import java.util.UUID;

/**
 * {@code TrackerIssue} in {@code docs/specs/openapi.yaml} — an issue <strong>as fetched this
 * request, with this viewer's credential</strong>.
 *
 * <h2>A render-time record. It is never persisted and never shared between users</h2>
 *
 * <p>004 D5/D15. Every field except {@link #id} is a fact somebody's token was needed to obtain, so
 * writing one into a column — or into a cache keyed on anything but the requesting person — serves
 * user A's view of an issue to user B, who may not be entitled to see it at all. There is no
 * {@code doc_link} column for the title, no {@code defect_link.status_cache} writer, and
 * {@link TrackerCache}'s key starts with the user id.
 *
 * @param id  the tracker's stable id. <strong>The only field here that is also stored</strong>
 *            (as {@code doc_link.external_issue_id}), because it is the only one that does not
 *            move and does not need a credential to be true.
 * @param key today's display key, {@code PAY-991}. Fetched, never stored: a Jira project move
 *            changes it, and a stored key is a label that lies rather than a link that breaks.
 * @param url the live browse URL as the tracker gives it, which for Jira is key-based and therefore
 *            fresher than the one on the link row.
 */
public record TrackerIssue(
        TrackerProvider provider,
        String id,
        String key,
        String title,
        TrackerIssueStatusCategory statusCategory,
        String statusName,
        String url,
        UUID bindingId) {
}
