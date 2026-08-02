package com.tcms.identity;

import com.tcms.project.PeopleDirectory;
import com.tcms.project.UserRefView;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one implementation of {@link PeopleDirectory} — plan §6u's naming rule, in one place.
 *
 * <p>{@code IdentityProjectAccess} is the sibling: {@code project} declares what it needs and this
 * module, which may already see {@code project}, supplies it. The arrow still points one way.
 *
 * <p>Two queries for a whole page, never two per row. Resolving a person per row is the N+1 that
 * looks harmless because each query is fast, and the callers here render lists — a review's
 * participants, a grid's owners, a run's executors.
 */
@Component
public class IdentityPeopleDirectory implements PeopleDirectory {

    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;

    public IdentityPeopleDirectory(
            CompanyMembershipRepository memberships, AppUserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserRefView> resolve(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, and a page whose every row is unattributed is an ordinary state.
            //
            // A HashMap rather than Map.of(), and that is the whole point of the line. Map.of()
            // throws NPE on get(null) by contract while HashMap answers null, so returning the
            // immutable empty map would make behaviour on an unresolvable id depend on whether the
            // page happened to contain anybody resolvable — fine on every page with one named
            // person, a 500 on the page where nobody has one. That was a real 500 in §38.
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
                    // The membership's name wins (plan §6u): the same person is labelled separately
                    // per client, and neither may see the other's label. The account's canonical
                    // name is the fallback for somebody since offboarded from this company — their
                    // authored work stays, and a blank name reads as data loss rather than as a
                    // person having left.
                    displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
                    user.getEmail(),
                    // Nullable in the contract and null for everyone: nothing uploads an avatar.
                    null));
        }
        return resolved;
    }
}
