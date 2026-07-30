package com.tcms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Team membership in the active company.
 *
 * <p>{@link PermissionEvaluator} does not call this — it resolves a user's teams inside
 * its union query so the whole decision is one round trip. These methods exist for
 * admin screens, and the evaluator's inlined subquery is kept consistent with them by
 * {@code PermissionEvaluatorTest}'s team-grant assertions.
 */
public interface TeamMembershipRepository extends JpaRepository<TeamMembership, TeamMembershipId> {

    List<TeamMembership> findByUserId(UUID userId);

    List<TeamMembership> findByTeamId(UUID teamId);
}
