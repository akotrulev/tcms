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
/**
 * Setting or changing your own password (005 S9a) — told apart by `CurrentUser.hasPassword`, with
 * `currentPassword` null only on an account that has none.
 */
export type PasswordChangeInput = S['PasswordChangeInput'];
export type CurrentUser = S['CurrentUser'];
export type InvitationPreview = S['InvitationPreview'];
export type UserRef = S['UserRef'];
export type User = S['User'];
export type UserInvitation = S['UserInvitation'];
export type UserInvitationInput = S['UserInvitationInput'];
export type Team = S['Team'];
export type TeamInput = S['TeamInput'];
export type TeamMemberInput = S['TeamMemberInput'];
// The org chart's four shapes. New schemas rather than a widened `User`/`Team`: the matrix needs
// team NODES where `User.teams` is `string[]`, and widening a shipped shape for a screen that can
// have its own is the breaking change the contract refused.
export type OrgChart = S['OrgChart'];
export type OrgChartPerson = S['OrgChartPerson'];
export type OrgChartProject = S['OrgChartProject'];

/** Who can grant a permission — what a refusal prints. Display names only, at most three. */
export type GranteeList = S['GranteeList'];
export type OrgChartAssignment = S['OrgChartAssignment'];
/**
 * A permission **no active member of a project holds** (005 N6/S6) — the fourth gap, and the only
 * one the client cannot derive from the document it is already reading.
 *
 * Its `permission` is an unconstrained string rather than a `PermissionKey`, deliberately: the
 * server's catalogue is the authority, so a key this build cannot label renders as its own text.
 */
export type OrgChartGap = S['OrgChartGap'];
export type AssignmentVia = OrgChartAssignment['via'];
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
/**
 * A connected AI assistant — **an `Agent`-role `api_token` the caller owns** (015 D26).
 *
 * There is no agent principal and no table of its own, which is why this is an alias over a view
 * of `api_token` rather than a shape of its own: what a person calls "my assistant" is the
 * credential their tool presents, and every act it performs is already attributed to them through
 * it. A revoked one **stays in the list** with `revokedAt` set, for {@link ApiToken}'s reason —
 * its acts are in each issue's history and a vanished row would read as though it never existed.
 */
export type Assistant = S['Assistant'];
export type AssistantInput = S['AssistantInput'];
/**
 * The connect response — **the second shape in this contract that carries a credential**, and
 * carried for exactly one render. {@link IssuedApiToken}'s note applies verbatim: nothing that
 * renders a list may name this type, and nothing may write it to the query cache.
 */
export type IssuedAssistant = S['IssuedAssistant'];
/**
 * One recorded act (§242).
 *
 * **Both actor halves are independently nullable and are normally set together** — a token-driven
 * act names the person answerable for the credential *and* the credential. Rendering one and
 * hiding the other loses half of who did it, which is what this screen exists to show.
 *
 * `action` and `targetType` are bare `string`s on the wire on purpose: the set grows with every
 * slice that records something, so pinning it as an enum would make adding an act a contract
 * change. That is why the labels go through `labelForKey` rather than a `satisfies Record<…>` map.
 */
export type AuditEvent = S['AuditEvent'];

// project
export type Project = S['Project'];
export type ProjectInput = S['ProjectInput'];
export type ClassificationDimension = S['ClassificationDimension'];
export type ClassificationValue = S['ClassificationValue'];
export type ClassificationValueInput = S['ClassificationValueInput'];
export type CaseStatus = S['CaseStatus'];
/**
 * One repository a project spans, and how it is built — **reference data (015 D16)**.
 *
 * This product never holds a repository, clones one or runs a build. The row is what a new joiner,
 * or somebody's assistant, is told to check out, and a project with none is not degraded in any
 * way — which is why nothing else in the product reads it.
 *
 * `primary` is **computed from `position`, not stored**: the lowest position is the primary one, so
 * there is no flag two rows can both claim.
 */
export type ProjectRepository = S['ProjectRepository'];
export type ProjectRepositoryInput = S['ProjectRepositoryInput'];

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
export type CaseSearchHit = S['CaseSearchHit'];
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
/**
 * The rows and the Background a pasted scenario would become (§255) — **a suggestion, not a case**.
 *
 * `steps` is literally `TestStepInput[]` with every `id` null, so it can be handed to the case
 * write unchanged; `background` is one entry **per Background step** (amendment AM1), each already
 * shaped as the `ConditionInput` it would become. Nothing on the server is created by asking.
 */
export type GherkinParseResult = S['GherkinParseResult'];
export type ParsedBackground = S['ParsedBackground'];
/**
 * Where an imported case came from — **provenance, never a locator**. The `.feature` file lives in
 * somebody's automation repository and this instance has never held it, so nothing resolves this
 * to content: it is what lets a reader answer "why does this case say that".
 */
export type ImportedFrom = S['ImportedFrom'];

/**
 * What an import of `.feature` files would do, or what it just did (§255).
 *
 * **The same shape in both modes**, which is the property the import screen is built on: `PREVIEW`
 * plans and writes nothing, `APPLY` recomputes the identical plan inside its transaction and
 * executes it, and on a clean run `applied` and the rows' `caseKey`s are the only fields that
 * move. One component renders both, so a preview cannot describe an outcome the apply does not
 * produce — there is no second decision procedure for the two to disagree about.
 */
export type FeatureImportPlan = S['FeatureImportPlan'];
export type FeatureImportRequest = S['FeatureImportRequest'];
export type FeatureFile = S['FeatureFile'];
/**
 * One scenario's verdict.
 *
 * `reason` is a **fixed sentence from the server**, not a client-side rendering of one: the list is
 * written out in `openapi.yaml` on this property and clients may match on the strings. It is an
 * outcome rather than an error, so it is shown verbatim and never routed through
 * `lib/describeError.ts`, whose whole job is the opposite — turning a failure into copy of ours.
 *
 * `preconditions` is already ordered and already resolved by the server: the import-owned titles in
 * `Background:` order, then the case's human-attached ones. It is per ROW; `FeatureImportPlan.conditions`
 * is per REQUEST, and re-deriving one from the other is how a row grows conditions from a file it
 * has nothing to do with.
 */
export type ImportRow = S['ImportRow'];
export type ImportAction = S['ImportRow']['action'];
export type SuiteAction = S['SuiteAction'];
export type SuiteActionKind = S['SuiteAction']['action'];
export type ConditionAction = S['ConditionAction'];
export type ConditionActionKind = S['ConditionAction']['action'];
export type ImportOrphan = S['Orphan'];
export type ImportTotals = S['ImportTotals'];
export type OnExisting = S['FeatureImportRequest']['onExisting'];
export type Condition = S['Condition'];
export type CaseConditionRef = S['CaseConditionRef'];
export type ConditionInput = S['ConditionInput'];
export type CaseConditionInput = S['CaseConditionInput'];
/**
 * One entry in the project's derived step corpus (§94).
 *
 * Carries no id, and that absence is the contract: a suggestion is text to copy into a step, not
 * an entity to reference. See `/projects/{projectId}/step-suggestions` in `openapi.yaml`.
 */
export type StepSuggestion = S['StepSuggestion'];
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
 * What changing the review policy would land on (005 N7/S7) — the blast radius beside the `PUT`.
 *
 * Costs `project:settings`, the **write** key, unlike the settings read itself: the counts are a
 * fact about the team's backlog rather than about how the project behaves, and the only reader
 * with a reason for them is the one about to change the policy.
 */
export type ReviewImpact = S['ReviewImpact'];
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
 * A standing request for a run — what to ask for and when (§247, `V68`).
 *
 * `scope` is the SAME `RunScopeRequest` a run records, not a second encoding of it: one document,
 * one writer on the server and one reader, which is what makes "why is this case in this run"
 * answerable for a run nobody asked for.
 */
export type RunSchedule = S['RunSchedule'];
export type RunScheduleInput = S['RunScheduleInput'];
/**
 * The run lifecycle. Read off `TestRun` rather than a named component because the contract
 * declares it inline — and named here so `lib/labels.ts` can `satisfies Record<RunStatus, string>`
 * over it, which is what turns a missing or invented value into a compile error.
 */
export type RunStatus = NonNullable<TestRun['status']>;
/**
 * Which **door** a run came through, never who asked for it — `MANUAL`, `INGESTED`, or `SCHEDULED`
 * since §247.
 *
 * Read off `TestRun` for `RunStatus`' reason (the contract declares it inline) and named here for
 * the same one: `runSourceLabels` is `satisfies Record<RunSource, string>` over it, so the day a
 * fourth door is added the label map is a **compile error** rather than a screen quietly saying the
 * wrong word. The ternary this replaced would have labelled every scheduled run "manual" and
 * typechecked while doing it.
 */
export type RunSource = NonNullable<TestRun['source']>;
/**
 * The plan lifecycle, read off `TestPlanSummary` for `RunStatus`' reason — and named in §73 after
 * finding `PlanListPage`'s colour map had drifted the same way the run list's had: typed
 * `Record<string, string>`, carrying an `ARCHIVED` that is not a plan status and missing both
 * `IN_REVIEW` and `APPROVED`, which that slice made reachable for the first time.
 */
export type PlanStatus = NonNullable<TestPlanSummary['status']>;
export type TestExecution = S['TestExecution'];
/**
 * The body of `POST …/runs/{runId}/executions/bulk` (§245) — one action over a selection.
 *
 * `ASSIGN` puts every named row on one person, or on nobody with a null `assigneeId`;
 * `DISTRIBUTE` round-robins them across a list. There is no `UNASSIGN` and no `Idempotency-Key`:
 * the single-row `PUT` already spells "nobody" as a null assignee, and nothing is created here for
 * a replay to be answered with — see `useBulkAssignExecutions`.
 */
export type ExecutionBulkOperation = S['ExecutionBulkOperation'];
export type ExecutionBulkPayload = S['ExecutionBulkPayload'];
export type ExecutionBulkResult = S['ExecutionBulkResult'];
/**
 * One row of a preview — what moves, and **where to**.
 *
 * Named so the dialog's per-person tally can be typed. The case result reports `changed` as bare
 * keys, which says *that* something moved and not where; for a distribute, where is the entire
 * question the preview exists to answer.
 */
export type ExecutionAssignmentChange = ExecutionBulkResult['changed'][number];
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
 * deployment (`openapi.yaml`, verbatim: *"the enum is not narrowed to what is installed"*), and a
 * hard-coded copy here would be a second definition of somebody else's configuration — right until
 * the day PDF is installed and the SPA still refuses to offer it. ~~The client cannot observe
 * it.~~ → **it can, since B5**: `ReportCapabilities` below is the server's own answer, and the
 * picker is built from the intersection. What the server cannot render it still refuses at request
 * time with a sentence naming what it can, for the caller that did not ask.
 */
export type ReportFormat = NonNullable<
  Op['generateReport']['requestBody']
>['content']['application/json']['format'];

/**
 * What the **server** can render, which is a different question from the one above (B5).
 *
 * The paragraph above is still the whole argument for why the request enum is not narrowed to what
 * is installed — and what it left unsaid is that a picker built from the enum is then wrong on
 * every deployment, offering PDF that nothing can produce. `GET …/reports/capabilities` answers the
 * other half, and this is its shape: the intersection of "may be asked for" and "has a renderer
 * here", computed by the server from the renderers on its classpath.
 *
 * `formats` is typed as the request union, so a picker fed from it stays exhaustive against
 * `lib/labels.ts` — a fourth format added to the contract still fails the build until it is
 * labelled, whether or not any deployment renders it yet.
 */
export type ReportCapabilities = S['ReportCapabilities'];

// documentation & artifacts
export type DocPage = S['DocPage'];
export type DocPageSummary = S['DocPageSummary'];
export type DocPageSearchHit = S['DocPageSearchHit'];
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
/**
 * Where an artifact sits in the register.
 *
 * Three states reached by two different acts, which is the part worth carrying into the client:
 * `ARCHIVED` is chosen and reversible, `SUPERSEDED` holds exactly when `supersededById` names a
 * replacement — so it is written by registering one and can be neither set nor left.
 */
export type ArtifactStatus = S['Artifact']['status'];
/** The half of that a caller may ask for — `ArtifactStatusInput`, not `Artifact['status']`. */
export type ArtifactCirculation = S['ArtifactStatusInput']['status'];
export type ArtifactInput = S['ArtifactInput'];
/**
 * The register's whole **editable** surface — three fields, and the absences are the design.
 *
 * Scope, plan, kind and every payload column are `updatable = false` on the row: correcting what an
 * artifact *points at* is superseding it (plan §6n), and its status has its own operation. So this
 * is deliberately not `Partial<ArtifactInput>`, which would offer four fields the server refuses.
 */
export type ArtifactEditInput = S['ArtifactEditInput'];
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

// integration (§218) — where a project sends its notices, and what came back
/**
 * A channel, **without its credential and with no property that could carry one**.
 *
 * `secretHint` is the masked tail of a live webhook URL — enough to recognise which room is
 * configured, far short of enough to post to it — and it is null for a kind that has no credential
 * of its own. Nothing in this SPA ever holds a plaintext webhook: it travels one way, on
 * {@link NotificationChannelInput}.
 */
export type NotificationChannel = S['NotificationChannel'];
/**
 * The write shape — a full replacement, with **one field that does not obey that**.
 *
 * `secret` absent on the `PUT` means *unchanged*, which inverts this API's usual convention. The
 * screen never shows a secret, so an edit form has nothing to send back, and a `''` or a `null`
 * there would blank the credential of every channel anybody renamed — a failure with no error,
 * arriving hours later as notifications that stopped.
 */
export type NotificationChannelInput = S['NotificationChannelInput'];
export type NotificationChannelKind = S['NotificationChannelKind'];
export type NotificationSubscription = S['NotificationSubscription'];
export type NotificationSubscriptionInput = S['NotificationSubscriptionInput'];
export type NotificationDelivery = S['NotificationDelivery'];
export type NotificationDeliveryStatus = S['NotificationDeliveryStatus'];
/**
 * What a channel may subscribe to — the live vocabulary, and deliberately **not** the type of
 * `NotificationDelivery.eventType`.
 *
 * A delivery row is historical: it may name an event this instance recorded once and no longer
 * sends, so the contract types that one as a bare string. Rendering a delivery through a map keyed
 * on this union is therefore a lookup that may miss, which is why `labels.ts` gives that column a
 * fallback and the subscription controls do not need one.
 */
// tracker integration (004) — the binding a project files through, and the credential a person
// connects with. Two different things with two different owners, which is the whole of D2.
export type TrackerProvider = S['TrackerProvider'];
export type TrackerBinding = S['TrackerBinding'];
export type TrackerBindingInput = S['TrackerBindingInput'];
export type TrackerBindingPatch = S['TrackerBindingPatch'];
export type TrackerConnectedMember = S['TrackerConnectedMember'];
export type TrackerConnectionState = S['TrackerConnectionState'];
/**
 * One person's connection to one host — **theirs**, served to nobody else, and carrying no
 * property that could hold the credential.
 */
export type TrackerIdentity = S['TrackerIdentity'];
/**
 * The connect shape. `secret` is required here, unlike {@link NotificationChannelInput}'s
 * absent-means-unchanged: a `POST` to this path always sets one, and rotation is a second `POST`.
 */
export type TrackerIdentityInput = S['TrackerIdentityInput'];
export type TrackerConnectionCheck = S['TrackerConnectionCheck'];
/**
 * An issue **as fetched this request with the viewer's own credential** (D15). Never stored, never
 * cached across users. Its `key` and `title` are today's and are not written anywhere.
 */
export type TrackerIssue = S['TrackerIssue'];
export type TrackerIssueStatusCategory = S['TrackerIssueStatusCategory'];
export type TrackerIssueAvailability = S['TrackerIssueAvailability'];
export type TrackerIssueSearch = S['TrackerIssueSearch'];
export type TrackerIssueLookup = S['TrackerIssueLookup'];
export type TrackerIssueCreateInput = S['TrackerIssueCreateInput'];
export type TrackerIssueCreated = S['TrackerIssueCreated'];
/**
 * A tracker issue linked to a case — the stored row, with **no key, no title and no status**
 * (D5/D17). The chip fetches those live, or renders the provider and the stable id.
 */
export type IssueLink = S['IssueLink'];
export type IssueLinkInput = S['IssueLinkInput'];

export type NotificationEventType = S['NotificationEventType'];

// external identity (§222) — the issuers a company authenticates its own people against
/**
 * One sign-in button, as an anonymous caller is allowed to see it.
 *
 * Two fields, and the shape is the design: there is no company id and no company name here,
 * because a domain may be claimed by several companies and naming them would publish the client
 * roster `POST /auth/providers` exists to withhold (§6r).
 */
export type SsoProviderChoice = S['SsoProviderChoice'];
/**
 * What discovery answers — **always present, often empty**, and an empty list is the answer for an
 * unclaimed domain, a disabled provider and a malformed address alike. A screen that told those
 * apart would rebuild the oracle the endpoint's whole design closes.
 */
export type SsoProviderDiscovery = S['SsoProviderDiscovery'];
/**
 * An instance-level social sign-in authority (GitHub, GitLab, Google) — for people, not
 * companies, which is why it is a closed enum and not an `IdentityProvider` row. A provider
 * appears in `SocialProviderList` exactly when the operator configured both halves of its
 * credential; absent means the button does not exist.
 */
export type SocialProvider = S['SocialProvider'];
export type SocialProviderList = S['SocialProviderList'];
/** The begin-link answer: a URL to NAVIGATE to (top-level), never to fetch. */
export type SocialRedirect = S['SocialRedirect'];
/** One linked identity. `emailAtLink` is provenance the provider asserted — never a key. */
export type SocialIdentity = S['SocialIdentity'];
export type SocialIdentityList = S['SocialIdentityList'];
/**
 * A configured issuer, **with no property that could carry its client secret**.
 *
 * `clientSecretSet` is the boolean that replaces it: a form needs to know whether one is stored so
 * it can say "leave blank to keep", and that question is answerable without the value. There is no
 * read path anywhere in this API that echoes the secret back.
 */
export type IdentityProvider = S['IdentityProvider'];
/**
 * The write shape — a full replacement in which **five properties are required and nullable**, and
 * that combination is what a form here has to get right.
 *
 * `clientSecret` is the consequential one: `null` is a *keep*, not a clear, because no read path
 * can echo the stored value back and an administrator renaming a provider has nothing to resend.
 * Omitting the property instead would be a body the contract refuses — it is on the `required`
 * list — so the keep is spelled `null`, not absence. This is the opposite arrangement from
 * {@link NotificationChannelInput}, where the property is optional and absence is the keep.
 */
export type IdentityProviderInput = S['IdentityProviderInput'];
export type IdentityProviderKind = S['IdentityProviderKind'];
/** The domains one company claims, as a whole set — both the request and the response shape. */
export type EmailDomains = S['EmailDomains'];

// work — the board, which composes issues with the four derived generators (011 D-a)
/**
 * A card on any of the three boards — ~~`WorkItem`~~, renamed with the endpoint in 011 D-a.
 *
 * The path named a table that no longer exists: `work_item` is gone and a row-backed card is a
 * `tracker` issue now, so `GET /work-items` became `GET /board` and the shape came with it. Only
 * one of the seven kinds is a row; the rest are projections whose status is computed from their
 * source on every read, which is what stops a card diverging from its execution.
 */
export type BoardCard = S['BoardCard'];
/**
 * A card's status — **an object since M1, and there is no `WorkItemStatus` enum any more**
 * (011 D-c). A project names its own statuses, so the five-member enum could not spell one
 * without a mapping table, and a card carrying both spellings is the two-contradictory-values
 * failure this repo has shipped once already.
 */
export type BoardCardStatus = BoardCard['status'];
export type BoardCardKind = BoardCard['kind'];
/** The viewer's OWN note, personal due date and flag on a card — never another viewer's. */
export type CardAnnotation = S['CardAnnotation'];
export type CardAnnotationInput = S['CardAnnotationInput'];

// tracker (011) — issues, the project's workflow, and the four edges hanging off an issue
/**
 * Where a type sits in the hierarchy. The depth rule is expressed in LEVELS, not in type names:
 * Story, Bug and Spike all sit at `STORY`, so a fourth story-level type is a row rather than a
 * migration.
 */
export type IssueLevel = S['IssueLevel'];
/**
 * What a status MEANS, so a percentage can be computed without knowing what a project called its
 * columns. Every completion figure in the product is `DONE` over the counted total; `IN_PROGRESS`
 * is not partial credit (D8).
 */
export type IssueStatusKind = S['IssueStatusKind'];
export type IssueTypeRef = S['IssueTypeRef'];
export type IssueStatusRef = S['IssueStatusRef'];
/**
 * An epic's completion, **with its basis on the wire** (D8).
 *
 * The client never infers the basis and never recomputes the percentage: an epic weighted by
 * hours and one counted by heads are different measurements, and a number that changes meaning
 * when somebody clears an estimate is the number people learn to distrust.
 */
export type IssueCompletion = S['IssueCompletion'];
export type CompletionBasis = IssueCompletion['basis'];
export type IssueSummary = S['IssueSummary'];
export type Issue = S['Issue'];
export type IssueInput = S['IssueInput'];
export type IssueUpdate = S['IssueUpdate'];
export type IssueTransitionInput = S['IssueTransitionInput'];
/** One acceptance criterion, ordered and individually done (story H1/H4). */
export type AcceptanceCriterion = NonNullable<Issue['acceptanceCriteria']>[number];
export type IssueComment = S['IssueComment'];
export type IssueCommentInput = S['IssueCommentInput'];
/**
 * `tracker`'s projection of a `doc_link` row — **read-only from this module's endpoint.**
 *
 * The write is `documentation`'s block-link POST, because `tracker` may not import that module
 * and does not own the edge. What is served here is the projection, which is what carries
 * `stale` and the excerpt **as it was when linked**.
 *
 * Not to be confused with {@link IssueLink}, which is 004's link out to a *Jira or ADO* issue.
 */
export type IssueDocLink = S['IssueDocLink'];
/**
 * Everything needed to work one task, assembled server-side in one read (015 D4).
 *
 * One composite endpoint rather than six calls, and the reason is stated as a grounding rule: an
 * assistant that has to assemble its own context from six requests generates without it. The task
 * page renders the same payload so a person can see exactly what was handed over — which is the
 * only way to tell "the assistant misread the spec" from "the assistant never received it".
 */
export type AgentTaskContext = S['AgentTaskContext'];
export type AgentSpecExcerpt = S['AgentSpecExcerpt'];
export type ProjectWorkflow = S['ProjectWorkflow'];
export type WorkflowTransition = ProjectWorkflow['transitions'][number];
export type StatusRemovalInput = S['StatusRemovalInput'];

// tracker.board (012 M2) — the PROJECT board, which is not the composite one above
/**
 * Story B3's grouping axis. ~~`RELEASE` is absent until M3, deliberately: a member whose data does
 * not exist would group every card into one lane called "None".~~ → **it landed with 013 T1**, on
 * the commit that added the enum rather than the one that drew a release screen, because
 * `lib/labels.ts::swimlaneByLabels` is `satisfies Record<Union, string>` and the tree has to build
 * at every task boundary (D-j). Six members now.
 */
export type SwimlaneBy = S['SwimlaneBy'];
/** A row in the board picker — `columnCount` rather than the columns, which the picker never draws. */
export type BoardSummary = S['BoardSummary'];
/**
 * A board's **configuration** — its columns and its swimlane axis, and nothing about cards.
 *
 * Named `BoardConfig` rather than `Board` for the reason the operation is `getBoardConfig` rather
 * than `getBoard`: the composite board already owns the bare word here and in the contract, and
 * two shapes called `Board` one import apart is how a wrong type survives review (D-a).
 */
export type BoardConfig = S['Board'];
/**
 * One configured column. **Not `features/board/cardMove.ts`'s `BoardColumn`**, which is assembled
 * from the cards for the two cross-project boards and carries no id (D-b). This one is a
 * `board_column` row and knows about statuses no card currently holds.
 */
export type BoardColumnConfig = S['BoardColumn'];
export type BoardInput = S['BoardInput'];
export type BoardColumnInput = S['BoardColumnInput'];
/** The cards on one board, in lanes, with the project's WIP breaches beside them. */
export type BoardContents = S['BoardContents'];
export type BoardLane = S['BoardLane'];
/**
 * A card on a project board — every one of them an issue with a real key.
 *
 * **Not {@link BoardCard}**, which is the composite board's card and carries four `kind`s, a
 * `derived` flag and a synthetic id. An Epic reaches this shape with its **derived** status and an
 * **empty** `allowedTransitions` (D-r), so a move affordance for one is absent rather than
 * disabled-and-empty.
 */
export type BoardIssueCard = S['BoardIssueCard'];
/**
 * A column over its limit **right now**, counted over the whole project.
 *
 * It does not shrink when a filter is applied, which is the point: a limit is a property of the
 * column, not of what happens to be on screen. Never recomputed client-side from the cards that
 * arrived.
 */
export type WipBreach = S['WipBreach'];
export type CardMoveResult = S['CardMoveResult'];
export type BacklogRow = S['BacklogRow'];
/**
 * Where an issue landed, and whether the project's ranks were respaced to fit it.
 *
 * `rank` is a decimal **as a string** and stays one: `NUMERIC` is arbitrary precision and a
 * `JSON.parse` into a double would silently round a value the server distinguishes. Sort on it,
 * never do arithmetic with it.
 */
export type RankResult = S['RankResult'];
/**
 * A project's label — `IssueLabel` rather than the contract's bare `Label`, which would read as
 * display copy beside `lib/labels.ts` and means the opposite thing.
 */
export type IssueLabel = S['Label'];
export type IssueLabelInput = S['LabelInput'];
export type IssueLabelsInput = S['IssueLabelsInput'];

// tracker.release (013 M3) — releases, readiness, the roadmap and the QA link
export type ReleaseStatus = S['ReleaseStatus'];
/**
 * The one answer to "can we ship 2.4?".
 *
 * `NOT_TESTED` is not `NOT_READY` with no failures: it means nothing has run against this version
 * at all. Read {@link ReleaseTests}`.hasRun` beside it, and never draw a meter from `passed` /
 * `failed` when it is false — they are null, and a zero drawn as a bar looks like a clean release.
 */
export type ReleaseVerdict = S['ReleaseVerdict'];
export type Release = S['Release'];
export type ReleaseSummary = S['ReleaseSummary'];
/** A release as it appears on an issue or a backlog row — never its readiness. */
export type ReleaseRef = S['ReleaseRef'];
/** The row-sized readiness, carried inline on every {@link ReleaseSummary}. */
export type ReadinessSummary = S['ReadinessSummary'];
export type ReleaseReadiness = S['ReleaseReadiness'];
export type ReleaseStories = S['ReleaseStories'];
/** The QA half — **the latest result per case, not the best one**, and null counts when untested. */
export type ReleaseTests = S['ReleaseTests'];
export type ReleaseBlocker = S['ReleaseBlocker'];
export type Roadmap = S['Roadmap'];
export type RoadmapRelease = S['RoadmapRelease'];
export type RoadmapEpic = S['RoadmapEpic'];
export type ReleaseInput = S['ReleaseInput'];
/**
 * Every field optional, and **`status` is not among them**: publishing is
 * `POST /releases/{id}/publish` and costs `release:publish`, which `release:manage` does not imply
 * (D-m). A status property here would be that state moved behind the other key.
 */
export type ReleaseUpdate = S['ReleaseUpdate'];
/** A case linked to a story, with the latest result it has **anywhere** — not scoped to a release. */
export type IssueTestCase = S['IssueTestCase'];
export type IssueTestCasesInput = S['IssueTestCasesInput'];
export type CaseVerification = S['CaseVerification'];
export type IssueRef = S['IssueRef'];

// tracker.imports (014 M4) — the Jira / Azure DevOps offramp
/**
 * The source system. `JIRA_CLOUD` authenticates with Basic (`accountEmail` + an API token); the
 * other two with a Bearer PAT, and `accountEmail` is ignored.
 *
 * **Not {@link TrackerProvider}**, which is 004's *outbound* integration and spells its middle
 * member `JIRA_DC`. Two enums for two directions is deliberate: this one is a source read once and
 * abandoned, that one is a per-person credential the product keeps.
 */
export type ImportProvider = S['ImportProvider'];
/**
 * One connection, for one operation — **and the credential in it is never stored** (D-a), not on
 * the run, not encrypted, not anywhere.
 *
 * That is a claim about this type's lifetime as much as about the server's: an `ImportConnection`
 * belongs in a component's `useState` and in a request body, and nowhere a value outlives the tab.
 * It is never a query key, never `localStorage`, never a URL.
 */
export type ImportConnection = S['ImportConnection'];
export type SourceProject = S['SourceProject'];
export type SourceProjectList = S['SourceProjectList'];
export type ImportPreviewInput = S['ImportPreviewInput'];
/**
 * One source field that is **not** imported, and how many values it holds.
 *
 * The same shape is served by the preview and by the report, from one derivation on the server
 * (D-f), so the screen must not compute, re-order or re-word a row: the two are asserted equal
 * element for element, and a client that rewrote one of them would break a promise it cannot see.
 */
export type DroppedField = S['DroppedField'];
export type PreviewTypeRow = S['PreviewTypeRow'];
/**
 * One source status with the target this project would suggest.
 *
 * `suggestedStatusId` null means nothing matched — the mapping step shows the row **unmapped**, and
 * a client that defaulted it to the first status would be doing precisely what the disabled Confirm
 * exists to prevent.
 */
export type PreviewStatusRow = S['PreviewStatusRow'];
export type ImportPreviewTotals = S['ImportPreviewTotals'];
/** A reason the import cannot proceed, computed by the preview rather than at minute four. */
export type ImportBlocker = S['ImportBlocker'];
export type ImportBlockerKind = S['ImportBlocker']['kind'];
export type ImportPreview = S['ImportPreview'];
export type StatusMappingEntry = S['StatusMappingEntry'];
export type ImportOptions = S['ImportOptions'];
export type ImportInput = S['ImportInput'];
export type ImportRunStatus = S['ImportRunStatus'];
export type AppliedStatusMapping = S['AppliedStatusMapping'];
export type ImportedPerson = S['ImportedPerson'];
export type ImportRunSummary = S['ImportRunSummary'];
/**
 * One import, and the report of it.
 *
 * `startedBy` is a **nullable {@link UserRef}** — read `startedBy?.displayName`. It was an inline
 * `{id, name}` when the contract first declared it and became a `UserRef` in 014 T4, because every
 * other "who did this" position in the contract is one and a bespoke person shape here would have
 * been a fifth spelling of one concept.
 */
export type ImportRun = S['ImportRun'];

/**
 * The one filter the board, the backlog and the table share (D34) — and it is the board's
 * parameter set, which is **not** {@link IssueFilters}.
 *
 * The two differ in both directions and neither difference is incidental: a board takes `labelId`
 * and `priority`, which the table's shape has never carried, and it takes no `statusId` or
 * `parentKey`, because a status filter over a board would empty columns the board is drawn from.
 * A single widened interface would let a screen send a parameter its endpoint answers 400 for.
 */
export interface BoardFilters {
  labelId?: string[];
  assigneeId?: string[];
  epicKey?: string;
  level?: IssueLevel;
  priority?: Priority[];
  unparented?: boolean;
}

/**
 * What the **backlog** declares — {@link BoardFilters} plus a release filter (013 D-k).
 *
 * <h2>A type of its own, because `contractParameters.test.ts` reads the type and not the call site</h2>
 *
 * `GET /projects/{id}/backlog` takes `releaseId` and `unscheduled`; `GET /boards/{id}/cards`
 * **deliberately does not** — `BoardCardController.cards` passes `null, false` into the shared parser
 * with the reason on the line, which is that a board groups by release through the `RELEASE`
 * swimlane axis instead, so every card stays on screen.
 *
 * The first attempt widened `BoardFilters` itself, which the plan predicted and which is wrong: both
 * hooks spread their filter object into `qs`, so the keys the SPA sends to `/boards/{id}/cards` are
 * *that type's members*, and the check that reads them failed in exactly the words §47 and §49 are
 * recorded in — *"an undeclared query parameter is not rejected, it is IGNORED."* Two endpoints that
 * declare different parameters are two types; the same rule that keeps `BoardFilters` and
 * {@link IssueFilters} apart (D-v) keeps this apart from `BoardFilters`.
 *
 * The other half of the cost, had it typechecked: both views draw their chips from
 * `useBoardFilterChips`, so a `release` in the URL would have put a `Release · 2.4` chip on the
 * **board**, over a list nothing had narrowed.
 */
export interface BacklogFilters extends BoardFilters {
  /**
   * Only issues targeting this release — **a filter over the one project-wide rank order rather than
   * a second order** (D-k). The rows come back in the sequence they would have without it, so a drag
   * from one release section into another is still one neighbour-relative `PUT /issues/{key}/rank`.
   */
  releaseId?: string;
  /**
   * Only issues with **no** target release. A flag rather than `releaseId=null`, for `unparented`'s
   * reason: an absent parameter already means *don't filter*, and a client cannot spell the
   * difference between "every release" and "no release" in one value.
   *
   * **Mutually exclusive with `releaseId`** — sending both is a 400 — so the two controls replace
   * each other rather than accumulating.
   */
  unscheduled?: boolean;
}

/**
 * Work-table filter state. Mirrored into the URL so a view is shareable and survives a switch
 * between the five views (D34), which is the whole reason it is not component state.
 */
export interface IssueFilters {
  level?: IssueLevel;
  epicKey?: string;
  parentKey?: string;
  statusId?: string[];
  assigneeId?: string[];
  unparented?: boolean;
}

// time (016 M6) — the timesheet, the approvals queue and the two rollup grains
/**
 * What an entry is logged against (016 D-b). **Five members, and `TEST_RUN` is not one of them.**
 *
 * `timesheet-approvals.html` displays a row logged against a test run and `timesheets.html` — the
 * only screen that *creates* an entry — cannot produce one. The two mockups disagree and the log
 * form wins, because a kind the queue can display and the form cannot make is a row no person can
 * file. `lib/labels.ts::timeSubjectLabels` is where a sixth member becomes a compile error.
 */
export type TimeSubjectType = S['TimeSubjectType'];
/**
 * OPEN is editable by its owner, SUBMITTED by nobody, APPROVED is final, and REJECTED is editable
 * again and carries the note that sent it back. The whole feature turns on that four-way rule, and
 * the client never re-derives it: `TimeEntry.editable` and `TimesheetPeriod.editable` are served.
 */
export type TimesheetState = S['TimesheetState'];
export type TimeEntry = S['TimeEntry'];
export type TimeEntryInput = S['TimeEntryInput'];
export type TimesheetPeriod = S['TimesheetPeriod'];
export type TimesheetDecisionInput = S['TimesheetDecisionInput'];
/** The decision itself, so a caller cannot post a fourth verb the server has no arm for. */
export type TimesheetDecision = TimesheetDecisionInput['decision'];
/**
 * One person's fortnight in the queue, with a total per project — and **`projectTotals` does not
 * add up to `period.loggedHours`**, deliberately.
 *
 * A project-less entry has no row (the contract says the literal em dash is not used, because a dash
 * in a column of project names is a project called dash), and neither has an entry against a
 * project the approver cannot see (016 D-i). Any screen showing both numbers has to label them so
 * the difference does not read as a contradiction.
 */
export type TimesheetQueueEntry = S['TimesheetQueueEntry'];
/** Estimate against approved and pending hours for one issue. Never summed — 016 D-d. */
export type TimeRollup = S['TimeRollup'];
export type EpicTimeRollup = S['EpicTimeRollup'];

// staffing (017 M7) — the directory, capacity, allocation and absence
/**
 * Why somebody is away, and `HOLIDAY` is a member for a reason worth knowing: this product has **no
 * holiday calendar** (017 D-c). A public holiday is one absence row per person, which is what
 * actually zeroes that day's capacity everywhere; a shared calendar would have to be per company
 * *and* per country, and the product has no locale model to hang either on.
 *
 * `lib/labels.ts::absenceKindLabels` is where a fifth member becomes a compile error.
 */
export type AbsenceKind = S['AbsenceKind'];
export type Absence = S['Absence'];
export type AbsenceInput = S['AbsenceInput'];
/**
 * Contracted hours, a job title, a location and **a date rather than a count of years** (017 D-b).
 *
 * `experienceYears` is derived server-side from `experienceSince` and served beside it, so no client
 * does the subtraction: an integer stored as "6" is correct on the day it is typed and wrong every
 * day after, and nothing in the product would ever say so.
 */
/**
 * A company's own customer — an **attribute of a project, never a level of the hierarchy**
 * (scope D1). It carries no isolation semantics and no permission scope, so nothing in the client
 * treats it as a third gate.
 *
 * `Project.client` is `null` for internal work, and that is the common case rather than an edge:
 * the owner's own worked example is a company building its own products. Nothing in this app may
 * render a null client as a deficiency — no "attach a client" prompt, no marker, no empty column.
 */
export type Client = S['Client'];
export type ClientRef = S['ClientRef'];
export type ClientInput = S['ClientInput'];
export type ClientDetail = S['ClientDetail'];
export type ClientProject = S['ClientProject'];
export type ClientContact = S['ClientContact'];
export type ClientContactInput = S['ClientContactInput'];
export type ClientStatus = NonNullable<S['Client']['status']>;

/**
 * Billing (019 M8) — what an approved hour is worth, what a project may spend, and the invoice
 * that says so.
 *
 * **Every monetary figure is a {@link Money}, never a bare number** (019 D-l), and every
 * company-level total is an array of them, one per currency: a company with a EUR client and a CHF
 * client has no single total, and a naked amount is the field that eventually gets summed across
 * two of them.
 *
 * **{@link BudgetBurn} is the one shape here a Test Engineer may read**, and it carries no amount
 * and no currency member at all (019 D-h) — `project-overview.html`'s panel is *"hours, no rates"*.
 * Everything else costs one of the seven money keys, which Instance Admin, Company Admin and
 * Finance hold and nobody else does.
 */
export type Money = S['Money'];
export type RateCard = S['RateCard'];
export type RateCardInput = S['RateCardInput'];
export type RateCardEntry = S['RateCardEntry'];
export type RateCardEntryInput = S['RateCardEntryInput'];
export type RateCardSlot = S['RateCardSlot'];
export type RateCardScope = S['RateCardScope'];
export type Budget = S['Budget'];
export type BudgetInput = S['BudgetInput'];
export type BudgetBasis = S['BudgetBasis'];
export type BudgetBurn = S['BudgetBurn'];
export type Invoice = S['Invoice'];
export type InvoiceDetail = S['InvoiceDetail'];
export type InvoiceLine = S['InvoiceLine'];
export type InvoiceEntry = S['InvoiceEntry'];
export type InvoiceDraftInput = S['InvoiceDraftInput'];
export type InvoiceStatus = S['InvoiceStatus'];
export type ExcludedHours = S['ExcludedHours'];
/** Why an hour is not on a line — `NOT_APPROVED` or `NO_RATE`, both counted and named (019 D-g). */
export type ExclusionReason = NonNullable<S['ExcludedHours']['reason']>;
export type Expense = S['Expense'];
export type ExpenseInput = S['ExpenseInput'];
export type ClientBillingSummary = S['ClientBillingSummary'];
export type ClientBillingRow = S['ClientBillingRow'];
export type BillingSummary = S['BillingSummary'];

export type PersonProfile = S['PersonProfile'];
export type PersonProfileInput = S['PersonProfileInput'];
export type Skill = S['Skill'];
export type SkillInput = S['SkillInput'];
export type PersonSkillsInput = S['PersonSkillsInput'];
export type AllocationSpan = S['AllocationSpan'];
export type Allocation = S['Allocation'];
export type AllocationInput = S['AllocationInput'];
export type AllocationPatch = S['AllocationPatch'];
/**
 * One person over one window, and **every hours figure is nullable** because a person with no
 * profile is a real state rather than a failed read (017, correction 4).
 *
 * Two of those nulls carry their own claim and neither may be rendered as a zero:
 * `capacityHours: null` says nobody has recorded this person's contracted hours — the tile reads an
 * em dash, never `0h` and never a default of 40 — and `utilisationPercent: null` says nobody has
 * allocated them, where `0%` would say they are idle (017 D-i).
 *
 * `capacityHours` is already net of absence. No screen subtracts it a second time.
 */
export type PersonCapacity = S['PersonCapacity'];
export type StaffingCapacityView = S['StaffingCapacityView'];
/** The caller's own capacity — the one staffing read with no staffing permission on it (017 D-m). */
export type SelfCapacity = S['SelfCapacity'];
export type ProjectStaffingPerson = S['ProjectStaffingPerson'];
export type ProjectStaffingView = S['ProjectStaffingView'];

/**
 * What `GET /staffing/people` declares — the directory's whole parameter set, held in the URL.
 *
 * A type of its own rather than an inline object, for {@link BacklogFilters}' reason:
 * `useStaffingPeople` spreads it into `qs`, so the keys this type carries **are** the query string
 * the SPA sends, and `contractParameters.test.ts` reads the type rather than the call site. A field
 * added here that the contract does not declare is not rejected by Spring — it is *ignored*, and
 * the screen renders the broader answer under the heading it had already written.
 *
 * `skill` is an array and is **ANDed** by the server: a person must hold every one, which is what
 * "who knows Playwright and Java" means and is why the chips are a set rather than a single value.
 */
export interface DirectoryFilters {
  from?: string;
  to?: string;
  skill?: string[];
  freeFrom?: string;
}

// enums
export type LifecycleStatus = S['LifecycleStatus'];
export type AutomationStatus = S['AutomationStatus'];
export type Priority = S['Priority'];
export type StepFormat = S['StepFormat'];
/**
 * The Gherkin keyword on one step. **Nullable wherever it appears, and the null is the format** —
 * a numbered action has no Given/When/Then, so the absence is a fact rather than a value somebody
 * forgot. `lib/labels.ts::stepKeywordLabels` is where a new member becomes a compile error.
 */
export type StepKeyword = S['StepKeyword'];
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

/**
 * Audit trail filter state (§242). Mirrored into the URL like every other filter set here.
 *
 * `projectId` is a *filter* on the company trail and is absent from the project trail, where the
 * project comes from the path and is a scope — the server ignores it there, and sending it would
 * suggest a caller could read one project's rows through another project's gate.
 */
export interface AuditFilters {
  projectId?: string;
  actorUserId?: string;
  action?: string;
  occurredAfter?: string;
  occurredBefore?: string;
  page?: number;
  size?: number;
}

/** Case list filter state. Mirrored into the URL so views are shareable (plan §6o/§6q). */
export interface CaseFilters {
  q?: string;
  /**
   * Cases named directly, by id — the by-ids read (B8). The parameter repeats (`?id=…&id=…`) and
   * `qs` spells that for free.
   *
   * **A filter like every other one here**, intersected with the rest rather than replacing them,
   * which is what makes it usable to answer "which of these are approved". Capped server-side at
   * 200 — the page maximum, since more ids than a page can hold cannot be answered in one
   * response — and an id from another project matches nothing rather than failing.
   *
   * It exists because the plan's Test cases tab holds ids and needs their keys: before it, that
   * screen fetched the first 200 cases of the project and joined client-side, so a plan naming a
   * case outside that window drew a chip with no name on it.
   */
  id?: string[];
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
