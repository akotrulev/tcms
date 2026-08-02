package com.tcms.project;

import java.util.UUID;

/**
 * The contract's {@code ReviewCommentInput} — one remark on an open review.
 *
 * <p>Two fields, and the absent ones carry the same argument {@link ReviewParticipationRequest}'s
 * header makes. There is no {@code userId}: the author is the authenticated caller, and a field for
 * it would let one person write in another's name into the record kept to show who said what. There
 * is no {@code verdict}: a comment is not a peer verdict — §6g's three tiers put commenting
 * <em>below</em> review, and a request that could carry both would let the commenting tier record
 * an LGTM.
 *
 * <p>There is also no {@code anchorType} / {@code anchorId}, and that absence is a decision rather
 * than an omission — {@link ReviewComment}'s header says what accepting one would cost and what it
 * would need first.
 *
 * @param body     the remark. Blank is a field error rather than a silently-dropped write: a
 *                 comment with no text is not a comment, and answering 201 to one would put an
 *                 empty entry in the trail
 * @param parentId the thread root this replies to, or null to start a thread. Validated against
 *                 <em>this review's</em> comments — see {@link ReviewCommentEdit}
 */
public record ReviewCommentRequest(String body, UUID parentId) {
}
