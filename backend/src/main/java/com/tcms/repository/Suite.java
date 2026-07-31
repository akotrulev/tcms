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
 * One folder of one project's test tree — "Payments" › "Refunds" › "Partial refund".
 *
 * <p>Soft-deleted ({@link #deletedAt}), like everything a run can have executed against: past
 * executions reference the cases beneath a suite, and {@code suite_run_summary} keys on the suite
 * id, so hard-deleting one would rewrite what a past run covered. Every read must exclude deleted
 * rows explicitly — see {@link SuiteRepository#findLiveForProject}.
 *
 * <p>No factory method and no mutators, for the reason recorded on
 * {@link com.tcms.project.ClassificationDimension}: nothing writes a suite yet, and a factory with
 * no caller is where the {@code company_id} rule gets forgotten. Add both with the endpoint that
 * needs them — which is also where {@link #path} stops being read-only, and that is the harder
 * half (see below).
 */
@Entity
@Table(name = "suite")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Suite {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null for a root folder. The client builds the tree from this, not from {@link #path}. */
    @Column(name = "parent_id")
    private UUID parentId;

    /** {@code TS-1} — company-scoped and immutable (plan §6t). Displayed; never sorted on. */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    /**
     * Materialized path, so "run this folder" reads a subtree in one indexed query (ERD §4).
     *
     * <p><strong>{@code columnDefinition} is load-bearing, not decoration</strong> — the second
     * {@code ltree} column in the codebase, and it fails exactly as the first one does. See
     * {@link com.tcms.project.ClassificationValue#getPath()} for the full account: {@code ltree}
     * reports as {@code Types#OTHER} while a {@code String} field maps to {@code varchar}, so
     * {@code ddl-auto: validate} rejects the mismatch and the application context fails to build
     * at all. That is the good kind of failure — at boot rather than at the first query.
     *
     * <p>It is not the display name. {@code ltree} labels admit only {@code [A-Za-z0-9_]}, so a
     * suite called "UI / E2E" has a path that does not spell it. Render {@link #name}; use the
     * path for subtree matching.
     *
     * <p>Reparenting rewrites every descendant's path in one statement, which is the reason there
     * is no setter here and the reason the rename/move endpoint is a slice of its own rather than
     * a field assignment.
     */
    @Column(name = "path", nullable = false, columnDefinition = "ltree")
    private String path;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /** Sibling order within one parent. Not exposed — see {@link SuiteView}. */
    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    private LifecycleStatus lifecycleStatus;

    /** Set ⇒ deleted. Excluded from every read; kept so past runs still resolve their scope. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Optimistic-locking token, and part of the contract: the SPA sends it back on a rename so a
     * concurrent edit is a 409 rather than a silent overwrite (plan §6j). Exposed for that reason
     * alone, which is why it survives the general rule that internals stay off the wire.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Suite() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getKey() {
        return key;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getPosition() {
        return position;
    }

    public LifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
