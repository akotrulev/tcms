/**
 * The permission catalogue, as runtime data derived from the contract's own union.
 *
 * `satisfies Record<PermissionKey, ...>` is the whole point (mirrors lib/labels.ts): adding a
 * permission to openapi.yaml makes this file a compile error until it is placed in an area and
 * described. A hand-maintained list drifts silently, and a drifted *permission* list is worse
 * than a drifted label — it decides which affordances an admin screen claims a role has.
 *
 * Why this exists at all: the first UI pass modelled "holds everything" as a `'*'` sentinel in
 * the permission array. No such permission exists in the model (plan §6b — the code checks
 * permissions, never role names, and there is no wildcard). Rendering that sentinel meant the
 * screen could not tell "holds every permission" from "holds a permission called star", and a
 * new permission added to the catalogue would silently keep reading as full coverage. Coverage
 * is now *derived* by comparing against this list, so a gap shows up as a gap.
 */
import type { PermissionKey } from '../api/types';

type Area =
  | 'repository'
  | 'review'
  | 'execution'
  | 'work'
  | 'reporting'
  | 'documentation'
  | 'artifacts'
  | 'project'
  | 'company'
  | 'system';

/** Every permission, keyed to its area. Exhaustive against the contract by construction. */
export const permissionAreas = {
  'suite:manage': 'repository',
  'suite:delete': 'repository',
  'case:read': 'repository',
  'case:create': 'repository',
  'case:edit': 'repository',
  'case:delete': 'repository',
  'library:manage': 'repository',

  'case:comment': 'review',
  'case:review': 'review',
  'suite:review': 'review',
  'plan:review': 'review',
  'case:approve': 'review',
  'suite:approve': 'review',
  'plan:approve': 'review',

  'plan:create': 'execution',
  'plan:edit': 'execution',
  'plan:delete': 'execution',
  'run:create': 'execution',
  'run:execute': 'execution',
  'run:close': 'execution',
  'result:submit': 'execution',
  'result:amend': 'execution',
  'evidence:attach': 'execution',
  'evidence:delete': 'execution',

  'defect:create': 'work',
  'workitem:manage': 'work',

  'report:view': 'reporting',
  'report:export': 'reporting',

  'doc:read': 'documentation',
  'doc:edit': 'documentation',
  'doc:publish': 'documentation',

  'artifact:read': 'artifacts',
  'artifact:edit': 'artifacts',
  'artifact:delete': 'artifacts',

  'project:read': 'project',
  'project:audit': 'project',
  'project:settings': 'project',
  'project:members': 'project',
  'integration:manage': 'project',

  'company:read': 'company',
  'company:settings': 'company',
  'company:members': 'company',
  'company:teams': 'company',
  'company:vocabulary': 'company',
  'company:idp': 'company',
  'project:create': 'company',
  'token:issue': 'company',
  'audit:view': 'company',

  'company:create': 'system',
  'company:delete': 'system',
  'company:suspend': 'system',
  'user:manage': 'system',
  'role:manage': 'system',
  'system:settings': 'system',
  'instance:audit': 'system',
} as const satisfies Record<PermissionKey, Area>;

export const allPermissions = Object.keys(permissionAreas) as PermissionKey[];

export const areaLabels = {
  repository: 'Repository',
  review: 'Review',
  execution: 'Execution',
  work: 'Defects & work',
  reporting: 'Reporting',
  documentation: 'Documentation',
  artifacts: 'Artifacts',
  project: 'Project',
  company: 'Company',
  system: 'System',
} as const satisfies Record<Area, string>;

/**
 * True when a role holds the entire catalogue — derived, never a sentinel. If a permission is
 * added and Instance Admin isn't given it, this correctly starts returning false and the screen
 * shows the individual grants, exposing the gap instead of hiding it behind "every permission".
 */
export function holdsEveryPermission(permissions: readonly PermissionKey[]): boolean {
  return allPermissions.every((p) => permissions.includes(p));
}

/** Permissions grouped by area, in catalogue order, skipping areas the role holds nothing in. */
export function groupByArea(
  permissions: readonly PermissionKey[],
): { area: Area; label: string; permissions: PermissionKey[] }[] {
  const groups = new Map<Area, PermissionKey[]>();
  for (const permission of allPermissions) {
    if (!permissions.includes(permission)) continue;
    const area = permissionAreas[permission];
    const existing = groups.get(area);
    if (existing) existing.push(permission);
    else groups.set(area, [permission]);
  }
  return [...groups].map(([area, list]) => ({ area, label: areaLabels[area], permissions: list }));
}
