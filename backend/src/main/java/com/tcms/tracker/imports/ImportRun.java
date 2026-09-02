package com.tcms.tracker.imports;

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
 * One migration off Jira or Azure DevOps, in flight or finished — <strong>the report that outlives
 * its source</strong>.
 *
 * <h2>The state machine is three values and only goes forwards</h2>
 *
 * <pre>
 *   RUNNING --complete--&gt; COMPLETED
 *           \--fail-----&gt; FAILED
 * </pre>
 *
 * <p>{@code ReportJob}'s machine minus {@code PENDING}, and minus it for a reason rather than by
 * simplification: a report is queued and claimed by a worker, where a run is dispatched by the
 * request that created it, so there is no state between "asked for" and "reading". A re-request is
 * a new row, so what was asked for and what it produced stay one request per row — and D-m refuses
 * the second request outright when the first one completed.
 *
 * <h2>There is no {@code lock_version}, and that is a decision</h2>
 *
 * <p>The row is written by one thread at two moments — {@code RUNNING} when the request accepts it,
 * then terminal when the run ends — and nothing else may touch it in between. An {@code If-Match}
 * on a row nobody else writes is a header the client would have to send to change nothing.
 * {@code ArchitectureRulesTest.everyLockVersionColumnIsAVersion} is about a column that
 * <em>exists</em>, so it has nothing to say here; the column is absent, which is why this paragraph
 * is.
 *
 * <h2>Two JSONB columns and no third table (D-g)</h2>
 *
 * <p>{@link #statusMapping} and {@link #people} both reference rows that can legitimately vanish —
 * a status is removable through the workflow screen, a user can be deactivated — and a real foreign
 * key would either cascade the report away or block the removal. The report's whole promise is that
 * it outlives what it names, so each entry captures the <em>name at run time</em> beside the id.
 * {@code @JdbcTypeCode(JSON)} rather than plain text for {@code ReportJob#parameters}' reason: a
 * malformed value cannot commit, and Postgres will not implicitly cast {@code varchar} to
 * {@code jsonb}.
 *
 * <p><strong>There is no credential column and there never was one</strong> — see
 * {@link ImportConnection} and {@code V7}'s header, which both say so at length because a column
 * absent by decision is indistinguishable from one nobody thought of.
 */
@Entity
@Table(name = "import_run")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ImportRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private ImportProvider provider;

    @Column(name = "site_url", nullable = false, updatable = false)
    private String siteUrl;

    @Column(name = "account_email", updatable = false)
    private String accountEmail;

    @Column(name = "source_project_key", nullable = false, updatable = false)
    private String sourceProjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ImportRunStatus status;

    @Column(name = "issues_imported", nullable = false)
    private int issuesImported;

    @Column(name = "releases_created", nullable = false)
    private int releasesCreated;

    @Column(name = "comments_imported", nullable = false)
    private int commentsImported;

    @Column(name = "labels_created", nullable = false)
    private int labelsCreated;

    @Column(name = "dropped_value_count", nullable = false)
    private int droppedValueCount;

    @Column(name = "label_per_sprint", nullable = false)
    private boolean labelPerSprint;

    @Column(name = "backlog_order_preserved", nullable = false)
    private boolean backlogOrderPreserved;

    /** A JSON array of {@code AppliedStatusMappingView}. See the class header for why it is one. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "status_mapping", nullable = false)
    private String statusMapping;

    /** A JSON array of {@code ImportedPersonView}. Same argument as {@link #statusMapping}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "people", nullable = false)
    private String people;

    /**
     * Why it failed, written for the person watching.
     *
     * <p>Never an exception's {@code toString()} — {@code report_job.error}'s rule, and the same
     * reason: a stack frame rendered on a report screen is useless to the reader and an unintended
     * description of the server's internals.
     */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** Nullable for {@code report_job.requested_by}'s reason: offboarding does not un-run a run. */
    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    /** JPA only. */
    protected ImportRun() {
    }

    /**
     * The row the {@code 202} returns — {@code RUNNING}, with every count at zero.
     *
     * <p>Written and committed <strong>before</strong> the source is dialled, which is what makes
     * the operation pollable at all: a run that recorded itself only on success would leave a
     * client polling an id that does not exist yet, and a run interrupted by a restart would leave
     * no trace for {@link ImportReaper} to find.
     */
    static ImportRun start(
            UUID id,
            UUID companyId,
            UUID projectId,
            ImportConnection connection,
            String sourceProjectKey,
            ImportOptions options,
            UUID startedBy,
            String idempotencyKey,
            Instant now) {

        ImportRun run = new ImportRun();
        run.id = id;
        run.companyId = companyId;
        run.projectId = projectId;
        run.provider = connection.provider();
        run.siteUrl = connection.siteUrl();
        run.accountEmail = connection.accountEmail();
        run.sourceProjectKey = sourceProjectKey;
        run.status = ImportRunStatus.RUNNING;
        run.labelPerSprint = options.labelPerSprint();
        run.backlogOrderPreserved = options.preserveBacklogOrder();
        run.statusMapping = "[]";
        run.people = "[]";
        run.startedAt = now;
        run.startedBy = startedBy;
        run.createdIdempotencyKey = idempotencyKey;
        return run;
    }

    /**
     * The terminal success, with the counts and both JSON documents written together.
     *
     * <p>One call rather than a setter each, for {@code ReportJob#complete}'s reason: a status
     * written before the thing it claims exists is a row a client believes and a server cannot
     * serve. Here that would be a {@code COMPLETED} report with an empty dropped-field list.
     */
    void complete(
            ImportCounts counts, String statusMappingJson, String peopleJson, Instant now) {

        this.status = ImportRunStatus.COMPLETED;
        this.issuesImported = counts.issues();
        this.releasesCreated = counts.releases();
        this.commentsImported = counts.comments();
        this.labelsCreated = counts.labels();
        this.droppedValueCount = counts.droppedValues();
        this.statusMapping = statusMappingJson;
        this.people = peopleJson;
        this.finishedAt = now;
    }

    /**
     * The terminal failure. Every count stays at zero <strong>because nothing was written</strong>
     * — the write is one transaction (D-b), so a failed run's project is byte-identical to what it
     * was before, and a non-zero count here would be the report claiming otherwise.
     */
    void fail(String reason, Instant now) {
        this.status = ImportRunStatus.FAILED;
        this.failureReason = reason;
        this.finishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public ImportProvider getProvider() {
        return provider;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public String getAccountEmail() {
        return accountEmail;
    }

    public String getSourceProjectKey() {
        return sourceProjectKey;
    }

    public ImportRunStatus getStatus() {
        return status;
    }

    public int getIssuesImported() {
        return issuesImported;
    }

    public int getReleasesCreated() {
        return releasesCreated;
    }

    public int getCommentsImported() {
        return commentsImported;
    }

    public int getLabelsCreated() {
        return labelsCreated;
    }

    public int getDroppedValueCount() {
        return droppedValueCount;
    }

    public boolean isLabelPerSprint() {
        return labelPerSprint;
    }

    public boolean isBacklogOrderPreserved() {
        return backlogOrderPreserved;
    }

    public String getStatusMapping() {
        return statusMapping;
    }

    public String getPeople() {
        return people;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getCreatedIdempotencyKey() {
        return createdIdempotencyKey;
    }
}
