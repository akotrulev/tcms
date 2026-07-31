package com.tcms.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded action inside a company — <strong>the client's own trail, not the operator's</strong>.
 *
 * <h2>Why this lives in {@code tenancy} beside {@link InstanceAuditEvent} rather than in the module
 * that writes it</h2>
 *
 * <p>Every module will write here: a case edited, a run closed, a plan approved, a result amended,
 * a member removed. Putting the entity in whichever module happens to write first makes every other
 * module depend on that one for an unrelated reason — {@code execution} importing
 * {@code repository} to record that a run was closed. {@code tenancy} is depended on by everything
 * and depends on nothing, which is exactly the shape a cross-cutting append-only log needs.
 *
 * <p>Its instance-level sibling is a genuinely different table for the reason handoff §3 gives about
 * SYSTEM-scope grants: {@code instance_audit_event} records what the <em>operator</em> did across
 * companies and is therefore not company-scoped, and a nullable {@code company_id} under RLS would
 * be permanently unreachable. This one is company-scoped, filtered and RLS-forced like everything a
 * client owns — including from the client's own admins, who see their company's trail and no other.
 *
 * <h2>Append-only, and it says so in the absence of mutators</h2>
 *
 * <p>V1's comment: "Append-only, NEVER updated." There is no setter and no factory that takes an
 * existing row. An audit trail that can be edited is a record of what somebody was willing to leave
 * behind, which is not the same thing and is not worth storing.
 *
 * <h2>{@code targetIds} is an array, and that is a product decision</h2>
 *
 * <p>V1: one event covers a bulk operation over 143 cases, so the log reads "Ana attached 2
 * conditions to 143 cases" rather than 143 lines (plan §6f). A single-target write passes a list of
 * one, which is why there is no scalar convenience overload — the shape that scales is the only
 * shape, so the bulk endpoint has nothing to add later.
 */
@Entity
@Table(name = "audit_event")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * Nullable, for a company-level action that belongs to no project — a member invited, a
     * vocabulary changed. Not a foreign key in V1, and left as the schema has it.
     */
    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    /**
     * The API token that acted, when a CI runner did rather than a person.
     *
     * <p>Mapped and always null today — nothing authenticates by token yet. It is mapped anyway
     * because it is the column that keeps "who did this" answerable once ingestion ships: an
     * automated result attributed to no actor at all is indistinguishable from one whose actor was
     * lost, and the Automation Service role exists precisely so those results are attributable.
     */
    @Column(name = "actor_token_id", updatable = false)
    private UUID actorTokenId;

    /** A stable dotted verb, e.g. {@code case.updated}. Never a display string. */
    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Column(name = "target_ids", nullable = false, updatable = false)
    private UUID[] targetIds;

    /**
     * What changed, as JSON, or null.
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition} — see
     * {@link InstanceAuditEvent#getChanges()} for the trap that distinction avoids and why the
     * difference only shows up on the first insert.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", updatable = false)
    private String changes;

    /** Set when this event is one of many from a single bulk operation. */
    @Column(name = "bulk_operation_id", updatable = false)
    private UUID bulkOperationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** JPA only. */
    protected AuditEvent() {
    }

    /**
     * Records one action against one or more targets in the active company.
     *
     * <p>{@code companyId} comes from {@link CompanyContext}, never from a parameter — an audit row
     * filed against a company the caller nominated is an audit row in the wrong client's trail.
     *
     * @param actorUserId the authenticated caller; null only for an unattended job
     * @param changes     a JSON object, or null. Must not contain a credential, a token or a
     *                    password — this table is readable by the client's own admins.
     */
    public static AuditEvent record(
            UUID projectId,
            UUID actorUserId,
            String action,
            String targetType,
            List<UUID> targetIds,
            String changes,
            Instant now) {

        AuditEvent event = new AuditEvent();
        event.id = UUID.randomUUID();
        event.companyId = CompanyContext.require();
        event.projectId = projectId;
        event.actorUserId = actorUserId;
        event.action = action;
        event.targetType = targetType;
        event.targetIds = targetIds.toArray(UUID[]::new);
        event.changes = changes;
        event.occurredAt = now;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public List<UUID> getTargetIds() {
        return List.of(targetIds);
    }

    public String getChanges() {
        return changes;
    }

    public UUID getBulkOperationId() {
        return bulkOperationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
