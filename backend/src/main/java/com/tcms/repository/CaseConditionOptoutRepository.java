package com.tcms.repository;

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
@Transactional(readOnly = true)
public interface CaseConditionOptoutRepository
        extends JpaRepository<CaseConditionOptout, CaseConditionOptoutId> {

    /**
     * The inherited attachments this case has declined — usually none.
     *
     * <p>Queried unconditionally rather than only when the case has inherited conditions, because
     * the answer is needed before the inherited set can be filtered and the two are one round trip
     * either way. An empty result is the ordinary case and costs an index seek on the primary key.
     */
    @Query("""
            SELECT o FROM CaseConditionOptout o
             WHERE o.caseId = :caseId
            """)
    List<CaseConditionOptout> findForCase(@Param("caseId") UUID caseId);

    /**
     * The same answer for a whole selection, in one query — {@code CaseBulkService}'s shape for
     * every per-case lookup it does, and it is here for that class's stated reason rather than for
     * speed alone: {@link CaseEdit} carries the declines, so a bulk-reconstructed edit built
     * without them hashes as though the case declined nothing, and the next ordinary save would be
     * credited with restoring decisions a {@code MOVE_TO_SUITE} never touched.
     *
     * <p>Ordered so the reconstruction is deterministic. It is sorted again before it is hashed —
     * the set has no order of its own — but an unordered query feeding a sort is a fact worth not
     * relying on twice.
     */
    @Query("""
            SELECT o FROM CaseConditionOptout o
             WHERE o.caseId IN :caseIds
             ORDER BY o.caseId, o.suiteConditionId
            """)
    List<CaseConditionOptout> findForCases(@Param("caseIds") List<UUID> caseIds);
}
