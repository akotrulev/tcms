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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;

/**
 * One pass over a plan's scope, in one configuration (ERD §5).
 *
 * <p>Creatable since M2 slice 2, through {@link #create} and nothing else. <strong>§72 is that
 * separate slice</strong>: until it landed, {@code status} had been written exactly once in the
 * product's life — to {@code NOT_STARTED}, by the factory below — so every run on every real
 * instance stayed "not started" after every one of its results had been recorded, and
 * {@code started_at} and {@code completed_at} were columns nothing ever filled. Two writes are the
 * whole lifecycle: the automatic one on the first result — {@link TestRunRepository#startIfNotStarted}
 * since §155, a mutator here until then — and {@link #applyStatus}, deliberate and behind
 * {@code run:close}. Which moves are legal is {@link RunLifecycle}'s, not this class's.
 *
 * <p>{@code release_sort_key} <strong>is now mapped</strong>, and §38's reason for leaving it out is
 * the reason it arrives here: it is <em>"computed on write from release_version"</em>, and this is
 * the first slice that performs such a write. {@link #releaseSortKey} is never set independently —
 * {@link #create} derives it from {@link #releaseVersion} through {@link ReleaseSortKey} in the one
 * statement that sets either — so the two cannot come to disagree, which is the failure §6q calls
 * silent: "1.10" sorts before "1.9" and every latest-release query is quietly wrong.
 *
 * <h2>Two writers, overlapping on one column — and each order loses something different (§155)</h2>
 *
 * <p>This is the only aggregate in the product with <strong>two concurrent writers that are not
 * the same act</strong>, which ERD §5 says is the ordinary case rather than the race: a run is
 * worked by several testers at once. A tester's first result <em>starts</em> the run
 * ({@code status}, {@code started_at}); a lead's {@code PUT /status} <em>ends</em> it
 * ({@code status}, {@code completed_at}). They overlap on {@code status} alone, and a full-row
 * {@code UPDATE} from either side therefore discards the other's column:
 *
 * <ul>
 *   <li><strong>Start after close.</strong> The tester's transaction read {@code NOT_STARTED}, the
 *       lead committed {@code COMPLETED}, and the tester's write puts the run back to
 *       {@code IN_PROGRESS} with {@code completed_at} cleared — <em>a run closure erased, with no
 *       error anywhere</em>. Closed by {@link TestRunRepository#startIfNotStarted}, which re-reads
 *       the predicate under the row lock rather than trusting the snapshot.</li>
 *   <li><strong>Close after start.</strong> The mirror: the lead's entity was loaded before the
 *       first result landed, so its {@code started_at} is still null and a full-row write nulls the
 *       column on a run that demonstrably ran. Closed by {@link DynamicUpdate} — a mutator that
 *       does not touch a column no longer writes it, so the two writers stop colliding on the
 *       columns they do not share.</li>
 * </ul>
 *
 * <h2>No {@code @Version} — and that is §155's finding, not §74's omission</h2>
 *
 * <p>{@code V1} gives this table a {@code lock_version} column and <strong>this class does not map
 * it.</strong> §74 swept the entities, annotated three, and left this one and {@code work.WorkItem}
 * mapped-but-unmaintained — a column reading {@code 0} for ever while looking like a guard, which
 * {@code TestPlan.lockVersion}'s header has recorded as latent ever since.
 * {@code ArchitectureRulesTest.everyLockVersionColumnIsAVersion} makes that state unbuildable, and
 * the way out is not automatically the annotation. {@code ApiToken}'s rubric is the one to apply:
 * is a lost update here a <em>discarded edit</em> or a <em>duplicated intent</em>?
 *
 * <p>It is neither, once the two writes above are correct. There is no {@code PATCH /runs/{runId}}
 * and no field on this row is edited from a value a client read, so nothing derives a write from a
 * stale snapshot; the lifecycle write names an absolute target, which is exactly the argument
 * {@code RunStatusService} already gives for serving no {@code If-Match} and the contract repeats on
 * three status endpoints. What {@code @Version} would add is a 409 on <strong>the most-used write in
 * the product</strong> — two testers recording their first results in the same run collide, and one
 * legitimate verdict rolls back with "someone else recorded a result first" pointing at an execution
 * that is not the one they were looking at. {@code TestPlan}'s header named that cost before this
 * slice existed. The guard the row actually needed was a predicate, not a counter.
 */
@Entity
@Table(name = "test_run")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
@DynamicUpdate
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

    /**
     * The commit this run tested — §6q's third rung of precision, and Git traceability (plan §3
     * phase 2, which the ERD annotates both columns with).
     *
     * <p><strong>{@code V1} created this column and nothing mapped it until now</strong>, which is
     * §170's finding read from the persistence side: {@code IngestionRequest} bound a {@code branch}
     * and a {@code commitSha} that no field here could receive, so a CI job that set them got a 2xx
     * and a run with no branch on it. §170 deleted the two components rather than the columns and
     * said the slice that built the feature would add them back in the commit that made them read.
     * This is that commit.
     *
     * <p>{@code updatable = false} for the reason every other identity column here carries it: the
     * run has no edit endpoint, and a coordinate that names what code ran may not be quietly moved
     * to name different code — {@code doc_link.relationship} (§174) is the one value in the product
     * that earned the opposite, and it earned it by being a <em>claim</em> rather than a coordinate.
     */
    @Column(name = "commit_sha", updatable = false)
    private String commitSha;

    /** The branch that commit came from. {@link #commitSha}'s column, and its history. */
    @Column(name = "branch", updatable = false)
    private String branch;

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
     * <p><strong>{@code source} is a PARAMETER as of M3 slice 2, and it is the caller's entry point
     * that decides it — never the caller's role.</strong> This header said so before the ingestion
     * path existed: deciding it from the role would make a Test Lead's run "manual" and an
     * Automation Service's run "ingested" while both came through the same browser-shaped request,
     * and a token holding {@code run:create} is exactly the caller for whom that would be wrong.
     * {@code RunCreateService} passes {@link RunSource#MANUAL} because its endpoint is a person with
     * a session; {@code IngestionService} passes {@link RunSource#INGESTED} because its endpoint is
     * a posted report. The distinction is which door the request came through.
     */
    static TestRun create(
            UUID projectId,
            UUID configurationId,
            String key,
            long keySeq,
            RunEdit edit,
            RunSource source,
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
        created.commitSha = edit.commitSha();
        created.branch = edit.branch();
        created.source = source;
        created.startedAt = null;
        created.completedAt = null;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.updatedAt = now;
        created.updatedBy = creatorId;
        created.createdIdempotencyKey = idempotencyKey;
        return created;
    }

    // There is deliberately no `begin()` mutator here, and §72 wrote one. It was idempotent and
    // self-guarding — `if (status != NOT_STARTED) return false` — which is the right rule and the
    // wrong place for it: the test ran against a snapshot this transaction read before a lead's
    // close committed, so the guard passed and the write then erased the closure (see the class
    // header). The rule now lives in the WHERE clause of TestRunRepository#startIfNotStarted, where
    // the database re-evaluates it under the row lock and the answer cannot be stale.

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

    String getCommitSha() {
        return commitSha;
    }

    String getBranch() {
        return branch;
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
