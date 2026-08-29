import { ChevronDown } from 'lucide-react';
import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * One row of a detail or editor rail — the most-repeated control in the product.
 *
 * It exists to settle an argument the rail spec
 * (`docs/specs/2026-08-28-case-authoring-rail.md`) had with the render. The render drew these as
 * `div.kv` spans of plain text with one chevron and called the rail *"a read-only summary you
 * click to change"*, which is three named anti-patterns at once:
 *
 * - **UX#117 Compact Control Semantics (Critical)** — *"don't use a clickable div"*;
 * - **UX#62 Input Affordance** — a summary that is secretly a form;
 * - **UX#114 Compact Label Semantics** — these values are owned by the author, so they are chips,
 *   not badges.
 *
 * So every row is a real `<button>` with a native role, an accessible name, keyboard operation and
 * a visible focus ring. The failure that decides it: an author sets Automation to *Not
 * automatable*, the server refuses with *"Say why this case cannot be automated"*, and the field
 * carrying that error is inside something that reads as a summary.
 *
 * **And it is quiet at rest** — no filled background, no permanent border, value right-aligned,
 * border appearing on hover and focus. That half is equally load-bearing: what shipped before this
 * was the opposite failure, a rail of full outlined selects with a helper paragraph each, which
 * reads as the nine-control form the redesign retired.
 *
 * `readOnly` renders a non-interactive row for a value the author genuinely cannot change here —
 * inherited preconditions, a server-assigned id. It is not a disabled button: there is no action
 * behind it to enable.
 */
export interface RailRowProps {
  label: string;
  value: ReactNode;
  onOpen?: () => void;
  readOnly?: boolean;
  'data-testid'?: string;
}

const ROW =
  'flex w-full items-center justify-between gap-2.5 rounded-[5px] py-1 text-[13px] text-left min-h-7';

export function RailRow({ label, value, onOpen, readOnly, ...rest }: RailRowProps) {
  if (readOnly || !onOpen) {
    return (
      <div className={cn(ROW, 'opacity-[.72]')} {...rest}>
        <span className="shrink-0 text-ink-3">{label}</span>
        <span className="flex items-center gap-1.5 text-right font-normal text-ink">{value}</span>
      </div>
    );
  }

  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        ROW,
        '-mx-2 w-[calc(100%+1rem)] cursor-pointer border border-transparent px-2',
        'hover:border-white/14 hover:bg-white/3',
        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]',
      )}
      {...rest}
    >
      <span className="shrink-0 text-ink-3">{label}</span>
      <span className="flex items-center gap-1.5 text-right font-normal text-ink">
        {value}
        <ChevronDown aria-hidden className="size-3 shrink-0 text-ink-4" />
      </span>
    </button>
  );
}
