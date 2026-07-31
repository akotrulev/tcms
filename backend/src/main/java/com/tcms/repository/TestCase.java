package com.tcms.repository;

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
 * <h2>No factory and no mutators, still</h2>
 *
 * <p>Same reason as {@link Suite} and {@code ClassificationDimension}: this slice reads, and a
 * factory with no caller is where the {@code company_id} rule gets forgotten. The create endpoint
 * brings both, along with the two things that make writing a case harder than filling in these
 * fields — allocating {@code key}/{@code key_seq} from the company's {@code key_sequence} row, and
 * writing the first {@code test_case_version} in the same transaction so history starts at v1
 * rather than at the first edit.
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
 * converter for into every {@code SELECT} the entity generates. {@code created_at} /
 * {@code created_by} / {@code updated_by} have no field on the contract's shape, and
 * {@code ddl-auto: validate} checks that mapped columns exist rather than that existing columns are
 * mapped — so leaving them off is safe and keeps the list of what this endpoint can leak short.
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

    /** Never null — a case always lives in a folder. Its subtree is what {@code suiteId} filters. */
    @Column(name = "suite_id", nullable = false)
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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set ⇒ deleted. Excluded from every read; kept so past runs still resolve what they covered. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TestCase() {
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

    public long getLockVersion() {
        return lockVersion;
    }
}
