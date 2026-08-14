/**
 * Complete response bodies, one factory per shape, for the tests that mock `api.*`.
 *
 * ## Why these are shared and complete rather than written per file
 *
 * §196 removed every `as TestCase` from the tree and named the population it could not reach: a
 * body handed to a spy through `as never`, which constrains nothing. `contractDoubles.ts` compares
 * those bodies against `openapi.yaml` at the moment they are served, and it found 629 disagreements
 * across 33 files — most of them a fixture standing in for a response with a third of its fields.
 *
 * A per-file repair would have written those fields out 33 times, which is 33 places for the next
 * required field to be missing from. So each shape lives here once, complete, **typed to the
 * contract and never cast** (§79's rule, as §196 restated it): the day the contract gains a required
 * field, this file is a build error and nothing else is.
 *
 * ## The values are inert on purpose
 *
 * A factory's defaults are the least interesting body the contract admits — empty arrays, nulls
 * where a field is nullable, `DRAFT` where a lifecycle must be named, zero counts. A test says what
 * it is about by overriding, and a reader can therefore tell the two apart: anything a test names is
 * load-bearing for that test, and anything it does not is scenery the contract requires.
 *
 * This is the opposite of a "realistic" fixture, deliberately. A plausible default is a claim about
 * the domain that no test asserts and every test inherits, and when one of them starts depending on
 * it, the dependency is invisible.
 */
import type {
  AuthResult,
  Configuration,
  CurrentUser,
  DocPageSummary,
  ExternalLink,
  PlanStatistics,
  Project,
  ProjectSettings,
  ResultCounts,
  Suite,
  Team,
  TestCase,
  TestCaseSummary,
  TestPlan,
  TestPlanSummary,
  TestRun,
  WorkItem,
} from '../api/types'

/** A fixed instant, so a body carrying a timestamp does not carry a different one each run. */
export const AT = '2026-01-01T00:00:00Z'

export function aProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 'p1',
    key: 'PRJ',
    name: 'Project',
    archived: false,
    caseCount: 0,
    myRole: 'Viewer',
    ...overrides,
  }
}

/**
 * Review gating **off** by default, which is the state most screens are tested in.
 *
 * It is also the state most of them were accidentally in: nine test files answered
 * `/projects/{id}/settings` with the *project* body, so every flag read as `undefined` — falsy, and
 * indistinguishable from a decision until a test wanted the other branch.
 */
export function projectSettings(overrides: Partial<ProjectSettings> = {}): ProjectSettings {
  return {
    projectId: 'p1',
    reviewEnabledCase: false,
    reviewEnabledSuite: false,
    reviewEnabledPlan: false,
    requiredPeerReviews: 1,
    authorMayApproveOwnWork: false,
    editAfterApproval: 'RETAIN',
    requireApprovedCaseForRun: false,
    ...overrides,
  }
}

export function aCaseSummary(overrides: Partial<TestCaseSummary> = {}): TestCaseSummary {
  return {
    id: 'c1',
    key: 'TC-1',
    keySeq: 1,
    title: 'A case',
    suiteId: null,
    suiteName: null,
    priority: 'MEDIUM',
    lifecycleStatus: 'DRAFT',
    automationStatus: 'MANUAL',
    stepFormat: 'STRUCTURED',
    owner: null,
    createdBy: null,
    features: [],
    tags: [],
    healthSignals: [],
    lastResult: null,
    updatedAt: AT,
    ...overrides,
  }
}

export function aCase(overrides: Partial<TestCase> = {}): TestCase {
  return {
    ...aCaseSummary(),
    objective: null,
    description: null,
    expectedOutcome: null,
    preconditions: [],
    postconditions: [],
    declinedPreconditions: [],
    declinedPostconditions: [],
    steps: [],
    gherkinSource: null,
    severity: null,
    estimatedDurationSec: null,
    automationKey: null,
    automationTags: [],
    notAutomatableReason: null,
    classificationValueIds: [],
    relatedDocuments: [],
    attachments: [],
    flake: null,
    currentVersionNo: 1,
    lockVersion: 0,
    ...overrides,
  }
}

export function aSuite(overrides: Partial<Suite> = {}): Suite {
  return {
    id: 's1',
    key: 'TS-1',
    name: 'A suite',
    caseCount: 0,
    tagRules: [],
    caseIds: [],
    lifecycleStatus: 'DRAFT',
    preconditions: [],
    postconditions: [],
    lockVersion: 0,
    ...overrides,
  }
}

export function aPlanSummary(overrides: Partial<TestPlanSummary> = {}): TestPlanSummary {
  return {
    id: 'pl1',
    key: 'TP-1',
    keySeq: 1,
    name: 'A plan',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 0,
    passRate: null,
    coverage: null,
    ...overrides,
  }
}

export function aPlan(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    ...aPlanSummary(),
    description: null,
    scopeText: null,
    outOfScopeText: null,
    entryCriteria: [],
    exitCriteria: [],
    sections: [],
    suiteIds: [],
    caseIds: [],
    configurations: [],
    tags: [],
    currentVersionNo: 1,
    lockVersion: 0,
    ...overrides,
  }
}

export function noCounts(overrides: Partial<ResultCounts> = {}): ResultCounts {
  return { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0, ...overrides }
}

/**
 * The six counts and no others.
 *
 * `RETEST`, `UNTESTED` and `WIP` are real status kinds and **none of them is a count**: the server
 * folds all three into `notRun` (`ExecutionViews`), and `ResultCounts` declares six fields whose
 * comment says the parts sum to the total. Four test files served a seventh, `retest`, and one
 * served `executed` and `untested` as well — a vocabulary the API has never had.
 */
export function aConfiguration(overrides: Partial<Configuration> = {}): Configuration {
  return { id: 'cfg1', name: 'Default', isDefault: true, attributes: {}, ...overrides }
}

export function aRun(overrides: Partial<TestRun> = {}): TestRun {
  return {
    id: 'r1',
    key: 'TR-1',
    name: 'A run',
    planId: null,
    planKey: null,
    configuration: aConfiguration(),
    status: 'NOT_STARTED',
    releaseVersion: null,
    buildNumber: null,
    commitSha: null,
    branch: null,
    source: 'MANUAL',
    startedAt: null,
    completedAt: null,
    counts: noCounts(),
    ...overrides,
  }
}

export function planStatistics(overrides: Partial<PlanStatistics> = {}): PlanStatistics {
  return {
    planId: 'pl1',
    planKey: 'TP-1',
    planName: 'A plan',
    planVersionNo: 1,
    aggregationPolicy: 'LATEST',
    counts: noCounts(),
    coveragePercent: 0,
    stale: false,
    computedAt: AT,
    byConfiguration: [],
    bySuite: [],
    blockers: [],
    notRun: [],
    exitCriteria: [],
    trend: [],
    ...overrides,
  }
}

export function aWorkItem(overrides: Partial<WorkItem> = {}): WorkItem {
  return {
    id: 'w1',
    derived: false,
    kind: 'TASK',
    title: 'An item',
    status: 'TODO',
    priority: 'MEDIUM',
    overdue: false,
    projectId: 'p1',
    projectKey: 'PRJ',
    allowedTransitions: [],
    ...overrides,
  }
}

export function aTeam(overrides: Partial<Team> = {}): Team {
  return { id: 't1', name: 'A team', leads: [], members: [], projects: [], ...overrides }
}

export function aDocPageSummary(overrides: Partial<DocPageSummary> = {}): DocPageSummary {
  return { id: 'dp1', key: 'DOC-1', title: 'A page', slug: 'a-page', status: 'DRAFT', updatedAt: AT, ...overrides }
}

export function anExternalLink(overrides: Partial<ExternalLink> = {}): ExternalLink {
  return { id: 'el1', targetKind: 'EXTERNAL_URL', relationship: 'VERIFIES', ...overrides }
}

export function aCurrentUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: 'u1',
    email: 'user@acme.test',
    displayName: 'A. User',
    isSystemAdmin: false,
    teams: [],
    ...overrides,
  }
}

export function anAuthResult(overrides: Partial<AuthResult> = {}): AuthResult {
  return { token: 'token', expiresAt: AT, user: aCurrentUser(), ...overrides }
}
