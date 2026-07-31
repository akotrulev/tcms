package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One step's recorded result inside one execution — the {@code step_result} table.
 *
 * <p><strong>Named {@code StepOutcome} rather than {@code StepResult}</strong>, because
 * {@code StepResult} is the <em>contract's</em> name for something else: the wire shape that merges
 * the pinned step's text with whatever result was recorded against it. Two different things wearing
 * one name in one package is how a renderer comes to serve the persisted row and omit the step —
 * which is a screen of empty checkboxes with no instructions.
 *
 * <p>{@link #stepId} points at {@code test_step.id}, which is stable across reorders precisely so
 * this row keeps pointing at the step it was recorded against (plan §6m). It is one of the two
 * places this module holds an id owned by {@code repository}; see the package comment.
 *
 * <p>There is no {@code @Version} here even though the column exists. The lock that matters is the
 * execution's: two testers contend for "the result", and step rows are only ever written as part of
 * recording one. A second optimistic lock underneath it would fail a save for a reason the caller's
 * {@code If-Match} could not have anticipated.
 */
@Entity
@Table(name = "step_result")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class StepOutcome {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "step_id", nullable = false, updatable = false)
    private UUID stepId;

    @Column(name = "status_id")
    private UUID statusId;

    @Column(name = "comment")
    private String comment;

    /** JPA only. */
    protected StepOutcome() {
    }

    static StepOutcome record(
            UUID companyId, UUID executionId, UUID stepId, UUID statusId, String comment) {

        StepOutcome outcome = new StepOutcome();
        outcome.id = UUID.randomUUID();
        outcome.companyId = companyId;
        outcome.executionId = executionId;
        outcome.stepId = stepId;
        outcome.statusId = statusId;
        outcome.comment = comment;
        return outcome;
    }

    void amend(UUID statusId, String comment) {
        this.statusId = statusId;
        this.comment = comment;
    }

    UUID getExecutionId() {
        return executionId;
    }

    UUID getStepId() {
        return stepId;
    }

    UUID getStatusId() {
        return statusId;
    }

    String getComment() {
        return comment;
    }
}
