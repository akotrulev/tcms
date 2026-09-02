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

    /**
     * The source system's own author string for an imported comment nobody here answers to —
     * {@code j.arnold@old-vendor.test} (plan 014 D-j).
     *
     * <p><strong>Never set together with {@link #createdBy}</strong>, so no screen has to choose
     * between them: the importer matches the source author by email and writes one column or the
     * other. {@code import-report.html}'s People panel is the promise this keeps — an unmatched
     * author is preserved as text rather than reassigned to somebody who did not write the comment.
     *
     * <p>Not a prefix on the body — <em>"Originally by …"</em> — which would edit somebody's words
     * to carry metadata and would come back out as content in the next export.
     */
    @Column(name = "external_author", updatable = false)
    private String externalAuthor;

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

    /**
     * A comment read out of somebody else's tracker.
     *
     * <p>Public because {@code tracker.imports} is the caller and lives in a sub-package —
     * {@link Issue#imported}'s reason. Exactly one of {@code author} and {@code externalAuthor} is
     * non-null, and that is the invariant {@link #externalAuthor} exists to state; the importer is
     * the one writer and its match is what decides which.
     *
     * @param createdAt the source's own timestamp, kept: a comment thread re-dated to the import is
     *                  a thread nobody can read in order
     */
    public static IssueComment imported(
            UUID companyId,
            UUID issueId,
            String body,
            UUID author,
            String externalAuthor,
            Instant createdAt) {

        IssueComment comment = new IssueComment();
        comment.id = UUID.randomUUID();
        comment.companyId = companyId;
        comment.issueId = issueId;
        comment.body = body;
        comment.createdAt = createdAt;
        comment.createdBy = author;
        comment.externalAuthor = author == null ? externalAuthor : null;
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

    public String getExternalAuthor() {
        return externalAuthor;
    }
}
