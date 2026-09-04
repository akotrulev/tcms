package com.tcms.time;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * This module's own projection of hours per issue (016 D-c).
 *
 * <p><strong>One writer, and it is {@link RollupService}.</strong> Note which table this is not: the
 * tracker's issue projection has exactly one writer of its own, and a downstream module writing
 * <em>there</em> is the failure scope §3 names by name. This module reads that one through
 * {@link IssueFacts} and writes only here.
 *
 * <p>At the <strong>issue</strong> grain only. The story, epic and project figures are aggregated at
 * read time, because three stored grains would be three tables that can disagree about the same
 * hours.
 *
 * <p>{@link #approvedHours} and {@link #pendingHours} are separate columns (016 D-d) because
 * {@code epic.html} says "approved hours only", {@code story.html} says "14h (approved)" and the
 * timesheet's own KPI counts hours nobody has approved. One number could not say all three, and
 * nothing sums them.
 */
@Entity
@Table(name = "issue_time_rollup")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueTimeRollup {

    @Id
    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "approved_hours", nullable = false)
    private BigDecimal approvedHours;

    @Column(name = "pending_hours", nullable = false)
    private BigDecimal pendingHours;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected IssueTimeRollup() {
    }

    static IssueTimeRollup of(UUID issueId, UUID companyId, UUID projectId, Instant now) {
        IssueTimeRollup created = new IssueTimeRollup();
        created.issueId = issueId;
        created.companyId = companyId;
        created.projectId = projectId;
        created.approvedHours = BigDecimal.ZERO;
        created.pendingHours = BigDecimal.ZERO;
        created.updatedAt = now;
        return created;
    }

    void restate(UUID projectId, BigDecimal approved, BigDecimal pending, Instant now) {
        this.projectId = projectId;
        this.approvedHours = approved;
        this.pendingHours = pending;
        this.updatedAt = now;
    }

    UUID getIssueId() {
        return issueId;
    }

    BigDecimal getApprovedHours() {
        return approvedHours;
    }

    BigDecimal getPendingHours() {
        return pendingHours;
    }
}
