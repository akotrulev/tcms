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

    // A countLiveCasesBySuite(companyId, projectId) + its SuiteCaseCount projection sat here
    // until §152. §98 replaced it with SuiteMembership.countsBySuite — the three-arm union that
    // IS a flat suite's membership — and left this one standing, revising its header in the same
    // slice to claim it "feeds the internal reads that mean FILING specifically". There are no
    // such reads and there had never been any. That revision is what makes this the dangerous
    // one of the ten: the query still runs, still returns a plausible number, and the number is
    // the FILED ARM ALONE — precisely the count §98 stopped putting on the wire. The next author
    // to need a per-suite count finds this, believes the header, and ships §98's defect back.
    // CaseScope's header states the same distinction and is the place it is written down now.

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
}
