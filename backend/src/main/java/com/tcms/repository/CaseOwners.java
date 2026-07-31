package com.tcms.repository;

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
 * Case owners, rendered — a set of user ids in, a {@link UserRefView} each out.
 *
 * <h2>This is why the module now depends on {@code identity}, and what that dependency is not</h2>
 *
 * <p>{@code repository}'s package comment used to say "not {@code identity}", and the reason it
 * gave was authorization: this module must never hold a second answer to "may they?". That is still
 * true and nothing here changes it — the gate remains {@code ProjectService.readVisible}, borrowed
 * from {@code project}, and no class in this package asks what a caller holds.
 *
 * <p>What this asks is a different question with a different failure mode: <em>what is this person
 * called</em>. Every grid that shows a person needs it, plan §3 lesson 10 makes {@code app_user.id}
 * the permanent key for exactly this kind of resolution, and the cross-module edge toward
 * {@code identity} is one the architecture explicitly allows. There is no cycle:
 * {@code identity} depends on {@code tenancy} and {@code project}, never on this module.
 *
 * <p>The port-and-adapter shape {@code project} uses for {@code ProjectAccess} is deliberately
 * <em>not</em> repeated here. That inversion exists because {@code project} cannot depend on
 * {@code identity} without creating a cycle; this module can, so declaring a port would mean making
 * {@code identity} depend on the test repository in order to hand back a display name — an arrow
 * pointing the wrong way to avoid one that points the right way.
 *
 * <h2>Two queries for a page, never two per row</h2>
 *
 * <p>Memberships and accounts are each fetched once for the whole page. The obvious alternative —
 * resolving an owner per case — is a 200-query page to render one column, and it is the N+1 that
 * looks harmless because each query is fast.
 */
@Component
public class CaseOwners {

    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;

    public CaseOwners(CompanyMembershipRepository memberships, AppUserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    /**
     * Resolves each id, skipping any that names no account.
     *
     * <p>Callers look their owners up in the returned map and render whatever comes back, including
     * nothing — see {@link TestCaseSummaryView#owner()} for why an unresolvable owner produces a
     * case with no owner rather than a case that is hidden or given an invented one.
     *
     * @param companyId the active company, passed rather than read from the context so the caller's
     *                  scope and this lookup's scope are visibly the same one
     */
    Map<UUID, UserRefView> resolve(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error. A page whose every case is unowned is an ordinary state, not an edge one.
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
                    // The membership's name wins (plan §6u): the same person is labelled
                    // separately per client, and neither may see the other's label. The account's
                    // canonical name is the fallback for an owner who has since been offboarded
                    // from this company — their authored cases stay, and a case whose owner column
                    // is blank reads as data loss rather than as a person having left.
                    displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
                    user.getEmail(),
                    // Nullable in the contract and null for everyone: nothing uploads an avatar.
                    null));
        }
        return resolved;
    }
}
