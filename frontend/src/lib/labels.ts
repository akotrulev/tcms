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
  AutomationStatus,
  CriterionMetric,
  DocBlockType,
  DocPageStatus,
  EditAfterApproval,
  HealthSignal,
  LifecycleStatus,
  PlanStatus,
  Priority,
  ReportFormat,
  ReportJobStatus,
  ReviewState,
  ReviewSubjectType,
  ReviewVerdict,
  RunStatus,
  StatusKind,
  StepFormat,
  WorkItemStatus,
} from '../api/types';

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
 * Here rather than in `BoardPage`, because it is not only the board that needs it: the mock's
 * `POST /work-items` computes `allowedTransitions` from the same set, and a hand-written array
 * there is exactly what keeps mock mode one column behind the server. Order is a display
 * decision and this is the display layer.
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
 * **The list is not narrowed to what the server can render.** `ReportFormat`'s own comment carries
 * the argument: what is installed is a property of the deployment, and a copy of it here would be
 * wrong in both directions — offering nothing where PDF has been installed, and claiming to know
 * where it has not. The refusal names what the server can produce, and it lands on this field.
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
  INCOMPLETE: 'Missing required fields',
  ORPHANED: 'Owner deactivated',
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
  BEST: 'Best result per case',
  ALL: 'Every execution counted',
} as const satisfies Record<AggregationPolicy, string>;

export const aggregationHelp = {
  LATEST: 'Each case counts once, using its most recent result across every run in this plan.',
  LATEST_PER_CONFIG:
    'A case executed on three configurations counts three times — once per configuration — so the total exceeds the case count.',
  WORST:
    'Each case counts once, using its worst result across every run: a case that failed anywhere counts as failed.',
  BEST: 'Each case counts once, using its best result across every run. Flatters a flaky suite — use with care.',
  ALL: 'Every execution is counted separately, so a case retried four times contributes four results.',
} as const satisfies Record<AggregationPolicy, string>;

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
