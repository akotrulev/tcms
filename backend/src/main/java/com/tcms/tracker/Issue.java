package com.tcms.tracker;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An epic, a story, a task, a bug or a spike — <strong>one table for all three levels</strong>
 * (scope §4), with a self-referencing {@link #parentId} and an {@code issue_type} pointer.
 *
 * <p>Deliberately not Jira's shape, where a Sub-task is a second-class citizen that cannot be
 * estimated or moved independently. Here a Task is a first-class issue that happens to have a
 * parent, which is what lets a dev and a QA each hold a Task under one Story.
 *
 * <h2>{@code lock_version} IS mapped with {@code @Version}, unlike {@code work.WorkItem}</h2>
 *
 * <p>{@code WorkItem}'s argument for leaving it unmapped was that its only mutator names an absolute
 * target — a column — so a lost update duplicates an intent rather than discarding an edit. That
 * does not survive here. {@code PATCH /issues/{key}} writes a title, a description and a list of
 * acceptance criteria somebody typed over a copy they read, and a lost update there really does lose
 * the other author's words. {@code ArchitectureRulesTest.everyLockVersionColumnIsAVersion} makes the
 * half-state — a mapped column Hibernate does not maintain, which reads {@code 0} for ever and is
 * the value an {@code If-Match} would be checked against — unbuildable.
 *
 * <h2>Two columns for one key</h2>
 *
 * <p>{@link #issueKey} is stored rather than composed from {@code project.key || key_seq}: what is
 * rendered and what somebody types into a commit message must not be derived from something that can
 * move underneath it, and {@code settings-general.html} promises existing links keep working if the
 * project key changes. {@link #keySeq} is <strong>the only column that may order issue keys</strong>
 * — sorting on the string puts {@code ACME-100} before {@code ACME-2}.
 */
@Entity
@Table(name = "issue")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Issue {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "issue_key", nullable = false, updatable = false)
    private String issueKey;

    @Column(name = "type_id", nullable = false)
    private UUID typeId;

    @Column(name = "status_id", nullable = false)
    private UUID statusId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    /**
     * An ordered JSON array of {@code {id, text, done}}.
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than plain text, for {@code ReportJob#parameters}'
     * reason: a malformed value cannot commit, and Postgres will not implicitly cast
     * {@code varchar} to {@code jsonb}. The array is written whole by one form in one request, which
     * is why it is a column rather than a table — nothing joins to a criterion and nothing reports
     * on one.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptance_criteria", nullable = false)
    private String acceptanceCriteria;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority;

    /**
     * Hours, never story points (D8) — the same unit a budget and an invoice use.
     *
     * <p>{@code BigDecimal} because the column is {@code NUMERIC(8,2)} and Hibernate's schema
     * validation refuses a {@code double} against it. The wire shape is a JSON number, so the two
     * boundaries convert; the storage is exact, which is what matters the moment M6 multiplies it
     * by a rate.
     */
    @Column(name = "estimate_hours", precision = 8, scale = 2)
    private java.math.BigDecimal estimateHours;

    /** Epics only — a body of work has a span; a story has a due date. {@link IssueEdit} refuses it elsewhere. */
    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * The target release, or null — {@code issue.release_id}, a nullable FK with no join table
     * (scope D6). Not all work belongs to a version, and requiring one would force a junk release
     * per stray bug, which is the same argument §4 makes for an optional epic.
     */
    @Column(name = "release_id")
    private UUID releaseId;

    /**
     * D30 — a hint that this issue is a good candidate for an LLM, and the default filter of an
     * assistant's queue.
     *
     * <p>A boolean and a filter, <strong>never an assignment target</strong>: assignment stays
     * person to person, and an "assigned to the agent" column is the shape that makes a machine
     * answerable for work a person is answerable for.
     */
    @Column(name = "agent_eligible", nullable = false)
    private boolean agentEligible;

    /**
     * The pull request somebody — a person, or an assistant acting as one — opened for this issue,
     * reported back through {@code PATCH}.
     *
     * <p>This product opens no PR and holds no forge credential (D25). The value is a string
     * somebody sends, which is why setting it records its own audit action rather than riding on
     * {@code issue.updated}: "attached PR #482" is a thing that happened, and a generic edit row
     * says nothing a reader can act on.
     */
    @Column(name = "pr_url")
    private String prUrl;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "reporter_id")
    private UUID reporterId;

    /** When this issue entered its CURRENT status — the cheap half of cycle time (009 review R12). */
    @Column(name = "status_entered_at", nullable = false)
    private Instant statusEnteredAt;

    /**
     * When it FIRST reached a {@code DONE}-kind status, and <strong>never cleared by a reopen</strong>.
     *
     * <p>A reopened issue has been done once. A lead time computed from a cleared column silently
     * lengthens every time somebody reopens something, which is the kind of wrong number nothing
     * fails about.
     */
    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /**
     * Soft delete, and required rather than stylistic. Scope §3: a downstream {@code time_entry} or
     * invoice line holds an id into this table with no foreign key, because the module rule forbids
     * one. A hard delete orphans those silently.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    /**
     * Where this issue sits in its project's order — the fractional rank (012 D-d).
     *
     * <p><strong>Filled on insert by {@code V5}'s {@code issue_rank_default} trigger, never here</strong>
     * (D-k). A new issue takes {@code max(rank) + 1024} in its project, which a column {@code DEFAULT}
     * cannot express because it may not read another row — and assigning it on the creation path
     * instead would be a rule that holds only for the callers who remember it. The trigger stands
     * aside whenever the insert supplies a rank, which is the door {@code RankService} writes through.
     *
     * <p>{@code NUMERIC} with no precision, so a midpoint between two neighbours always exists and
     * placing one row never rewrites another. Deliberately <strong>not unique</strong>: two inserts
     * into one project in the same instant read the same maximum and tie, so every ordered read is
     * {@code ORDER BY rank, created_at, id} and never {@code ORDER BY rank} alone.
     */
    @Column(name = "rank", nullable = false)
    private java.math.BigDecimal rank;

    /** JPA only. */
    protected Issue() {
    }

    /**
     * A new issue, in the project's first status by position.
     *
     * @param key      from {@link com.tcms.project.ProjectKeys.Allocation#keyAt}
     * @param keySeq   the number behind it, from the same allocation
     * @param statusId the first status <em>by position</em> — never looked up by name
     */
    static Issue create(
            String key,
            long keySeq,
            UUID projectId,
            UUID typeId,
            UUID statusId,
            UUID parentId,
            IssueEdit edit,
            UUID reporterId,
            String idempotencyKey,
            Instant now) {

        Issue created = new Issue();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.issueKey = key;
        created.keySeq = keySeq;
        created.typeId = typeId;
        created.statusId = statusId;
        created.parentId = parentId;
        created.title = edit.title();
        created.description = edit.description();
        created.acceptanceCriteria = AcceptanceCriteria.toJson(edit.acceptanceCriteria());
        created.priority = edit.priority();
        created.estimateHours = hours(edit.estimateHours());
        created.startDate = edit.startDate();
        created.targetDate = edit.targetDate();
        created.dueDate = edit.dueDate();
        created.releaseId = edit.releaseId();
        created.assigneeId = edit.assigneeId();
        created.reporterId = reporterId;
        created.statusEnteredAt = now;
        created.createdAt = now;
        created.createdBy = reporterId;
        created.updatedAt = now;
        created.updatedBy = reporterId;
        created.createdIdempotencyKey = idempotencyKey;
        return created;
    }

    /**
     * An issue read out of somebody else's tracker, with <strong>both</strong> of its key columns
     * written by hand (plan 014 D-d, story J3).
     *
     * <p>Public because {@code tracker.imports} is the one caller and lives in a sub-package —
     * {@code RankService}'s reason for {@link #rankAt}. It is a second factory rather than an
     * argument on {@link #create} because almost nothing it does is that method's behaviour: the
     * key is the source's own string instead of the allocation's, the timestamps are the source's
     * instead of now, the reporter is somebody matched by email instead of the caller, and there is
     * no {@link IssueEdit} because there is no form and nothing to validate against one.
     *
     * <h2>The two key columns, and why they disagree on purpose</h2>
     *
     * <p>{@code issueKey} is {@code PROJ-4812} verbatim, which is what makes J3 true and what
     * somebody's commit messages and browser bookmarks still say. {@code keySeq} comes from
     * <em>this project's</em> counter through {@code ProjectKeys.allocate}. Using Jira's 4 812 here
     * would push the counter past four thousand unused values, or collide with a local
     * {@code ACME-12} if it did not — and {@code key_seq} is the only column that may order issue
     * keys anywhere in the product (see the class header).
     *
     * @param createdAt the source's own timestamp, kept: an imported backlog re-dated to the
     *                  migration is a backlog nobody can read in age order, and every cycle-time
     *                  figure computed from it would start at the import
     * @param releaseId the first of the source's fix versions, as a release here (D6). The first
     *                  and not all of them: {@code issue.release_id} is a single nullable FK with
     *                  no join table, so an issue Jira had in three versions arrives in the
     *                  earliest one it named and the rest are reachable only through the release
     *                  list — recorded here because it is a loss the dropped-field list does not
     *                  cover, the field itself having arrived.
     * @param importedBy who ran the migration — {@code created_by}, because that is the only true
     *                  answer this instance has. The source's reporter is carried separately in
     *                  {@link #reporterId} when they matched somebody here.
     */
    public static Issue imported(
            UUID companyId,
            UUID projectId,
            String key,
            long keySeq,
            UUID typeId,
            UUID statusId,
            UUID parentId,
            String title,
            String description,
            UUID releaseId,
            UUID assigneeId,
            UUID reporterId,
            UUID importedBy,
            Instant createdAt,
            Instant now) {

        Issue created = new Issue();
        created.id = UUID.randomUUID();
        created.companyId = companyId;
        created.projectId = projectId;
        created.issueKey = key;
        created.keySeq = keySeq;
        created.typeId = typeId;
        created.statusId = statusId;
        created.parentId = parentId;
        created.title = title;
        created.description = description;
        created.acceptanceCriteria = AcceptanceCriteria.toJson(List.of());
        // No source this module reads has a priority vocabulary that maps onto this one without
        // guessing, and a guessed priority is a number people plan against. MEDIUM is the value
        // IssueEdit gives an issue somebody created without choosing one.
        created.priority = Priority.MEDIUM;
        created.releaseId = releaseId;
        created.assigneeId = assigneeId;
        created.reporterId = reporterId;
        created.statusEnteredAt = createdAt;
        created.createdAt = createdAt;
        created.createdBy = importedBy;
        created.updatedAt = now;
        created.updatedBy = importedBy;
        return created;
    }

    /** The fields {@code PATCH /issues/{key}} writes. Status is not among them, by design. */
    void apply(IssueEdit edit, UUID parentId, UUID actorId, Instant now) {
        this.title = edit.title();
        this.description = edit.description();
        this.acceptanceCriteria = AcceptanceCriteria.toJson(edit.acceptanceCriteria());
        this.priority = edit.priority();
        this.estimateHours = hours(edit.estimateHours());
        this.startDate = edit.startDate();
        this.targetDate = edit.targetDate();
        this.dueDate = edit.dueDate();
        this.releaseId = edit.releaseId();
        this.assigneeId = edit.assigneeId();
        this.parentId = parentId;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    /**
     * Moves to another status.
     *
     * <p>{@link #statusEnteredAt} moves on every accepted transition; {@link #doneAt} is set the
     * first time a {@code DONE}-kind status is reached and is never cleared — see its own comment.
     */
    void moveTo(UUID targetStatusId, boolean targetIsDone, UUID actorId, Instant now) {
        this.statusId = targetStatusId;
        this.statusEnteredAt = now;
        if (targetIsDone && this.doneAt == null) {
            this.doneAt = now;
        }
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    void softDelete(UUID actorId, Instant now) {
        this.deletedAt = now;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    public boolean isAgentEligible() {
        return agentEligible;
    }

    /**
     * Set from the create form and from {@code PATCH}, and <strong>not through
     * {@link IssueEdit}</strong>: that record is the validated field set one form submits whole, and
     * a flag with no rule to validate against would be carried through it for the sake of symmetry.
     */
    void setAgentEligible(boolean eligible) {
        this.agentEligible = eligible;
    }

    public String getPrUrl() {
        return prUrl;
    }

    void setPrUrl(String url) {
        this.prUrl = url;
    }

    public UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public long getKeySeq() {
        return keySeq;
    }

    public String getIssueKey() {
        return issueKey;
    }

    public UUID getTypeId() {
        return typeId;
    }

    public UUID getStatusId() {
        return statusId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public Priority getPriority() {
        return priority;
    }

    /** The stored estimate as the contract spells it — a JSON number, or null for "not estimated". */
    public Double getEstimateHours() {
        return estimateHours == null ? null : estimateHours.doubleValue();
    }

    private static java.math.BigDecimal hours(Double submitted) {
        return submitted == null
                ? null
                : java.math.BigDecimal.valueOf(submitted)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public Instant getStatusEnteredAt() {
        return statusEnteredAt;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public String getCreatedIdempotencyKey() {
        return createdIdempotencyKey;
    }

    public java.math.BigDecimal getRank() {
        return rank;
    }

    /**
     * Places this issue at an already-computed rank.
     *
     * <p>Public because {@code tracker.board.RankService} is the one caller and lives in a
     * sub-package. It takes no actor and stamps no {@code updated_at}: reordering a backlog is not
     * an edit to any of the issues in it, and moving thirty rows would otherwise mark thirty issues
     * as touched by whoever dragged one.
     */
    public UUID getReleaseId() {
        return releaseId;
    }

    public void rankAt(java.math.BigDecimal placed) {
        this.rank = placed;
    }
}
