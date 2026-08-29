/**
 * The breakpoint contract — one set for the whole app, and this file is its source.
 *
 * ~~MUI's values adopted wholesale (responsive-design skill: with a component library, ITS
 * values are the set).~~ → the values are unchanged but the authority moved: T16 removed the
 * theme they were adopted from, so they are now the app's own rather than a library's, and this
 * module is where they are decided.
 *
 * It exists as TypeScript for client components that branch on width and for the QA suite to
 * import. `index.css`'s `@theme inline` restates the same four numbers so Tailwind's `md:` means
 * this `md`, and `app/tokenSystem.test.ts` fails if the two ever disagree — the pairing is
 * computed, not remembered.
 */
export const breakpoints = {
  xs: 0,
  sm: 600,
  md: 900,
  lg: 1200,
  xl: 1536,
} as const;

export type Breakpoint = keyof typeof breakpoints;
