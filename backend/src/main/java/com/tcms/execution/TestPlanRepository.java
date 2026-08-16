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
     * A project's live plans, newest key first — <strong>the working list, so archived plans are
     * not in it</strong> ({@code V65}, audit D2).
     *
     * <p>Ordered by {@code keySeq} descending rather than by name: a plan list is read as a
     * chronology — "what are we working to now" — and the newest plan is the answer far more often
     * than the alphabetically first one.
     *
     * <p>Archived plans are excluded <em>here</em> rather than filtered by the caller, so the
     * default of every reader of this method is the working list. {@link #findAllInProject} is the
     * explicit opt-in, and having to name it is the point: a screen that wants the archive says so.
     */
    @Query("SELECT p FROM TestPlan p WHERE p.projectId = :projectId AND p.deletedAt IS NULL "
            + "AND p.archivedAt IS NULL ORDER BY p.keySeq DESC")
    List<TestPlan> findLiveInProject(@Param("projectId") UUID projectId);

    /**
     * Every live plan, archived ones included — {@code ?archived=true}'s query.
     *
     * <p>Not "the archived ones". The list a person asks for when they tick "show archived" is
     * everything, because the alternative makes them read two screens to answer "which plans exist";
     * the archived rows carry {@code archivedAt} so a client can mark them.
     */
    @Query("SELECT p FROM TestPlan p WHERE p.projectId = :projectId AND p.deletedAt IS NULL "
            + "ORDER BY p.keySeq DESC")
    List<TestPlan> findAllInProject(@Param("projectId") UUID projectId);

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

    /**
     * The plan a previous request with this {@code Idempotency-Key} created, if any.
     *
     * <p><strong>The three predicates are exactly the three columns of
     * {@code test_plan_created_idempotency_key_idx}</strong>, and that is a correctness requirement
     * rather than a coincidence: a lookup narrower than the constraint finds nothing and then
     * violates the index on insert — a validated request that 500s. V14's header carries the full
     * account. {@code company_id} is supplied by the Hibernate filter and RLS rather than named
     * here, which is the third column.
     *
     * <p>Deliberately <em>not</em> filtered on {@code deletedAt}. A retry of a create whose plan has
     * since been deleted must still be recognised as that same intent — answering "no such plan,
     * here is a new one" would resurrect something somebody removed, on a request that was only ever
     * asking whether the first attempt landed.
     */
    @Query("SELECT p FROM TestPlan p WHERE p.projectId = :projectId "
            + "AND p.createdIdempotencyKey = :key")
    Optional<TestPlan> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("key") String key);
}
