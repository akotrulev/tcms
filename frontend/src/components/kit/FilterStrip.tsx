import { ListFilter } from 'lucide-react';
import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';
import { Button, Popover, PopoverContent, PopoverTrigger } from '../ui';
import { Chip } from './Chip';

/**
 * Design language rule 2: *"filters in one row under the toolbar"*.
 *
 * The distinction this component exists to hold is §5's: **a chip here means "this filter is
 * applied; remove it", never "this value exists; apply it".** What shipped before it
 * (`CaseFilterBar`, renders audit 1.1) listed every available value of four vocabularies as a
 * toggle chip — eleven for Health alone, over two rows, wider than the table's own Health column —
 * which is why that strip could not be one row: it scaled with the vocabulary rather than with what
 * the reader had chosen, and put ~480px of chrome above the first data row.
 *
 * So choosing happens in `pickers` (a closed control per facet) and the strip shows only what is
 * chosen. The consequence is that the strip's height is bounded by the reader's own choices, which
 * is the property rule 2 is actually asking for.
 *
 * **The whole applied chip removes its filter** — body and ×, not only the ×. A chip's `onClick` is
 * required (`Chip`), and there is no second meaning available here: an applied filter's only verb
 * is "stop applying it". Making the body a no-op to keep the × the sole target would be a button
 * that does nothing, which is the anti-pattern `Chip` exists to prevent.
 */
export interface AppliedFilter {
  /** Stable across renders — the facet and the value, e.g. `lifecycleStatus:APPROVED`. */
  key: string;
  /** Written as the reader reads it: `Status · Approved`. */
  label: string;
  onRemove: () => void;
}

export interface FilterStripProps {
  /** The search box, when this family has one. */
  search?: ReactNode;
  /** One closed picker per facet. */
  pickers?: ReactNode;
  applied: AppliedFilter[];
  /** `Showing 86 of 1 284` — or the plain count when nothing is applied. */
  summary?: ReactNode;
  /**
   * Omitted hides the control: there is nothing to clear.
   *
   * Gated on this and NOT on `applied.length`. Not every filter that narrows a list has a chip —
   * a suite selected in the tree beside it, a release picked upstream, the search box itself — so
   * "no chips" does not mean "nothing applied", and keying the escape hatch to the chips is how a
   * reader ends up on an empty list with no way off it. The caller knows which of its filters are
   * active; it passes `onClear` when any of them is.
   */
  onClear?: () => void;
  className?: string;
  'data-testid'?: string;
}

export function FilterStrip({
  search,
  pickers,
  applied,
  summary,
  onClear,
  className,
  ...rest
}: FilterStripProps) {
  const testId = rest['data-testid'] ?? 'Filters';
  return (
    <div
      data-testid={rest['data-testid']}
      className={cn('flex flex-wrap items-center gap-2 text-[12.5px]', className)}
    >
      {search}
      {pickers && (
        <Popover>
          <PopoverTrigger asChild>
            <Button variant="ghost" size="sm" data-testid={`${testId}.Open`}>
              <ListFilter aria-hidden className="size-3.5" />
              Filter
              {applied.length > 0 && (
                <span className="rounded-full bg-steel px-1.5 font-mono text-[10.5px] leading-[15px] text-rail">
                  {applied.length}
                </span>
              )}
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-[320px] p-3" data-testid={`${testId}.Popover`}>
            <div className="flex flex-col gap-2.5 [&_select]:w-full [&_[role=combobox]]:w-full">
              {pickers}
            </div>
            {summary && (
              <p className="mt-3 mb-0 border-t border-line pt-2 text-[11.5px] text-ink-3">{summary}</p>
            )}
          </PopoverContent>
        </Popover>
      )}
      {applied.map((filter) => (
        <Chip
          key={filter.key}
          onClick={filter.onRemove}
          onRemove={filter.onRemove}
          removeLabel={`Remove ${filter.label}`}
          data-testid={`${testId}.Applied.${filter.key}`}
        >
          {filter.label}
        </Chip>
      ))}
      {!pickers && summary && <span className="ml-auto tabular-nums text-ink-4">{summary}</span>}
      {onClear && (
        <Button variant="ghost" size="sm" onClick={onClear} data-testid={`${testId}.Clear`}>
          Clear filters
        </Button>
      )}
    </div>
  );
}
