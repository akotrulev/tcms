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
 * One folder of one project's test tree — "Payments" › "Refunds" › "Partial refund".
 *
 * <p>Soft-deleted ({@link #deletedAt}), like everything a run can have executed against: past
 * executions reference the cases beneath a suite, and {@code suite_run_summary} keys on the suite
 * id, so hard-deleting one would rewrite what a past run covered. Every read must exclude deleted
 * rows explicitly — see {@link SuiteRepository#findLiveForProject}.
 *
 * <p>The factory and the two mutators arrived with {@link SuiteWriteService}, which is the endpoint
 * the older header here said to wait for. {@link #path} is still read-only after construction, and
 * that remains the harder half: nothing moves a suite yet (see below).
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
     * The number inside {@link #key}, and the only column that may order suites (§3 lesson 6).
     *
     * <p>Mapped because it has to be written, not because anything reads it: {@code key_seq} is
     * {@code NOT NULL} with <strong>no default</strong>, so it is the one column an insert cannot
     * leave to the schema. It was unmapped for as long as nothing created a suite, which is exactly
     * how long that was harmless.
     */
    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

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
     *
     * <p><strong>{@code @ColumnTransformer} is the other half of {@code columnDefinition}, and it
     * is needed the moment anything <em>writes</em> a suite.</strong> Reading works without it —
     * Postgres renders {@code ltree} as text on the way out — but an insert binds this field as
     * {@code varchar} and Postgres refuses the assignment outright: <em>column "path" is of type
     * ltree but expression is of type character varying</em>. A hard error rather than a silent
     * one, which is the good version, and it only appeared when {@link #create} did: every fixture
     * that wrote a suite before then spelled the {@code CAST} itself in raw SQL.
     *
     * <p>{@code ClassificationValue.path} has the same mapping and not this annotation. It is not
     * broken today because nothing in Java inserts one — V2's seed function writes them with its
     * own cast — and it will fail exactly like this on the day something does.
     */
    @Column(name = "path", nullable = false, columnDefinition = "ltree")
    @ColumnTransformer(write = "CAST(? AS ltree)")
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
     * A new folder.
     *
     * <h2>The path label is the key sequence, and that is the decision in this method</h2>
     *
     * <p>{@code V2} ships {@code tcms_ltree_label()}, which slugs a <em>name</em> into a path label,
     * and it is the wrong tool here. Classification values are seeded once and never renamed; suites
     * are renamed constantly, and a name-derived path makes <strong>every rename a subtree
     * rewrite</strong> — which contradicts this class's own account of paths, where reparenting is
     * the thing that rewrites them. A slug is also not unique ("UI / E2E" and "ui e2e" produce the
     * same label under one parent) and can be empty ("…" slugs to {@code ''}, which is not a valid
     * {@code ltree} label at all and fails at insert with a message about nothing).
     *
     * <p>So the label is {@code s} + {@link #keySeq}: unique by construction, stable across renames,
     * never empty, and valid without escaping. The leading letter is not superstition about
     * {@code ltree} accepting digits — it is so a path segment is recognisable as a suite when it
     * turns up in {@code execution_fact.suite_path} beside other trees.
     *
     * @param parentPath the parent's {@link #path}, or null for a root folder
     */
    static Suite create(
            UUID projectId,
            UUID parentId,
            String parentPath,
            String key,
            long keySeq,
            SuiteEdit edit,
            UUID creatorId,
            Instant now) {

        Suite created = new Suite();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.parentId = parentId;
        created.key = key;
        created.keySeq = keySeq;
        created.path = parentPath == null ? label(keySeq) : parentPath + "." + label(keySeq);
        created.lifecycleStatus = LifecycleStatus.DRAFT;
        created.currentVersionNo = 1;
        // Sibling order is not exposed and nothing reorders yet; the flat list is ordered by path
        // (SuiteRepository.findLiveForProject). Left at the column's own default rather than
        // invented here, so the slice that adds reordering has one place to start assigning it.
        created.position = 0;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.applyEdit(edit, creatorId, now);
        return created;
    }

    /** The {@code ltree} label for a suite — see {@link #create}. */
    private static String label(long keySeq) {
        return "s" + keySeq;
    }

    /**
     * Applies a validated edit — <strong>the only way a suite's content changes.</strong>
     *
     * <p>One method rather than three setters, for {@link TestCase#applyEdit}'s reason: it is not
     * possible to change the name without stamping who did it and when. A setter-based rename is one
     * call site away from a row that was silently edited by nobody at no time.
     *
     * <p>{@link #parentId} and {@link #path} are read from the edit but never re-assigned here.
     * {@link SuiteEdit#validateRename} has already refused any payload that asked to move, so this
     * method has nothing to enforce — and if it did assign them, it would assign a parent without
     * rewriting a single descendant path, which is the corrupted-tree version of the feature rather
     * than the feature.
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

    public UUID getParentId() {
        return parentId;
    }

    public String getKey() {
        return key;
    }

    public long getKeySeq() {
        return keySeq;
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
