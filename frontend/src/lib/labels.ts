/**
 * Enum → label maps, derived from the types themselves.
 *
 * `satisfies Record<Union, string>` is the point: adding a member to the union makes this
 * file a compile error until it's labeled. A hand-maintained map drifts silently — a new
 * value falls through to a default, a renamed one leaves a stale entry — and then the same
 * value reads three different ways across grid, filter, and detail (ui-rules §1).
 *
 * Every render path uses these. There is no second place a status becomes a word.
 */
import type { BadgeTone } from '../components/kit/Badge';
import type {
  AbsenceKind,
  ClientStatus,
  AggregationPolicy,
  AssignmentVia,
  ArtifactStatus,
  AutomationStatus,
  BlockLink,
  ConditionActionKind,
  CriterionMetric,
  DocBlockType,
  DocPageStatus,
  EditAfterApproval,
  HealthSignal,
  ImportAction,
  IngestedOutcome,
  IngestionFormat,
  IngestionStatus,
  LifecycleStatus,
  LinkRelationship,
  NotificationChannelKind,
  NotificationDeliveryStatus,
  NotificationEventType,
  PlanStatus,
  Priority,
  ProposalStatus,
  ProposalVerdict,
  ReportFormat,
  ReportJobStatus,
  ReviewState,
  ReviewSubjectType,
  ReviewVerdict,
  RunSource,
  RunStatus,
  SocialProvider,
  StatusKind,
  TrackerConnectionState,
  TrackerIssueAvailability,
  TrackerIssueStatusCategory,
  TrackerProvider,
  StepFormat,
  StepKeyword,
  SuiteActionKind,
  VerdictSource,
  CompletionBasis,
  IssueLevel,
  IssueStatusKind,
  SwimlaneBy,
  ReleaseStatus,
  ReleaseVerdict,
  ImportProvider,
  ImportRunStatus,
  TimeSubjectType,
  TimesheetState,
} from '../api/types';

/**
 * Label a value the wire typed as a bare `string` — the dashboard's tile and segment `key`.
 *
 * **Every other caller in this file indexes its map directly and must keep doing so.** A typed
 * `HealthSignal` looked up in `healthLabels` is total, and routing it through here would trade a
 * compile error for a silent fallback, which is the whole thing these maps exist to prevent.
 *
 * This exists for the one shape that genuinely is not: `DashboardTile.key` is a `string` in the
 * contract because the same field carries a `HealthSignal` on one panel and a `LifecycleStatus` on
 * another (§180 took the server's `label` off the wire, so the client labels from the key). The
 * fallback is the key itself, and it is deliberate on both counts — a tile the client cannot name
 * still renders and still drills, and `OUTDATED_SPEC_CHANGED` on a screen is a legible bug report
 * where a blank tile is a rendering fault nobody can describe.
 */
export function labelForKey(catalogue: Record<string, string>, key: string): string {
  return catalogue[key] ?? key;
}

/**
 * The three social sign-in authorities, spelled the way each brand spells itself. Used by the
 * sign-in page's buttons and the security page's rows — one catalogue, so the two screens
 * cannot drift.
 */
export const socialProviderLabels = {
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  GOOGLE: 'Google',
} as const satisfies Record<SocialProvider, string>;

export const lifecycleLabels = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In review',
  APPROVED: 'Approved',
  DEPRECATED: 'Deprecated',
} as const satisfies Record<LifecycleStatus, string>;

/**
 * Block types, in the contract's own lower-case spelling.
 *
 * The keys are lower case because the wire is — this is the one enum in the product that does not
 * shout, and the map exists so the *editor's* type picker and the reader's rendering cannot end up
 * calling a `list` two different things.
 */
export const docBlockTypeLabel = {
  heading: 'Heading',
  paragraph: 'Paragraph',
  list: 'List',
  code: 'Code',
  table: 'Table',
  image: 'Image',
} as const satisfies Record<DocBlockType, string>;

/**
 * How a person holds the role an org-chart cell shows.
 *
 * The three arms are not a detail: a matrix built the obvious way — the grants that NAME a project
 * — renders `DIRECT` and `TEAM` identically and omits `INHERITED` entirely, so a chart that did not
 * say which arm a cell came from would silently under-report exactly the staffing shape an org
 * chart exists to encourage. A fourth member added to the contract is a compile error here until it
 * is labelled, which is the whole reason this map is not a switch in the grid.
 *
 * There is deliberately no COMPANY arm: a Company Admin produces no cell at all and appears on the
 * person instead, because authority over the company is not a default for its projects.
 */
export const assignmentViaLabels = {
  DIRECT: 'Direct grant',
  TEAM: 'Through a team',
  INHERITED: 'Company-wide default',
} as const satisfies Record<AssignmentVia, string>;

/**
 * A page's publication state.
 *
 * Not a lifecycle: nothing computes from it, and it moves only because somebody holding
 * `doc:publish` said so. Labeled here anyway so the chip, the action button and the header agree.
 */
export const docPageStatusLabel = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
} as const satisfies Record<DocPageStatus, string>;

/**
 * An artifact's place in the register (plan §6n).
 *
 * Worded as states rather than as the acts that produce them, because the chip sits beside a title
 * and describes the row. The register's own vocabulary calls the second one "superseded" and not
 * "replaced", which is the word the refusal sentences and the audit trail use — one verb per
 * operation, `ui-rules` §6.
 */
export const artifactStatusLabel = {
  ACTIVE: 'Active',
  SUPERSEDED: 'Superseded',
  ARCHIVED: 'Archived',
} as const satisfies Record<ArtifactStatus, string>;

export const automationLabels = {
  MANUAL: 'Manual',
  TO_BE_AUTOMATED: 'To be automated',
  AUTOMATED: 'Automated',
  NOT_AUTOMATABLE: 'Not automatable',
} as const satisfies Record<AutomationStatus, string>;

/**
 * `IssueLevel`. Shown in the type picker's grouping and nowhere else — the icon carries it
 * elsewhere.
 *
 * ~~`workItemStatusLabels` and `workItemColumns` stood here.~~ → **deleted with the type they
 * labelled** (011 D-c). `WorkItemStatus` was a five-member enum for a status a project *names*,
 * so the board's columns were the contract's vocabulary and a per-project status could not be
 * spelled at all. Board card tones now come from {@link issueStatusKinds}, and the columns from
 * the project's own workflow. The old map's lesson survives it and is worth keeping: the board
 * once compared `item.status` against English strings, and the mock agreed with the page, so no
 * gate could see that a real server would file every card into no column.
 */
export const issueLevels = {
  EPIC: 'Epic',
  STORY: 'Story level',
  TASK: 'Sub-task level',
} as const satisfies Record<IssueLevel, string>;

/**
 * `IssueStatusKind`. **NOT a status name** — a project names its own statuses, and this is what a
 * status MEANS.
 *
 * Rendered on the workflow settings screen beside each status, and used to pick a badge tone
 * everywhere else. Lower case because it is read as a caption beside a name the project chose
 * ("In Review · in progress"), never as a heading of its own.
 */
export const issueStatusKinds = {
  NOT_STARTED: 'not started',
  IN_PROGRESS: 'in progress',
  DONE: 'done',
} as const satisfies Record<IssueStatusKind, string>;

/**
 * `SwimlaneBy` — how story B3's board bands its rows.
 *
 * ~~Four members, and the fifth is the mechanism rather than an omission~~ → **the mechanism fired,
 * and it fired on the contract commit rather than on the screen commit** (013 T1). Adding `RELEASE`
 * to the contract's enum broke this line, `ProjectBoardView.tsx` and `BoardSettingsPage.tsx` in one
 * `tsc -b`, which is exactly what `satisfies Record<Union, string>` is here for and not what a
 * lookup with a default would have done — that would have shipped a grouping labelled `RELEASE`.
 *
 * Worth keeping for the next axis: the wording lands in the task that lands the **enum**, not in the
 * task that draws the screen, because the tree has to build at every task boundary.
 *
 * `NONE` is worded as what the reader chose ("No swimlanes"), not as the absence of a choice: it
 * is a selectable option in the board settings, and "None" beside four nouns reads as a
 * placeholder for one of them.
 */
export const swimlaneByLabels = {
  NONE: 'No swimlanes',
  EPIC: 'Epic',
  ASSIGNEE: 'Assignee',
  LABEL: 'Label',
  PRIORITY: 'Priority',
  RELEASE: 'Release',
} as const satisfies Record<SwimlaneBy, string>;

/**
 * `ReleaseVerdict` — and **the readiness badge's whole vocabulary**, which diverges from
 * `releases.html` deliberately (013 D-e).
 *
 * The mockup's Readiness column holds `Not ready · Planned · Planned · Released · Released` — two
 * of which are a status a person set and three a verdict the server computed, under one heading.
 * Its row for 2.5 shows 0/6 stories done and reads "Planned" while 2.4 shows 21/27 and reads "Not
 * ready", so the same badge would mean "nobody started" on one row and "somebody did, and it
 * failed" on the next.
 *
 * Everything else on that screen is the mockup's, including the literal `no run yet` in the Tests
 * column — which is where "says so rather than showing zero failures" is actually met.
 * `features/releases/releaseBadge.ts` is the one function that reads this, so the list, the detail
 * page, the roadmap and the overview cannot disagree about what 2.4 is.
 */
export const releaseVerdictLabels = {
  READY: 'Ready',
  NOT_READY: 'Not ready',
  NOT_TESTED: 'Not tested',
} as const satisfies Record<ReleaseVerdict, string>;

/**
 * `ReleaseStatus` — what a person set, as opposed to what the server computed.
 *
 * `PLANNED` is worded as the state rather than as "not released", because a release that has not
 * shipped yet is the ordinary case and the negative form reads as a failure. Only `RELEASED`
 * reaches the badge (D-e's table); `PLANNED` is read on the detail header's own chip, where the
 * verdict is beside it and the two are not competing for one column.
 */
export const releaseStatusLabels = {
  PLANNED: 'Planned',
  RELEASED: 'Released',
} as const satisfies Record<ReleaseStatus, string>;

/**
 * How a status kind reads as colour — the one place a per-project status name becomes a tone.
 *
 * A status the project invented has no tone of its own, so the tone comes from what the status
 * *means*. `NOT_STARTED` is `untested` rather than `plain`: a badge with no tone reads as
 * metadata, and a status is the card's primary fact.
 */
export const issueStatusKindTones = {
  NOT_STARTED: 'untested',
  IN_PROGRESS: 'steel',
  DONE: 'passed',
} as const satisfies Record<IssueStatusKind, BadgeTone>;

/**
 * The basis of a completion percentage (D8), and it is rendered VERBATIM beside every number.
 *
 * `epic.html` and `project-overview.html` both show it as a small caption — "by estimate",
 * "by count" — because a percentage that changes meaning silently is the failure D8 exists to
 * prevent, and the caption is the whole mitigation.
 */
export const completionBases = {
  COUNT: 'by count',
  ESTIMATE: 'by estimate',
} as const satisfies Record<CompletionBasis, string>;

export const priorityLabels = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
} as const satisfies Record<Priority, string>;

export const statusLabels = {
  PASSED: 'Passed',
  FAILED: 'Failed',
  BLOCKED: 'Blocked',
  SKIPPED: 'Skipped',
  RETEST: 'Retest',
  UNTESTED: 'Untested',
  // §97 (M9). "WIP" rather than "In progress": it is the name Zephyr uses, it is what the seed
  // writes into `case_status.name`, and a label that disagreed with the row's own name would put
  // two words for one status on two screens.
  WIP: 'WIP',
} as const satisfies Record<StatusKind, string>;

/**
 * The dashboard's by-last-run segmentation (005 N3/S8) — the seven outcomes, plus the eighth thing
 * a case's last result can be: **there isn't one**.
 *
 * `NEVER_RUN` is not a `StatusKind` and must not be folded into `UNTESTED`, which is a recorded
 * verdict somebody entered. A case nobody has ever executed and a case somebody marked Untested are
 * different facts about different work, and a segmentation that merged them would put the two
 * populations a repository dashboard exists to tell apart into one bar.
 *
 * A partial map with `labelForKey`'s fallback rather than a `satisfies` one: the server sends a
 * `StatusKind` **or** the literal, so the key type on the wire is `string` and there is no union to
 * be exhaustive against. The `statusLabels` spread is what keeps the seven from being a second copy.
 */
export const lastRunLabels: Record<string, string> = {
  ...statusLabels,
  NEVER_RUN: 'Never run',
};

/**
 * The run lifecycle, in the reader's words.
 *
 * Added in §72, with the slice that gave `test_run.status` a writer. Before it, both screens that
 * render a run spelled the status with `status.replace(/_/g, ' ').toLowerCase()` — and the run
 * list's colour map beside it was typed `Record<string, string>`, so it had drifted exactly the way
 * this file's header predicts: it carried a `PLANNED` that is not a run status at all and was
 * **missing `NOT_STARTED`**, which on a real instance was the status of every run in existence.
 */
export const runStatusLabels = {
  NOT_STARTED: 'Not started',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  ABORTED: 'Aborted',
} as const satisfies Record<RunStatus, string>;

/**
 * Where a run came from, in the reader's words.
 *
 * <em>The reason this is a map and not a ternary.</em> `RunListPage` rendered
 * `run.source === 'INGESTED' ? 'from CI' : 'manual'` until §247, which was correct while there
 * were exactly two doors. `SCHEDULED` made the else-branch a lie: every run a poller created would
 * have been labelled **"manual"** beside a `createdBy` of nobody — precisely the false attribution
 * the whole authority-versus-actor split on the server exists to avoid — and it would have
 * typechecked, linted and passed every test.
 *
 * The `satisfies` is the point: the next member is a compile error until it is labelled. That is
 * the guarantee this file's header asks for and the ternary never had.
 */
export const runSourceLabels = {
  MANUAL: 'manual',
  INGESTED: 'from CI',
  SCHEDULED: 'scheduled',
} as const satisfies Record<RunSource, string>;

/**
 * The four CI dialects, spelled the way their own projects spell them.
 *
 * Case matters here in a way it does not for most enums in this file: these are product names a
 * person matches against their `pom.xml` or their CI config, and "Junit" reads as a typo to
 * somebody looking for the one they configured.
 */
export const ingestionFormatLabels = {
  JUNIT: 'JUnit XML',
  TESTNG: 'TestNG XML',
  ALLURE: 'Allure JSON',
  CUCUMBER: 'Cucumber JSON',
} as const satisfies Record<IngestionFormat, string>;

/**
 * What the runner said about one test, before it became one of the project's own statuses.
 *
 * **`ERRORED` is not a synonym for `Failed`** and is worded to keep them apart: JUnit and TestNG
 * separate `<failure>` (an assertion the test made and lost) from `<error>` (the test throwing
 * before it could assert), and the second usually means the fixture or the environment rather than
 * the thing under test. They land on the same `StatusKind`, which is exactly why the distinction has
 * to survive on the screen that shows what was *reported*.
 */
export const ingestedOutcomeLabels = {
  PASSED: 'Passed',
  FAILED: 'Failed',
  ERRORED: 'Errored',
  SKIPPED: 'Skipped',
} as const satisfies Record<IngestedOutcome, string>;

/**
 * A posted report's four states.
 *
 * ~~**Only `COMPLETED` is reachable today**~~ → **`COMPLETED` and `FAILED` since B6**: a report that
 * cannot be parsed leaves a `FAILED` row now, which is what makes a rejected report visible in this
 * history at all. `PENDING` and `PROCESSING` are still unreachable, and are labeled rather than
 * omitted because the enum is the contract's, an asynchronous lane is a named next step, and a
 * screen that rendered a raw `PROCESSING` the day it arrives is the drift this file exists to
 * prevent.
 */
export const ingestionStatusLabels = {
  PENDING: 'Queued',
  PROCESSING: 'Reading',
  COMPLETED: 'Ingested',
  FAILED: 'Failed',
} as const satisfies Record<IngestionStatus, string>;

/**
 * A proposal's three states (§138).
 *
 * Three, not §6w.6's four: `SUPERSEDED` has no writer and is deliberately absent from the
 * contract's enum, so it is absent here too. If it ever arrives, this object stops compiling —
 * which is the whole point of the `satisfies` on every map in this file.
 */
export const proposalStatusLabels = {
  PENDING: 'Waiting for review',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
} as const satisfies Record<ProposalStatus, string>;

/**
 * What the proposer CLAIMED about grounding.
 *
 * `UNVERIFIED` is worded as an instruction rather than as a state, because it is one: §6w.6 asks
 * for *"not independently checked — review against the spec"*, which is exactly what a reviewer
 * needs to be told and exactly what a human-only workflow has always required. Labeling it
 * "Unverified" alone would read as a defect in the proposal rather than as the reviewer's job.
 */
export const proposalVerdictLabels = {
  GROUNDED: 'Grounded',
  PARTIAL: 'Partly grounded',
  HALLUCINATED: 'Contains invented content',
  UNVERIFIED: 'Not independently checked',
} as const satisfies Record<ProposalVerdict, string>;

/**
 * WHO checked — and this map is the reason the verdict is never rendered on its own.
 *
 * §6w.1a: an agent's claim is recorded, shown, attributed, and never counted as verified, because
 * TCMS cannot see that agent's critic, its isolation or its prompt. So `EXTERNAL` is worded as a
 * report of somebody else's statement — *"the connecting agent reports…"* — and not as a finding.
 * There is no `TCMS_VERIFIED` in the contract yet, and when it arrives it will be the only value
 * here that may be worded as a fact.
 */
export const verdictSourceLabels = {
  NONE: 'Nobody has checked this',
  EXTERNAL: 'Reported by the connecting agent',
} as const satisfies Record<VerdictSource, string>;

/**
 * The verdict chip's colour, keyed on the verdict alone — and every one of them is a NEUTRAL or a
 * warning tone. None is `--color-passed`.
 *
 * That is deliberate: green is what this product uses for a fact it verified, and a proposal's
 * verdict is at best a claim by somebody else's critic. A grounded proposal rendered in the same
 * green as a passing test would make the two look like the same kind of statement on a screen whose
 * entire job is telling them apart.
 */
export const proposalVerdictColors = {
  GROUNDED: 'var(--color-info)',
  PARTIAL: 'var(--color-warning)',
  HALLUCINATED: 'var(--color-failed)',
  UNVERIFIED: 'var(--color-draft)',
} as const satisfies Record<ProposalVerdict, string>;

/**
 * Beside the labels, so a status cannot be worded in one place and coloured in another.
 *
 * `COMPLETED` draws from the progress tier, not the outcome tier. It was `--color-passed`, which
 * put a green chip on a run whose result bar was entirely red and whose pass rate was 0% — the
 * colour answered "did the tests pass" when the column asks "has the run finished". `NOT_STARTED`
 * and `IN_PROGRESS` were already non-outcome tones and are unchanged.
 */
export const runStatusColors = {
  NOT_STARTED: 'var(--color-draft)',
  IN_PROGRESS: 'var(--color-info)',
  COMPLETED: 'var(--color-progress-done)',
  ABORTED: 'var(--color-failed)',
} as const satisfies Record<RunStatus, string>;

/**
 * A generation job's four states (§87), in the words of the person who pressed Generate.
 *
 * `PENDING` reads as *Queued* rather than "Pending" because the distinction the reader cares about
 * is "nothing has started yet" versus "it is being written now", and *Pending* says neither. The
 * pair is what makes the wait legible: a report that sits at Queued for a minute is a worker that
 * has not picked it up, and a report at Building is one that has.
 */
export const reportJobStatusLabels = {
  PENDING: 'Queued',
  RUNNING: 'Building',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
} as const satisfies Record<ReportJobStatus, string>;

/** Beside the labels, for `runStatusColors`' reason: one place a state is worded and coloured. */
export const reportJobStatusColors = {
  PENDING: 'var(--color-draft)',
  RUNNING: 'var(--color-info)',
  COMPLETED: 'var(--color-progress-done)',
  FAILED: 'var(--color-failed)',
} as const satisfies Record<ReportJobStatus, string>;

/**
 * The formats a report may be asked for, and what each one is *for* — the second half is the point.
 *
 * A picker offering "MD · HTML · PDF" asks the reader to know what those are for; §6e's answer is
 * that HTML is the one a client is *sent* (self-contained, opens with no network) and Markdown is
 * the one that goes into a wiki or a pull request. The description is rendered beside the option
 * rather than left to the acronym.
 *
 * **This map is still not narrowed to what the server can render, and the picker now is** (B5).
 * The distinction is worth keeping straight: a label is what a format is *called*, and every format
 * the contract can name needs one whether or not this deployment renders it — a job requested
 * before a renderer was removed still has to be readable. Which options are *offered* is
 * `GET …/reports/capabilities`'s answer, read by `GenerateReportDialog`. A hard-coded list of
 * installed renderers here would be the second copy of somebody else's configuration this comment
 * used to warn about; asking the server is not.
 */
export const reportFormatLabels = {
  MD: 'Markdown',
  HTML: 'HTML',
  PDF: 'PDF',
} as const satisfies Record<ReportFormat, string>;

export const reportFormatHints = {
  MD: 'For a wiki, a pull request, or anywhere the text matters more than the layout.',
  HTML: 'One self-contained file — charts included, opens with no network. What a client is sent.',
  PDF: 'Needs a headless browser on the server; not every deployment has one.',
} as const satisfies Record<ReportFormat, string>;

/** Whether the run has ended — the server's `RunLifecycle.isClosed`, and the one copy of it here. */
export const isRunClosed = (status: RunStatus) => status === 'COMPLETED' || status === 'ABORTED';

/**
 * The plan ladder, in the reader's words.
 *
 * Added in §73 with the slice that gave `test_plan.status` a writer — and the sweep §72 owed: the
 * run list's colour map was fixed there and **the plan list's, three files away, had drifted in
 * exactly the same way**. It was typed `Record<string, string>`, carried an `ARCHIVED` that is not a
 * plan status, and was missing `IN_REVIEW` and `APPROVED` — invisible while `DRAFT` was the only
 * value any plan could hold, and a grey unlabelled chip the moment this slice made the other four
 * reachable. Both plan screens also spelled the status `status.toLowerCase()`, which renders
 * `IN_REVIEW` as "in_review".
 */
export const planStatusLabels = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In review',
  APPROVED: 'Approved',
} as const satisfies Record<PlanStatus, string>;

/** Beside the labels, so a status cannot be worded in one place and coloured in another. */
export const planStatusColors = {
  DRAFT: 'var(--color-draft)',
  IN_REVIEW: 'var(--color-info)',
  APPROVED: 'var(--color-passed)',
} as const satisfies Record<PlanStatus, string>;

// `isPlanClosed` lived here — the client's one copy of the server's `PlanLifecycle.isClosed`. It
// went with the CLOSED state in §97 (M7): a plan is an outline, and whether its testing is over is
// answered by its runs. Nothing freezes a plan against editing any more.

/**
 * The review record's four states (plan §6g), in the reader's words.
 *
 * `WITHDRAWN` and `REJECTED` are worded as the different acts they are, which is the whole reason
 * `V22` added the fourth value: the submitter taking their own submission back is not a rejection,
 * and a panel that called it one would name a reviewer who never spoke.
 */
export const reviewStateLabels = {
  IN_REVIEW: 'In review',
  APPROVED: 'Approved',
  REJECTED: 'Sent back',
  WITHDRAWN: 'Withdrawn',
} as const satisfies Record<ReviewState, string>;

/** Beside the labels, so a review state cannot be worded in one place and coloured in another. */
export const reviewStateColors = {
  IN_REVIEW: 'var(--color-info)',
  APPROVED: 'var(--color-passed)',
  REJECTED: 'var(--color-failed)',
  WITHDRAWN: 'var(--color-skipped)',
} as const satisfies Record<ReviewState, string>;

/**
 * What a participant said.
 *
 * All four verdicts, not the two a client may send: the trail also carries the `APPROVED` and
 * `REJECTED` rows the artifact's own ladder writes, and a map keyed off the *input* enum would be a
 * missing label on the first approved review anybody opened.
 */
export const reviewVerdictLabels = {
  LGTM: 'Looks good',
  CHANGES_REQUESTED: 'Changes requested',
  APPROVED: 'Approved',
  REJECTED: 'Sent back',
} as const satisfies Record<ReviewVerdict, string>;

/** What is under review, in the reader's words — used for the refusal a closed review answers. */
export const reviewSubjectLabels = {
  CASE: 'case',
  SUITE: 'suite',
  PLAN: 'plan',
  // The fourth (005 S9b). "page" rather than "doc" or "documentation page": the panel's sentences
  // read "This page has never been submitted for review", and the reader is looking at one.
  DOC: 'page',
} as const satisfies Record<ReviewSubjectType, string>;

/**
 * §6g's two answers to "what does an edit to an approved artifact do".
 *
 * Worded as the *outcome* rather than as the enum: "Retain" and "Re-review" are the plan's terms
 * and mean nothing to somebody meeting this setting for the first time in a dropdown.
 */
export const editAfterApprovalLabels = {
  RETAIN: 'Keep the approval, and mark it stale',
  RE_REVIEW: 'Return it to draft — approval must be re-earned',
} as const satisfies Record<EditAfterApproval, string>;

/**
 * The consequence of each, beside the label so the two cannot drift.
 *
 * The Re-review sentence names the cost rather than selling the feature: §6g recommends Retain
 * precisely because "forcing full re-approval on a typo fix is how review processes get abandoned",
 * and an admin choosing between them should read that here rather than discover it in a month.
 */
export const editAfterApprovalDescriptions = {
  RETAIN:
    'Recommended. Edits are allowed and the approval stands, with a warning shown that the content has changed since it was reviewed.',
  RE_REVIEW:
    'Strict — right for regulated work. Any edit to an approved case, suite or plan sends it back to draft, and it must go through review again.',
} as const satisfies Record<EditAfterApproval, string>;

/**
 * The metrics a plan's entry and exit criteria may be gated on, in the reader's words.
 *
 * `satisfies Record<CriterionMetric, string>` against the contract's own enum, so a metric added
 * server-side is a compile error here rather than an option the editor silently cannot offer.
 *
 * **Two of the five are evaluated today** — pass rate and coverage, from the statistics endpoint's
 * own numbers. The other three need machinery the product does not have (an issue tracker, the
 * review workflow, a build feed) and render as "not evaluated", which is the honest answer and the
 * reason `Criterion.met` is nullable. They are offered anyway: a release gate somebody has agreed
 * to is worth recording before it can be checked automatically, and the plan page shows it as
 * unevaluated rather than as passing.
 */
export const criterionMetricLabels = {
  pass_rate: 'Pass rate (%)',
  coverage: 'Coverage (%)',
  open_critical: 'Open critical defects',
  cases_approved: 'Cases approved (%)',
  build_available: 'Build available',
} as const satisfies Record<CriterionMetric, string>;

/**
 * Which of those the product can actually decide today (B11).
 *
 * The paragraph above always said two of the five evaluate, and the picker did not: all five were
 * offered identically, so a plan could be gated on "open critical defects" and read `not evaluated`
 * for ever with nothing at selection time to say it would. The audit's own words — *a user can
 * build an exit gate that will read `null` forever*.
 *
 * **Marked rather than hidden**, which is the choice the paragraph above already argued for and
 * `ui-rules`' disable-don't-hide one screen over: a gate somebody has agreed to is worth recording
 * before the product can check it, and removing the option would make the plan document lie by
 * omission. What was missing was the label, not the restraint.
 *
 * **This is a copy, and the original is `PlanStatisticsService.actuals`** — the map of metric to
 * number that endpoint can fill. Nothing compares the two: the evaluated set is not on the wire,
 * and putting it there is a contract change for a boolean per metric. So the failure mode is
 * stated instead: teach the server a third metric and this file says "not evaluated" about
 * something that now is, until somebody edits it. `satisfies` at least forces a NEW metric to be
 * classified here rather than silently defaulting either way.
 */
export const criterionMetricEvaluated = {
  pass_rate: true,
  coverage: true,
  open_critical: false,
  cases_approved: false,
  build_available: false,
} as const satisfies Record<CriterionMetric, boolean>;

/**
 * The comparisons a criterion may make, in the contract's order.
 *
 * The server tolerates `==` and `<>` as well, because rows seeded by hand carry them — this list is
 * what a client should *send*, which is why it is the six canonical spellings and not the eight the
 * evaluator accepts.
 */
export const criterionOperators = ['>=', '>', '<=', '<', '=', '!='] as const;

export const stepFormatLabels = {
  STRUCTURED: 'Structured steps',
  GHERKIN: 'Gherkin',
} as const satisfies Record<StepFormat, string>;

/**
 * The Gherkin keywords, title-cased — what a `.feature` file spells and what a step row shows.
 *
 * `satisfies Record<StepKeyword, string>` for this catalogue's usual reason: the enum is the
 * server's and a sixth member is a compile error here until somebody labels it. The mapping looks
 * like a `charAt(0) + toLowerCase()` away from being derivable, and it is not — the keywords are
 * *English words in a file format*, so a locale-aware lower-casing would render `GIVEN` as `Gıven`
 * in a Turkish locale and produce a `.feature` line Cucumber cannot read.
 */
export const stepKeywordLabels = {
  GIVEN: 'Given',
  WHEN: 'When',
  THEN: 'Then',
  AND: 'And',
  BUT: 'But',
} as const satisfies Record<StepKeyword, string>;

/**
 * The four verdicts an imported scenario can carry (§255).
 *
 * **`REFUSE` is a row, never a status code** — one `Scenario Outline` in a folder of forty files
 * refuses that scenario and imports the rest — so it needs a word in a column beside three
 * successes rather than a page of its own. It is deliberately *"Refused"* and not *"Failed"*:
 * nothing broke, and a caller who lacks a key is told which key by the row's own `reason`.
 *
 * `satisfies Record<ImportAction, string>` for this catalogue's usual reason: a fifth verdict on the
 * contract is a compile error here until somebody labels it.
 */
export const importActionLabels = {
  CREATE: 'Create',
  UPDATE: 'Update',
  SKIP: 'Skip',
  REFUSE: 'Refused',
} as const satisfies Record<ImportAction, string>;

/**
 * What an import does to the suite a `Feature:` maps to (§255).
 *
 * Its own map rather than a reuse of {@link importActionLabels}: the two unions differ — a suite is
 * never skipped and a case is never reused — and one map with the union of both members would label
 * combinations neither side can produce, which is exactly the silent fall-through these maps exist
 * to make impossible.
 */
export const suiteActionLabels = {
  CREATE: 'Create',
  REUSE: 'Use existing',
  REFUSE: 'Refused',
} as const satisfies Record<SuiteActionKind, string>;

/**
 * What an import does to the library entry for one `Background:` step (§255, amendment AM1).
 *
 * **`UNCHANGED` is the common answer on a re-import** and it is the point of hashing the step text,
 * so it is worded as the reassurance it is — *"Already there"* — rather than as the absence of an
 * act. A reader scanning this panel after a second import is looking for something to have gone
 * wrong, and a column of blanks is what that looks like.
 */
export const conditionActionLabels = {
  CREATE: 'Create',
  UPDATE: 'Retitle',
  UNCHANGED: 'Already there',
  REFUSE: 'Refused',
} as const satisfies Record<ConditionActionKind, string>;

/**
 * Health signals are the dashboard's vocabulary, so the label has to say what's wrong in
 * the reader's terms — "Automation stopped reporting", not "STALE_AUTOMATION".
 *
 * **These words stay on this side and the severity does not (§179).** `CaseService.facets` argues
 * the split from the server: enum *words* belong to this catalogue, because it is the one place a
 * new member is a compile error until it is labelled. ~~And the wire structurally cannot own them —
 * this map must label `INCOMPLETE`, which no server constant can ever send~~ → **§182 gave
 * `INCOMPLETE` a server constant and a derivation, so that half of the argument is spent.** The
 * argument itself is not: `satisfies Record<HealthSignal, string>` is what makes a new member a
 * compile error here, and that holds whether or not the two enums are the same set. What remains
 * structural is the other clause — a per-case payload carries only the signals that were *raised*,
 * so nothing on the wire can carry the vocabulary.
 *
 * ~~**BUT THE SERVER SENDS A SECOND SET ANYWAY, and six of the ten disagree with this one.**
 * `HealthSignal.label()` reaches the SPA as `DashboardTile.label`, which the dashboard renders
 * verbatim, so the "Needs attention" tile reads *"Outdated — spec changed"* and the case list it
 * drills into reads *"Linked spec changed"* — one navigation, two vocabularies.~~ → **closed in
 * §180: `DashboardTile` has no `label`.** `HealthSignal.label()` is deleted outright and the
 * dashboard labels a tile from its `key` through `labelForKey` above, so this map is the only place
 * a health signal becomes a word. The four that used to agree by coincidence were
 * `NEVER_EXECUTED`, `LONG_UNEXECUTED`, `FAILING_REPEATEDLY` and `UNDECLARED_AUTOMATION`; nothing
 * agrees by coincidence now, because there is nothing to agree with.
 *
 * The note §179 left — *"do not close it by editing one word here"* — was the right instruction and
 * this is why: every one-word fix would have made two spellings agree and left the two spellings.
 */
export const healthLabels = {
  OUTDATED_SPEC_CHANGED: 'Linked spec changed',
  STALE_APPROVAL: 'Approved before its last edit',
  CONDITION_DRIFT: 'Shared condition changed',
  NEVER_EXECUTED: 'Never executed',
  // ~~'Not run in 90 days'~~ → **'Not run recently' (005 §5/§6, item 13).** Ninety days is a
  // `Duration` an operator can tune (`tcms.case-health.long-unexecuted`), so the old label was a
  // number this client had no way of knowing and no way of being told: serving the configured
  // threshold would mean a config-disclosure endpoint for one adverb. The reworded label is true
  // on every deployment; the precise line is the operator's to know.
  LONG_UNEXECUTED: 'Not run recently',
  STALE_AUTOMATION: 'Automation stopped reporting',
  UNLINKED: 'Not linked to any spec',
  FAILING_REPEATEDLY: 'Failing repeatedly',
  // ~~'Missing required fields'~~ — the FOURTH tooltip-and-label pair in this family corrected by
  // the slice that gave its signal a derivation (§158, §178, §181, now §182), and the words were
  // wrong in a way only the derivation could show. Nothing here is a REQUIRED field: a case saves
  // perfectly well with no steps, no expected result and no classification, and the server's
  // predicate says so — what it adds is a gate, `lifecycle_status = 'APPROVED'`. So the fault is
  // not that fields are missing, it is that somebody signed the case off while they were, and the
  // label that omits the gate describes a state no chip is ever raised for.
  INCOMPLETE: 'Approved but unfinished',
  // ~~'Owner deactivated'~~ — the one of the three states the derivation reads that no running
  // deployment can currently produce. §181 gave this signal its writer and measured the words
  // against it: `AppUser.deactivate` has four callers and all four are tests, because the account
  // lifecycle is a `user:manage` screen nobody has built. What a deployment DOES reach is an
  // offboarded colleague (`MembershipRemovalService`, reachable from the Members screen), and that
  // state had no words here at all. The label names the answer both spellings share — the person is
  // not in this company any more — rather than either mechanism, which is also what keeps it true
  // when the account screen ships.
  ORPHANED: 'Owner has left',
  // The mirror of STALE_AUTOMATION, and the label is deliberately about the CASE rather
  // than about CI: what the reader can act on is the automation status field, which is
  // wrong. "Receiving CI results" describes the evidence and leaves them working out
  // what to do with it.
  UNDECLARED_AUTOMATION: 'Automated, but not marked so',
} as const satisfies Record<HealthSignal, string>;

/**
 * The aggregation policy must be displayed on any multi-run report — two teams reading
 * "94% pass" otherwise mean different things (plan §3, detail 9). These describe the
 * arithmetic, because that is the ambiguity being resolved.
 */
export const aggregationLabels = {
  LATEST: 'Latest result per case',
  LATEST_PER_CONFIG: 'Latest result per case, per configuration',
  WORST: 'Worst result per case',
  ALL: 'Every execution counted',
} as const satisfies Record<AggregationPolicy, string>;

export const aggregationHelp = {
  LATEST: 'Each case counts once, using its most recent result across every run in this plan.',
  LATEST_PER_CONFIG:
    'A case executed on three configurations counts three times — once per configuration — so the total exceeds the case count.',
  WORST:
    'Each case counts once, using its worst result across every run: a case that failed anywhere counts as failed.',
  ALL: 'Every execution is counted separately, so a case retried four times contributes four results.',
} as const satisfies Record<AggregationPolicy, string>;

/**
 * What a traceability link claims — `doc_link.relationship`, rendered as the chip on every link.
 *
 * Sentence case rather than the wire value lower-cased, which is what the case detail did inline
 * until §169 and what a second screen would have done slightly differently. The three are not
 * synonyms and the difference is the whole value of the field: a link recorded as *verifies* when
 * the case merely *references* the statement is what turns a coverage number into a false claim.
 */
export const linkRelationshipLabels = {
  VERIFIES: 'Verifies',
  DERIVED_FROM: 'Derived from',
  REFERENCES: 'References',
} as const satisfies Record<LinkRelationship, string>;

/** The sentence beside each choice in the picker — the distinction, not the word again. */
export const linkRelationshipHelp = {
  VERIFIES: 'This case proves the statement. Counted as coverage.',
  DERIVED_FROM: 'The case was written from the statement without claiming to cover it.',
  REFERENCES: 'The case mentions it — context, not coverage.',
} as const satisfies Record<LinkRelationship, string>;

/**
 * What is at the *other* end of a block's link — `BlockLink.sourceType`.
 *
 * ~~Two values and not four.~~ **Three since M1**, and the third arrived exactly the way the
 * sentence below predicted. `doc_link.source_type` also permits `DEFECT` and `DOC_PAGE`; the
 * contract's enum omits both because neither has a writer, and this map is keyed off the contract's
 * enum rather than the column's so a value nothing can produce cannot get a label that implies it
 * can. `satisfies` makes the day one of them gains a writer a compile error here — which is what
 * `ISSUE` was when 011 T4 gave `tracker` a writer for it.
 */
export const linkSourceTypeLabels = {
  TEST_CASE: 'Test case',
  SUITE: 'Suite',
  ISSUE: 'Issue',
} as const satisfies Record<BlockLink['sourceType'], string>;

// ------------------------------------------------- notifications (§218) ----

/**
 * Which transport a channel is.
 *
 * The product's word, not the vendor's enum: `TEAMS` is "Microsoft Teams" because that is what an
 * administrator is looking for in a list beside Slack, and a bare "Teams" reads as a noun this
 * product already uses for something else entirely (`company:teams`).
 */
export const notificationChannelKindLabels = {
  SLACK: 'Slack',
  TEAMS: 'Microsoft Teams',
  EMAIL: 'Email',
} as const satisfies Record<NotificationChannelKind, string>;

/**
 * The kinds, in display order — **derived from the labels rather than listed again.**
 *
 * `satisfies Record<NotificationChannelKind, string>` above is what makes this complete: a member
 * added to the contract's enum is a compile error in the map, and this array inherits that. A
 * second hand-written list is the drift `labels.ts` exists to prevent, one level up from a label.
 */
export const NOTIFICATION_CHANNEL_KINDS = Object.keys(
  notificationChannelKindLabels,
) as NotificationChannelKind[];

/**
 * What each transport authenticates with — the sentence the create form shows beside the secret.
 *
 * The distinction is load-bearing rather than decorative. A Slack or Teams incoming-webhook URL
 * **is** the authentication for the room it posts to, which is why it is required, encrypted, and
 * never returned. Email has no credential of its own: it authenticates as whatever the deployment's
 * mail relay does, which is not a company-owned value, so that kind takes recipients instead and
 * the field is not shown at all.
 */
export const notificationChannelCredentialHelp = {
  SLACK:
    'The incoming-webhook URL from your Slack app. It is the whole authentication for that channel, so it is stored encrypted and never shown again.',
  TEAMS:
    'The incoming-webhook URL from the Teams channel connector. It is the whole authentication for that channel, so it is stored encrypted and never shown again.',
  EMAIL:
    'No credential. Email is sent through this instance’s own mail relay, so this channel is addressed by its recipients instead.',
} as const satisfies Record<NotificationChannelKind, string>;

/** How one delivery attempt ended. Terminal either way — a retry updates the row. */
export const notificationDeliveryStatusLabels = {
  SENT: 'Sent',
  FAILED: 'Failed',
} as const satisfies Record<NotificationDeliveryStatus, string>;

/**
 * What a channel may subscribe to.
 *
 * Keyed on the wire name because that is what the contract's enum members are — dotted, module-
 * qualified strings — and turning them into words is exactly the display decision this file owns.
 *
 * **This map is for a `NotificationSubscription`, never for a `NotificationDelivery`.** The
 * delivery's `eventType` is a bare string on purpose: a historical row may name an event this
 * instance no longer sends, so it renders through {@link notificationEventLabel}, which falls back
 * rather than showing a blank cell for a name the vocabulary has dropped.
 */
export const notificationEventTypeLabels = {
  'execution.run_completed': 'Run completed',
  'work.item_assigned': 'Work assigned to somebody',
  'review.requested': 'Review requested',
  'review.endorsed': 'Peer said it looks good',
  'review.changes_requested': 'Peer asked for changes',
  'review.approved': 'Review approved',
} as const satisfies Record<NotificationEventType, string>;

/** The live vocabulary, derived from the map — `NOTIFICATION_CHANNEL_KINDS`' reasoning. */
export const NOTIFICATION_EVENT_TYPES = Object.keys(
  notificationEventTypeLabels,
) as NotificationEventType[];

/**
 * What each event carries, beside its checkbox — and in particular whether the failure threshold
 * beside it does anything.
 *
 * The threshold is served for every event type, including the one that carries no verdicts, where
 * it is inert rather than a filter that never matches. Hiding it per type would put a rule about
 * which events carry counts into the client, where it would be a second copy of a fact the server
 * owns; saying so is the honest alternative.
 */
export const notificationEventTypeHelp = {
  'execution.run_completed':
    'Sent when a run reaches Completed — not when one is aborted or reopened. Carries the run’s final status and its pass/fail/blocked counts, which is what the failure threshold reads.',
  'work.item_assigned':
    'Sent when a task is created with somebody on it. Carries no result counts, so a failure threshold set here is inert rather than silencing.',
  'review.requested':
    'Sent when somebody is asked to review a case, a suite or a plan. Asking the same person twice sends nothing. Needs the review workflow switched on for that artifact type in project settings — with it off, no review exists and nothing is sent.',
  'review.endorsed':
    'Sent when a reviewer records LGTM. This is a peer’s opinion and not the sign-off — approving is a separate act with its own permission, and it sends “Review approved”.',
  'review.changes_requested':
    'Sent when a reviewer asks for changes. Advisory: it does not move the artifact out of In review.',
  'review.approved':
    'Sent when a review is signed off, for a case, a suite or a plan alike. A bulk approval sends one message per artifact — up to 500 — so leave this off for a channel that only wants headlines.',
} as const satisfies Record<NotificationEventType, string>;

/**
 * One delivery row's event, in words — **with a fallback, unlike every other map in this file.**
 *
 * The delivery log is history and its `eventType` is typed as a bare string for that reason. An
 * instance that recorded `work.defect_created` before that name existed, or one whose vocabulary
 * later drops a name, must still render an honest log; `humanize` turns the wire name into
 * something readable rather than leaving the cell empty, which would read as a row that was never
 * about anything.
 */
export function notificationEventLabel(eventType: string): string {
  return (
    notificationEventTypeLabels[eventType as NotificationEventType] ??
    humanize(eventType.replace(/^[a-z]+\./, ''))
  );
}

/**
 * Fallback for values that arrive without a map — an unrecognized classification value,
 * a tag, a run source. Never used where a typed map exists.
 */
export function humanize(value: string): string {
  return value
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .replace(/^./, (c) => c.toUpperCase());
}

/**
 * Audit actions, in the product's own words (§242).
 *
 * **A partial map with a computed fallback, not a `satisfies Record<Union, string>` one**, and the
 * difference is the contract's: `AuditEvent.action` is a bare `string` because the set grows with
 * every slice that records something, so there is no union for a total map to be total against.
 * Pinning one would make adding an act a contract change, which is the trade `openapi.yaml` refuses
 * at the schema.
 *
 * `notificationEventLabel` one screen over is the same shape for the same reason, and this follows
 * it deliberately rather than inventing a second arrangement. What is here is the set the product
 * writes today, spelled the way a person would say it; anything else falls through to
 * {@link auditActionLabel}'s fallback and renders legibly rather than blank.
 */
export const auditActionLabels: Record<string, string> = {
  'case.created': 'Case created',
  'case.updated': 'Case updated',
  'case.deleted': 'Case deleted',
  'case.cloned': 'Case cloned',
  'case.submitted': 'Case submitted for review',
  'case.approved': 'Case approved',
  'case.unapproved': 'Case approval withdrawn',
  'case.withdrawn': 'Case review withdrawn',
  'case.deprecated': 'Case deprecated',
  'case.revived': 'Case revived',
  'case.quarantined': 'Case quarantined',
  'case.quarantine_released': 'Case released from quarantine',
  'case.re_review_required': 'Case sent back for re-review',
  'case.attachment.add': 'Attachment added to case',
  'case.attachment.remove': 'Attachment removed from case',
  'suite.created': 'Suite created',
  'suite.renamed': 'Suite renamed',
  'suite.deleted': 'Suite deleted',
  'suite.submitted': 'Suite submitted for review',
  'suite.approved': 'Suite approved',
  'plan.created': 'Plan created',
  'plan.updated': 'Plan updated',
  'plan.approved': 'Plan approved',
  'plan.archived': 'Plan archived',
  'plan.deleted': 'Plan deleted',
  'run.created': 'Run created',
  'run.started': 'Run started',
  'run.closed': 'Run closed',
  'run.reopened': 'Run reopened',
  'run.aborted': 'Run aborted',
  'execution.recorded': 'Result recorded',
  'execution.amended': 'Result amended',
  'execution.assigned': 'Execution assigned',
  'execution.unassigned': 'Execution unassigned',
  'project.created': 'Project created',
  'project.archived': 'Project archived',
  'project.unarchived': 'Project unarchived',
  'team.created': 'Team created',
  'team.updated': 'Team updated',
  'team.deleted': 'Team deleted',
  'ingestion.completed': 'CI results ingested',
};

/**
 * One act, in words — the label if this build knows the verb, a readable rendering of the verb
 * itself otherwise.
 *
 * **Never blank and never the raw dotted key on its own.** An audit trail written by a newer
 * server than the SPA it is being read in is the ordinary case during a rollout, and an unlabelled
 * row must still say what happened: `suite.condition_attached` renders as "Suite condition
 * attached", which is a legible bug report where an empty cell is a rendering fault nobody can
 * describe. `notificationEventLabel`'s argument exactly, on a surface where it matters more.
 */
export function auditActionLabel(action: string): string {
  return auditActionLabels[action] ?? humanize(action.replace(/\./g, ' '));
}

/**
 * What kind of thing an act was performed on.
 *
 * The writers spell these inconsistently — `test_case` and `case`, `ARTIFACT` in caps, `test_run`
 * beside `TEST_EXECUTION` — because forty-two call sites each chose their own. The map normalizes
 * the ones that exist onto one vocabulary rather than the SPA rendering the inconsistency; the
 * fallback keeps an unmapped one legible. **Do not "fix" this by editing the writers**: the column
 * is append-only, so historical rows keep whatever they were written with, and a client that only
 * understood the new spelling would blank every row older than the change.
 */
export const auditTargetTypeLabels: Record<string, string> = {
  case: 'Test case',
  test_case: 'Test case',
  suite: 'Suite',
  test_plan: 'Test plan',
  test_run: 'Test run',
  TEST_EXECUTION: 'Execution',
  ARTIFACT: 'Artifact',
  project: 'Project',
  team: 'Team',
  team_membership: 'Team membership',
  company_membership: 'Member',
  company_email_domain: 'Email domain',
  role_assignment: 'Role grant',
  api_token: 'API token',
  identity_provider: 'Identity provider',
  condition: 'Condition',
  classification_value: 'Classification value',
  doc_page: 'Document page',
  doc_link: 'Document link',
  ai_proposal: 'Generated case',
  ingestion_job: 'CI ingestion',
  automation_key_mapping: 'Automation mapping',
};

/** {@link auditActionLabel}'s rule, for the subject rather than the verb. */
export function auditTargetTypeLabel(targetType: string): string {
  return auditTargetTypeLabels[targetType] ?? humanize(targetType);
}

// ------------------------------------------------------------------ tracker (004) ----

/**
 * The two trackers this product speaks to, in the words their own users use.
 *
 * `satisfies Record<TrackerProvider, string>` for this catalogue's usual reason — a third adapter
 * is a compile error here until somebody decides how to say it out loud, rather than a binding
 * whose provider column renders `JIRA_DC` at a person.
 */
export const trackerProviderLabels = {
  JIRA_CLOUD: 'Jira Cloud',
  JIRA_DC: 'Jira Data Center',
  AZURE_DEVOPS: 'Azure DevOps',
} as const satisfies Record<TrackerProvider, string>;

/**
 * The three systems an import can read **from** (014 M4).
 *
 * A second provider catalogue beside `trackerProviderLabels`, and the duplication is the honest
 * shape rather than the lazy one: these are two different enums in the contract — 004's outbound
 * integration spells its middle member `JIRA_DC`, this one `JIRA_DATA_CENTER` — and a shared map
 * would need a key neither enum has. They also mean different things. That one is a credential the
 * product keeps and files issues through; this one is a connection used once and abandoned.
 */
export const importProviderLabels = {
  JIRA_CLOUD: 'Jira Cloud',
  JIRA_DATA_CENTER: 'Jira Data Center',
  AZURE_DEVOPS: 'Azure DevOps',
} as const satisfies Record<ImportProvider, string>;

/**
 * What a run is doing, or what became of it.
 *
 * `FAILED` reads as *"Failed \u2014 nothing was imported"* rather than the bare word, because that
 * is the fact the state carries: the write is one all-or-nothing transaction after the read pass
 * (D-b), so a failed run leaves the project exactly as it was. A screen showing only "Failed"
 * invites the question the label can simply answer.
 */
export const importRunStatusLabels = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed \u2014 nothing was imported',
} as const satisfies Record<ImportRunStatus, string>;

/** The tone each run status wears, in the eight the kit names. */
export const importRunStatusTones = {
  RUNNING: 'retest',
  COMPLETED: 'passed',
  FAILED: 'failed',
} as const satisfies Record<ImportRunStatus, BadgeTone>;

/**
 * A tracker's status, reduced to the **category** and never to its own status name.
 *
 * This is the map 004 Phase 2 requires be keyed on the category: a project's status names are
 * free text, per-project and unbounded — "Ready for QA", "Pending triage", "На проверке" — so a
 * `satisfies Record<Union, string>` over *those* is not a thing that can exist, and a colour table
 * keyed on them would be a permanent list of the statuses somebody happened to have configured
 * the week it was written. The tracker tells us which of three buckets a status is in; that is
 * what is bounded, and that is what is coloured.
 *
 * The chip still shows `statusName` as the *word* — it is what the reader recognises — and takes
 * only its colour from here. Two different questions, and conflating them is what makes an
 * unbounded enum look bounded.
 */
export const trackerStatusCategoryLabels = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
} as const satisfies Record<TrackerIssueStatusCategory, string>;

/**
 * Badge tones for the three categories. Not a status palette: three buckets, three weights.
 *
 * ~~MUI chip colours (`default` / `info` / `success`)~~ → superseded in T16, which took the MUI
 * palette away. `plain` / `steel` / `passed` are the kit's nearest three weights and keep the
 * same reading — an unstarted issue is unpainted, one in flight carries the action hue, and a
 * finished one carries the passed hue.
 */
export const trackerStatusCategoryColors = {
  TODO: 'plain',
  IN_PROGRESS: 'steel',
  DONE: 'passed',
} as const satisfies Record<TrackerIssueStatusCategory, BadgeTone>;

/**
 * Whether a person on this project can use the integration, per bound host (004 D19).
 *
 * **Three states and no fourth**, which is the decision rather than the vocabulary: the list is
 * scoped to answering *"why can nobody on this team file defects?"* and may not show the
 * credential, the hint, the account id, the scopes, the expiry or the text of the last error.
 * `Needs attention` is the whole of what a colleague may learn; the sentence explaining it belongs
 * on the affected person's own account page, where the credential is theirs to read about.
 */
export const trackerConnectionStateLabels = {
  CONNECTED: 'Connected',
  NEEDS_ATTENTION: 'Needs attention',
  NOT_CONNECTED: 'Not connected',
} as const satisfies Record<TrackerConnectionState, string>;

/*
 * ~~`trackerConnectionStateColors` — the same three, as chip colours.~~ → superseded, deleted in
 * T16. It spelled `success` / `warning` / `default`, which were MUI's palette keys and nothing
 * else's, and its only consumer (`settings/TrackerSettingsPage`) grew its own local
 * `satisfies Record<TrackerConnectionState, BadgeTone>` when T15 ported that screen. Keeping a
 * second, unused vocabulary for one concept is the drift `ui-rules` §6 forbids.
 */

/**
 * Why a chip is showing the id instead of the issue (004 D16/D17) — one sentence per reason.
 *
 * `NOT_CONNECTED` and `UNAVAILABLE` are deliberately **different sentences over the same visible
 * chip**: D16 makes *no access* and *bad token* render identically to the reader (the tracker's
 * refusal is not ours to interpret), but "you have not connected" is a thing the reader can fix in
 * one click and "the tracker did not answer" is not. Telling them apart in the tooltip is the
 * whole of the difference, and it is why `tcmsFaults.tracker('403')` has an arm of its own.
 */
export const trackerUnavailableReasons = {
  AVAILABLE: '',
  NOT_CONNECTED: 'Connect your tracker account to see this issue\u2019s title and status.',
  UNAVAILABLE:
    'The tracker did not answer, or it will not show you this issue. The link still works.',
} as const satisfies Record<TrackerIssueAvailability, string>;

/**
 * What an entry was logged against (016 D-b).
 *
 * **Five members, and `TEST_RUN` is deliberately absent.** `timesheet-approvals.html` draws a row
 * against a test run; `timesheets.html` — the only screen that *creates* an entry — has no such
 * option in its `Against` select. The two mockups disagree, and the form wins: a kind the queue can
 * display and the form cannot produce is a row no person can file. Adding it later is one CHECK
 * member, one enum member, and a compile error here.
 *
 * `INTERNAL` reads as `Internal / admin` because that is the mockup's own option text, and because
 * the bare word is the one a reader would take to mean "not visible to the client".
 */
export const timeSubjectLabels = {
  ISSUE: 'Issue',
  MEETING: 'Meeting',
  LEAVE: 'Leave',
  TRAINING: 'Training',
  INTERNAL: 'Internal / admin',
} as const satisfies Record<TimeSubjectType, string>;

/**
 * The four states of a fortnight.
 *
 * `OPEN` is the word on the KPI tile of a period nobody has sent anywhere; the approvals queue says
 * **Not submitted** for the same state, because there the reader is looking at somebody else's
 * fortnight and "open" would read as an invitation to act on it. That second sentence is written at
 * its one call site rather than as a second map here — one state, two audiences, and only one of
 * them is the label.
 */
export const timesheetStateLabels = {
  OPEN: 'Open',
  SUBMITTED: 'Submitted',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
} as const satisfies Record<TimesheetState, string>;

/**
 * Badge tones for the four, mapped onto the kit's eight rather than the mockups' CSS classes.
 *
 * `SUBMITTED` is `retest` and not `steel`: the mockups paint it `b-review`, which is the warning
 * hue, and what it means on both screens is *waiting on somebody* — the same reading `retest` has
 * everywhere else in this product. `REJECTED` is `failed` where the mockups say `b-blocked`; the
 * kit's `blocked` means "something outside stopped this", and a rejection is a decision a named
 * person made, which is what `failed` reads as here.
 */
export const timesheetStateTones = {
  OPEN: 'plain',
  SUBMITTED: 'retest',
  APPROVED: 'passed',
  REJECTED: 'failed',
} as const satisfies Record<TimesheetState, BadgeTone>;

/**
 * Why somebody is away (017 M7).
 *
 * **`HOLIDAY` is a member rather than a calendar**, and the label is where a reader meets that
 * decision: this product has no holiday calendar (017 D-c), so a public holiday is one absence row
 * per person — which is what actually zeroes that day's capacity everywhere. A shared calendar
 * would have to be per company *and* per country, and there is no locale model to hang either on.
 *
 * `OTHER` reads as *Other* and not as *Unspecified*: somebody chose it, so the label must not imply
 * they failed to.
 */
export const absenceKindLabels = {
  LEAVE: 'Leave',
  SICK: 'Sick',
  HOLIDAY: 'Public holiday',
  OTHER: 'Other',
} as const satisfies Record<AbsenceKind, string>;

/**
 * Badge tones for the four, on the kit's eight.
 *
 * All four are `steel` except `SICK`, and the flatness is deliberate: an absence is not a verdict
 * about anybody, and colouring leave differently from a public holiday would make the timeline read
 * as a judgement on how people spend their days. `SICK` is `blocked` for the one reason a tone
 * earns its place here — it is the absence nobody planned, which is the one a staffing reader is
 * actually looking for when a week goes wrong.
 */
export const absenceKindTones = {
  LEAVE: 'steel',
  SICK: 'blocked',
  HOLIDAY: 'steel',
  OTHER: 'steel',
} as const satisfies Record<AbsenceKind, BadgeTone>;

/**
 * Whether a client is one this company still works with (018 Mc).
 *
 * **Two members and no third**, and `ARCHIVED` is not a deleted-flag: `DELETE /clients/{id}` is
 * refused while any project names the client, and archiving is what that refusal points at
 * (018 D-k). The label says *Archived* rather than *Inactive* for that reason — inactive would
 * read as a judgement about the relationship, where archived is a statement about the record.
 */
export const clientStatusLabels = {
  ACTIVE: 'Active',
  ARCHIVED: 'Archived',
} as const satisfies Record<ClientStatus, string>;

/**
 * Badge tones for the two.
 *
 * `ACTIVE` is `steel` and not `good`: a client being current is the ordinary state of almost every
 * row on the screen, and a green badge on every one of them is a colour that has stopped carrying
 * information. `ARCHIVED` is `plain`, which is the only thing the badge is there to say.
 */
export const clientStatusTones = {
  ACTIVE: 'steel',
  ARCHIVED: 'plain',
} as const satisfies Record<ClientStatus, BadgeTone>;
