import type { ReactNode } from 'react';
import { cn } from '../lib/cn';

/**
 * The design signature.
 *
 * Every identifier in the product — case key, suite key, version, release, build, SHA,
 * percentage — renders in IBM Plex Mono with tabular figures. This is not decoration: it
 * makes `TC-42` and `TC-402` distinguishable at a glance, and it makes keys and numbers
 * align down a column so a scanning eye can compare them. The `.identifier` class in
 * index.css carries the font; this component is the one place that class is applied.
 *
 * ~~`sx`~~ → superseded by `className` in T16, when MUI went and `sx` had no engine behind it.
 * The five call sites that passed one were rewritten to utilities; `cn` merges them so a caller
 * can still override the variant's own size or colour, which is what `sx` was being used for.
 */
export function Identifier({
  children,
  variant = 'default',
  className,
  testId,
}: {
  children: ReactNode;
  /** `muted` for secondary identifiers (build numbers, SHAs); `strong` for the page subject. */
  variant?: 'default' | 'muted' | 'strong';
  className?: string;
  testId?: string;
}) {
  return (
    <span
      className={cn(
        'identifier whitespace-nowrap',
        variant === 'muted' && 'text-xs text-ink-2',
        variant === 'strong' && 'font-semibold text-ink',
        className,
      )}
      data-testid={testId}
    >
      {children}
    </span>
  );
}
