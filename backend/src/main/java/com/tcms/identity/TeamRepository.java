package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Teams in the active company. Company-filtered; team names are unique per company. */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByName(String name);

    List<Team> findByParentTeamId(UUID parentTeamId);

    List<Team> findAllByOrderByNameAsc();

    /**
     * One team by id, through a query so the company filter applies in the application layer too —
     * {@code findById} goes through {@code EntityManager.find}, which Hibernate does not apply
     * {@code @Filter} to. The grant path resolves a {@code teamId} taken from a request body with
     * this, so "a team in another company" and "no such team" must be the same answer, decided by
     * both layers rather than by RLS alone.
     */
    Optional<Team> findScopedById(UUID id);
}
