/**
 * Complete bodies, shaped like the ones the server sends, for every resource `packages/` doubles.
 *
 * ## Why this exists at all
 *
 * §203 installed `contractExchange.ts` and **118 of this tree's 241 tests went red** — every one of
 * them serving a hand-written object literal that stood in for a response and was missing something
 * the contract requires, or carrying something it does not declare. Repairing them one literal at a
 * time would have produced the same population again the next slice, because the reason they were
 * incomplete is that writing a whole resource by hand is tedious and a test only ever reads three
 * fields of it.
 *
 * So a factory per resource, complete by construction, `Partial<T>` in and `T` out. A test names the
 * two or three fields its assertion turns on and inherits a body the server could actually send. Same
 * answer `frontend/src/test/fixtures.ts` reached after §197, and the same reasoning; the two are not
 * shared for the reason `contractBodies.ts`'s shared region records.
 *
 * ## Declared, never cast — §196's rule, one stack over
 *
 * Each factory's return type is the generated contract type, so a field the contract removes is a
 * **compile** error here rather than a runtime report from the seam. That is deliberate redundancy:
 * the compiler catches the shape, the seam catches the values (an enum member that is not one, a null
 * in a field the server always fills), and neither sees what the other does.
 *
 * ## The defaults are the server's answer for an *empty* resource, not a rich one
 *
 * Arrays are empty, nullable scalars are null, counts are zero. A fixture that shipped a populated
 * `healthSignals` by default would make every test that never mentions health assert against a case
 * that has some — which is how a fixture stops standing for the common case and starts standing for
 * one somebody once needed.
 */
import type { components } from '../schema.js';

type Schemas = components['schemas'];

/**
 * The ids these fixtures use, in one place, because three test files asserting on `'p-1'` and a
 * fourth on `'proj-1'` is how a shared factory stops being usable from the fourth.
 *
 * Deliberately not UUIDs even though the contract says `format: uuid` — `contractBodies.ts` records
 * why it does not check that format, and the short form is what makes a test readable.
 */
export const IDS = {
  project: 'p-1',
  user: 'u-1',
  suite: 's-1',
  case: 'c-1',
  run: 'r-1',
  plan: 'tp-1',
  page: 'dp-1',
  artifact: 'af-1',
  release: 'rl-1',
  importRun: 'ir-1',
  period: 'tsp-1',
  timeEntry: 'te-1',
  allocation: 'al-1',
  absence: 'ab-1',
  skill: 'sk-1',
  client: 'cl-1',
  clientContact: 'cc-1',
  rateCard: 'rc-1',
  rateCardEntry: 'rce-1',
  invoice: 'inv-1',
  expense: 'ex-1',
} as const;

/** A fixed instant, so nothing here depends on when the suite runs. */
export const NOW = '2026-08-08T09:00:00Z';

export function userRef(overrides: Partial<Schemas['UserRef']> = {}): Schemas['UserRef'] {
  return { id: IDS.user, displayName: 'Ana Engineer', email: 'ana@acme.test', ...overrides };
}

export function currentUser(overrides: Partial<Schemas['CurrentUser']> = {}): Schemas['CurrentUser'] {
  return {
    id: IDS.user,
    displayName: 'Ana Engineer',
    email: 'ana@acme.test',
    isSystemAdmin: false,
    hasPassword: true,
    teams: [],
    ...overrides,
  };
}

export function project(overrides: Partial<Schemas['Project']> = {}): Schemas['Project'] {
  return {
    id: IDS.project,
    key: 'OLB',
    name: 'Online Banking',
    archived: false,
    caseCount: 3,
    myRole: 'Test Engineer',
    ...overrides,
  };
}

export function permissions(
  keys: readonly string[],
  qualifiers: Readonly<Record<string, 'ALL' | 'OWN' | 'PLAN_SCOPE'>> = {},
  projectId: string | null = IDS.project,
): Schemas['EffectivePermissions'] {
  return {
    projectId,
    permissions: keys.map((key) => ({
      key: key as Schemas['EffectivePermissions']['permissions'][number]['key'],
      qualifier: qualifiers[key] ?? 'ALL',
    })),
  };
}

export function caseSummary(overrides: Partial<Schemas['TestCaseSummary']> = {}): Schemas['TestCaseSummary'] {
  return {
    id: IDS.case,
    key: 'TC-42',
    keySeq: 42,
    title: 'Login works',
    suiteId: IDS.suite,
    suiteName: 'Smoke',
    priority: 'HIGH',
    lifecycleStatus: 'DRAFT',
    automationStatus: 'MANUAL',
    stepFormat: 'STRUCTURED',
    owner: null,
    createdBy: null,
    features: [],
    tags: [],
    healthSignals: [],
    lastResult: null,
    updatedAt: NOW,
    ...overrides,
  };
}

export function caseDetail(overrides: Partial<Schemas['TestCase']> = {}): Schemas['TestCase'] {
  return {
    ...caseSummary(),
    severity: 'MEDIUM',
    description: null,
    objective: null,
    expectedOutcome: null,
    estimatedDurationSec: null,
    automationKey: null,
    automationTags: [],
    notAutomatableReason: null,
    gherkinSource: null,
    importedFrom: null,
    classificationValueIds: [],
    steps: [],
    preconditions: [],
    postconditions: [],
    declinedPreconditions: [],
    declinedPostconditions: [],
    relatedDocuments: [],
    attachments: [],
    flake: null,
    currentVersionNo: 1,
    lockVersion: 1,
    ...overrides,
  };
}

export function step(overrides: Partial<Schemas['TestStep']> = {}): Schemas['TestStep'] {
  return {
    id: 'st-1',
    position: 1,
    keyword: null,
    action: 'Sign in',
    testData: null,
    expectedResult: 'The dashboard renders.',
    docString: null,
    dataTable: null,
    ...overrides,
  };
}

export function condition(overrides: Partial<Schemas['CaseConditionRef']> = {}): Schemas['CaseConditionRef'] {
  return {
    id: 'sc-1',
    position: 1,
    conditionId: null,
    title: 'The ledger is seeded',
    body: [],
    inline: true,
    inherited: false,
    inheritedFrom: null,
    // B10 — how many cases decline this attachment. Zero is a case's own condition and an
    // inherited row; the number that matters before a detach is on the SUITE's copy.
    declineCount: 0,
    ...overrides,
  };
}

export function suite(overrides: Partial<Schemas['Suite']> = {}): Schemas['Suite'] {
  return {
    id: IDS.suite,
    key: 'TS-1',
    name: 'Smoke',
    caseCount: 4,
    filedCount: 4,
    addedCount: 0,
    taggedCount: 0,
    tagRules: [],
    caseIds: [],
    lifecycleStatus: 'DRAFT',
    preconditions: [],
    postconditions: [],
    lockVersion: 1,
    ...overrides,
  };
}

export function configuration(
  overrides: Partial<Schemas['Configuration']> = {},
): Schemas['Configuration'] {
  return { id: 'cfg-1', name: 'Default', isDefault: true, attributes: {}, ...overrides };
}

export function counts(overrides: Partial<Schemas['ResultCounts']> = {}): Schemas['ResultCounts'] {
  return { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0, ...overrides };
}

export function run(overrides: Partial<Schemas['TestRun']> = {}): Schemas['TestRun'] {
  return {
    id: IDS.run,
    key: 'RUN-1',
    name: 'Nightly',
    planId: null,
    planKey: null,
    configuration: configuration(),
    status: 'NOT_STARTED',
    releaseVersion: null,
    buildNumber: null,
    commitSha: null,
    branch: null,
    source: 'MANUAL',
    createdBy: null,
    startedAt: null,
    completedAt: null,
    counts: counts(),
    // B7 — no request recorded, which is what a run created before `V61` carries.
    scopeRequest: null,
    ...overrides,
  };
}

export function runDetail(overrides: Partial<Schemas['TestRunDetail']> = {}): Schemas['TestRunDetail'] {
  return { ...run(), executions: [], ...overrides };
}

/**
 * A row of the run detail's `executions`, which is **not** the shape `GET …/executions/{id}` answers.
 *
 * `TestExecutionSummary` stops at the verdict; the full `TestExecution` adds the steps, the
 * conditions, the evidence and a lock version. Serving the detail in the list is how a fixture makes
 * a list look like something a caller could edit from.
 */
export function executionSummary(
  overrides: Partial<Schemas['TestExecutionSummary']> = {},
): Schemas['TestExecutionSummary'] {
  return {
    id: 'ex-1',
    caseId: IDS.case,
    caseKey: 'TC-42',
    caseTitle: 'Login works',
    caseVersionNo: 1,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
    quarantined: false,
    ...overrides,
  };
}

export function execution(overrides: Partial<Schemas['TestExecution']> = {}): Schemas['TestExecution'] {
  return {
    id: 'ex-1',
    caseId: IDS.case,
    caseKey: 'TC-42',
    caseTitle: 'Login works',
    caseVersionNo: 1,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
    quarantined: false,
    comment: null,
    releaseVersion: null,
    stepFormat: 'STRUCTURED',
    preconditions: [],
    postconditions: [],
    steps: [],
    evidence: [],
    lockVersion: 1,
    ...overrides,
  };
}

export function plan(overrides: Partial<Schemas['TestPlan']> = {}): Schemas['TestPlan'] {
  return {
    id: IDS.plan,
    key: 'TP-1',
    keySeq: 1,
    name: 'Release 4.2',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 0,
    passRate: null,
    coverage: null,
    exitCriteriaMet: 0,
    exitCriteriaTotal: 0,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    suiteIds: [],
    caseIds: [],
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 1,
    // In the working list — V65 (audit D2). `archived` is orthogonal to `status`.
    archived: false,
    ...overrides,
  };
}

export function docPageSummary(
  overrides: Partial<Schemas['DocPageSummary']> = {},
): Schemas['DocPageSummary'] {
  return {
    id: IDS.page,
    key: 'DOC-1',
    title: 'Release notes',
    slug: 'release-notes',
    status: 'DRAFT',
    updatedAt: NOW,
    ...overrides,
  };
}

export function docPage(overrides: Partial<Schemas['DocPage']> = {}): Schemas['DocPage'] {
  return {
    ...docPageSummary(),
    blocks: [],
    attachments: [],
    currentVersionNo: 1,
    lockVersion: 1,
    ...overrides,
  };
}

/**
 * One row of the artifact register.
 *
 * `EXTERNAL_URL` because it is the kind whose payload is a plain string — the two id-bearing
 * kinds would make every default reference another fixture's row, and a `FILE`'s `filename` and
 * `sizeBytes` are only non-null on that one kind. `writeOnce` is `false`: a frozen row refuses
 * both content acts, so defaulting to it would make every register double drive the exception.
 */
export function artifact(overrides: Partial<Schemas['Artifact']> = {}): Schemas['Artifact'] {
  return {
    id: IDS.artifact,
    scope: 'PROJECT',
    category: 'Strategy',
    kind: 'EXTERNAL_URL',
    title: 'Test strategy',
    description: null,
    status: 'ACTIVE',
    supersededById: null,
    inherited: false,
    url: 'https://docs.example.test/strategy',
    docPageId: null,
    filename: null,
    sizeBytes: null,
    writeOnce: false,
    updatedBy: userRef(),
    updatedAt: NOW,
    ...overrides,
  };
}

export function docBlock(overrides: Partial<Schemas['DocBlock']> = {}): Schemas['DocBlock'] {
  return {
    id: 'db-1',
    position: 1,
    blockType: 'paragraph',
    text: 'The happy path.',
    links: [],
    coverage: null,
    ...overrides,
  };
}

export function review(overrides: Partial<Schemas['Review']> = {}): Schemas['Review'] {
  return {
    id: 'rv-1',
    subjectType: 'CASE',
    subjectId: IDS.case,
    subjectVersionNo: 1,
    state: 'IN_REVIEW',
    createdAt: NOW,
    assignees: [],
    participations: [],
    comments: [],
    ...overrides,
  };
}

export function proposal(overrides: Partial<Schemas['AiProposal']> = {}): Schemas['AiProposal'] {
  return {
    id: 'ap-1',
    projectId: IDS.project,
    title: 'Login works',
    verdict: 'UNVERIFIED',
    verdictSource: 'NONE',
    status: 'PENDING',
    createdAt: NOW,
    filedByAgent: true,
    ...overrides,
  };
}

/**
 * A board card — ~~`WorkItem`~~ since 011 M1, when `work_item` was retired.
 *
 * `kind: 'ISSUE'` is the row-backed one and replaces `TASK`; `status` is an object rather than a
 * fixed enum, because an issue's status is a per-project row and a five-member enum cannot spell
 * one without a mapping table.
 */
export function boardCard(overrides: Partial<Schemas['BoardCard']> = {}): Schemas['BoardCard'] {
  return {
    id: 'ACME-124',
    derived: false,
    kind: 'ISSUE',
    title: 'Update the smoke suite',
    status: { name: 'To Do', kind: 'NOT_STARTED' },
    priority: 'HIGH',
    overdue: false,
    projectId: IDS.project,
    projectKey: 'OLB',
    allowedTransitions: [],
    ...overrides,
  };
}

export function issueType(overrides: Partial<Schemas['IssueTypeRef']> = {}): Schemas['IssueTypeRef'] {
  return { id: 'it-1', name: 'Story', level: 'STORY', countsTowardCompletion: true, ...overrides };
}

export function issueStatus(
  overrides: Partial<Schemas['IssueStatusRef']> = {},
): Schemas['IssueStatusRef'] {
  // `blocking` false by default: at most one status per project carries it (021 D-b), so false
  // is the answer for ten of the eleven seeded statuses and a test about Blocked passes true.
  return { id: 'is-1', name: 'To Do', kind: 'NOT_STARTED', position: 0, blocking: false, ...overrides };
}

export function issueSummary(
  overrides: Partial<Schemas['IssueSummary']> = {},
): Schemas['IssueSummary'] {
  return {
    key: 'ACME-124',
    title: 'Sign-out clears the session',
    type: issueType(),
    status: issueStatus(),
    projectId: IDS.project,
    projectKey: 'ACME',
    agentEligible: false,
    ...overrides,
  };
}

export function issue(overrides: Partial<Schemas['Issue']> = {}): Schemas['Issue'] {
  return {
    key: 'ACME-124',
    seq: 124,
    title: 'Sign-out clears the session',
    type: issueType(),
    status: issueStatus(),
    projectId: IDS.project,
    projectKey: 'ACME',
    watchers: [],
    children: [],
    allowedTransitions: [],
    createdAt: NOW,
    agentEligible: false,
    // Null unless the issue is sitting in the project's `blocking` status (021 D-c).
    blockedFromStatus: null,
    ...overrides,
  };
}

/* --------------------------------------------------------------- agent (015) ---- */

export function projectRepository(
  overrides: Partial<Schemas['ProjectRepository']> = {},
): Schemas['ProjectRepository'] {
  return {
    id: 'pr-1',
    name: 'acme-storefront',
    url: 'git@github.com:northwind/acme-storefront.git',
    defaultBranch: 'main',
    subdirectory: '/',
    buildCommand: 'npm ci && npm run build && npm test',
    position: 0,
    // Computed from `position`, never stored (D16/plan 015 §6) — so the fixture's default agrees
    // with its own position rather than being a second, independent claim.
    primary: true,
    ...overrides,
  };
}

export function agentTaskContext(
  overrides: Partial<Schemas['AgentTaskContext']> = {},
): Schemas['AgentTaskContext'] {
  return {
    issue: issue({ agentEligible: true }),
    story: {
      key: 'ACME-118',
      title: 'Sign-out clears the session',
      acceptanceCriteria: [{ text: 'The session cookie is gone.', done: false }],
    },
    epic: { key: 'ACME-90', title: 'Account security', goal: 'Nobody stays signed in by accident.' },
    specExcerpts: [],
    repositories: [projectRepository()],
    ...overrides,
  };
}

export function issueComment(
  overrides: Partial<Schemas['IssueComment']> = {},
): Schemas['IssueComment'] {
  return { id: 'ic-1', body: 'Reproduced on staging.', author: userRef(), createdAt: NOW, ...overrides };
}

export function issueDocLink(
  overrides: Partial<Schemas['IssueDocLink']> = {},
): Schemas['IssueDocLink'] {
  return { id: 'idl-1', targetKind: 'DOC_BLOCK', relationship: 'VERIFIES', stale: false, ...overrides };
}

/* --------------------------------------------------------------- board (012) ---- */

export function label(overrides: Partial<Schemas['Label']> = {}): Schemas['Label'] {
  return { id: 'lb-1', name: 'Flaky', color: 'label-3', ...overrides };
}

export function boardSummary(
  overrides: Partial<Schemas['BoardSummary']> = {},
): Schemas['BoardSummary'] {
  // `statusCount` agrees with `board()`'s four claimed statuses over three columns — a board
  // claims a SUBSET since 021 R7, so the two numbers are allowed to differ and here they do.
  return {
    id: 'bd-1', name: 'Team board', columnCount: 3, swimlaneBy: 'NONE', statusCount: 4, ...overrides,
  };
}

export function boardColumn(
  overrides: Partial<Schemas['BoardColumn']> = {},
): Schemas['BoardColumn'] {
  return {
    id: 'bc-1',
    name: 'To Do',
    position: 0,
    wipLimit: null,
    statuses: [issueStatus()],
    defaultStatusId: 'is-1',
    ...overrides,
  };
}

/**
 * A board whose three columns claim the four seeded statuses, so the middle one claims two.
 *
 * The doubled claim is the default rather than an override: D2's rule — a card dropped into a
 * column claiming more than one status takes the column's default — has no exercise on a board
 * where every column claims exactly one, and that is the board a hand-written fixture produces.
 */
export function board(overrides: Partial<Schemas['Board']> = {}): Schemas['Board'] {
  return {
    id: 'bd-1',
    projectId: IDS.project,
    name: 'Team board',
    swimlaneBy: 'NONE',
    columns: [
      boardColumn({ id: 'bc-1', name: 'To Do', position: 0, statuses: [issueStatus()], defaultStatusId: 'is-1' }),
      boardColumn({
        id: 'bc-2',
        name: 'In flight',
        position: 1,
        wipLimit: 2,
        statuses: [
          issueStatus({ id: 'is-2', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 }),
          issueStatus({ id: 'is-3', name: 'In Review', kind: 'IN_PROGRESS', position: 2 }),
        ],
        defaultStatusId: 'is-2',
      }),
      boardColumn({
        id: 'bc-3',
        name: 'Done',
        position: 2,
        statuses: [issueStatus({ id: 'is-4', name: 'Done', kind: 'DONE', position: 3 })],
        defaultStatusId: 'is-4',
      }),
    ],
    ...overrides,
  };
}

export function boardIssueCard(
  overrides: Partial<Schemas['BoardIssueCard']> = {},
): Schemas['BoardIssueCard'] {
  return {
    key: 'ACME-124',
    title: 'Sign-out clears the session',
    typeName: 'Story',
    typeLevel: 'STORY',
    iconCode: 'story',
    countsTowardCompletion: true,
    columnId: 'bc-1',
    status: issueStatus(),
    rank: '1000',
    labels: [],
    allowedTransitions: [],
    agentEligible: false,
    subtaskTotal: 0,
    subtaskDone: 0,
    parentKey: null,
    blockedFromStatus: null,
    elsewhere: [],
    // Null, not a zeroed summary: the key is always present so a consumer can tell "not served"
    // (021 T4 has not landed) from "none linked", and a zeroed summary would claim the second.
    linkedCases: null,
    allStoriesDone: false,
    hasAcceptanceCriteria: false,
    ...overrides,
  };
}

export function completion(
  overrides: Partial<Schemas['IssueCompletion']> = {},
): Schemas['IssueCompletion'] {
  return { percent: 0, basis: 'COUNT', done: 0, total: 0, excludedSpikes: 0, ...overrides };
}

export function boardLane(overrides: Partial<Schemas['BoardLane']> = {}): Schemas['BoardLane'] {
  return { key: null, label: 'All work', cards: [boardIssueCard()], completion: completion(), ...overrides };
}

export function boardContents(
  overrides: Partial<Schemas['BoardContents']> = {},
): Schemas['BoardContents'] {
  return { boardId: 'bd-1', swimlaneBy: 'NONE', lanes: [boardLane()], breaches: [], ...overrides };
}

/**
 * The status × board matrix (021 R7) — one row per status, naming the boards that claim it.
 *
 * One covered row and nothing uncovered, which is a freshly seeded project: `uncovered` is the
 * warning the read exists to raise, so a default that carried one would make every test that never
 * mentions coverage assert against a broken project.
 */
export function boardCoverage(
  overrides: Partial<Schemas['BoardCoverage']> = {},
): Schemas['BoardCoverage'] {
  const status = issueStatus();
  return {
    projectId: IDS.project,
    rows: [
      {
        status,
        claims: [{ boardId: 'bd-1', boardName: 'Team board', columnId: 'bc-1', columnName: status.name }],
      },
    ],
    uncovered: [],
    ...overrides,
  };
}

/** A palette row (021 D-j) — enough to draw it and to navigate, and nothing more. */
export function issueSearchHit(
  overrides: Partial<Schemas['IssueSearchHit']> = {},
): Schemas['IssueSearchHit'] {
  return {
    key: 'ACME-118',
    title: 'Guest checkout',
    typeLevel: 'STORY',
    projectId: IDS.project,
    projectName: 'Acme Payments',
    status: issueStatus(),
    ...overrides,
  };
}

export function wipBreach(overrides: Partial<Schemas['WipBreach']> = {}): Schemas['WipBreach'] {
  return { columnId: 'bc-2', limit: 2, count: 3, ...overrides };
}

export function cardMoveResult(
  overrides: Partial<Schemas['CardMoveResult']> = {},
): Schemas['CardMoveResult'] {
  return { card: boardIssueCard({ columnId: 'bc-2' }), ...overrides };
}

export function backlogRow(
  overrides: Partial<Schemas['BacklogRow']> = {},
): Schemas['BacklogRow'] {
  return {
    key: 'ACME-124',
    title: 'Sign-out clears the session',
    typeName: 'Story',
    typeLevel: 'STORY',
    iconCode: 'story',
    position: 1,
    rank: '1000',
    status: issueStatus(),
    labels: [],
    ...overrides,
  };
}

export function rankResult(
  overrides: Partial<Schemas['RankResult']> = {},
): Schemas['RankResult'] {
  return { key: 'ACME-124', rank: '1500', rebalanced: false, ...overrides };
}

/** A project workflow with the six seeded statuses and the five seeded types (011 D-b). */
export function projectWorkflow(
  overrides: Partial<Schemas['ProjectWorkflow']> = {},
): Schemas['ProjectWorkflow'] {
  return {
    projectId: IDS.project,
    statuses: [
      issueStatus({ id: 'is-1', name: 'To Do', kind: 'NOT_STARTED', position: 0 }),
      issueStatus({ id: 'is-2', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 }),
      issueStatus({ id: 'is-3', name: 'In Review', kind: 'IN_PROGRESS', position: 2 }),
      issueStatus({ id: 'is-4', name: 'Done', kind: 'DONE', position: 3 }),
    ],
    types: [
      issueType({ id: 'it-0', name: 'Epic', level: 'EPIC', countsTowardCompletion: false }),
      issueType({ id: 'it-1', name: 'Story', level: 'STORY' }),
      issueType({ id: 'it-2', name: 'Bug', level: 'STORY' }),
      issueType({ id: 'it-3', name: 'Task', level: 'TASK' }),
    ],
    transitions: [{ fromStatusId: 'is-1', toStatusId: 'is-2' }],
    ...overrides,
  };
}

export function caseStatus(overrides: Partial<Schemas['CaseStatus']> = {}): Schemas['CaseStatus'] {
  return {
    id: 'cs-1',
    name: 'Passed',
    kind: 'PASSED',
    color: '#2e7d32',
    countsAsPass: true,
    isFinal: true,
    ...overrides,
  };
}

export function dimension(
  overrides: Partial<Schemas['ClassificationDimension']> = {},
): Schemas['ClassificationDimension'] {
  return {
    id: 'cd-1',
    key: 'component',
    name: 'Component',
    cardinality: 'SINGLE',
    hierarchical: false,
    locked: false,
    values: [],
    ...overrides,
  };
}

/**
 * The repository dashboard, empty.
 *
 * `healthComputedAt: null` is the default and it is the honest one: the contract's own comment says
 * `case_health` has no writer in any deployment yet, so *"nobody has looked"* is what every real
 * instance answers — and it is a different claim from an empty `health` with a timestamp.
 */
export function dashboard(
  overrides: Partial<Schemas['RepositoryDashboard']> = {},
): Schemas['RepositoryDashboard'] {
  return {
    generatedAt: NOW,
    lifecycle: [],
    health: [],
    healthComputedAt: null,
    byFeature: [],
    byOwner: [],
    byLastRun: [],
    automationCoverage: {
      automated: 0,
      toBeAutomated: 0,
      manual: 0,
      notAutomatable: 0,
      coveragePercent: 0,
    },
    recentActivity: [],
    ...overrides,
  };
}

export function bulkResult(overrides: Partial<Schemas['BulkResult']> = {}): Schemas['BulkResult'] {
  return {
    dryRun: false,
    total: 0,
    changed: [],
    unchanged: [],
    skipped: [],
    declinesEnded: [],
    ...overrides,
  };
}

/** A case's execution history, which is three arrays and a summary rather than one list. */
export function caseHistory(overrides: {
  summary?: Partial<Schemas['CaseHistorySummary']>;
  entries?: Schemas['ExecutionHistoryEntry'][];
  versionMarkers?: Schemas['VersionMarker'][];
} = {}): {
  summary: Schemas['CaseHistorySummary'];
  entries: Schemas['ExecutionHistoryEntry'][];
  versionMarkers: Schemas['VersionMarker'][];
} {
  return {
    summary: {
      scope: 'ALL_VERSIONS',
      currentVersionNo: 1,
      executions: 0,
      passed: 0,
      failed: 0,
      ...overrides.summary,
    },
    entries: overrides.entries ?? [],
    versionMarkers: overrides.versionMarkers ?? [],
  };
}

/* ------------------------------------------------------- releases (013) ---- */

export function releaseRef(overrides: Partial<Schemas['ReleaseRef']> = {}): Schemas['ReleaseRef'] {
  return { id: IDS.release, version: '2.4', targetDate: null, ...overrides };
}

export function release(overrides: Partial<Schemas['Release']> = {}): Schemas['Release'] {
  return {
    id: IDS.release,
    projectId: IDS.project,
    version: '2.4',
    description: null,
    targetDate: null,
    status: 'PLANNED',
    releasedAt: null,
    createdAt: NOW,
    updatedAt: null,
    ...overrides,
  };
}

/**
 * A version nothing has run against — `hasRun: false` and both counts **null**.
 *
 * The default is the untested state rather than a clean one, and it is the one default in this file
 * chosen for a reason beyond "empty": `ReleaseVerdict` says an untested version and a passing one
 * are not the same claim, so a fixture defaulting `casesPassed` to 0 would let a `?? 0` anywhere
 * between the wire and a terminal pass every test in the tree.
 */
export function readinessSummary(
  overrides: Partial<Schemas['ReadinessSummary']> = {},
): Schemas['ReadinessSummary'] {
  return {
    verdict: 'NOT_TESTED',
    storiesDone: 0,
    storiesTotal: 0,
    linkedCases: 0,
    hasRun: false,
    casesPassed: null,
    casesFailed: null,
    ...overrides,
  };
}

export function releaseSummary(
  overrides: Partial<Schemas['ReleaseSummary']> = {},
): Schemas['ReleaseSummary'] {
  return {
    id: IDS.release,
    version: '2.4',
    description: null,
    targetDate: null,
    status: 'PLANNED',
    releasedAt: null,
    createdAt: NOW,
    readiness: readinessSummary(),
    ...overrides,
  };
}

export function releaseStories(
  overrides: Partial<Schemas['ReleaseStories']> = {},
): Schemas['ReleaseStories'] {
  return { done: 0, total: 0, inProgress: 0, notStarted: 0, excludedSpikes: 0, ...overrides };
}

export function releaseTests(
  overrides: Partial<Schemas['ReleaseTests']> = {},
): Schemas['ReleaseTests'] {
  return {
    hasRun: false,
    linkedCases: 0,
    withResult: null,
    passed: null,
    failed: null,
    blocked: null,
    untested: null,
    latestExecutedAt: null,
    latestRunId: null,
    latestRunName: null,
    executedBy: null,
    ...overrides,
  };
}

export function releaseBlocker(
  overrides: Partial<Schemas['ReleaseBlocker']> = {},
): Schemas['ReleaseBlocker'] {
  return {
    key: 'ACME-118',
    title: 'Sign-out clears the session',
    type: issueType(),
    status: issueStatus(),
    casesPassed: 0,
    casesFailed: 0,
    casesBlocked: 0,
    casesUntested: 0,
    failedCaseKeys: [],
    ...overrides,
  };
}

export function roadmapEpic(
  overrides: Partial<Schemas['RoadmapEpic']> = {},
): Schemas['RoadmapEpic'] {
  return {
    key: 'ACME-3',
    title: 'Sign-out everywhere',
    startDate: null,
    targetDate: null,
    releaseId: null,
    statusKind: 'NOT_STARTED',
    completionPercent: null,
    ...overrides,
  };
}

export function releaseReadiness(
  overrides: Partial<Schemas['ReleaseReadiness']> = {},
): Schemas['ReleaseReadiness'] {
  return {
    releaseId: IDS.release,
    version: '2.4',
    status: 'PLANNED',
    verdict: 'NOT_TESTED',
    stories: releaseStories(),
    tests: releaseTests(),
    blockers: [],
    epics: [],
    scopeAddedAfterCreation: 0,
    ...overrides,
  };
}

export function roadmapRelease(
  overrides: Partial<Schemas['RoadmapRelease']> = {},
): Schemas['RoadmapRelease'] {
  return {
    id: IDS.release,
    version: '2.4',
    targetDate: null,
    status: 'PLANNED',
    verdict: 'NOT_TESTED',
    epics: [],
    ...overrides,
  };
}

/** Empty by default, which the contract says is the ordinary state of a fresh instance (D14). */
export function roadmap(overrides: Partial<Schemas['Roadmap']> = {}): Schemas['Roadmap'] {
  return { releases: [], unscheduled: [], ...overrides };
}

export function issueRef(overrides: Partial<Schemas['IssueRef']> = {}): Schemas['IssueRef'] {
  return { key: 'ACME-118', title: 'Sign-out clears the session', typeLevel: 'STORY', ...overrides };
}

/** The three `last*` members are required and nullable together — a case never executed is three nulls. */
export function issueTestCase(
  overrides: Partial<Schemas['IssueTestCase']> = {},
): Schemas['IssueTestCase'] {
  return {
    caseId: IDS.case,
    caseKey: 'TC-42',
    title: 'Login works',
    lastResultKind: null,
    lastExecutedAt: null,
    lastReleaseVersion: null,
    ...overrides,
  };
}

/* ---------------------------------------------------------- tracker imports ---- */

/**
 * One row of `tcms import list` — a completed run with nothing dropped.
 *
 * `droppedFieldCount` is zero by default for the file's own rule (an *empty* resource, not a rich
 * one), and a test that is about the dropped list says so rather than inheriting one.
 */
export function importRunSummary(
  overrides: Partial<Schemas['ImportRunSummary']> = {},
): Schemas['ImportRunSummary'] {
  return {
    id: IDS.importRun,
    provider: 'JIRA_CLOUD',
    siteUrl: 'https://northwind.atlassian.net',
    sourceProjectKey: 'PROJ',
    status: 'COMPLETED',
    startedAt: NOW,
    issuesImported: 0,
    statusesMapped: 0,
    labelsCreated: 0,
    droppedFieldCount: 0,
    ...overrides,
  };
}

export function droppedField(
  overrides: Partial<Schemas['DroppedField']> = {},
): Schemas['DroppedField'] {
  return {
    name: 'Story Points',
    fieldType: 'number',
    valueCount: 0,
    reason: 'This product has no estimate field.',
    ...overrides,
  };
}

export function appliedStatusMapping(
  overrides: Partial<Schemas['AppliedStatusMapping']> = {},
): Schemas['AppliedStatusMapping'] {
  return {
    sourceStatusName: 'In Progress',
    targetStatusId: 'is-2',
    targetStatusName: 'In Progress',
    targetStatusKind: 'IN_PROGRESS',
    issueCount: 0,
    ...overrides,
  };
}

/** Unmatched by default: `userId` and `userName` are null together, which is the pair D-j creates. */
export function importedPerson(
  overrides: Partial<Schemas['ImportedPerson']> = {},
): Schemas['ImportedPerson'] {
  return { sourceName: 'jsmith', matched: false, userId: null, userName: null, ...overrides };
}

/** The whole report. `startedBy` is a `UserRef` here; null is the run nobody still resolves for. */
export function importRun(overrides: Partial<Schemas['ImportRun']> = {}): Schemas['ImportRun'] {
  return {
    id: IDS.importRun,
    projectId: IDS.project,
    provider: 'JIRA_CLOUD',
    siteUrl: 'https://northwind.atlassian.net',
    accountEmail: null,
    sourceProjectKey: 'PROJ',
    status: 'COMPLETED',
    startedAt: NOW,
    finishedAt: null,
    startedBy: userRef(),
    issuesImported: 0,
    releasesCreated: 0,
    commentsImported: 0,
    labelsCreated: 0,
    droppedValueCount: 0,
    labelPerSprint: false,
    backlogOrderPreserved: false,
    failureReason: null,
    droppedFields: [],
    statusMapping: [],
    people: [],
    ...overrides,
  };
}

export function caseVerification(
  overrides: Partial<Schemas['CaseVerification']> = {},
): Schemas['CaseVerification'] {
  return { caseId: IDS.case, issues: [], ...overrides };
}

/* --------------------------------------------------------------------- time ---- */

/**
 * A period nobody has submitted, which is the state every screen opens in.
 *
 * `entries` is empty and `approvedHours` is 0 for the reason the header gives: the defaults are
 * the server's answer for an EMPTY resource. A test that turns on an entry names one, and a test
 * that turns on approval names the state — the contract carries approved and pending apart (016
 * D-d) precisely so neither can be inferred from the other.
 */
export function timesheetPeriod(
  overrides: Partial<Schemas['TimesheetPeriod']> = {},
): Schemas['TimesheetPeriod'] {
  return {
    id: IDS.period,
    personId: IDS.user,
    personName: 'Ana Engineer',
    startsOn: '2026-09-01',
    endsOn: '2026-09-15',
    state: 'OPEN',
    loggedHours: 0,
    approvedHours: 0,
    submittedAt: null,
    decidedAt: null,
    decidedByName: null,
    decisionNote: null,
    editable: true,
    entries: [],
    ...overrides,
  };
}

/** An entry against an issue. `subject` is absent for MEETING, LEAVE, TRAINING and INTERNAL. */
export function timeEntry(overrides: Partial<Schemas['TimeEntry']> = {}): Schemas['TimeEntry'] {
  return {
    id: IDS.timeEntry,
    periodId: IDS.period,
    subjectType: 'ISSUE',
    subject: {
      issueId: 'i-1',
      issueKey: 'ACME-124',
      title: 'Address validation',
      typeLevel: 'TASK',
      projectId: IDS.project,
      projectName: 'Acme Storefront',
    },
    spentOn: '2026-09-01',
    hours: 2,
    note: null,
    editable: true,
    ...overrides,
  };
}

/** One person in the approval queue — the period, plus the per-project totals the screen shows. */
export function timesheetQueueEntry(
  overrides: Partial<Schemas['TimesheetQueueEntry']> = {},
): Schemas['TimesheetQueueEntry'] {
  return { period: timesheetPeriod(), projectTotals: [], ...overrides };
}

/**
 * The issue grain. `estimateHours` defaults to null — nobody estimated — which is a real state and
 * not zero, the same distinction `readinessSummary` keeps for an untested release.
 */
export function timeRollup(overrides: Partial<Schemas['TimeRollup']> = {}): Schemas['TimeRollup'] {
  return { issueKey: 'ACME-118', estimateHours: null, approvedHours: 0, pendingHours: 0, ...overrides };
}

export function epicTimeRollup(
  overrides: Partial<Schemas['EpicTimeRollup']> = {},
): Schemas['EpicTimeRollup'] {
  return {
    epicKey: 'ACME-100',
    estimateHours: 0,
    approvedHours: 0,
    pendingHours: 0,
    children: [],
    ...overrides,
  };
}

/* ----------------------------------------------------------------- staffing ---- */

/**
 * Contracted hours, and the one value here that is not the emptiest the contract admits.
 *
 * `weeklyHours: 40` rather than zero: a profile exists to record contracted hours, and nobody is
 * contracted for none. The absence that matters is the **missing profile**, which is
 * `personCapacity({ profile: null, … })` below — a different state, and the one that must never
 * render as `0h` or as an invented 40 (017 correction 4).
 */
export function personProfile(
  overrides: Partial<Schemas['PersonProfile']> = {},
): Schemas['PersonProfile'] {
  return {
    personId: IDS.user,
    weeklyHours: 40,
    jobTitle: null,
    location: null,
    experienceSince: null,
    experienceYears: null,
    ...overrides,
  };
}

export function skill(overrides: Partial<Schemas['Skill']> = {}): Schemas['Skill'] {
  return { id: IDS.skill, name: 'Playwright', ...overrides };
}

/** A whole day off. `member.html`'s *half day* is `dayFraction: 0.5`, named by the test about it. */
export function absence(overrides: Partial<Schemas['Absence']> = {}): Schemas['Absence'] {
  return {
    id: IDS.absence,
    personId: IDS.user,
    startsOn: '2026-08-29',
    endsOn: '2026-08-29',
    kind: 'LEAVE',
    dayFraction: 1,
    note: null,
    ...overrides,
  };
}

export function allocation(overrides: Partial<Schemas['Allocation']> = {}): Schemas['Allocation'] {
  return {
    id: IDS.allocation,
    projectId: IDS.project,
    personId: IDS.user,
    person: userRef(),
    percent: 50,
    startsOn: '2026-09-01',
    endsOn: '2026-11-30',
    note: null,
    ...overrides,
  };
}

/**
 * One person with a profile and nothing allocated.
 *
 * `utilisationPercent: null` is the default rather than a scenario: allocated capacity is zero, and
 * 017 D-i is explicit that `0%` would claim this person is idle where the true statement is that
 * nobody has planned their time. The no-profile state nulls five figures together, because the
 * server sends them together.
 */
export function personCapacity(
  overrides: Partial<Schemas['PersonCapacity']> = {},
): Schemas['PersonCapacity'] {
  return {
    person: userRef(),
    profile: personProfile(),
    skills: [],
    capacityHours: 80,
    absenceHours: 0,
    allocatedPercent: 0,
    allocatedCapacityHours: 0,
    freePercent: 100,
    peakPercent: 0,
    peakOn: null,
    overAllocated: false,
    approvedHours: 0,
    utilisationPercent: null,
    freeFrom: '2026-09-01',
    allocations: [],
    absences: [],
    ...overrides,
  };
}

/** The company view. `overAllocated` is a served subset, never a client-side filter of `people`. */
export function staffingCapacityView(
  overrides: Partial<Schemas['StaffingCapacityView']> = {},
): Schemas['StaffingCapacityView'] {
  return { from: '2026-09-01', to: '2026-11-30', people: [], overAllocated: [], ...overrides };
}

/** The caller's own capacity (017 D-m) — the one staffing read with no staffing key on it. */
export function selfCapacity(
  overrides: Partial<Schemas['SelfCapacity']> = {},
): Schemas['SelfCapacity'] {
  return {
    from: '2026-09-01',
    to: '2026-09-15',
    capacityHours: 80,
    absenceHours: 0,
    allocatedPercent: 0,
    hasProfile: true,
    ...overrides,
  };
}

/** This project's staffing. No row names another project (017 D-f). */
export function projectStaffingView(
  overrides: Partial<Schemas['ProjectStaffingView']> = {},
): Schemas['ProjectStaffingView'] {
  return { from: '2026-09-01', to: '2026-11-30', allocations: [], people: [], ...overrides };
}

/**
 * A page of anything, with the `PageMeta` the contract puts in front of every list.
 *
 * `total` defaults to the number of items rather than to zero: a page claiming a total of 0 while
 * carrying rows is a body the server cannot send, and it is the kind of thing a hand-written literal
 * gets wrong in exactly the direction that makes a paging assertion pass.
 */
export function page<T>(
  items: T[],
  overrides: { page?: number; size?: number; total?: number } = {},
): { page: number; size: number; total: number; items: T[] } {
  return {
    page: overrides.page ?? 0,
    size: overrides.size ?? 20,
    total: overrides.total ?? items.length,
    items,
  };
}

/** The cases list, which is a page plus the facet block the grid's filters read. */
export function casePage(
  items: Schemas['TestCaseSummary'][],
  overrides: { page?: number; size?: number; total?: number } = {},
): ReturnType<typeof page<Schemas['TestCaseSummary']>> & { facets: Schemas['Facet'][] } {
  return { ...page(items, overrides), facets: [] };
}

/**
 * A client organisation.
 *
 * No rate, no cost and no hours figure — none is on this schema and none is on any other in the
 * `clients` tag. What a client's work is worth is M8's, gated on `billing:*` and `ratecard:read`
 * (scope §7).
 */
export function client(overrides: Partial<Schemas['Client']> = {}): Schemas['Client'] {
  return {
    id: IDS.client,
    name: 'Acme Retail',
    location: 'Stockholm',
    industry: 'retail',
    notes: null,
    status: 'ACTIVE',
    accountManager: null,
    projectCount: 0,
    contactCount: 0,
    createdAt: NOW,
    ...overrides,
  };
}

/** The two fields a project card, an overview chip or a picker option renders. */
export function clientRef(overrides: Partial<Schemas['ClientRef']> = {}): Schemas['ClientRef'] {
  return { id: IDS.client, name: 'Acme Retail', ...overrides };
}

/** A person to talk to, and **not an account** — there is no `userId` on this shape (scope D1). */
export function clientContact(
  overrides: Partial<Schemas['ClientContact']> = {},
): Schemas['ClientContact'] {
  return {
    id: IDS.clientContact,
    name: 'Hanna Lund',
    role: 'Product owner',
    email: 'hanna.lund@acme.test',
    phone: '+46 8 555 0142',
    ...overrides,
  };
}

/** A project on the client page — the four fields `Project` already carries, and no more. */
export function clientProject(
  overrides: Partial<Schemas['ClientProject']> = {},
): Schemas['ClientProject'] {
  return { id: IDS.project, key: 'ACME', name: 'Acme Storefront', archived: false, ...overrides };
}

/**
 * `client.html` in one document. `projects` and `contacts` default to empty, per this module's
 * rule that a factory's defaults are the server's answer for an **empty** resource: a client
 * recorded a minute ago has neither.
 */
export function clientDetail(
  overrides: Partial<Schemas['ClientDetail']> = {},
): Schemas['ClientDetail'] {
  return { client: client(), contacts: [], projects: [], ...overrides };
}

/**
 * A monetary figure and its currency, **always together** (019 D-l).
 *
 * There is deliberately no helper that sums two of these. A company with a EUR client and a CHF
 * client has no single total, and every company-level figure on this surface is an array of these
 * rather than one of them.
 */
export function money(overrides: Partial<Schemas['Money']> = {}): Schemas['Money'] {
  return { amount: 0, currency: 'EUR', ...overrides };
}

/** One rate-card entry. `ALL_ROLES` by default — the least specific rung of the ladder (019 D-d). */
export function rateCardEntry(
  overrides: Partial<Schemas['RateCardEntry']> = {},
): Schemas['RateCardEntry'] {
  return {
    id: IDS.rateCardEntry,
    scope: 'ALL_ROLES',
    personId: null,
    roleId: null,
    hourlyRate: 90,
    effectiveFrom: '2026-01-01',
    appliesTo: 'All roles',
    ...overrides,
  };
}

/** One card per client. `entries` defaults to empty — a card set a minute ago has none. */
export function rateCard(overrides: Partial<Schemas['RateCard']> = {}): Schemas['RateCard'] {
  return { id: IDS.rateCard, client: clientRef(), currency: 'EUR', entries: [], ...overrides };
}

/**
 * A client and its card, **or a client and `null`** — `billing.html`'s *no rate card yet* row.
 * The default is `null`, because that is the state every new client is in.
 */
export function rateCardSlot(
  overrides: Partial<Schemas['RateCardSlot']> = {},
): Schemas['RateCardSlot'] {
  return { client: clientRef(), card: null, ...overrides };
}

/**
 * The money view of a budget, gated on `billing:read`. `HOURS` by default, no client, nothing
 * priced — `billing.html`'s internal-project row, which nothing may mark as deficient (019 D-p).
 */
export function budget(overrides: Partial<Schemas['Budget']> = {}): Schemas['Budget'] {
  return {
    projectId: IDS.project,
    projectKey: 'ACME',
    projectName: 'Acme Storefront',
    client: null,
    basis: 'HOURS',
    amountHours: 600,
    amountMoney: null,
    approvedHours: 0,
    pricedToDate: null,
    burnPercent: 0,
    ...overrides,
  };
}

/**
 * The **project-scope** burn — no amount member and no currency member at all (019 D-h).
 * `basis: null` by default: a project with no budget, which is a state and not an error.
 */
export function budgetBurn(overrides: Partial<Schemas['BudgetBurn']> = {}): Schemas['BudgetBurn'] {
  return { basis: null, budgetHours: null, approvedHours: 0, burnPercent: null, ...overrides };
}

/** A draft by default: no number, no issue stamp, nothing on it that only issuing writes. */
export function invoice(overrides: Partial<Schemas['Invoice']> = {}): Schemas['Invoice'] {
  return {
    id: IDS.invoice,
    number: null,
    client: clientRef(),
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    status: 'DRAFT',
    currency: 'EUR',
    total: money(),
    lineCount: 0,
    approvedHours: 0,
    issuedAt: null,
    issuedBy: null,
    ...overrides,
  };
}

/** One project, one rate, the hours at it. */
export function invoiceLine(
  overrides: Partial<Schemas['InvoiceLine']> = {},
): Schemas['InvoiceLine'] {
  return {
    projectId: IDS.project,
    projectName: 'Acme Storefront',
    appliesTo: 'All roles',
    scope: 'ALL_ROLES',
    hours: 0,
    rate: 0,
    amount: money(),
    ...overrides,
  };
}

/**
 * One approved hour behind a line. `periodApprovedBy` is who decided that person's **period** —
 * not who approved this project's hours, and the difference is 019 D-f.
 */
export function invoiceEntry(
  overrides: Partial<Schemas['InvoiceEntry']> = {},
): Schemas['InvoiceEntry'] {
  return {
    timeEntryId: IDS.timeEntry,
    spentOn: '2026-09-02',
    personName: 'Mira Sund',
    hours: 0,
    subjectKey: 'ACME-122',
    subjectTitle: 'Card capture',
    periodApprovedAt: '2026-09-03T08:00:00Z',
    periodApprovedBy: 'Rae Lindqvist',
    ...overrides,
  };
}

/** Hours that are not on a line, counted and named. Never silently dropped (019 D-g). */
export function excludedHours(
  overrides: Partial<Schemas['ExcludedHours']> = {},
): Schemas['ExcludedHours'] {
  return { reason: 'NOT_APPROVED', hours: 0, detail: '', ...overrides };
}

/**
 * `invoice.html` in one document. Every array defaults to empty, `excluded` included — an empty
 * `excluded` means every approved hour in the period is on a line, which is a real answer.
 */
export function invoiceDetail(
  overrides: Partial<Schemas['InvoiceDetail']> = {},
): Schemas['InvoiceDetail'] {
  return {
    invoice: invoice(),
    billTo: null,
    termsDays: 30,
    lines: [],
    entries: [],
    excluded: [],
    budgetEffect: [],
    ...overrides,
  };
}

/** A cost recorded against a project, and never an invoice line (019 D-j). */
export function expense(overrides: Partial<Schemas['Expense']> = {}): Schemas['Expense'] {
  return {
    id: IDS.expense,
    projectId: IDS.project,
    projectName: 'Acme Storefront',
    incurredOn: '2026-09-02',
    description: 'Device lab hire',
    amount: money(),
    ...overrides,
  };
}

/** One row of `client.html`'s hours-and-invoices table. Unbilled by default. */
export function clientBillingRow(
  overrides: Partial<Schemas['ClientBillingRow']> = {},
): Schemas['ClientBillingRow'] {
  return {
    periodLabel: 'Sep 2026',
    periodStart: '2026-09-01',
    projectId: IDS.project,
    projectName: 'Acme Storefront',
    approvedHours: 0,
    invoiceId: null,
    invoiceNumber: null,
    invoiceStatus: null,
    amount: null,
    ...overrides,
  };
}

/**
 * `client.html`'s two tiles and the table under them.
 *
 * **`amountsVisible` defaults to `false`** — the Account Manager's view, which is the one the value
 * column must be *absent* from rather than empty (019 D-i).
 */
export function clientBillingSummary(
  overrides: Partial<Schemas['ClientBillingSummary']> = {},
): Schemas['ClientBillingSummary'] {
  return {
    amountsVisible: false,
    approvedHoursToDate: 0,
    approvedHoursThisQuarter: 0,
    invoiceCount: 0,
    lastIssuedAt: null,
    rows: [],
    ...overrides,
  };
}

/** `billing.html`'s four tiles. Empty arrays mean nothing issued and nothing draftable. */
export function billingSummary(
  overrides: Partial<Schemas['BillingSummary']> = {},
): Schemas['BillingSummary'] {
  return {
    invoicedYearToDate: [],
    draftableNow: [],
    unapprovedHours: 0,
    clientsBilled: 0,
    ...overrides,
  };
}

/**
 * Delivery analytics (020, M9) — three reports, and every one of them defaults to **sufficient**.
 *
 * The insufficient case is what these exist to let a test *ask for*: `burnUpReport({ sufficiency:
 * reportSufficiency({ sufficient: false, reason: '…' }) })` is the shape a client must read as "this
 * cannot be drawn and here is why" rather than as an empty series. It is a 200, not an error — a
 * well-formed question about a project with three issues deserves a true statement about what can be
 * said (020 D-e).
 */
export function reportSufficiency(
  overrides: Partial<Schemas['ReportSufficiency']> = {},
): Schemas['ReportSufficiency'] {
  return {
    sufficient: true,
    reason: null,
    issuesConsidered: 27,
    ...overrides,
  };
}

/**
 * The mockup's numbers: 27 counted, 21 done, and **4 assigned after 2.4 was created** — the one
 * figure in this chart that cannot be reconstructed after the fact (009 review R12).
 */
export function burnUpReport(
  overrides: Partial<Schemas['BurnUpReport']> = {},
): Schemas['BurnUpReport'] {
  return {
    releaseId: '00000000-0000-4000-8000-0000000009a1',
    releaseVersion: '2.4',
    releaseCreatedAt: '2026-07-01T09:00:00Z',
    countedTotal: 27,
    doneTotal: 21,
    scopeAddedAfterCreation: 4,
    points: [
      { date: '2026-07-01', done: 0, totalScope: 23 },
      { date: '2026-08-12', done: 12, totalScope: 27 },
      { date: '2026-09-02', done: 21, totalScope: 27 },
    ],
    sufficiency: reportSufficiency(),
    ...overrides,
  };
}

/**
 * Four bands, ordered `count` descending then alphabetically — the order the server serves, and the
 * order a test asserting determinism expects. `measuredFrom` is `CREATED` and there is no other
 * value to give it (020 D-c): this product records no moment at which work started.
 */
export function cycleTimeReport(
  overrides: Partial<Schemas['CycleTimeReport']> = {},
): Schemas['CycleTimeReport'] {
  return {
    measuredFrom: 'CREATED',
    windowDays: 90,
    bands: [
      { typeName: 'Task', count: 41, medianDays: 1.8, p90Days: 4 },
      { typeName: 'Story', count: 18, medianDays: 6.2, p90Days: 14 },
      { typeName: 'Bug', count: 9, medianDays: 3.1, p90Days: 9 },
      { typeName: 'Spike', count: 3, medianDays: 4, p90Days: 7 },
    ],
    sufficiency: reportSufficiency({ issuesConsidered: 71 }),
    ...overrides,
  };
}

/**
 * `beforeCurrentStatus` shrinks to **zero at the newest point**, which is the property that makes
 * the fourth band honest rather than a fourth status (020 D-d). A fixture whose last point carried a
 * non-zero one would describe something the server cannot produce.
 */
export function cumulativeFlowReport(
  overrides: Partial<Schemas['CumulativeFlowReport']> = {},
): Schemas['CumulativeFlowReport'] {
  return {
    windowWeeks: 12,
    points: [
      { date: '2026-06-15', notStarted: 14, inFlight: 4, done: 2, beforeCurrentStatus: 7 },
      { date: '2026-08-03', notStarted: 9, inFlight: 5, done: 11, beforeCurrentStatus: 2 },
      { date: '2026-09-01', notStarted: 3, inFlight: 5, done: 19, beforeCurrentStatus: 0 },
    ],
    sufficiency: reportSufficiency(),
    ...overrides,
  };
}
