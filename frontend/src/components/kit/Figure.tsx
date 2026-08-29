import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * The two analytics screens' figures — design language §6: every figure is one series in steel or
 * a status stack. There is no categorical palette, by design — the product's accents measured
 * 2.5 ΔE apart under deuteranopia, indistinguishable, so a three-series chart facets into three
 * single-series ones instead of sharing an axis. No pie, donut or gauge anywhere.
 *
 * `Tile` is a real `<button>`, not a `<div>` — rule 8: a count is a link to its rows.
 */
export interface TileProps {
  label: ReactNode;
  value: ReactNode;
  detail?: ReactNode;
  alert?: boolean;
  onClick: () => void;
  className?: string;
  'data-testid'?: string;
}

export function Tile({ label, value, detail, alert, onClick, className, ...rest }: TileProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={alert ? { borderColor: 'color-mix(in oklab, var(--color-failed) 26%, transparent)' } : undefined}
      className={cn(
        'rounded-lg border border-white/8 bg-panel px-[15px] py-[13px] text-left hover:border-white/14',
        className,
      )}
      {...rest}
    >
      <span className="mb-1.5 flex items-center gap-1.5 text-[11.5px] text-ink-3">{label}</span>
      <span
        style={alert ? { color: 'var(--color-failed)' } : undefined}
        className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink"
      >
        {value}
      </span>
      {detail && <span className="mt-[5px] block text-[11.5px] text-ink-4">{detail}</span>}
    </button>
  );
}

export interface HBarProps {
  label: ReactNode;
  value: ReactNode;
  fraction?: number;
  passed?: number;
  failed?: number;
  blocked?: number;
  untested?: number;
  className?: string;
  'data-testid'?: string;
}

const STACK_KEYS = ['passed', 'failed', 'blocked'] as const;

export function HBar({ label, value, fraction, passed, failed, blocked, untested, className, ...rest }: HBarProps) {
  const counts = { passed: passed ?? 0, failed: failed ?? 0, blocked: blocked ?? 0 };
  const isStack = passed != null || failed != null || blocked != null || untested != null;
  const segTotal = counts.passed + counts.failed + counts.blocked + (untested ?? 0);
  const segments = STACK_KEYS.map((key) => ({ key, count: counts[key] })).filter((segment) => segment.count > 0);

  return (
    <div
      className={cn('grid grid-cols-[minmax(0,88px)_minmax(46px,1fr)_50px] items-center gap-[11px] text-[12.5px]', className)}
      {...rest}
    >
      <span className="truncate text-ink-2">{label}</span>
      <span className="flex h-2 gap-0.5 overflow-hidden rounded bg-field">
        {isStack ? (
          segments.map((segment) => (
            <i
              key={segment.key}
              style={{ width: `${(segment.count / segTotal) * 100}%`, backgroundColor: `var(--color-${segment.key})` }}
              className="block h-full"
            />
          ))
        ) : (
          <i style={{ width: `${(fraction ?? 0) * 100}%` }} className="block h-full rounded-[1px] bg-steel" />
        )}
      </span>
      <span className="text-right font-mono text-[11.5px] tabular-nums text-ink-3">{value}</span>
    </div>
  );
}

export interface SparklinePoint {
  value: number;
  highlighted?: boolean;
}

export interface SparklineProps {
  points: SparklinePoint[];
  className?: string;
  'data-testid'?: string;
}

export function Sparkline({ points, className, ...rest }: SparklineProps) {
  const max = Math.max(1, ...points.map((point) => point.value));

  return (
    <div className={cn('flex h-11 items-end gap-[3px]', className)} {...rest}>
      {points.map((point, index) => (
        <i
          key={index}
          style={{
            height: `${(point.value / max) * 100}%`,
            backgroundColor: point.highlighted ? undefined : 'color-mix(in oklab, var(--color-steel) 12%, transparent)',
          }}
          className={cn('block min-h-[2px] flex-1 rounded-t-[1px]', point.highlighted && 'bg-steel')}
        />
      ))}
    </div>
  );
}
