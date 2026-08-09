package com.tcms.project;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One thread, as the contract's {@code ReviewComment} — a root and its replies.
 *
 * <p><strong>Nested rather than flat, and the nesting is the contract's job rather than the
 * client's.</strong> The rows come out of the database flat, with a {@code parentId}; assembling
 * them is one rule ("a reply hangs under the root it names, in the order both were written") and
 * serving the flat list would make every consumer implement it — which is how §68's
 * {@code buildValueTree} found that one implementation dropped orphans and another surfaced them.
 * There is exactly one level, so the recursion this shape usually implies does not exist:
 * {@code replies} is a list of {@link ReviewCommentView} whose own {@code replies} is always empty,
 * and {@link ReviewCommentEdit} is what keeps that true.
 *
 * @param author     who said it, or <strong>null</strong> when the account no longer resolves —
 *                   {@link ReviewParticipationView}'s rule, and for its reason
 * @param resolvedAt null while the thread is open. Always null on a reply: resolution is a property
 *                   of the thread, so half a conversation cannot be marked dealt with
 * @param resolvedBy who marked it dealt with ({@code V24}'s column), null while open and null when
 *                   that account no longer resolves. The pair with {@code resolvedAt} is what an
 *                   auditor reads, which is the whole reason the column was added
 */
public record ReviewCommentView(
        UUID id,
        UserRef author,
        String body,
        Instant createdAt,
        Instant resolvedAt,
        UserRef resolvedBy,
        List<ReviewCommentView> replies) {
}
