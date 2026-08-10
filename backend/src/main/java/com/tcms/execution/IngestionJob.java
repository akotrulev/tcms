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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One posted CI report — {@code V1}'s {@code ingestion_job}, <strong>first writer</strong>.
 *
 * <p>Along with {@code automation_key_mapping} this was one of three tables that had existed since
 * M0 with nothing able to write a row (§116 gave the third, {@code api_token}, its writer). It is
 * the record of what a CI job sent, what was understood of it, and where each test landed.
 *
 * <h2>The row is written ONCE, at the end, and is never updated</h2>
 *
 * <p>{@code status} has four values and this lane writes exactly one of them: {@code COMPLETED}.
 * That is a decision rather than an omission (slice plan decision F) and it follows from the
 * idempotency key. {@code uq_ingestion_idempotency} means the key is consumed by whichever attempt
 * writes a row — so a {@code FAILED} row for a malformed report would make the <em>corrected</em>
 * retry, under the same key the CI job generated once, replay the failure instead of ingesting.
 * A report that cannot be parsed therefore leaves no row at all, and the caller gets a field error.
 *
 * <p>{@code PENDING} and {@code PROCESSING} belong to an asynchronous lane that does not exist:
 * there, a row is written before the work and moved afterwards, and {@code FAILED} becomes reachable
 * because the key has already been consumed by the accepted request. The {@code CHECK} is left wide
 * for {@code LinkSourceType}'s reason (§93) — a constraint tightened and loosened again is one
 * nobody trusts.
 *
 * <h2>{@code raw_payload} holds what was UNDERSTOOD; the file itself is in {@code storage}</h2>
 *
 * <p>Two columns, two questions, and they are not redundant. {@code raw_payload} is the normalized
 * parse and the match outcome per test — what a human binding an unmatched name reads, and the only
 * record of <em>why</em> a test that clearly ran produced no result. {@code raw_storage_key} names
 * the verbatim upload in {@code storage.RawReportStore}, which answers the different question of
 * whether this server understood correctly what CI actually sent.
 *
 * <p><del>The verbatim report belongs in an object store, which is what {@code raw_storage_key} is
 * for and there is no adapter (§67's port argument) — so that column stays null and says so.</del>
 * <strong>→ superseded by §160.</strong> The adapter has existed since §125;
 * {@code raw_storage_key}'s own javadoc below carries what changed and what a null there means now.
 *
 * <h2>No {@code lock_version}, and the table has no such column</h2>
 *
 * <p>Nothing edits an ingestion job, so there is no lost update to lose. If a future lane moves
 * {@code status}, it is a single-writer transition by the job runner rather than two screens racing
 * — {@code report_job}'s shape, not {@code test_case}'s.
 */
@Entity
@Table(name = "ingestion_job")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IngestionJob {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * The run the results landed in. Nullable in {@code V1} and it stays that way, but this lane
     * always fills it: a completed job that matched nothing still names the run it would have used
     * only when one was given, and a job that created no run because nothing matched has none.
     */
    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, updatable = false)
    private ReportFormat format;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** See the class header — the normalized parse and its match outcomes, not the file. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;

    /**
     * Where the raw uploaded file is — <strong>the key into {@code storage.RawReportStore}</strong>,
     * and non-null on every job written since §160.
     *
     * <p><del>Nothing writes it and nothing reads it (§154's {@code READERLESS_BY_DECISION}, §156's
     * {@code WRITERLESS_BY_DECISION}); the upload cannot be retrieved. Closing it is §67's port plus
     * a read operation on the contract, not a wiring fix.</del> <strong>→ superseded by §160, which
     * did exactly that.</strong> The port arrived in §125 when {@code storage} became a foundation
     * module, so the argument this column rested on had already expired by two slices' worth of
     * commits when §154 and §156 each recorded it as a standing decision — the comment outlived what
     * made it true, which is the shape those two rules exist to find.
     *
     * <p><strong>Null means "posted before §160", not "lost".</strong> The column is nullable and
     * stays nullable: every row {@code V1} through §159 has no upload behind it and no migration can
     * invent one. {@code IngestionReadService.openRawReport} answers 404 for those, which is the
     * same answer it gives when a blob has been pruned, and deliberately so — the caller's next
     * action is identical and a second spelling would be a distinction with no act behind it.
     */
    @Column(name = "raw_storage_key")
    private String rawStorageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IngestionStatus status;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "unmatched_count", nullable = false)
    private int unmatchedCount;

    /**
     * Why a {@code FAILED} job failed — and <strong>nothing writes it</strong> (§156,
     * {@code ArchitectureRulesTest.WRITERLESS_BY_DECISION}), because nothing writes {@code FAILED}.
     *
     * <p>That is the class header's decision rather than a gap, and it is the one member of this
     * rule's population whose argument predates the rule by forty slices: a parse failure leaves no
     * row at all, so there is no row for this column to explain. It becomes reachable with the
     * asynchronous lane the header describes, at the same moment {@code PENDING} and
     * {@code PROCESSING} do — which is why it is mapped now rather than added then.
     */
    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected IngestionJob() {
    }

    /**
     * The one factory — a job that has already run.
     *
     * <p><strong>The id is the caller's</strong>, unlike every other factory in this module, and
     * §160 is why: {@code raw_storage_key} names a blob whose key contains this job's id, so the id
     * has to exist before the row does. The alternative was a second mutator to set the key after
     * construction, on an entity whose header says nothing edits an ingestion job — a mutation seam
     * bought to avoid a parameter.
     *
     * @param runId null when nothing matched, so no run was created or used
     * @param rawStorageKey where the verbatim upload was put — see the field
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    static IngestionJob completed(
            UUID id,
            UUID companyId,
            UUID projectId,
            UUID runId,
            ReportFormat format,
            String idempotencyKey,
            String rawPayload,
            String rawStorageKey,
            int matchedCount,
            int unmatchedCount,
            Instant now) {

        IngestionJob job = new IngestionJob();
        job.id = id;
        job.companyId = companyId;
        job.projectId = projectId;
        job.runId = runId;
        job.format = format;
        job.idempotencyKey = idempotencyKey;
        job.rawPayload = rawPayload;
        job.rawStorageKey = rawStorageKey;
        job.status = IngestionStatus.COMPLETED;
        job.matchedCount = matchedCount;
        job.unmatchedCount = unmatchedCount;
        job.createdAt = now;
        return job;
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

    ReportFormat getFormat() {
        return format;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRawPayload() {
        return rawPayload;
    }

    String getRawStorageKey() {
        return rawStorageKey;
    }

    IngestionStatus getStatus() {
        return status;
    }

    int getMatchedCount() {
        return matchedCount;
    }

    int getUnmatchedCount() {
        return unmatchedCount;
    }

    String getError() {
        return error;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
