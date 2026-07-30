package com.tcms.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An operator action that spans or targets companies — provisioning, suspension,
 * appointing a Company Admin, reaching into a client's content.
 *
 * <p><strong>Instance-level: no {@code company_id} discriminator, no RLS, no company
 * filter</strong> (ERD §0.1 exception list). {@link #companyId} names the company acted
 * <em>upon</em>; it is a target, not a tenancy discriminator, and it is nullable because
 * some operator actions target none.
 *
 * <p>Deliberately separate from the per-company {@code audit_event}. Operator access
 * <em>into</em> a company is recorded in <strong>both</strong>: here, and in that
 * client's own trail flagged as instance-operator access. Because companies are separate
 * client organisations, "has anyone outside our team looked at this?" has to have a real
 * answer the client can read for themselves (plan §6b, §6s).
 *
 * <p>Lives in {@code tenancy} rather than in {@code provisioning} because provisioning is
 * only its first writer — suspension, reinstatement and admin appointment all write here
 * too, and they will not live in the provisioning module. Every field is a plain UUID or
 * a scalar, so this costs {@code tenancy} no dependency and keeps its "depends on
 * nothing" property intact.
 *
 * <p>Append-only by construction: there is no mutator and no {@code @Version}. An audit
 * row that can be edited is not an audit row.
 */
@Entity
@Table(name = "instance_audit_event")
public class InstanceAuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The operator who acted, or {@code null} when the action had no human actor — the
     * provisioning CLI run from a deployment script is the case that exists today.
     * Nullable rather than a synthetic "system" user, because a fake actor row is
     * indistinguishable from a real one in the trail.
     */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    /** The company acted upon, where applicable. */
    @Column(name = "company_id", updatable = false)
    private UUID companyId;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    /**
     * What changed, as JSON. Kept as a {@code String} rather than mapped to a tree: it is
     * written once and read by a human, never queried field-by-field from application
     * code.
     *
     * <p><strong>{@code @JdbcTypeCode(JSON)}, not {@code columnDefinition = "jsonb"}</strong>
     * — this is the {@code AppUser.email} / {@code citext} trap in a different shape, and
     * the difference between the two fixes is the point. {@code columnDefinition} only
     * satisfies {@code ddl-auto: validate}, which compares the SQL type <em>name</em> when
     * the type codes differ; the parameter is still bound as {@code varchar}, and Postgres
     * does <em>not</em> implicitly cast {@code varchar} to {@code jsonb} on assignment the
     * way it does to {@code citext}. So the context starts, every read works, and the
     * first insert fails with "column is of type jsonb but expression is of type character
     * varying". The annotation below fixes the binding, which is the actual problem.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", updatable = false)
    private String changes;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** JPA only. */
    protected InstanceAuditEvent() {
    }

    /**
     * Records an operator action against one company.
     *
     * @param actorUserId the operator, or {@code null} for an unattended run
     * @param changes     a JSON object, or {@code null}
     */
    public static InstanceAuditEvent record(
            UUID id,
            UUID actorUserId,
            String action,
            UUID companyId,
            String targetType,
            String changes,
            Instant now) {
        InstanceAuditEvent event = new InstanceAuditEvent();
        event.id = id;
        event.actorUserId = actorUserId;
        event.action = action;
        event.companyId = companyId;
        event.targetType = targetType;
        event.changes = changes;
        event.occurredAt = now;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getChanges() {
        return changes;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
