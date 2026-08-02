package com.tcms.repository;

import com.tcms.project.PeopleDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Case owners, rendered — a set of user ids in, a {@link UserRefView} each out.
 *
 * <h2>Where the naming rule lives, and why this class is no longer the one applying it</h2>
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
 * <p>The port-and-adapter shape {@code project} uses for {@code ProjectAccess} was deliberately
 * <em>not</em> repeated here, and that reasoning still stands as far as it went: that inversion
 * exists because {@code project} cannot depend on {@code identity} without creating a cycle, and
 * this module can. What it missed is that {@code project} then acquired a consumer of its own — the
 * review panel's participants (§76) — so the port exists regardless, and the choice was no longer
 * "port or direct call" but "one resolution or three". Hence {@link PeopleDirectory}, and hence this
 * class is now the mapping and not the rule.
 *
 * <h2>Two queries for a page, never two per row</h2>
 *
 * <p>Memberships and accounts are each fetched once for the whole page. The obvious alternative —
 * resolving an owner per case — is a 200-query page to render one column, and it is the N+1 that
 * looks harmless because each query is fast.
 */
@Component
public class CaseOwners {

    private final PeopleDirectory people;

    public CaseOwners(PeopleDirectory people) {
        this.people = people;
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
        // Delegated, not implemented. This method and execution.ExecutionPeople#resolve were
        // byte-for-byte copies of one another, the second carrying the comment "the two classes must
        // not disagree" — §58's E1 exactly: two hand-maintained copies under an instruction to keep
        // them in step, surviving because neither module can import the other. The rule they were
        // both applying (plan §6u, below) now lives in project.PeopleDirectory, which both may call,
        // and §76 collapsed them onto it rather than adding a third copy for the review panel.
        //
        // What remains here is the mapping onto this module's own UserRefView — a shape, not a rule.
        // Callers still null-check, because "unattributed" is a rendering decision; see
        // TestCaseSummaryView#owner() for why an unresolvable owner produces a case with no owner
        // rather than one that is hidden or given an invented one.
        Map<UUID, UserRefView> resolved = new HashMap<>();
        for (Map.Entry<UUID, com.tcms.project.UserRefView> person
                : people.resolve(companyId, userIds).entrySet()) {
            resolved.put(person.getKey(), new UserRefView(
                    person.getValue().id(),
                    person.getValue().displayName(),
                    person.getValue().email(),
                    person.getValue().avatarUrl()));
        }
        // A HashMap all the way down, never Map.of(): the latter throws NPE on get(null) by
        // contract, so an immutable empty map would make behaviour on an unresolvable id depend on
        // whether the page happened to contain anybody resolvable — fine on every page with one
        // named owner, a 500 on the page where nobody has one (§38).
        return resolved;
    }
}