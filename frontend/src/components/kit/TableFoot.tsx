import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * Design language rule 2: the table's footer *"states what is on screen and what the keyboard can
 * do"*. The 2026-08-30 renders audit found this on exactly one screen out of forty-two — the
 * execution runner — with every other index leaving its count floating above the table and saying
 * nothing about the sort or any shortcut.
 *
 * It is a `<div role="status">` beside the table rather than a `<tfoot>` inside it, and that is
 * deliberate: the table lives in `ScrollX`, so a `<tfoot>` would scroll horizontally away from the
 * reader who most needs it, and a summary that changes as filters change is a live region.
 *
 * `keys` is optional because not every index has shortcuts. A screen that has them and does not
 * name them is the defect; a screen that has none and names none is correct.
 */
export interface TableFootProps {
  /** What is on screen — "86 cases · 12 selected". */
  summary: ReactNode;
  /** How it is ordered — "sorted by Updated, newest first". */
  sort?: ReactNode;
  /** One entry per binding, already written as the reader reads it — "J/K case". */
  keys?: string[];
  className?: string;
  'data-testid'?: string;
}

export function TableFoot({ summary, sort, keys, className, ...rest }: TableFootProps) {
  return (
    <div
      role="status"
      data-testid={rest['data-testid']}
      className={cn(
        // `.foot` in the render kit is a bar, not an inline strip: its own recessed ground, the
        // stage's 22px gutter, and two type sizes — 12px for the summary, 11px mono for the key
        // hints. It stays in flow rather than pinned to the stage's bottom edge, because it sits
        // inside the content column here and a full-bleed foot would need the shell to own it.
        'mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-line',
        'bg-field/60 px-[22px] py-2.5 text-[12px] text-ink-4',
        className,
      )}
    >
      <span className="tabular-nums">{summary}</span>
      {sort && <span>{sort}</span>}
      {keys && keys.length > 0 && (
        <span className="ml-auto flex flex-wrap gap-3 font-mono text-[11px]">
          {keys.map((key) => (
            <span key={key}>{key}</span>
          ))}
        </span>
      )}
    </div>
  );
}
