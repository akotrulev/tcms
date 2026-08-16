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
//
// ~~No declared query methods yet, deliberately: nothing reads a suite's composition history,
// because nothing changes a composition after v1 (see SuiteVersions).~~ → superseded in §238, which
// gave the composition its second writer and therefore this its first reader. The annotation was
// here before the method for exactly the reason stated then: a repository that acquires its first
// query method later must not also need someone to remember this.
@Transactional(readOnly = true)
public interface SuiteVersionRepository extends JpaRepository<SuiteVersion, UUID> {

    /**
     * The newest recorded composition of one suite, or empty for a suite whose v1 predates
     * {@link SuiteVersions} — {@code TestCaseVersionRepository#findNewestForCase}'s shape, and the
     * comparison {@link SuiteVersions#decide} makes.
     *
     * <p>By {@code versionNo} rather than by {@code createdAt}: two rows written in the same
     * transaction share an instant, and the number is the thing that is actually ordered. Empty is
     * a real answer and not a missing one — see {@code decide}, which reads it as "nothing to
     * compare against, so this is a change".
     *
     * <p>No new index, for {@code findNewestForCase}'s reason on the sibling table:
     * {@code uq_suite_version (suite_id, version_no)} is a b-tree whose leading column is the seek
     * key and whose second is the sort key, so this reads the last entry of one contiguous range
     * backwards. A second index would be a duplicate maintained on every write.
     */
    @Query("""
            SELECT v FROM SuiteVersion v
             WHERE v.suiteId = :suiteId
             ORDER BY v.versionNo DESC
             LIMIT 1
            """)
    Optional<SuiteVersion> findNewestForSuite(@Param("suiteId") UUID suiteId);
}
