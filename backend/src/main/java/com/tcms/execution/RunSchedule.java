package com.tcms.execution;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A standing request for a run — what to ask for, and when ({@code V68}, §247).
 *
 * <h2>It is a request, not a run</h2>
 *
 * <p>Nothing here records work. The run a fire produces is an ordinary {@code test_run}, created
 * through the one door that has ever created one ({@code RunCreateService}), and deleting this row
 * destroys nothing that happened — the runs it made outlive it. That is why the table has no
 * {@code deleted_at}: a soft delete exists to keep a record of work, and there is none to keep.
 *
 * <h2>{@link #createdBy} is authority, not attribution</h2>
 *
 * <p>Every fire re-evaluates that person's {@code run:create} grant through
 * {@code ProjectService.writeGranted}, so a schedule outlives neither the grant nor the account —
 * and it is <strong>not</strong> the actor stamped on what gets written. A fire's run row and audit
 * row both carry null, because nobody performed the act at 03:00. See {@code AuditEvent#record}'s
 * own parameter doc — <em>"the authenticated caller; null only for an unattended job"</em> — and
 * {@code RunScheduleFiringService}, which is that job and the product's first.
 *
 * <h2>{@link #scopeRequest} is {@code V61}'s document and not a second encoding</h2>
 *
 * <p>Written by {@link RunScopeDocument#of} and read by {@link RunScopeRequestView#parse}, both
 * shared verbatim with {@code test_run.scope_request}. It is {@code NOT NULL} here and re-resolved
 * on every fire — a tag arm means today's cases, which is the whole point of a schedule and takes
 * nothing away from materialisation-once, a property of each run.
 *
 * <h2>{@link #zoneId} is a {@code String} on purpose</h2>
 *
 * <p>Converted at the edges by {@link CronSchedule} and nowhere else. An attribute converter would
 * be a third place a bad zone can enter the system and the only one with no field to land a 400 on.
 *
 * <h2>{@code @Version} here, unlike {@code TestRun}</h2>
 *
 * <p>{@code ApiToken}'s rubric answers the other way for this row: a schedule <em>is</em> edited
 * from a form somebody read, so a lost update here is a <strong>discarded edit</strong> — two
 * people moving the cadence from screens opened minutes apart, later write wins, nothing said. That
 * is exactly the case {@code TestRun}'s header says a counter is for, and the cost {@code TestRun}
 * refuses it over (contention on the product's most-used write) does not arise: the only writes
 * here are a person's save and one poller tick per schedule.
 */
@Entity
@Table(name = "run_schedule")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
class RunSchedule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** The schedule's label, and the prefix of every run name it produces. */
    @Column(name = "name", nullable = false)
    private String name;

    /** Six-field Spring cron. Parsed by {@link CronSchedule} and by nothing else. */
    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    /** An IANA zone id. See the class header for why this is a column and not a default. */
    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    /** {@code V61}'s document — see the class header. Never null; the write path refuses one. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_request", nullable = false)
    private String scopeRequest;

    /** Null means the project's default, exactly as an absent {@code configurationId} on a run. */
    @Column(name = "configuration_id")
    private UUID configurationId;

    /** Everybody on the created run's checklist, or null for nobody. */
    @Column(name = "assignee_id")
    private UUID assigneeId;

    /**
     * Whether the poller asks this schedule.
     *
     * <p>A disabled schedule keeps its {@link #nextFireAt} and is simply not due — pausing a
     * cadence and deleting it are different acts and only one is reversible. The partial index
     * {@code run_schedule_due_idx} is built on this predicate.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * When the poller will next ask.
     *
     * <p>Stored rather than computed per tick, and written by exactly one method —
     * {@code RunScheduleService#recomputeNextFire}. <strong>Null is a real answer</strong>: an
     * expression pinned to a date that has passed yields nothing further, and the screen says "will
     * not fire again" rather than rendering a blank that reads as "soon".
     */
    @Column(name = "next_fire_at")
    private Instant nextFireAt;

    /**
     * When a fire last produced a run.
     *
     * <p>The honest record of a gap. There is <strong>no catch-up</strong> by decision, so a
     * deployment down for a day leaves this saying when the last run really happened rather than
     * manufacturing a day of backdated ones.
     */
    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    /** The run the last successful fire created — or replayed. */
    @Column(name = "last_run_id")
    private UUID lastRunId;

    /**
     * Why the last attempt produced no run, or null after one that did.
     *
     * <p><strong>This field is the feature's honesty.</strong> A schedule that silently stops is
     * the failure mode of scheduling anything, and there is nowhere else a reader could find out.
     */
    @Column(name = "last_fire_error")
    private String lastFireError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The AUTHORITY, never the actor — see the class header. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected RunSchedule() {
    }

    /**
     * A new standing request.
     *
     * <p>{@code nextFireAt} is deliberately <em>not</em> a parameter: it is derived state and
     * {@code RunScheduleService#recomputeNextFire} is its one writer, called immediately after this
     * and again on every edit and every fire. Passing it here would be a second place that decides
     * when a schedule is next due.
     *
     * @param ownerId the caller, who becomes the authority every later fire is gated as
     */
    static RunSchedule create(
            UUID projectId,
            String name,
            String cronExpression,
            String zoneId,
            String scopeRequest,
            UUID configurationId,
            UUID assigneeId,
            boolean enabled,
            UUID ownerId,
            Instant now) {

        RunSchedule created = new RunSchedule();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.name = name;
        created.cronExpression = cronExpression;
        created.zoneId = zoneId;
        created.scopeRequest = scopeRequest;
        created.configurationId = configurationId;
        created.assigneeId = assigneeId;
        created.enabled = enabled;
        created.nextFireAt = null;
        created.lastFiredAt = null;
        created.lastRunId = null;
        created.lastFireError = null;
        created.createdAt = now;
        created.createdBy = ownerId;
        created.updatedAt = now;
        created.updatedBy = ownerId;
        return created;
    }

    /**
     * Replaces everything a caller may state — the {@code PUT}'s whole body.
     *
     * <p>A <strong>total</strong> statement, not a patch: an omitted {@code assigneeId} or
     * {@code configurationId} clears it, on the same terms as every other write in this API. One
     * mutator rather than eight setters, so the {@code updated_at}/{@code updated_by} stamp is a
     * property of the type and not a convention each call site remembers.
     *
     * <p>It does <strong>not</strong> touch {@link #nextFireAt}. The expression or the zone may
     * have moved, so the caller must follow this with {@code recomputeNextFire} — which is the one
     * writer of that column, and doing it here would make this the second.
     */
    void replace(
            String name,
            String cronExpression,
            String zoneId,
            String scopeRequest,
            UUID configurationId,
            UUID assigneeId,
            boolean enabled,
            UUID editorId,
            Instant now) {

        this.name = name;
        this.cronExpression = cronExpression;
        this.zoneId = zoneId;
        this.scopeRequest = scopeRequest;
        this.configurationId = configurationId;
        this.assigneeId = assigneeId;
        this.enabled = enabled;
        this.updatedAt = now;
        this.updatedBy = editorId;
    }

    /**
     * Moves the next due time — the one write of {@link #nextFireAt}, reached only through
     * {@code RunScheduleService#recomputeNextFire}.
     *
     * <p>No {@code updated_at} stamp, for {@code Suite#applyCompositionVersion}'s reason: this is
     * derived state moved by the clock rather than an edit somebody made, and re-stamping would
     * sort an untouched schedule to the top of "recently updated" every time it fired.
     */
    void applyNextFire(Instant nextFireAt) {
        this.nextFireAt = nextFireAt;
    }

    /**
     * Records that a fire made — or replayed — a run.
     *
     * <p>{@code lastFireError} is cleared in the same statement, because the field means <em>why
     * the last attempt produced no run</em> and this attempt produced one. Leaving a stale sentence
     * beside a fresh {@code lastRunId} is a screen saying the schedule is broken while it works.
     */
    void recordFire(UUID runId, Instant firedAt) {
        this.lastRunId = runId;
        this.lastFiredAt = firedAt;
        this.lastFireError = null;
    }

    /**
     * Records why this attempt produced no run.
     *
     * <p>{@code lastFiredAt} is deliberately <em>not</em> moved: it means "when a run last happened"
     * and no run happened. A reader compares it against the cadence to see the gap, which is the
     * whole substitute for the catch-up this feature refuses.
     */
    void recordFireFailure(String reason) {
        this.lastFireError = reason;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getName() {
        return name;
    }

    String getCronExpression() {
        return cronExpression;
    }

    String getZoneId() {
        return zoneId;
    }

    String getScopeRequest() {
        return scopeRequest;
    }

    UUID getConfigurationId() {
        return configurationId;
    }

    UUID getAssigneeId() {
        return assigneeId;
    }

    boolean isEnabled() {
        return enabled;
    }

    Instant getNextFireAt() {
        return nextFireAt;
    }

    Instant getLastFiredAt() {
        return lastFiredAt;
    }

    UUID getLastRunId() {
        return lastRunId;
    }

    String getLastFireError() {
        return lastFireError;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    UUID getUpdatedBy() {
        return updatedBy;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
