package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A test plan — a scope, a milestone and a set of criteria (ERD §5).
 *
 * <p>Creatable since M2 slice 2 and still <strong>not editable</strong>: {@link #create} is the one
 * way a row appears and there is no mutator, because {@code plan:edit} is a separate permission and
 * a separate slice. The factory is package-private and takes an already-validated {@link PlanEdit},
 * so an entity that can be constructed before anything validates it — the shape that writes a plan
 * with no name and no key — is not expressible.
 *
 * <h2>The criteria are held as JSON text, not as a mapped graph</h2>
 *
 * <p>{@code entry_criteria} and {@code exit_criteria} are {@code JSONB} holding an array of
 * structured predicates ({@code {"metric":"pass_rate","op":">=","value":95}}), so the plan page can
 * <em>evaluate</em> them rather than display prose nobody can check (plan §6p). They are mapped as
 * {@code String} for the reason {@code repository.TestCaseVersion} gives for its snapshot: the
 * column type still checks well-formedness at insert, and nothing in this module needs to walk into
 * one — {@link PlanCriteria} parses them at the edge, once, into the wire shape.
 *
 * <p>Null is an ordinary value for both. A plan with no exit criteria has not failed to declare
 * them; it has not reached the point of declaring them, and rendering {@code []} says the same
 * thing without claiming the question was asked.
 */
@Entity
@Table(name = "test_plan")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestPlan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** {@code TP-1} — sequential per COMPANY and immutable (§6t). */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlanStatus status;

    @Column(name = "milestone_name")
    private String milestoneName;

    /**
     * A {@code DATE}, not a timestamp, and mapped as one: a milestone is a day everybody in the
     * project agrees on, and giving it an instant makes it a different day either side of a time
     * zone — the whole point of the field being the shared deadline.
     */
    @Column(name = "milestone_date")
    private LocalDate milestoneDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_criteria")
    private String entryCriteria;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exit_criteria")
    private String exitCriteria;

    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    @Column(name = "deleted_at")
    private java.time.Instant deletedAt;

    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;

    /** Opaque, write-once, never read as data — see {@code V14}. */
    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    /** JPA only. */
    protected TestPlan() {
    }

    /**
     * A new plan, {@code DRAFT} at v1.
     *
     * <p><strong>Not {@code ACTIVE}.</strong> The status ladder is DRAFT → IN_REVIEW → APPROVED →
     * ACTIVE → CLOSED and {@code plan:review} / {@code plan:approve} are separate permissions held
     * by fewer roles (roles spec §A4). A create that landed at ACTIVE would let a Test Engineer
     * grant their own plan the approval the catalogue says they may not — the same argument
     * {@code repository.TestCase.applyEdit} makes for leaving {@code lifecycleStatus} off an edit,
     * and {@code TestPlanInput} agrees by having no status field.
     *
     * <p>{@code companyId} comes from {@link CompanyContext}, never from the caller (ERD §0.1
     * point 2).
     */
    static TestPlan create(
            UUID projectId,
            String key,
            long keySeq,
            PlanEdit edit,
            UUID creatorId,
            String idempotencyKey,
            java.time.Instant now) {

        TestPlan created = new TestPlan();
        created.id = UUID.randomUUID();
        created.companyId = com.tcms.tenancy.CompanyContext.require();
        created.projectId = projectId;
        created.key = key;
        created.keySeq = keySeq;
        created.name = edit.name();
        created.description = edit.description();
        created.status = PlanStatus.DRAFT;
        created.milestoneName = edit.milestoneName();
        created.milestoneDate = edit.milestoneDate();
        // Both null, not "[]": a plan that has not reached the point of declaring criteria has not
        // declared an empty set of them, and PlanCriteria renders null as [] for the wire anyway.
        // Writing "[]" here would make "nobody has decided" and "there are deliberately none"
        // indistinguishable in the column that a later plan:edit slice has to tell apart.
        created.entryCriteria = null;
        created.exitCriteria = null;
        created.currentVersionNo = 1;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.updatedAt = now;
        created.createdIdempotencyKey = idempotencyKey;
        return created;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getKey() {
        return key;
    }

    long getKeySeq() {
        return keySeq;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    PlanStatus getStatus() {
        return status;
    }

    String getMilestoneName() {
        return milestoneName;
    }

    LocalDate getMilestoneDate() {
        return milestoneDate;
    }

    String getEntryCriteria() {
        return entryCriteria;
    }

    String getExitCriteria() {
        return exitCriteria;
    }

    int getCurrentVersionNo() {
        return currentVersionNo;
    }

    java.time.Instant getDeletedAt() {
        return deletedAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
