package com.tcms.project;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method runs
// outside any transaction, app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface ReviewAssignmentRepository extends JpaRepository<ReviewAssignment, UUID> {

    /**
     * Every request against a set of reviews, oldest first — <strong>one query for the whole
     * response</strong>, the shape {@link ReviewParticipationRepository#findForReviews} and
     * {@link ReviewCommentRepository#findForReviews} already have, for the third time.
     *
     * <p>Ascending by {@code assignedAt} with the id as the tie-break, because two people asked in
     * the same microsecond would otherwise come back in whatever order the scan produced and the
     * panel's list of who was asked would reorder itself between renders.
     */
    @Query("""
            SELECT a FROM ReviewAssignment a
             WHERE a.reviewId IN :reviewIds
             ORDER BY a.assignedAt ASC, a.id ASC
            """)
    List<ReviewAssignment> findForReviews(@Param("reviewIds") Collection<UUID> reviewIds);

    /**
     * One assignment by id, <strong>within a review</strong>.
     *
     * <p>Never {@code findById}: RLS scopes that to the <em>company</em>, so it accepts an
     * assignment belonging to a <em>sibling project's</em> review, and the caller would then
     * withdraw a request in a project it holds no grant on — §64's {@code sharedStepId}, §68's
     * classification value, §69's dimension, §71's team membership and §79's comment parent, the
     * same family a sixth time. The withdrawal endpoint has already had the review gated by
     * project, so resolving through the review bounds the act to the row the gate examined.
     */
    @Query("""
            SELECT a FROM ReviewAssignment a
             WHERE a.reviewId = :reviewId
               AND a.id = :assignmentId
            """)
    Optional<ReviewAssignment> findInReview(
            @Param("reviewId") UUID reviewId, @Param("assignmentId") UUID assignmentId);

    /**
     * The existing request naming this person on this review, if there is one.
     *
     * <p>What makes re-assigning a no-op rather than a duplicate row or a constraint violation.
     * {@code V25}'s partial unique index is the backstop behind it, in {@code V20}'s and
     * {@code V21}'s sense — this query is the check, and it is what lets the service leave
     * {@code assigned_at}/{@code assigned_by} on the row that already exists rather than re-stamping
     * them onto whoever clicked last (§79's resolution defect).
     */
    @Query("""
            SELECT a FROM ReviewAssignment a
             WHERE a.reviewId = :reviewId
               AND a.assigneeUserId = :userId
            """)
    Optional<ReviewAssignment> findForUser(
            @Param("reviewId") UUID reviewId, @Param("userId") UUID userId);

    /** {@link #findForUser}'s team half, and for its reason. */
    @Query("""
            SELECT a FROM ReviewAssignment a
             WHERE a.reviewId = :reviewId
               AND a.assigneeTeamId = :teamId
            """)
    Optional<ReviewAssignment> findForTeam(
            @Param("reviewId") UUID reviewId, @Param("teamId") UUID teamId);
}
