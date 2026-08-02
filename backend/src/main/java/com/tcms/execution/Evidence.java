package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One screenshot or log attached to a result — {@code V1}'s {@code evidence} table, first writer.
 *
 * <p>{@link #stepResultId} is nullable because evidence attaches at either level, and the two are
 * genuinely different claims: a file on the execution documents <em>the verdict</em>, a file on a
 * step documents <em>where it went wrong</em>. Collapsing them would lose which one the tester
 * meant, on the screen whose entire job is answering that.
 *
 * <h2>The run-closed invariant is NOT enforced here, deliberately</h2>
 *
 * <p>{@code V1}'s comment on this table says evidence is <em>immutable once the run closes</em>.
 * That is not implemented, and the reason is that implementing it here alone would be incoherent:
 * {@link RunStatus} has no {@code CLOSED} value, {@code run:close} has no implementation, and
 * decisively, {@link ExecutionService#record} does <strong>not</strong> refuse to amend a result on a
 * {@code COMPLETED} run. Gating evidence alone would make the screenshot stricter than the verdict
 * it documents — a tester could change the result and not attach the proof of it.
 *
 * <p>The invariant belongs with {@code run:close}, as one rule over both writes at once. Written
 * down rather than half-applied, because a rule enforced on one of two paths reads as a bug on the
 * other.
 *
 * <h2>{@code created_by} is what {@code OWN} resolves against</h2>
 *
 * <p>{@code evidence:delete} is {@code OWN}-qualified for Test Engineer (roles spec §A5), and this
 * is the column that answers it — the same shape, and the same nullable arm, as
 * {@code test_case.created_by} in {@code repository.CaseDeleteService}. Nullable and no FK, so a row
 * with no recorded author is possible and is <strong>not</strong> anybody's own work.
 */
@Entity
@Table(name = "evidence")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Evidence {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    /** Null ⇒ attached to the execution as a whole rather than to one step's result. */
    @Column(name = "step_result_id", updatable = false)
    private UUID stepResultId;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "caption")
    private String caption;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected Evidence() {
    }

    static Evidence attach(
            UUID companyId,
            UUID executionId,
            UUID stepResultId,
            UUID attachmentId,
            String caption,
            UUID userId,
            Instant now) {

        Evidence evidence = new Evidence();
        evidence.id = UUID.randomUUID();
        evidence.companyId = companyId;
        evidence.executionId = executionId;
        evidence.stepResultId = stepResultId;
        evidence.attachmentId = attachmentId;
        evidence.caption = caption;
        evidence.createdAt = now;
        evidence.createdBy = userId;
        return evidence;
    }

    UUID getId() {
        return id;
    }

    UUID getExecutionId() {
        return executionId;
    }

    UUID getStepResultId() {
        return stepResultId;
    }

    UUID getAttachmentId() {
        return attachmentId;
    }

    String getCaption() {
        return caption;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * The row's author, <strong>nullable</strong>. Handed to
     * {@link com.tcms.project.AuthorshipGate#mayActOn} — a row nobody can attribute is not the
     * caller's own work, so an {@code OWN} grant does not cover it.
     */
    UUID getCreatedBy() {
        return createdBy;
    }
}
