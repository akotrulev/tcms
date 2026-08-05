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
public interface SuiteCaseRepository extends JpaRepository<SuiteCase, SuiteCaseId> {

    /**
     * The cases added individually to one suite — what the write path reconciles against and what
     * the suite's own read serves.
     *
     * <p>Ordered by case id rather than by {@code added_at}: the table is a set with no authored
     * order, and ordering by the timestamp would make two rows added in one transaction come back
     * in an order the database chose.
     */
    @Query("""
            SELECT m FROM SuiteCase m
             WHERE m.suiteId = :suiteId
             ORDER BY m.caseId
            """)
    List<SuiteCase> findForSuite(@Param("suiteId") UUID suiteId);

    /**
     * The same for a page of suites, in one query — the tree renders every node at once.
     *
     * <p>The caller must not pass an empty collection: an empty {@code IN ()} is a SQL syntax error,
     * and the guard lives at the call site because "the project has no suites" is a state it
     * already has to branch on. {@link CaseTagRepository#findForCases} carries the same note.
     */
    @Query("""
            SELECT m FROM SuiteCase m
             WHERE m.suiteId IN :suiteIds
             ORDER BY m.caseId
            """)
    List<SuiteCase> findForSuites(@Param("suiteIds") Collection<UUID> suiteIds);
}
