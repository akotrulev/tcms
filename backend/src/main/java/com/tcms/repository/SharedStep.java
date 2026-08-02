package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A reusable block of steps from the project's library (plan §6f, ERD §4).
 *
 * <p>The sibling of {@link Condition} and mapped on the same terms: a case references an entry by
 * id while authoring, so editing the entry propagates, and the reference is snapshotted into the
 * case version at pin time. DRY while live, immutable once executed.
 *
 * <h2>{@code usage_count} is mapped and deliberately not read</h2>
 *
 * <p>The column exists, is {@code NOT NULL DEFAULT 0}, and stays at 0 forever — it is not mapped
 * here at all, and {@link SharedStepRepository#findLibrary} derives the served count instead. The
 * reason is the one {@link Condition#getUsageCount} could not act on: a case edit replaces the
 * whole step list rather than diffing it, so a denormalized counter has to be recomputed by every
 * write path that touches steps, and the first path that forgets leaves a number that is wrong in
 * a way no read reveals. A count that ranks the picker has to be right to be worth showing, and
 * the honest way to get that is to count the rows.
 *
 * <p>Mapping it and ignoring it would be worse than not mapping it: a field mapped before its
 * reader is a field nobody notices has gone stale, which is the rule {@code Condition} states for
 * {@code parameters} and {@code search_vector}. Both of those stay unmapped here too — there is no
 * parameter surface, and {@code search_vector} is a generated column (V2) whose value no caller
 * ever wants.
 */
@Entity
@Table(name = "shared_step")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class SharedStep {

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
     * The ordered actions — a list, not a paragraph, for {@link Condition}'s reason (ERD §4).
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition = "jsonb"}: the latter
     * satisfies {@code ddl-auto: validate} while the parameter is still bound as {@code varchar},
     * and Postgres does not implicitly cast {@code varchar} to {@code jsonb}. The long form of that
     * is on {@code InstanceAuditEvent.changes}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false)
    private List<String> body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /**
     * Set ⇒ retired from the library.
     *
     * <p>Soft, for the reason {@link Condition} gives: steps referencing it survive. That is also
     * why {@code CaseEdit} accepts a stored reference to a retired entry — see
     * {@link CaseEdit#validate}, where refusing it would make every case using a retired entry
     * unsaveable.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** JPA only. */
    protected SharedStep() {
    }

    /**
     * A new library entry.
     *
     * <p>The id is assigned here rather than by the database, so the caller has it before the
     * insert and nothing has to save-then-read to learn it — the convention every entity in this
     * module follows.
     */
    static SharedStep create(
            UUID companyId, UUID projectId, SharedStepEdit edit, UUID authorId, Instant now) {

        SharedStep created = new SharedStep();
        created.id = UUID.randomUUID();
        created.companyId = companyId;
        created.projectId = projectId;
        created.title = edit.title();
        created.body = List.copyOf(edit.body());
        created.createdAt = now;
        created.createdBy = authorId;
        created.updatedAt = now;
        created.updatedBy = authorId;
        return created;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    /** Never null in practice — {@code body} is {@code NOT NULL} — but may be empty on old rows. */
    public List<String> getBody() {
        return body == null ? List.of() : List.copyOf(body);
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
