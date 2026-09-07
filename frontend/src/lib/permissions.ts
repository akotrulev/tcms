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

/**
 * The nine seeded roles, in §A3's column order — least-permissioned first.
 *
 * A **display** vocabulary and nothing more. Nothing in this app may branch on a role name (plan
 * §6b, and `OrgChartAssignment.roleName` says it again on the wire); these strings exist so a
 * refusal can name somebody to ask, which is the one thing a permission key cannot do.
 *
 * `Every member` is not a role a person is granted — it is how §A3's COMPANY table spells the
 * non-assignable `Company Member` baseline that joining a company confers (`V15`). It is in the
 * list because the table has a column for it, and a holders row that dropped it would claim a key
 * every member holds is held by nobody.
 */
export const ROLE_NAMES = [
  'Every member',
  'Viewer',
  'Automation Service',
  'Test Engineer',
  'Project Manager',
  'Test Lead',
  'Account Manager',
  'Finance',
  'Company Admin',
  'Instance Admin',
] as const;

export type RoleName = (typeof ROLE_NAMES)[number];

/**
 * Who holds each permission — roles spec §A3, transcribed.
 *
 * ## Why a transcription rather than a read
 *
 * This is the second copy problem `matrixCopies.test.ts` exists for, arriving in a third place —
 * and the answer is the same one: **copy it, then compare the copy.** The alternative was
 * considered and does not work. `docs/specs/permission-matrix-summary.md` is a document on disk,
 * not a module; a browser bundle cannot read it, and importing it through a Vite raw-text loader
 * would ship 12KB of markdown and a markdown-table parser into every session so the shell can put
 * three role names in a tooltip. So the table is here, and `permissions.test.ts` parses §A3's copy
 * the way `matrixCopies.test.ts` does and fails on any cell where the two disagree.
 *
 * ## What a cell means here
 *
 * **Held, or not held.** §A3 writes some cells as a qualifier rather than a `✓` — `case:create` is
 * `quarantine only` for an Automation Service — and a qualifier *narrows* a grant without removing
 * it. This table records the role as a holder, because the question it answers is "who could do
 * this for me", and the answer "an Automation Service, but only in quarantine" is still a lead.
 * The qualifier itself is deliberately not carried: nothing on screen has anywhere to put it, and
 * a half-transcribed qualifier is worse than an honestly absent one.
 *
 * `satisfies Record<PermissionKey, ...>` for the reason every table in this file has it — a
 * permission added to the contract is a compile error here until somebody says who holds it.
 */
export const permissionHolders = {
  'suite:manage': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'suite:delete': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'case:read': [
    'Viewer',
    'Automation Service',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'case:create': [
    'Automation Service',
    'Test Engineer',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'case:edit': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'case:delete': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'library:manage': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],

  'case:comment': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'case:review': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'suite:review': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'plan:review': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'doc:review': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'case:approve': ['Test Lead', 'Company Admin', 'Instance Admin'],
  'suite:approve': ['Test Lead', 'Company Admin', 'Instance Admin'],
  'plan:approve': ['Test Lead', 'Company Admin', 'Instance Admin'],

  'plan:create': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'plan:edit': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'plan:delete': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'run:create': [
    'Automation Service',
    'Test Engineer',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'run:execute': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'run:close': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'result:submit': [
    'Automation Service',
    'Test Engineer',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'result:amend': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'evidence:attach': [
    'Automation Service',
    'Test Engineer',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'evidence:delete': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],

  'defect:create': [
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'workitem:manage': [
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],

  'issue:read': [
    'Viewer',
    'Automation Service',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'issue:create': [
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'issue:edit': [
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'issue:delete': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'issue:comment': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'issue:workflow': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'issue:import': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],

  'board:read': [
    'Viewer',
    'Automation Service',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'board:rank': ['Project Manager', 'Company Admin', 'Instance Admin'],
  'board:configure': ['Project Manager', 'Company Admin', 'Instance Admin'],

  'release:read': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'release:manage': ['Project Manager', 'Company Admin', 'Instance Admin'],
  'release:publish': ['Project Manager', 'Company Admin', 'Instance Admin'],

  'time:log': ['Test Engineer', 'Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'time:read': ['Project Manager', 'Company Admin', 'Instance Admin'],
  'time:edit': ['Project Manager', 'Company Admin', 'Instance Admin'],
  'time:approve': ['Project Manager', 'Company Admin', 'Instance Admin'],

  'staffing:read': ['Project Manager', 'Company Admin', 'Instance Admin'],
  'staffing:allocate': ['Project Manager', 'Company Admin', 'Instance Admin'],
  'staffing:directory': ['Account Manager', 'Finance', 'Company Admin', 'Instance Admin'],
  'staffing:profile': ['Account Manager', 'Company Admin', 'Instance Admin'],
  'staffing:absence': ['Account Manager', 'Company Admin', 'Instance Admin'],

  'report:view': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'report:export': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],

  'doc:read': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'doc:edit': ['Test Engineer', 'Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'doc:publish': [
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],

  'artifact:read': [
    'Viewer',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'artifact:edit': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'artifact:delete': ['Test Engineer', 'Test Lead', 'Company Admin', 'Instance Admin'],

  'project:read': [
    'Viewer',
    'Automation Service',
    'Test Engineer',
    'Project Manager',
    'Test Lead',
    'Company Admin',
    'Instance Admin',
  ],
  'project:audit': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'project:settings': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'project:members': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],
  'integration:manage': ['Project Manager', 'Test Lead', 'Company Admin', 'Instance Admin'],

  'client:read': ['Account Manager', 'Finance', 'Company Admin', 'Instance Admin'],
  'client:create': ['Account Manager', 'Company Admin', 'Instance Admin'],
  'client:edit': ['Account Manager', 'Company Admin', 'Instance Admin'],
  'client:delete': ['Account Manager', 'Company Admin', 'Instance Admin'],

  'ratecard:read': ['Finance', 'Company Admin', 'Instance Admin'],
  'ratecard:manage': ['Finance', 'Company Admin', 'Instance Admin'],
  'billing:read': ['Finance', 'Company Admin', 'Instance Admin'],
  'billing:budget': ['Finance', 'Company Admin', 'Instance Admin'],
  'billing:expense': ['Finance', 'Company Admin', 'Instance Admin'],
  'billing:invoice': ['Finance', 'Company Admin', 'Instance Admin'],
  'billing:issue': ['Finance', 'Company Admin', 'Instance Admin'],

  'company:read': ['Every member', 'Account Manager', 'Finance', 'Company Admin', 'Instance Admin'],
  'company:settings': ['Company Admin', 'Instance Admin'],
  'company:members': ['Company Admin', 'Instance Admin'],
  'company:teams': ['Company Admin', 'Instance Admin'],
  'company:vocabulary': ['Company Admin', 'Instance Admin'],
  'company:idp': ['Company Admin', 'Instance Admin'],
  'project:create': ['Company Admin', 'Instance Admin'],
  'token:issue': ['Company Admin', 'Instance Admin'],
  'audit:view': ['Company Admin', 'Instance Admin'],

  'company:create': ['Instance Admin'],
  'company:delete': ['Instance Admin'],
  'company:suspend': ['Instance Admin'],
  'user:manage': ['Instance Admin'],
  'role:manage': ['Instance Admin'],
  'system:settings': ['Instance Admin'],
  'instance:audit': ['Instance Admin'],
} as const satisfies Record<PermissionKey, readonly RoleName[]>;

/**
 * The roles §A3 records as holding a permission, in the spec's own column order.
 *
 * Least-permissioned first, which is deliberate and is what makes the sentence useful: the first
 * name in the list is the *cheapest* grant that would unblock the reader, and a list that led with
 * `Instance Admin` would send everyone to the operator of the instance for a key their own team
 * lead holds.
 */
export function holdersOf(permission: PermissionKey): readonly RoleName[] {
  return permissionHolders[permission];
}

/**
 * `a`, `a and b`, `a, b and c` — the English list a refusal sentence needs.
 *
 * No serial comma, matching the mockups and §8.2's example sentence. Exported for the access page,
 * which prints role lists and project-key lists side by side and must not spell one of them a
 * second way — hence `readonly string[]` rather than `RoleName[]`: the punctuation rule is about
 * English, not about roles.
 */
export function listOfRoles(items: readonly string[]): string {
  if (items.length === 0) return 'nobody';
  if (items.length === 1) return items[0] as string;
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1] as string}`;
}

/**
 * The roles worth *naming to the reader* as somebody to ask.
 *
 * `Instance Admin` is dropped, and this is the one place the two lists differ. An Instance Admin
 * operates the whole instance and is very often not a person inside the reader's company at all —
 * naming them in "held by" turns an actionable sentence into an escalation the reader cannot make.
 * Both mockups drop it for that reason: 09's tooltip reads *held by Test Lead and Company Admin*
 * where §A3's row has three names, and 08's badges do the same.
 *
 * It survives when it is the ONLY holder: the seven SYSTEM-scope keys are held by nobody else, and
 * a sentence reading "held by nobody" would be false as well as useless.
 *
 * `Every member` survives too, for the opposite reason — a refusal naming it is telling the reader
 * they already hold the key, which is a bug report worth being able to make.
 */
export function askableHoldersOf(permission: PermissionKey): readonly RoleName[] {
  const holders = holdersOf(permission);
  const askable = holders.filter((role) => role !== 'Instance Admin');
  return askable.length > 0 ? askable : holders;
}

/**
 * **The one disabled-reason sentence in the app** (021 §8.2, T7).
 *
 * One shape, one place: *"Needs Approve timesheets, held by Project Manager, Test Lead and Company
 * Admin."* Every disabled control that names a reason calls this and none of them composes its
 * own — which is the point, because a convention stated in a spec and spelled four ways in the
 * code is not a convention.
 *
 * **Not a replacement for {@link refusalSentence}.** The two answer different questions from
 * different sides of a request. That one turns a 403 the server *already sent* into copy, and it
 * deliberately refuses to name roles because the server said which key was missing and nothing
 * about who holds it. This one is the pre-emptive form, on a control the client disabled before
 * anything was sent, where naming somebody to ask is the whole value: the reader is looking at a
 * greyed button, and the useful sentence is the one that ends the dead end.
 *
 * Pair it with `aria-describedby` on the control, never with `title` alone — a `title` is a hover
 * affordance and a keyboard user never triggers it.
 */
export function disabledReason(permission: PermissionKey): string {
  const label = permissionLabels[permission];
  const capitalized = label.charAt(0).toUpperCase() + label.slice(1);
  return `Needs ${capitalized}, held by ${listOfRoles(askableHoldersOf(permission))}.`;
}

/**
 * {@link disabledReason} for an action reachable by **any one of several keys**.
 *
 * The holder list is the **union**, and the union is the right answer rather than a convenient one:
 * `canAny` is satisfied by holding one of the keys, so a role holding one of them is a role that
 * could do this — which is exactly what "who do I ask" means here. Printed in `ROLE_NAMES` order so
 * the cheapest grant still leads, the way the single-key sentence's does.
 */
export function disabledReasonAny(permissions: readonly PermissionKey[]): string {
  const labels = permissions.map((permission) => permissionLabels[permission]).join(' or ');
  const capitalized = labels.charAt(0).toUpperCase() + labels.slice(1);
  const union = new Set(permissions.flatMap((permission) => askableHoldersOf(permission)));
  const holders = ROLE_NAMES.filter((role) => union.has(role));
  return `Needs ${capitalized}, held by ${listOfRoles(holders)}.`;
}
