import { Box, Stack, Tooltip, Typography } from '@mui/material';
import type { ResultCounts, StatusKind } from '../api/types';
import { formatCount, formatPercent, percentOf } from '../lib/format';
import { executedCount, passRate, type Denominator } from '../lib/results';
import { statusLabels } from '../lib/labels';
import { Identifier } from './Identifier';

/**
 * Result counts, rendered as a stacked bar.
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
 */

const SEGMENTS: { kind: StatusKind; key: keyof ResultCounts; color: string }[] = [
  { kind: 'PASSED', key: 'passed', color: 'var(--color-passed)' },
  { kind: 'FAILED', key: 'failed', color: 'var(--color-failed)' },
  { kind: 'BLOCKED', key: 'blocked', color: 'var(--color-blocked)' },
  { kind: 'SKIPPED', key: 'skipped', color: 'var(--color-skipped)' },
  { kind: 'UNTESTED', key: 'notRun', color: 'var(--color-untested)' },
];

export function ResultBar({
  counts,
  height = 10,
  showLegend = false,
  testId,
}: {
  counts: ResultCounts;
  height?: number;
  showLegend?: boolean;
  testId?: string;
}) {
  const total = Math.max(counts.total, 1);

  return (
    <Box data-testid={testId}>
      <Stack direction="row" sx={{ height, borderRadius: 0.5, overflow: 'hidden', bgcolor: 'action.hover' }}>
        {SEGMENTS.map((segment) => {
          const value = counts[segment.key];
          if (value === 0) return null;
          return (
            <Tooltip
              key={segment.kind}
              title={`${statusLabels[segment.kind]}: ${formatCount(value)} of ${formatCount(counts.total)}`}
            >
              <Box
                sx={{ width: `${(value / total) * 100}%`, bgcolor: segment.color, minWidth: 2 }}
                aria-label={`${statusLabels[segment.kind]}: ${value}`}
              />
            </Tooltip>
          );
        })}
      </Stack>

      {showLegend && (
        <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap', mt: 1 }}>
          {SEGMENTS.map((segment) => (
            <Stack key={segment.kind} direction="row" sx={{ alignItems: 'center', gap: 0.75 }}>
              <Box
                aria-hidden="true"
                sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: segment.color, flexShrink: 0 }}
              />
              <Typography variant="caption">
                {statusLabels[segment.kind]}{' '}
                <Identifier variant="muted">{formatCount(counts[segment.key])}</Identifier>
              </Typography>
            </Stack>
          ))}
        </Stack>
      )}
    </Box>
  );
}

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
    <Stack direction="row" sx={{ gap: 4, flexWrap: 'wrap' }} data-testid={testId}>
      <Box>
        <Typography className="identifier" sx={{ fontSize: '2rem', fontWeight: 600, lineHeight: 1 }}>
          {formatPercent(ofExecuted)}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          passed, of {formatCount(executed)} executed
        </Typography>
      </Box>
      <Box>
        <Typography className="identifier" sx={{ fontSize: '2rem', fontWeight: 600, lineHeight: 1 }}>
          {formatPercent(coverage)}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          executed, of {formatCount(counts.total)} in scope
        </Typography>
      </Box>
    </Stack>
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
    <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }} data-testid={testId}>
      <Identifier sx={{ minWidth: 52, textAlign: 'right' }}>{formatPercent(rate, 0)}</Identifier>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {formatCount(counts.passed)}/{formatCount(whole)}
      </Typography>
    </Stack>
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
    <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Pass rate as a share of
      </Typography>
      {(['EXECUTED', 'TOTAL'] as const).map((option) => (
        <Box
          key={option}
          component="button"
          type="button"
          onClick={() => onChange(option)}
          aria-pressed={value === option}
          data-testid={`${testId}.${option}`}
          sx={{
            font: 'inherit',
            fontSize: '0.75rem',
            fontWeight: value === option ? 600 : 400,
            px: 1,
            py: 0.25,
            border: '1px solid',
            borderColor: value === option ? 'primary.main' : 'divider',
            borderRadius: 0.5,
            bgcolor: value === option ? 'action.selected' : 'transparent',
            color: 'text.primary',
            cursor: 'pointer',
          }}
        >
          {option === 'EXECUTED' ? 'executed' : 'all in scope'}
        </Box>
      ))}
    </Stack>
  );
}
