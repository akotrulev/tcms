package com.tcms.tracker;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One legal move, from one status to another.
 *
 * <p><strong>An allow-list, not a deny-list.</strong> A pair with no row here is refused, so a
 * status added to a workflow reaches nothing until somebody says where it goes — story H5's "only
 * the moves the project workflow allows are offered", and a forbidden drop on the board is refused
 * with the reason rather than snapping back in silence.
 *
 * <p>There is no self-pair and there never is one: {@code ck_issue_transition_distinct} refuses it,
 * and {@link TransitionService} answers "a status is a legal destination from itself" without a row.
 * Storing it would make "no moves allowed" and "only the current one" the same set.
 */
@Entity
@Table(name = "issue_transition")
@IdClass(IssueTransitionId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueTransition {

    @Id
    @Column(name = "from_status_id", nullable = false, updatable = false)
    private UUID fromStatusId;

    @Id
    @Column(name = "to_status_id", nullable = false, updatable = false)
    private UUID toStatusId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** JPA only. */
    protected IssueTransition() {
    }

    static IssueTransition allow(UUID companyId, UUID projectId, UUID fromStatusId, UUID toStatusId) {
        IssueTransition allowed = new IssueTransition();
        allowed.companyId = companyId;
        allowed.projectId = projectId;
        allowed.fromStatusId = fromStatusId;
        allowed.toStatusId = toStatusId;
        return allowed;
    }

    public UUID getFromStatusId() {
        return fromStatusId;
    }

    public UUID getToStatusId() {
        return toStatusId;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getProjectId() {
        return projectId;
    }
}
