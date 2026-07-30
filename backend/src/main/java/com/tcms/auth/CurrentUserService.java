package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InstanceRoleAssignmentRepository;
import com.tcms.identity.TeamMembership;
import com.tcms.identity.TeamMembershipRepository;
import com.tcms.identity.TeamRepository;
import com.tcms.tenancy.CompanyContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the {@code CurrentUser} the contract defines.
 *
 * <h2>The name comes from the membership, never from the account</h2>
 *
 * <p>{@code AppUser.canonicalName} exists for the Instance Admin directory and is
 * deliberately not user-facing (plan §6u): a contractor may legitimately be
 * "A. Kotrulev" to one client and "Aleks (Vendor)" to another, and neither client may see
 * or overwrite the other's. So every rendering site resolves through the <em>active</em>
 * company's {@link CompanyMembership} — including this one, which is the first such site to
 * exist.
 *
 * <p>The canonical name is the fallback only when there is no active company, which happens
 * for an operator who belongs to none. There is no membership to resolve through then, and
 * showing nothing would be worse than showing the directory name to the only person who
 * can see it.
 */
@Service
public class CurrentUserService {

    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final TeamMembershipRepository teamMemberships;
    private final TeamRepository teams;
    private final InstanceRoleAssignmentRepository instanceGrants;

    public CurrentUserService(
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            TeamMembershipRepository teamMemberships,
            TeamRepository teams,
            InstanceRoleAssignmentRepository instanceGrants) {
        this.users = users;
        this.memberships = memberships;
        this.teamMemberships = teamMemberships;
        this.teams = teams;
        this.instanceGrants = instanceGrants;
    }

    /** {@code CurrentUser} in the contract: {@code UserRef} plus the two session facts. */
    public record CurrentUser(
            UUID id,
            String displayName,
            String email,
            String avatarUrl,
            boolean isSystemAdmin,
            List<TeamRef> teams) {
    }

    public record TeamRef(UUID id, String name, boolean isLead) {
    }

    /**
     * @param companyId the session's active company, or null for an operator who belongs to
     *                  none. Passed rather than read from {@link CompanyContext} because
     *                  the login path calls this <em>before</em> a context is open — the
     *                  token it is building is what will open one on the next request.
     */
    @Transactional(readOnly = true)
    public CurrentUser describe(UUID userId, UUID companyId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new IllegalStateException(
                "Authenticated user " + userId + " does not exist. A session outliving its "
                        + "account means deletion happened somewhere that should only deactivate."));

        // Derived from holding a SYSTEM-scope grant, not from a role NAME — code checks
        // permissions, never names (plan §6b). It drives affordances only; every action is
        // independently checked server-side, so hidden is not protected.
        boolean systemAdmin = !instanceGrants.findByUserId(userId).isEmpty();

        if (companyId == null) {
            return new CurrentUser(user.getId(), user.getCanonicalName(), user.getEmail(),
                    null, systemAdmin, List.of());
        }

        Optional<CompanyMembership> membership =
                memberships.findByCompanyIdAndUserId(companyId, userId);
        String displayName = membership
                .map(CompanyMembership::getDisplayName)
                .orElseGet(user::getCanonicalName);
        String avatarUrl = membership.map(CompanyMembership::getAvatarUrl).orElse(null);

        return new CurrentUser(user.getId(), displayName, user.getEmail(), avatarUrl,
                systemAdmin, teamsOf(userId));
    }

    /**
     * The caller's teams <em>in the active company</em>.
     *
     * <p>Scoped by RLS rather than by a predicate here: {@code team_membership} and
     * {@code team} are both company-owned, so a membership in another client's team is not
     * in the result set to begin with. That is the layering the design depends on — the
     * company filter removes foreign rows from existence, and permission checks then decide
     * what may be done with what remains.
     */
    private List<TeamRef> teamsOf(UUID userId) {
        List<TeamMembership> memberOf = teamMemberships.findByUserId(userId);
        if (memberOf.isEmpty()) {
            return List.of();
        }
        // One lookup per team. A user is in a handful of teams, and a join here would need
        // an association this module deliberately does not map (plain UUID FKs, matching
        // Project). Revisit if that stops being true, not before.
        return memberOf.stream()
                .flatMap(membership -> teams.findById(membership.getTeamId()).stream()
                        .map(team -> new TeamRef(
                                team.getId(), team.getName(), membership.isLead())))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }
}
