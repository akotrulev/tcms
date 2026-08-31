package com.tcms.tracker;

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
 * Who is following an issue (story H8).
 *
 * <p><strong>An author is added here when they comment.</strong> Without that people say something,
 * the conversation continues, and they never hear about it — which is how a comment thread on an
 * issue tracker dies. Following by default is what makes a reply reach the person it answers, and
 * the star is then only for the case where somebody wants to follow work they have not spoken about.
 *
 * <p>The pair is the identity, so the row's presence <em>is</em> the state and there is nothing on it
 * to toggle. That is what lets the endpoint be a {@code PUT} with a boolean, idempotent in both
 * directions: two clicks on a slow connection cannot toggle a star back.
 */
@Entity
@Table(name = "issue_watcher")
@IdClass(IssueWatcherId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueWatcher {

    @Id
    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected IssueWatcher() {
    }

    static IssueWatcher follow(UUID companyId, UUID issueId, UUID userId, Instant now) {
        IssueWatcher watcher = new IssueWatcher();
        watcher.companyId = companyId;
        watcher.issueId = issueId;
        watcher.userId = userId;
        watcher.createdAt = now;
        return watcher;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getUserId() {
        return userId;
    }
}
