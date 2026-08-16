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
 * @param content     the buffered form state, unvalidated. Exactly the shape {@code PATCH} takes.
 */
public record CaseDraftView(
        Long lockVersion,
        boolean stale,
        Instant updatedAt,
        CaseUpdateRequest content) {

    // ~~updatedBy — who typed it, and the field that carried the 1:1 key's cost: there was one
    // buffer per case, so the draft on offer might be a colleague's and adopting it silently would
    // publish their half-finished sentence under your name.~~ → REMOVED with V64 (audit D1). The
    // row is keyed on the reader now, so this could only ever answer "you", and a field that
    // discloses a hazard the schema no longer has is worse than no field: it tells a returning
    // editor to weigh something that cannot happen. `stale` is what a colleague's save produces
    // instead, and it was already the field doing the work.
}
