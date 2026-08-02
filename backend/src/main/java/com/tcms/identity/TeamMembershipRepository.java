package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team membership in the active company.
 *
 * <p>{@link PermissionEvaluator} does not call this — it resolves a user's teams inside
 * its union query so the whole decision is one round trip. These methods exist for
 * admin screens, and the evaluator's inlined subquery is kept consistent with them by
 * {@code PermissionEvaluatorTest}'s team-grant assertions.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface TeamMembershipRepository extends JpaRepository<TeamMembership, TeamMembershipId> {

    List<TeamMembership> findByUserId(UUID userId);

    List<TeamMembership> findByTeamId(UUID teamId);

    /**
     * One membership, for the write that asserts it.
     *
     * <p>Resolved rather than blind-saved so a promotion keeps the row's {@code joinedAt} — see
     * {@link TeamMembership#setLead}. Both ids are company-filtered by the same policy as every
     * other read here, so a team or a person from another company resolves to empty.
     */
    Optional<TeamMembership> findByTeamIdAndUserId(UUID teamId, UUID userId);
}
