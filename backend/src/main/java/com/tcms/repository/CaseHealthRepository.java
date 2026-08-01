package com.tcms.repository;

import java.time.Instant;
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
@Transactional(readOnly = true)
public interface CaseHealthRepository extends JpaRepository<CaseHealth, UUID> {

    /**
     * The health rows for a page of cases, in <strong>one</strong> query.
     *
     * <p>Cases with no row are simply absent, and the caller renders them with no signals. That is
     * the state of <em>every</em> case today — nothing computes health yet — so "absent means
     * healthy-so-far" is the normal path here rather than a fallback.
     *
     * <p>The filter and the facet do not come through this method: both need a predicate over a
     * signal rather than a fetch by id, and both are built in {@link CaseSpecifications} and
     * {@link CaseFacetReader} as Criteria so the Hibernate company filter stays applied.
     */
    @Query("""
            SELECT h FROM CaseHealth h
             WHERE h.caseId IN :caseIds
            """)
    List<CaseHealth> findForCases(@Param("caseIds") Collection<UUID> caseIds);

    /**
     * The most recent moment any of this project's health was derived, or null if it never has been.
     *
     * <p>The dashboard's one job for it is telling those two apart. An empty "Needs attention" panel
     * with a timestamp means the project is clean; the same panel with nothing behind it means
     * nobody has looked, and rendering them identically is the reassuring-but-false screen. Null is
     * the answer in every deployment today, because no writer for this read model exists yet.
     *
     * <p>{@code MAX} rather than a row count, so the panel can eventually say <em>when</em> — and so
     * a project whose health is computed per case does not report a moment that belongs to one row.
     */
    @Query("""
            SELECT MAX(h.computedAt) FROM CaseHealth h
             WHERE h.projectId = :projectId
            """)
    Instant latestComputedAt(@Param("projectId") UUID projectId);
}
