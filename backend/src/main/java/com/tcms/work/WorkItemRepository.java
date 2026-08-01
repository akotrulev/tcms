package com.tcms.work;

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
     * A project's live tasks.
     *
     * <p>Unordered here on purpose. The board groups by column and then sorts within it by overdue,
     * priority and id — a sort that spans <em>derived and stored cards together</em> and therefore
     * cannot be expressed in this query at all. Ordering here would be a second, weaker sort that
     * the service immediately discards, and the next reader would reasonably assume it was the one
     * in effect.
     */
    @Query("SELECT w FROM WorkItem w WHERE w.projectId = :projectId AND w.deletedAt IS NULL")
    List<WorkItem> findLiveInProject(@Param("projectId") UUID projectId);

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
}
