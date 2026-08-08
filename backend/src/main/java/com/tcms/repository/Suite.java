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
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Filter;

/**
 * One named collection of one project's test cases — "Payments", "Refunds", "Smoke".
 *
 * <p><strong>FLAT since §98 ({@code V33}).</strong> This was a folder in a tree, with a
 * {@code parent_id} and a materialized {@code ltree} {@code path}; both columns are gone. A suite
 * now says only what it holds, through the three membership arms {@link SuiteMembership} unions:
 * cases <em>filed</em> here ({@code test_case.suite_id}), cases <em>added</em> here
 * ({@code suite_case}), and cases <em>matched</em> by one of its tag rules
 * ({@code suite_tag_rule}). None of the three is a containment, which is why nothing here can move
 * a case and why deleting a suite can never delete one.
 *
 * <p>Soft-deleted ({@link #deletedAt}), like everything a run can have executed against: past
 * executions reference the cases filed under a suite, and {@code suite_run_summary} keys on the
 * suite id, so hard-deleting one would rewrite what a past run covered. Every read must exclude
 * deleted rows explicitly — see {@link SuiteRepository#findLiveForProject}.
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

    /** {@code TS-1} — company-scoped and immutable (plan §6t). Displayed; never sorted on. */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    /**
     * The number inside {@link #key}, and the only column that may order suites (§3 lesson 6).
     *
     * <p>Mapped because it has to be written, not because anything reads it: {@code key_seq} is
     * {@code NOT NULL} with <strong>no default</strong>, so it is the one column an insert cannot
     * leave to the schema. It was unmapped for as long as nothing created a suite, which is exactly
     * how long that was harmless.
     */
    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * Order within the project — it was order within a parent until §98, and nothing has ever
     * written or read it. Kept by {@code V33} rather than dropped: removing an unused column is
     * its own change, and this migration's subject is the hierarchy.
     */
    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    private LifecycleStatus lifecycleStatus;

    /**
     * Composition version (member cases, order, nesting) — <strong>not</strong> content.
     *
     * <p>{@code V1}'s comment on {@code suite_version.snapshot} is explicit about the distinction,
     * and it is why a rename does not bump this: otherwise every case edit would bump every
     * ancestor suite. {@link SuiteVersions} writes v1 at create and nothing else yet, because
     * nothing in this slice changes a composition — see its header for what owes the later bumps.
     */
    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Set ⇒ deleted. Excluded from every read; kept so past runs still resolve their scope. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * The {@code Idempotency-Key} the creating request carried, or null (migration {@code V42}).
     *
     * <p>{@code updatable = false} because it describes the request that made the row and not the
     * row's current state — an edit that rewrote it would let a later intent claim an earlier one's
     * key, which is the one thing the unique index exists to prevent.
     */
    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

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

    /**
     * A new suite.
     *
     * <p>It used to derive an {@code ltree} path here, and the decision that lived in this javadoc
     * — label the path {@code s}+{@code keySeq} rather than slugging the <em>name</em>, because a
     * name-derived path makes every rename a subtree rewrite, is not unique ("UI / E2E" and
     * "ui e2e" slug alike) and can be empty — went with the column in {@code V33}. It is worth
     * knowing about only because {@code execution_fact.suite_path} still holds paths written under
     * that rule, and they are frozen history that must keep rendering.
     *
     * @param idempotencyKey the creating request's {@code Idempotency-Key}, or null when it sent
     *                       none — which today is every SPA create, the header being for callers
     *                       that retry without a person watching (§135)
     */
    static Suite create(
            UUID projectId,
            String key,
            long keySeq,
            SuiteEdit edit,
            UUID creatorId,
            String idempotencyKey,
            Instant now) {

        Suite created = new Suite();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.key = key;
        created.keySeq = keySeq;
        created.lifecycleStatus = LifecycleStatus.DRAFT;
        created.currentVersionNo = 1;
        // Order is not exposed and nothing reorders; the rail sorts by name. Left at the column's
        // own default rather than invented here, so the slice that adds reordering has one place
        // to start assigning it.
        created.position = 0;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.createdIdempotencyKey = idempotencyKey;
        created.applyEdit(edit, creatorId, now);
        return created;
    }

    /**
     * Applies a validated edit — <strong>the only way a suite's content changes.</strong>
     *
     * <p>One method rather than three setters, for {@link TestCase#applyEdit}'s reason: it is not
     * possible to change the name without stamping who did it and when. A setter-based rename is one
     * call site away from a row that was silently edited by nobody at no time.
     *
     * <p>It never re-assigned {@code parentId} or {@code path} either, back when a suite had them:
     * {@link SuiteEdit} refused any payload that asked to move, because assigning a parent without
     * rewriting every descendant path is the corrupted-tree version of the feature rather than the
     * feature. §98 removed the tree, so there is nothing left to refuse.
     *
     * <p>{@code lifecycleStatus} is absent for the reason it is absent from {@link TestCase}: a
     * status an ordinary edit can set is an approval anyone can grant themselves by saving (§6g).
     */
    void applyEdit(SuiteEdit edit, UUID editorId, Instant now) {
        this.name = edit.name();
        this.description = edit.description();
        this.updatedAt = now;
        this.updatedBy = editorId;
    }

    /**
     * Moves this suite along §6g's ladder — <strong>the only writer {@code lifecycle_status} has
     * ever had besides {@link #create}.</strong>
     *
     * <p>Separate from {@link #applyEdit} and that separation is the point: the comment above says a
     * status an ordinary edit can set is an approval anyone can grant themselves by saving. This one
     * is reached only through {@link SuiteStatusService}, which has already asked
     * {@link LifecycleLadder#permissionFor} which key the <em>edge</em> costs.
     *
     * <p>No version row and no {@code currentVersionNo} bump. {@code SuiteVersions} defines a suite
     * version as its <em>composition</em>, and approving a folder does not change what is in it —
     * {@code TestPlan.applyStatus} declined the same bump in §73 for the same reason. A history whose
     * entries all carry byte-identical snapshots is the every-edit-bumps-every-ancestor spiral
     * {@code V1}'s own schema comment refuses.
     */
    void applyStatus(LifecycleStatus target, UUID actorId, Instant now) {
        this.lifecycleStatus = target;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Soft-deletes this suite.
     *
     * <p>Soft, because past runs reference the cases beneath it and {@code suite_run_summary} keys
     * on the id, so removing the row rewrites what a past run covered. The caller has already
     * established the folder is empty — {@link SuiteWriteService} refuses a non-empty one — which is
     * why nothing here walks a subtree.
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

    public String getKey() {
        return key;
    }

    public long getKeySeq() {
        return keySeq;
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

    /**
     * The composition version a review pins itself to ({@code SuiteVersions}).
     *
     * <p>Package-private and read by exactly one caller, {@code SuiteStatusService}: it is not on
     * the wire — {@code SuiteView} serves no version number — and exposing it publicly would invite
     * a second reading of "which version is current" beside {@code SuiteVersions}' own.
     */
    int getCurrentVersionNo() {
        return currentVersionNo;
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
