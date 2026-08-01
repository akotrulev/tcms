package com.tcms.documentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code DocPage} in {@code docs/specs/openapi.yaml} — one whole page.
 *
 * <p>Flat, because {@code allOf} is flat: the contract declares
 * {@code allOf: [DocPageSummary, {…}]}, which is one JSON object carrying both sets of keys at the
 * top level, not a summary nested under a field. {@link com.tcms.repository.TestCaseDetailView}
 * records the two rejected alternatives at length — {@code @JsonUnwrapped}, whose failure mode is a
 * silently nested response, and a {@code Map}, which gives up the compile-time check that every
 * required field is present.
 *
 * <p>As there, the values are not re-derived: {@link #of} copies a {@link DocPageSummaryView} built
 * by the same code the tree renders, so a row in the sidebar and the page beside it cannot disagree
 * about a title or a status.
 *
 * @param blocks never null; empty is a real state and the SPA has copy for it ("This page has no
 *               content yet"). A page is created before anything is written into it.
 * @param lockVersion on the wire because the SPA sends it back as {@code If-Match}, so a concurrent
 *               edit is a 409 rather than a silent overwrite (plan §6j).
 */
public record DocPageView(
        UUID id,
        String key,
        UUID parentId,
        String title,
        String slug,
        DocPageStatus status,
        Instant updatedAt,
        List<DocBlockView> blocks,
        int currentVersionNo,
        long lockVersion) {

    static DocPageView of(DocPage page, List<DocBlock> blocks) {
        DocPageSummaryView summary = DocPageSummaryView.of(page);
        return new DocPageView(
                summary.id(),
                summary.key(),
                summary.parentId(),
                summary.title(),
                summary.slug(),
                summary.status(),
                summary.updatedAt(),
                blocks.stream().map(DocBlockView::of).toList(),
                page.getCurrentVersionNo(),
                page.getLockVersion());
    }
}
