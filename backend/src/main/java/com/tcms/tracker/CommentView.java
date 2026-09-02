package com.tcms.tracker;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code IssueComment}.
 *
 * <p>{@code editedAt} is null on every row today — editing a comment is not built, and the field is
 * on the wire so the thread's shape does not change on the day it is. See {@link IssueComment}.
 *
 * @param author         <strong>nullable, and it always was</strong>. {@code created_by} has no
 *                       foreign key and no cascade, so an account offboarded since is a comment
 *                       nobody is named for — and naming somebody would attribute what they did not
 *                       say. The contract declared it required and non-nullable until plan 014 T4,
 *                       which is a shape this server has never served: {@code CommentService} has
 *                       returned null here since the comment endpoint landed. D-j is what forced
 *                       the two into agreement rather than what created the disagreement.
 * @param externalAuthor the source system's own author string for an imported comment whose author
 *                       matched nobody here (D-j). <strong>Never non-null at the same time as
 *                       {@code author}</strong> — {@link IssueComment#imported} is the one writer
 *                       and the entity's field comment carries the invariant.
 */
public record CommentView(
        UUID id,
        String body,
        UserRef author,
        String externalAuthor,
        Instant createdAt,
        Instant editedAt) {
}
