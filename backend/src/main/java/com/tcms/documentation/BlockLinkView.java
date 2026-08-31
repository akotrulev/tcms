package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code BlockLink} in {@code docs/specs/openapi.yaml} — one {@code doc_link} seen from the
 * <strong>block</strong> end: what claims to verify this statement.
 *
 * <p>{@link com.tcms.repository.DocLinkView} is the same row seen from the case end, and the two are
 * deliberately not merged. Each names the thing at the <em>other</em> end and omits the thing its
 * reader already has — a case's view carries the page and the block excerpt, this one carries the
 * case — so a merged record would be half-null in both directions and would let a caller read
 * whichever field happened to be populated without noticing which end it was standing at.
 *
 * @param sourceKey   the case, suite or issue key — {@code TC-42}, {@code TS-3}, {@code ACME-124}.
 *                    Resolved through {@link CatalogueFacts}, because the rows live in
 *                    {@code repository} and {@code tracker}, neither of which this module may
 *                    import.
 * @param sourceTitle resolved from the same read. Never null: a link whose source has been deleted
 *                    is not served at all, since the read joins rather than looks up — a link to a
 *                    row nobody can name renders as a blank bullet with a checkbox.
 */
public record BlockLinkView(
        UUID id,
        String sourceType,
        UUID sourceId,
        String sourceKey,
        String sourceTitle,
        String relationship) {
}
