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
public interface TestStepRepository extends JpaRepository<TestStep, UUID> {

    /**
     * One case's steps, in the order they are executed.
     *
     * <p>Keyed on the case rather than on a set of cases, unlike every other lookup in this module.
     * That is the detail endpoint's shape and not an oversight: the grid does not render steps, so
     * there is no page to batch, and a method taking a collection would invite a future list screen
     * to load every step of 200 cases to show a count.
     *
     * <p><strong>{@code position} then {@code id}, and the tie-breaker is not decoration.</strong>
     * Nothing in {@code V1} makes {@code (case_id, position)} unique — a bulk insert that assigns
     * every step position 0, or a reorder that half-committed, both produce ties — and a step list
     * whose order changes between two reads of the same case is worse than one that is wrong,
     * because it reads as the page being broken rather than as the data being. {@code id} makes it
     * stable while it is wrong, which is what lets someone see it and fix it.
     *
     * <p>Served by {@code test_step_case_idx (case_id, position)} from {@code V1} — the one index
     * this slice did not have to add.
     *
     * <h2>Deleting this {@code ORDER BY} is a mutation that survives, and the index is why</h2>
     *
     * <p>Measured, not assumed. The fixture writes the steps at positions 3, 1, 2 in that physical
     * order specifically so an unordered query would come back wrong — and with the clause removed
     * the response still arrives 1, 2, 3. Nothing sorted it in Java, so the plan is an index scan
     * on {@code test_step_case_idx}, whose second column <em>is</em> {@code position}. Postgres
     * hands back the order for free and no fixture can make it not.
     *
     * <p>It stays for the reason {@code ConditionRepository}'s tie-breaker does: the index is not a
     * contract. Drop it, replace it, or let the planner choose a bitmap scan once a case has enough
     * steps, and the order becomes arbitrary — at which point every past screenshot is filed
     * against a step list that reads in a different order than it was written. The clause costs a
     * sort the planner will elide anyway, and it is the only thing that makes the guarantee the
     * caller's rather than the index's.
     */
    @Query("""
            SELECT s FROM TestStep s
             WHERE s.caseId = :caseId
             ORDER BY s.position ASC, s.id ASC
            """)
    List<TestStep> findForCase(@Param("caseId") UUID caseId);
}
