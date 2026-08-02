package com.tcms.project;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method runs
// outside any transaction, app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface ReviewParticipationRepository extends JpaRepository<ReviewParticipation, UUID> {

    /**
     * Every contribution to a set of reviews, oldest first — <strong>one query for the whole
     * response</strong>, not one per review.
     *
     * <p>The subject's history is short but not one row, and resolving participations per review is
     * the N+1 that looks harmless because each query is fast ({@code CaseOwners}' reason, one module
     * over). The service groups by {@code reviewId}.
     *
     * <p>Ascending, because a participation list is read as a conversation: the request for changes
     * comes before the LGTM that answers it.
     */
    @Query("""
            SELECT p FROM ReviewParticipation p
             WHERE p.reviewId IN :reviewIds
             ORDER BY p.createdAt ASC, p.id ASC
            """)
    List<ReviewParticipation> findForReviews(@Param("reviewIds") Collection<UUID> reviewIds);
}
