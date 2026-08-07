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
// NOT readOnly here, unlike CaseHealthRepository: this repository's owner writes the four authored
// quarantine columns through it. readOnly = true would put Hibernate in a mode where the flush is
// skipped, so the write would silently do nothing on a path where every assertion about the read is
// still true — which is the shape of defect the whole @Transactional rule exists because of.
@Transactional
public interface CaseFlakeRepository extends JpaRepository<CaseFlake, UUID> {

    /**
     * The flake rows for a page of cases, in <strong>one</strong> query.
     *
     * <p>{@link CaseHealthRepository#findForCases}' shape, and absent means the same thing: a case
     * the recompute has not reached has no row, and the caller renders it with no score rather than
     * with a zero.
     */
    @Query("""
            SELECT f FROM CaseFlake f
             WHERE f.caseId IN :caseIds
            """)
    List<CaseFlake> findForCases(@Param("caseIds") Collection<UUID> caseIds);

    /**
     * Every quarantined case in a set of projects, worst score first.
     *
     * <p>§6l's fourth derived-item generator reads this, and the set-of-projects signature is
     * {@code BoardFacts}' rule rather than this repository's preference: since §83 the board is not
     * always about one project, and a per-project overload beside this one would be a second
     * spelling for the older caller to keep in step with.
     *
     * <p><strong>The caller must not pass an empty collection.</strong> {@code IN ()} is a syntax
     * error rather than a query matching nothing — the same guard {@code BoardFacts} states at each
     * of its own grouped queries.
     *
     * <p>Nulls last, so a case quarantined before it was ever scored — the ordinary sequence, since
     * a red run is quarantined within the hour and the fact-log refresh is on a schedule — sorts
     * below cases with real evidence instead of above them.
     */
    @Query("""
            SELECT f FROM CaseFlake f
             WHERE f.projectId IN :projectIds
               AND f.quarantined = true
             ORDER BY f.flakeScore DESC NULLS LAST, f.caseId
            """)
    List<CaseFlake> findQuarantined(@Param("projectIds") Collection<UUID> projectIds);
}
