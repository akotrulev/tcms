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
import type { ResultCounts } from '../api/types';
import { percentOf } from './format';

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
