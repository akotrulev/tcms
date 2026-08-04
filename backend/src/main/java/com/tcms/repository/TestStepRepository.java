package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
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

    /**
     * The project's step corpus — every distinct step it already uses, most-used first.
     *
     * <p>This is what the editor's action field completes from. The corpus is <strong>derived, not
     * curated</strong>: it is every step written in the project, so it needs no maintenance and is
     * never empty in a project that has cases. That is the correction to {@code shared_step}, whose
     * whole failure was that somebody had to remember to put things in it — a library nobody
     * populates ranks nothing, and the picker built on it opens empty forever.
     *
     * <h2>Grouped on the triple, counted on distinct cases</h2>
     *
     * <p>{@code (action, testData, expectedResult)} is the group key because the whole triple is
     * what a pick fills in — see {@link StepSuggestionView}. {@code COUNT(DISTINCT caseId)} and not
     * {@code COUNT(*)} for §6e's reason and {@link SharedStepRepository#countUsage}'s: a case that
     * repeats a step is one case using it, and counting rows lets one loop-heavy case out-rank a
     * step forty cases share.
     *
     * <p>{@code c.deletedAt IS NULL} is what makes the number mean "in use". Cases are soft-deleted
     * because executions reference them, so without it the corpus ranks by history — and worse,
     * keeps offering the step text of a case somebody deliberately retired.
     *
     * <h2>Gherkin steps are in the corpus, deliberately</h2>
     *
     * <p>No predicate on {@code keyword}. A Gherkin step's {@code action} is the step text without
     * its keyword, which is an ordinary sentence describing an action and is exactly as reusable as
     * a structured one — the two formats are one table for the same reason {@code V1}'s comment
     * gives. Excluding them would hide the corpus of any project that writes Gherkin from the
     * project's own authors.
     *
     * <h2>HQL, not native, and that is a constraint rather than a preference</h2>
     *
     * <p>A native query would drop the Hibernate company filter — the same constraint the condition
     * library, the shared-step library and the case list's free text all record. The filter on
     * {@link TestStep} and {@link TestCase} is what keeps this scoped to the caller's company;
     * RLS is the backstop underneath, not the mechanism.
     *
     * <p>The fragment match is {@code LOWER(action) LIKE}, served by {@code V30}'s index — which is
     * built on the {@code lower(action)} expression precisely so this predicate can use it.
     *
     * @param needle always bound and never null — {@code '%'} when the caller asked nothing, which
     *               matches every row since {@code action} is {@code NOT NULL}. Resolved once by
     *               {@link StepSuggestionQuery} rather than re-decided per call site.
     * @param limit  caps the response. The corpus of a mature project is thousands of distinct
     *               steps and a typeahead renders a dozen; the cap is applied in SQL so the
     *               unbounded set is never materialised.
     */
    @Query("""
            SELECT new com.tcms.repository.StepSuggestionView(
                       t.action, t.testData, t.expectedResult, COUNT(DISTINCT t.caseId))
              FROM TestStep t, TestCase c
             WHERE t.caseId = c.id
               AND c.projectId = :projectId
               AND c.deletedAt IS NULL
               AND LOWER(t.action) LIKE :needle ESCAPE '\\'
             GROUP BY t.action, t.testData, t.expectedResult
             ORDER BY COUNT(DISTINCT t.caseId) DESC, t.action ASC
            """)
    List<StepSuggestionView> findSuggestions(
            @Param("projectId") UUID projectId, @Param("needle") String needle, Limit limit);
}
