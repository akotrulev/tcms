package com.tcms.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * predicate, not a bare native query, and it belongs to the slice that builds a screen for this
     * table rather than to the first one that writes to it.
     */
    List<AuditEvent> findByActionOrderByOccurredAtDesc(String action);
}
