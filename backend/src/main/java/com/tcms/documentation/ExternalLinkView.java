package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code ExternalLink} in {@code docs/specs/openapi.yaml} — one {@code doc_link} whose target is a
 * repository range or a URL, as the endpoint that just wrote it answers.
 *
 * <p><strong>Three records now serve one table and none of them is redundant.</strong>
 * {@link BlockLinkView} is a link seen from the block end (it names the case, because its reader is
 * looking at the statement); {@code repository.DocLinkView} is a link seen from the case end,
 * assembled off the {@code case_doc_link} mirror because a request path in that module may not read
 * this one's tables; this is the create's answer, and it exists because neither of the other two
 * can be it. The block view has no external columns and never will — a link with no block does not
 * render on a page — and the case view lives in a module {@code documentation} may not import.
 *
 * <p>What it deliberately does <em>not</em> carry is the case's key and title. The caller named the
 * case in the path, so echoing it back is the request talking to itself (§163's argument against
 * {@code instance}); the fields here are the ones the server decided — the id it minted and the
 * relationship it defaulted.
 *
 * @param targetKind {@code EXTERNAL_GIT} or {@code EXTERNAL_URL}, never {@code DOC_BLOCK}: this
 *                   record is only ever built by {@link ExternalLinkService}, and a block link
 *                   reaching here would mean the two write paths had been merged.
 */
public record ExternalLinkView(
        UUID id,
        String targetKind,
        String relationship,
        String externalRepo,
        String externalPath,
        Integer externalLineStart,
        Integer externalLineEnd,
        String externalCommitSha,
        String externalUrl) {

    static ExternalLinkView of(DocLink link) {
        return new ExternalLinkView(
                link.getId(),
                link.getTargetKind(),
                link.getRelationship().name(),
                link.getExternalRepo(),
                link.getExternalPath(),
                link.getExternalLineStart(),
                link.getExternalLineEnd(),
                link.getExternalCommitSha(),
                link.getExternalUrl());
    }
}
