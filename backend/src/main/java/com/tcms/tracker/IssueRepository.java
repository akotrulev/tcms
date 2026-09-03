package com.tcms.tracker;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The issues.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the interface is not decoration.</strong>
 * Isolation is established in {@code CompanyAwareTransactionManager.doBegin}, and Spring Data gives
 * a transaction only to the CRUD methods <em>inherited</em> from {@code SimpleJpaRepository} — never
 * to the ones declared here. Without it these run outside any transaction, {@code app.current_company}
 * is never set, and RLS returns <strong>zero rows</strong> while {@code CompanyContext} holds the
 * right company. Fail-closed and silent; {@code RepositoryTransactionTest} derives its list from the
 * live context, so omitting this fails the build.
 *
 * <p>No method here takes a {@code companyId}. The company predicate is RLS's and the Hibernate
 * filter's, populated from the authenticated session — never from a parameter, a header or a path
 * segment (ERD §0.1 point 2).
 */
@Transactional(readOnly = true)
public interface IssueRepository extends JpaRepository<Issue, UUID> {

    /**
     * One live issue, by its display key.
     *
     * <p>By key rather than by id because that is what the path carries and what a person types into
     * a commit message. {@code uq_issue_key} is per company, which is the only scope RLS lets a
     * caller see into anyway, so no project predicate is needed or wanted — the gate is applied to
     * the issue's own {@code project_id} after this read, exactly as {@code PATCH /work-items/{id}}
     * does.
     */
    Optional<Issue> findByIssueKeyAndDeletedAtIsNull(String issueKey);

    /**
     * Every live issue of one project.
     *
     * <p>Unordered, for {@link #findFiltered}'s reason turned around: the board and the backlog
     * order by {@code rank, created_at, id} and then filter on values this query does not join —
     * a label, an epic key, a type's level — so the ordering is applied over the assembled views
     * and an ORDER BY here would be one the caller discards.
     */
    List<Issue> findByProjectIdAndDeletedAtIsNull(UUID projectId);

    /** An epic's stories, or a story's sub-tasks, in key order — which is {@code key_seq} order. */
    List<Issue> findByParentIdAndDeletedAtIsNullOrderByKeySeqAsc(UUID parentId);

    /** Whether anything still hangs off this issue — the 409 on {@code DELETE /issues/{key}}. */
    long countByParentIdAndDeletedAtIsNull(UUID parentId);

    /** How many live issues a status holds — the 409 on removing it from the workflow. */
    long countByStatusIdAndDeletedAtIsNull(UUID statusId);

    /**
     * Which of these keys this company already owns — the import preview's {@code KEY_COLLISION}
     * blocker (plan 014).
     *
     * <p><strong>Soft-deleted issues count.</strong> {@code uq_issue_key} is
     * {@code (company_id, issue_key)} with no {@code deleted_at} predicate, so a deleted
     * {@code PROJ-4812} still owns the key and an import that ignored it would be promising
     * something the database refuses at minute four. Every other read in this interface filters
     * deleted rows out; this one must not, and that is the reason.
     *
     * <p>Company-wide and with no project predicate, for {@link #findByIssueKeyAndDeletedAtIsNull}'s
     * reason: the constraint is per company, and RLS lets a caller see no further.
     */
    @Query("SELECT i.issueKey FROM Issue i WHERE i.issueKey IN :keys")
    List<String> findExistingKeys(@Param("keys") Collection<String> keys);

    /**
     * A replay of {@code POST /issues} with the same {@code Idempotency-Key}.
     *
     * <p>Project-scoped as well as key-scoped because {@code issue_created_idempotency_key_idx} is,
     * so a client reusing one key across two projects creates two issues rather than getting the
     * first one back for the second request.
     */
    @Query("""
            SELECT i FROM Issue i
             WHERE i.projectId = :projectId
               AND i.createdIdempotencyKey = :idempotencyKey
            """)
    Optional<Issue> findReplay(
            @Param("projectId") UUID projectId,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * One project's live issues, with every filter the work table offers.
     *
     * <p>{@code agentEligible} (D30) is switched the same way and for the neighbouring reason: the
     * parameter is a tri-state on the wire — absent, true, false — and {@code false} is a filter
     * somebody asks for, so it cannot be spelled by the absence of the flag.
     *
     * <p>The two collection filters are switched by a boolean rather than by a null collection:
     * Hibernate binds an empty {@code IN ()} as a syntax error and a null one as an unknown, so a
     * caller that sent no {@code statusId} would otherwise get either a 500 or an empty table
     * depending on which. The flag says "do not filter" out loud and the collection is then never
     * read.
     *
     * <p>Unordered here on purpose — {@code sort} spans a status's <em>name</em> and a type's, which
     * are rows this query does not join, so ordering is applied over the assembled views. A weaker
     * ORDER BY here would be one the service immediately discards and the next reader would
     * reasonably assume was in effect.
     */
    @Query("""
            SELECT i FROM Issue i
             WHERE i.projectId = :projectId
               AND i.deletedAt IS NULL
               AND (:anyLevel = true OR i.typeId IN :typeIds)
               AND (:anyParent = true OR i.parentId = :parentId)
               AND (:unparented = false OR i.parentId IS NULL)
               AND (:anyStatus = true OR i.statusId IN :statusIds)
               AND (:anyAssignee = true OR i.assigneeId IN :assigneeIds)
               AND (:anyEligibility = true OR i.agentEligible = :agentEligible)
            """)
    List<Issue> findFiltered(
            @Param("projectId") UUID projectId,
            @Param("anyEligibility") boolean anyEligibility,
            @Param("agentEligible") boolean agentEligible,
            @Param("anyLevel") boolean anyLevel,
            @Param("typeIds") Collection<UUID> typeIds,
            @Param("anyParent") boolean anyParent,
            @Param("parentId") UUID parentId,
            @Param("unparented") boolean unparented,
            @Param("anyStatus") boolean anyStatus,
            @Param("statusIds") Collection<UUID> statusIds,
            @Param("anyAssignee") boolean anyAssignee,
            @Param("assigneeIds") Collection<UUID> assigneeIds);

    /**
     * Moves every live issue off a status that is being removed.
     *
     * <p>In the same transaction as the removal, so there is no window where an issue points at a
     * status that is gone (story B1's third criterion). {@code @Modifying} plus its own
     * {@code @Transactional} because the interface's is read-only.
     *
     * <p>It deliberately does <strong>not</strong> touch {@code status_entered_at} or
     * {@code done_at}: nobody moved this work, an administrator reorganised the columns underneath
     * it, and stamping a fresh entry time would reset the cycle time of every issue in the column.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Issue i SET i.statusId = :to WHERE i.statusId = :from AND i.deletedAt IS NULL")
    int reassignStatus(@Param("from") UUID from, @Param("to") UUID to);
}
