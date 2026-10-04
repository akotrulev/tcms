import { cn } from '../../lib/cn';

/**
 * A stacked result bar — proportion first, numbers second, never a pie.
 *
 * Two things here are the `dataviz` validator's doing rather than taste, and both are recorded in
 * `docs/specs/2026-08-28-design-language.md` §6 as **defects in what shipped before**, not as
 * preferences:
 *
 * 1. **`untested` has no fill.** As a solid it sat 10.7 ΔE from `skipped` in OKLab — a hard fail
 *    for full colour vision, before CVD is considered — and the two were adjacent in every bar in
 *    the product. Re-stepping either colour was the wrong fix: *untested is the absence of a
 *    result, not a result*, so it became the empty track. That deletes the failing pair outright
 *    and leaves four fills whose worst adjacent pair is 9.4 ΔE deutan / 17.3 normal, both passing.
 *    `--color-untested` survives only as the ink of the words "Never run".
 * 2. **A 2px gap between segments, and 3px ends.** Abutting fills of similar lightness read as one
 *    segment at 6px tall; the gap is what makes the boundary survive a CVD reader.
 *
 * So `untested` is deliberately absent from `FILLS`. Adding it back as a fill re-creates the
 * measured failure, which is why `kitRules.test.tsx` counts the fills.
 */
export interface ResultBarProps {
  passed?: number;
  failed?: number;
  blocked?: number;
  skipped?: number;
  /** Rendered as the empty remainder of the track, never as a fill. */
  untested?: number;
  /**
   * `edge` is the board card's form: 3px, flush to the card's bottom edge, 1px gaps, square ends.
   * The same four fills and the same empty track for `untested` — only the geometry differs.
   */
  variant?: 'bar' | 'edge';
  className?: string;
  'data-testid'?: string;
}

type Counts = Pick<ResultBarProps, 'passed' | 'failed' | 'blocked' | 'skipped' | 'untested'>;

const FILLS = [
  { key: 'passed', className: 'bg-[color:var(--color-passed)]' },
  { key: 'failed', className: 'bg-[color:var(--color-failed)]' },
  { key: 'blocked', className: 'bg-[color:var(--color-blocked)]' },
  { key: 'skipped', className: 'bg-[color:var(--color-skipped)]' },
] as const;

export function ResultBar({
  className,
  variant = 'bar',
  'data-testid': testId,
  ...counts
}: ResultBarProps) {
  const edge = variant === 'edge';
  const total =
    (counts.passed ?? 0) +
    (counts.failed ?? 0) +
    (counts.blocked ?? 0) +
    (counts.skipped ?? 0) +
    (counts.untested ?? 0);

  const segments = FILLS.map((fill) => ({ ...fill, count: counts[fill.key] ?? 0 })).filter(
    (segment) => segment.count > 0,
  );

  return (
    <span
      role="img"
      aria-label={describe(counts, total)}
      data-testid={testId}
      className={cn(
        'flex overflow-hidden',
        edge ? 'absolute inset-x-0 bottom-0 h-[3px] gap-px bg-wash-3' : 'h-1.5 min-w-[110px] gap-0.5 rounded-sm bg-field',
        className,
      )}
    >
      {segments.map((segment) => (
        <i
          key={segment.key}
          data-fill={segment.key}
          className={cn(
            'block h-full',
            !edge && 'rounded-xs first:rounded-l-sm last:rounded-r-sm',
            segment.className,
          )}
          style={{ width: `${(segment.count / total) * 100}%` }}
        />
      ))}
    </span>
  );
}

/**
 * The bar is the only carrier of this information for a screen-reader user, so it gets a real
 * label rather than being hidden. An empty total reads as "Never run" — the one place
 * `--color-untested` still speaks, as ink rather than as a fill.
 */
function describe(counts: Counts, total: number): string {
  if (total === 0) return 'Never run';
  const parts = [
    counts.passed ? `${counts.passed} passed` : null,
    counts.failed ? `${counts.failed} failed` : null,
    counts.blocked ? `${counts.blocked} blocked` : null,
    counts.skipped ? `${counts.skipped} skipped` : null,
    counts.untested ? `${counts.untested} untested` : null,
  ].filter((part): part is string => part !== null);
  return `${parts.join(', ')} of ${total}`;
}
