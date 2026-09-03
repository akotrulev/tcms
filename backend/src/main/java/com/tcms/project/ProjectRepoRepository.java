package com.tcms.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** A project's repositories. Company-filtered; names are unique per project. */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest.
@Transactional(readOnly = true)
public interface ProjectRepoRepository extends JpaRepository<ProjectRepo, UUID> {

    /**
     * The project's repositories in the order the settings screen shows them — which is also the
     * order that decides which one is primary, so the sort is the answer and not a presentation
     * choice.
     */
    List<ProjectRepo> findByProjectIdOrderByPositionAsc(UUID projectId);

    /**
     * One row by id, through a query so the company filter applies in the application layer too —
     * {@code findById} goes through {@code EntityManager.find}, which Hibernate does not apply
     * {@code @Filter} to. The id arrives from a request path, so "in another company" and "no such
     * repository" must be one answer decided by both layers rather than by RLS alone.
     */
    Optional<ProjectRepo> findScopedById(UUID id);
}
