package com.tcms.project;

/**
 * The contract's {@code ReviewCommentResolutionInput} — whether a thread is dealt with.
 *
 * <p><strong>A {@code PUT} with a required boolean, not a {@code PATCH} and not two verbs.</strong>
 * §69's reason, from the side that applies here: for a boolean, an absent field and {@code false}
 * are the same bytes, so a {@code PATCH} could never express "re-open this" — the request that
 * means it is indistinguishable from the request that means nothing. Two endpoints
 * ({@code /resolve}, {@code /unresolve}) would express it and would put one act in two places, which
 * is what {@code Team.rename}/{@code reparent} became in §71.
 *
 * <p>The target is absolute, so there is no {@code If-Match} — {@code RunStatusService}'s call, and
 * for its reason: nothing here is derived from a stale read, so there is no lost update to protect
 * against. Setting a thread to the state it is already in is a 200 that writes nothing.
 *
 * @param resolved required. Null is a field error rather than "leave it alone", because a request
 *                 body that names no state has not asked for anything
 */
public record ReviewCommentResolutionRequest(Boolean resolved) {
}
