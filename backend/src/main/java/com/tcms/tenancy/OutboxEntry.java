package com.tcms.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing that happened, recorded for delivery to whoever is listening — the transactional
 * outbox's row, and the first code ever to touch a table {@code V1} shipped in M0 (decision round 3
 * #52).
 *
 * <h2>What the row is for, and why it is not just a queue</h2>
 *
 * <p>{@code V1}: <em>"A failing Slack webhook must never roll back a test result."</em> The
 * mechanism is the whole point of the pattern: this row is written <strong>inside the producer's own
 * transaction</strong>, so the fact and the notice of it commit together or neither does. There is
 * no window in which the work happened and the notice was lost, and none in which a notice went out
 * for work that rolled back — which is exactly the pair a "publish after commit" call site cannot
 * get right, because there is no such thing as after-commit-and-guaranteed.
 *
 * <h2>It is also the only way two feature modules may exchange a shape</h2>
 *
 * <p>Not a nicety of this design — a consequence of plan §3, checked by
 * {@code ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation}. That rule reads
 * <em>every</em> dependency out of a module, with no exemption for an event type, so
 * {@code reporting} may not import an event class published by {@code execution}. The obvious Spring
 * shape — a typed event and a {@code @TransactionalEventListener} — is therefore unbuildable between
 * two feature modules in this codebase. Here {@code event_type} is a {@code TEXT} column and
 * {@code payload} is {@code JSONB}: nothing crosses the boundary but a string and some bytes, and
 * the compiler cannot be asked to check a contract the architecture forbids it to see. What checks
 * it instead is a test on each side of the payload, which is the trade being made rather than one
 * being overlooked.
 *
 * <h2>Why {@code tenancy}</h2>
 *
 * <p>{@link AuditEvent}'s argument, unchanged: every module will write here, so the entity cannot
 * live in whichever module writes first without making every other module depend on that one for an
 * unrelated reason. {@code tenancy} is depended on by everything and depends on nothing.
 *
 * <h2>{@code next_attempt_at} is never null, though the column allows it</h2>
 *
 * <p>{@code V1} leaves it nullable and {@link #pending} always fills it with the creation instant.
 * That is deliberate and it is about the index: {@code outbox_pending_idx} is
 * {@code ON (next_attempt_at) WHERE status = 'PENDING'}, so "due now" is one comparison against an
 * indexed column rather than a comparison plus an {@code IS NULL} branch that has to be spelled
 * identically in every query anybody writes later. A nullable column that is never null is a
 * migration away from being tightened, and nothing depends on the looseness.
 *
 * <h2>Mutation is four named transitions and no setters</h2>
 *
 * <p>{@link #claim}, {@link #succeeded}, {@link #retryAt} and {@link #stopped}. A row's status and
 * its attempt count move together or not at all — an attempt that increments the counter without
 * moving the schedule is a busy loop, and a schedule that moves without the counter is a row that
 * can never exhaust. Keeping both inside one method is what makes those two states unreachable
 * rather than merely unwritten.
 */
@Entity
@Table(name = "outbox")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class OutboxEntry {

    /**
     * How much of a failure's message is kept.
     *
     * <p>{@code last_error} is {@code TEXT} and Postgres would happily store a megabyte of stack
     * trace per row, in a table nothing prunes, for a value whose only reader is a person deciding
     * whether to replay. The first line and a bit is what answers that; the rest is in the log,
     * where a stack trace belongs and where it is correlated with everything else that happened at
     * that instant.
     */
    static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * What kind of thing this is about — {@code run}, {@code case}, {@code plan}. A stable lowercase
     * noun, never a display string and never a Java class name: a class name is a refactor away from
     * changing the meaning of rows already written.
     */
    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    /**
     * The thing itself, when there is one. Nullable in {@code V1} and left nullable: a company-level
     * event belongs to no aggregate, and inventing an id for it would make "which run was this?"
     * answerable with a lie.
     */
    @Column(name = "aggregate_id", updatable = false)
    private UUID aggregateId;

    /**
     * A stable dotted verb — {@code execution.result_recorded}. {@link AuditEvent#getAction()}'s
     * convention, and the same reason: it is matched by string against
     * {@link OutboxHandler#eventType()}, so it is a wire format and renaming it is a migration.
     */
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /**
     * The event's body as a JSON document.
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition}, for
     * {@link InstanceAuditEvent#getChanges()}'s reason — the difference shows up only on the first
     * insert.
     *
     * <p><strong>Must not carry a credential.</strong> This is a row at rest that an operator
     * browses and a backup copies, and it outlives the request that wrote it by design. The
     * invitation email is the example that made the rule concrete and is documented where it is
     * refused: {@code InvitationMailer}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    /**
     * How many times delivery has been <em>started</em>, not how many times it has failed.
     *
     * <p>Incremented by {@link #claim} rather than by a failure, which is what makes a payload that
     * kills the process eventually stop rather than being retried for ever. A counter incremented on
     * the failure path is only ever reached by failures the process survives.
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected OutboxEntry() {
    }

    /**
     * A new event, due immediately, in the active company.
     *
     * <p>{@code companyId} comes from {@link CompanyContext}, never from a parameter — for
     * {@link AuditEvent#record}'s reason, and with one more consequence here: the dispatcher
     * re-establishes a handler's company from this column, so a row filed against the wrong company
     * does not merely sit in the wrong list, it hands another client's context to a handler.
     */
    static OutboxEntry pending(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload,
            Instant now) {

        OutboxEntry entry = new OutboxEntry();
        entry.id = UUID.randomUUID();
        entry.companyId = CompanyContext.require();
        entry.aggregateType = aggregateType;
        entry.aggregateId = aggregateId;
        entry.eventType = eventType;
        entry.payload = payload;
        entry.status = OutboxStatus.PENDING;
        entry.attempts = 0;
        entry.nextAttemptAt = now;
        entry.createdAt = now;
        return entry;
    }

    /**
     * Takes this row for one delivery attempt: counts the attempt and pushes the next one out by the
     * lease.
     *
     * <p><strong>The lease is what makes a crash cost one interval instead of a row.</strong> The
     * status stays {@code PENDING} throughout the attempt, so if the process dies between claiming
     * and settling — a restart, an OOM, a pulled cable — the row simply becomes due again when the
     * lease expires and is retried. The alternative, a {@code SENDING} status, is a state only a
     * live process can leave, and every one of those rows is stuck for ever after a crash with
     * nothing to notice it.
     *
     * <p>It is also why delivery is <strong>at least once</strong>: a handler that succeeded and
     * then lost the settle will run again. Every handler must therefore be idempotent, which
     * {@link OutboxHandler} states as its contract rather than leaving to be inferred from here.
     */
    void claim(Instant now, Duration lease) {
        this.attempts = attempts + 1;
        this.nextAttemptAt = now.plus(lease);
    }

    /** Delivered. */
    void succeeded() {
        this.status = OutboxStatus.SENT;
        this.lastError = null;
    }

    /** Failed, and worth another go at {@code nextAttemptAt}. Stays {@code PENDING} by design. */
    void retryAt(Instant nextAttemptAt, String error) {
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
    }

    /**
     * Done trying, one way or the other.
     *
     * @param status {@link OutboxStatus#FAILED} when the ceiling was reached against something that
     *               might stop happening, {@link OutboxStatus#DEAD} when retrying cannot help
     */
    void stopped(OutboxStatus status, String error) {
        this.status = status;
        this.nextAttemptAt = null;
        this.lastError = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    /** The envelope a handler receives — the row's identity is the dispatcher's business, not its. */
    OutboxMessage toMessage() {
        return new OutboxMessage(companyId, aggregateType, aggregateId, eventType, payload);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
