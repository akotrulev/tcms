import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * The frame the two analytics screens draw a figure in — the render's `.fig`.
 *
 * It is the tenth thing in a nine-component kit for one reason: `Figure.tsx` beside it is the
 * *drawings* (`Tile`, `HBar`, `Sparkline`), and design language §5's "Figure" row is a drawing
 * plus the sentence that says what it is of. The subtitle is not decoration — every figure on
 * `06-analytics.html` states its denominator there (*"of approved cases"*, *"418 in scope"*,
 * *"one series · each point is a completed run"*), because a number whose divisor a reader has to
 * infer is a number two readers will read differently.
 *
 * It is deliberately **not** `Panel`: rule 1 forbids a card around the only thing on a screen, and
 * this is the opposite case — a grid of figures where the border is what separates one set of
 * numbers from the next.
 *
 * <h2>The 2026-08-30 renders audit disagreed, and this paragraph is the answer</h2>
 *
 * Finding 6.4 (Low) reads: *"Every figure on the Dashboard sits in a bordered panel. Rule 1 says no
 * panel around the subject of the page; on a screen that is nothing but figures, seven borders is
 * what rule 1 describes."* **Kept as it is**, because rule 1's subject is *the only thing on a
 * screen* and seven figures are seven things: what the rule is protecting against is a card drawn
 * around a page's single subject, which announces a boundary the reader already knows. Here the
 * boundary is the information — where one denominator stops and the next begins — and the
 * dashboard is the one screen in the product where two adjacent sets of numbers mean different
 * things and look identical.
 *
 * The audit's own evidence is on the other side of this: 6.2 found two of these figures shipping
 * with no legend, which is a reader unable to tell one set of numbers from another *inside* one
 * panel. Removing the frames between them would make more of that, not less.
 *
 * The case detail's four carded blocks (2.4) were a real instance of the finding and were fixed —
 * there the card sat beside uncarded siblings in one column, which is two idioms for one kind of
 * thing. Nothing on this screen is uncarded, so nothing here disagrees with anything.
 */
export interface FigurePanelProps {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  testId?: string;
}

export function FigurePanel({ title, subtitle, actions, children, className, testId }: FigurePanelProps) {
  return (
    <section
      data-testid={testId}
      className={cn('min-w-0 rounded-xl border border-line bg-panel px-4 py-3.5', className)}
    >
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-[13px] font-semibold tracking-[-.01em] text-ink">{title}</h2>
          {subtitle && <p className="mt-[3px] text-[12px] text-ink-4">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-1.5">{actions}</div>}
      </div>
      {children}
    </section>
  );
}
