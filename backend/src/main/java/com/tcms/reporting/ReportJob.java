package com.tcms.reporting;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Filter;
import org.hibernate.type.SqlTypes;

/**
 * One generation request, in flight or finished — {@code V27}'s {@code report_job}, first writer.
 *
 * <p>This is the row §6e's {@code 202} names. It exists because the work outlives the request: an
 * {@code @Async} dispatch is lost on restart, leaving a client polling a job id that never resolves,
 * with no symptom anywhere but a spinner.
 *
 * <h2>The state machine is four values and it only moves forwards</h2>
 *
 * <pre>
 *   PENDING --claim--&gt; RUNNING --complete--&gt; COMPLETED
 *                              \--fail-----&gt; FAILED
 * </pre>
 *
 * <p>There is no path back to {@code PENDING}: a re-request is a <em>new row</em>, so the record of
 * what was asked for and what it produced stays one request per row. Retrying in place would make
 * {@code error} the error of the last attempt only, and {@code attempts} the only surviving trace of
 * a report that failed four times before it worked.
 *
 * <h2>{@code artifactId} is set at the same moment as {@code COMPLETED}, and after the bytes</h2>
 *
 * <p>{@link #complete} takes both ids and the status together for that reason: a status written
 * before the artifact reference is a job the client believes is downloadable, holding a
 * {@code downloadUrl} the contract declares and the server cannot serve. See
 * {@code ReportJobRunner} for why the bytes are written before either.
 */
@Entity
@Table(name = "report_job")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ReportJob {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null ⇒ ad-hoc. Set ⇒ the saved definition (R5) this run came from. */
    @Column(name = "definition_id", updatable = false)
    private UUID definitionId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, updatable = false)
    private ReportFormat format;

    /**
     * What was asked for, verbatim, including the aggregation policy.
     *
     * <p>A JSON <em>string</em> rather than a typed object, for {@code TestCaseVersion#snapshot}'s
     * reason: the parameter set differs per report type and grows with each one, so a Java type here
     * would be a second definition of every report's inputs that has to move whenever the first
     * does. {@code @JdbcTypeCode(JSON)} rather than plain text so a malformed value cannot commit —
     * and because Postgres will not implicitly cast {@code varchar} to {@code jsonb}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false, updatable = false)
    private String parameters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReportJobStatus status;

    @Column(name = "artifact_id")
    private UUID artifactId;

    @Column(name = "model_artifact_id")
    private UUID modelArtifactId;

    @Column(name = "error")
    private String error;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Nullable since §66: an offboarded account does not un-request a report. */
    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** JPA only. */
    protected ReportJob() {
    }

    static ReportJob request(
            UUID companyId,
            UUID projectId,
            UUID definitionId,
            String type,
            ReportFormat format,
            String parameters,
            UUID userId,
            Instant now) {

        ReportJob job = new ReportJob();
        job.id = UUID.randomUUID();
        job.companyId = companyId;
        job.projectId = projectId;
        job.definitionId = definitionId;
        job.type = type;
        job.format = format;
        job.parameters = parameters;
        job.status = ReportJobStatus.PENDING;
        job.attempts = 0;
        job.requestedBy = userId;
        job.requestedAt = now;
        return job;
    }

    /**
     * Take the job. Increments {@link #attempts} and stamps {@link #startedAt}.
     *
     * <p>{@code attempts} moves on the claim rather than on failure, so a job that dies with the
     * process mid-run — the one failure that writes nothing — still counts. Incrementing on failure
     * instead would leave the crash-loop case reading {@code attempts = 0} for ever, which is the
     * number nobody would investigate.
     */
    void claim(Instant now) {
        this.status = ReportJobStatus.RUNNING;
        this.attempts = this.attempts + 1;
        this.startedAt = now;
    }

    /** Done: both artifacts exist and their bytes are already in the store. */
    void complete(UUID artifactId, UUID modelArtifactId, Instant now) {
        this.status = ReportJobStatus.COMPLETED;
        this.artifactId = artifactId;
        this.modelArtifactId = modelArtifactId;
        this.finishedAt = now;
    }

    /**
     * Ended without an artifact.
     *
     * <p>{@code reason} is stored and is <strong>served to the client</strong> on the job poll, so
     * it must be a sentence about the report and never an exception's {@code toString()} — a stack
     * frame rendered in a UI is both useless to the reader and an unintended description of the
     * server's internals.
     */
    void fail(String reason, Instant now) {
        this.status = ReportJobStatus.FAILED;
        this.error = reason;
        this.finishedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getDefinitionId() {
        return definitionId;
    }

    String getType() {
        return type;
    }

    ReportFormat getFormat() {
        return format;
    }

    String getParameters() {
        return parameters;
    }

    ReportJobStatus getStatus() {
        return status;
    }

    UUID getArtifactId() {
        return artifactId;
    }

    UUID getModelArtifactId() {
        return modelArtifactId;
    }

    String getError() {
        return error;
    }

    int getAttempts() {
        return attempts;
    }

    UUID getRequestedBy() {
        return requestedBy;
    }

    Instant getRequestedAt() {
        return requestedAt;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getFinishedAt() {
        return finishedAt;
    }
}
