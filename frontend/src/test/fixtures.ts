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
  Artifact,
  AuditEvent,
  AuthResult,
  Configuration,
  CurrentUser,
  DocPageSearchHit,
  DocPageSummary,
  ExecutionAssignmentChange,
  ExecutionBulkResult,
  ExternalLink,
  FeatureImportPlan,
  IdentityProvider,
  InvitationPreview,
  IssueLink,
  NotificationChannel,
  NotificationDelivery,
  NotificationSubscription,
  PlanStatistics,
  Project,
  ProjectSettings,
  ReviewImpact,
  SocialIdentity,
  SocialIdentityList,
  SocialProviderList,
  SocialRedirect,
  OrgChart,
  OrgChartAssignment,
  OrgChartPerson,
  OrgChartProject,
  ResultCounts,
  SsoProviderChoice,
  Role,
  RoleAssignment,
  Suite,
  Team,
  TrackerBinding,
  TrackerConnectedMember,
  TrackerIdentity,
  TrackerIssue,
  User,
  UserInvitation,
  UserRef,
  ImportRow,
  TestCase,
  TestStep,
  StepResult,
  CaseSearchHit,
  TestCaseSummary,
  TestExecution,
  RunSchedule,
  TestPlan,
  TestPlanSummary,
  TestRun,
  BoardCard,
  Issue,
  IssueComment,
  IssueDocLink,
  IssueStatusRef,
  IssueSummary,
  IssueTypeRef,
  ProjectWorkflow,
  BacklogRow,
  BoardConfig,
  BoardContents,
  BoardIssueCard,
  BoardLane,
  BoardSummary,
  CardMoveResult,
  IssueCompletion,
  IssueLabel,
  RankResult,
  CaseVerification,
  IssueRef,
  IssueTestCase,
  ReadinessSummary,
  Release,
  ReleaseBlocker,
  ReleaseReadiness,
  ReleaseStories,
  ReleaseSummary,
  ReleaseTests,
  Roadmap,
  RoadmapEpic,
  RoadmapRelease,
  DroppedField,
  ImportBlocker,
  ImportPreview,
  ImportRun,
  ImportRunSummary,
  PreviewStatusRow,
  PreviewTypeRow,
  SourceProject,
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
    // B1's fourth review subject (005 S9b). `false` like its three siblings — off is the state
    // most screens are tested in, and it is what a freshly provisioned project carries.
    reviewEnabledDoc: false,
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

/** One cross-project search hit (§249) — a waypoint, so deliberately fewer fields than a summary. */
export function aCaseSearchHit(overrides: Partial<CaseSearchHit> = {}): CaseSearchHit {
  return {
    id: 'c1',
    key: 'TC-1',
    keySeq: 1,
    title: 'A case',
    projectId: 'p1',
    projectKey: 'PRJ',
    projectName: 'A project',
    lifecycleStatus: 'DRAFT',
    automationStatus: 'MANUAL',
    priority: 'MEDIUM',
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
    // Null for every case nobody imported, which today is all of them (§255) — the columns exist
    // from `V69` and the writer arrives with the import endpoint.
    importedFrom: null,
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

/**
 * One authored step (§255) — the least interesting one the contract admits.
 *
 * `keyword`, `docString` and `dataTable` are all null by default, which is a **structured** step
 * with test data and an expected result and no block argument: the shape most of the suite's cases
 * are about. A test that is about the Gherkin lens says so by naming a keyword.
 */
export function aStep(overrides: Partial<TestStep> = {}): TestStep {
  return {
    id: 's1',
    position: 0,
    keyword: null,
    action: 'A step',
    testData: null,
    expectedResult: null,
    docString: null,
    dataTable: null,
    ...overrides,
  }
}

/**
 * One step of a pinned case version, with whatever verdict has been recorded against it.
 *
 * The step half is history and the result half is current — `StepResult`'s own description — which
 * is why they are one shape rather than two lists a client joins by `stepId`.
 */
export function aStepResult(overrides: Partial<StepResult> = {}): StepResult {
  return {
    stepId: 's1',
    position: 0,
    keyword: null,
    action: 'A step',
    testData: null,
    expectedResult: null,
    docString: null,
    dataTable: null,
    statusId: null,
    comment: null,
    evidence: [],
    ...overrides,
  }
}

export function aSuite(overrides: Partial<Suite> = {}): Suite {
  return {
    id: 's1',
    key: 'TS-1',
    name: 'A suite',
    // The three arms a suite resolves through (B1 / 005 S1), each zero and summing to `caseCount`:
    // a suite is filed ∪ added ∪ tagged, and a fixture that filled one of them would make every
    // test that never mentions membership assert against a suite that has some.
    filedCount: 0,
    addedCount: 0,
    taggedCount: 0,
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
    // Zero of zero, not "none met": a plan with no exit criteria is the ordinary state and both
    // numbers are counts rather than a nullable ratio (B1 / 005 S5).
    exitCriteriaMet: 0,
    exitCriteriaTotal: 0,
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
    // Not archived — the working-list state, which is what every plan fixture wants by default
    // (V65, audit D2). Pass `archived: true` to build the other one.
    archived: false,
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
    // Null is a real answer here and the honest default: a scheduled run and an ingested one have
    // no actor at all, which is what `TestRun.createdBy` being nullable records (B1 / 005 S1).
    createdBy: null,
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
    // B7 — null is "no request recorded", which is what a run created before `V61` or by an
    // ingested report carries. A test about the provenance line passes its own.
    scopeRequest: null,
    ...overrides,
  }
}

/**
 * One execution as `GET …/executions/{id}` and both writes on it serve it.
 *
 * The least interesting execution the contract admits: materialised, no verdict, nobody assigned,
 * no steps, no evidence — which is exactly the state `RunCreateService` writes and therefore the
 * one every test about *changing* an execution should be overriding away from.
 */
export function anExecution(overrides: Partial<TestExecution> = {}): TestExecution {
  return {
    id: 'ex1',
    caseId: 'c1',
    caseKey: 'TC-1',
    caseTitle: 'A case',
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
    lockVersion: 0,
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

/**
 * A status ref — id, name, kind and position, which is what a *transition target* carries.
 *
 * The name is the project's own, so the default is a plausible one rather than a code: there is
 * no enum behind it any more (011 D-c) and a fixture spelling `TODO` would be a project nobody
 * has.
 */
export function aStatusRef(overrides: Partial<IssueStatusRef> = {}): IssueStatusRef {
  return { id: 's1', name: 'Backlog', kind: 'NOT_STARTED', position: 0, ...overrides }
}

export function anIssueType(overrides: Partial<IssueTypeRef> = {}): IssueTypeRef {
  return { id: 'ty1', name: 'Story', level: 'STORY', countsTowardCompletion: true, ...overrides }
}

/**
 * A card on any of the three boards — ~~`aWorkItem`~~, renamed with the shape (011 D-a/D-c).
 *
 * `allowedTransitions` is empty by default, which is the inert value and also the honest one: a
 * card that can go nowhere is what a derived card and an epic both are, and a test that wants a
 * movable card says so.
 */
export function aBoardCard(overrides: Partial<BoardCard> = {}): BoardCard {
  return {
    id: 'PRJ-1',
    derived: false,
    kind: 'ISSUE',
    title: 'An item',
    status: { name: 'Backlog', kind: 'NOT_STARTED' },
    priority: 'MEDIUM',
    overdue: false,
    projectId: 'p1',
    projectKey: 'PRJ',
    allowedTransitions: [],
    ...overrides,
  }
}

/**
 * A card on a **project** board — the 012 M2 shape, which is not {@link aBoardCard}'s.
 *
 * `allowedTransitions` is empty by default for `aBoardCard`'s reason, and here it carries a second
 * one: an Epic reaches this shape with an empty set whatever the target (D-r), so the inert value is
 * also the one the contract's own comment singles out.
 */
export function aBoardIssueCard(overrides: Partial<BoardIssueCard> = {}): BoardIssueCard {
  return {
    key: 'PRJ-1',
    title: 'An item',
    typeName: 'Story',
    typeLevel: 'STORY',
    iconCode: 'story',
    countsTowardCompletion: true,
    columnId: 'col-1',
    status: aStatusRef(),
    rank: '1024',
    labels: [],
    allowedTransitions: [],
    agentEligible: false,
    subtaskTotal: 0,
    subtaskDone: 0,
    ...overrides,
  }
}

/**
 * A completion, **with its basis**, which is the whole reason it is on the wire (D8).
 *
 * `COUNT` rather than `ESTIMATE` by default: an estimate basis is the case where every counted issue
 * carries an estimate, so it is the narrower state and a fixture asserting against it should say so.
 */
export function aCompletion(overrides: Partial<IssueCompletion> = {}): IssueCompletion {
  return { percent: 0, basis: 'COUNT', done: 0, total: 0, excludedSpikes: 0, ...overrides }
}

export function aBoardLane(overrides: Partial<BoardLane> = {}): BoardLane {
  return { key: null, label: 'No epic', cards: [], completion: aCompletion(), ...overrides }
}

/**
 * A board's cards.
 *
 * `breaches` is empty by default — the normal case, and the one a test that is not about WIP limits
 * should be starting from.
 */
export function boardContents(overrides: Partial<BoardContents> = {}): BoardContents {
  return {
    boardId: 'b1',
    swimlaneBy: 'NONE',
    lanes: [aBoardLane()],
    breaches: [],
    ...overrides,
  }
}

export function aBoardSummary(overrides: Partial<BoardSummary> = {}): BoardSummary {
  return { id: 'b1', name: 'Team board', columnCount: 4, swimlaneBy: 'NONE', ...overrides }
}

export function aBoardConfig(overrides: Partial<BoardConfig> = {}): BoardConfig {
  return {
    id: 'b1',
    projectId: 'p1',
    name: 'Team board',
    swimlaneBy: 'NONE',
    columns: [
      {
        id: 'col-1',
        name: 'Backlog',
        position: 0,
        wipLimit: null,
        statuses: [aStatusRef()],
        defaultStatusId: 's1',
      },
    ],
    ...overrides,
  }
}

/**
 * A row in the ranked backlog. **No epic appears here** (D-r) — an epic's status is derived from its
 * stories, so it has nothing to be ranked against.
 */
export function aBacklogRow(overrides: Partial<BacklogRow> = {}): BacklogRow {
  return {
    key: 'PRJ-1',
    title: 'An item',
    typeName: 'Story',
    typeLevel: 'STORY',
    iconCode: 'story',
    position: 1,
    rank: '1024',
    status: aStatusRef(),
    labels: [],
    ...overrides,
  }
}

/** Where a rank write landed. `rebalanced` is false by default — a respace is the rare answer. */
export function aRankResult(overrides: Partial<RankResult> = {}): RankResult {
  return { key: 'PRJ-1', rank: '1536', rebalanced: false, ...overrides }
}

/** A move that landed with no breach — the ordinary case; a test about WIP passes one in. */
export function aCardMoveResult(overrides: Partial<CardMoveResult> = {}): CardMoveResult {
  return { card: aBoardIssueCard(), ...overrides }
}

export function anIssueLabel(overrides: Partial<IssueLabel> = {}): IssueLabel {
  return { id: 'lab1', name: 'needs-design', color: 'label-3', ...overrides }
}

// ---------------------------------------------------------------- releases (013) ----

export function aRelease(overrides: Partial<Release> = {}): Release {
  return {
    id: 'rel1',
    projectId: 'p1',
    version: '2.4',
    description: null,
    targetDate: null,
    status: 'PLANNED',
    releasedAt: null,
    createdAt: AT,
    updatedAt: null,
    ...overrides,
  }
}

/**
 * A version nothing has run against — `hasRun: false` and both counts **null**.
 *
 * The one default in this file chosen for a reason beyond "least interesting": `ReleaseVerdict` says
 * an untested version and a passing one are not the same claim, so a fixture defaulting
 * `casesPassed` to `0` would let a `?? 0` anywhere between the wire and a rendered meter pass every
 * test in the tree. The inert default is the one that catches the defect.
 */
export function aReadinessSummary(overrides: Partial<ReadinessSummary> = {}): ReadinessSummary {
  return {
    verdict: 'NOT_TESTED',
    storiesDone: 0,
    storiesTotal: 0,
    linkedCases: 0,
    hasRun: false,
    casesPassed: null,
    casesFailed: null,
    ...overrides,
  }
}

/** A row in `releases.html`'s table — its readiness is inline, never a second read. */
export function aReleaseSummary(overrides: Partial<ReleaseSummary> = {}): ReleaseSummary {
  return {
    id: 'rel1',
    version: '2.4',
    description: null,
    targetDate: null,
    status: 'PLANNED',
    releasedAt: null,
    createdAt: AT,
    readiness: aReadinessSummary(),
    ...overrides,
  }
}

export function aReleaseStories(overrides: Partial<ReleaseStories> = {}): ReleaseStories {
  return { done: 0, total: 0, inProgress: 0, notStarted: 0, excludedSpikes: 0, ...overrides }
}

/** Null counts by default, for {@link aReadinessSummary}'s reason. */
export function aReleaseTests(overrides: Partial<ReleaseTests> = {}): ReleaseTests {
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
  }
}

export function aReleaseBlocker(overrides: Partial<ReleaseBlocker> = {}): ReleaseBlocker {
  return {
    key: 'PRJ-1',
    title: 'An issue',
    type: anIssueType(),
    status: aStatusRef(),
    casesPassed: 0,
    casesFailed: 0,
    casesBlocked: 0,
    casesUntested: 0,
    failedCaseKeys: [],
    ...overrides,
  }
}

export function aRoadmapEpic(overrides: Partial<RoadmapEpic> = {}): RoadmapEpic {
  return {
    key: 'PRJ-9',
    title: 'An epic',
    startDate: null,
    targetDate: null,
    releaseId: null,
    statusKind: 'NOT_STARTED',
    completionPercent: null,
    ...overrides,
  }
}

export function aReleaseReadiness(overrides: Partial<ReleaseReadiness> = {}): ReleaseReadiness {
  return {
    releaseId: 'rel1',
    version: '2.4',
    status: 'PLANNED',
    verdict: 'NOT_TESTED',
    stories: aReleaseStories(),
    tests: aReleaseTests(),
    blockers: [],
    epics: [],
    scopeAddedAfterCreation: 0,
    ...overrides,
  }
}

export function aRoadmapRelease(overrides: Partial<RoadmapRelease> = {}): RoadmapRelease {
  return {
    id: 'rel1',
    version: '2.4',
    targetDate: null,
    status: 'PLANNED',
    verdict: 'NOT_TESTED',
    epics: [],
    ...overrides,
  }
}

/** Empty by default, which the contract says is the ordinary state of a fresh instance (D14). */
export function aRoadmap(overrides: Partial<Roadmap> = {}): Roadmap {
  return { releases: [], unscheduled: [], ...overrides }
}

export function anIssueRef(overrides: Partial<IssueRef> = {}): IssueRef {
  return { key: 'PRJ-1', title: 'An issue', typeLevel: 'STORY', ...overrides }
}

/** The three `last*` members are required and nullable together — a case never run is three nulls. */
export function anIssueTestCase(overrides: Partial<IssueTestCase> = {}): IssueTestCase {
  return {
    caseId: 'c1',
    caseKey: 'TC-1',
    title: 'A case',
    lastResultKind: null,
    lastExecutedAt: null,
    lastReleaseVersion: null,
    ...overrides,
  }
}

export function aCaseVerification(overrides: Partial<CaseVerification> = {}): CaseVerification {
  return { caseId: 'c1', issues: [], ...overrides }
}

/** A row in a list — the work table, an epic's children, a story's sub-tasks. */
export function anIssueSummary(overrides: Partial<IssueSummary> = {}): IssueSummary {
  return {
    key: 'PRJ-1',
    title: 'An issue',
    type: anIssueType(),
    status: aStatusRef(),
    projectId: 'p1',
    projectKey: 'PRJ',
    agentEligible: false,
    ...overrides,
  }
}

/**
 * One issue.
 *
 * `completion` is **absent** by default and not a zeroed object: it is epics-only on the wire, and
 * a fixture that always carried one would let a story page render a meter the server can never
 * send it (D8).
 */
export function anIssue(overrides: Partial<Issue> = {}): Issue {
  return {
    key: 'PRJ-1',
    seq: 1,
    title: 'An issue',
    type: anIssueType(),
    status: aStatusRef(),
    projectId: 'p1',
    projectKey: 'PRJ',
    watchers: [],
    children: [],
    allowedTransitions: [],
    createdAt: AT,
    agentEligible: false,
    ...overrides,
  }
}

export function anIssueComment(overrides: Partial<IssueComment> = {}): IssueComment {
  return { id: 'c1', body: 'A comment', author: aUserRef(), createdAt: AT, ...overrides }
}

/** A spec link as `tracker` projects it — `stale` false, which is the state a fresh link is in. */
export function anIssueDocLink(overrides: Partial<IssueDocLink> = {}): IssueDocLink {
  return {
    id: 'dl1',
    targetKind: 'DOC_BLOCK',
    relationship: 'REFERENCES',
    stale: false,
    ...overrides,
  }
}

/**
 * A project's workflow — one status, one type, no transitions.
 *
 * Deliberately the least interesting workflow the contract admits: a test about the legal-move set
 * states its own, and one about a screen that merely needs a vocabulary does not inherit a
 * plausible six-status one it never asserted.
 */
export function aProjectWorkflow(overrides: Partial<ProjectWorkflow> = {}): ProjectWorkflow {
  return {
    projectId: 'p1',
    statuses: [aStatusRef()],
    types: [anIssueType()],
    transitions: [],
    ...overrides,
  }
}

export function aTeam(overrides: Partial<Team> = {}): Team {
  return { id: 't1', name: 'A team', leads: [], members: [], projects: [], ...overrides }
}

export function aUserRef(overrides: Partial<UserRef> = {}): UserRef {
  return { id: 'u1', displayName: 'A. Person', email: 'a.person@acme.test', avatarUrl: null, ...overrides }
}

export function aUser(overrides: Partial<User> = {}): User {
  return { ...aUserRef(), status: 'ACTIVE', lastLoginAt: null, teams: [], ...overrides }
}

/**
 * One role, complete rather than minimal like every factory here — `Role` has seven required
 * properties and a double serving a subset is a screen one commit away from a TypeError.
 *
 * `assignable` defaults to `true`: three seeded rows are non-assignable and none of them is what a
 * test that says "a role" means, so the default is the one a grant picker would actually offer.
 */
export function aRole(overrides: Partial<Role> = {}): Role {
  return {
    id: 'r1',
    name: 'Test Engineer',
    scope: 'PROJECT',
    builtIn: true,
    assignable: true,
    description: 'Writes and maintains cases.',
    permissions: ['project:read', 'case:read', 'case:edit'],
    ...overrides,
  }
}

/**
 * One grant. `user` and `team` are user *xor* team on the wire — the constraint the column carries
 * — so this defaults to the user arm and a team grant is `aRoleAssignment({ user: null, team })`
 * rather than an override that sets both.
 */
export function aRoleAssignment(overrides: Partial<RoleAssignment> = {}): RoleAssignment {
  return {
    id: 'ra1',
    role: aRole(),
    user: aUserRef(),
    team: null,
    projectId: null,
    projectName: null,
    grantedAt: AT,
    // Null is a real answer: a grant written by `provision-company` or by a directory sync has no
    // human actor, which is what `RoleAssignment.grantedBy` being nullable records (B1 / 005 S1).
    grantedBy: null,
    ...overrides,
  }
}

export function aUserInvitation(overrides: Partial<UserInvitation> = {}): UserInvitation {
  return { user: aUser(), issued: true, emailed: false, expiresAt: AT, ...overrides }
}

export function anOrgChartProject(overrides: Partial<OrgChartProject> = {}): OrgChartProject {
  return { id: 'p1', key: 'ATL', name: 'Atlas', archived: false, ...overrides }
}

/**
 * One matrix cell. `via` defaults to `DIRECT` with a null `viaTeam`, which is the pairing the
 * contract requires — `viaTeam` is non-null EXACTLY when `via` is `TEAM`, so a test driving the
 * team arm must set both.
 */
export function anOrgChartAssignment(
  overrides: Partial<OrgChartAssignment> = {},
): OrgChartAssignment {
  return { projectId: 'p1', roleName: 'Test Engineer', via: 'DIRECT', viaTeam: null, ...overrides }
}

/**
 * One person on the chart. `projects` is empty by default: somebody with no cell in any column the
 * READER can see is the ordinary row, not an edge case, and it is the row `OrgChartGaps` is about.
 */
export function anOrgChartPerson(overrides: Partial<OrgChartPerson> = {}): OrgChartPerson {
  return {
    ...aUserRef(),
    status: 'ACTIVE',
    teams: [],
    leadsTeams: [],
    companyRoles: [],
    projects: [],
    lastLoginAt: null,
    ...overrides,
  }
}

/**
 * `gaps` defaults to empty rather than being omitted, and the two are different claims.
 *
 * It is required on the wire (005 S6) and nullable in neither direction: an org chart with nothing
 * missing serves `[]`. A factory that left it out would make every chart test drive a shape the
 * server cannot send, and the Gaps panel's empty state — the one an administrator sees when the
 * answer is "nobody is missing a grant" — would be the branch nothing exercised.
 */
export function anOrgChart(overrides: Partial<OrgChart> = {}): OrgChart {
  return { teams: [], people: [], projects: [], gaps: [], ...overrides }
}

// ---- tracker integration (004) ----

/**
 * A project's binding. Non-secret by construction — there is no field on this shape that could
 * carry a credential, which is the half of D2 the fixtures can hold.
 */
export function aTrackerBinding(overrides: Partial<TrackerBinding> = {}): TrackerBinding {
  return {
    id: 'tb1',
    projectId: 'p1',
    provider: 'JIRA_CLOUD',
    baseUrl: 'https://acme.atlassian.test',
    externalProject: 'PAY',
    config: {},
    isDefault: true,
    enabled: true,
    createdAt: AT,
    updatedAt: AT,
    lockVersion: 0,
    ...overrides,
  }
}

/**
 * One person's connection, **theirs**.
 *
 * `secretHint` and `accountId` default to null, which is the least interesting body — and, on this
 * shape, the safest default for a fixture to have: a test asserting that a screen does not leak a
 * hint must set one deliberately, or it is asserting against a body that never had one.
 */
export function aTrackerIdentity(overrides: Partial<TrackerIdentity> = {}): TrackerIdentity {
  return {
    id: 'ti1',
    provider: 'JIRA_CLOUD',
    baseUrl: 'https://acme.atlassian.test',
    authMethod: 'PAT',
    secretHint: null,
    accountId: null,
    expiresAt: null,
    lastError: null,
    lastCheckedAt: null,
    enabled: true,
    createdAt: AT,
    updatedAt: AT,
    ...overrides,
  }
}

/** A row of the D19 list — a person, and a state per bound host. Nothing else exists on it. */
export function aTrackerConnectedMember(
  overrides: Partial<TrackerConnectedMember> = {},
): TrackerConnectedMember {
  return { user: aUserRef(), hosts: [], ...overrides }
}

/**
 * An issue **as fetched this request**. Everything on it is live: the key is today's, the title and
 * status were read with one person's credential, and none of it is stored anywhere.
 */
export function aTrackerIssue(overrides: Partial<TrackerIssue> = {}): TrackerIssue {
  return {
    provider: 'JIRA_CLOUD',
    id: '10042',
    key: 'PAY-991',
    title: 'An issue',
    statusCategory: 'TODO',
    statusName: 'To Do',
    url: 'https://acme.atlassian.test/browse/PAY-991',
    bindingId: 'tb1',
    ...overrides,
  }
}

/**
 * A stored link — **no key, no title, no status** (D5/D17), which is the whole shape of the
 * decision and the reason a chip has to fetch or degrade.
 *
 * The default `url` names a key (`PAY-991`) that the default `issueId` (`10042`) does not
 * mention, deliberately: the id-only test asserts the chip renders the id and never the URL's key,
 * and a fixture whose two halves agreed could not tell the two apart.
 */
export function anIssueLink(overrides: Partial<IssueLink> = {}): IssueLink {
  return {
    id: 'il1',
    provider: 'JIRA_CLOUD',
    host: 'https://acme.atlassian.test',
    issueId: '10042',
    url: 'https://acme.atlassian.test/browse/PAY-991',
    bindingId: 'tb1',
    createdAt: AT,
    createdBy: null,
    ...overrides,
  }
}

/** What a change to the review policy would land on (005 S7). All zero, nothing named. */
export function aReviewImpact(overrides: Partial<ReviewImpact> = {}): ReviewImpact {
  return {
    casesInReview: 0,
    suitesInReview: 0,
    plansInReview: 0,
    docsInReview: 0,
    awaitingFinalApproval: 0,
    draftDocPages: 0,
    plansBlockedFromRunning: [],
    ...overrides,
  }
}

export function aDocPageSummary(overrides: Partial<DocPageSummary> = {}): DocPageSummary {
  return { id: 'dp1', key: 'DOC-1', title: 'A page', slug: 'a-page', status: 'DRAFT', updatedAt: AT, ...overrides }
}

/**
 * One search hit. `snippet` is required on the wire and nullable — a page with no blocks matched on
 * its title alone — so it defaults to a fragment rather than to null: a factory whose default was
 * the absent case would make every search test drive the one branch that renders nothing.
 */
export function aDocPageSearchHit(overrides: Partial<DocPageSearchHit> = {}): DocPageSearchHit {
  return { ...aDocPageSummary(), snippet: 'a fragment of the page', ...overrides }
}

/**
 * One row of the artifact register.
 *
 * Complete rather than minimal, like every factory here: the contract's `Artifact` has eight
 * required properties, and a double serving a subset is a component one commit away from a
 * TypeError (§195). `supersededById` is nullable and defaults to `null` — a value on an `ACTIVE`
 * row is a state the server cannot produce.
 *
 * `writeOnce` is `false` here and required on the wire (C7): a row that cannot be corrected is the
 * exception, and a factory defaulting to it would make every register test drive a frozen row.
 */
export function anArtifact(overrides: Partial<Artifact> = {}): Artifact {
  return {
    id: 'af1',
    scope: 'PROJECT',
    category: 'SignOff',
    kind: 'EXTERNAL_URL',
    title: 'An artifact',
    description: null,
    status: 'ACTIVE',
    writeOnce: false,
    supersededById: null,
    inherited: false,
    url: 'https://docs.example.test/a',
    docPageId: null,
    filename: null,
    sizeBytes: null,
    updatedBy: { id: 'u1', displayName: 'L. Lead', email: 'l@x.test', avatarUrl: null },
    updatedAt: AT,
    ...overrides,
  }
}

export function anExternalLink(overrides: Partial<ExternalLink> = {}): ExternalLink {
  return { id: 'el1', targetKind: 'EXTERNAL_URL', relationship: 'VERIFIES', ...overrides }
}

/**
 * One notification channel (§218).
 *
 * `secretHint: null` and `subscriptions: []` are the inert defaults this file's header calls for,
 * and the first of them is more than scenery: null is what the contract says for a kind with no
 * credential of its own, so a webhook fixture that wants a hint must say so. **There is no `secret`
 * property to override** — `NotificationChannel` has none, deliberately, and a fixture that grew one
 * would be a build error here rather than a credential in a response body.
 */
export function aNotificationChannel(
  overrides: Partial<NotificationChannel> = {},
): NotificationChannel {
  return {
    id: 'nc1',
    projectId: 'p1',
    kind: 'SLACK',
    name: 'A channel',
    enabled: true,
    recipients: [],
    secretHint: null,
    subscriptions: [],
    createdAt: AT,
    updatedAt: AT,
    lockVersion: 0,
    ...overrides,
  }
}

/**
 * One event a channel wants.
 *
 * `minFailures: 0` is the contract's own default and means *every occurrence*, which is the least
 * interesting body the schema admits — a test that cares about the threshold says so.
 */
export function aNotificationSubscription(
  overrides: Partial<NotificationSubscription> = {},
): NotificationSubscription {
  return {
    id: 'ns1',
    eventType: 'execution.run_completed',
    minFailures: 0,
    ...overrides,
  }
}

/**
 * One delivery attempt.
 *
 * `eventType` is a bare **string** here because the contract types it as one — a historical row may
 * name an event this instance no longer sends. Serving a live enum member where the contract says
 * string is fine and is what this default does; the reverse is what would fail.
 */
export function aNotificationDelivery(
  overrides: Partial<NotificationDelivery> = {},
): NotificationDelivery {
  return {
    id: 'nd1',
    channelId: 'nc1',
    eventId: '11111111-1111-4111-8111-111111111111',
    eventType: 'execution.run_completed',
    status: 'SENT',
    attempt: 1,
    error: null,
    createdAt: AT,
    updatedAt: AT,
    ...overrides,
  }
}

export function aCurrentUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: 'u1',
    email: 'user@acme.test',
    displayName: 'A. User',
    isSystemAdmin: false,
    // True, because an account reached through `provision-company`'s invitation has one. The
    // account-security page's "set a password" branch is the exception and passes its own (005 S9a).
    hasPassword: true,
    teams: [],
    ...overrides,
  }
}

export function anAuthResult(overrides: Partial<AuthResult> = {}): AuthResult {
  return { token: 'token', expiresAt: AT, user: aCurrentUser(), ...overrides }
}

/**
 * A configured issuer, disabled — the state registration always produces.
 *
 * **There is no secret to override**, and that is the shape rather than an omission of this
 * factory's: `IdentityProvider` carries `clientSecretSet` and no property that could hold a
 * credential, so a fixture that grew one would be a build error here rather than a secret in a
 * response body.
 */
export function anIdentityProvider(overrides: Partial<IdentityProvider> = {}): IdentityProvider {
  return {
    id: 'f2000000-0000-4000-8000-000000000001',
    key: 'acme-sso',
    kind: 'OIDC',
    displayName: 'Acme Corporate SSO',
    issuerUri: 'https://issuer.acme.test/realms/acme',
    clientId: 'tcms',
    clientSecretSet: true,
    bindDn: null,
    bindPasswordSet: false,
    startTls: false,
    userSearchBase: null,
    userSearchFilter: null,
    userIdAttribute: null,
    emailAttribute: null,
    displayNameAttribute: null,
    jitProvisioning: false,
    autoLinkVerifiedEmail: false,
    groupClaim: null,
    enabled: false,
    createdAt: AT,
    updatedAt: AT,
    ...overrides,
  }
}

/**
 * A configured directory, disabled — `anIdentityProvider`'s other kind.
 *
 * The eight LDAP columns and the eight OIDC ones are one row and one schema, so this is the same
 * factory with the halves swapped rather than a second shape. `bindPasswordSet` is a boolean here
 * for `clientSecretSet`'s reason: no read path echoes either credential back.
 */
export function aDirectoryProvider(
  overrides: Partial<IdentityProvider> = {},
): IdentityProvider {
  return anIdentityProvider({
    id: 'f3000000-0000-4000-8000-000000000001',
    key: 'acme-directory',
    kind: 'LDAP',
    displayName: 'Acme Directory',
    issuerUri: 'ldaps://dc.acme.test:636',
    clientId: null,
    clientSecretSet: false,
    bindDn: 'cn=service,dc=acme,dc=test',
    bindPasswordSet: true,
    startTls: false,
    userSearchBase: 'ou=people,dc=acme,dc=test',
    userSearchFilter: '(|(userPrincipalName={0})(mail={0}))',
    userIdAttribute: 'objectGUID',
    emailAttribute: 'mail',
    displayNameAttribute: 'displayName',
    ...overrides,
  })
}

/**
 * One sign-in route, as discovery answers it: an id, a label, and which control to render —
 * `OIDC` navigates to the issuer, `LDAP` switches the form to a directory password.
 */
export function anInvitationPreview(
  overrides: Partial<InvitationPreview> = {},
): InvitationPreview {
  return {
    email: 'invitee@acme.test',
    displayName: 'Kim Invitee',
    passwordRequired: true,
    ...overrides,
  }
}

export function aSocialProviderList(
  overrides: Partial<SocialProviderList> = {},
): SocialProviderList {
  return { providers: ['GITHUB', 'GITLAB', 'GOOGLE'], ...overrides }
}

export function aSocialIdentity(overrides: Partial<SocialIdentity> = {}): SocialIdentity {
  return {
    provider: 'GITHUB',
    linkedAt: AT,
    lastLoginAt: null,
    emailAtLink: null,
    ...overrides,
  }
}

export function aSocialIdentityList(
  overrides: Partial<SocialIdentityList> = {},
): SocialIdentityList {
  return { identities: [], ...overrides }
}

export function aSocialRedirect(overrides: Partial<SocialRedirect> = {}): SocialRedirect {
  return { url: 'https://github.com/login/oauth/authorize?client_id=x&state=y', ...overrides }
}

export function anSsoProviderChoice(
  overrides: Partial<SsoProviderChoice> = {},
): SsoProviderChoice {
  return {
    id: 'f2000000-0000-4000-8000-000000000001',
    displayName: 'Acme Corporate SSO',
    kind: 'OIDC',
    ...overrides,
  }
}

/**
 * One recorded act (§242) — **a plain session act by default**: a person, no credential, one
 * target.
 *
 * The three shapes that matter are reached by overriding rather than by three factories, because
 * the distinctions are which fields are null and a factory per shape hides exactly that. A
 * token-driven act sets `actorTokenId`/`actorTokenName` **beside** the person, never instead of
 * them; an unattended job nulls both actor ids; a vanished membership nulls `actorUserName` while
 * `actorUserId` stays.
 */
/**
 * The result of a bulk assignment — §245's preview and its commit share one shape.
 *
 * The least interesting one the contract admits: a preview of nothing. `bulkOperationId` is null,
 * which is what a dry run and a commit that moved nothing both answer, and a test about either
 * overrides away from it.
 */
export function aBulkAssignResult(
  overrides: Partial<ExecutionBulkResult> = {},
): ExecutionBulkResult {
  return {
    dryRun: true,
    total: 0,
    changed: [],
    unchanged: [],
    skipped: [],
    bulkOperationId: null,
    ...overrides,
  }
}

/** One row of a preview — where a case lands, and who it lands on. */
export function anAssignmentChange(
  overrides: Partial<ExecutionAssignmentChange> = {},
): ExecutionAssignmentChange {
  return {
    executionId: 'ex1',
    caseKey: 'TC-1',
    assigneeId: 'user-2',
    assigneeName: 'A. Ana',
    ...overrides,
  }
}

export function anAuditEvent(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: 'ae-1',
    projectId: 'p1',
    actorUserId: 'user-1',
    actorUserName: 'A. Actor',
    actorTokenId: null,
    actorTokenName: null,
    action: 'case.updated',
    targetType: 'test_case',
    targetIds: ['c1'],
    changes: null,
    bulkOperationId: null,
    occurredAt: AT,
    ...overrides,
  }
}

/**
 * A run schedule (§247) — complete and typed to the contract, never cast.
 *
 * Every nullable field is spelled out rather than omitted, for `projectSettings`' reason one file
 * up: an omitted key reads as `undefined`, which is falsy and indistinguishable from a decision
 * until a test wants the other branch. Here that matters most for `lastFireError` and `nextFireAt`
 * — the two fields whose *absence* is what the screen renders differently.
 */
export function aRunSchedule(overrides: Partial<RunSchedule> = {}): RunSchedule {
  return {
    id: 'rs-1',
    projectId: 'p1',
    name: 'Nightly smoke',
    cronExpression: '0 0 3 * * MON-FRI',
    zoneId: 'Europe/Sofia',
    scope: { planId: null, caseIds: [], suiteIds: [], tags: ['smoke'] },
    configurationId: null,
    assigneeId: null,
    assigneeName: null,
    enabled: true,
    nextFireAt: AT,
    lastFiredAt: null,
    lastRunId: null,
    lastRunKey: null,
    lastFireError: null,
    createdAt: AT,
    createdBy: 'user-1',
    createdByName: 'O. Owner',
    ...overrides,
  }
}

/**
 * A `.feature` import plan (§255) — **the same shape a `PREVIEW` and an `APPLY` answer**.
 *
 * The default is the least interesting body the contract admits: `PREVIEW`, not applied, nothing to
 * do. A test says what it is about by overriding `scenarios` and `totals`, which are the two the
 * screen reads together — `totals` is the server's tally and is deliberately NOT derived from
 * `scenarios` here, because a fixture that computed it would make the screen's use of the server's
 * count untestable, and re-tallying on the client is exactly what `ImportTotals` exists to stop.
 */
export function anImportPlan(overrides: Partial<FeatureImportPlan> = {}): FeatureImportPlan {
  return {
    mode: 'PREVIEW',
    applied: false,
    scenarios: [],
    suites: [],
    conditions: [],
    orphans: [],
    totals: { create: 0, update: 0, skip: 0, refuse: 0 },
    ...overrides,
  }
}

/**
 * One row of that plan.
 *
 * `reason` defaults to null — the action that needs no explanation — so a test that sets one is
 * naming a sentence from `api-surface.md` §5.4 on purpose. Every other field is present and
 * nullable exactly as the contract has it, so a row for a whole-file refusal is written by nulling
 * `line`, `scenario` and `automationKey` rather than by leaving them out.
 */
export function anImportRow(overrides: Partial<ImportRow> = {}): ImportRow {
  return {
    path: 'features/orders.feature',
    line: 12,
    feature: 'Orders',
    scenario: 'Cancel an order',
    automationKey: 'Orders#Cancel an order',
    action: 'CREATE',
    reason: null,
    caseKey: null,
    preconditions: [],
    ...overrides,
  }
}

/**
 * The import shapes (014 M4). Inert like every factory here, and two of them deliberately so.
 *
 * `anImportPreview` has **no blockers and no statuses**: a preview that could not proceed is the
 * subject of exactly one kind of test, and a test that is about the mapping step should not inherit
 * a collision it never asserted. `anImportRun` is `COMPLETED` with every count at zero, which is the
 * least interesting run the contract admits — a test about the failure path names `FAILED` and its
 * `failureReason` on purpose.
 */
export function aSourceProject(overrides: Partial<SourceProject> = {}): SourceProject {
  return { key: 'PROJ', name: 'Storefront', issueCount: 0, ...overrides }
}

export function aPreviewTypeRow(overrides: Partial<PreviewTypeRow> = {}): PreviewTypeRow {
  return {
    sourceTypeName: 'Story',
    targetTypeId: 'it1',
    targetTypeName: 'Story',
    targetLevel: 'STORY',
    countsTowardCompletion: true,
    count: 0,
    ...overrides,
  }
}

/**
 * One source status.
 *
 * `matched` false and `suggestedStatusId` null is the **unmapped** row — the state the mapping step
 * exists to refuse to skip past — so a test asking for "a status" gets the interesting one for free
 * and a test about a pre-filled row says `matched: true` out loud.
 */
export function aPreviewStatusRow(overrides: Partial<PreviewStatusRow> = {}): PreviewStatusRow {
  return {
    sourceStatusName: 'To Do',
    count: 0,
    matched: false,
    suggestedStatusId: null,
    ...overrides,
  }
}

export function aDroppedField(overrides: Partial<DroppedField> = {}): DroppedField {
  return {
    name: 'Story Points',
    fieldType: 'number',
    valueCount: 0,
    reason: 'estimates here are hours; points have no conversion',
    ...overrides,
  }
}

export function anImportBlocker(overrides: Partial<ImportBlocker> = {}): ImportBlocker {
  return { kind: 'KEY_COLLISION', detail: 'Some keys already exist.', keys: [], ...overrides }
}

export function anImportPreview(overrides: Partial<ImportPreview> = {}): ImportPreview {
  return {
    projectId: 'p1',
    sourceProjectKey: 'PROJ',
    sourceKeyPrefix: 'PROJ',
    projectKeyPrefix: 'PRJ',
    totals: {
      issues: 0,
      labels: 0,
      comments: 0,
      historyEntries: 0,
      fixVersions: 0,
      sprintValues: 0,
    },
    types: [],
    statuses: [],
    droppedFields: [],
    blockers: [],
    ...overrides,
  }
}

export function anImportRun(overrides: Partial<ImportRun> = {}): ImportRun {
  return {
    id: 'ir1',
    projectId: 'p1',
    provider: 'JIRA_CLOUD',
    siteUrl: 'https://northwind.atlassian.net',
    accountEmail: 'ops@northwind.test',
    sourceProjectKey: 'PROJ',
    status: 'COMPLETED',
    startedAt: AT,
    finishedAt: AT,
    // Nullable, and null by default: a run nobody is named for is the state the report has to
    // survive, and a screen that read `startedBy.displayName` would only fail on it.
    startedBy: null,
    issuesImported: 0,
    releasesCreated: 0,
    commentsImported: 0,
    labelsCreated: 0,
    droppedValueCount: 0,
    labelPerSprint: false,
    backlogOrderPreserved: true,
    failureReason: null,
    droppedFields: [],
    statusMapping: [],
    people: [],
    ...overrides,
  }
}

export function anImportRunSummary(overrides: Partial<ImportRunSummary> = {}): ImportRunSummary {
  return {
    id: 'ir1',
    provider: 'JIRA_CLOUD',
    siteUrl: 'https://northwind.atlassian.net',
    sourceProjectKey: 'PROJ',
    status: 'COMPLETED',
    startedAt: AT,
    issuesImported: 0,
    statusesMapped: 0,
    labelsCreated: 0,
    droppedFieldCount: 0,
    ...overrides,
  }
}
