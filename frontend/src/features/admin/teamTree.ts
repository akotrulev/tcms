/**
 * The team hierarchy, flattened into an indented list — depth reads as hierarchy without a diagram.
 *
 * Extracted from `TeamsPage` so `/admin/teams` and `/org-chart` draw the same tree. A copy is how
 * the two come to disagree about which teams exist, and the disagreement would be invisible on
 * exactly the data that is already broken (org chart plan §9.3).
 */

/** The shape the flattening needs — anything wider (members, leads, projects) rides along. */
export type TeamTreeNode = {
  id: string;
  name: string;
  parentTeamId?: string | null;
};

export type TeamTreeRow<T extends TeamTreeNode> = { team: T; depth: number };

/*
  The chart, with **orphans and cycle members surfaced at the root** — §68's `buildValueTree`
  rule, and it matters more here than it did there.

  A plain descent from the roots reaches only teams whose whole ancestor chain is present, so a
  team pointing at a parent that is missing, or two teams pointing at each other, are silently
  dropped. That was cosmetic while this list was read-only. It is not now: the tree is the ONLY
  place a team can be edited or deleted, so a team the tree does not draw is a team nobody can
  fix — and the state that most needs fixing is exactly the one that hides.

  The API cannot produce either (the parent is validated in-company and cycles are refused twice
  over), which is the point: what gets here has come from somewhere else, and dropping it is how
  it stays.
*/
export function flattenTeamTree<T extends TeamTreeNode>(teams: T[]): TeamTreeRow<T>[] {
  const byId = new Map(teams.map((team) => [team.id, team]));
  const byParent = new Map<string | null, T[]>();

  /** Whether this team's ancestors terminate at a root that exists. */
  const reachesARoot = (team: T): boolean => {
    const seen = new Set<string>();
    let current: T | undefined = team;
    while (current) {
      if (seen.has(current.id)) return false; // a cycle
      seen.add(current.id);
      if (!current.parentTeamId) return true;
      current = byId.get(current.parentTeamId);
    }
    return false; // a parent that is not in the list
  };

  for (const team of teams) {
    const key = team.parentTeamId && reachesARoot(team) ? team.parentTeamId : null;
    const bucket = byParent.get(key);
    if (bucket) bucket.push(team);
    else byParent.set(key, [team]);
  }

  const build = (parentId: string | null, depth: number): TeamTreeRow<T>[] =>
    [...(byParent.get(parentId) ?? [])]
      .sort((a, b) => a.name.localeCompare(b.name))
      .flatMap((team) => [{ team, depth }, ...build(team.id, depth + 1)]);
  return build(null, 0);
}
