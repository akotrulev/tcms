package com.tcms.project;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every method here is implicitly company-scoped by the Hibernate filter — with one
 * exception worth knowing: {@code findById} resolves through the persistence context
 * and by primary key, which Hibernate filters do NOT apply to. RLS is what covers that
 * gap, which is why the database policy is not optional. {@link #findScopedById} exists
 * for call sites that want the filter to apply too.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOrderByNameAsc();

    Optional<Project> findByKey(String key);

    /** A query-based lookup, so the company filter applies in the application layer too. */
    Optional<Project> findScopedById(UUID id);

    boolean existsByKey(String key);

    /**
     * Live case counts for the given projects, in <strong>one</strong> query.
     *
     * <p>Projects with no cases are absent from the result rather than present with zero —
     * a {@code GROUP BY} cannot invent rows for groups that do not exist. The caller must
     * default a missing project to 0; {@link ProjectService} does, and its test covers a
     * brand-new project precisely because "absent means zero" is the kind of thing that
     * reads as correct and renders as {@code null}.
     *
     * <h2>Why this lives in the project module and reads another module's table</h2>
     *
     * <p>{@code test_case} belongs to the {@code repository} module, and a Java dependency
     * {@code project -> repository} is <strong>forbidden</strong>: cross-module edges may
     * only point at {@code tenancy}/{@code identity}/{@code project} (plan §3, enforced by
     * ArchUnit). The dependency is therefore expressed in SQL, where ArchUnit cannot see it,
     * and that is a deliberate trade rather than a way around the rule — recorded here so the
     * next reader does not "fix" it by adding the forbidden import.
     *
     * <p>The long-run answer is the one §3 lesson 4 gives for every rollup: an event-updated
     * read model. There is nothing to update from yet — no code writes a case — so a live
     * count is both correct and cheaper than a counter that could drift. Revisit when case
     * writes exist, not before.
     *
     * <h2>Why native, and why the predicates are spelled out</h2>
     *
     * <p>There is no {@code TestCase} entity to write JPQL against. A Hibernate filter does
     * not apply to a native query, so {@code company_id = :companyId} is explicit — RLS is
     * the guarantee, the predicate is the intent, and the two agreeing is what makes a
     * mismatch return nothing rather than something. Same reasoning, and the same standing
     * instruction not to delete it because no test fails, as
     * {@code RoleAssignmentRepository}'s permission queries.
     *
     * <p>{@code deleted_at IS NULL} is not optional: {@code test_case} is soft-deleted, so
     * without it a deleted case still counts and the number contradicts the case list. The
     * partial index {@code test_case_lifecycle_idx (company_id, project_id, lifecycle_status)
     * WHERE deleted_at IS NULL} covers exactly this shape.
     *
     * <h2>The suite must be live too, and that join is the second half of the same rule</h2>
     *
     * <p>Soft-deleting a <em>suite</em> does not touch the cases beneath it, and the case list
     * excludes them — a row whose folder is gone would name a place the user cannot navigate to
     * (see {@code CaseSpecifications.where}). So a live case in a deleted folder is unreachable,
     * and counting it here made the picker say "4 cases" over a list that showed 3.
     *
     * <p><strong>Found by running the app, not by a test.</strong> The two numbers are computed by
     * different queries on different screens, both were individually plausible, the build was
     * green and 306 tests passed. It is the same defect class as the two contradictory percentages
     * on the plan statistics screen (handoff §5), and the same reason the working agreement says
     * the gates prove it compiles, not that it works.
     *
     * <p>The alternative fix — having the case list include those rows — was rejected: it
     * contradicts what {@code SuiteRepository} already documents as the intent, and it would put a
     * case in the grid under a folder the tree refuses to show. If suite deletion later cascades
     * into its cases, this join becomes redundant rather than wrong.
     *
     * <h2>And an unfiled case is counted, which is why the join is now a LEFT one (§96)</h2>
     *
     * <p>{@code test_case.suite_id} is nullable since {@code V31}, so the inner join above dropped
     * every case filed in no folder — and the grid lists those. <strong>The very same defect this
     * method was written to fix, reintroduced from the other side</strong>: the picker would say
     * "6 cases" over a list of 7, both plausible, neither identifiable as the wrong one. Caught by
     * {@code CaseListEndpointTest.theTwoScreensAgree}, which exists because the first version of
     * this was found by running the app.
     *
     * <p>The predicate is therefore "the case has no folder, or its folder is live" — not
     * {@code s.deleted_at IS NULL}, which is false for a NULL row and would silently keep the inner
     * join's behaviour behind a {@code LEFT} that looks correct.
     */
    @Query(nativeQuery = true, value = """
            SELECT tc.project_id AS projectId, COUNT(*) AS caseCount
              FROM test_case tc
              LEFT JOIN suite s ON s.id = tc.suite_id
             WHERE tc.company_id = :companyId
               AND tc.project_id IN (:projectIds)
               AND tc.deleted_at IS NULL
               AND (tc.suite_id IS NULL OR s.deleted_at IS NULL)
             GROUP BY tc.project_id
            """)
    List<ProjectCaseCount> countLiveCasesByProject(
            @Param("companyId") UUID companyId, @Param("projectIds") Collection<UUID> projectIds);

    /** A {@code (projectId, caseCount)} row from {@link #countLiveCasesByProject}. */
    interface ProjectCaseCount {

        UUID getProjectId();

        long getCaseCount();
    }
}
