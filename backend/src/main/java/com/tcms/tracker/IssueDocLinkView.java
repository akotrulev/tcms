package com.tcms.tracker;

import java.util.UUID;

/**
 * The contract's {@code IssueDocLink} — one link from this issue to a specification block, as the
 * epic screen renders it.
 *
 * <p>{@code blockExcerpt} is <strong>what was linked, not what the block says now</strong>, which is
 * the entire point of {@code stale}: story H1 says an epic linked to a spec block should say so when
 * the spec moves underneath it, and re-confirming replaces the excerpt with the current text and
 * clears the flag.
 *
 * <p>Read-only. The write is {@code documentation}'s own link endpoint with
 * {@code sourceType: ISSUE}; this module owns the mirror and never the source (D-e).
 */
public record IssueDocLinkView(
        UUID id,
        String targetKind,
        String relationship,
        UUID pageId,
        String pageKey,
        String pageTitle,
        UUID blockId,
        String blockExcerpt,
        boolean stale,
        String externalUrl) {

    static IssueDocLinkView of(IssueDocLink link) {
        return new IssueDocLinkView(
                link.getId(),
                link.getTargetKind(),
                link.getRelationship(),
                link.getPageId(),
                link.getPageKey(),
                link.getPageTitle(),
                link.getBlockId(),
                link.getBlockExcerpt(),
                link.isStale(),
                link.getExternalUrl());
    }
}
