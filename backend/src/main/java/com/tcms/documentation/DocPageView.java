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
 * @param attachments never null, for {@code TestCase.attachments}' reason: unlike the {@code flake}
 *               beside that one there is no third state to express — a page with no files and a page
 *               nobody has looked at are the same page — so a nullable list would hand the SPA two
 *               spellings of nothing. Detail-only: {@link DocPageSummaryView} has no such field,
 *               because the tree renders hundreds of rows and none of them show a file.
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
        String subjectType,
        UUID subjectId,
        List<DocBlockView> blocks,
        List<DocPageAttachmentView> attachments,
        int currentVersionNo,
        long lockVersion) {

    /**
     * The just-created page.
     *
     * <p>No links and no attachments, and neither is an omission: every block was created a
     * statement ago so nothing can link to one yet, and a page cannot have a file before it has an
     * id to hang one off. Asking either collaborator here would be one guaranteed-empty query per
     * create.
     */
    static DocPageView of(DocPage page, List<DocBlock> blocks) {
        return of(page, blocks, BlockLinks.PageLinks.NONE, List.of());
    }

    /**
     * @param pageLinks   each block's links and its coverage marker, as {@link BlockLinks} assembled
     *                    them from one read. A block absent from either map has no links — served as
     *                    {@code []} — and no marker — served as {@code null}, and the difference
     *                    between those two misses is the whole point; see {@link DocBlockView}.
     * @param attachments the page's files, oldest first, as {@link PageAttachments} assembled them.
     */
    static DocPageView of(
            DocPage page,
            List<DocBlock> blocks,
            BlockLinks.PageLinks pageLinks,
            List<DocPageAttachmentView> attachments) {

        DocPageSummaryView summary = DocPageSummaryView.of(page);
        return new DocPageView(
                summary.id(),
                summary.key(),
                summary.parentId(),
                summary.title(),
                summary.slug(),
                summary.status(),
                summary.updatedAt(),
                summary.subjectType(),
                summary.subjectId(),
                blocks.stream()
                        .map(block -> DocBlockView.of(
                                block,
                                pageLinks.of(block.getId()),
                                pageLinks.markerOf(block.getId())))
                        .toList(),
                attachments,
                page.getCurrentVersionNo(),
                page.getLockVersion());
    }
}
