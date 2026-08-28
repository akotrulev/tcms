package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
//
// readOnly, like CaseHealthRepository and unlike CaseFlakeRepository: nothing authored ever reaches
// this table. DocumentationFacts replaces it in native SQL, and a write path through here would be
// a second author of a mirrored value (see CaseDocLink's header).
@Transactional(readOnly = true)
public interface CaseDocLinkRepository extends JpaRepository<CaseDocLink, UUID> {

    /**
     * The links of a batch of cases, in <strong>one</strong> query.
     *
     * <p>{@link CaseHealthRepository#findForCases}' shape and its caller's: {@code CaseBulkService}
     * renders up to 500 cases in one operation, so the per-case spelling of this is 500 queries on
     * an operation that already had four bulk lookups.
     *
     * <p>Absent means <em>no link</em> here, and not "not computed" — which is the one thing this
     * table cannot distinguish, argued at {@code V46} §5. Ordered so that two renders of one case
     * agree: the version snapshot is this same render serialized, and an unstable order would make
     * a case's recorded history differ from itself with nothing having changed.
     *
     * <p><strong>{@code targetKind} leads and {@code id} closes, both since §169.</strong> The
     * original {@code pageKey, blockId} was total while every row was a block link and stopped being
     * so the moment external targets got a writer: both columns are null on those rows, so the whole
     * external half of a case's panel was left in whatever order a plan produced — the instability
     * this clause exists to prevent, arriving through the clause itself. Leading with the kind also
     * groups the panel the way it reads (specifications, then code, then links out), which falls out
     * of the enum's own alphabet rather than being imposed by a {@code CASE}.
     *
     * <h2>{@code EXTERNAL_ISSUE} rows are excluded, and it is not a rendering preference</h2>
     *
     * <p>{@code V75} added a fourth target kind and the mirror copies it, because the case detail's
     * Links panel needs the provider, host and id to render a degraded chip. This query feeds a
     * different reader — {@code TestCase.relatedDocuments} — whose {@code DocLink.targetKind} in the
     * contract is the closed set {@code [DOC_BLOCK, EXTERNAL_GIT, EXTERNAL_URL]}. Serving a fourth
     * value through it would put a member no generated client can name onto the wire, silently, on
     * every case that has ever been linked to an issue.
     *
     * <p>The tracker links are served by {@code GET …/cases/{caseId}/issue-links} instead, which is
     * their own shape for their own reason: they carry an id and a host and never a title, and their
     * previews are fetched live per viewer. An issue link is an <em>annotation</em>, not a
     * traceability edge (004 D13) — the same distinction {@code SpecLinkage} draws one predicate
     * over — so the two populations are genuinely different and not one list somebody split.
     */
    @Query("""
            SELECT l FROM CaseDocLink l
             WHERE l.caseId IN :caseIds
               AND l.targetKind <> 'EXTERNAL_ISSUE'
             ORDER BY l.targetKind, l.pageKey, l.blockId, l.externalPath, l.externalUrl, l.id
            """)
    List<CaseDocLink> findForCases(@Param("caseIds") Collection<UUID> caseIds);
}
