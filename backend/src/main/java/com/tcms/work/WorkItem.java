package com.tcms.work;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A task somebody typed — <strong>the only kind of board card that is a row</strong> (plan §6l).
 *
 * <p>{@code V1}'s comment on this table is the specification: "ONLY real tasks are rows. Derived
 * items — assigned executions, pending reviews, stale cases — are NEVER materialized; they resolve
 * at query time from their source." So there is no {@code sourceType} or {@code sourceId} column
 * here to be tempted by, and {@link #kind} exists in the schema but is written exactly once, with
 * exactly one value.
 *
 * <h2>{@code kind} is a column that only ever says TASK, and it stays</h2>
 *
 * <p>Stated because it reads like dead weight. The wire's {@code kind} spans six values, five of
 * which belong to derived cards that have no row — so this column can only ever hold the sixth.
 * Dropping it would mean the entity and the projection answer the same field from different shapes,
 * and the day a genuinely stored non-task kind appears (a local {@code DEFECT} record is the one
 * §6l names) it would be a migration rather than a value. Cheap to keep, and it keeps
 * {@code ddl-auto: validate} covering the whole row.
 *
 * <h2>No mutator but one</h2>
 *
 * <p>{@link #moveTo} is the only way this changes after creation, because the board is the only
 * screen that writes here. Editing a task's title, description, due date or assignee has no
 * affordance anywhere in the SPA, and an entity that permitted it would be modelling a capability
 * the product does not have — which is how a setter grows a caller that bypasses a permission check
 * nobody remembered to add.
 */
@Entity
@Table(name = "work_item")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class WorkItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    /** Always {@code TASK}. See the class header for why the column survives anyway. */
    @Column(name = "kind", nullable = false)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkItemStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority;

    /**
     * A {@code DATE}, and mapped as one.
     *
     * <p>Same argument {@code execution.TestPlan} makes for its milestone: a deadline is a day the
     * whole team agrees on, and giving it an instant makes it a different day either side of a time
     * zone — which is the entire point of the field.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected WorkItem() {
    }

    /**
     * A new task, in {@code TODO}.
     *
     * <p><strong>The status is not a parameter.</strong> {@code WorkItemInput} has no status field
     * for the same reason {@code TestPlan.create} refuses one: a create that could choose its column
     * is a move that no move was ever recorded for, and "create it directly in Done" is the shape
     * that lets a board show completed work nobody did.
     *
     * <p>{@code companyId} comes from {@link CompanyContext}, never from the caller (ERD §0.1
     * point 2) — the caller supplies a {@code projectId}, and which company that project is in is
     * not theirs to assert.
     */
    static WorkItem create(UUID projectId, WorkItemEdit edit, UUID creatorId, Instant now) {
        WorkItem created = new WorkItem();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.title = edit.title();
        created.description = edit.description();
        created.kind = "TASK";
        created.status = WorkItemStatus.TODO;
        created.priority = edit.priority();
        created.dueDate = edit.dueDate();
        created.assigneeId = edit.assigneeId();
        created.createdAt = now;
        created.createdBy = creatorId;
        created.updatedAt = now;
        created.updatedBy = creatorId;
        return created;
    }

    /**
     * Move this task to another column.
     *
     * <p>No transition is refused here, and that is the model rather than an omission: a task's
     * status is <em>its own</em>, so every column is a legal destination and
     * {@code BoardService} says so by giving a task every status but its current one in
     * {@code allowedTransitions}. The constrained side is the derived card, which has no row and
     * therefore never reaches this method.
     */
    void moveTo(WorkItemStatus target, UUID actorId, Instant now) {
        this.status = target;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getTitle() {
        return title;
    }

    String getDescription() {
        return description;
    }

    WorkItemStatus getStatus() {
        return status;
    }

    Priority getPriority() {
        return priority;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    UUID getAssigneeId() {
        return assigneeId;
    }
}
