import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * State the system owns — never a control (UX#114, design language §5/rule 3's counterpart).
 *
 * `RailRow`'s doc comment tells the story of the chip half of this pair; the badge half is the
 * failure that pairs with it going the other way. A badge with an `onClick` reads as a filter
 * control to a screen-reader user and a mouse user alike, and there is nothing behind it — so this
 * component's props type has no `onClick`, and it renders a `<span>`, never a `<button>`.
 */
export type BadgeTone =
  | 'passed'
  | 'failed'
  | 'blocked'
  | 'retest'
  | 'skipped'
  | 'untested'
  | 'steel'
  | 'plain';

export interface BadgeProps {
  tone: BadgeTone;
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}

const TONE_VAR: Record<Exclude<BadgeTone, 'plain'>, string> = {
  passed: '--color-passed',
  failed: '--color-failed',
  blocked: '--color-blocked',
  retest: '--color-retest',
  skipped: '--color-skipped',
  untested: '--color-untested',
  steel: '--color-steel',
};

const FILLED_PCT: Partial<Record<BadgeTone, { border: number; bg: number }>> = {
  passed: { border: 28, bg: 10 },
  failed: { border: 28, bg: 10 },
  blocked: { border: 28, bg: 10 },
  retest: { border: 28, bg: 10 },
  steel: { border: 22, bg: 12 },
};

export function Badge({ tone, children, className, ...rest }: BadgeProps) {
  const varName = tone === 'plain' ? null : `var(${TONE_VAR[tone]})`;
  const filled = FILLED_PCT[tone];

  const style = varName
    ? {
        color: varName,
        ...(filled
          ? {
              borderColor: `color-mix(in oklab, ${varName} ${filled.border}%, transparent)`,
              backgroundColor: `color-mix(in oklab, ${varName} ${filled.bg}%, transparent)`,
            }
          : undefined),
      }
    : undefined;

  return (
    <span
      style={style}
      className={cn(
        'inline-flex items-center gap-[5px] whitespace-nowrap rounded-full border border-white/8',
        'bg-white/5 px-2 py-0.5 text-[11.5px] font-medium text-ink-2',
        className,
      )}
      {...rest}
    >
      {tone !== 'plain' && <span aria-hidden className="block size-1.5 shrink-0 rounded-full bg-current" />}
      {children}
    </span>
  );
}
