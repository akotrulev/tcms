import type { ReactNode } from 'react';
import { Banner } from '../../components/kit';
import { ScrollX } from '../../components/ScrollX';

/**
 * The prose primitives the help sections are written with.
 *
 * **Why a component set and not a markdown renderer.** The obvious shape for a help page is
 * markdown plus `react-markdown`, and it was refused on two counts. It is ~40 kB of parser and
 * sanitizer shipped to every reader for content that is fixed at build time and never
 * user-supplied, on a page whose entire job is to be cheap and always available; and it would put
 * the copy outside TypeScript, where a rename in `lib/permissions.ts` or a route change in
 * `router.tsx` cannot reach it. Sections are authored as TSX instead, so a link to a route that
 * stops existing is a compile error and every identifier on the page can come from the same
 * catalogue the rest of the app reads.
 *
 * These are deliberately thin. Anything a section needs that is more than a paragraph, a list or
 * a command block belongs in a real component beside `RoleMatrix` — not in another primitive here.
 *
 * **None of them carries a measure any more.** ~~`maxWidth: '78ch'` on every primitive~~ →
 * superseded by design language §3: help is the fourth document surface, and the measure is the
 * surface's — 70ch, set once by `kit/DocumentSurface`, so that a reader who has learnt a case, a
 * doc page or a proposal has already learnt this page. A per-primitive width would silently
 * out-vote it and give the same page two different columns depending on which primitive drew the
 * paragraph.
 */

/** A paragraph. */
export function P({ children }: { children: ReactNode }) {
  return <p className="mb-3 text-[14.5px] leading-[1.7] text-ink-2">{children}</p>;
}

/** A sub-heading inside a section. `h3`, because the section itself renders the `h2`. */
export function H({ children }: { children: ReactNode }) {
  return <h3 className="mt-7 mb-2 text-[18px] font-[620] tracking-[-.015em] text-ink">{children}</h3>;
}

/**
 * An unordered list, or a numbered one with `ordered`.
 *
 * Takes `ReactNode[]` rather than children so a section cannot accidentally nest a paragraph
 * inside a list item and get two different vertical rhythms on the same page.
 */
export function List({ items, ordered }: { items: ReactNode[]; ordered?: boolean }) {
  const className = 'mt-0 mb-4 pl-6 text-[14.5px] leading-[1.7] text-ink-2';
  const body = items.map((item, index) => (
    <li key={index} className="mb-1.5">
      {item}
    </li>
  ));

  return ordered ? (
    <ol className={`list-decimal ${className}`}>{body}</ol>
  ) : (
    <ul className={`list-disc ${className}`}>{body}</ul>
  );
}

/** Inline monospace, for a command, a path, an environment variable or a permission key. */
export function Code({ children }: { children: ReactNode }) {
  return (
    <code className="rounded-[3px] bg-wash-2 px-1 py-px font-mono text-[12.5px] whitespace-nowrap text-ink">
      {children}
    </code>
  );
}

/**
 * A block of commands or configuration, meant to be copied.
 *
 * Wrapped in `ScrollX` rather than wrapped soft: a broken command line is a command that fails
 * when pasted, and a wrapped JSON block hides which brace closed what. Long lines scroll.
 */
export function Terminal({ children }: { children: string }) {
  return (
    <div className="mb-4 rounded-[8px] border border-line bg-field">
      <ScrollX>
        <pre className="m-0 px-4 py-3 font-mono text-[12.5px] leading-[1.6] text-ink-2">{children}</pre>
      </ScrollX>
    </div>
  );
}

/** A callout for the one sentence in a section a reader must not skim past. */
export function Note({
  severity = 'info',
  children,
}: {
  severity?: 'info' | 'warning';
  children: ReactNode;
}) {
  return (
    <Banner tone={severity === 'warning' ? 'warn' : 'steel'} className="mb-4">
      <span className="leading-[1.6]">{children}</span>
    </Banner>
  );
}

/**
 * A term and what it means — the shape most of this page's reference material takes.
 *
 * A definition list rather than a table because the right-hand side is a sentence and the
 * left-hand side is one or two words: a two-column table gives that pairing a column width to
 * fight over at every breakpoint, and loses on a phone.
 */
export function Definitions({ items }: { items: { term: ReactNode; detail: ReactNode }[] }) {
  return (
    <dl className="m-0 mb-4 flex flex-col gap-2.5">
      {items.map((item, index) => (
        <div key={index}>
          <dt className="text-[14.5px] font-semibold text-ink">{item.term}</dt>
          <dd className="m-0 text-[14.5px] leading-[1.7] text-ink-3">{item.detail}</dd>
        </div>
      ))}
    </dl>
  );
}
