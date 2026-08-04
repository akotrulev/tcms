package com.tcms.work;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stored tasks. Derived cards are not here, because they are not rows (see the package comment).
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
public interface WorkItemRepository extends JpaRepository<WorkItem, UUID> {

    /**
     * The live tasks in a set of projects.
     *
     * <p><strong>A set rather than one project, and there is deliberately no single-project
     * overload.</strong> §83's cross-project board reads the tasks of every project the caller can
     * see, and the project board is the one-element case — two methods would be two queries to keep
     * in step, and the one that drifts is whichever the newer board does not use (§58's E1). The
     * caller must not pass an empty collection: {@code IN ()} is a syntax error, and
     * {@code BoardService} answers an empty board before it gets here.
     *
     * <p>Unordered here on purpose. The board groups by column and then sorts within it by overdue,
     * priority and id — a sort that spans <em>derived and stored cards together</em> and therefore
     * cannot be expressed in this query at all. Ordering here would be a second, weaker sort that
     * the service immediately discards, and the next reader would reasonably assume it was the one
     * in effect.
     */
    @Query("SELECT w FROM WorkItem w WHERE w.projectId IN :projectIds AND w.deletedAt IS NULL")
    List<WorkItem> findLiveInProjects(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * One live task, anywhere in the caller's company.
     *
     * <p><strong>Not scoped by project, and that is deliberate</strong> — unlike
     * {@code TestPlanRepository.findLiveInProject(projectId, planId)}, whose second predicate exists
     * because its path names a project. {@code PATCH /work-items/{id}} has no project segment: the
     * task's own {@code project_id} is what the permission is then checked against, so a project
     * predicate here would need a project the request never supplied.
     *
     * <p>The gate is therefore in {@code BoardService}, which reads this row, takes its
     * {@code projectId}, and puts <em>that</em> through {@code ProjectService.writeGranted}. RLS has
     * already bounded this to the caller's company; the grant check bounds it to their projects.
     */
    @Query("SELECT w FROM WorkItem w WHERE w.id = :id AND w.deletedAt IS NULL")
    Optional<WorkItem> findLiveById(@Param("id") UUID id);

    /**
     * The task a previous request with this {@code Idempotency-Key} created, if any (V17).
     *
     * <p><strong>The predicates are exactly the columns of
     * {@code work_item_created_idempotency_key_idx}</strong>, and that is a correctness requirement
     * rather than a coincidence: a lookup narrower than the constraint finds nothing and then
     * violates the index on insert — a validated request that 500s. {@code company_id} is the third
     * column and is supplied by the Hibernate filter and RLS rather than named here.
     *
     * <p>Deliberately <em>not</em> filtered on {@code deletedAt}, unlike both queries above, and the
     * difference is the whole point of this one. A retry of a create whose task has since been
     * removed must still be recognised as that same intent — answering "no such task, here is a new
     * one" would resurrect something somebody deleted, on a request that was only ever asking
     * whether the first attempt landed. {@code TestPlanRepository} carries the same note; the two
     * must not drift.
     */
    @Query("SELECT w FROM WorkItem w WHERE w.projectId = :projectId "
            + "AND w.createdIdempotencyKey = :key")
    Optional<WorkItem> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("key") String key);
}
