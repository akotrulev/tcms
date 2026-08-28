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

export function workItem(overrides: Partial<Schemas['WorkItem']> = {}): Schemas['WorkItem'] {
  return {
    id: 'wi-1',
    derived: false,
    kind: 'TASK',
    title: 'Update the smoke suite',
    status: 'TODO',
    priority: 'HIGH',
    overdue: false,
    projectId: IDS.project,
    projectKey: 'OLB',
    allowedTransitions: [],
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
