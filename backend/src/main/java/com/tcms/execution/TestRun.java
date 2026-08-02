package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One pass over a plan's scope, in one configuration (ERD §5).
 *
 * <p>Creatable since M2 slice 2, through {@link #create} and nothing else. <strong>§72 is that
 * separate slice</strong>: until it landed, {@code status} had been written exactly once in the
 * product's life — to {@code NOT_STARTED}, by the factory below — so every run on every real
 * instance stayed "not started" after every one of its results had been recorded, and
 * {@code started_at} and {@code completed_at} were columns nothing ever filled. The two mutators
 * here are the whole lifecycle: {@link #begin} (automatic, on the first result) and
 * {@link #applyStatus} (deliberate, behind {@code run:close}). Which moves are legal is
 * {@link RunLifecycle}'s, not this class's.
 *
 * <p>{@code release_sort_key} <strong>is now mapped</strong>, and §38's reason for leaving it out is
 * the reason it arrives here: it is <em>"computed on write from release_version"</em>, and this is
 * the first slice that performs such a write. {@link #releaseSortKey} is never set independently —
 * {@link #create} derives it from {@link #releaseVersion} through {@link ReleaseSortKey} in the one
 * statement that sets either — so the two cannot come to disagree, which is the failure §6q calls
 * silent: "1.10" sorts before "1.9" and every latest-release query is quietly wrong.
 */
@Entity
@Table(name = "test_run")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null for an ad-hoc run — a run need not belong to a plan. */
    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    @Column(name = "configuration_id", nullable = false)
    private UUID configurationId;

    /** {@code TR-1} — sequential per COMPANY and immutable (§6t). */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RunStatus status;

    @Column(name = "release_version")
    private String releaseVersion;

    /** Distinct from {@link #releaseVersion} — see V1's column comment for what conflating costs. */
    @Column(name = "build_number")
    private String buildNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RunSource source;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Derived from {@link #releaseVersion}, never set on its own — see the class header and
     * {@link ReleaseSortKey}. {@code updatable = false} because nothing may change the release of a
     * run that has been executed against, and because a settable pair is a pair that drifts.
     */
    @Column(name = "release_sort_key", updatable = false)
    private String releaseSortKey;

    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Mapped in §72, with the first mutator that could set it. The column has been in {@code V1}
     * from the beginning and stayed null on every row, because until then nothing ever updated a
     * run — a create stamped {@code created_by} and left its pair empty.
     */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Opaque, write-once, never read as data — see {@code V14}. */
    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    /** JPA only. */
    protected TestRun() {
    }

    /**
     * A new run — {@code NOT_STARTED}, {@code MANUAL}, and not yet begun.
     *
     * <p><strong>{@code startedAt} is null and the status is NOT_STARTED</strong>, even though the
     * run's executions exist from this moment. The two are different claims: the executions are the
     * checklist, and the run starts when somebody records the first result. Stamping {@code now()}
     * here would make every abandoned run look like one that ran for months, and it would make the
     * duration of a run the time since somebody clicked "New run" in a meeting.
     *
     * <p>{@code source} is {@code MANUAL} because this endpoint is a person with a session. An
     * ingested run arrives through M3's CI path and sets {@code INGESTED} there; deciding it from
     * the caller's role instead would make a Test Lead's run "manual" and an Automation Service's
     * run "ingested" while both came through the same browser-shaped request.
     */
    static TestRun create(
            UUID projectId,
            UUID configurationId,
            String key,
            long keySeq,
            RunEdit edit,
            UUID creatorId,
            String idempotencyKey,
            Instant now) {

        TestRun created = new TestRun();
        created.id = UUID.randomUUID();
        created.companyId = com.tcms.tenancy.CompanyContext.require();
        created.projectId = projectId;
        created.planId = edit.planId();
        created.configurationId = configurationId;
        created.key = key;
        created.keySeq = keySeq;
        created.name = edit.name();
        created.status = RunStatus.NOT_STARTED;
        created.releaseVersion = edit.releaseVersion();
        // The one place either is assigned. See the field comment.
        created.releaseSortKey = ReleaseSortKey.of(edit.releaseVersion());
        created.buildNumber = edit.buildNumber();
        created.source = RunSource.MANUAL;
        created.startedAt = null;
        created.completedAt = null;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.updatedAt = now;
        created.updatedBy = creatorId;
        created.createdIdempotencyKey = idempotencyKey;
        return created;
    }

    /**
     * The run starts, because somebody recorded the first result in it.
     *
     * <p><strong>Idempotent and self-guarding</strong>: it returns false on a run that has already
     * begun, so the caller — {@link ExecutionService}, on every single result — asks unconditionally
     * and writes an audit row only when the answer is yes. A caller-side {@code if} would be the
     * same test in a second place, and the second place is where it drifts.
     *
     * <p>{@code startedAt} takes the <em>result's</em> {@code now}, not a fresh read of the clock:
     * they are the same event, and two instants a few microseconds apart would let a run's start
     * sort after the first result recorded in it.
     *
     * @return true when this call is what started the run
     */
    boolean begin(UUID actorId, Instant now) {
        if (status != RunStatus.NOT_STARTED) {
            return false;
        }
        status = RunStatus.IN_PROGRESS;
        startedAt = now;
        updatedAt = now;
        updatedBy = actorId;
        return true;
    }

    /**
     * Ends the run, or reopens it — the move {@link RunLifecycle} has already ruled legal.
     *
     * <p>This method does not re-decide anything, deliberately: a second copy of the transition
     * table inside the entity is a second answer to "may this happen", and the pair would be
     * consulted in different orders by the two callers that exist a year from now.
     *
     * <p><strong>{@code completedAt} is derived from the target, in both directions.</strong>
     * Reopening clears it, because a live run carrying an end timestamp is the same false claim
     * §42's live pass found on the other side of this lifecycle — a {@code NOT_STARTED} run
     * rendering "Still running" — and every duration this product ever computes reads that column.
     * {@code startedAt} is left alone: it records that the run <em>did</em> start, which reopening
     * does not undo.
     */
    void applyStatus(RunStatus target, UUID actorId, Instant now) {
        status = target;
        completedAt = RunLifecycle.isClosed(target) ? now : null;
        updatedAt = now;
        updatedBy = actorId;
    }

    /** Whether this run is over, in {@link RunLifecycle}'s one sense of the word. */
    boolean isClosed() {
        return RunLifecycle.isClosed(status);
    }

    UUID getCompanyId() {
        return companyId;
    }

    String getReleaseSortKey() {
        return releaseSortKey;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getConfigurationId() {
        return configurationId;
    }

    String getKey() {
        return key;
    }

    long getKeySeq() {
        return keySeq;
    }

    String getName() {
        return name;
    }

    RunStatus getStatus() {
        return status;
    }

    String getReleaseVersion() {
        return releaseVersion;
    }

    String getBuildNumber() {
        return buildNumber;
    }

    RunSource getSource() {
        return source;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    /**
     * When the run was created — <strong>null on no run</strong>, unlike {@link #getStartedAt},
     * which is null on every run until somebody records the first result (see the factory above).
     * That is what makes this the last fallback for a suite history entry's required timestamp.
     */
    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }
}
