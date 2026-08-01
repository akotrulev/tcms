package com.tcms.work;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.MembershipStatus;
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

    BoardPeople(CompanyMembershipRepository memberships, AppUserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    /**
     * Whether {@code userId} is an <strong>active member of this company</strong>.
     *
     * <p>Not the same question as "does this id name an account", and {@code ExecutionPeople}'s
     * header records what conflating them costs: {@code app_user} is instance-level with no
     * {@code company_id} (ERD §0.1's exception list, because one person works with several clients),
     * so an existence check accepts a user belonging to <em>another client entirely</em>. On this
     * module's surface that would mean assigning a client's task to a stranger and printing their
     * name on this company's board.
     *
     * <p>{@code ACTIVE}, not merely present: an {@code INVITED} membership conveys nothing until it
     * is accepted, so an unaccepted invitation must not become a way to be handed work.
     *
     * <p><strong>Company membership is necessary and not sufficient here.</strong>
     * {@code BoardService} additionally requires a project grant before accepting an assignee —
     * see {@code WorkItemEdit}'s header for the defect that prevents: a card assigned to a colleague
     * who cannot open the project it lives in, visible on their board and 404 when they click it.
     */
    boolean isActiveMember(UUID companyId, UUID userId) {
        return memberships.existsByCompanyIdAndUserIdAndStatus(
                companyId, userId, MembershipStatus.ACTIVE);
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
