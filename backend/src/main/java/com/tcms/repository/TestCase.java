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
 * <h2>The columns this class deliberately does not map</h2>
 *
 * <p>{@code gherkin_source}, {@code description}'s siblings on the detail shape
 * ({@code objective}, {@code expected_outcome}, {@code estimated_duration_sec}),
 * {@code not_automatable_reason}, {@code current_version_no} and {@code search_vector} are all
 * absent. {@code ddl-auto: validate} checks that mapped columns exist, not that existing columns
 * are mapped, so leaving them off is safe and keeps the list of what this endpoint can leak short.
 * {@code description} <em>is</em> mapped because the contract's {@code q} searches it.
 *
 * <p>{@code search_vector} in particular is mapped nowhere on purpose: it is a {@code TSVECTOR}
 * maintained by a V2 trigger, it has no Java-side meaning, and mapping it would put a type
 * Hibernate has no converter for into every {@code SELECT} the entity generates.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "step_format", nullable = false)
    private StepFormat stepFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    private LifecycleStatus lifecycleStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "automation_status", nullable = false)
    private AutomationStatus automationStatus;

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

    public StepFormat getStepFormat() {
        return stepFormat;
    }

    public Priority getPriority() {
        return priority;
    }

    public LifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public AutomationStatus getAutomationStatus() {
        return automationStatus;
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
