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

// work
export type WorkItem = S['WorkItem'];
export type WorkItemStatus = S['WorkItemStatus'];
export type WorkItemInput = S['WorkItemInput'];

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
