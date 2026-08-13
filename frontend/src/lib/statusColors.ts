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
import type { HealthSignalSeverity, LifecycleStatus, StatusKind } from '../api/types';

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

/**
 * How loudly a health signal reads — **a map over the server's four severities, not over the ten
 * signals**, and the only one in the SPA (§183).
 *
 * It was two. `StatusChip.tsx` held one for the case chip and `DashboardPage.tsx` a private
 * `severityColor` for the tile's left border; four keys each, three values agreeing and **INFO
 * differing** — amber on the chip, blue on the tile. So one severity had two colours, the same
 * signal read as a warning in the grid and as information on the dashboard, and neither suite
 * asserted a colour on the tile at all. That is `SEVERE_SIGNALS` (§179) and `DashboardBreakdown`'s
 * server-composed `color` (§180) a third time: one mapping, two mechanisms, compared by nothing.
 * The rule those two slices leave is that the second copy is the defect, so there is one table.
 *
 * **INFO is `--color-info`, and that is the reading-plan decision §179 recorded and deferred.**
 * Amber had been carrying two meanings — *something is wrong now* and *worth knowing* — and once
 * §178, §181 and §182 gave `UNLINKED`, `ORPHANED` and `INCOMPLETE` writers, three of the ten
 * signals were INFO and one of them fires on any case with no spec link. Most rows of a young
 * project carried an amber chip, which spends the colour that is supposed to mean triage-this on
 * ordinary state. Blue is not "quieter" by being fainter — it is a *different* claim, which is what
 * a reader scanning the Health column needs: amber and red are work, blue is context. Fainter was
 * the other candidate and it is wrong here, because `--color-text-muted` is what NEUTRAL uses to
 * say "this is not a signal", and an INFO chip is one.
 *
 * `NEUTRAL` never reaches a chip — it is the dashboard's non-signal tiles — and is mapped so the
 * exhaustiveness check has something to be exhaustive about. A new severity on the wire is a
 * compile error here rather than a chip with no colour.
 */
export const severityVar: Record<HealthSignalSeverity, string> = {
  ERROR: 'var(--color-failed)',
  WARNING: 'var(--color-blocked)',
  INFO: 'var(--color-info)',
  NEUTRAL: 'var(--color-text-muted)',
};
