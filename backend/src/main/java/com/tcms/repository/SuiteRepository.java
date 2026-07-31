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
public interface SuiteRepository extends JpaRepository<Suite, UUID> {

    /**
     * One project's live suites, parents before children.
     *
     * <p>{@code deletedAt IS NULL} is not optional: a suite is soft-deleted because past runs
     * reference the cases beneath it, so without this predicate a deleted folder comes back in the
     * tree — and its cases are already gone from the case list, which reads as data loss rather
     * than as a stale row.
     *
     * <p><strong>Ordered by {@code path}, not by {@code position}.</strong> Position is sibling
     * order <em>within one parent</em>, so ordering the flat list by it interleaves depths and a
     * child can precede its own parent. An {@code ltree} compares element-wise, so path order is
     * depth-first with every parent ahead of its descendants — which is what a consumer building
     * the tree in one pass needs. The SPA re-sorts siblings by name after building, so this
     * ordering is about being <em>constructible</em>, not about the final display order.
     */
    @Query("""
            SELECT s FROM Suite s
             WHERE s.projectId = :projectId
               AND s.deletedAt IS NULL
             ORDER BY s.path ASC
            """)
    List<Suite> findLiveForProject(@Param("projectId") UUID projectId);

    /**
     * Live case counts per suite for one project, in <strong>one</strong> query.
     *
     * <p>Suites with no cases are absent from the result rather than present with zero — a
     * {@code GROUP BY} cannot invent rows for groups that do not exist — so the caller defaults a
     * missing suite to 0. An empty folder is the normal state of a tree, not an edge case.
     *
     * <h2>Direct cases only, deliberately</h2>
     *
     * <p>This counts the cases whose {@code suite_id} <em>is</em> the suite, not the cases in its
     * subtree. That is what the contract's consumer expects: the SPA's tree adds each node's own
     * count to its children's to get a rollup, so returning a subtree count here would count every
     * case once per ancestor and a two-level tree would report roughly double. The fixture in the
     * SPA says the same thing out loud — a parent folder with children carries {@code caseCount:
     * 0}.
     *
     * <h2>Why native, and why the two tenancy predicates cannot be tested — but stay</h2>
     *
     * <p>There is no {@code TestCase} entity yet, and a Hibernate filter does not apply to a
     * native query, so both scoping predicates are spelled out.
     *
     * <p><strong>Removing either one is a mutation that survives, and it survives for a structural
     * reason rather than a missing test.</strong> The result is keyed on {@code suite_id} and read
     * back per suite of <em>this</em> project, and a suite id is a UUID primary key — so a case in
     * another project or another company hangs off a suite whose id is never looked up. No fixture
     * can make the difference observable, which is why no test here pretends to. Recorded rather
     * than quietly left as a passing predicate nobody can account for; §22's documented survivor
     * on {@code RoleAssignmentRepository} is the same family.
     *
     * <p>They stay for two reasons that are not "in case the join key changes". {@code company_id}
     * leads {@code test_case_suite_idx (company_id, suite_id) WHERE deleted_at IS NULL}, so it is
     * what makes this query index-covered rather than a scan. And {@code project_id} bounds the
     * aggregate to the project being read instead of grouping every case in the company and
     * discarding most of them. The intent they state is the same one RLS enforces, and the two
     * agreeing is what makes a future mismatch return nothing rather than something.
     *
     * <p>Unlike that method, this one needs no cross-module apology — {@code test_case} is ERD §4
     * and therefore <em>this</em> module. The event-updated read model §3 lesson 4 prescribes is
     * still the long-run answer for a rollup; there is nothing to update from until something
     * writes a case, so a live count is both correct and cheaper than a counter that can drift.
     *
     * <p>{@code deleted_at IS NULL} for the same reason it is on the project count: a soft-deleted
     * case is gone from the case list, so counting it here makes the tree contradict the grid.
     */
    @Query(nativeQuery = true, value = """
            SELECT tc.suite_id AS suiteId, COUNT(*) AS caseCount
              FROM test_case tc
             WHERE tc.company_id = :companyId
               AND tc.project_id = :projectId
               AND tc.deleted_at IS NULL
             GROUP BY tc.suite_id
            """)
    List<SuiteCaseCount> countLiveCasesBySuite(
            @Param("companyId") UUID companyId, @Param("projectId") UUID projectId);

    /** A {@code (suiteId, caseCount)} row from {@link #countLiveCasesBySuite}. */
    interface SuiteCaseCount {

        UUID getSuiteId();

        long getCaseCount();
    }
}
