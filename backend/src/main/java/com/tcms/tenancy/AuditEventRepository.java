package com.tcms.tenancy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The client's own trail — company-filtered and RLS-forced, unlike
 * {@link InstanceAuditEventRepository}.
 *
 * <p>Append-only in practice as well as by intent: {@link AuditEvent} has no mutator, so the
 * {@code save} and {@code delete*} that {@code JpaRepository} contributes cannot rewrite a recorded
 * event's content. That is the same accepted cost the instance-level sibling documents, and the same
 * reason no update method is declared here.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Every recorded event with this action, newest first — the company's own, by the filter.
     *
     * <p><strong>Deliberately not "every event naming this target".</strong> The natural query is
     * containment against {@code target_ids}, which is what {@code audit_event_ids_idx} (GIN) exists
     * to serve — and HQL cannot express Postgres's {@code @>} against a basic array column at all
     * ({@code MEMBER OF} applies to mapped collections, not to a single array column, and compiles
     * to nothing). A native query would say it exactly and would drop the Hibernate company filter,
     * leaving only RLS between one client's audit trail and another's: the same constraint that kept
     * the case list's free text on {@code LIKE} rather than {@code @@}, and the same choice, on a
     * table where the cost of being wrong is highest.
     *
     * <p>So callers filter by action and match the target in memory. That is correct at today's
     * volume and will not stay correct — an activity panel over a busy company needs the index. The
     * fix is a projection through a repository fragment with its own explicit {@code company_id}
     * predicate, not a bare native query, and ~~it belongs to the slice that builds a screen for
     * this table rather than to the first one that writes to it.~~ → <strong>the screen arrived in
     * §242 and did not need it.</strong> Neither audit trail filters by target: they filter by
     * project, actor, action and date, all of which are plain column predicates the two methods
     * below express in HQL with the company filter intact. "Every act naming this case" is a
     * different feature — a per-case history panel — and it is the one that owes the fragment.
     */
    List<AuditEvent> findByActionOrderByOccurredAtDesc(String action);

    /**
     * One project's acts, newest first — the project-scoped trail.
     *
     * <p>{@code e.projectId = :projectId} and nothing else. A company-scope act carries a NULL here,
     * and widening this to {@code OR e.projectId IS NULL} would fold every member invitation and
     * vocabulary change into every project's log — the defect
     * {@code AuditLogReadTest.aCompanyScopeActIsAbsentFromEveryProjectLog} exists to catch.
     *
     * <p>The optional filters are expressed inline rather than as sixteen derived methods, for
     * {@code TestRunRepository.findLiveInProject}'s reason: the SPA sends them independently, and
     * sixteen combinations is sixteen places the {@code projectId} predicate can be forgotten.
     *
     * <h2>The two dates use {@code coalesce} and the other two use {@code IS NULL} — not a style
     * inconsistency</h2>
     *
     * <p>{@code (:value IS NULL OR column = :value)} is this repository's idiom everywhere and it
     * <strong>does not work for a temporal parameter</strong>. Hibernate infers a parameter's type
     * from where it is used; in the {@code IS NULL} position there is nothing to infer from, and for
     * {@code UUID} and {@code String} the second occurrence settles it. For an {@code Instant} it
     * does not: Hibernate falls back to binding the parameter as {@code bytea}, and Postgres refuses
     * with <em>"cannot cast type bytea to timestamp with time zone"</em>. Adding {@code CAST(:after
     * AS Instant)} does not help — the cast is applied to a value already bound as the wrong type.
     *
     * <p>{@code coalesce(:after, e.occurredAt)} puts the parameter in a typed context, so Hibernate
     * binds it as a timestamp. When it is null the predicate becomes {@code occurredAt >=
     * occurredAt}, which is true for every row because the column is {@code NOT NULL} — the same
     * "unfiltered" meaning, reached by a route the type inference can follow.
     *
     * <p><strong>Found by running it</strong>, not by reading it: the plan for this slice proposed
     * a {@code CAST}, the first implementation dropped it on the precedent above, and fourteen of
     * this class's fifteen arms failed identically. The precedent was real and its scope was
     * narrower than it looked.
     *
     * <p><strong>{@code ORDER BY e.occurredAt DESC, e.id DESC} — the secondary key is
     * load-bearing.</strong> Acts recorded in one transaction share {@code occurred_at} to the
     * microsecond, because the writers stamp them from one {@code Instant}. Without a tiebreaker
     * Postgres may order those rows differently between the {@code LIMIT/OFFSET} for page 1 and the
     * one for page 2, so a row is served twice or not at all — on an audit trail, where "not at all"
     * is the failure the table exists to prevent.
     */
    @Query(value = """
            SELECT e FROM AuditEvent e
             WHERE e.projectId = :projectId
               AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
               AND (:action IS NULL OR e.action = :action)
               AND e.occurredAt >= coalesce(:after, e.occurredAt)
               AND e.occurredAt <= coalesce(:before, e.occurredAt)
             ORDER BY e.occurredAt DESC, e.id DESC
            """,
            countQuery = """
            SELECT COUNT(e) FROM AuditEvent e
             WHERE e.projectId = :projectId
               AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
               AND (:action IS NULL OR e.action = :action)
               AND e.occurredAt >= coalesce(:after, e.occurredAt)
               AND e.occurredAt <= coalesce(:before, e.occurredAt)
            """)
    Page<AuditEvent> pageForProject(
            @Param("projectId") UUID projectId,
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("after") Instant after,
            @Param("before") Instant before,
            Pageable pageable);

    /**
     * The company's acts, newest first — project-scoped rows included, so this is a superset of
     * every project trail.
     *
     * <p><strong>No {@code company_id} predicate, deliberately.</strong> The {@link CompanyFilter} on
     * {@link AuditEvent} and the forced RLS policy behind it are what scope this, and writing one by
     * hand here would read as though the filter were optional — the impression
     * {@code ProjectRepository}'s header refuses one table over. {@code projectId} is a
     * <em>filter</em>, not a gate: an id naming no project in this company matches nothing rather
     * than refusing, so the query string cannot become an existence oracle.
     *
     * <p>The sort key is {@link #pageForProject}'s, for the reason stated there.
     */
    @Query(value = """
            SELECT e FROM AuditEvent e
             WHERE (:projectId IS NULL OR e.projectId = :projectId)
               AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
               AND (:action IS NULL OR e.action = :action)
               AND e.occurredAt >= coalesce(:after, e.occurredAt)
               AND e.occurredAt <= coalesce(:before, e.occurredAt)
             ORDER BY e.occurredAt DESC, e.id DESC
            """,
            countQuery = """
            SELECT COUNT(e) FROM AuditEvent e
             WHERE (:projectId IS NULL OR e.projectId = :projectId)
               AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
               AND (:action IS NULL OR e.action = :action)
               AND e.occurredAt >= coalesce(:after, e.occurredAt)
               AND e.occurredAt <= coalesce(:before, e.occurredAt)
            """)
    Page<AuditEvent> pageForCompany(
            @Param("projectId") UUID projectId,
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("after") Instant after,
            @Param("before") Instant before,
            Pageable pageable);
}
