package com.tcms.tracker.imports;

/**
 * Somebody who appears in the source project — as an assignee, a reporter or a comment author.
 *
 * @param sourceName the source's own display string, kept verbatim. It is what an unmatched author
 *                   is preserved as on {@code issue_comment.external_author} rather than being
 *                   reassigned to somebody who did not write the comment (D-j).
 * @param email      what the match is made on, or null when the source will not disclose one —
 *                   Jira Cloud hides it unless the account's profile is public, which is why the
 *                   report has a {@code matched} flag at all rather than an assumption
 */
public record SourcePerson(String sourceName, String email) {
}
