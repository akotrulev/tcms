package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One pass of review over one version of one artifact — the record §6g says a team actually wants
 * review for: <em>"proving to an auditor that someone competent signed off"</em>.
 *
 * <p>The table has been in {@code V1} since the beginning with no entity, no native query and no
 * writer of any kind. Its own column comment states the rule this class implements —
 * <em>"an approval applies to a SPECIFIC version, including its snapshotted conditions"</em> — and
 * nothing has ever been in a position to obey it.
 *
 * <h2>A review is history, not a mirror of the artifact's status</h2>
 *
 * <p>The subject's own status ladder is the live fact: a case is {@code APPROVED} or it is not. A
 * review row says what happened <em>once</em>, at a version, by a named person, at a time — so
 * rescinding an approval does not rewrite the review that granted it, and re-submitting opens a new
 * row rather than reopening the old one. Rows accumulate; only the open one is exclusive, which is
 * what {@code V22}'s partial unique index enforces.
 *
 * <p>That is also why {@link #subjectVersionNo} is stamped at submission and never re-stamped. An
 * artifact edited while it is in review is exactly §6g's diff-since-approval case: the review still
 * names the version somebody was asked to read, and the client can see the artifact has moved past
 * it. Re-stamping would silently transfer a reviewer's attention to content they never saw.
 *
 * <h2>Four states, and the fourth is {@code V22}'s</h2>
 *
 * <p>{@code V1} allowed {@code IN_REVIEW}, {@code APPROVED} and {@code REJECTED}. The ladders that
 * shipped in §62/§73/§75 let the person who submitted an artifact take it back at the <em>edit</em>
 * key — §6g: submitting for review "needs only edit rights" — so the same downward edge is walked
 * by two different acts, and closing both as {@code REJECTED} writes a rejection nobody performed
 * into the one record kept to show who said what. Hence {@code WITHDRAWN}; see {@code V22}'s header.
 */
@Entity
@Table(name = "review")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Review {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private ReviewSubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    /** The version submitted, pinned once — see the class header. */
    @Column(name = "subject_version_no", nullable = false, updatable = false)
    private int subjectVersionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ReviewState state;

    /**
     * §6g's "configurable minimum peer reviews before approval is offered (default 0)".
     *
     * <p><strong>Copied from {@link ReviewSettings#requiredPeerReviews()} at submission, and read
     * from here rather than from the project.</strong> That is the whole reason the column is on
     * this table instead of only on {@code project_settings}: a lead who raises the bar from 2 to 4
     * must not thereby freeze every review already in flight, whose reviewers were asked under the
     * old rule and some of whom have already spoken. The setting governs reviews opened after it;
     * this column governs this one.
     *
     * <p>Until {@code V23} there was nowhere for the setting to live, so this was stamped from the
     * row's default and nothing read it — the state the class header used to describe, and which
     * that comment named this slice as the fix for. Rows written before {@code V23} carry {@code 0}
     * and are therefore unaffected, which is the correct reading of a review opened when the product
     * had no such requirement.
     */
    @Column(name = "required_peer_reviews", nullable = false)
    private int requiredPeerReviews;

    /** Who signed it off. Null unless {@link #state} is {@link ReviewState#APPROVED}. */
    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Who submitted it. Nullable in the schema, so every reader must handle an unattributed row. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /**
     * {@code @Version}, unlike {@code TestRun} and {@code work.WorkItem}, which map the column
     * without it (§74's finding, still latent there). Nothing sends an {@code If-Match} for a review
     * — but two people closing one review is a real race with a real database behind it, and a
     * mapped {@code lock_version} that Hibernate does not maintain is a column that reads {@code 0}
     * for ever while looking like a guard.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Review() {
    }

    /**
     * Opens a review over a subject's current version.
     *
     * @param requiredPeerReviews the project's setting <em>at this moment</em>, pinned onto the row
     *                            — see {@link #requiredPeerReviews}
     */
    static Review open(
            UUID companyId,
            UUID projectId,
            ReviewSubjectType subjectType,
            UUID subjectId,
            int subjectVersionNo,
            int requiredPeerReviews,
            UUID submittedBy,
            Instant at) {

        Review opened = new Review();
        opened.id = UUID.randomUUID();
        opened.companyId = companyId;
        opened.projectId = projectId;
        opened.subjectType = subjectType;
        opened.subjectId = subjectId;
        opened.subjectVersionNo = subjectVersionNo;
        opened.state = ReviewState.IN_REVIEW;
        opened.requiredPeerReviews = requiredPeerReviews;
        opened.createdAt = at;
        opened.createdBy = submittedBy;
        return opened;
    }

    /**
     * Signs the artifact off.
     *
     * <p>{@link #approvedBy} and {@link #approvedAt} are set only here, so a rejected or withdrawn
     * review can never carry an approver — a state that would read, to anybody joining the two
     * columns, as an approval that was somehow undone.
     */
    void approve(UUID approver, Instant at) {
        this.state = ReviewState.APPROVED;
        this.approvedBy = approver;
        this.approvedAt = at;
    }

    /**
     * Ends the review without a sign-off.
     *
     * @param withdrawn true when the person who submitted it is the one taking it back. The
     *                  distinction is derived from the two ids rather than sent, because a client
     *                  that could nominate it could record a rejection as a withdrawal.
     */
    void close(boolean withdrawn) {
        this.state = withdrawn ? ReviewState.WITHDRAWN : ReviewState.REJECTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public ReviewSubjectType getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public int getSubjectVersionNo() {
        return subjectVersionNo;
    }

    public ReviewState getState() {
        return state;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    /** How many peer LGTMs this review needs before it may be approved — pinned at submission. */
    public int getRequiredPeerReviews() {
        return requiredPeerReviews;
    }
}
