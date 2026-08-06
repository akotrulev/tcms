/**
 * Result-count arithmetic.
 *
 * These live apart from `components/ResultBar.tsx` so that file exports only components and
 * keeps Fast Refresh working — the same split as `auth/authContextValue.ts` beside
 * `auth/AuthContext.tsx`.
 *
 * The arithmetic is centralized for a stronger reason than tidiness: a pass rate computed
 * ad hoc in a component is where the wrong denominator gets chosen. There are exactly two
 * legitimate ones and both are named here.
 */
import type { CaseStatus, ResultCounts, StatusKind, StepResult, TestExecutionSummary } from '../api/types';
import { compareCaseKeys, percentOf } from './format';

/** Which denominator a pass rate is taken against. */
export type Denominator = 'EXECUTED' | 'TOTAL';

/**
 * Cases that produced a result. `notRun` is deliberately excluded — counting unexecuted
 * cases as failures would make an in-progress run look catastrophic, and counting them as
 * passes would make it look finished.
 */
export function executedCount(counts: ResultCounts): number {
  return counts.passed + counts.failed + counts.blocked + counts.skipped;
}

/**
 * Pass rate against the chosen denominator, or `null` when that denominator is zero — a
 * rate over nothing is not 0%, it is unknown, and rendering it as 0% reads as total failure.
 */
export function passRate(counts: ResultCounts, denominator: Denominator): number | null {
  const whole = denominator === 'EXECUTED' ? executedCount(counts) : counts.total;
  return percentOf(counts.passed, whole);
}

/**
 * Sums leaf counts for a rollup. Nested totals must be built this way and never by averaging
 * children's percentages — a 100%-passing suite of 2 cases and a 50%-passing suite of 200 do
 * not average to 75%.
 */
export function sumCounts(all: ResultCounts[]): ResultCounts {
  return all.reduce<ResultCounts>(
    (total, counts) => ({
      passed: total.passed + counts.passed,
      failed: total.failed + counts.failed,
      blocked: total.blocked + counts.blocked,
      skipped: total.skipped + counts.skipped,
      notRun: total.notRun + counts.notRun,
      total: total.total + counts.total,
    }),
    { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0 },
  );
}

/**
 * Whether an exit criterion was met, was not met, or was never evaluated.
 *
 * **Three states, not two, and conflating the last two is a release-safety bug.** The server
 * sets `met` to null for metrics a plan's own numbers cannot answer — `open_critical` needs the
 * issue tracker, `cases_approved` the review workflow, `build_available` nothing that exists.
 * A `!criterion.met` test reads those as failed and puts a red cross and an "N unmet" count
 * against gates nobody has checked, which is the opposite of the honest answer: the release may
 * well be fine, and the reader is being told it is blocked.
 *
 * It is here rather than inline in `PlanStatisticsPage` because a second reading of `met`
 * somewhere else is how the two would come to disagree — the rule this project applies to
 * labels, formats and permissions, and this is the same kind of thing.
 */
export type CriterionState = 'MET' | 'UNMET' | 'NOT_EVALUATED';

export function criterionState(criterion: { met?: boolean | null }): CriterionState {
  if (criterion.met === true) return 'MET';
  if (criterion.met === false) return 'UNMET';
  // null AND undefined: the contract marks the field nullable, and a server that omits it
  // entirely is saying the same thing as one that sends null.
  return 'NOT_EVALUATED';
}

/**
 * The colour each state renders in, and the sentence each state means.
 *
 * Extracted in §100 because the plan LIST grew a second reader of `criterionState`. The mapping
 * had lived inline in `PlanStatisticsPage.ExitCriteria`, where it was correct — and a second
 * inline copy is precisely how the detail page would come to draw a not-evaluated gate grey
 * while the list drew the same gate red. The state helper above already carries that argument;
 * these two carry it the rest of the way to the pixels.
 *
 * **`NOT_EVALUATED` must never borrow the failed colour.** It is the whole reason the three
 * states exist: a gate nobody measured is not a gate that failed, and a release blocked by a
 * red mark nothing computed is the defect `criterionState` was written to prevent.
 */
export function criterionColor(state: CriterionState): string {
  if (state === 'MET') return 'var(--color-passed)';
  if (state === 'UNMET') return 'var(--color-failed)';
  // Deliberately the muted text token rather than `--color-untested`: this is an absence of a
  // measurement, not a result kind, and reusing a StatusKind colour would put it in the result
  // vocabulary a reader scans the row for.
  return 'var(--color-text-muted)';
}

/**
 * What a criterion in this state means, in one clause — the tooltip on a mark too small to
 * carry a label of its own.
 *
 * Colour is never the only signal (`StatusChip`'s rule): the strip that uses these is always
 * accompanied by the "n of m met" count in text, and each mark names its own criterion here.
 */
export function criterionStateLabel(state: CriterionState): string {
  if (state === 'MET') return 'met';
  if (state === 'UNMET') return 'not met';
  return 'not evaluated';
}

/**
 * ---------------------------------------------------------------- failures ---
 *
 * "What actually broke?" — the question a lead reads a finished run to answer, and the one the
 * counts above cannot answer on their own because they are totals rather than a list.
 *
 * Two distinctions are load-bearing here, and both have already cost this project a defect
 * elsewhere:
 *
 * **A blocked case is not a failed case.** Blocked means the tester could not get far enough to
 * find out — a missing environment, a precondition that did not hold. `RunDetailPage`'s
 * "Needs attention" chip merges the two on purpose (both need somebody), but a *failure* summary
 * that merged them would report the product as broken in places nobody has tested. They are
 * partitioned, counted separately, and labelled with different sentences.
 *
 * **An unmarked step is not a passed step.** `StepResult.statusId` is nullable, and null means
 * nobody recorded anything — which is the ordinary state of every step *after* the one a tester
 * stopped on. `suggestedVerdict` shipped the mirror-image of this defect (a vacuous `[].every()`
 * proposing Passed from no evidence at all), and `criterionState` above exists for the same
 * reason one field over. So the step summary reports THREE numbers and never two.
 */

/** A run's executions split by what its verdict actually claims. */
export interface RunFailures {
  /** The product did something wrong. */
  failed: TestExecutionSummary[];
  /** Nobody could find out. */
  blocked: TestExecutionSummary[];
}

/**
 * Partition a run's executions into the two kinds of bad news, each in a stable order.
 *
 * Sorted by `compareCaseKeys` rather than left in the run's own order: this list is read as a
 * report, and a report whose rows move between two refetches is one nobody can quote. (The plain
 * `localeCompare` puts `TC-10` ahead of `TC-9` — §100's finding, and the reason that helper
 * exists.)
 *
 * Everything else is dropped, including `SKIPPED`: a case somebody deliberately declined to run
 * is not a finding, and putting it here would pad the answer to "what broke" with things that
 * did not.
 */
export function runFailures(executions: TestExecutionSummary[]): RunFailures {
  const of = (kind: StatusKind) =>
    executions
      .filter((execution) => execution.status?.kind === kind)
      .sort((a, b) => compareCaseKeys(a.caseKey, b.caseKey));

  return { failed: of('FAILED'), blocked: of('BLOCKED') };
}

/** What a case's step checklist actually says, in the three numbers it takes to say it. */
export interface StepOutcome {
  /** Steps whose recorded status is FAILED — the ones worth reading. */
  failed: StepResult[];
  /** Steps carrying no status at all. Never folded into `failed` and never into "passed". */
  unmarkedCount: number;
  /** Steps on the pinned case version. Zero for an unstructured (exploratory) case. */
  totalCount: number;
}

/**
 * Resolve a pinned case's steps against the project's status vocabulary.
 *
 * `StepResult` carries a `statusId` and not a kind, because a project names its own statuses; the
 * vocabulary is the only thing that can say which id means FAILED. An id the vocabulary does not
 * know resolves to **no kind**, which counts as neither failed nor unmarked — it is a result this
 * client cannot interpret, and guessing either way would be worse than leaving it out of both.
 *
 * `unmarkedCount` keys on `statusId == null` alone, deliberately not on "did not resolve": those
 * are two different situations (nobody recorded anything vs. somebody recorded something we
 * cannot read) and only the first is the tester's unfinished work.
 */
export function stepOutcome(steps: StepResult[], statuses: CaseStatus[]): StepOutcome {
  const kindOf = (statusId: string | null | undefined) =>
    statusId == null ? null : (statuses.find((status) => status.id === statusId)?.kind ?? null);

  return {
    failed: steps
      .filter((step) => kindOf(step.statusId) === 'FAILED')
      .sort((a, b) => (a.position ?? 0) - (b.position ?? 0)),
    unmarkedCount: steps.filter((step) => step.statusId == null).length,
    totalCount: steps.length,
  };
}
