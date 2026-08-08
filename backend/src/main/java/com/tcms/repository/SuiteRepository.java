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
     * One project's live suites.
     *
     * <p>{@code deletedAt IS NULL} is not optional: a suite is soft-deleted because past runs
     * reference the cases filed in it, so without this predicate a deleted suite comes back in the
     * rail — and its cases are already gone from the case list, which reads as data loss rather
     * than as a stale row.
     *
     * <p><strong>Ordered by {@code keySeq} since §98.</strong> It was ordered by {@code path},
     * which made the flat list <em>constructible</em> into a tree — an {@code ltree} compares
     * element-wise, so path order is depth-first with every parent ahead of its descendants, where
     * {@code position} (sibling order within one parent) would have interleaved depths and let a
     * child precede its own parent. There is no tree to construct now, and the SPA sorts by name
     * for display either way; what an order still has to be is <strong>total and stable</strong>,
     * which is what {@code key_seq} gives and what a name does not (two suites may share one).
     */
    @Query("""
            SELECT s FROM Suite s
             WHERE s.projectId = :projectId
               AND s.deletedAt IS NULL
             ORDER BY s.keySeq ASC
            """)
    List<Suite> findLiveForProject(@Param("projectId") UUID projectId);

    /**
     * Live case counts per suite for one project, in <strong>one</strong> query.
     *
     * <p>Suites with no cases are absent from the result rather than present with zero — a
     * {@code GROUP BY} cannot invent rows for groups that do not exist — so the caller defaults a
     * missing suite to 0. An empty folder is the normal state of a tree, not an edge case.
     *
     * <h2>Filed cases only, deliberately</h2>
     *
     * <p>This counts the cases whose {@code suite_id} <em>is</em> the suite. It counted "not the
     * subtree" for the SPA's rollup until §98; what it still is not is the resolved membership —
     * {@link SuiteMembership#countsBySuite} is that, and it is what the wire's {@code caseCount}
     * carries. This one feeds the internal reads that mean <em>filing</em> specifically.
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
     * How many live cases are <strong>filed</strong> in this suite — the delete's emptiness check.
     *
     * <p>Two queries collapsed into this one by §98. There was a {@code countLiveChildren} over
     * {@code parent_id} (direct children only: a suite with a grandchild necessarily has a child,
     * so refusing on direct children refused every non-empty subtree, one level at a time, in the
     * order a person would clear them) and a {@code countLiveCasesInSubtree} spelled with
     * {@code ltree}'s {@code <@} against the GiST index. Both are gone with the tree.
     *
     * <p><strong>The filing arm alone, and that is the narrower of two available readings.</strong>
     * A case merely added to this suite, or matched by one of its tag rules, is not held here — it
     * lives elsewhere and only appears in this collection, so deleting the collection takes a claim
     * and never a case. {@link SuiteMembership} is the other reading and is deliberately not what
     * this asks.
     *
     * <p>JPQL rather than native: {@code TestCase} is an entity in this module, so the Hibernate
     * company filter applies and the tenancy predicate does not have to be spelled out. The
     * {@code projectId} predicate that {@code countLiveCasesInSubtree} needed — paths were unique
     * only within a project, so two projects of one company routinely both had a suite at
     * {@code s1} — is not needed here either: {@code suite_id} is a foreign key, and a case cannot
     * be filed in a suite belonging to another project.
     */
    @Query("""
            SELECT COUNT(c) FROM TestCase c
             WHERE c.suiteId = :suiteId
               AND c.deletedAt IS NULL
            """)
    long countLiveCasesFiledIn(@Param("suiteId") UUID suiteId);

    /**
     * The suite a previous request with this {@code Idempotency-Key} already created, if any.
     *
     * <p>Scoped to the project because {@code suite_created_idempotency_key_idx} is — company
     * through RLS and the Hibernate filter, the other two named here. A lookup wider or narrower
     * than the constraint enforcing it is a check that passes and an insert that fails, so a
     * request that validated comes back as a 500 (V11's header, from the other direction).
     *
     * <p><strong>No {@code deletedAt IS NULL}, and that is the point.</strong> A retry whose
     * original suite has since been deleted is still a retry: creating a second one would resurrect
     * something somebody deliberately removed, and the index would refuse it anyway. What to answer
     * for a replay pointing at a deleted row is the caller's decision; this method's job is to say
     * the key was used.
     */
    @Query("""
            SELECT s FROM Suite s
             WHERE s.projectId = :projectId
               AND s.createdIdempotencyKey = :idempotencyKey
            """)
    Optional<Suite> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("idempotencyKey") String idempotencyKey);

    /** A {@code (suiteId, caseCount)} row from {@link #countLiveCasesBySuite}. */
    interface SuiteCaseCount {

        UUID getSuiteId();

        long getCaseCount();
    }
}
