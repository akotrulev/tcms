/**
 * Enum → CSS custom property maps: the colour half of what `labels.ts` is the word half of.
 *
 * `Record<Union, string>` is the same device as `satisfies Record<Union, string>` next door — a new
 * member of either enum is a compile error here until it has a colour, rather than a chip that
 * renders transparent.
 *
 * **The values are custom-property *names*, never hex.** The browser resolves them against whatever
 * theme is active, so a status keeps its meaning in dark mode; `index.css` holds both palettes.
 *
 * **Why these are in `lib/` rather than beside the chips that draw them (§180).** They were private
 * to `StatusChip.tsx` until the dashboard needed the lifecycle palette, and the dashboard needed it
 * because the *server* had been sending it: `DashboardBreakdown`'s segments carried a `color` that
 * `DashboardService` composed as `"var(--color-" + status.name().toLowerCase() + ")"`. Two
 * mechanisms, one mapping, in two stacks, compared by nothing and tested on neither side — §179's
 * `SEVERE_SIGNALS` finding at a colour. The wire stopped sending it; this is the one table left.
 */
import type { LifecycleStatus, StatusKind } from '../api/types';

/** Execution outcome. Load-bearing data, which is why the brand palette stays out of it. */
export const statusVar: Record<StatusKind, string> = {
  PASSED: 'var(--color-passed)',
  FAILED: 'var(--color-failed)',
  BLOCKED: 'var(--color-blocked)',
  SKIPPED: 'var(--color-skipped)',
  RETEST: 'var(--color-retest)',
  UNTESTED: 'var(--color-untested)',
  WIP: 'var(--color-wip)',
};

/** Authoring lifecycle — the chip on a case or suite, and the dashboard's stacked bars. */
export const lifecycleVar: Record<LifecycleStatus, string> = {
  DRAFT: 'var(--color-draft)',
  IN_REVIEW: 'var(--color-in-review)',
  APPROVED: 'var(--color-approved)',
  DEPRECATED: 'var(--color-deprecated)',
};
