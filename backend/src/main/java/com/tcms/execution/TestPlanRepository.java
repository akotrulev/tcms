package com.tcms.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans, by project.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface is not decoration and not a
 * performance hint: isolation is established in {@code CompanyAwareTransactionManager.doBegin}, and
 * Spring Data gives a transaction only to the CRUD methods <em>inherited</em> from
 * {@code SimpleJpaRepository} — never to the ones declared here. Without it these run outside any
 * transaction, {@code app.current_company} is never set, and RLS returns <strong>zero rows</strong>
 * while {@code CompanyContext} holds the right company (handoff §15). {@code RepositoryTransactionTest}
 * derives its list from the live context, so omitting this fails the build.
 */
@Transactional(readOnly = true)
public interface TestPlanRepository extends JpaRepository<TestPlan, UUID> {

    /**
     * A project's live plans, newest key first.
     *
     * <p>Ordered by {@code keySeq} descending rather than by name: a plan list is read as a
     * chronology — "what are we working to now" — and the newest plan is the answer far more often
     * than the alphabetically first one.
     */
    @Query("SELECT p FROM TestPlan p WHERE p.projectId = :projectId AND p.deletedAt IS NULL "
            + "ORDER BY p.keySeq DESC")
    List<TestPlan> findLiveInProject(@Param("projectId") UUID projectId);

    /**
     * One live plan that is in this project.
     *
     * <p>The {@code projectId} predicate is the second gate a two-segment path needs: without it a
     * plan id from a sibling project resolves through a path naming a project the caller does hold,
     * and every cross-company test still passes because the row is in the caller's own company.
     */
    @Query("SELECT p FROM TestPlan p WHERE p.projectId = :projectId AND p.id = :planId "
            + "AND p.deletedAt IS NULL")
    Optional<TestPlan> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("planId") UUID planId);
}
