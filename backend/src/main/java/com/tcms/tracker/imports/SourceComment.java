package com.tcms.tracker.imports;

import java.time.Instant;

/**
 * One comment on a source issue.
 *
 * @param authorName  the source's own author string. When it matches no {@code app_user} by email
 *                    it is kept as {@code issue_comment.external_author} — text, not a reassignment
 *                    to somebody who did not write it, and not a prefix on the body, which would
 *                    edit somebody's words to carry metadata (D-j).
 * @param authorEmail null where the source will not disclose one
 * @param body        the comment as the source rendered it
 * @param createdAt   the source's timestamp, kept: a comment thread re-dated to the import is a
 *                    thread nobody can read in order
 */
public record SourceComment(String authorName, String authorEmail, String body, Instant createdAt) {
}
