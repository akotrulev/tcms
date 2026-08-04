/**
 * Convenience aliases over the generated OpenAPI schema.
 *
 * Everything here resolves back to docs/specs/openapi.yaml — if a shape looks
 * wrong, fix the spec and re-run `npm run gen:api`, don't hand-edit schema.d.ts.
 */
import type { components, operations } from './schema';

type S = components['schemas'];
/**
 * Operations, for the handful of shapes that exist only on a request body and never as a named
 * schema — `generateReport`'s `format` enum is the first. Aliasing through here keeps them derived
 * from the contract rather than retyped beside it.
 */
type Op = operations;

// auth & identity
export type AuthResult = S['AuthResult'];
export type CurrentUser = S['CurrentUser'];
export type InvitationPreview = S['InvitationPreview'];
export type UserRef = S['UserRef'];
export type User = S['User'];
export type UserInvitation = S['UserInvitation'];
export type UserInvitationInput = S['UserInvitationInput'];
export type Team = S['Team'];
export type TeamInput = S['TeamInput'];
export type TeamMemberInput = S['TeamMemberInput'];
export type Role = S['Role'];
export type RoleAssignment = S['RoleAssignment'];
export type RoleAssignmentInput = S['RoleAssignmentInput'];
export type PermissionKey = S['PermissionKey'];
export type EffectivePermissions = S['EffectivePermissions'];
export type RoleName = Role['name'];
export type RoleScope = Role['scope'];

// project
export type Project = S['Project'];
export type ProjectInput = S['ProjectInput'];
export type ClassificationDimension = S['ClassificationDimension'];
export type ClassificationValue = S['ClassificationValue'];
export type ClassificationValueInput = S['ClassificationValueInput'];
export type CaseStatus = S['CaseStatus'];

// repository
export type Suite = S['Suite'];
export type TestCase = S['TestCase'];
export type TestCaseSummary = S['TestCaseSummary'];
export type TestStep = S['TestStep'];
export type Condition = S['Condition'];
export type CaseConditionRef = S['CaseConditionRef'];
export type SharedStep = S['SharedStep'];
/**
 * One entry in the project's derived step corpus (§94).
 *
 * Carries no id, and that absence is the contract: a suggestion is text to copy into a step, not
 * an entity to reference. See `/projects/{projectId}/step-suggestions` in `openapi.yaml`.
 */
export type StepSuggestion = S['StepSuggestion'];
export type SharedStepInput = S['SharedStepInput'];
export type BulkOperation = S['BulkOperation'];
export type BulkPayload = S['BulkPayload'];
export type BulkResult = S['BulkResult'];
export type EntityVersion = S['EntityVersion'];
export type VersionMarker = S['VersionMarker'];
export type Facet = S['Facet'];

// review (plan §6g) — one record over all three reviewable artifacts, so it sits between the
// repository and execution blocks rather than inside either.
export type Review = S['Review'];
export type ReviewParticipation = S['ReviewParticipation'];
export type ReviewParticipationInput = S['ReviewParticipationInput'];
export type ReviewAssignee = S['ReviewAssignee'];
export type TeamRef = S['TeamRef'];
export type ReviewComment = S['ReviewComment'];
export type ReviewCommentInput = S['ReviewCommentInput'];
export type ReviewSubjectType = S['ReviewSubjectType'];
export type ReviewState = S['ReviewState'];
// The project's own §6g policy. Beside the review types because every one of its fields is about
// them, and because the panel reads the two together on every artifact screen.
export type ProjectSettings = S['ProjectSettings'];
export type ProjectSettingsUpdate = S['ProjectSettingsUpdate'];
export type EditAfterApproval = S['EditAfterApproval'];
/**
 * What a participation says. Read off `ReviewParticipation` rather than the *input* schema, for the
 * reason `CriterionMetric` gives one module over and in the opposite direction: the input declares
 * only the two advisory verdicts a client may send, while a response carries all four — the
 * `APPROVED` and `REJECTED` rows the ladder writes. A label map keyed off the input would be a
 * compile error waiting for the first approved review to render.
 */
export type ReviewVerdict = NonNullable<ReviewParticipation['verdict']>;

// execution
export type TestPlan = S['TestPlan'];
export type TestPlanSummary = S['TestPlanSummary'];
export type TestRun = S['TestRun'];
export type TestRunDetail = S['TestRunDetail'];
/**
 * The run lifecycle. Read off `TestRun` rather than a named component because the contract
 * declares it inline — and named here so `lib/labels.ts` can `satisfies Record<RunStatus, string>`
 * over it, which is what turns a missing or invented value into a compile error.
 */
export type RunStatus = NonNullable<TestRun['status']>;
/**
 * The plan lifecycle, read off `TestPlanSummary` for `RunStatus`' reason — and named in §73 after
 * finding `PlanListPage`'s colour map had drifted the same way the run list's had: typed
 * `Record<string, string>`, carrying an `ARCHIVED` that is not a plan status and missing both
 * `IN_REVIEW` and `APPROVED`, which that slice made reachable for the first time.
 */
export type PlanStatus = NonNullable<TestPlanSummary['status']>;
export type TestExecution = S['TestExecution'];
export type TestExecutionSummary = S['TestExecutionSummary'];
export type StepResult = S['StepResult'];
export type Evidence = S['Evidence'];
export type Configuration = S['Configuration'];
export type Criterion = S['Criterion'];
/**
 * The metrics a criterion may be gated on, read off `CriterionInput` — the *input* schema and not
 * `Criterion`, because only the input declares the enum. The response side has to keep accepting
 * whatever is already stored, including rows seeded before the vocabulary was closed.
 *
 * Named here so `lib/labels.ts` can `satisfies Record<CriterionMetric, string>` over it: a metric
 * added server-side then fails the frontend build until the editor offers it, instead of becoming
 * an option nobody can choose.
 */
export type CriterionMetric = NonNullable<S['CriterionInput']['metric']>;
export type ResultCounts = S['ResultCounts'];
export type DefectLink = S['DefectLink'];

// history & reporting
export type CaseHistorySummary = S['CaseHistorySummary'];
export type ExecutionHistoryEntry = S['ExecutionHistoryEntry'];
export type SuiteRunSummary = S['SuiteRunSummary'];
export type SuiteHistoryResponse = S['SuiteHistoryResponse'];
export type RepositoryDashboard = S['RepositoryDashboard'];
export type DashboardTile = S['DashboardTile'];
export type DashboardBreakdown = S['DashboardBreakdown'];
export type PlanStatistics = S['PlanStatistics'];
export type ReportDefinition = S['ReportDefinition'];
export type ReportJob = S['ReportJob'];
export type ReportJobStatus = S['ReportJob']['status'];
/**
 * The formats a person may **ask** for — read off the generate request body, not off `ReportJob`.
 *
 * `ReportJob.format` is a bare `string` on purpose: a job row echoes back whatever was asked for,
 * including a format a later release stops offering, and narrowing the response side would make an
 * old job unreadable. The *request* side is the closed set, so this is where the union lives — and
 * it is what `lib/labels.ts` satisfies over, so a fourth format added to the contract fails the
 * frontend build until the picker offers it.
 *
 * It is deliberately **not** "the formats this server can render". That is a property of the
 * deployment (`openapi.yaml`, verbatim: *"the enum is not narrowed to what is installed"*), the
 * client cannot observe it, and a hard-coded copy here would be a second definition of somebody
 * else's configuration — right until the day PDF is installed and the SPA still refuses to offer
 * it. What the server cannot render, it refuses at request time with a sentence naming what it can.
 */
export type ReportFormat = NonNullable<
  Op['generateReport']['requestBody']
>['content']['application/json']['format'];

// documentation & artifacts
export type DocPage = S['DocPage'];
export type DocPageSummary = S['DocPageSummary'];
export type DocBlock = S['DocBlock'];
export type DocPageInput = S['DocPageInput'];
export type DocBlockInput = S['DocBlockInput'];
export type DocPageStatus = S['DocPageSummary']['status'];
export type DocBlockType = S['DocBlock']['blockType'];
export type BlockLink = S['BlockLink'];
export type BlockLinkInput = S['BlockLinkInput'];
/** What a page documents, when it documents anything. Null on nearly every page. */
export type DocSubjectType = NonNullable<S['DocPageSummary']['subjectType']>;
export type DocLink = S['DocLink'];
export type Artifact = S['Artifact'];
export type ArtifactInput = S['ArtifactInput'];

// work
export type WorkItem = S['WorkItem'];
export type WorkItemStatus = S['WorkItemStatus'];
export type WorkItemInput = S['WorkItemInput'];

// enums
export type LifecycleStatus = S['LifecycleStatus'];
export type AutomationStatus = S['AutomationStatus'];
export type Priority = S['Priority'];
export type StepFormat = S['StepFormat'];
export type StatusKind = S['StatusKind'];
export type HealthSignal = S['HealthSignal'];
export type AggregationPolicy = S['AggregationPolicy'];

export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  facets?: Facet[];
}

/** Case list filter state. Mirrored into the URL so views are shareable (plan §6o/§6q). */
export interface CaseFilters {
  q?: string;
  suiteId?: string;
  featureId?: string;
  testTypeId?: string;
  testLevelId?: string;
  /** One owner. Exists so the dashboard's by-owner bars link to the cases they counted (§6o). */
  ownerId?: string;
  lifecycleStatus?: LifecycleStatus[];
  automationStatus?: AutomationStatus[];
  priority?: Priority[];
  healthSignal?: HealthSignal[];
  releaseVersion?: string;
  page?: number;
  size?: number;
}
