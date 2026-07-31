package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface CaseConditionRepository extends JpaRepository<CaseCondition, UUID> {

    /**
     * One case's own condition attachments, both kinds in one query.
     *
     * <p>Both kinds together rather than one call per list: the caller has to bucket by
     * {@link ConditionKind} anyway to build the two arrays, and splitting it into two queries
     * doubles the round trips to save a partition the caller performs in a loop it already writes.
     *
     * <p><strong>{@code conditionId IS NOT NULL} excludes inline attachments</strong>, on exactly
     * the terms {@link SuiteConditionRepository#findForSuites} sets out — the contract requires a
     * title, an inline row has no column to hold one, and no writer can create one today. The
     * predicate is here rather than in the service so the exclusion is visible next to the
     * selection.
     *
     * <p>Ordered by {@code kind} then {@code position}: the author's order is the reading order,
     * and a condition list is a procedure where "log in" before "navigate to Billing" is the
     * content. Ordering by kind first is what makes the caller's partition a single pass; {@code
     * id} breaks the remaining ties so the list is the same on two consecutive reads even where
     * nothing makes {@code (case_id, kind, position)} unique.
     *
     * <p>Served by {@code case_condition_case_idx (case_id, kind, position)} from {@code V1}.
     */
    @Query("""
            SELECT cc FROM CaseCondition cc
             WHERE cc.caseId = :caseId
               AND cc.conditionId IS NOT NULL
             ORDER BY cc.kind ASC, cc.position ASC, cc.id ASC
            """)
    List<CaseCondition> findForCase(@Param("caseId") UUID caseId);
}
