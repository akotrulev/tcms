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
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A reusable pre- or post-condition from the project's library (plan §6f, ERD §4).
 *
 * <p>A suite or a case attaches one by <em>reference</em> while authoring, so editing the library
 * entry propagates; the reference is resolved and snapshotted into the case version at version-pin
 * time. That is the whole design: DRY while live, immutable once executed. This module reads the
 * live side only.
 *
 * <p>Still mapped thin, but less so than it was: {@code type} was left off while nothing read it,
 * and the library endpoint ({@link ConditionController}) is the reader it was waiting for.
 * {@code parameters} and {@code search_vector} stay unmapped on the original terms, because a
 * field mapped before its reader is a field nobody notices has gone stale: parameters belong to a
 * substitution surface nothing implements, and {@code search_vector} is a generated column no
 * caller ever wants the value of.
 *
 * <h2>{@code usage_count} is UNMAPPED as of §95, and that is a deliberate removal</h2>
 *
 * <p>It was mapped and served, always reading its {@code DEFAULT 0}, under a comment arguing the
 * stored value was the honest answer while no writer maintained it. §95 gives the library a writer
 * and an <em>impact warning</em>, and a warning computed from a permanent zero is not a cautious
 * answer — it is a confident wrong one, telling an author that editing an entry three hundred cases
 * attach will affect nothing. {@link ConditionService} derives both counts from the referencing
 * tables instead, so there is one shape here and not two.
 *
 * <h2>{@code lock_version} is mapped WITH {@code @Version}, from this entity's first writer</h2>
 *
 * <p>The column has existed since {@code V1} and was never mapped. §74 found the other spelling of
 * this on {@link TestPlan}, {@code TestRun} and {@code work.WorkItem} — {@code lock_version} mapped
 * as a plain column with no {@code @Version} — where it reads {@code 0} for ever, so an
 * {@code If-Match} precondition <strong>cannot fail</strong>: there is no such thing as a stale
 * {@code 0}, and the endpoint ships a concurrency check that is decorative. Mapping it correctly is
 * free here because this entity has no hot write path to make contention expensive (the reason the
 * run's copy was left alone).
 */
@Entity
@Table(name = "condition")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Condition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "title", nullable = false)
    private String title;

    /**
     * PRE, POST or BOTH — see {@link ConditionType} for why BOTH is not a third role.
     *
     * <p>{@code @Enumerated(STRING)} rather than the JPA default: the default is
     * {@code ORDINAL}, which stores 0/1/2 against a {@code TEXT CHECK (type IN ('PRE','POST',
     * 'BOTH'))} column and fails at insert — and, worse, would silently re-map every stored row if
     * a member were ever inserted into the middle of the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ConditionType type;

    /**
     * The ordered steps — "log in" is realistically three actions, so the body is a list and not a
     * paragraph (ERD §4).
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition = "jsonb"}, for the
     * reason {@code InstanceAuditEvent.changes} records at length: {@code columnDefinition} only
     * satisfies {@code ddl-auto: validate} while the parameter is still bound as {@code varchar},
     * and Postgres does not implicitly cast {@code varchar} to {@code jsonb}. Reading a
     * {@code List<String>} out of it goes through Hibernate's JSON format mapper, which is Jackson
     * here.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false)
    private List<String> body;

    /**
     * The {@code .feature} import's key for one {@code Background:} step, or null.
     *
     * <p>{@code "bg:"} plus the first 16 hex of SHA-256 over the normalised step — a TEXT hash, not
     * a positional key, so a reordered or partly rewritten Background re-mints nothing and the same
     * step text in two feature files is one library entry for the project. The column arrived in
     * {@code V69} and the field arrives here with its writer (§255 T6), which is this repository's
     * standing rule: a field mapped before its writer is a field nobody notices has gone stale.
     *
     * <p><strong>Non-null also means "the import owns this entry".</strong> A re-import may replace
     * the {@code PRE} attachments whose condition has one, and may never touch a human-attached
     * {@code PRE} or any {@code POST} — {@code api-surface.md} §5.4, and the ownership half of
     * amendment AM1. That is the only thing the value is ever compared for besides the lookup.
     *
     * <p>Unique per {@code (company_id, project_id, imported_key)} among live rows
     * ({@code condition_imported_key_idx}, partial on {@code deleted_at IS NULL}), so retiring a
     * condition frees its key and the next import re-creates it.
     */
    @Column(name = "imported_key")
    private String importedKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Set ⇒ retired from the library. Attachments to it survive; see {@link SuiteConditionView}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** See the class header: {@code @Version}, not a plain column, or the {@code If-Match} is a no-op. */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Condition() {
    }

    /**
     * A new library entry.
     *
     * <p>The id is assigned here rather than by the database, so the caller has it before the
     * insert and nothing has to save-then-read to learn it — the convention every entity in this
     * module follows.
     */
    static Condition create(
            UUID companyId, UUID projectId, ConditionEdit edit, UUID authorId, Instant now) {

        Condition created = new Condition();
        created.id = UUID.randomUUID();
        created.companyId = companyId;
        created.projectId = projectId;
        created.title = edit.title();
        created.type = edit.type();
        created.body = List.copyOf(edit.body());
        created.createdAt = now;
        created.createdBy = authorId;
        created.updatedAt = now;
        created.updatedBy = authorId;
        return created;
    }

    /**
     * A new library entry the {@code .feature} import owns — {@link #create} plus its key.
     *
     * <p>A separate factory rather than a nullable sixth parameter on {@link #create}, because
     * every other caller would then have to pass {@code null} for a concept it has no business
     * knowing about, and one of them would eventually pass something else. The two share nothing
     * but the call below, which is the point: an entry is import-owned or it is not, and the fork
     * is at the moment it is written.
     */
    static Condition createImported(
            UUID companyId, UUID projectId, ConditionEdit edit, String importedKey, UUID authorId,
            Instant now) {

        Condition created = create(companyId, projectId, edit, authorId, now);
        created.importedKey = importedKey;
        return created;
    }

    /**
     * Replaces the authored content.
     *
     * <p>Every attachment resolves through this row, so this method <em>is</em> the propagation —
     * there is nothing to fan out to and no second copy to keep in step. That is the design
     * ({@code case_condition.condition_id} is a reference), and it is why the impact warning lives
     * on the client rather than in a confirmation round-trip here: by the time this runs, the
     * decision has been made.
     *
     * <p>{@code project_id} and {@code company_id} are {@code updatable = false} and are not
     * touched — an entry cannot be moved between projects by editing it.
     */
    void applyEdit(ConditionEdit edit, UUID editorId, Instant now) {
        this.title = edit.title();
        this.type = edit.type();
        this.body = List.copyOf(edit.body());
        this.updatedAt = now;
        this.updatedBy = editorId;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public ConditionType getType() {
        return type;
    }

    public long getLockVersion() {
        return lockVersion;
    }

    /** Never null in practice — {@code body} is {@code NOT NULL} — but may legitimately be empty. */
    public List<String> getBody() {
        return body == null ? List.of() : List.copyOf(body);
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Null unless the {@code .feature} import created this entry — see the field. */
    public String getImportedKey() {
        return importedKey;
    }
}
