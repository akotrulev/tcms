package com.tcms.identity;

import com.tcms.api.TeamRef;
import com.tcms.project.TeamDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one implementation of {@link TeamDirectory}.
 *
 * <p>{@link IdentityProjectAccess} and {@link IdentityPeopleDirectory} are the siblings, and the
 * arrangement is theirs: {@code project} declares what it needs and this module, which may already
 * see {@code project}, supplies it. The arrow still points one way.
 *
 * <p>One query for a whole page, never one per row — {@link IdentityPeopleDirectory}'s rule, and the
 * caller here renders a list of requests that may name several teams.
 *
 * <p>{@code companyId} is a parameter and is <strong>asserted</strong> rather than assumed. The
 * company filter and RLS both bound these reads already, so the check is redundant three times over
 * — and it is here because that redundancy is the point: a team resolved for the wrong company would
 * put a stranger's org-chart name on a client's review panel, and this is the one line in the path
 * that says so out loud rather than relying on infrastructure to have been configured.
 */
@Component
public class IdentityTeamDirectory implements TeamDirectory {

    private final TeamRepository teams;

    public IdentityTeamDirectory(TeamRepository teams) {
        this.teams = teams;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TeamRef> resolve(UUID companyId, Collection<UUID> teamIds) {
        if (teamIds.isEmpty()) {
            // A HashMap rather than Map.of(), for IdentityPeopleDirectory's stated reason: Map.of()
            // throws on get(null) by contract, so the immutable empty map would make the behaviour
            // of an unresolvable id depend on whether the page contained a resolvable one.
            return new HashMap<>();
        }

        Map<UUID, TeamRef> resolved = new HashMap<>();
        for (Team team : teams.findAllById(teamIds)) {
            if (!companyId.equals(team.getCompanyId())) {
                continue;
            }
            resolved.put(team.getId(), new TeamRef(team.getId(), team.getName()));
        }
        return resolved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamRef> find(UUID companyId, UUID teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        // findScopedById, never findById: the latter goes through EntityManager.find, which
        // Hibernate does not apply @Filter to, so it would rest on RLS alone. TeamRepository's own
        // header makes the point for the grant path, and this is the second write to resolve a team
        // id taken from a request body.
        return teams.findScopedById(teamId)
                .filter(team -> companyId.equals(team.getCompanyId()))
                .map(team -> new TeamRef(team.getId(), team.getName()));
    }
}
