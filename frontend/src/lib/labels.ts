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
  HealthSignal,
  LifecycleStatus,
  Priority,
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
  IN_REVIEW: 'In review',
  DONE: 'Done',
} as const satisfies Record<WorkItemStatus, string>;

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
} as const satisfies Record<StatusKind, string>;

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
