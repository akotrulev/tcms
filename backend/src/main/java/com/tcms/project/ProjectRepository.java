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
     */
    @Query(nativeQuery = true, value = """
            SELECT tc.project_id AS projectId, COUNT(*) AS caseCount
              FROM test_case tc
             WHERE tc.company_id = :companyId
               AND tc.project_id IN (:projectIds)
               AND tc.deleted_at IS NULL
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
