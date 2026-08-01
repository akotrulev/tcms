package com.tcms.documentation;

/**
 * {@code DocPageSummary.status} in {@code docs/specs/openapi.yaml}, and {@code doc_page.status}'s
 * {@code CHECK} constraint, spelled the same in both.
 *
 * <p>Not a lifecycle the way {@code LifecycleStatus} is: nothing computes from it, no report
 * aggregates on it, and a page's status changes only because somebody with {@code doc:publish}
 * said so. It is a publication state, which is why it moves through its own endpoint rather than
 * through the edit.
 *
 * <p>{@code ARCHIVED} is reachable and is not a delete. An archived page keeps its blocks, keeps
 * its links, and still resolves for anything pointing at it — which is the difference that matters
 * for traceability, where the whole point is that a record survives its subject going out of use.
 */
public enum DocPageStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
