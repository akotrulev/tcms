package com.tcms.documentation;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code DocPageSummary} in {@code docs/specs/openapi.yaml} — one row of the page tree.
 *
 * <p>Flat, and with {@code parentId} rather than nested children: the client builds the tree, which
 * is the same shape {@code SuiteView} uses and for the same reason — a nested response has to pick
 * an ordering and a depth limit, and the SPA re-sorts siblings anyway.
 *
 * <p>No block count and no author. Both would be honest and neither is on the contract; adding a
 * field the SPA does not read means a query per page for a number nobody sees.
 *
 * <p>{@code subjectType} / {@code subjectId} are null on nearly every page and are on the tree row
 * rather than only on the detail deliberately: the SPA labels a plan's document in the sidebar and
 * routes its "edit" to the plan, and a client that had to open each page to discover what it was
 * about would fetch the whole space to render one list.
 */
public record DocPageSummaryView(
        UUID id,
        String key,
        UUID parentId,
        String title,
        String slug,
        DocPageStatus status,
        Instant updatedAt,
        String subjectType,
        UUID subjectId) {

    static DocPageSummaryView of(DocPage page) {
        DocSubject subject = page.getSubject();
        return new DocPageSummaryView(
                page.getId(),
                page.getKey(),
                page.getParentId(),
                page.getTitle(),
                page.getSlug(),
                page.getStatus(),
                page.getUpdatedAt(),
                subject == null ? null : subject.typeName(),
                subject == null ? null : subject.id());
    }
}
