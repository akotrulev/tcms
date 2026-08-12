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
     */
    @Query("""
            SELECT l FROM CaseDocLink l
             WHERE l.caseId IN :caseIds
             ORDER BY l.pageKey, l.blockId
            """)
    List<CaseDocLink> findForCases(@Param("caseIds") Collection<UUID> caseIds);
}
