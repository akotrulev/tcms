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
 * created (a slice that does not exist yet) and never again.
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

    /** JPA only. */
    protected TestExecution() {
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
