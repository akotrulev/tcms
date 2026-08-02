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
public interface ReviewCommentRepository extends JpaRepository<ReviewComment, UUID> {

    /**
     * Every comment on a set of reviews, oldest first — <strong>one query for the whole
     * response</strong>, the shape {@link ReviewParticipationRepository#findForReviews} already has
     * and for the same reason.
     *
     * <p>Ascending, because the service assembles threads from a flat list and a root must be seen
     * before the replies that name it. Ordering by {@code createdAt} alone is not enough: two
     * remarks written in the same microsecond would come back in whatever order the scan produced,
     * and a reply rendered above its own root reads as a defect.
     */
    @Query("""
            SELECT c FROM ReviewComment c
             WHERE c.reviewId IN :reviewIds
             ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<ReviewComment> findForReviews(@Param("reviewIds") Collection<UUID> reviewIds);

    /**
     * One comment by id, <strong>within a review</strong>.
     *
     * <p>Never {@code findById}: RLS scopes that to the <em>company</em>, so it accepts a comment id
     * belonging to another project's review — §69's {@code findForProject} rule, §64's
     * {@code sharedStepId} and §71's team membership, the same family each time. Both writers here
     * need it. The resolution endpoint has already had the <em>review</em> gated by project, so
     * resolving through this bounds the act to the row the gate actually examined; and a reply's
     * parent resolves through it too, or a client could thread a remark onto a conversation in a
     * project it holds no grant on and have it render there.
     */
    @Query("""
            SELECT c FROM ReviewComment c
             WHERE c.reviewId = :reviewId
               AND c.id = :commentId
            """)
    Optional<ReviewComment> findInReview(
            @Param("reviewId") UUID reviewId, @Param("commentId") UUID commentId);
}
