/**
 * The three fills every figure on this screen draws with, and the argument for there being three.
 *
 * <h2>This product has no categorical palette, by design</h2>
 *
 * `components/kit/Figure.tsx`'s header states it: the accents measured **2.5 ΔE apart under
 * deuteranopia** — indistinguishable — so a three-series chart *facets into three single-series
 * ones* rather than sharing an axis, and there is no pie, donut or gauge anywhere. `index.css`'s
 * viz block says the same thing from the other end: `--color-viz-1` and `--color-viz-2` are **one
 * analytic hue at two steps**, disjoint from status by chroma rather than by hue, because every
 * candidate categorical hue measured below the ΔE 15 normal-vision floor against a status colour.
 *
 * <h2>That rule was re-derived here rather than taken on trust, and it held</h2>
 *
 * `reports.html` draws the cumulative flow as a three-hue stack. Running the three tokens it would
 * need through the `dataviz` skill's validator against this app's own surfaces reproduced exactly
 * the failure `Figure.tsx` records:
 *
 * | Ramp | Adjacent ΔE (normal) | Lightest step vs surface |
 * |---|---|---|
 * | three steps of one hue, light | 9.2 at best inside the contrast band | 2.38:1 — below 3:1 |
 * | three steps of one hue, dark | 13.0 | 1.87:1 — below 3:1 |
 *
 * Three bands of one hue cannot be both separable and legible on this surface; three bands of three
 * hues cannot be separable under CVD at all. **So the flow is faceted** — one band per figure, each
 * a single series at full strength — which is what the kit prescribes and what the skill's own step
 * 1 (*pick the form first*) arrives at independently.
 *
 * <h2>Why these are strings and not utility classes</h2>
 *
 * They go into SVG `fill` and `stroke` attributes, which Tailwind's colour utilities do not reach.
 * Every one is a `var(--…)` and never a literal: `@theme inline` emits the variable rather than
 * copying its value, which is the whole mechanism that moves the palette on `[data-color-scheme]`.
 * A hex here is a figure that is correct in one scheme and unreadable in the other, and it
 * typechecks — `app/tokenSystem.test.ts` is the check for the rest of the app, and this file is the
 * SVG-shaped hole in it.
 */

/** The analytic hue at full strength — every single-series mark on this screen. */
export const VIZ_STRONG = 'var(--color-viz-1)';

/** Its lighter step. The burn-up's bounding line, which is a second mark and not a second series. */
export const VIZ_SOFT = 'var(--color-viz-2)';

/**
 * Absence of information — the flow's fourth band, and it is deliberately not a colour.
 *
 * It is the rule token, drawn through a 45° hatch. A fourth *hue* would say "a fourth status"; a
 * hatch says "nothing is recorded here", which is what the band actually means (020 D-d).
 */
export const VIZ_ABSENT = 'var(--color-line-strong)';

/** Grid lines and axis rules — recessive, per the skill's mark specs. */
export const VIZ_GRID = 'var(--color-line)';
