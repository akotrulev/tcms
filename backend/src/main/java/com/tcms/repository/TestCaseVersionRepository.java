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
public interface TestCaseVersionRepository extends JpaRepository<TestCaseVersion, UUID> {

    /**
     * The case's newest authored version, or empty for a case that has none.
     *
     * <p><strong>Empty is the normal state of every case in the database today</strong>, not an edge
     * one: nothing has ever written this table, and no backfill invents rows for cases authored
     * before the write path existed. So {@link CaseVersions} must treat absence as "no prior
     * content to compare against" and write a version, rather than as a broken invariant.
     *
     * <p>Read to answer one question — has the content actually changed — so it fetches the newest
     * row rather than counting or scanning. The version number it carries is deliberately *not* the
     * source of the next one: {@code test_case.current_version_no} is, because that column is what
     * every reader already displays and two counters that can disagree are worse than one that can
     * be wrong.
     *
     * <p>Served by {@code uq_case_version (case_id, version_no)} — the unique constraint's own
     * b-tree, seeking on its leading column and reading the last entry of that range backwards. No
     * index was added for this query, unlike V9 and V10: those tables had nothing to seek on at all,
     * and this one already has exactly the index this access path wants.
     */
    @Query("""
            SELECT v FROM TestCaseVersion v
             WHERE v.caseId = :caseId
             ORDER BY v.versionNo DESC
             LIMIT 1
            """)
    Optional<TestCaseVersion> findNewestForCase(@Param("caseId") UUID caseId);
}
