import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * One setting: the claim and its consequence on the left, one control on the right.
 *
 * The render's `.setting` (`docs/specs/assets/renders/07-settings-admin.html`), and the shape
 * family 7 repeats on every one of its screens — *"Two columns, always the same two. Nine settings
 * screens, one scan. A page of mixed inline controls makes a reader hunt for the thing they came
 * to change."*
 *
 * `consequence` is the load-bearing slot and the reason this is a component rather than a
 * `<div className="grid">`. Every setting on these screens **states its blast radius against live
 * data**: not what it toggles, what it will cause, counted before the control moves — *"Raising
 * this to 2 leaves 7 items with one approval waiting for a second."* It renders as quiet prose
 * behind a rule, never as a warning: amber is reserved for a state that is currently wrong, and a
 * consequence is neutral information about a choice not yet made.
 */
export interface SettingRowProps {
  label: ReactNode;
  description?: ReactNode;
  /** What this setting would land on, counted against live data. */
  consequence?: ReactNode;
  control?: ReactNode;
  className?: string;
  'data-testid'?: string;
}

export function SettingRow({ label, description, consequence, control, className, ...rest }: SettingRowProps) {
  return (
    <div
      className={cn(
        'grid items-start gap-4 border-b border-white/8 py-4 last:border-b-0',
        'md:grid-cols-[minmax(0,1fr)_220px] md:gap-[26px]',
        className,
      )}
      {...rest}
    >
      <div className="min-w-0">
        <h3 className="mb-1 text-[13.5px] font-medium text-ink">{label}</h3>
        {description && (
          <p className="max-w-[62ch] text-[12.5px] leading-[1.55] text-ink-3">{description}</p>
        )}
        {consequence && (
          <p className="mt-2 max-w-[62ch] border-l-2 border-white/14 pl-2.5 text-[12px] leading-[1.55] text-ink-4">
            {consequence}
          </p>
        )}
      </div>
      {control && <div className="flex items-center gap-2.5 md:justify-end md:pt-0.5">{control}</div>}
    </div>
  );
}

/**
 * The uppercase mono heading the render puts above each group of settings. Small enough to live
 * here rather than in a file of its own, and it exists so the nine screens spell the same thing
 * the same way.
 */
export function SettingGroup({
  title,
  children,
  className,
  ...rest
}: {
  title: ReactNode;
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}) {
  return (
    <section className={cn('mt-6 first:mt-0', className)} {...rest}>
      <h2 className="mb-1.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
        {title}
      </h2>
      {children}
    </section>
  );
}
