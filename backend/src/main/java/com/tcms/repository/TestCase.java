package com.tcms.repository;

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
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One test case — the thing this whole product exists to hold.
 *
 * <p>Soft-deleted ({@link #deletedAt}) like the suite above it, and for a stronger reason: past
 * executions FK the case and pin the <em>version</em> they ran (§3 lesson 1), so hard-deleting one
 * would rewrite what every historical run covered. Every read must exclude deleted rows explicitly.
 *
 * <h2>A factory and a mutator, and the factory arrived with its caller</h2>
 *
 * <p>{@link #applyEdit} arrived with {@code PATCH} and {@link #create} with {@code POST}, in that
 * order and deliberately: a factory with no caller is where the {@code company_id} rule gets
 * forgotten. Both take a validated {@link CaseEdit} and neither enforces anything, so there is one
 * place a rule about a case's content lives.
 *
 * <p>The two things that make <em>creating</em> a case harder than editing one are both outside this
 * class, because neither is a property of the row: the {@code key}/{@code key_seq} pair is allocated
 * by {@link com.tcms.tenancy.DisplayKeys} from the company's counter, and the first
 * {@code test_case_version} is written by {@link CaseVersions} in the same transaction so history
 * starts at v1 rather than at the first edit.
 *
 * <h2>Plain UUID foreign keys, no associations</h2>
 *
 * <p>{@link #suiteId} and {@link #ownerId} are UUIDs, matching {@code project} and {@code identity}
 * (see their package comments). A {@code @ManyToOne} to {@link Suite} would make the suite name a
 * field access and would also make it an N+1 the moment a page of 200 rows renders — the exact trap
 * this module already avoided once in {@code SuiteService}. The suite name arrives through a
 * scalar subquery for sorting and a bulk lookup for rendering; neither needs a mapped edge.
 *
 * <h2>The detail columns are mapped now, and the reason they were not is the reason they are</h2>
 *
 * <p>{@code objective}, {@code expected_outcome}, {@code severity}, {@code estimated_duration_sec},
 * {@code gherkin_source}, {@code automation_key}, {@code not_automatable_reason} and
 * {@code current_version_no} were all left off while the only reader was the grid, on the rule that
 * a field mapped before its reader is a field nobody notices has gone stale. {@code GET
 * /projects/{projectId}/cases/{caseId}} is that reader — every one of them is a field on the
 * contract's {@code TestCase} — so they arrive together rather than one at a time.
 *
 * <p><strong>They are also the reason this entity is now loaded for a page it does not need them
 * on.</strong> The grid selects entities rather than a projection (see {@code CaseSearch#page}), so
 * mapping eight more columns widens every {@code SELECT} the list runs. At a page capped at 200
 * rows that is not measurable, and the alternative — a second entity, or a projection whose field
 * list must be kept in step by hand — is the drift this codebase has already paid for once.
 *
 * <h2>The columns this class still deliberately does not map</h2>
 *
 * <p>{@code search_vector} is mapped nowhere on purpose: it is a {@code TSVECTOR} maintained by a
 * V2 trigger, it has no Java-side meaning, and mapping it would put a type Hibernate has no
 * converter for into every {@code SELECT} the entity generates.
 *
 * <p>{@code created_at} and {@code created_by} were on that list while nothing created a case. They
 * are mapped now, by the same argument {@code updated_by} was: a row that says nothing about when it
 * was created or by whom is an audit column that looks present and answers nothing, and the moment
 * there is an {@code INSERT} the values are available for free. Neither is on the contract's shape,
 * so neither is served — {@code ddl-auto: validate} checks that mapped columns exist rather than the
 * reverse, so mapping more than the wire carries is safe.
 */
@Entity
@Table(name = "test_case")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestCase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * The folder this case is <strong>filed</strong> in, or {@code null} for an unfiled case.
     *
     * <p>Nullable since {@code V31}. It used to be the whole of suite membership; it is now one of
     * three arms — filed here, added individually ({@code suite_case}), or matched by tag
     * ({@code suite_tag_rule}) — and a case may be created before anybody has decided where it
     * lives. Its subtree is still what the grid's {@code suiteId} filter walks.
     */
    @Column(name = "suite_id")
    private UUID suiteId;

    /** {@code TC-1} — company-scoped, immutable, and display-only: never an API path (plan §6t). */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    /**
     * The numeric part of {@link #key}, and <strong>the only thing that may order it</strong>.
     *
     * <p>{@code TC-10000} sorts before {@code TC-9999} as text, so a grid ordered on the key column
     * looks sorted and is not — the rows are in a plausible order and the mistake surfaces only
     * once a company passes its ten-thousandth case. Every sort that names the key resolves here
     * instead; see {@link CaseSort}.
     */
    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "title", nullable = false)
    private String title;

    /** Mapped only because the contract's free-text {@code q} covers title <em>and</em> description. */
    @Column(name = "description")
    private String description;

    /** Why the case exists. Distinct from {@link #description}, which is what it does (plan §6m). */
    @Column(name = "objective")
    private String objective;

    /**
     * The case-level outcome, alongside the per-step expected results (plan §6m).
     *
     * <p>Both exist because they answer different questions: a step's expected result is what the
     * tester checks at that step, and this is what the case as a whole proves. Collapsing them
     * would either lose the per-step checks or turn the case-level claim into a restatement of the
     * last one.
     */
    @Column(name = "expected_outcome")
    private String expectedOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_format", nullable = false)
    private StepFormat stepFormat;

    /**
     * The Gherkin text, for a case whose {@link #stepFormat} is {@code GHERKIN} (plan §6a).
     *
     * <p>Held instead of {@code test_step} rows rather than alongside them: the two formats are
     * alternatives, and a case carrying both would leave the execution screen to pick one. Nothing
     * in {@code V1} enforces the exclusivity, so this endpoint serves whatever is stored and the
     * SPA branches on {@code stepFormat} — see {@link TestCaseDetailView}.
     */
    @Column(name = "gherkin_source")
    private String gherkinSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority;

    /**
     * How bad it is when this case fails, as opposed to how urgently it should run.
     *
     * <p><strong>Nullable in the schema, and therefore nullable on the wire</strong> — the same
     * divergence {@link #ownerId} had, resolved the same way. {@code priority} is
     * {@code NOT NULL DEFAULT 'MEDIUM'} and this column has no default at all, which is the
     * schema saying the two are not the same field: a team that does not distinguish severity from
     * priority leaves it unset rather than being made to repeat itself. {@code openapi.yaml} now
     * says {@code nullable: true} to match; inventing {@code MEDIUM} here would put a judgement on
     * the wire that nobody made.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private Priority severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    private LifecycleStatus lifecycleStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "automation_status", nullable = false)
    private AutomationStatus automationStatus;

    /**
     * The single stable identifier a CI result comes back on — unique per <em>company</em> (§6c).
     *
     * <p>Company-wide rather than per project so a CI job posts a result without knowing which
     * project owns the case, and so moving a case between projects does not change where its key
     * resolves. Distinct from {@code case_automation_tag}, which selects a set to run; see
     * {@link CaseAutomationTag}.
     */
    @Column(name = "automation_key")
    private String automationKey;

    /**
     * Why this case cannot be automated. {@code NOT NULL} whenever
     * {@link #automationStatus} is {@code NOT_AUTOMATABLE}, by a {@code V1} check constraint —
     * which is what keeps automation coverage honestly denominated rather than letting anything
     * inconvenient be filed as impossible.
     */
    @Column(name = "not_automatable_reason")
    private String notAutomatableReason;

    /** Roughly how long one execution takes. Null when nobody estimated it, not zero. */
    @Column(name = "estimated_duration_sec")
    private Integer estimatedDurationSec;

    /**
     * The version number a reader is looking at — {@code v7} beside the title.
     *
     * <p>Not to be confused with {@link #lockVersion}: this counts <em>authored</em> versions and
     * is what a run pins (§3 lesson 1), while the lock version counts writes and exists to make a
     * concurrent edit a 409. They move independently — an autosave promotes no version — and
     * showing the wrong one would put a number beside the title that no history entry matches.
     */
    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    /**
     * Nullable in the schema, and therefore nullable on the wire.
     *
     * <p>The contract originally marked {@code TestCaseSummary.owner} required while this column
     * has always permitted null — a divergence that had to be resolved in one direction or the
     * other, and this slice resolved it toward the schema. See {@link TestCaseSummaryView#owner()}
     * for why an unowned case is listed with no owner rather than hidden or given an invented one.
     */
    @Column(name = "owner_id")
    private UUID ownerId;

    /**
     * When the row was created. Defaulted to {@code now()} in the schema, and set explicitly by
     * {@link #create} anyway: the default would be the database's clock while every other timestamp
     * in one request comes from the injected {@link java.time.Clock}, and two clocks in one
     * transaction is how {@code created_at} ends up after the version row that snapshots it.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Who created the case. Nullable, and null on every row that predates {@code POST} — there is no
     * backfill, for the reason {@link #updatedBy} gives. Like every {@code created_by} in this schema
     * it carries no FK to {@code app_user}; see {@link TestCaseVersion#getCreatedBy()}.
     */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /**
     * The {@code Idempotency-Key} the creating request carried, or null (migration V11).
     *
     * <p>Write-once and never served: it exists so a retried create returns <em>this</em> case rather
     * than making a second one. {@code updatable = false} is the mapping-level half of that — an edit
     * cannot move a key onto a different case, which would make the original creation replayable into
     * the wrong row.
     */
    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Who last wrote to this row. Mapped now because {@link #applyEdit} is the first thing that can
     * change it, and a row saying it was updated without saying by whom is the audit column that
     * looks present and answers nothing.
     *
     * <p>Nullable, and null on every row written before this slice — there is no backfill, because
     * inventing an author for an edit nobody made is worse than admitting the record predates the
     * column being kept. Like every {@code created_by}/{@code updated_by} in this schema it carries
     * no FK to {@code app_user}; see {@link TestCaseVersion#getCreatedBy()}.
     */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Set ⇒ deleted. Excluded from every read; kept so past runs still resolve what they covered. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TestCase() {
    }

    /**
     * A new case — <strong>the only way one comes into existence.</strong>
     *
     * <h2>What is server-assigned, and why none of it is a request field</h2>
     *
     * <ul>
     *   <li><strong>{@code companyId} from {@link CompanyContext}</strong>, never an argument. ERD
     *       §0.1 point 2: a company taken from a parameter is a company some future call site can get
     *       wrong, and this row is the client's content.</li>
     *   <li><strong>{@code key} / {@code keySeq} from the company's counter</strong>, passed in
     *       already allocated so this class is not a second place that knows how numbering works.
     *       They are {@code updatable = false}, so a case can never be renumbered.</li>
     *   <li><strong>{@code lifecycleStatus = DRAFT}</strong>, matching the column's default and
     *       {@code TestCaseInput}'s omission of the field. A case that could be created
     *       {@code APPROVED} is an approval its author granted themselves, which is the review
     *       workflow's decision to make (plan §6g) and not a create's.</li>
     *   <li><strong>{@code currentVersionNo = 1}</strong> paired with the v1 row the caller writes.
     *       The column defaults to 1, and relying on the default is the trap: {@link
     *       CaseVersions#decide} would then number the <em>first</em> version 2, reproducing on a new
     *       case exactly the v1 gap that is only defensible for cases predating versioning.</li>
     *   <li><strong>{@code ownerId = creatorId}.</strong> {@code ownerId} is refused as a request
     *       field — choosing another person's case needs a membership check this slice does not build
     *       — but a case with no owner at all is a row nobody is answerable for, and authorship is
     *       what {@code case:delete}'s {@code OWN} qualifier will resolve against. The author owns
     *       what they wrote until something reassigns it.</li>
     * </ul>
     *
     * @param edit           already validated and normalized — see {@link CaseEdit}
     * @param creatorId      the authenticated caller, never a value from the request body
     * @param idempotencyKey the request's {@code Idempotency-Key}, or null when it sent none
     */
    static TestCase create(
            UUID projectId,
            String key,
            long keySeq,
            CaseEdit edit,
            UUID creatorId,
            String idempotencyKey,
            Instant now) {

        TestCase created = new TestCase();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.key = key;
        created.keySeq = keySeq;
        created.lifecycleStatus = LifecycleStatus.DRAFT;
        created.currentVersionNo = 1;
        created.ownerId = creatorId;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.createdIdempotencyKey = idempotencyKey;
        // Every content field through the same method an edit uses, so a field added to CaseEdit
        // cannot arrive on edits and be silently missing on creates.
        created.applyEdit(edit, creatorId, now);
        return created;
    }

    /**
     * Applies a validated edit to this case — <strong>the only way its content changes.</strong>
     *
     * <h2>One method rather than fourteen setters, and that is the design</h2>
     *
     * <p>Every field here moves together on an explicit save (plan §6j), and two of them are not
     * content at all: {@link #updatedAt} and {@link #updatedBy} must be stamped by whatever changed
     * the row. With setters that is a rule each call site remembers, and the call site that forgets
     * produces a row that was silently edited by nobody at no time — invisible in every test that
     * checks the field it did set. Here it is not possible to change the title without stamping who
     * did it.
     *
     * <p>{@code lifecycleStatus} is deliberately absent, matching {@code TestCaseInput}: promotion
     * through draft → review → approved is the review workflow's (plan §6g), and a status that an
     * ordinary edit can set is an approval anyone can grant themselves by saving. The SPA's editor
     * agrees — status is not a field on the form.
     *
     * <p>{@code key}, {@code keySeq}, {@code companyId} and {@code projectId} are
     * {@code updatable = false} at the mapping level, so a case cannot be renumbered or moved
     * between projects or companies by this path even if a future caller passes them. Moving a case
     * between projects would invalidate every suite, classification value and condition attached to
     * it; it is not an edit, and if it is ever wanted it is its own operation.
     *
     * @param edit     already validated and normalized — see {@link CaseEdit}. This method enforces
     *                 nothing, on purpose: a rule half-enforced here and half at the edge is a rule
     *                 with two answers.
     * @param editorId the authenticated caller, never a value from the request body
     */
    void applyEdit(CaseEdit edit, UUID editorId, Instant now) {
        this.suiteId = edit.suiteId();
        this.title = edit.title();
        this.description = edit.description();
        this.objective = edit.objective();
        this.expectedOutcome = edit.expectedOutcome();
        this.stepFormat = edit.stepFormat();
        this.gherkinSource = edit.gherkinSource();
        this.priority = edit.priority();
        this.severity = edit.severity();
        this.automationStatus = edit.automationStatus();
        this.automationKey = edit.automationKey();
        this.notAutomatableReason = edit.notAutomatableReason();
        this.estimatedDurationSec = edit.estimatedDurationSec();
        this.updatedAt = now;
        this.updatedBy = editorId;
    }

    /**
     * Moves this case to a lifecycle status — <strong>the only writer of this column</strong>.
     *
     * <p>Deliberately not part of {@link #applyEdit}, and the header on that method says why: a
     * status an ordinary save can set is an approval anyone grants themselves by pressing Save. It
     * is reachable only from {@link CaseBulkService}, whose gate asks {@code case:approve} when the
     * target is {@code APPROVED} and {@code case:edit} otherwise (plan §6g's single gate).
     *
     * <p>Whether the transition is legal is {@link CaseBulkService}'s to decide, not this method's.
     * The rule — {@code APPROVED} is reachable only from {@code IN_REVIEW} — needs to produce a
     * per-case <em>reason</em> for the preview, and an entity that could only refuse by throwing
     * would turn one ineligible case into a failed operation over the other 43.
     */
    void moveToStatus(LifecycleStatus status, UUID actorId, Instant now) {
        this.lifecycleStatus = status;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Sets the automation status, and the reason that must accompany {@code NOT_AUTOMATABLE}.
     *
     * <p>The two move together because {@code V1}'s {@code ck_not_automatable_reason} requires it,
     * and separate setters are how one of them gets called without the other and the constraint
     * surfaces as a 500 halfway through a batch.
     */
    void applyAutomationStatus(
            AutomationStatus status, String reason, UUID actorId, Instant now) {

        this.automationStatus = status;
        this.notAutomatableReason = reason;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /** Assigns an owner, or clears one when {@code ownerId} is null. */
    void assignOwner(UUID newOwnerId, UUID actorId, Instant now) {
        this.ownerId = newOwnerId;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Moves this case into another suite <em>of the same project</em>.
     *
     * <p>The caller checks that. This method cannot: {@code projectId} is {@code updatable = false}
     * so a case cannot change project by any path, but a suite id belonging to a different project
     * would move the case into a folder no read of either project's tree walks — and the case simply
     * disappears from both, which is {@link CaseEdit#validate}'s reason for the same check.
     */
    void moveToSuite(UUID newSuiteId, UUID actorId, Instant now) {
        this.suiteId = newSuiteId;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Records that a new authored version now exists — called only when one was actually written.
     *
     * <p>Separate from {@link #applyEdit} because the two do not always both happen: a save whose
     * content hash is unchanged writes no version and must not advance this number, while
     * {@link #lockVersion} advances on every write regardless. Keeping them one method is how the
     * version beside the title starts counting saves instead of versions, and then no history entry
     * matches it.
     */
    void advanceVersion(int versionNo) {
        this.currentVersionNo = versionNo;
    }

    /**
     * Soft-deletes this case.
     *
     * <p>Soft for the reason this class's header already gives and which is stronger here than for a
     * suite: a past run pinned the version it executed, and that result must keep resolving what it
     * covered (§3 lesson 1). A hard delete rewrites history rather than removing a case from the
     * repository.
     *
     * <p>Nothing beneath the case is touched. Steps, conditions, tags and classifications stay
     * attached to a row that no read returns, which is what makes the deletion recoverable and what
     * lets a pinned version still render. The keys stay reserved too — deliberately, per
     * {@link TestCaseRepository#existsByAutomationKeyOtherThan}: results keep arriving for a retired
     * automated test until someone deletes the CI job, and handing its key to a new case would file
     * them against the wrong one.
     *
     * <p>No {@code lifecycleStatus} change. Deleted is not a workflow state — a case in review that
     * is deleted was not rejected, and re-using the status column for it is how "approved" starts
     * meaning two things.
     */
    void softDelete(UUID actorId, Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public String getKey() {
        return key;
    }

    public long getKeySeq() {
        return keySeq;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getObjective() {
        return objective;
    }

    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    public StepFormat getStepFormat() {
        return stepFormat;
    }

    public String getGherkinSource() {
        return gherkinSource;
    }

    public Priority getPriority() {
        return priority;
    }

    public Priority getSeverity() {
        return severity;
    }

    public LifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public AutomationStatus getAutomationStatus() {
        return automationStatus;
    }

    public String getAutomationKey() {
        return automationKey;
    }

    public String getNotAutomatableReason() {
        return notAutomatableReason;
    }

    public Integer getEstimatedDurationSec() {
        return estimatedDurationSec;
    }

    public int getCurrentVersionNo() {
        return currentVersionNo;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * Who wrote this case, or null on a row that predates {@code POST}.
     *
     * <p>Read by exactly one caller — {@link CaseDeleteService}, which hands it to the authorship
     * gate because {@code case:delete} is {@code OWN}-qualified for Test Engineer (roles spec §A5).
     * It is deliberately <em>not</em> on {@link TestCaseSummaryView} or the detail view: an author is
     * not a field the API serves, and a null one would have to mean something on a screen.
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
