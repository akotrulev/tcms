import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * The two-column threshold, ported from `docs/specs/assets/renders/08-threshold.html` (`.thr`).
 *
 * Family 8 is the only place in the product with no rail, no project and no session, and the
 * render's rule for it is a pair: **more identity than anywhere inside** — hence the story column,
 * which is the only marketing surface in the app and the one place the instance names itself — and
 * **fewer controls than anywhere inside**, which is why this component takes no actions of its own
 * and the form column is whatever the screen puts in it.
 *
 * Two deliberate divergences from the drawing:
 *
 * **The gradient is written in tokens, not in the render's three literal hexes.** The renders are
 * dark-only; plan 007 D1 keeps the app's light theme and derives it. `linear-gradient(150deg,
 * #0c1119, #11161e 55%, #0e1a20)` frozen into a class would have been a dark panel behind light
 * text the moment the toggle moved.
 *
 * **It stacks below `md` rather than holding two columns.** The renders were opened at 1680px only
 * (design language §0), so they make no claim here; a 380px form column beside anything at 600px
 * is not one.
 */
export interface ThresholdLayoutProps {
  /** The instance's own sentence — the largest type on the screen. */
  headline: ReactNode;
  /** Two sentences at most, under the headline. */
  blurb?: ReactNode;
  /** The mono line at the foot of the story column: which instance this is. */
  footnote: ReactNode;
  /** The form column. */
  children: ReactNode;
  className?: string;
}

export function ThresholdLayout({ headline, blurb, footnote, children, className }: ThresholdLayoutProps) {
  return (
    <main
      className={cn(
        'grid min-h-full bg-ground text-ink md:grid-cols-[minmax(0,1fr)_380px]',
        className,
      )}
    >
      <div
        className={
          'flex flex-col justify-between gap-8 border-b border-white/8 px-6 py-8 ' +
          'bg-[linear-gradient(150deg,var(--color-ground),var(--color-paper)_55%,var(--color-panel))] ' +
          'md:border-b-0 md:border-r md:px-[46px] md:py-11'
        }
      >
        <span className="font-mono text-xs uppercase tracking-[.14em] text-steel">TCMS</span>
        <div>
          <h2 className="m-0 mb-3 max-w-[22ch] text-[26px] font-[620] leading-[1.25] tracking-[-.022em] text-balance">
            {headline}
          </h2>
          {blurb && <p className="m-0 max-w-[44ch] text-sm leading-[1.6] text-ink-3">{blurb}</p>}
        </div>
        <p className="m-0 font-mono text-[11px] text-ink-4">{footnote}</p>
      </div>

      <div className="flex flex-col justify-center bg-panel px-6 py-10 md:px-9 md:py-11">
        {children}
      </div>
    </main>
  );
}

/** `.thr .form h3` — the form column's own heading, and the page's `<h1>`. */
export function ThresholdTitle({ children }: { children: ReactNode }) {
  return (
    <h1 className="m-0 mb-[5px] text-[19px] font-semibold tracking-[-.015em] text-ink">{children}</h1>
  );
}

/** `.thr .form .lead` — one line under the title saying what to do. */
export function ThresholdLead({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn('m-0 mb-6 text-[13px] text-ink-3', className)}>{children}</p>;
}

/** `label.f` — mono, uppercase, quiet: a field label is a column head, not a sentence. */
export function FieldLabel({ htmlFor, children }: { htmlFor: string; children: ReactNode }) {
  return (
    <label
      htmlFor={htmlFor}
      className="mb-[5px] block font-mono text-[11px] uppercase tracking-[.06em] text-ink-4"
    >
      {children}
    </label>
  );
}

/** `.err` — a field's refusal, red, with the icon the render puts in front of it. */
export function FieldError({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <p
      id={id}
      role="alert"
      className="mt-[5px] flex items-start gap-1.5 text-xs text-[color:var(--color-failed)]"
    >
      <svg aria-hidden viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} className="mt-px size-[13px] shrink-0">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 8v5M12 16v.01" />
      </svg>
      <span>{children}</span>
    </p>
  );
}

/** `.help` — the quiet line under a field that is not an error. */
export function FieldHelp({ id, children }: { id?: string; children: ReactNode }) {
  return <p id={id} className="mt-[5px] text-xs leading-[1.5] text-ink-4">{children}</p>;
}

/** `.or` — the mono rule between the password path and the buttons that skip it. */
export function OrRule({ children = 'or' }: { children?: ReactNode }) {
  return (
    <div className="my-5 flex items-center gap-[11px] font-mono text-[11.5px] uppercase tracking-[.06em] text-ink-4">
      <span aria-hidden className="h-px flex-1 bg-white/8" />
      {children}
      <span aria-hidden className="h-px flex-1 bg-white/8" />
    </div>
  );
}

/** `.quiet` — the sentence under the form. Small, faint, and the last thing read. */
export function ThresholdFootnote({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn('m-0 text-xs leading-[1.55] text-ink-4', className)}>{children}</p>;
}
