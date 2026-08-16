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
import type {
  AggregationPolicy,
  ArtifactStatus,
  AutomationStatus,
  BlockLink,
  CriterionMetric,
  DocBlockType,
  DocPageStatus,
  EditAfterApproval,
  HealthSignal,
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
  RunStatus,
  SocialProvider,
  StatusKind,
  StepFormat,
  VerdictSource,
  WorkItemStatus,
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
 * Board columns (plan §6l).
 *
 * The reason this map exists at all: until §53 the board compared `item.status` against
 * these English strings *directly*, and the contract typed `status` as an unconstrained
 * `string`. The mock served 'To do' because the page expected 'To do', so mock mode agreed
 * with itself and no gate could see that a real server choosing `TODO` would file every
 * card into no column and render an empty board. Naming the codes here puts the vocabulary
 * on the contract's side of the comparison — §52's lesson, one level down.
 */
export const workItemStatusLabels = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  BLOCKED: 'Blocked',
  IN_REVIEW: 'In review',
  DONE: 'Done',
} as const satisfies Record<WorkItemStatus, string>;

/**
 * The board's columns, left to right — §6l's order.
 *
 * Here rather than in `BoardPage`: order is a display decision and this is the display layer, and
 * more than one screen needs it. (It was also what the mock's `POST /work-items` computed
 * `allowedTransitions` from, back when a hand-written array there could leave mock mode a column
 * behind the server — that reason retired with the mock; the one above did not.)
 *
 * **Derived from the label map, which is the part that makes it exhaustive.** That map is
 * `satisfies Record<WorkItemStatus, string>`, so a status added to the contract is a compile error
 * until it is labelled — and taking the columns from its keys means it then has a column too,
 * automatically. Writing them out again as a second literal is what would let the two diverge, and
 * the direction it diverges is a card that exists, is returned by the API, and appears nowhere on
 * screen (§53's reason for leaving BLOCKED out until §54 could add all three pieces at once).
 *
 * The assertion is the one cast in this file and it is justified by the line above it: the object
 * has exactly the enum's keys, checked by the compiler, so `Object.keys` cannot produce anything
 * else. `BoardPage` additionally asserts the result covers `WorkItemStatus` — a guard that fails
 * loudly if this reasoning ever stops holding.
 */
export const workItemColumns = Object.keys(workItemStatusLabels) as WorkItemStatus[];

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

/** Beside the labels, so a status cannot be worded in one place and coloured in another. */
export const runStatusColors = {
  NOT_STARTED: 'var(--color-draft)',
  IN_PROGRESS: 'var(--color-info)',
  COMPLETED: 'var(--color-passed)',
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
  COMPLETED: 'var(--color-passed)',
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
  LONG_UNEXECUTED: 'Not run in 90 days',
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
 * Two values and not four. `doc_link.source_type` also permits `DEFECT` and `DOC_PAGE`; the
 * contract's enum omits both because neither has a writer, and this map is keyed off the contract's
 * enum rather than the column's so a value nothing can produce cannot get a label that implies it
 * can. `satisfies` makes the day one of them gains a writer a compile error here.
 */
export const linkSourceTypeLabels = {
  TEST_CASE: 'Test case',
  SUITE: 'Suite',
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
