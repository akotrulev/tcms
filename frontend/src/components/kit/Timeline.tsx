import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * One column of events, mono stamps in a fixed gutter — case history, suite history, the audit log.
 *
 * The connector line is suppressed on the last event via `:last-child`, matching `.tl .e:last-child
 * .mark::after{display:none}` — driven off the DOM's own last-child rather than a caller-supplied
 * `last` flag, so appending an event to the list never leaves a stray connector needing a second
 * prop update to remove.
 */
export interface TimelineProps {
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}

export function Timeline({ children, className, ...rest }: TimelineProps) {
  return (
    <ol
      className={cn('flex flex-col', '[&>li:last-child_.tl-connector]:hidden', className)}
      {...rest}
    >
      {children}
    </ol>
  );
}

export interface TimelineEventProps {
  when: ReactNode;
  children: ReactNode;
  detail?: ReactNode;
  highlighted?: boolean;
  className?: string;
  'data-testid'?: string;
}

export function TimelineEvent({ when, children, detail, highlighted, className, ...rest }: TimelineEventProps) {
  return (
    <li className={cn('grid grid-cols-[96px_16px_1fr] gap-0 pb-4', className)} {...rest}>
      <span className="pr-3 pt-0.5 text-right font-mono text-[11px] tabular-nums text-ink-4">{when}</span>
      <span className="relative flex justify-center">
        <span
          aria-hidden
          style={
            highlighted
              ? { boxShadow: '0 0 0 3px color-mix(in oklab, var(--color-steel) 12%, transparent)' }
              : undefined
          }
          className={cn('z-10 mt-1.5 size-[7px] shrink-0 rounded-full', highlighted ? 'bg-steel' : 'bg-ink-4')}
        />
        <span aria-hidden className="tl-connector absolute top-[11px] bottom-[-16px] w-px bg-white/8" />
      </span>
      <span className="min-w-0 pl-3 text-[13px] leading-[1.55] text-ink-2">
        {children}
        {detail && <span className="mt-[3px] block text-xs text-ink-4">{detail}</span>}
      </span>
    </li>
  );
}
