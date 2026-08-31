package com.tcms.tracker;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One thing somebody said about an issue.
 *
 * <p>{@code edited_at} is mapped and never written. The column exists because the contract's
 * {@code IssueComment} carries it and a client that renders "edited" needs somewhere to read it
 * from; editing a comment is not built, so the value is null on every row. That is stated here
 * rather than left for a reader to infer from a missing mutator — an unused column with no
 * explanation reads as a defect, and this one is a decision.
 */
@Entity
@Table(name = "issue_comment")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueComment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "edited_at")
    private Instant editedAt;

    /** JPA only. */
    protected IssueComment() {
    }

    static IssueComment write(
            UUID companyId, UUID issueId, String body, UUID author, Instant now) {

        IssueComment comment = new IssueComment();
        comment.id = UUID.randomUUID();
        comment.companyId = companyId;
        comment.issueId = issueId;
        comment.body = body;
        comment.createdAt = now;
        comment.createdBy = author;
        return comment;
    }

    public UUID getId() {
        return id;
    }

    UUID getIssueId() {
        return issueId;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getEditedAt() {
        return editedAt;
    }
}
