import type { ResultCounts } from '../api/types';
import { formatCount, formatPercent, percentOf } from '../lib/format';
import { executedCount, passRate, type Denominator } from '../lib/results';
import { Identifier } from './Identifier';
import { cn } from '../lib/cn';

/**
 * The three figure-shaped readings of a set of result counts.
 *
 * **Counts are the unit of truth; percentages are derived for reading.** That is why the API
 * returns counts and never percentages: the pass rate a reader wants depends on the
 * denominator they mean, and there are two legitimate ones.
 *
 *   - *of executed* — how well the tests that ran did. Answers "is the build good?"
 *   - *of total* — how much of the plan has been proven. Answers "are we done?"
 *
 * A single stored percentage can only express one of them, and it is never obvious which. So
 * both are offered, always labeled, and never averaged: a rollup sums the leaf counts,
 * because averaging children's percentages over unequal case counts is wrong arithmetic.
 *
 * ## ~~`ResultBar`~~ → superseded by `components/kit/ResultBar` (T3), deleted in T16
 *
 * This file used to export a stacked bar of the same name, plus the `SEGMENTS` table that drew
 * it. The kit's `ResultBar` is the render's version of the same figure and has been the one on
 * screen since T10 ported the index family onto it — the copy here had no importer left, and
 * two stacked bars for one concept is the drift `ui-rules` §6 forbids. The three components
 * below survived because the kit has no equivalent of them; `PlanStatisticsPage` is their one
 * caller and T15 recorded them as the second copy that "collapses when MUI goes". It has.
 */

/**
 * The headline figure. Both denominators are shown side by side rather than behind a toggle
 * on the summary, because the pair *is* the answer: "94% of what ran, over 71% of the plan"
 * is a sentence a release decision can be made on. One number alone is not.
 */
export function PassRateSummary({
  counts,
  coveragePercent,
  testId,
}: {
  counts: ResultCounts;
  /** Coverage from the server when it knows better than executed/total (e.g. across runs). */
  coveragePercent?: number | null;
  testId?: string;
}) {
  const executed = executedCount(counts);
  const ofExecuted = passRate(counts, 'EXECUTED');
  // Falls back to executed/total when the server hasn't computed coverage itself — which it
  // does across runs, where "executed" isn't derivable from one set of counts.
  const coverage = coveragePercent ?? percentOf(executed, counts.total);

  return (
    <div className="flex flex-wrap gap-8" data-testid={testId}>
      <div>
        <p className="identifier m-0 text-[2rem] leading-none font-semibold text-ink">
          {formatPercent(ofExecuted)}
        </p>
        <span className="text-xs text-ink-2">passed, of {formatCount(executed)} executed</span>
      </div>
      <div>
        <p className="identifier m-0 text-[2rem] leading-none font-semibold text-ink">
          {formatPercent(coverage)}
        </p>
        <span className="text-xs text-ink-2">
          executed, of {formatCount(counts.total)} in scope
        </span>
      </div>
    </div>
  );
}

/**
 * A compact "n / m" cell for grids, with the pass rate against the caller's denominator.
 * Grids get the toggle at table level rather than per row, so every row in a comparison
 * uses the same arithmetic.
 */
export function ResultCountsCell({
  counts,
  denominator,
  testId,
}: {
  counts: ResultCounts;
  denominator: Denominator;
  testId?: string;
}) {
  const rate = passRate(counts, denominator);
  const whole = denominator === 'EXECUTED' ? executedCount(counts) : counts.total;

  return (
    <div className="flex items-center gap-2" data-testid={testId}>
      <Identifier className="min-w-[52px] text-right">{formatPercent(rate, 0)}</Identifier>
      <span className="text-xs text-ink-2">
        {formatCount(counts.passed)}/{formatCount(whole)}
      </span>
    </div>
  );
}

/** The toggle itself, so a table's caption states the arithmetic it is using. */
export function DenominatorToggle({
  value,
  onChange,
  testId,
}: {
  value: Denominator;
  onChange: (next: Denominator) => void;
  testId: string;
}) {
  return (
    <div className="flex items-center gap-1">
      <span className="text-xs text-ink-2">Pass rate as a share of</span>
      {(['EXECUTED', 'TOTAL'] as const).map((option) => (
        <button
          key={option}
          type="button"
          onClick={() => onChange(option)}
          aria-pressed={value === option}
          data-testid={`${testId}.${option}`}
          className={cn(
            'cursor-pointer rounded-[2px] border px-2 py-0.5 font-sans text-xs text-ink',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
            value === option
              ? 'border-steel bg-wash-3 font-semibold'
              : 'border-line bg-transparent',
          )}
        >
          {option === 'EXECUTED' ? 'executed' : 'all in scope'}
        </button>
      ))}
    </div>
  );
}
