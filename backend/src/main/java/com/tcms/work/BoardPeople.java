package com.tcms.work;

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
 * The people on a board card, resolved to display names.
 *
 * <p>The same shape and the same justification as {@code repository.CaseOwners} and
 * {@code execution.ExecutionPeople}, in a module that cannot import either. What is asked of
 * {@code identity} here is <em>what is this person called</em> and <em>are they here</em>, never
 * <em>may they</em>: the gate stays {@code project.ProjectService}, and no class in this package
 * asks what a caller holds.
 *
 * <p>Two queries for a whole board, never two per card. A busy project board is a hundred cards and
 * resolving a person per card is the N+1 that looks harmless because each query is fast.
 */
@Component
class BoardPeople {

    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;
    private final ProjectMembershipService projectMembers;

    BoardPeople(
            CompanyMembershipRepository memberships,
            AppUserRepository users,
            ProjectMembershipService projectMembers) {

        this.memberships = memberships;
        this.users = users;
        this.projectMembers = projectMembers;
    }

    /**
     * Whether {@code userId} may be handed work in this project.
     *
     * <p>Delegated in full to {@code identity.ProjectMembershipService}, and that delegation is the
     * §54 change worth knowing about. This module used to answer half the question itself —
     * "is this an active member of the company" — and {@code BoardService} ANDed the other half
     * ("do they hold a grant on the project") from {@code ProjectAccess}. Both halves were right and
     * the arrangement was still wrong, because {@code GET /projects/{id}/members} now builds the
     * SPA's assignee picker from the same rule. Two expressions of one predicate drift, and the
     * direction they drift is the harmful one: a picker offering somebody the create refuses is a
     * form the user fills in correctly and cannot submit.
     *
     * <p>So there is one implementation and this is a call to it. What is asked of {@code identity}
     * is still never <em>may the caller</em> — the gate stays {@code project.ProjectService} — but
     * <em>may this other person be given work</em>, which is a fact about the assignee and belongs
     * where memberships and grants are resolved.
     *
     * <p>The {@code companyId} parameter is gone with the delegation: the company is
     * {@link com.tcms.tenancy.CompanyContext}'s, which is where the callee reads it from, and
     * passing it was an opportunity to pass the wrong one.
     */
    boolean isAssignableTo(UUID userId, UUID projectId) {
        return projectMembers.isAssignableTo(userId, projectId);
    }

    /**
     * Resolves each id, skipping any that names no account.
     *
     * <p>An unresolvable id yields no entry and the caller renders a null assignee. Real rows are in
     * that state: {@code work_item.assignee_id} has no cascade, so "the account this was assigned to
     * has since been removed from the instance" survives as a dangling id, and inventing a person
     * for it would put a name on somebody else's work.
     *
     * <p>A {@link HashMap} rather than {@code Map.of()}, and this is not style — the latter throws
     * {@code NullPointerException} on {@code get(null)}, and an unassigned card passes exactly that.
     * It made this same method a 500 in {@code execution} (§38); both sibling classes carry the same
     * note so the three cannot quietly disagree.
     */
    Map<UUID, UserRefView> resolve(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // An empty collection bound into SQL `IN ()` is a syntax error, and a board with
            // nothing assigned is the ordinary state of a freshly provisioned project.
            return new HashMap<>();
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
                    // somebody offboarded from this company since the card was assigned.
                    displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
                    user.getEmail(),
                    null));
        }
        return resolved;
    }
}
