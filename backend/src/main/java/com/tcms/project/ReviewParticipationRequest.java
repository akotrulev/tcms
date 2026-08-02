package com.tcms.project;

/**
 * The contract's {@code ReviewParticipationInput} — one peer's verdict on an open review.
 *
 * <p>Two fields, and the absent ones are the point. There is no {@code kind}: the endpoint can only
 * ever write {@code PEER_REVIEW}, and letting a request name the kind would be a second door to the
 * binding sign-off (see {@link ReviewService#record}). There is no {@code userId}: the participant
 * is the authenticated caller, and a field for it would let one person record another's verdict.
 * And there is no review state, because a verdict does not move the artifact — that is the ladder's
 * act, held by a different key.
 *
 * @param verdict {@code LGTM} or {@code CHANGES_REQUESTED}; anything else is a field error
 * @param comment optional. Blank and absent are the same thing here — a verdict with an empty
 *                comment is a verdict, not a comment nobody wrote — so both store null
 */
public record ReviewParticipationRequest(String verdict, String comment) {
}
