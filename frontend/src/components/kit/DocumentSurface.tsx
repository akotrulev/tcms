import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * The four document screens' shared surface — design language §3: **74ch for a procedure** (case
 * editor, case detail, execution, proposal, import preview), **70ch for prose** (documentation,
 * doc editor, help). A person who learns one of the four has learned all four.
 */
export interface DocumentSurfaceProps {
  measure: 'procedure' | 'prose';
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}

export function DocumentSurface({ measure, children, className, ...rest }: DocumentSurfaceProps) {
  return (
    <div className={cn('min-w-0 overflow-auto bg-paper px-8 py-[26px]', className)} {...rest}>
      <div
        className={cn(
          measure === 'procedure'
            ? 'max-w-[74ch] text-[15px] leading-[1.75]'
            : 'max-w-[70ch] text-[14.5px] leading-[1.7] text-ink-2',
        )}
      >
        {children}
      </div>
    </div>
  );
}

export interface DocumentTitleProps {
  children: ReactNode;
  why?: ReactNode;
  whyLabel?: string;
  className?: string;
}

export function DocumentTitle({ children, why, whyLabel = 'Why', className }: DocumentTitleProps) {
  return (
    <div className={className}>
      <h1 className="m-0 mb-1.5 max-w-[36ch] text-2xl leading-[1.25] font-[620] tracking-[-.02em] text-balance">
        {children}
      </h1>
      {why && (
        <p className="m-0 mb-[22px] max-w-[62ch] text-[14.5px] leading-[1.6] text-ink-2">
          <span className="mb-[3px] block font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">
            {whyLabel}
          </span>
          {why}
        </p>
      )}
      <div className="my-[18px] h-px bg-white/8" />
    </div>
  );
}

/**
 * One line of a Gherkin-style procedure: a 26px mono right-aligned gutter number and a keyword
 * coloured by `--color-given/--color-when/--color-then` — the same hues the runner paints results
 * in, by design language rule 5.
 */
export type ProcedureKeyword = 'given' | 'when' | 'then' | 'and';

const KEYWORD_LABEL: Record<ProcedureKeyword, string> = {
  given: 'Given',
  when: 'When',
  then: 'Then',
  and: 'And',
};

const KEYWORD_COLOR: Record<ProcedureKeyword, string | undefined> = {
  given: 'var(--color-given)',
  when: 'var(--color-when)',
  then: 'var(--color-then)',
  and: undefined,
};

export interface ProcedureLineProps {
  line: number;
  keyword?: ProcedureKeyword;
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}

export function ProcedureLine({ line, keyword, children, className, ...rest }: ProcedureLineProps) {
  return (
    <div className={cn('grid grid-cols-[26px_1fr] gap-3.5 py-0.5', className)} {...rest}>
      <span className="pt-[5px] text-right font-mono text-[11px] tabular-nums text-ink-4">{line}</span>
      <span className="min-w-0">
        {keyword && (
          <span
            style={KEYWORD_COLOR[keyword] ? { color: KEYWORD_COLOR[keyword] } : undefined}
            className={cn('mr-[7px] font-mono text-sm font-medium', !KEYWORD_COLOR[keyword] && 'text-ink-3')}
          >
            {KEYWORD_LABEL[keyword]}
          </span>
        )}
        {children}
      </span>
    </div>
  );
}
