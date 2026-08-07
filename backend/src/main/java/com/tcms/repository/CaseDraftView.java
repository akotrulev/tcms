package com.tcms.repository;

import java.time.Instant;

/**
 * {@code CaseDraft} in {@code docs/specs/openapi.yaml} — a buffered draft, and everything a
 * returning editor needs in order to decide whether to adopt it.
 *
 * @param lockVersion the case's {@code lockVersion} when this draft was written. Echoed rather than
 *                    kept private because a client that restores the draft has to know it is not
 *                    restoring onto the version it just fetched — see {@link CaseDraftInput}.
 * @param stale       {@code true} when the case has moved since. <strong>Computed here rather than
 *                    left to the client</strong>: the client holds both numbers and could compare
 *                    them, and that is precisely how two screens end up disagreeing about whether
 *                    the same draft is safe. One answer, from the side that reads the row.
 *                    <p>Advisory, never enforcing. A stale draft is still served in full — the words
 *                    are the author's and are often exactly what they want, particularly when the
 *                    intervening save touched a different field. Dropping it would be the product
 *                    deciding that somebody else's edit invalidates your sentence.
 * @param updatedAt   when it was last buffered. The editor shows it, because "restore unsaved
 *                    changes" means something different for work from four minutes ago and work from
 *                    four weeks ago.
 * @param updatedBy   <strong>who typed it, and this is the field that carries the 1:1 key's
 *                    cost.</strong> There is one buffer per case (see {@link TestCaseDraft}), so the
 *                    draft on offer may be a colleague's. Null when the account no longer resolves,
 *                    which {@link CaseOwners} treats as unattributed everywhere else in this module
 *                    and which reads here as "somebody, no longer in this company" — the honest
 *                    answer, and the reason it is not defaulted to the caller.
 * @param content     the buffered form state, unvalidated. Exactly the shape {@code PATCH} takes.
 */
public record CaseDraftView(
        Long lockVersion,
        boolean stale,
        Instant updatedAt,
        UserRefView updatedBy,
        CaseUpdateRequest content) {
}
