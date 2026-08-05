package com.tcms.repository;

import java.util.List;
import java.util.Optional;
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
     *
     * <p><strong>{@code suite_id IS NOT NULL} is new in §96 and is not defensive.</strong> The column
     * is nullable since {@code V31}, so without the predicate an unfiled case joins a {@code NULL}
     * group — a {@code SuiteCaseCount} whose {@code suiteId} is null, which every caller then looks
     * up by a suite id that cannot match it. Harmless today and exactly the kind of row that is
     * later "fixed" by rendering it under whichever folder is first. Unfiled cases are counted by
     * the grid, not by the tree; the tree counts what is <em>filed</em>, and this slice's other two
     * membership arms are resolved separately rather than summed into this number.
     */
    @Query(nativeQuery = true, value = """
            SELECT tc.suite_id AS suiteId, COUNT(*) AS caseCount
              FROM test_case tc
             WHERE tc.company_id = :companyId
               AND tc.project_id = :projectId
               AND tc.deleted_at IS NULL
               AND tc.suite_id IS NOT NULL
             GROUP BY tc.suite_id
            """)
    List<SuiteCaseCount> countLiveCasesBySuite(
            @Param("companyId") UUID companyId, @Param("projectId") UUID projectId);

    /**
     * One live suite of one project, by id — the read every write gates on.
     *
     * <p>Both predicates matter and neither is redundant with the other. {@code projectId} is what
     * makes {@code /projects/{a}/suites/{id}} refuse a suite belonging to project {@code b} in the
     * same company: without it a caller granted one project could rename folders in another, and
     * the project gate above would have passed on a project the suite is not in.
     * {@code deletedAt IS NULL} is what stops a second delete of an already-deleted folder
     * answering 204 as though it had done something.
     *
     * <p>Not {@code findById}: that resolves by primary key through the persistence context, and
     * Hibernate filters do not apply to that path (see {@code ProjectRepository}'s header). RLS
     * still covers it, but using one layer where two are available is how a layered defence quietly
     * becomes a single point.
     */
    @Query("""
            SELECT s FROM Suite s
             WHERE s.id = :suiteId
               AND s.projectId = :projectId
               AND s.deletedAt IS NULL
            """)
    Optional<Suite> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("suiteId") UUID suiteId);

    /**
     * How many live child suites this suite has — <strong>direct children only.</strong>
     *
     * <p>Direct rather than subtree-wide because that is all the emptiness check needs: a suite with
     * a grandchild necessarily has a child, so refusing on direct children refuses every non-empty
     * subtree, one level at a time, in the order a person would clear them. The message it produces
     * is also the more useful one — "3 folders" names what is visible in the tree under this node,
     * not a total the user cannot see the parts of.
     */
    @Query("""
            SELECT COUNT(s) FROM Suite s
             WHERE s.parentId = :suiteId
               AND s.deletedAt IS NULL
            """)
    long countLiveChildren(@Param("suiteId") UUID suiteId);

    /**
     * How many live cases sit <strong>in the whole subtree</strong> beneath and including this
     * suite.
     *
     * <p>Subtree-wide here, unlike the child count above, and the asymmetry is deliberate rather
     * than an oversight: the child check already guarantees a refused delete whenever descendants
     * exist, so by the time this number is read the suite is a leaf and "subtree" and "own" are the
     * same set. It is written as a subtree match anyway so the query stays correct if a later slice
     * relaxes the child rule — a count that silently becomes wrong under a rule change is how a
     * "delete is safe" guarantee turns into deleting somebody's cases.
     *
     * <p>Native and {@code <@}: the {@code ltree} containment operator has no JPQL spelling, and it
     * is served by {@code suite_path_idx}, the GiST index V1 created for exactly this shape. Both
     * tenancy predicates are spelled out because a Hibernate filter does not apply to a native
     * query — the same note {@link #countLiveCasesBySuite} carries. Unlike that one, though,
     * <strong>{@code project_id} here is observable</strong> and has a test: paths are unique only
     * within a project, so two projects of one company routinely both have a suite at {@code s1},
     * and dropping the predicate makes an empty folder undeletable because a different project's
     * folder has cases in it.
     */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(*)
              FROM test_case tc
              JOIN suite s ON s.id = tc.suite_id
             WHERE tc.company_id = :companyId
               AND tc.project_id = :projectId
               AND tc.deleted_at IS NULL
               AND s.deleted_at IS NULL
               AND s.path <@ CAST(:path AS ltree)
            """)
    long countLiveCasesInSubtree(
            @Param("companyId") UUID companyId,
            @Param("projectId") UUID projectId,
            @Param("path") String path);

    /** A {@code (suiteId, caseCount)} row from {@link #countLiveCasesBySuite}. */
    interface SuiteCaseCount {

        UUID getSuiteId();

        long getCaseCount();
    }
}
