package com.tcms.integration.tracker;

import java.util.UUID;

/**
 * {@code TrackerIssueCreated} — what the tracker made, and what the follow-up needs.
 *
 * <p>Every field here is an argument to the client's next call,
 * {@code POST …/executions/{executionId}/defects}, and {@link #bindingId} is the one that matters:
 * {@code defect_link_execution_key_idx} is
 * {@code (company_id, execution_id, provider, external_key)}, so a follow-up that omits the binding
 * writes {@code EXTERNAL} where the outbox backstop writes {@code JIRA_CLOUD}, the index does not
 * collide, and the run screen shows the same defect twice with both rows looking correct. That is
 * pinned by a test rather than by this paragraph.
 *
 * @param key the key <strong>at creation</strong>. It is what {@code DefectLinkInput.externalKey}
 *            takes, which is a different decision from the issue-link table's — a defect link is
 *            keyed on the tracker's key by {@code V62}'s index and predates D17's id-only rule.
 */
public record TrackerIssueCreatedView(
        TrackerProvider provider, String id, String key, String url, UUID bindingId) {
}
