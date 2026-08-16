package com.tcms.documentation;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code DocPageSearchHit} in {@code docs/specs/openapi.yaml} — one result of a documentation
 * search.
 *
 * <p>Flat, because {@code allOf} is flat, and the values are copied from a {@link
 * DocPageSummaryView} rather than re-derived — {@link DocPageView} states both at length and the
 * reasons are the same ones. Here the second matters more than usual: a search result and the tree
 * row beside it are the same page rendered twice on one screen, so a second assembly is a second
 * chance for them to disagree about a title.
 *
 * <p>There is no rank on the wire. The order is the answer, and {@code ts_rank_cd}'s scale is a
 * Postgres internal a client can neither compare across queries nor render.
 *
 * @param snippet the fragment of the page's text the match was found in, or null when the page has
 *                no blocks — a title-only match, which is every page created and not yet written
 *                into. Plain text with no markup: emitting {@code <mark>} would make this module
 *                responsible for escaping content it stores and renders as text everywhere else.
 */
public record DocPageSearchHitView(
        UUID id,
        String key,
        UUID parentId,
        String title,
        String slug,
        DocPageStatus status,
        Instant updatedAt,
        String subjectType,
        UUID subjectId,
        String snippet) {

    static DocPageSearchHitView of(DocPage page, String snippet) {
        DocPageSummaryView summary = DocPageSummaryView.of(page);
        return new DocPageSearchHitView(
                summary.id(),
                summary.key(),
                summary.parentId(),
                summary.title(),
                summary.slug(),
                summary.status(),
                summary.updatedAt(),
                summary.subjectType(),
                summary.subjectId(),
                snippet);
    }
}
