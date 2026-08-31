package com.tcms.tracker;

/**
 * {@code IssueCommentInput} in {@code docs/specs/openapi.yaml} — the body of a comment.
 *
 * <p>One field, and a blank one is a field error rather than an accepted empty comment: an empty
 * bubble in a thread is indistinguishable from a rendering fault.
 */
public record IssueCommentInput(String body) {
}
