package com.tcms.execution;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The people on an execution — an assignee and an executor — resolved to display names.
 *
 * <p>The same shape and the same justification as {@code repository.CaseOwners}, in a module that
 * cannot import it. What is asked of {@code identity} here is <em>what is this person called</em>,
 * never <em>may they</em>: the gate stays {@code project.ProjectService}, and no class in this
 * package asks what a caller holds.
 *
 * <p>Two queries for a whole run, never two per row. A run detail lists two hundred executions and
 * resolving a person per row is the N+1 that looks harmless because each query is fast.
 */
@Component
class ExecutionPeople {

    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;

    ExecutionPeople(CompanyMembershipRepository memberships, AppUserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    /**
     * Resolves each id, skipping any that names no account.
     *
     * <p>An unresolvable id yields no entry, and the caller renders a null. That is deliberate for
     * an <em>executor</em> in particular: {@code test_execution.executed_by} is nullable and has no
     * cascade, so "the account that recorded this has since been removed" is a state real rows are
     * in. Inventing a person there would attribute a verdict to somebody, which is the one thing a
     * result must not do wrongly.
     *
     * @param companyId the active company, passed rather than read from the context so the caller's
     *                  scope and this lookup's scope are visibly the same one
     */
    Map<UUID, UserRefView> resolve(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // An empty collection bound into SQL `IN ()` is a syntax error, and a run nobody has
            // been assigned or executed is the ordinary state of a run that was just created.
            return Map.of();
        }

        Map<UUID, String> displayNames = new HashMap<>();
        for (CompanyMembership membership
                : memberships.findByCompanyIdAndUserIdIn(companyId, userIds)) {
            displayNames.put(membership.getUserId(), membership.getDisplayName());
        }

        Map<UUID, UserRefView> resolved = new HashMap<>();
        for (AppUser user : users.findAllById(userIds)) {
            resolved.put(user.getId(), new UserRefView(
                    user.getId(),
                    // The membership's name wins (plan §6u); the canonical name is the fallback for
                    // somebody offboarded from this company since they ran the test.
                    displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
                    user.getEmail(),
                    null));
        }
        return resolved;
    }
}
