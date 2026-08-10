package com.tcms.project;

/**
 * {@code ProjectArchiveInput} in {@code docs/specs/openapi.yaml} — the body of
 * {@code PUT /projects/{projectId}/archive}.
 *
 * <p>One field, and it is <strong>the target state rather than a verb</strong>. Two endpoints
 * ({@code /archive} and {@code /unarchive}) would make the client choose which to call from a copy
 * of the state it may have read minutes ago, and the request that loses that race is the one that
 * does the opposite of what the person pressed. Naming the state makes the wrong-copy case a no-op
 * instead — {@code SuiteStatusInput}'s argument, over two values rather than four.
 *
 * @param archived boxed, so an absent field is {@code null} and a field error rather than a silent
 *                 {@code false} that quietly un-archives the project — the same reason
 *                 {@code ProjectSettingsRequest} boxes {@code requiredPeerReviews}
 */
public record ProjectArchiveRequest(Boolean archived) {
}
