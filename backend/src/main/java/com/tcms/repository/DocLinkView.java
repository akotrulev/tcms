package com.tcms.repository;

import java.util.UUID;

/**
 * {@code DocLink} in {@code docs/specs/openapi.yaml} — one traceability link, seen from the case
 * end: <em>this case verifies that statement</em> (plan §6i).
 *
 * <p>Built from {@link CaseDocLink}, the read model {@code DocumentationFacts} mirrors out of
 * {@code doc_link}, and <strong>never from {@code doc_link} itself</strong>: this record is
 * assembled on a request path, and a request path in this module may not name a
 * {@code documentation} table. {@code BlockLinkView} is the same row seen from the block end, in
 * the module that owns it, and the two are deliberately not merged — each names what its own reader
 * needs, and neither module may hold the other's type.
 *
 * <h2>Six of the schema's properties have no component here, and that is pinned</h2>
 *
 * <p>{@code externalRepo}, {@code externalPath}, {@code externalLineStart}, {@code externalLineEnd},
 * {@code externalCommitSha} and {@code externalUrl} belong to the two {@code targetKind}s this
 * product does not write. {@code V1}'s {@code ck_doc_link_target} designs them, {@link
 * com.tcms.documentation.DocLink} leaves those columns <em>unmapped</em> rather than mapped as
 * permanently-null fields — "a mapped null suggests a value somebody could set" — and this record
 * makes the same call one layer up.
 *
 * <p>Each is recorded in {@code ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION} as
 * CONTRACT-ONLY, in both directions, so the day an external target gets a writer the entries have
 * to be deleted for the build to pass. That is the safe direction of the two §165 names: a declared
 * property nothing sends costs a client a dead branch, where a component nothing declares is a value
 * no generated type can see.
 *
 * @param targetKind always {@code DOC_BLOCK} today, and carried rather than assumed for the reason
 *              {@code V46} gives at the column: it is what tells an external link apart from a
 *              block link on the day the first one exists. The SPA already branches on it.
 * @param relationship {@code VERIFIES}, {@code DERIVED_FROM} or {@code REFERENCES} — a String and
 *              not an enum, because the value set is {@code documentation}'s
 *              ({@code LinkRelationship}) and a second declaration of it in a module forbidden to
 *              import the first would drift silently. {@link CaseDocLink} carries the same argument
 *              at the column.
 * @param blockExcerpt the first 160 characters of the linked block's text, ellipsised when cut, or
 *              null when the block has no words. Bounded in the statement that mirrors it rather
 *              than here, so the stored row is the excerpt and two renders of it cannot differ.
 * @param stale the linked page's content changed after this case's content last changed. Computed
 *              at refresh time and stored — see {@link CaseDocLink#isStale}, and {@code V46} §4 for
 *              why it is not compared at read time even though that would be fresher.
 */
public record DocLinkView(
        UUID id,
        String targetKind,
        String relationship,
        UUID pageId,
        String pageKey,
        String pageTitle,
        UUID blockId,
        String blockExcerpt,
        boolean stale) {

    static DocLinkView of(CaseDocLink row) {
        return new DocLinkView(
                row.getId(),
                row.getTargetKind(),
                row.getRelationship(),
                row.getPageId(),
                row.getPageKey(),
                row.getPageTitle(),
                row.getBlockId(),
                row.getBlockExcerpt(),
                row.isStale());
    }
}
