package com.tcms.tracker.board;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One label on one issue.
 *
 * <p>A join row rather than a {@code @ManyToMany}: the set is written whole by
 * {@code PUT /issues/{key}/labels}, and the board reads it back grouped by issue in one query —
 * neither of which wants Hibernate managing a collection on {@code Issue}, which would make every
 * issue read carry a second one.
 */
@Entity
@Table(name = "issue_label_assignment")
@IdClass(IssueLabelAssignmentId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueLabelAssignment {

    @Id
    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Id
    @Column(name = "label_id", nullable = false, updatable = false)
    private UUID labelId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected IssueLabelAssignment() {
    }

    static IssueLabelAssignment of(UUID companyId, UUID issueId, UUID labelId, Instant now) {
        IssueLabelAssignment assigned = new IssueLabelAssignment();
        assigned.companyId = companyId;
        assigned.issueId = issueId;
        assigned.labelId = labelId;
        assigned.createdAt = now;
        return assigned;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getLabelId() {
        return labelId;
    }
}
