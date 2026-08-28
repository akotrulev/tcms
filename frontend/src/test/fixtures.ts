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
  NotificationChannel,
  NotificationDelivery,
  NotificationSubscription,
  PlanStatistics,
  Project,
  ProjectSettings,
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

export function anOrgChart(overrides: Partial<OrgChart> = {}): OrgChart {
  return { teams: [], people: [], projects: [], ...overrides }
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
