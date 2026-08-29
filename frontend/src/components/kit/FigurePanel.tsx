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
      className={cn('min-w-0 rounded-lg border border-white/8 bg-panel px-4 py-3.5', className)}
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
