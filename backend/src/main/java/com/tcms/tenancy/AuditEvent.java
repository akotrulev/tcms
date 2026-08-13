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
     * <p><strong>It is set beside {@link #actorUserId} and never instead of it.</strong> The caller
     * identity on a token-authenticated request is the token's <em>owner</em>, so both columns are
     * filled: the person answerable for the credential, and the credential. Writing only the token
     * would lose who is accountable; writing only the person would say a human did what a pipeline
     * did, which is the attribution this column exists to prevent.
     *
     * <p>~~Mapped and always null today — nothing authenticates by token yet.~~ → <strong>superseded
     * 2026-08-08.</strong> §116 issued the first token and §117 made CI ingest with one, so this
     * sentence stopped being true two slices before anything wrote here. {@code #record} fills it
     * from {@link TokenContext}.
     *
     * <p><strong>Written and never read — in {@code ArchitectureRulesTest.READERLESS_BY_DECISION}
     * since §188</strong>, and it spent thirty-two sections as that rule's worked example for the
     * opposite position. Its corpus included {@code src/test}, so {@code IngestionEndpointTest}'s
     * {@code SELECT actor_token_id FROM audit_event} counted as the reader; §188 narrowed the corpus
     * to the product, on the ground that a fixture selecting a column proves the write happened and
     * not that anything consults the value. Nothing does. {@link #bulkOperationId}'s argument is the
     * same one and the exit is the same screen: the audit trail has none.</p>
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

    /**
     * Set when this event is one of many from a single bulk operation.
     *
     * <p>~~Set~~ — <strong>it never is.</strong> The field, its mapping and its getter are the only
     * three mentions of it in {@code src/main} and {@code src/test} alike (§154,
     * {@code ArchitectureRulesTest.READERLESS_BY_DECISION}), so the sentence above describes a
     * condition the database has never been in. Kept because it is {@code V1}'s and because the
     * thing that would read it does not exist: the audit trail has no screen, which §152 recorded
     * from the other side when {@code AuditEventRepository}'s only reader turned out to be a test.
     */
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
     * {@link #actorTokenId} comes from {@link TokenContext} for the same reason and a stronger one:
     * there are 42 call sites, and a parameter is 42 places somebody can forget one. A forgotten
     * company would be caught by the very next assertion; a forgotten token would silently record
     * that a person did what a pipeline did, and read correct.
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
        TokenContext.ApiTokenPrincipal token = TokenContext.currentOrNull();
        event.actorTokenId = token == null ? null : token.tokenId();
        event.action = action;
        event.targetType = targetType;
        event.targetIds = targetIds.toArray(UUID[]::new);
        event.changes = changes;
        event.occurredAt = now;
        return event;
    }

    /**
     * Escapes a value into a JSON string literal, for building a {@code changes} document.
     *
     * <p>Here rather than in each module that writes one, because the failure it prevents is
     * identical everywhere and silent until the first awkward value: a suite called
     * {@code "Payments" — v2\new}, or a case title with a quote in it, concatenated raw produces a
     * malformed document in a {@code JSONB} column. The insert fails, the whole write rolls back,
     * and the error names the audit table rather than the title — so a folder with a quote in its
     * name becomes uncreatable for a reason nothing on the screen can explain.
     *
     * <p>Quotes and backslashes are the two that matter; control characters are escaped as well
     * because a pasted value can carry them.
     */
    public static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
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

    /**
     * No product surface reads this yet — the audit screen is M6 (open question #54, where
     * {@code audit:view} and {@code project:audit} still have zero consumers). It exists so the
     * writer above is assertable, which is the difference between a column that is filled and a
     * column somebody believes is filled.
     */
    public UUID getActorTokenId() {
        return actorTokenId;
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
