package com.tcms.tracker;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code IssueComment}.
 *
 * <p>{@code editedAt} is null on every row today — editing a comment is not built, and the field is
 * on the wire so the thread's shape does not change on the day it is. See {@link IssueComment}.
 */
public record CommentView(
        UUID id,
        String body,
        UserRef author,
        Instant createdAt,
        Instant editedAt) {
}
