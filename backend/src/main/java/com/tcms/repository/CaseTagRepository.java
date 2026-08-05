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
@Transactional(readOnly = true)
public interface CaseTagRepository extends JpaRepository<CaseTag, CaseTagId> {

    /**
     * Every tag on a page of cases, in <strong>one</strong> query.
     *
     * <p>One query for the whole page rather than one per case: at a page size of 200 the
     * alternative is 200 round trips to render one column, which is the N+1 a mapped
     * {@code @ElementCollection} would have given for free. The caller groups by case id.
     *
     * <p>The parameter is the page's case ids, never the project — so the query stays proportional
     * to what is being rendered rather than to how much the client has authored. The caller must
     * not pass an empty collection: an empty {@code IN ()} is a SQL syntax error, and the guard
     * lives at the call site because "the page is empty" is a state it already has to branch on.
     *
     * <p>Ordered by tag so a row's chips do not reshuffle between requests. There is no authored
     * order to preserve — {@code case_tag} has no position column, because tags are a set.
     */
    @Query("""
            SELECT t FROM CaseTag t
             WHERE t.caseId IN :caseIds
             ORDER BY t.tag ASC
            """)
    List<CaseTag> findForCases(@Param("caseIds") Collection<UUID> caseIds);

    /**
     * One case's tags — what the reconcile compares against.
     *
     * <p>A separate method rather than {@link #findForCases} with a singleton list, for
     * {@code CaseClassificationRepository#findForCase}'s reason: the batch method's contract is
     * "never give me an empty collection", and a save path has no page to guard on.
     *
     * <p>Unfiltered and unbounded, unlike a classification read. There is no vocabulary a tag can
     * be retired from — it is free text — so every stored tag is a candidate for removal and the
     * bound that {@code CaseClassifications} needs has nothing to be here.
     */
    @Query("""
            SELECT t FROM CaseTag t
             WHERE t.caseId = :caseId
             ORDER BY t.tag ASC
            """)
    List<CaseTag> findForCase(@Param("caseId") UUID caseId);

    /**
     * Every tag in use in one project, with how many live cases carry it.
     *
     * <p><strong>Derived, never curated</strong> — {@link StepSuggestionService}'s argument at a
     * different altitude, and the correction {@code shared_step} earned by staying empty: a tag list
     * somebody has to populate is a tag list nobody populates. A tag exists here because a case
     * carries it, so this cannot disagree with the grid.
     *
     * <p><strong>The join to {@code TestCase} is what scopes it, and it is not decoration.</strong>
     * {@code case_tag} has a {@code company_id} and no {@code project_id}, so RLS bounds this to the
     * caller's company and nothing else — without the join a client's tag vocabulary would leak
     * across every sibling project in their own instance, which is precisely the sibling-project
     * hole {@code PlanEdit}'s live-id sets exist to close, arriving on a read.
     *
     * <p>Counts are over <strong>live</strong> cases, so a tag whose every case was soft-deleted
     * disappears from the list while remaining legal to write — zero-count rows are impossible
     * rather than filtered.
     *
     * <p>Most-used first, then alphabetically. The second key is not cosmetic: the counts tie
     * constantly on a young project, and without it the picker's order would be the database's and
     * would move between two loads of one screen.
     */
    @Query("""
            SELECT t.tag, COUNT(c.id)
              FROM CaseTag t
              JOIN TestCase c ON c.id = t.caseId
             WHERE c.projectId = :projectId
               AND c.deletedAt IS NULL
             GROUP BY t.tag
             ORDER BY COUNT(c.id) DESC, t.tag ASC
            """)
    List<Object[]> findTagUsage(@Param("projectId") UUID projectId);
}
