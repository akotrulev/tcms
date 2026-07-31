package com.tcms.repository;

import java.util.Optional;
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
public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    /**
     * One live case, <strong>in the project the path named</strong>.
     *
     * <h2>The {@code projectId} predicate is the point of this method</h2>
     *
     * <p>The caller has already passed the gate for {@code projectId} and for nothing else, so a
     * lookup by case id alone would answer with any case in the company — including one in a
     * sibling project the caller holds no grant on. The path would say
     * {@code /projects/{granted}/cases/{ungranted}} and the response would be the ungranted case's
     * full text. That is the whole leak, it needs no cross-company step, and every cross-company
     * test in the suite still passes while it is open: the row genuinely is in the caller's
     * company.
     *
     * <p>So the two ids are checked together and a mismatch is indistinguishable from a case that
     * does not exist — 404, no body, exactly as {@link CaseController} answers an unreachable
     * project. Anything that told them apart would make the endpoint an oracle for case ids in
     * projects the caller cannot open.
     *
     * <h2>{@code deletedAt IS NULL}, and why a deleted case is a 404 rather than a tombstone</h2>
     *
     * <p>A case is soft-deleted because past runs pin the version they executed and must keep
     * resolving what they covered (§3 lesson 1). That is a reason to keep the <em>row</em>, not a
     * reason to keep serving it as current: the detail screen is where someone edits, executes and
     * links, and offering all three for a case that has been removed from the repository is worse
     * than saying it is gone. History reaches it through the run that pinned it, which is a
     * different endpoint with a different shape.
     *
     * <p>Not {@code findById}: that resolves by primary key through the persistence context, and
     * Hibernate filters do <strong>not</strong> apply to that path. RLS would still cover it, but
     * using one layer where both are available is how a layered defence quietly becomes a single
     * point (see {@code ProjectRepository}'s header).
     */
    @Query("""
            SELECT tc FROM TestCase tc
             WHERE tc.id = :caseId
               AND tc.projectId = :projectId
               AND tc.deletedAt IS NULL
            """)
    Optional<TestCase> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("caseId") UUID caseId);
}
