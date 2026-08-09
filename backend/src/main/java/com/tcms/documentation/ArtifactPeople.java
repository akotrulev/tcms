package com.tcms.documentation;

import com.tcms.api.UserRef;
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
 * Who last touched each artifact, resolved to display names.
 *
 * <p>The same shape and the same justification as {@code repository.CaseOwners},
 * {@code execution.ExecutionPeople} and {@code work.BoardPeople}, in a module that cannot import any
 * of them. What is asked of {@code identity} here is <em>what is this person called</em>, never
 * <em>may they</em>: the gate stays {@code project.ProjectService}, and no class in this package
 * asks what a caller holds.
 *
 * <p><strong>This class is why {@code documentation} depends on {@code identity} at all</strong>,
 * and the package comment's old claim that "nothing here renders a person" was true only for as long
 * as the register was unbuilt. {@code Artifact.updatedBy} is a required field of the contract's
 * {@code Artifact} — "who changed the environment guide, and when" is the question a stale-artifact
 * conversation opens with, so a register without it is a list of documents nobody is accountable for.
 *
 * <p>Two queries for the whole register, never two per row. A mature project's register is dozens of
 * documents and resolving a person per row is the N+1 that looks harmless because each query is fast.
 */
@Component
class ArtifactPeople {

    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;

    ArtifactPeople(CompanyMembershipRepository memberships, AppUserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    /**
     * Resolves each id, skipping any that names no account.
     *
     * <p>An unresolvable id yields no entry. Real rows reach that state: {@code artifact.updated_by}
     * has no foreign key in {@code V1}, so "the account that registered this has since been removed
     * from the instance" survives as a dangling id — the same shape §49 found on
     * {@code VersionMarker.author}, where a non-null type over an unconstrained column was an
     * unguarded dereference waiting for the first offboarded colleague.
     *
     * <p>A {@link HashMap} rather than {@code Map.of()}, and this is not style — the latter throws
     * {@code NullPointerException} on {@code get(null)}, and {@code updated_by} is nullable. It made
     * this same method a 500 in {@code execution} (§38); all four siblings carry the note so they
     * cannot quietly disagree.
     */
    Map<UUID, UserRef> resolve(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            // An empty collection bound into SQL `IN ()` is a syntax error, and an empty register is
            // the ordinary state of every freshly provisioned project.
            return new HashMap<>();
        }

        Map<UUID, String> displayNames = new HashMap<>();
        for (CompanyMembership membership
                : memberships.findByCompanyIdAndUserIdIn(companyId, userIds)) {
            displayNames.put(membership.getUserId(), membership.getDisplayName());
        }

        Map<UUID, UserRef> resolved = new HashMap<>();
        for (AppUser user : users.findAllById(userIds)) {
            resolved.put(user.getId(), new UserRef(
                    user.getId(),
                    // The membership's name wins (plan §6u); the canonical name is the fallback for
                    // somebody offboarded from this company since the artifact was registered.
                    displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
                    user.getEmail(),
                    null));
        }
        return resolved;
    }
}
