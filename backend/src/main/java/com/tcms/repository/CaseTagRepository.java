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
}
