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
  | 'tracker'
  | 'board'
  | 'releases'
  | 'time'
  | 'staffing'
  | 'reporting'
  | 'documentation'
  | 'artifacts'
  | 'project'
  | 'clients'
  | 'money'
  | 'company'
  | 'system';

/**
 * Every permission, keyed to its area. Exhaustive against the contract by construction.
 *
 * The `satisfies` clause below holds the KEYS to the contract and says nothing about the VALUES.
 * Those are the second copy of `permission.area`, which V3 seeds and no backend code reads — the
 * column's whole justification is that it agrees with this map. `SeedDataTest.areasMatchTheSpa`
 * (backend) compares the two and reads this file directly, so moving a permission to a different
 * area here fails the backend build until the seed moves with it, in a corrective migration.
 */
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
  'doc:review': 'review',
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

  'issue:read': 'tracker',
  'issue:create': 'tracker',
  'issue:edit': 'tracker',
  'issue:delete': 'tracker',
  'issue:comment': 'tracker',
  'issue:workflow': 'tracker',
  'issue:import': 'tracker',

  'board:read': 'board',
  'board:rank': 'board',
  'board:configure': 'board',

  'release:read': 'releases',
  'release:manage': 'releases',
  'release:publish': 'releases',

  'time:log': 'time',
  'time:read': 'time',
  'time:edit': 'time',
  'time:approve': 'time',

  'staffing:read': 'staffing',
  'staffing:allocate': 'staffing',
  'staffing:directory': 'staffing',
  'staffing:profile': 'staffing',
  'staffing:absence': 'staffing',

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

  'client:read': 'clients',
  'client:create': 'clients',
  'client:edit': 'clients',
  'client:delete': 'clients',

  'ratecard:read': 'money',
  'ratecard:manage': 'money',
  'billing:read': 'money',
  'billing:budget': 'money',
  'billing:expense': 'money',
  'billing:invoice': 'money',
  'billing:issue': 'money',

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

/**
 * Each permission as the **verb phrase** that completes "You don't have permission to ___ in
 * this project." — the sentence `useCan` puts on a disabled control.
 *
 * Phrased from the user's side of the screen, never from the model's: the key is
 * `library:manage`, and the thing a person recognizes is "reusable steps and preconditions".
 * A tooltip reading "You don't have permission to library:manage" tells someone who already
 * knows the catalogue what they already knew, and everyone else nothing.
 *
 * `satisfies Record<PermissionKey, string>` for the same reason `permissionAreas` has it: a
 * permission added to the contract is a compile error here until someone decides how to say it
 * out loud. A gap would otherwise surface as `undefined` inside a sentence.
 */
export const permissionLabels = {
  'suite:manage': 'create or rename suites',
  'suite:delete': 'delete suites',
  'case:read': 'view test cases',
  'case:create': 'create test cases',
  'case:edit': 'edit test cases',
  'case:delete': 'delete test cases',
  'library:manage': 'manage reusable steps and preconditions',

  'case:comment': 'comment on test cases',
  'case:review': 'review test cases',
  'suite:review': 'review suites',
  'plan:review': 'review test plans',
  'doc:review': 'review documentation pages',
  'case:approve': 'approve test cases',
  'suite:approve': 'approve suites',
  'plan:approve': 'approve test plans',

  'plan:create': 'create test plans',
  'plan:edit': 'edit test plans',
  'plan:delete': 'delete test plans',
  'run:create': 'create test runs',
  'run:execute': 'execute test runs',
  'run:close': 'close test runs',
  'result:submit': 'record results',
  'result:amend': 'amend recorded results',
  'evidence:attach': 'attach evidence',
  'evidence:delete': 'delete evidence',

  'defect:create': 'raise defects',
  'workitem:manage': 'manage board items',

  'issue:read': 'view issues',
  'issue:create': 'create issues',
  'issue:edit': 'edit issues',
  'issue:delete': 'delete issues',
  'issue:comment': 'comment on issues',
  'issue:workflow': 'change the issue workflow',
  'issue:import': 'import issues from another tracker',

  'board:read': 'view the board and backlog',
  'board:rank': 'order the backlog',
  'board:configure': 'configure boards',

  'release:read': 'view releases and the roadmap',
  'release:manage': 'create and edit releases',
  'release:publish': 'mark a release released',

  'time:log': 'log your hours',
  'time:read': "view other people's time",
  'time:edit': "amend somebody else's time entry",
  'time:approve': 'approve timesheets',

  'staffing:read': "view this project's staffing",
  'staffing:allocate': 'allocate people to this project',
  'staffing:directory': 'view the company staffing directory',
  'staffing:profile': 'maintain staffing profiles and skills',
  'staffing:absence': "record somebody else's absence",

  'report:view': 'view reports',
  'report:export': 'export reports',

  'doc:read': 'read documentation',
  'doc:edit': 'edit documentation',
  'doc:publish': 'publish documentation',

  'artifact:read': 'view artifacts',
  'artifact:edit': 'upload or edit artifacts',
  'artifact:delete': 'delete artifacts',

  'project:read': 'open this project',
  'project:audit': 'view the project audit log',
  'project:settings': 'change project settings',
  'project:members': 'manage project members',
  'integration:manage': 'manage integrations',

  'client:read': 'view clients',
  'client:create': 'create clients',
  'client:edit': 'edit clients and their contacts',
  'client:delete': 'delete clients',

  'ratecard:read': 'view rate cards',
  'ratecard:manage': 'create and edit rate cards',
  'billing:read': 'view budgets and invoices',
  'billing:budget': 'set project budgets',
  'billing:expense': 'record expenses',
  'billing:invoice': 'draft invoices',
  'billing:issue': 'issue an invoice',

  'company:read': 'view this company',
  'company:settings': 'change company settings',
  'company:members': 'manage company members',
  'company:teams': 'manage teams',
  'company:vocabulary': 'manage the company vocabulary',
  'company:idp': 'configure single sign-on',
  'project:create': 'create projects',
  'token:issue': 'issue API tokens',
  'audit:view': 'view the company audit log',

  'company:create': 'create companies',
  'company:delete': 'delete companies',
  'company:suspend': 'suspend companies',
  'user:manage': 'manage users',
  'role:manage': 'manage roles',
  'system:settings': 'change instance settings',
  'instance:audit': 'view the instance audit log',
} as const satisfies Record<PermissionKey, string>;

export const areaLabels = {
  repository: 'Repository',
  review: 'Review',
  execution: 'Execution',
  work: 'Defects & work',
  tracker: 'Issues',
  board: 'Board & backlog',
  releases: 'Releases',
  time: 'Time',
  staffing: 'Staffing',
  reporting: 'Reporting',
  documentation: 'Documentation',
  artifacts: 'Artifacts',
  project: 'Project',
  clients: 'Clients',
  money: 'Rates & billing',
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

/**
 * How a refusal names the permission it is about — `Problem.permission` and `Problem.scope`
 * (005 C1a/C1b), turned into the one sentence the Refused state shows.
 *
 * **Labelled from this catalogue, never from a role name.** The 403 says which *key* was missing;
 * which roles happen to hold it is a different question, answered on the org chart, and a client
 * that mapped one to the other would be holding a second copy of §A3.
 *
 * Three degradations, all of which happen in practice and none of which may crash a screen:
 *
 *   - **`permission` is null** — most refusals still omit it, so the sentence falls back to the
 *     shape it had before the member existed and says nothing it cannot support.
 *   - **the key is not in this catalogue** — a server ahead of this build. It renders as its own
 *     text (`case:approve`), which is worse copy than a label and far better than a dropped
 *     sentence or the word `undefined`.
 *   - **`scope` is null or unrecognized** — the "where" clause is simply omitted.
 */
export function refusalSentence(permission: string | null, scope: string | null): string {
  const where =
    scope === 'PROJECT'
      ? ' on this project'
      : scope === 'COMPANY'
        ? ' in this company'
        : scope === 'SYSTEM'
          ? ' on this instance'
          : '';
  if (permission === null) {
    return `You do not have the permission this needs${where}.`;
  }
  const label = (permissionLabels as Record<string, string>)[permission] ?? permission;
  return `You do not have permission to ${label}${where}.`;
}
