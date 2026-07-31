package com.tcms.repository;

import java.util.List;
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

    /**
     * Every authored version of one case, <strong>newest first</strong>.
     *
     * <h2>The order is the contract's, and it is not the storage order</h2>
     *
     * <p>{@code openapi.yaml} says "Versions, newest first" and this clause is the only thing that
     * makes that true. Rows arrive in insertion order from any plan that does not sort, which is
     * oldest-first — the exact reverse — and the panel that renders them puts the current version at
     * the top. Reversed, every reader would take the <em>first</em> version of a case for its
     * current one, which is the same claim §27's condition-order defect made about a resolution
     * order and just as unnoticeable: five plausible rows in a plausible sequence.
     *
     * <p>Sorted on {@code version_no} rather than on {@code created_at}. They agree today because
     * versions are written one at a time by one transaction, and they are not the same statement: two
     * rows created in the same millisecond order arbitrarily by timestamp, and {@code version_no} is
     * the value the reader is looking at.
     *
     * <h2>No new index, for the same reason {@link #findNewestForCase} needed none</h2>
     *
     * <p>{@code uq_case_version (case_id, version_no)} is a b-tree whose leading column is the seek
     * key and whose second column is the sort key, so this reads one contiguous range backwards. V9
     * and V10 both had to add an index because their tables had nothing to seek on at all; this one
     * already has exactly the index this access path wants, and adding a second would be a duplicate
     * maintained on every write.
     *
     * <h2>Unpaged, deliberately</h2>
     *
     * <p>The list is bounded by how many times a human has saved one case. Plan §6j's retention
     * prunes unpinned versions, so it is bounded from above as well. Paging it would put a page
     * cursor on the contract for a list whose realistic maximum is tens — and the panel that reads it
     * needs the whole thing to say "v2 is the earliest recorded".
     */
    @Query("""
            SELECT v FROM TestCaseVersion v
             WHERE v.caseId = :caseId
             ORDER BY v.versionNo DESC
            """)
    List<TestCaseVersion> findForCase(@Param("caseId") UUID caseId);
}
