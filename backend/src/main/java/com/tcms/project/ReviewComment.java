package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One remark on one review — §6g's <em>"threaded, anchored to a specific step or field where
 * possible, resolvable"</em>, and the reason the section gives for wanting them at all:
 * <em>"Review without inline comments degrades into Slack conversations that vanish."</em>
 *
 * <p>{@code review_comment} was the last of the three review tables with no entity, no query and no
 * writer of any kind — {@code review} and {@code review_participation} got theirs in §76, and §77
 * named this one on its way out.
 *
 * <h2>A comment belongs to a REVIEW, not to an artifact</h2>
 *
 * <p>{@code review_id} is {@code NOT NULL} and there is no artifact-side column, so there is nowhere
 * to put a remark about a case nobody has submitted. That is {@code V1}'s design and it is the right
 * one for §6g: commenting is part of the review workflow, which is optional and off by default, and
 * §6i says in as many words that documentation has <em>"no comment threads in v1 beyond what §6g's
 * review provides"</em>. The consequence is worth stating rather than discovering — a Viewer holding
 * {@code case:comment} can comment on work that is <em>in review</em> and not on work that is not,
 * and the panel's "Not in review" empty state is already the honest answer to that.
 *
 * <h2>Threads are one level deep</h2>
 *
 * <p>{@link #parentId} names the root a reply answers, and {@link ReviewCommentEdit} refuses a reply
 * whose parent is itself a reply. Unbounded nesting has no bottom to render and no natural place to
 * resolve — §6g asks for threads, which are a root and its replies, not for a tree. Resolution is
 * therefore a property of the root: {@link #resolve} is refused on a reply, because half a
 * conversation cannot be dealt with.
 *
 * <h2>The anchor is deliberately not written</h2>
 *
 * <p>{@code anchor_type} and {@code anchor_id} stay NULL on every row this class writes, and they
 * are not mapped. §6g's "where possible" is doing real work: the only anchor the product has a
 * candidate for is a case's step, whose ids live in {@code repository.test_step} — a table this
 * module may not import, for the reason {@link ReviewService}'s header gives. That leaves two
 * honest options and one dishonest one. A port in the {@link PeopleDirectory} shape, implemented in
 * {@code repository}, is the honest expensive one; leaving it out is the honest cheap one. Accepting
 * an id nothing validates is §64's {@code sharedStepId} — a reference persisted unchecked since M1,
 * found four milestones later — written down in advance, and it is refused here for that reason.
 * The port is named so the slice that wants inline anchors knows what it is buying.
 */
@Entity
@Table(name = "review_comment")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ReviewComment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    /** The root this replies to, or null when this is a root. Never re-parented. */
    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    /** What was said. Immutable — see {@link #resolve}. */
    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** {@code V24}'s column: the act named its time and not its actor. */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Nullable in the schema, so every reader must handle an unattributed remark. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected ReviewComment() {
    }

    static ReviewComment of(
            UUID companyId,
            UUID reviewId,
            UUID parentId,
            String body,
            UUID author,
            Instant at) {

        ReviewComment row = new ReviewComment();
        row.id = UUID.randomUUID();
        row.companyId = companyId;
        row.reviewId = reviewId;
        row.parentId = parentId;
        row.body = body;
        row.createdAt = at;
        row.createdBy = author;
        return row;
    }

    /**
     * Marks the thread dealt with, or re-opens it.
     *
     * <p>The <strong>only</strong> mutation on this class, and the reason {@link #body} is
     * {@code updatable = false}: a review trail whose entries can be rewritten proves nothing to the
     * auditor §6g keeps it for, and an edited remark would silently change what a reviewer is
     * recorded as having said after somebody answered it. Retracting is a decision this slice does
     * not make — {@link ReviewCommentEdit} says what it would cost.
     *
     * <p>Both columns move together, in both directions. {@code V24}'s CHECK is the backstop: a
     * resolver with no timestamp is a half-applied row.
     */
    void resolve(boolean resolved, UUID actor, Instant at) {
        this.resolvedAt = resolved ? at : null;
        this.resolvedBy = resolved ? actor : null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getBody() {
        return body;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    /** Whether this is a thread root. Replies may not be replied to, nor resolved. */
    public boolean isRoot() {
        return parentId == null;
    }

    /**
     * Whether the thread is dealt with.
     *
     * <p>Read from {@link #resolvedAt} and never from {@link #resolvedBy}, because the actor is
     * nullable on its own terms — an unattributable resolution is still a resolution, which is what
     * {@code V24}'s one-directional CHECK says.
     */
    public boolean isResolved() {
        return resolvedAt != null;
    }
}
