package com.tcms.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// that forgets this.
@Transactional(readOnly = true)
public interface ClassificationDimensionRepository
        extends JpaRepository<ClassificationDimension, UUID> {

    /**
     * Every dimension that applies to one project: the company-level ones <strong>and</strong> the
     * project's own.
     *
     * <h2>Why the predicate is an OR and not an equality</h2>
     *
     * <p>{@code project_id IS NULL} means company-level (see {@link ClassificationDimension}).
     * Filtering on {@code project_id = :projectId} alone is the mistake this method exists to
     * prevent: it returns the project's empty Feature dimension and silently drops Test Type,
     * Test Level and Execution Phase — the three every authored case actually carries. It would
     * look like a working endpoint returning an oddly short list, which is why
     * {@code ProjectConfigurationEndpointTest} asserts the keys by name rather than the count.
     *
     * <p>Written as JPQL because a derived method name cannot express "is null OR equals", and
     * JPQL keeps the Hibernate company filter applied — a native query would not have it, leaving
     * only RLS between one client's vocabulary and another's. That matters more here than
     * anywhere else so far: the company-level rows match {@code project_id IS NULL} in
     * <em>every</em> company, so the project predicate scopes nothing for exactly the rows the
     * SPA needs most.
     *
     * <p>Ordered by {@code position} — the seed's intended reading order (Type, Level, Phase,
     * Feature) — then by key, so two dimensions sharing a position still come back in a stable
     * order rather than in whatever order the plan happened to produce.
     */
    @Query("""
            SELECT d FROM ClassificationDimension d
             WHERE d.projectId IS NULL OR d.projectId = :projectId
             ORDER BY d.position ASC, d.key ASC
            """)
    List<ClassificationDimension> findForProject(@Param("projectId") UUID projectId);
}
