/**
 * The breakpoint contract — MUI's values adopted wholesale (responsive-design skill:
 * with a component library, ITS values are the set; never introduce a second system).
 *
 * This TS mirror exists for client components that must branch on width and for the
 * QA suite to import. Changing a value here means changing it in the MUI theme too.
 */
export const breakpoints = {
  xs: 0,
  sm: 600,
  md: 900,
  lg: 1200,
  xl: 1536,
} as const;

export type Breakpoint = keyof typeof breakpoints;
