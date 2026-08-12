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
 * <h2>~~Six of the schema's properties have no component here~~ — they do, since §169</h2>
 *
 * <p>{@code externalRepo}, {@code externalPath}, {@code externalLineStart}, {@code externalLineEnd},
 * {@code externalCommitSha} and {@code externalUrl} belonged to the two {@code targetKind}s this
 * product designed and did not write, and each was pinned CONTRACT-ONLY in
 * {@code ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION} — in both directions, so that the day
 * an external target got a writer the entries would have to be deleted for the build to pass.
 * <strong>That is what happened</strong>: {@code ExternalLinkService} writes both kinds and all six
 * entries are gone. The pin did the job it was put there for, which is worth recording because the
 * same map has now also produced the opposite outcome three times — an argument outliving the
 * obstacle it named (§168).
 *
 * <p>A block link carries nulls in all six and an external link carries nulls in the four page and
 * block properties. That is a partitioned record rather than a half-empty one, and
 * {@link #targetKind} is what says which half is populated — carried for exactly this since
 * {@code V46}, when it could only ever hold one value.
 *
 * @param targetKind ~~always {@code DOC_BLOCK} today~~ → one of the three since §169, and it is
 *              what tells an external link apart from a block link. Carried rather than assumed
 *              from {@code V46} onward, a section before anything could produce a second value.
 * @param relationship {@code VERIFIES}, {@code DERIVED_FROM} or {@code REFERENCES} — a String and
 *              not an enum, because the value set is {@code documentation}'s
 *              ({@code LinkRelationship}) and a second declaration of it in a module forbidden to
 *              import the first would drift silently. {@link CaseDocLink} carries the same argument
 *              at the column.
 * @param blockExcerpt the first 160 characters of the linked block's text, ellipsised when cut, or
 *              null when the block has no words. Bounded in the statement that mirrors it rather
 *              than here, so the stored row is the excerpt and two renders of it cannot differ.
 * @param externalCommitSha mandatory for {@code EXTERNAL_GIT}, which is what makes that kind worth
 *              having: an unpinned range points at different code after the next push (#26, §6m).
 * @param stale the linked page's content changed after this case's content last changed. Always
 *              false for an external target — {@code V47} §3. Computed
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
        String externalRepo,
        String externalPath,
        Integer externalLineStart,
        Integer externalLineEnd,
        String externalCommitSha,
        String externalUrl,
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
                row.getExternalRepo(),
                row.getExternalPath(),
                row.getExternalLineStart(),
                row.getExternalLineEnd(),
                row.getExternalCommitSha(),
                row.getExternalUrl(),
                row.isStale());
    }
}
