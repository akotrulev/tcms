package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One person's contribution to one review — §6g's two tiers, in one table, as {@code V1} designed
 * it: <em>"Separates a Test Engineer advisory review from a Test Lead binding approval."</em>
 *
 * <h2>Append-only, and that is a decision rather than an omission</h2>
 *
 * <p>Nothing updates a row here and there is no uniqueness constraint on (review, user): a reviewer
 * who requests changes and later says LGTM has <strong>two</strong> rows, in that order.
 * Overwriting would leave a record saying they always agreed, which is precisely the fact a review
 * trail exists to keep. Readers take the newest row per person as the standing verdict; the whole
 * list is the history.
 *
 * <h2>Which rows may move the review, and which may not</h2>
 *
 * <p>{@code V1}'s column comment says only {@code APPROVAL} rows may transition {@code review.state},
 * and that holds: {@link ReviewService} writes those two itself, from the artifact's own status
 * ladder, and the participation endpoint can only ever write {@link Kind#PEER_REVIEW}. A peer
 * verdict is advisory by construction rather than by convention — there is no code path from
 * {@code POST …/participations} to a state change.
 *
 * <p>The second half of that comment — that creating an {@code APPROVAL} row requires the
 * {@code *:approve} permission — is <strong>narrower than what shipped</strong>, and deliberately.
 * Approving does cost the approve key (§6g's single gate, enforced by the three ladders). Sending an
 * artifact back does not: §73 and §75 put {@code IN_REVIEW → DRAFT} on the edit key, because a
 * reviewer who may not decline is not reviewing anything. So a rejection is an {@code APPROVAL}-kind
 * row written by an editor, and that is the ladder's rule, not this table's.
 */
@Entity
@Table(name = "review_participation")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ReviewParticipation {

    /** Advisory or binding — §6g's two tiers. */
    public enum Kind {

        /** A peer's opinion. Moves nothing, costs {@code *:review}. */
        PEER_REVIEW,

        /** The act that ended the review. Written by {@link ReviewService}, never by a request. */
        APPROVAL
    }

    /** What was said. The first two are a peer's; the last two end the review. */
    public enum Verdict {

        /** Looks good to me. */
        LGTM,

        /** Changes requested — advisory, and it does not move the artifact off {@code IN_REVIEW}. */
        CHANGES_REQUESTED,

        /** Sent back by somebody other than the submitter. */
        REJECTED,

        /** Signed off. */
        APPROVED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, updatable = false)
    private Verdict verdict;

    /** Optional, and the only free text on a review until {@code review_comment} gets a writer. */
    @Column(name = "comment", updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected ReviewParticipation() {
    }

    static ReviewParticipation of(
            UUID companyId,
            UUID reviewId,
            UUID userId,
            Kind kind,
            Verdict verdict,
            String comment,
            Instant at) {

        ReviewParticipation row = new ReviewParticipation();
        row.id = UUID.randomUUID();
        row.companyId = companyId;
        row.reviewId = reviewId;
        row.userId = userId;
        row.kind = kind;
        row.verdict = verdict;
        row.comment = comment;
        row.createdAt = at;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Kind getKind() {
        return kind;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
