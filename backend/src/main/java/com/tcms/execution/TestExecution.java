package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * The case × run instance — <strong>the row a result is recorded on</strong> (ERD §5).
 *
 * <h2>{@link #caseVersionId} is the pin, and it is {@code updatable = false} on purpose</h2>
 *
 * <p>An execution states what was tested. {@code case_version_id} names the exact version of the
 * case it ran, so editing that case afterwards cannot rewrite what a past run reported (plan
 * §2/§6j). Making it settable would give this module a way to say "actually, it ran the new text",
 * which is the one claim history exists to prevent — so the field is written when the execution is
 * created and never again. {@link #materialise} is that write, called once per case in scope when a
 * run is created; there is deliberately no setter beside it.
 *
 * <p>{@link #caseId} is immutable for the same reason and a weaker one: it is what the case-history
 * index is keyed on.
 *
 * <h2>No display key, deliberately</h2>
 *
 * <p>V1's table comment: an execution is always reached through its run or its case, nobody says
 * "TE-4038" out loud, and a per-execution counter would be the highest-volume contention point in
 * the schema for no benefit. It is identified by its UUID, or by {@code (run_id, case_id)}.
 */
@Entity
@Table(name = "test_execution")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestExecution {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * Denormalized from the run, per V1's comment: a permission-scoping shortcut that avoids a
     * two-hop join on every access check. Read here as the second gate — an execution reached
     * through a path naming another project is a 404 even when the caller may see both.
     */
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    /** <strong>The pin.</strong> See the class header. */
    @Column(name = "case_version_id", nullable = false, updatable = false)
    private UUID caseVersionId;

    /** Null ⇒ no result has been recorded yet, which is what separates submit from amend. */
    @Column(name = "status_id")
    private UUID statusId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    /** Null for an ingested result, where {@code executed_by_token} is set instead. */
    @Column(name = "executed_by")
    private UUID executedBy;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "comment")
    private String comment;

    /** Denormalized from the run — nearly every interesting query filters executions by it (§6q). */
    @Column(name = "release_version", updatable = false)
    private String releaseVersion;

    /**
     * Derived from {@link #releaseVersion} in the one statement that sets it, exactly as on
     * {@link TestRun}. {@code test_execution_release_idx} leads on this column, so leaving it null
     * while the release is set makes the release-filtered aggregate — the one every report runs —
     * skip the rows it is about.
     */
    @Column(name = "release_sort_key", updatable = false)
    private String releaseSortKey;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking, and here it is load-bearing rather than defensive: a run is worked by
     * several testers at once by design (ERD §5, resolved open question #7), so two people opening
     * the same execution is the ordinary case rather than the race.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected TestExecution() {
    }

    /**
     * One row of a new run's checklist — <strong>the write half of the pin</strong>.
     *
     * <p>{@code caseVersionId} is resolved by {@link CaseFacts#currentVersionIds} at the instant the
     * run is created and stored here. Everything the pin promises follows from that one fact: an
     * edit to the case afterwards writes a v2 and this row still names v1, so the execution screen
     * keeps answering what the tester was actually asked to do.
     *
     * <p>Nothing here has a result. {@code statusId}, {@code executedBy} and {@code executedAt} are
     * all null, which is what {@link #hasResult} reads and therefore what makes the first recording
     * a {@code result:submit} rather than a {@code result:amend}. Seeding a status — even an
     * "Untested" one from the project's vocabulary — would make every fresh execution look amended
     * and hand {@code result:amend}'s Lead-only gate to the first person who opened the run.
     *
     * <p>{@code assigneeId} may be null and that is the ordinary state: a run created without an
     * assignee is one the team picks up from, which is how most teams work. It is not a placeholder
     * for "unknown".
     *
     * @param releaseVersion denormalized from the run per V1's comment — nearly every interesting
     *                       query filters executions by release, and joining back to the run on each
     *                       is the join in every slow-query report (§6q). Copied here rather than
     *                       read through the run so that the two are set by one statement.
     */
    static TestExecution materialise(
            UUID projectId,
            UUID runId,
            UUID caseId,
            UUID caseVersionId,
            UUID assigneeId,
            String releaseVersion,
            Instant now) {

        TestExecution created = new TestExecution();
        created.id = UUID.randomUUID();
        created.companyId = com.tcms.tenancy.CompanyContext.require();
        created.projectId = projectId;
        created.runId = runId;
        created.caseId = caseId;
        created.caseVersionId = caseVersionId;
        created.assigneeId = assigneeId;
        created.releaseVersion = releaseVersion;
        created.releaseSortKey = ReleaseSortKey.of(releaseVersion);
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    /**
     * Records — or amends — the verdict.
     *
     * <p>{@code executedAt} and {@code executedBy} move to <em>this</em> recording, not to the
     * first one. An amended result is a statement by whoever amended it: leaving the original
     * author's name on a verdict someone else changed attributes a claim to a person who did not
     * make it, and the previous values are on the {@code audit_event} row where a correction
     * belongs.
     */
    void record(UUID statusId, UUID actorUserId, String comment, Instant now) {
        this.statusId = statusId;
        this.executedBy = actorUserId;
        this.executedAt = now;
        this.comment = comment;
        this.updatedAt = now;
    }

    /** True when a verdict is already on the row — the difference between submit and amend. */
    boolean hasResult() {
        return statusId != null;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getCaseId() {
        return caseId;
    }

    UUID getCaseVersionId() {
        return caseVersionId;
    }

    UUID getStatusId() {
        return statusId;
    }

    UUID getAssigneeId() {
        return assigneeId;
    }

    UUID getExecutedBy() {
        return executedBy;
    }

    Instant getExecutedAt() {
        return executedAt;
    }

    Long getDurationMs() {
        return durationMs;
    }

    String getComment() {
        return comment;
    }

    String getReleaseVersion() {
        return releaseVersion;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
