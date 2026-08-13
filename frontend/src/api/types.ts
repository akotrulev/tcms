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
export type ApiToken = S['ApiToken'];
export type ApiTokenInput = S['ApiTokenInput'];
/**
 * The issue response — **the only shape in this contract that carries a credential**.
 *
 * Kept as its own alias rather than reached through `ApiToken` because that is the contract's own
 * decision: a nullable `secret` on the read type would leave every list one forgotten null away
 * from serving a plaintext token. Nothing that renders a list may name this type.
 */
export type IssuedApiToken = S['IssuedApiToken'];

// project
export type Project = S['Project'];
export type ProjectInput = S['ProjectInput'];
export type ClassificationDimension = S['ClassificationDimension'];
export type ClassificationValue = S['ClassificationValue'];
export type ClassificationValueInput = S['ClassificationValueInput'];
export type CaseStatus = S['CaseStatus'];

// repository
export type Suite = S['Suite'];
/**
 * The body of both suite writes.
 *
 * **Generated, as of §186, where it was hand-written in `api/queries/suites.ts`.** It had been a
 * structural copy of this schema since Phase 2 and was correct for as long as nobody added a
 * field — then §186 added two, and the copy silently kept typing a payload the endpoint accepts
 * as a payload it does not, which is §52's lesson on the write side: a hand-written type is
 * generated from nothing, so there is no second opinion for the compiler to compare it against.
 */
export type SuiteInput = S['SuiteInput'];
export type TestCase = S['TestCase'];
export type TestCaseSummary = S['TestCaseSummary'];
/**
 * A case's flake evidence and quarantine decision (§121). `TestCase['flake']` is this **or null**,
 * and the null is a third state — see `lib/flake.ts`, which is the only thing that should read the
 * shape directly.
 */
export type CaseFlake = S['CaseFlake'];
/**
 * A case's buffered autosave draft (§128).
 *
 * `content` is a `TestCaseInput` that has been through **no validation at all** — that is what a
 * keystroke buffer means — so it may carry a blank title or a step referencing a retired shared
 * step. Restoring it into the form is safe; the refusal, if there is one, comes on Save.
 */
export type CaseDraft = S['CaseDraft'];
/**
 * What a clone of a case is allowed to say (§129).
 *
 * Two fields, and the shortness is the design: **a clone is a create**, so everything else the new
 * case gets is read off the source and run through the create path's own validation, its own key
 * allocator and its own version writer. A wider input would be a second definition of a legal case
 * beside `TestCaseInput`.
 */
export type TestCaseCloneInput = S['TestCaseCloneInput'];
export type TestStep = S['TestStep'];
export type Condition = S['Condition'];
export type CaseConditionRef = S['CaseConditionRef'];
export type ConditionInput = S['ConditionInput'];
export type CaseConditionInput = S['CaseConditionInput'];
export type SharedStep = S['SharedStep'];
/**
 * One entry in the project's derived step corpus (§94).
 *
 * Carries no id, and that absence is the contract: a suggestion is text to copy into a step, not
 * an entity to reference. See `/projects/{projectId}/step-suggestions` in `openapi.yaml`.
 */
export type StepSuggestion = S['StepSuggestion'];
export type SharedStepInput = S['SharedStepInput'];
/**
 * One tag in use in the project, with the number of live cases carrying it (§97).
 *
 * Derived from the cases themselves, so it is a **picker's list and never a validator's**: a tag is
 * free text and a client may send one this list does not contain — that is how the first case to
 * carry a new tag gets it. Narrowing a write to this list would close a vocabulary the product
 * deliberately leaves open.
 */
export type TagUsage = S['TagUsage'];
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
/**
 * A sub-feature subdivision of a plan's cases (§97) — "Create an object", "Edit", "Navigation".
 *
 * **A live selector, not a snapshot.** It holds references — case ids, suite ids, tags — resolved
 * on every read, so a section's contents move when a referenced suite gains a case or a case is
 * tagged, with nothing written to the plan. TestNG's `<test>` block is the shape.
 *
 * It is plan-local: no key, no lifecycle, no versions, no existence outside its plan. A reader who
 * takes it for a suite will expect it in the tree, where it will never be.
 */
export type PlanSection = S['PlanSection'];
export type PlanSectionInput = S['PlanSectionInput'];
export type TestPlanSummary = S['TestPlanSummary'];
/** The name, and nothing else — a plan clone copies its source and takes no other instruction. */
export type TestPlanCloneInput = S['TestPlanCloneInput'];
export type TestRun = S['TestRun'];
export type TestRunDetail = S['TestRunDetail'];
export type TestRunInput = S['TestRunInput'];
/**
 * A run clone's four overridable fields plus §6g's override (§129).
 *
 * Wider than its two siblings for one reason: *"re-run this regression cycle against the new
 * build"* is what a run clone is **for**, so a clone that could not change the build number would
 * make the caller edit the run immediately afterwards. `assigneeId` does not fall back to the
 * source — a run has no assignee, the column is on `test_execution` — and `overrideApprovalGate` is
 * never inherited, because nothing stores it and a carried-forward override is a permanent
 * exemption no screen shows.
 */
export type TestRunCloneInput = S['TestRunCloneInput'];
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
/**
 * One file attached to **authored content** — a case's definition, a documentation page, or an
 * artifact's bytes. One schema for all three owners (the contract's own decision), which is what
 * lets `components/AttachmentList.tsx` be one component instead of three.
 *
 * **Deliberately not `Evidence`, and the two must not be merged.** Evidence carries a `caption` and
 * is a record of something that happened; the delete gate reads authorship for it (`evidence:delete`
 * has an `OWN` arm) and deliberately does **not** here — a case attachment is part of the case
 * definition that `case:edit` already owns wholesale.
 */
export type Attachment = S['Attachment'];
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

/**
 * CI ingestion.
 *
 * **`IngestionJobSummary` and `IngestionJob` are two types on purpose** and neither is assignable
 * to the other: the summary the history list serves has no `unmatched` array at all, because the
 * names are not a column and reading every job's stored payload to render one screen is what the
 * split exists to avoid. A component that wants the names must have loaded the job.
 */
export type IngestionJobSummary = S['IngestionJobSummary'];
export type IngestionJob = S['IngestionJob'];
export type UnmatchedTest = S['UnmatchedTest'];
export type IngestedOutcome = S['IngestedOutcome'];
/**
 * Which CI dialect a report was — **named `IngestionFormat` here, though the schema is
 * `ReportFormat`.**
 *
 * The contract has two unrelated things called a report format: this one (JUnit, TestNG, Allure,
 * Cucumber — what a runner emitted) and the *export* format of a generated report (Markdown, HTML,
 * PDF), which owns the `ReportFormat` name in this file already and is what `lib/labels.ts`
 * satisfies over. Aliasing the schema name would have been a duplicate identifier at best and, had
 * the two lived in different files, a picker offering PDF to a CI parser at worst. The divergence
 * is deliberate and is recorded on both sides.
 */
export type IngestionFormat = S['ReportFormat'];
export type IngestionStatus = S['IngestionStatus'];
export type AutomationMapping = S['AutomationMapping'];
export type AutomationMappingInput = S['AutomationMappingInput'];

/**
 * Generated cases waiting for a person (§138, plan §6w.6).
 *
 * **`AiProposalSummary` and `AiProposal` are two types on purpose**, `IngestionJobSummary`'s split
 * for its reason: the queue's rows carry no payload and no grounding, because fifty rows would
 * otherwise be fifty full test cases and fifty critic reports for a screen on which the reader is
 * choosing which one to open.
 *
 * **`verdict` and `verdictSource` are two fields and every render must carry both.** The first is
 * what the proposer *claimed*; the second is who checked, and it is never anything TCMS verified
 * itself — §6w.1a. A chip showing "Grounded" with no provenance shows a claim as a finding, which
 * is the inflation the split exists to prevent.
 */
export type AiProposalSummary = S['AiProposalSummary'];
export type AiProposal = S['AiProposal'];
export type ProposalStatus = S['ProposalStatus'];
export type ProposalVerdict = S['ProposalVerdict'];
export type VerdictSource = S['VerdictSource'];

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
/** What a link claims. One vocabulary, shared by all three link shapes. */
export type LinkRelationship = S['DocLink']['relationship'];
/** A case's link to code or to a URL — the body of the create (§169). */
export type ExternalLinkInput = S['ExternalLinkInput'];
/** What that create answers with. NOT `DocLink`: the case end is served off the mirror. */
export type ExternalLink = S['ExternalLink'];
export type Artifact = S['Artifact'];
export type ArtifactInput = S['ArtifactInput'];
/**
 * The register fields that travel **beside an uploaded file** — the `artifact` part of
 * `POST /projects/{projectId}/artifacts/files`.
 *
 * It carries no `kind`, and that absence is the contract's design rather than an omission: the kind
 * is `FILE` by construction, so a client generating types from this spec cannot express a payload
 * that disagrees with the endpoint it was sent to. Everything `ArtifactInput` and this share
 * (scope, planId, category, title, description) is validated by one server-side path.
 */
export type ArtifactFileInput = S['ArtifactFileInput'];

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
/**
 * How loudly a signal reads — **the server's judgement, and never reconstructed here.**
 *
 * A signal arrives with its severity beside it (`RaisedSignal`) as of §179, which is what deleted
 * `StatusChip`'s hand-kept `SEVERE_SIGNALS` list. That list had drifted about two of the ten members,
 * so one case rendered red on a dashboard tile and amber on its own chip. Anything that needs to know
 * how bad a signal is reads this value; a second table keyed on `HealthSignal` is the defect.
 */
export type HealthSignalSeverity = S['HealthSignalSeverity'];
export type RaisedSignal = S['RaisedSignal'];
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
  /**
   * Tags the case must carry — **all of them** (§96). The parameter repeats, and the server ANDs,
   * which is deliberately the opposite of a suite's tag rules: a second chip here narrows a list
   * somebody is searching, a second rule there widens a collection somebody is assembling.
   */
  tag?: string[];
  /**
   * Only cases filed in **no** suite. The grid's one way of reaching a case that appears under no
   * node of the tree. Sent only when true — the server ignores it beside `suiteId`, so the pair is
   * harmless, but a `false` on the URL is noise in a link people paste to each other.
   */
  unfiled?: boolean;
  releaseVersion?: string;
  page?: number;
  size?: number;
}
