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
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /**
     * The open review for a subject, if there is one.
     *
     * <p>At most one can exist — {@code V22}'s partial unique index is the backstop — and this query
     * is how {@link ReviewService} decides whether a status move opens a review or closes one.
     * Reading the artifact's <em>previous</em> status instead would work equally well until the two
     * disagree, and the row is the fact that matters here: a subject with no open review has nothing
     * to close, whatever its ladder thought it was doing.
     *
     * <p>Scoped by project as well as by subject id, so a subject id belonging to another project in
     * the same company cannot resolve — RLS bounds this query to the company, never to the project.
     */
    @Query("""
            SELECT r FROM Review r
             WHERE r.projectId = :projectId
               AND r.subjectType = :subjectType
               AND r.subjectId = :subjectId
               AND r.state = com.tcms.project.ReviewState.IN_REVIEW
            """)
    Optional<Review> findOpenForSubject(
            @Param("projectId") UUID projectId,
            @Param("subjectType") ReviewSubjectType subjectType,
            @Param("subjectId") UUID subjectId);

    /**
     * The open reviews for a set of subjects — {@link #findOpenForSubject}'s batch, and the reason
     * it exists is {@code CaseBulkService}.
     *
     * <p>A bulk approve of four hundred cases has to answer §6g's approval preconditions for every
     * row, and asking {@link #findOpenForSubject} per row is the N+1 that looks harmless because
     * each query is fast — {@code CaseOwners}' reason and {@code findForReviews}' beside it. Two
     * queries serve the whole operation: this one, then the participations of whatever it returns.
     */
    @Query("""
            SELECT r FROM Review r
             WHERE r.projectId = :projectId
               AND r.subjectType = :subjectType
               AND r.subjectId IN :subjectIds
               AND r.state = com.tcms.project.ReviewState.IN_REVIEW
            """)
    List<Review> findOpenForSubjects(
            @Param("projectId") UUID projectId,
            @Param("subjectType") ReviewSubjectType subjectType,
            @Param("subjectIds") Collection<UUID> subjectIds);

    /**
     * Every review of a subject, newest first — the subject's review history.
     *
     * <p>Ordered by {@code createdAt} descending with the id as the tie-break, because two reviews
     * of one subject created in the same microsecond would otherwise come back in whatever order the
     * scan produced, and the client renders the first row as "the current one".
     */
    @Query("""
            SELECT r FROM Review r
             WHERE r.projectId = :projectId
               AND r.subjectType = :subjectType
               AND r.subjectId = :subjectId
             ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<Review> findForSubject(
            @Param("projectId") UUID projectId,
            @Param("subjectType") ReviewSubjectType subjectType,
            @Param("subjectId") UUID subjectId);

    /**
     * One review by id, within a project.
     *
     * <p>{@code findById} would be scoped by RLS to the <em>company</em> and not to the project, so
     * it accepts a sibling project's review id — §69's {@code findForProject} rule, and the same
     * family as §64's {@code sharedStepId} and §68's classification value. The participation
     * endpoint gates on a permission held per project, so resolving through the wrong project would
     * ask the right question about the wrong row.
     */
    /** Open reviews per subject type — the review-owned half of {@code ReviewImpact}. */
    @Query("""
            SELECT r.subjectType AS subjectType, COUNT(r) AS open
              FROM Review r
             WHERE r.projectId = :projectId
               AND r.state = com.tcms.project.ReviewState.IN_REVIEW
             GROUP BY r.subjectType
            """)
    List<OpenCount> countOpenBySubjectType(@Param("projectId") UUID projectId);

    interface OpenCount {

        ReviewSubjectType getSubjectType();

        long getOpen();
    }

    /**
     * Open reviews with at least one {@code LGTM} from somebody other than the submitter — the ones
     * a raised {@code requiredPeerReviews} would send back for more.
     */
    @Query("""
            SELECT COUNT(DISTINCT r.id)
              FROM Review r, ReviewParticipation p
             WHERE r.projectId = :projectId
               AND r.state = com.tcms.project.ReviewState.IN_REVIEW
               AND p.reviewId = r.id
               AND p.verdict = :verdict
               AND p.userId <> r.createdBy
            """)
    long countOpenWithPeerVerdict(
            @Param("projectId") UUID projectId,
            @Param("verdict") ReviewParticipation.Verdict verdict);

    default long countOpenAwaitingFinalApproval(UUID projectId) {
        return countOpenWithPeerVerdict(projectId, ReviewParticipation.Verdict.LGTM);
    }

    @Query("""
            SELECT r FROM Review r
             WHERE r.projectId = :projectId
               AND r.id = :reviewId
            """)
    Optional<Review> findInProject(
            @Param("projectId") UUID projectId, @Param("reviewId") UUID reviewId);
}
