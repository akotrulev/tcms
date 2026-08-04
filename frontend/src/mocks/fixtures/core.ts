/**
 * Seed data for the mock API.
 *
 * Deliberately realistic rather than lorem-ipsum: the UI is being judged on whether it
 * makes a real test repository legible, and that only shows with plausible key sequences,
 * mixed lifecycle states, results spanning several case versions and releases, and the
 * awkward rows (a case marked AUTOMATED that stopped reporting, a suite whose membership
 * changed mid-cycle).
 */
import { allPermissions } from '../../lib/permissions';
import type {
  Artifact,
  CaseStatus,
  ClassificationDimension,
  Condition,
  Configuration,
  CurrentUser,
  DocPage,
  DocPageSummary,
  PermissionKey,
  Project,
  Role,
  RoleAssignment,
  SharedStep,
  Suite,
  Team,
  TestCase,
  TestPlan,
  TestRun,
  User,
  WorkItem,
} from '../../api/types';

export const PROJECT_ID = '11111111-1111-4111-8111-111111111111';
export const PROJECT_ID_2 = '11111111-1111-4111-8111-111111111112';

const uid = (n: number, p = 'a') =>
  `${p}${p}${p}${p}${p}${p}${p}${p}-0000-4000-8000-${String(n).padStart(12, '0')}`;

// ---------------------------------------------------------------- users & teams ----

export const users: User[] = [
  {
    id: uid(1, 'u'),
    displayName: 'Ana Petrova',
    email: 'ana.petrova@example.com',
    avatarUrl: null,
    status: 'ACTIVE',
    lastLoginAt: '2026-07-30T08:12:00Z',
    teams: ['Payments Squad', 'QA Chapter'],
  },
  {
    id: uid(2, 'u'),
    displayName: 'Marco Silva',
    email: 'marco.silva@example.com',
    avatarUrl: null,
    status: 'ACTIVE',
    lastLoginAt: '2026-07-30T07:40:00Z',
    teams: ['Payments Squad'],
  },
  {
    id: uid(3, 'u'),
    displayName: 'Priya Nair',
    email: 'priya.nair@example.com',
    avatarUrl: null,
    status: 'ACTIVE',
    lastLoginAt: '2026-07-29T16:22:00Z',
    teams: ['Checkout Squad', 'QA Chapter'],
  },
  {
    id: uid(4, 'u'),
    displayName: 'Tom Becker',
    email: 'tom.becker@example.com',
    avatarUrl: null,
    status: 'ACTIVE',
    lastLoginAt: '2026-07-28T11:05:00Z',
    teams: ['Checkout Squad'],
  },
  {
    id: uid(5, 'u'),
    displayName: 'Lena Fischer',
    email: 'lena.fischer@example.com',
    avatarUrl: null,
    status: 'DEACTIVATED',
    lastLoginAt: '2026-04-02T09:00:00Z',
    teams: [],
  },
];

export const currentUser: CurrentUser = {
  id: users[0].id,
  displayName: users[0].displayName,
  email: users[0].email,
  avatarUrl: null,
  isSystemAdmin: false,
  teams: [
    { id: uid(1, 't'), name: 'Payments Squad', isLead: true },
    { id: uid(3, 't'), name: 'QA Chapter', isLead: false },
  ],
};

const ref = (u: User) => ({
  id: u.id,
  displayName: u.displayName,
  email: u.email,
  avatarUrl: u.avatarUrl,
});

export const teams: Team[] = [
  {
    id: uid(3, 't'),
    parentTeamId: null,
    name: 'QA Chapter',
    description: 'Cross-squad quality practice',
    leads: [ref(users[0])],
    members: [ref(users[0]), ref(users[2])],
    projects: [],
  },
  {
    id: uid(1, 't'),
    parentTeamId: uid(3, 't'),
    name: 'Payments Squad',
    description: 'Payments, refunds, settlement',
    leads: [ref(users[0])],
    members: [ref(users[0]), ref(users[1])],
    projects: [{ projectId: PROJECT_ID, projectName: 'Acme Online Banking', roleName: 'Test Lead' }],
  },
  {
    id: uid(2, 't'),
    parentTeamId: uid(3, 't'),
    name: 'Checkout Squad',
    description: 'Cart, checkout, order placement',
    leads: [ref(users[2])],
    members: [ref(users[2]), ref(users[3])],
    projects: [
      { projectId: PROJECT_ID, projectName: 'Acme Online Banking', roleName: 'Test Engineer' },
      { projectId: PROJECT_ID_2, projectName: 'Acme Mobile App', roleName: 'Test Lead' },
    ],
  },
];

// ---------------------------------------------------------------------- roles ----

/**
 * The six built-in roles — the complete set. There are no custom roles in v1, not even
 * clone-and-edit (plan §6b), so `builtIn` is always true and no create/edit path exists.
 *
 * Grants mirror docs/specs/2026-07-30-tcms-roles-permissions-naming.md, which is authoritative.
 * Instance Admin enumerates the whole catalogue rather than carrying a `'*'` wildcard: no such
 * permission exists in the model, and a sentinel would make a future gap invisible (see
 * lib/permissions.ts).
 */
export const roles: Role[] = [
  {
    id: uid(1, 'r'),
    name: 'Instance Admin',
    scope: 'SYSTEM',
    builtIn: true,
    description:
      'The operator. Everything, in every company — provisions companies and appoints their administrators. Reach into a client’s content is audited in that client’s own trail.',
    permissions: [...allPermissions],
  },
  {
    id: uid(2, 'r'),
    name: 'Company Admin',
    scope: 'COMPANY',
    builtIn: true,
    description:
      'Full control of the companies they are assigned to, and nothing outside them. Sees every project in the company, present and future, with no per-project grant. Assigned by an Instance Admin.',
    permissions: [
      'suite:manage', 'suite:delete', 'case:read', 'case:create', 'case:edit', 'case:delete',
      'library:manage',
      'case:comment', 'case:review', 'suite:review', 'plan:review',
      'case:approve', 'suite:approve', 'plan:approve',
      'plan:create', 'plan:edit', 'plan:delete',
      'run:create', 'run:execute', 'run:close', 'result:submit', 'result:amend',
      'evidence:attach', 'evidence:delete',
      'defect:create', 'workitem:manage',
      'report:view', 'report:export',
      'doc:read', 'doc:edit', 'doc:publish',
      'artifact:read', 'artifact:edit', 'artifact:delete',
      'project:read', 'project:audit', 'project:settings', 'project:members', 'integration:manage',
      'company:read', 'company:settings', 'company:members', 'company:teams',
      'company:vocabulary', 'company:idp', 'project:create', 'token:issue', 'audit:view',
    ],
  },
  {
    id: uid(3, 'r'),
    name: 'Test Lead',
    scope: 'PROJECT',
    builtIn: true,
    description:
      'Full control of assigned projects — deletes others’ content, amends others’ results, closes runs, gives final approval, reads the project audit trail, manages project settings.',
    permissions: [
      'suite:manage', 'suite:delete', 'case:read', 'case:create', 'case:edit', 'case:delete',
      'library:manage',
      'case:comment', 'case:review', 'suite:review', 'plan:review',
      'case:approve', 'suite:approve', 'plan:approve',
      'plan:create', 'plan:edit', 'plan:delete',
      'run:create', 'run:execute', 'run:close', 'result:submit', 'result:amend',
      'evidence:attach', 'evidence:delete',
      'defect:create', 'workitem:manage',
      'report:view', 'report:export',
      'doc:read', 'doc:edit', 'doc:publish',
      'artifact:read', 'artifact:edit', 'artifact:delete',
      'project:read', 'project:audit', 'project:settings', 'project:members', 'integration:manage',
      'company:read',
    ],
  },
  {
    id: uid(4, 'r'),
    name: 'Test Engineer',
    scope: 'PROJECT',
    builtIn: true,
    description:
      'The working contributor role, granted explicitly — never a default. Creates and edits everything, executes, reviews, files defects, maintains the shared library. Deletes only their own work; cannot approve, close runs, delete a suite subtree, or read the audit trail.',
    permissions: [
      'suite:manage', 'case:read', 'case:create', 'case:edit', 'case:delete',
      'library:manage',
      'case:comment', 'case:review', 'suite:review', 'plan:review',
      'plan:create', 'plan:edit',
      'run:create', 'run:execute', 'result:submit', 'evidence:attach', 'evidence:delete',
      'defect:create', 'workitem:manage',
      'report:view', 'report:export',
      'doc:read', 'doc:edit',
      'artifact:read', 'artifact:edit',
      'project:read', 'company:read',
    ],
  },
  {
    id: uid(5, 'r'),
    name: 'Automation Service',
    scope: 'PROJECT',
    builtIn: true,
    description:
      'Non-human, attached to API tokens rather than people. Deliberately the smallest role in the product: a leaked CI token can post results and add quarantine stubs, and cannot edit or delete a real case, close a run, or read reports.',
    permissions: ['case:read', 'case:create', 'run:create', 'result:submit', 'evidence:attach'],
  },
  {
    id: uid(6, 'r'),
    name: 'Viewer',
    scope: 'PROJECT',
    builtIn: true,
    description:
      'Read-only, plus commenting and report export. The default on joining a company or a project. Built for the stakeholder — often the client themselves — who reviews coverage without writing any of it.',
    permissions: [
      'case:read', 'case:comment', 'project:read', 'company:read',
      'report:view', 'report:export', 'doc:read', 'artifact:read',
    ],
  },
];

/**
 * Who holds what, and the fixture carries the awkward rows on purpose.
 *
 * Three shapes have to be present or a screen that renders them cannot be built, let alone seen:
 *
 *   1. **A grant naming a project** — the ordinary case, and the only kind revocable from a
 *      project screen.
 *   2. **A PROJECT-scope grant naming NO project** — the inheritance roles spec §A1.1 added on
 *      2026-08-02: "this role on every project in the company, unless that project says
 *      otherwise". Marco is a Test Lead everywhere.
 *   3. **Rule 2 actually being overridden.** Marco is a Viewer on Online Banking, so his
 *      company-wide Test Lead is dead *there* and live everywhere else. §A1.1 is REPLACE, not
 *      union — a project grant wins outright, including when it confers less — and a fixture
 *      without this row leaves the branch that renders it unreachable in development. Same class
 *      as §54's accepted-and-ignored `Idempotency-Key`.
 *
 * Tom's Company Admin row is the control for the exemption: he is also covered by Checkout
 * Squad's grant on Online Banking, so he is in the overridden *set* — and his row must still not
 * be marked replaced, because COMPANY-scope roles are never overridden (§A1.1 property 2). A
 * predicate that reached for the set alone would mark it, which is precisely the privilege
 * inversion the exemption exists to stop.
 *
 * **`user` and `team` are exclusive**, mirroring `ck_role_assignment_subject`: a team grant names
 * the team and nobody else, and the server's `RoleAssignmentView` sends `user: null` for one. The
 * previous fixture set both on two rows, which taught the "granted via" column a shape live mode
 * never produces.
 *
 * Company Admin is never granted ON a project here either — the server refuses that with a 400 on
 * `roleId`, so a fixture holding one was a row no path in the product could have created.
 */
export const roleAssignments: RoleAssignment[] = [
  {
    // COMPANY scope, company-wide: authority over the company, not a default for its projects.
    id: uid(1, 'g'),
    role: roles[1],
    user: ref(users[3]),
    team: null,
    projectId: null,
    projectName: null,
    grantedAt: '2026-01-08T09:00:00Z',
  },
  {
    // PROJECT scope, company-wide: the §A1.1 inheritance — Test Lead on every project…
    id: uid(2, 'g'),
    role: roles[2],
    user: ref(users[1]),
    team: null,
    projectId: null,
    projectName: null,
    grantedAt: '2026-01-15T10:00:00Z',
  },
  {
    // …except this one, which granted him less. The row above is replaced here, not added to.
    id: uid(3, 'g'),
    role: roles[5],
    user: ref(users[1]),
    team: null,
    projectId: PROJECT_ID,
    projectName: 'Acme Online Banking',
    grantedAt: '2026-02-10T14:20:00Z',
  },
  {
    // The two team grants, matching the org chart above rather than restating it differently.
    id: uid(4, 'g'),
    role: roles[2],
    user: null,
    team: { id: uid(1, 't'), name: 'Payments Squad' },
    projectId: PROJECT_ID,
    projectName: 'Acme Online Banking',
    grantedAt: '2026-01-15T10:00:00Z',
  },
  {
    id: uid(5, 'g'),
    role: roles[3],
    user: null,
    team: { id: uid(2, 't'), name: 'Checkout Squad' },
    projectId: PROJECT_ID,
    projectName: 'Acme Online Banking',
    grantedAt: '2026-02-03T09:30:00Z',
  },
  {
    id: uid(6, 'g'),
    role: roles[2],
    user: null,
    team: { id: uid(2, 't'), name: 'Checkout Squad' },
    projectId: PROJECT_ID_2,
    projectName: 'Acme Mobile App',
    grantedAt: '2026-02-03T09:30:00Z',
  },
  {
    // The seeded persona's own Viewer grant, so `projects[1].myRole` has a row behind it.
    id: uid(7, 'g'),
    role: roles[5],
    user: ref(users[0]),
    team: null,
    projectId: PROJECT_ID_2,
    projectName: 'Acme Mobile App',
    grantedAt: '2026-03-11T08:45:00Z',
  },
];

// ------------------------------------------------------------------- projects ----

/**
 * A project is a FULL CLIENT PROJECT — an entire product or engagement under test, not a feature
 * area (plan §1). The earlier seed modelled them as features ("Payments Platform", "Storefront"),
 * which taught the wrong shape to anyone reading the fixtures: "Payments" is a **Feature**
 * classification value (§6d) or a suite folder *inside* a project, never a project of its own.
 *
 * This matters beyond naming. A project grant hands someone an entire product's test assets,
 * which is exactly why company membership conveys no project visibility at all (§6b) — a regular
 * user sees only the projects they hold a grant on.
 */
/**
 * The caller's **COMPANY-scope** grant, which is a different question from any project's `myRole`.
 *
 * The real `GET /me/permissions` with no `projectId` answers the SYSTEM + COMPANY terms only, and
 * the seeded persona here is an ordinary member: a Test Lead on one project, a Viewer on another,
 * and no company-level powers. So they hold `company:read` and **not** `project:create` — which is
 * why "New project" on the picker renders *disabled with its reason* in mock mode.
 *
 * That is the honest answer for this persona and it leaves the enabled branch unreachable here.
 * **Add `'project:create'` to this array to drive the create dialog against the mock**; the live
 * mode this slice unblocks is where the happy path is actually exercised, against a real Company
 * Admin. A mock that granted it unconditionally would be §31's defect in reverse — a fixture that
 * can only ever render one branch of a two-branch affordance.
 *
 * **The same is now true of `company:teams` (§71).** This persona cannot create, edit, delete or
 * staff a team in mock mode; every org-chart affordance renders disabled with its reason, and the
 * handlers behind them answer 403. Add `'company:teams'` here to drive the editable branch — and
 * note that the *interesting* refusals (a case-insensitive name clash, a cycle, a delete refused
 * because grants hang off the team) are only reachable once you do.
 */
export const companyScopePermissions: PermissionKey[] = ['company:read'];

export const projects: Project[] = [
  {
    id: PROJECT_ID,
    key: 'OLB',
    name: 'Acme Online Banking',
    description:
      'The retail banking web and mobile-web product: accounts, transfers, payments, statements.',
    archived: false,
    caseCount: 1123,
    myRole: 'Test Lead',
  },
  {
    id: PROJECT_ID_2,
    key: 'MOB',
    name: 'Acme Mobile App',
    description: 'The native iOS and Android app — onboarding, accounts, card controls.',
    archived: false,
    caseCount: 486,
    // Viewer, not Test Engineer: read-only is the default on joining a project, and a
    // contributor grant is always explicit (plan §6b). Seeding the old default made the
    // fixtures read as more permissive than the model actually is.
    myRole: 'Viewer',
  },
];

// ------------------------------------------------------------- classification ----

export const dimensions: ClassificationDimension[] = [
  {
    id: uid(1, 'd'),
    key: 'test_type',
    name: 'Test Type',
    cardinality: 'SINGLE',
    hierarchical: false,
    locked: true,
    values: [
      { id: uid(11, 'v'), parentId: null, name: 'Functional', path: 'Functional', color: null },
      { id: uid(12, 'v'), parentId: null, name: 'Performance', path: 'Performance', color: null },
      { id: uid(13, 'v'), parentId: null, name: 'Security', path: 'Security', color: null },
      { id: uid(14, 'v'), parentId: null, name: 'Usability', path: 'Usability', color: null },
      { id: uid(15, 'v'), parentId: null, name: 'Accessibility', path: 'Accessibility', color: null },
    ],
  },
  {
    id: uid(2, 'd'),
    key: 'test_level',
    name: 'Test Level',
    cardinality: 'SINGLE',
    hierarchical: false,
    locked: true,
    values: [
      { id: uid(21, 'v'), parentId: null, name: 'API', path: 'API', color: null },
      { id: uid(22, 'v'), parentId: null, name: 'UI / E2E', path: 'UI-E2E', color: null },
      { id: uid(23, 'v'), parentId: null, name: 'Integration', path: 'Integration', color: null },
      { id: uid(24, 'v'), parentId: null, name: 'Database', path: 'Database', color: null },
      { id: uid(25, 'v'), parentId: null, name: 'Contract', path: 'Contract', color: null },
    ],
  },
  {
    id: uid(3, 'd'),
    key: 'feature',
    name: 'Feature',
    cardinality: 'MULTI',
    hierarchical: true,
    locked: false,
    values: [
      { id: uid(31, 'v'), parentId: null, name: 'Payments', path: 'Payments', color: null },
      { id: uid(32, 'v'), parentId: uid(31, 'v'), name: 'Refunds', path: 'Payments.Refunds', color: null },
      { id: uid(33, 'v'), parentId: uid(32, 'v'), name: 'Partial refund', path: 'Payments.Refunds.Partial', color: null },
      { id: uid(34, 'v'), parentId: uid(31, 'v'), name: 'Card capture', path: 'Payments.Capture', color: null },
      { id: uid(35, 'v'), parentId: null, name: 'Checkout', path: 'Checkout', color: null },
      { id: uid(36, 'v'), parentId: uid(35, 'v'), name: 'Cart', path: 'Checkout.Cart', color: null },
      { id: uid(37, 'v'), parentId: null, name: 'Settlement', path: 'Settlement', color: null },
    ],
  },
  {
    id: uid(4, 'd'),
    key: 'execution_phase',
    name: 'Execution Phase',
    cardinality: 'MULTI',
    hierarchical: false,
    locked: true,
    values: [
      { id: uid(41, 'v'), parentId: null, name: 'Smoke', path: 'Smoke', color: null },
      { id: uid(42, 'v'), parentId: null, name: 'Regression', path: 'Regression', color: null },
      { id: uid(43, 'v'), parentId: null, name: 'Acceptance', path: 'Acceptance', color: null },
    ],
  },
];

// ------------------------------------------------------------------- statuses ----

export const caseStatuses: CaseStatus[] = [
  { id: uid(1, 's'), name: 'Passed', kind: 'PASSED', color: '#2e7d5b', countsAsPass: true, isFinal: true },
  { id: uid(2, 's'), name: 'Failed', kind: 'FAILED', color: '#c0392b', countsAsPass: false, isFinal: true },
  { id: uid(3, 's'), name: 'Blocked', kind: 'BLOCKED', color: '#b7791f', countsAsPass: false, isFinal: true },
  { id: uid(4, 's'), name: 'Skipped', kind: 'SKIPPED', color: '#6b7280', countsAsPass: false, isFinal: true },
  { id: uid(5, 's'), name: 'Retest', kind: 'RETEST', color: '#7c5cbf', countsAsPass: false, isFinal: false },
  { id: uid(6, 's'), name: 'Untested', kind: 'UNTESTED', color: '#9ca3af', countsAsPass: false, isFinal: false },
];

export const statusByKind = Object.fromEntries(caseStatuses.map((s) => [s.kind, s])) as Record<
  string,
  CaseStatus
>;

// --------------------------------------------------------------- configurations ----

export const configurations: Configuration[] = [
  { id: uid(1, 'c'), name: 'Chrome / Windows', isDefault: true, attributes: { browser: 'Chrome', os: 'Windows 11', env: 'staging' } },
  { id: uid(2, 'c'), name: 'Safari / macOS', isDefault: false, attributes: { browser: 'Safari', os: 'macOS 15', env: 'staging' } },
  { id: uid(3, 'c'), name: 'Firefox / Linux', isDefault: false, attributes: { browser: 'Firefox', os: 'Ubuntu 24.04', env: 'staging' } },
];

// -------------------------------------------------------------------- suites ----

export const suites: Suite[] = [
  { id: uid(1, 'q'), key: 'TS-1', parentId: null, name: 'Payments', description: 'All payment flows', path: 'Payments', caseCount: 0, lifecycleStatus: 'APPROVED', conditions: [], lockVersion: 3 },
  { id: uid(2, 'q'), key: 'TS-2', parentId: uid(1, 'q'), name: 'Card capture', description: null, path: 'Payments.Capture', caseCount: 8, lifecycleStatus: 'APPROVED', conditions: [], lockVersion: 1 },
  {
    id: uid(3, 'q'), key: 'TS-3', parentId: uid(1, 'q'), name: 'Refunds',
    description: 'Full and partial refund handling', path: 'Payments.Refunds', caseCount: 12,
    lifecycleStatus: 'APPROVED',
    conditions: [
      { id: uid(90, 'x'), position: 0, conditionId: uid(1, 'n'), title: 'Log in as standard user', body: ['Open the application', 'Enter valid credentials', 'Confirm the dashboard is shown'], inline: false, inherited: false, inheritedFrom: null },
    ],
    lockVersion: 5,
  },
  { id: uid(4, 'q'), key: 'TS-4', parentId: uid(3, 'q'), name: 'Partial refunds', description: null, path: 'Payments.Refunds.Partial', caseCount: 6, lifecycleStatus: 'IN_REVIEW', conditions: [], lockVersion: 2 },
  { id: uid(5, 'q'), key: 'TS-5', parentId: null, name: 'Checkout', description: null, path: 'Checkout', caseCount: 0, lifecycleStatus: 'APPROVED', conditions: [], lockVersion: 1 },
  { id: uid(6, 'q'), key: 'TS-6', parentId: uid(5, 'q'), name: 'Cart', description: null, path: 'Checkout.Cart', caseCount: 9, lifecycleStatus: 'DRAFT', conditions: [], lockVersion: 1 },
  { id: uid(7, 'q'), key: 'TS-7', parentId: null, name: 'Settlement', description: 'Nightly settlement and reconciliation', path: 'Settlement', caseCount: 7, lifecycleStatus: 'APPROVED', conditions: [], lockVersion: 1 },
  { id: uid(8, 'q'), key: 'TS-8', parentId: null, name: 'Unmatched automation', description: 'Quarantine for CI results with no matching case', path: 'Quarantine', caseCount: 3, lifecycleStatus: 'DRAFT', conditions: [], lockVersion: 1 },
  // The only DEPRECATED folder, and it is here so the one edge that leaves that state — "Restore
  // to draft" — can be driven in mock mode at all. §68's retired-classification-value argument:
  // a state no fixture sits in is a state whose only transition is untestable without a backend.
  //
  // The mix above is worth reading in the other direction too. These fixtures have carried
  // APPROVED and IN_REVIEW folders since Phase 1, while `Suite.create` was the only writer
  // `lifecycle_status` had — so mock mode has been showing a signed-off suite tree that the
  // product could not produce on any real instance. §74's exit criteria, one aggregate over.
  { id: uid(9, 'q'), key: 'TS-9', parentId: null, name: 'Legacy card flows', description: 'Superseded by Payments; kept for the audit trail', path: 'Legacy', caseCount: 0, lifecycleStatus: 'DEPRECATED', conditions: [], lockVersion: 2 },
];

// ---------------------------------------------------------------- conditions ----

export const conditions: Condition[] = [
  { id: uid(1, 'n'), title: 'Log in as standard user', type: 'PRE', body: ['Open the application', 'Enter valid credentials', 'Confirm the dashboard is shown'], usageCount: 412 },
  { id: uid(2, 'n'), title: 'Log in as administrator', type: 'PRE', body: ['Open the application', 'Enter administrator credentials', 'Confirm the admin console is shown'], usageCount: 87 },
  { id: uid(3, 'n'), title: 'Navigate to Billing', type: 'PRE', body: ['Open the account menu', 'Select Billing'], usageCount: 156 },
  { id: uid(4, 'n'), title: 'A settled card payment exists', type: 'PRE', body: ['Seed a captured payment of 120.00 EUR', 'Wait for settlement to complete'], usageCount: 64 },
  { id: uid(5, 'n'), title: 'Reset test payment data', type: 'POST', body: ['Void any pending authorisations', 'Restore the merchant balance'], usageCount: 71 },
  { id: uid(6, 'n'), title: 'Log out', type: 'POST', body: ['Open the account menu', 'Select Sign out'], usageCount: 203 },
  { id: uid(7, 'n'), title: 'Sign in as standard user', type: 'PRE', body: ['Open the app', 'Sign in'], usageCount: 2 },
  // BOTH — setup that is also cleanup. Carried so the type filter's fold-in is reachable in
  // mock mode at all: with only PRE and POST rows, exact matching and the real semantics
  // return identical lists and the difference is invisible until live mode.
  { id: uid(8, 'n'), title: 'Restore the merchant balance', type: 'BOTH', body: ['Void any pending authorisations', 'Set the balance to 1000.00 EUR'], usageCount: 38 },
];

// `usageCount` is DERIVED — the server counts the distinct live cases referencing each entry and
// never reads the stored column, so the handler recomputes it here rather than serving these.
// They are 0 rather than plausible numbers on purpose: a handler that forgot to derive would then
// render an unranked picker, which is visible, instead of a confident fake that agrees with
// nothing. The fixture carried 48/33/91 while no case referenced any of them, which is exactly
// the "mock agrees with itself" divergence live mode would have exposed.
export const sharedSteps: SharedStep[] = [
  { id: uid(1, 'h'), title: 'Submit the refund form', body: ['Enter the refund amount', 'Select a reason', 'Confirm'], usageCount: 0 },
  { id: uid(2, 'h'), title: 'Verify the audit entry', body: ['Open the audit log', 'Confirm the newest entry matches the action'], usageCount: 0 },
  { id: uid(3, 'h'), title: 'Open the payment detail drawer', body: ['Click the payment row', 'Wait for the drawer'], usageCount: 0 },
];

// ------------------------------------------------------------------- artifacts ----

export const artifacts: Artifact[] = [
  { id: uid(1, 'f'), scope: 'PROJECT', category: 'Strategy', kind: 'DOC_PAGE_REF', title: 'Payments test strategy', description: 'How we test the payments platform — scope, risks, automation policy.', status: 'ACTIVE', inherited: false, url: null, filename: null, sizeBytes: null, updatedBy: ref(users[0]), updatedAt: '2026-06-11T14:20:00Z' },
  { id: uid(2, 'f'), scope: 'PROJECT', category: 'Environment', kind: 'FILE', title: 'Staging environment setup guide', description: 'Provisioning the payments staging stack.', status: 'ACTIVE', inherited: false, url: '#', filename: 'staging-setup-v4.pdf', sizeBytes: 842_113, updatedBy: ref(users[1]), updatedAt: '2026-02-02T09:05:00Z' },
  { id: uid(3, 'f'), scope: 'PROJECT', category: 'TestData', kind: 'FILE', title: 'Card test data set', description: 'Test PANs by scheme and expected outcome.', status: 'ACTIVE', inherited: false, url: '#', filename: 'card-test-data.csv', sizeBytes: 24_500, updatedBy: ref(users[1]), updatedAt: '2026-07-01T11:11:00Z' },
  { id: uid(4, 'f'), scope: 'PROJECT', category: 'Compliance', kind: 'EXTERNAL_URL', title: 'PCI DSS evidence checklist', description: 'Maintained by the compliance team in Confluence.', status: 'ACTIVE', inherited: false, url: 'https://example.com/confluence/pci', filename: null, sizeBytes: null, updatedBy: ref(users[0]), updatedAt: '2026-05-20T08:00:00Z' },
  { id: uid(5, 'f'), scope: 'PLAN', category: 'SignOff', kind: 'FILE', title: 'Release 4.2 sign-off', description: 'Signed by the release board.', status: 'ACTIVE', inherited: false, url: '#', filename: 'release-4.2-signoff.pdf', sizeBytes: 190_233, updatedBy: ref(users[0]), updatedAt: '2026-07-25T17:40:00Z' },
  { id: uid(6, 'f'), scope: 'PLAN', category: 'Environment', kind: 'FILE', title: 'Environment snapshot at cycle start', description: 'Service versions and feature flags when the cycle opened.', status: 'ACTIVE', inherited: false, url: '#', filename: 'env-snapshot-4.3.json', sizeBytes: 12_004, updatedBy: ref(users[1]), updatedAt: '2026-07-20T08:30:00Z' },
];

// --------------------------------------------------------------- documentation ----

export const docPages: DocPageSummary[] = [
  { id: uid(1, 'p'), key: 'DOC-1', parentId: null, title: 'Payments specification', slug: 'payments-spec', status: 'PUBLISHED', updatedAt: '2026-07-28T10:00:00Z' },
  { id: uid(2, 'p'), key: 'DOC-2', parentId: uid(1, 'p'), title: 'Refunds', slug: 'refunds', status: 'PUBLISHED', updatedAt: '2026-07-28T10:00:00Z' },
  { id: uid(3, 'p'), key: 'DOC-3', parentId: uid(1, 'p'), title: 'Card capture', slug: 'card-capture', status: 'PUBLISHED', updatedAt: '2026-06-02T09:00:00Z' },
  { id: uid(4, 'p'), key: 'DOC-4', parentId: null, title: 'Test strategy', slug: 'test-strategy', status: 'PUBLISHED', updatedAt: '2026-06-11T14:20:00Z' },
  { id: uid(5, 'p'), key: 'DOC-5', parentId: null, title: 'Release 4.3 notes', slug: 'release-4-3', status: 'DRAFT', updatedAt: '2026-07-29T15:45:00Z' },
];

export const docPageDetail: Record<string, DocPage> = {
  [uid(2, 'p')]: {
    ...docPages[1],
    currentVersionNo: 6,
    lockVersion: 6,
    blocks: [
      { id: uid(201, 'b'), position: 0, blockType: 'heading', level: 1, text: 'Refunds', coverage: null },
      { id: uid(202, 'b'), position: 1, blockType: 'paragraph', level: null, text: 'A merchant may refund a settled payment in full or in part. Refunds are asynchronous and settle on the next banking day.', coverage: null },
      { id: uid(203, 'b'), position: 2, blockType: 'heading', level: 2, text: 'Full refund', coverage: null },
      { id: uid(204, 'b'), position: 3, blockType: 'paragraph', level: null, text: 'A full refund returns the entire captured amount. The original payment moves to REFUNDED and cannot be refunded again.', coverage: { linkedCases: 6, passed: 5, failed: 1, notRun: 0, caseKeys: ['TC-1', 'TC-2', 'TC-3', 'TC-4', 'TC-7', 'TC-11'] } },
      { id: uid(205, 'b'), position: 4, blockType: 'heading', level: 2, text: 'Partial refund', coverage: null },
      { id: uid(206, 'b'), position: 5, blockType: 'paragraph', level: null, text: 'A partial refund returns less than the captured amount. Multiple partial refunds are allowed until the captured total is reached; the sum may never exceed it.', coverage: { linkedCases: 4, passed: 2, failed: 1, notRun: 1, caseKeys: ['TC-5', 'TC-6', 'TC-8', 'TC-12'] } },
      { id: uid(207, 'b'), position: 6, blockType: 'paragraph', level: null, text: 'A refund request for an amount greater than the remaining refundable balance is rejected with REFUND_AMOUNT_EXCEEDS_BALANCE.', coverage: { linkedCases: 0, passed: 0, failed: 0, notRun: 0, caseKeys: [] } },
      { id: uid(208, 'b'), position: 7, blockType: 'heading', level: 2, text: 'Refund windows', coverage: null },
      { id: uid(209, 'b'), position: 8, blockType: 'paragraph', level: null, text: 'Refunds are permitted for 180 days after capture. Beyond that the API returns REFUND_WINDOW_EXPIRED.', coverage: { linkedCases: 2, passed: 2, failed: 0, notRun: 0, caseKeys: ['TC-9', 'TC-10'] } },
    ],
  },
};

// -------------------------------------------------------------- plans & runs ----

export const plans: TestPlan[] = [
  {
    id: uid(1, 'l'), key: 'TP-1', name: 'Release 4.3 regression',
    status: 'ACTIVE', milestoneName: 'Release 4.3', milestoneDate: '2026-08-14',
    caseCount: 42, passRate: 0.89, coverage: 0.97,
    description: 'Full regression ahead of the 4.3 release.',
    entryCriteria: [
      { metric: 'build_available', op: '=', value: 1, label: 'Release candidate build deployed to staging', met: true, actual: 1 },
      { metric: 'cases_approved', op: '>=', value: 95, label: 'At least 95% of in-scope cases approved', met: true, actual: 98 },
    ],
    exitCriteria: [
      { metric: 'pass_rate', op: '>=', value: 95, label: 'Pass rate at least 95%', met: false, actual: 89.1 },
      { metric: 'open_critical', op: '=', value: 0, label: 'No open Critical defects', met: false, actual: 2 },
      { metric: 'coverage', op: '>=', value: 100, label: 'Every in-scope case executed', met: false, actual: 97.4 },
    ],
    configurations,
    /*
      Added in §74 with the endpoint that made the scope editable. Real suite ids, not an empty
      array: the plan editor seeds its folder checkboxes from this field, so fixtures carrying `[]`
      would render every plan as unscoped and the one screen this slice adds would look broken in
      the only mode it can be driven in without a backend.
    */
    suiteIds: [uid(1, 'q')],
    caseIds: [],
    currentVersionNo: 2,
    lockVersion: 2,
  },
  {
    id: uid(2, 'l'), key: 'TP-2', name: 'Release 4.2 sign-off',
    status: 'CLOSED', milestoneName: 'Release 4.2', milestoneDate: '2026-07-25',
    caseCount: 38, passRate: 1, coverage: 1,
    description: 'Closed and signed off.',
    entryCriteria: [], exitCriteria: [
      { metric: 'pass_rate', op: '>=', value: 95, label: 'Pass rate at least 95%', met: true, actual: 100 },
    ],
    configurations: [configurations[0]],
    suiteIds: [uid(1, 'q')], caseIds: [],
    currentVersionNo: 1, lockVersion: 1,
  },
  {
    id: uid(3, 'l'), key: 'TP-3', name: 'Settlement smoke (nightly)',
    status: 'ACTIVE', milestoneName: null, milestoneDate: null,
    caseCount: 7, passRate: 0.857, coverage: 1,
    description: 'Runs nightly from CI.',
    entryCriteria: [], exitCriteria: [],
    configurations: [configurations[0]],
    suiteIds: [uid(2, 'q')], caseIds: [],
    currentVersionNo: 1, lockVersion: 1,
  },
  /*
    The two states the fixtures did not have, added in §73 — and the reason is §72's lesson applied
    rather than restated. Every plan here was ACTIVE or CLOSED, while **every plan on every real
    instance was DRAFT**, because nothing could write the column. So the state a fresh install
    actually produces was the one state mock mode never rendered, and the ladder's first two rungs
    had no fixture to exercise them. TP-4 has no runs, so it is the one plan here that can be
    deleted; TP-5 sits mid-review, which is the only state APPROVED is reachable from.
  */
  {
    id: uid(4, 'l'), key: 'TP-4', name: 'Release 4.4 regression',
    status: 'DRAFT', milestoneName: 'Release 4.4', milestoneDate: '2026-09-30',
    caseCount: 0, passRate: null, coverage: null,
    description: 'Being scoped. Nothing has been executed against it.',
    entryCriteria: [], exitCriteria: [],
    configurations: [configurations[0]],
    // Genuinely empty, and it agrees with `caseCount: 0` — a plan somebody has named and not yet
    // filled is the state a fresh install produces, and the editor must open cleanly on it.
    suiteIds: [], caseIds: [],
    currentVersionNo: 1, lockVersion: 1,
  },
  {
    id: uid(5, 'l'), key: 'TP-5', name: 'Payments hardening cycle',
    status: 'IN_REVIEW', milestoneName: null, milestoneDate: null,
    caseCount: 5, passRate: null, coverage: null,
    description: 'Scope submitted for review.',
    entryCriteria: [], exitCriteria: [],
    configurations: [configurations[0]],
    suiteIds: [uid(3, 'q')], caseIds: [],
    currentVersionNo: 1, lockVersion: 1,
  },
];

/**
 * The review record's seed — plan §6g, and two rows that say different things on purpose.
 *
 * `TP-5` is in review **and has an open review** with a peer verdict on it: the ordinary state of
 * something submitted since the record existed, and what the panel is for. Suite `TS-4` is in
 * review and has **no** review row at all — which is not an oversight but the state every artifact
 * that was already `IN_REVIEW` when this shipped is in, on every real instance, until somebody
 * re-submits it. The panel has to read correctly in both, and a fixture set carrying only the tidy
 * one would hide the second from every mock-mode pass (§72's rule about fixtures never sitting in
 * the state a real install produces, applied from the other end).
 *
 * `TC-8`'s closed review is the third shape: a case whose review ended in a rejection, so the
 * trail's `APPROVAL`/`REJECTED` row — the one no client can post — is rendered by something.
 */
const userRefOf = (i: number) => ({
  id: users[i].id,
  displayName: users[i].displayName,
  email: users[i].email,
  avatarUrl: null,
});

export const reviews = [
  {
    id: uid(1, 'w'),
    projectId: projects[0].id,
    subjectType: 'PLAN' as const,
    subjectId: uid(5, 'l'),
    subjectVersionNo: 1,
    state: 'IN_REVIEW' as const,
    submittedBy: userRefOf(1),
    createdAt: '2026-07-29T11:20:00Z',
    approvedBy: null,
    approvedAt: null,
    // Three requests and the awkward one is deliberate: user 2 was asked AND has spoken, the QA
    // Chapter was asked as a TEAM, and user 0 was asked and has said nothing. That third row is
    // the whole reason `assignees` and `participations` are two lists — a request nobody has
    // answered is what a lead opens this panel to find, and merging the lists would render it as
    // somebody with no verdict, which is also what a person who was never asked looks like.
    assignees: [
      {
        id: uid(8, 'w'),
        user: userRefOf(2),
        team: null,
        assignedBy: userRefOf(1),
        assignedAt: '2026-07-29T11:22:00Z',
      },
      {
        id: uid(9, 'w'),
        user: null,
        team: { id: uid(3, 't'), name: 'QA Chapter' },
        assignedBy: userRefOf(1),
        assignedAt: '2026-07-29T11:22:00Z',
      },
      {
        id: uid(10, 'w'),
        user: userRefOf(0),
        team: null,
        assignedBy: userRefOf(1),
        assignedAt: '2026-07-29T11:23:00Z',
      },
    ],
    participations: [
      {
        id: uid(2, 'w'),
        user: userRefOf(2),
        kind: 'PEER_REVIEW' as const,
        verdict: 'CHANGES_REQUESTED' as const,
        comment: 'The settlement suite is missing from the scope.',
        createdAt: '2026-07-29T14:05:00Z',
      },
    ],
    // Two threads and both shapes: one open with a reply under it, one already resolved. The
    // resolved one carries a `resolvedBy`, so the column `V24` added is rendered by something
    // rather than being a field only a live pass could ever show.
    comments: [
      {
        id: uid(5, 'w'),
        author: userRefOf(2),
        body: 'Should the settlement suite be in scope, or is that a separate sign-off?',
        createdAt: '2026-07-29T14:06:00Z',
        resolvedAt: null,
        resolvedBy: null,
        replies: [
          {
            id: uid(6, 'w'),
            author: userRefOf(1),
            body: 'Separate — it ships a week later and has its own plan.',
            createdAt: '2026-07-29T15:12:00Z',
            resolvedAt: null,
            resolvedBy: null,
            replies: [],
          },
        ],
      },
      {
        id: uid(7, 'w'),
        author: userRefOf(0),
        body: 'The exit criterion for blocked cases reads as 0%, which cannot be right.',
        createdAt: '2026-07-29T16:40:00Z',
        resolvedAt: '2026-07-30T08:05:00Z',
        resolvedBy: userRefOf(1),
        replies: [],
      },
    ],
  },
  {
    id: uid(3, 'w'),
    projectId: projects[0].id,
    subjectType: 'CASE' as const,
    // TC-8, the eighth case seed — spelled the way `fixtures/cases.ts` allocates its ids rather
    // than imported, because that module already imports this one and the cycle would be real.
    subjectId: uid(107, 'e'),
    subjectVersionNo: 2,
    state: 'REJECTED' as const,
    submittedBy: userRefOf(2),
    createdAt: '2026-07-24T09:00:00Z',
    approvedBy: null,
    approvedAt: null,
    // Deliberately none, `comments`' reason: most reviews on a real instance were never assigned
    // to anybody, because §6g's peer review is open to the whole tier and assignment is a nudge.
    // A fixture set where every review has assignees is the state §72's rule is about.
    assignees: [],
    participations: [
      {
        id: uid(4, 'w'),
        user: userRefOf(0),
        kind: 'APPROVAL' as const,
        verdict: 'REJECTED' as const,
        comment: null,
        createdAt: '2026-07-24T16:40:00Z',
      },
    ],
    // Deliberately none. A rejected review with an empty conversation is what most reviews on a
    // real instance look like, and a fixture set where every review has threads is exactly the
    // state §72's rule is about — the mock never sitting where a fresh install does.
    comments: [],
  },
  {
    // TC-20, and it is here for the BOARD rather than for the panel (§81): the reviewer's inbox
    // draws a card per person asked, and with only the plan review above, mock mode could show
    // exactly one subject type — so the card's link to a *case* would have been unexercised in the
    // one mode anybody drives. `uid(119, 'e')` is the twentieth case seed, spelled the way
    // `fixtures/cases.ts` allocates ids rather than imported, for the reason TC-8's review gives.
    id: uid(11, 'w'),
    projectId: projects[0].id,
    subjectType: 'CASE' as const,
    subjectId: uid(119, 'e'),
    subjectVersionNo: 1,
    state: 'IN_REVIEW' as const,
    submittedBy: userRefOf(2),
    createdAt: '2026-07-30T08:15:00Z',
    approvedBy: null,
    approvedAt: null,
    assignees: [
      {
        id: uid(12, 'w'),
        user: userRefOf(0),
        team: null,
        assignedBy: userRefOf(2),
        assignedAt: '2026-07-30T08:16:00Z',
      },
    ],
    participations: [],
    comments: [],
  },
];

export const runs: TestRun[] = [
  { id: uid(1, 'k'), key: 'TR-418', name: 'Nightly regression #418', planId: plans[0].id, planKey: 'TP-1', configuration: configurations[0], status: 'COMPLETED', releaseVersion: '4.3.0', buildNumber: '#4471', source: 'INGESTED', startedAt: '2026-07-30T02:00:00Z', completedAt: '2026-07-30T02:41:00Z', counts: { passed: 37, failed: 4, blocked: 1, skipped: 0, notRun: 0, total: 42 } },
  { id: uid(2, 'k'), key: 'TR-417', name: 'Nightly regression #417', planId: plans[0].id, planKey: 'TP-1', configuration: configurations[0], status: 'COMPLETED', releaseVersion: '4.3.0', buildNumber: '#4470', source: 'INGESTED', startedAt: '2026-07-29T02:00:00Z', completedAt: '2026-07-29T02:38:00Z', counts: { passed: 39, failed: 2, blocked: 1, skipped: 0, notRun: 0, total: 42 } },
  { id: uid(3, 'k'), key: 'TR-416', name: 'Safari sweep', planId: plans[0].id, planKey: 'TP-1', configuration: configurations[1], status: 'IN_PROGRESS', releaseVersion: '4.3.0', buildNumber: '#4471', source: 'MANUAL', startedAt: '2026-07-30T09:15:00Z', completedAt: null, counts: { passed: 12, failed: 3, blocked: 0, skipped: 0, notRun: 27, total: 42 } },
  { id: uid(4, 'k'), key: 'TR-402', name: 'Release 4.2 sign-off run', planId: plans[1].id, planKey: 'TP-2', configuration: configurations[0], status: 'COMPLETED', releaseVersion: '4.2.1', buildNumber: '#4390', source: 'MANUAL', startedAt: '2026-07-25T09:00:00Z', completedAt: '2026-07-25T16:20:00Z', counts: { passed: 38, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 38 } },
];

/**
 * The board's typed tasks — the one card kind that is stored rather than derived.
 *
 * `db.workItems` was seeded **empty** until §83, which was fine while every board was one
 * project's: the derived generators filled it, and a create through the dialog landed a real row.
 * §6l's cross-project board makes the emptiness a fixture defect instead. Every derived card in
 * this fixture set comes from the OLB repository (see `db/workItems.ts` on why), so with no stored
 * task anywhere else, **My Work in mock mode would be identical to OLB's personal board** — and the
 * single property §83 adds, that the board spans projects, would be unexercised in the only mode
 * anybody drives. §72's rule: if a screen has two shapes, the fixture carries both.
 *
 * So: one task, in the *second* project, assigned to the signed-in user. `MOB` has no cases, runs
 * or reviews in this fixture set, so a task is the only card it can contribute — which is also a
 * fair picture of a real second project somebody is only lightly involved in.
 */
export const workItems: WorkItem[] = [
  {
    id: uid(1, 'j'),
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'TASK',
    title: 'Draft the card-controls test charter',
    description: 'Before the 2.1 freeze.',
    status: 'IN_PROGRESS',
    priority: 'HIGH',
    dueDate: '2026-08-07',
    overdue: false,
    assignee: {
      id: users[0].id,
      displayName: users[0].displayName,
      email: users[0].email,
      avatarUrl: null,
    },
    projectId: PROJECT_ID_2,
    projectKey: 'MOB',
    // A stored task may move anywhere but the column it is in — the same rule the create handler
    // applies, spelled from the contract's enum rather than as a literal list.
    allowedTransitions: ['TODO', 'BLOCKED', 'IN_REVIEW', 'DONE'],
  },
];

export { uid };
export type { TestCase };
