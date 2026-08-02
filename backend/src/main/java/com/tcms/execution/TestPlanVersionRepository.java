package com.tcms.execution;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// TestPlanRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface TestPlanVersionRepository extends JpaRepository<TestPlanVersion, UUID> {

    /**
     * The plan's newest scope version, or empty for a plan that has none.
     *
     * <p><strong>Empty is the state of every plan in the database today</strong>, not an edge case:
     * nothing has ever written this table, and no backfill invents rows for plans created before the
     * write path existed. So {@link PlanVersions} must read absence as "no prior scope to compare
     * against" and write a version, rather than as a broken invariant.
     *
     * <p>Served by {@code uq_plan_version (plan_id, version_no)} — the unique constraint's own
     * b-tree, seeking on its leading column and reading the last entry of that range backwards. No
     * index was added, for the reason {@code repository.TestCaseVersionRepository} gives: this table
     * already has exactly the index this access path wants, and a second would be a duplicate
     * maintained on every write.
     */
    @Query("""
            SELECT v FROM TestPlanVersion v
             WHERE v.planId = :planId
             ORDER BY v.versionNo DESC
             LIMIT 1
            """)
    Optional<TestPlanVersion> findNewestForPlan(@Param("planId") UUID planId);
}
