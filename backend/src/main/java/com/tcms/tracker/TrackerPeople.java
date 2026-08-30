package com.tcms.tracker;

import com.tcms.api.UserRef;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.ProjectMembershipService;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Names and avatars for the people on an issue — {@code work.BoardPeople}'s shape, in this module.
 *
 * <p>{@code identity} is asked <em>what is this person called</em> and never <em>may they</em>: the
 * second question is {@code project}'s, through the gate. The one exception is
 * {@link #isAssignableTo}, which is not a permission check on the caller but a check on the
 * <em>subject</em> — assigning work to somebody who cannot open its project is a defect whose only
 * observer is the assignee, who gets a 404 when they click it.
 */
@Component
class TrackerPeople {

    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;
    private final ProjectMembershipService projectMembers;

    TrackerPeople(
            CompanyMembershipRepository memberships,
            AppUserRepository users,
            ProjectMembershipService projectMembers) {

        this.memberships = memberships;
        this.users = users;
        this.projectMembers = projectMembers;
    }

    /** The predicate {@code GET /members} publishes — an active member with a grant on the project. */
    boolean isAssignableTo(UUID userId, UUID projectId) {
        return projectMembers.isAssignableTo(userId, projectId);
    }

    Map<UUID, UserRef> resolve(UUID companyId, Collection<UUID> userIds) {
        Map<UUID, UserRef> resolved = new HashMap<>();
        if (userIds.isEmpty()) {
            // An empty collection bound into SQL `IN ()` is a syntax error, and an unassigned
            // backlog is the ordinary state of a freshly created project.
            return resolved;
        }

        Map<UUID, String> displayNames = new HashMap<>();
        for (CompanyMembership membership
                : memberships.findByCompanyIdAndUserIdIn(companyId, userIds)) {
            displayNames.put(membership.getUserId(), membership.getDisplayName());
        }
        for (AppUser user : users.findAllById(userIds)) {
            resolved.put(user.getId(), new UserRef(
                    user.getId(),
                    // The membership's name wins (plan §6u); the canonical name is the fallback for
                    // somebody offboarded from this company since the issue was assigned.
                    displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
                    user.getEmail(),
                    null));
        }
        return resolved;
    }
}
