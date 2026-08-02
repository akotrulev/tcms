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
 * One request to review — <strong>who was <em>asked</em></strong>, which is the last fact §6g's
 * apparatus was missing.
 *
 * <p>{@code review_participation} has recorded who <em>spoke</em> since §76, and until {@code V25}
 * nothing recorded who was asked to. The consequence was that the workflow §76–§79 built had no
 * inbox: a review opened and the people who should look at it found out through some other product.
 * §6l's board states the same gap from the other end — <em>"Review requested (§6g) → card for each
 * reviewer"</em> — and it cannot draw a card for a request nothing stored.
 *
 * <h2>A worklist entry, not part of the audit record</h2>
 *
 * <p>This is the distinction the whole class rests on, and {@code V25}'s header carries it in full.
 * The record §6g wants for <em>"proving to an auditor that someone competent signed off"</em> is
 * {@link Review} plus {@link ReviewParticipation}: who submitted, who spoke, who signed. A request
 * is a routing hint, live only while the review is open. So there is no {@code removed_at} and no
 * {@link #withdraw}-shaped tombstone — {@link ReviewAssignmentRepository#delete} removes the row,
 * because asking the wrong person and correcting it should leave nothing behind, and a trail of
 * retracted requests reads as a history of suspicion.
 *
 * <h2>A user or a team, never both and never neither</h2>
 *
 * <p>§6b's shape, and the one {@code role_assignment} already uses: two nullable columns under a
 * {@code num_nonnulls(...) = 1} check. {@link #isTeam()} is the only branch any consumer needs,
 * because §6g says <em>"any member of the team can action it"</em> — a team assignment is not
 * expanded into per-member rows here, and deliberately: membership changes, and expanding it at
 * assignment time would freeze the team as it was on the day somebody clicked, which is the same
 * class of defect as {@link Review#getRequiredPeerReviews()} solved in the opposite direction. The
 * difference is that a required-count is a rule the reviewer was asked under, and a team is a
 * moving list of people the request is addressed to.
 *
 * <h2>Neither foreign key proves membership</h2>
 *
 * <p>{@code assignee_user_id} references instance-level {@code app_user}, so a sibling company's
 * account satisfies the FK, the company column, RLS and every constraint — §71's team-membership
 * defect one table over, and the fourth column in §64's family. The check that matters is therefore
 * in {@link ReviewAssigneeEdit}, not here, and it is project visibility rather than company
 * membership: a person who cannot see the project cannot open the artifact, so asking them produces
 * a request they can never action.
 */
@Entity
@Table(name = "review_assignment")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ReviewAssignment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    /** The person asked, or null when {@link #assigneeTeamId} names a team instead. */
    @Column(name = "assignee_user_id", updatable = false)
    private UUID assigneeUserId;

    /** The team asked, or null when {@link #assigneeUserId} names a person instead. */
    @Column(name = "assignee_team_id", updatable = false)
    private UUID assigneeTeamId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    /**
     * Who asked. Nullable like every other actor column here (§66) — an offboarded account does not
     * un-ask the question.
     */
    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;

    /** JPA only. */
    protected ReviewAssignment() {
    }

    /**
     * Asks one person to review.
     *
     * <p>Every column is {@code updatable = false} and there is no mutator on this class at all: an
     * assignment is made or removed, never edited. Re-asking somebody already assigned is handled
     * one level up by writing <strong>nothing</strong> — see {@link ReviewService#assign}, and §79
     * for the defect that decision prevents.
     */
    static ReviewAssignment ofUser(
            UUID companyId, UUID reviewId, UUID userId, UUID assignedBy, Instant at) {
        return of(companyId, reviewId, userId, null, assignedBy, at);
    }

    /** Asks a team to review; any member of it may action it (§6g). */
    static ReviewAssignment ofTeam(
            UUID companyId, UUID reviewId, UUID teamId, UUID assignedBy, Instant at) {
        return of(companyId, reviewId, null, teamId, assignedBy, at);
    }

    private static ReviewAssignment of(
            UUID companyId,
            UUID reviewId,
            UUID assigneeUserId,
            UUID assigneeTeamId,
            UUID assignedBy,
            Instant at) {

        ReviewAssignment assignment = new ReviewAssignment();
        assignment.id = UUID.randomUUID();
        assignment.companyId = companyId;
        assignment.reviewId = reviewId;
        assignment.assigneeUserId = assigneeUserId;
        assignment.assigneeTeamId = assigneeTeamId;
        assignment.assignedBy = assignedBy;
        assignment.assignedAt = at;
        return assignment;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    public UUID getAssigneeTeamId() {
        return assigneeTeamId;
    }

    /** Whether this row addresses a team rather than one person. The only branch consumers need. */
    public boolean isTeam() {
        return assigneeTeamId != null;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }
}
