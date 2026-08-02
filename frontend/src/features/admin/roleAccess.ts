/**
 * The client-side half of the access model — the arithmetic two screens must agree about.
 *
 * `GET /role-assignments` answers one flat list of the grants the caller may administer. Two
 * populations live in it and they read very differently on a project screen:
 *
 *   - a grant **naming** a project — "Ana is a Test Lead on Online Banking";
 *   - a grant naming **no** project whose role is PROJECT-scope — "Ana is a Test Lead on every
 *     project in this company", the inheritance the roles spec added as §A1.1 on 2026-08-02.
 *
 * Both arrive with `projectId: null` in the second case, so `projectId === null` is **not**
 * "company scope" any more: it covers COMPANY-scope grants (Company Admin, authority over the
 * company) and inherited PROJECT-scope grants (a default for every project), which have opposite
 * override semantics. The backend's own commit records splitting its queries on `role.scope` for
 * exactly this reason, and the same split has to happen here or the project screen renders a
 * Company Admin as somebody's project role.
 *
 * ## The override, and why it is computed rather than asked for
 *
 * §A1.1's rule is **replace, not union**: a grant naming the project wins outright over the
 * inherited one, *including when it confers less*. So an inherited row on a project screen is
 * either live or dead, and rendering all of them as live would tell an administrator that the
 * colleague they just demoted to Viewer here is still a Test Lead here.
 *
 * The switch is *whether a grant naming the project exists* — never what it confers. A check
 * written as "do they have project permissions" unions the two and silently undoes every
 * restriction, and a role granting nothing would fail to override at all. That is the same
 * sentence the backend's `hasProjectScopedAssignment` is written under, and it is repeated here
 * because this is the second implementation of the rule, not because it is obvious.
 *
 * **Team grants count, and forgetting them is §54's defect.** A query over direct user grants
 * finds only direct grants and silently omits everybody staffed through a team, then answers with
 * a plausible list. So the overridden set is seeded from the project's team grants expanded
 * through the org chart before any user grant is looked at.
 */
import type { RoleAssignment, Team } from '../../api/types';

/** A grant that names a project — the project's own staffing, and the only revocable-here kind. */
export function namesProject(grant: RoleAssignment, projectId: string): boolean {
  return grant.projectId === projectId;
}

/**
 * A grant that names no project and whose role is PROJECT-scope: the §A1.1 inheritance.
 *
 * COMPANY-scope and SYSTEM grants are excluded and that exclusion is load-bearing rather than
 * tidy — they are *never* overridden (spec §A1.1 property 2, Company Admin is authority over the
 * company rather than a default for its projects). Listing them among the inheritable rows would
 * invite the override chip onto a row the model says can never carry one.
 */
export function isInherited(grant: RoleAssignment): boolean {
  return grant.projectId === null && grant.role.scope === 'PROJECT';
}

/**
 * The people whose inherited role is replaced on this project, by §A1.1's replace rule.
 *
 * Derived from *whether* a grant names the project, never from what it confers — see the file
 * note. Team grants are expanded through `Team.members`, because a person staffed through a team
 * is as project-scoped as one staffed directly and the alternative is a screen that quietly
 * disagrees with the server about half the company.
 */
export function overriddenUserIds(
  grants: readonly RoleAssignment[],
  teams: readonly Team[],
  projectId: string,
): ReadonlySet<string> {
  const overridden = new Set<string>();
  const teamsById = new Map(teams.map((team) => [team.id, team]));

  for (const grant of grants) {
    if (!namesProject(grant, projectId)) continue;
    if (grant.user) overridden.add(grant.user.id);
    if (grant.team) {
      for (const member of teamsById.get(grant.team.id)?.members ?? []) {
        overridden.add(member.id);
      }
    }
  }
  return overridden;
}

/**
 * Whether one inherited row is dead on this project.
 *
 * A **user** row is answered exactly, including through their teams. A **team** row is answered
 * only by that team's own project grant — "some of this team is overridden and some is not" is a
 * true statement the row has no honest way to render, so it is not claimed. The panel's own copy
 * carries that caveat rather than a chip inventing a verdict per row.
 *
 * **The `isInherited` guard is inside this function rather than at the call site**, and it moved
 * here because a test caught it missing. §A1.1 exemption 2 says COMPANY-scope roles are never
 * overridden — and a Company Admin who is *also* covered by one of the project's grants is
 * legitimately in the overridden set, so a check that consulted the set alone answered "replaced"
 * for them. That is exactly the privilege inversion the exemption exists to stop, and a rule
 * enforced by whoever remembers to ask `isInherited` first is a rule the second caller gets wrong.
 */
export function isOverriddenHere(
  grant: RoleAssignment,
  overridden: ReadonlySet<string>,
  projectGrants: readonly RoleAssignment[],
  projectId: string,
): boolean {
  if (!isInherited(grant)) return false;
  if (grant.user) return overridden.has(grant.user.id);
  if (grant.team) {
    return projectGrants.some(
      (other) => namesProject(other, projectId) && other.team?.id === grant.team?.id,
    );
  }
  return false;
}
