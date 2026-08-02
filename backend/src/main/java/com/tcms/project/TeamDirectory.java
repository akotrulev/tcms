package com.tcms.project;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <strong>What team is this</strong> — asked from this side of the module boundary, and never
 * <em>who may</em>.
 *
 * <p>The third port in this package, after {@link ProjectAccess} and {@link PeopleDirectory}, and
 * it exists for their reason unchanged: {@code team} lives in {@code identity}, this module may
 * depend on {@code tenancy} <em>only</em>, and this package's own comment prescribes the remedy —
 * <em>"if Modulith ever fails on this edge, the fix is a port, never a wider
 * allowedDependencies"</em>. §6g's reviewer assignment accepts a team, so the boundary had to be
 * crossed one more time; the arrow still points one way.
 *
 * <h2>Why this is not a method on {@link PeopleDirectory}</h2>
 *
 * <p>That port's header is emphatic about what it answers — <em>"who is this person"</em> — and its
 * one rule is plan §6u's, that a name is the <em>company membership's</em> name. A team has no
 * membership row and no per-client label, so folding it in would put two unrelated resolution rules
 * behind one interface and invite a caller to assume the §6u rule applies to both. Two small ports
 * beat one that has to explain itself.
 *
 * <h2>What an implementation must not do</h2>
 *
 * <p>It must not answer membership, and nothing here asks. §6g's <em>"any member of the team can
 * action it"</em> is a statement about who may act, which is {@link ProjectAccess}'s question and is
 * answered by the permission evaluator — which already resolves a caller's teams inside every one of
 * its union queries (§71). A second path that expanded a team into user ids and compared them to the
 * caller would be the second authorization-resolution path plan §6b names, arriving in the module
 * least entitled to hold one.
 */
public interface TeamDirectory {

    /**
     * Resolves each id, skipping any that names no team <em>in this company</em>.
     *
     * <p>An unresolvable id yields <strong>no entry</strong>, and the caller renders whatever it
     * renders for a request whose addressee no longer exists. That is not defensive: {@code V25}
     * cascades a team's deletion onto its review requests, so the window is small, and a row read
     * inside a transaction that deletes the team concurrently is exactly the case where inventing a
     * name would attribute a request to a group that does not exist.
     *
     * @param companyId the active company, passed rather than read from the context so the caller's
     *                  scope and this lookup's scope are visibly the same one
     * @return a mutable map answering {@code null} for an id it could not place — never
     *         {@code Map.of()}, whose {@code get(null)} throws by contract and made the sibling
     *         lookup a 500 in §38
     */
    Map<UUID, TeamRefView> resolve(UUID companyId, Collection<UUID> teamIds);

    /**
     * One team by id, in this company — the check a write performs before storing the reference.
     *
     * <p>Separate from {@link #resolve} because the two questions differ in what an absent answer
     * means. A render tolerates a missing team; a <em>write</em> must refuse one, or the product
     * stores a {@code team_id} it never checked, which is §64's {@code sharedStepId} — a reference
     * persisted unchecked since M1 and found four milestones later. Asking {@link #resolve} with one
     * id and checking the map would work and would read like an optimisation, which is precisely how
     * a validation stops looking like one.
     */
    Optional<TeamRefView> find(UUID companyId, UUID teamId);
}
