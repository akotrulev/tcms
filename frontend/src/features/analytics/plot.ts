/**
 * The geometry every figure on this screen shares — a viewBox, a scale, and two path builders.
 *
 * Kept out of the components because all three figures draw the same shapes over different series,
 * and three copies of "map a value to a y" is how two of them come to disagree about where zero is.
 * Nothing here knows what a burn-up or a flow *is*.
 */

/** The drawing box. Fixed units; the `<svg>` scales to its container through `width="100%"`. */
export const PLOT = {
  width: 520,
  height: 200,
  /** Room for the y labels on the left and the x labels underneath. */
  left: 34,
  right: 8,
  top: 10,
  bottom: 22,
} as const;

export const INNER_WIDTH = PLOT.width - PLOT.left - PLOT.right;
export const INNER_HEIGHT = PLOT.height - PLOT.top - PLOT.bottom;

/**
 * A scale from a series' values to plot coordinates.
 *
 * The domain always starts at zero and never at the data's minimum — a chart of counts whose axis
 * starts at 14 exaggerates every change on it, which is the truncated-axis anti-pattern. The
 * maximum is floored at 1 so a series of all zeroes draws a flat line at the bottom rather than
 * dividing by zero.
 */
export function scale(values: readonly number[], count: number) {
  const max = Math.max(1, ...values);
  return {
    max,
    /** The x of point `index`, with a single point placed at the left edge rather than at NaN. */
    x: (index: number) =>
      PLOT.left + (count <= 1 ? 0 : (index / (count - 1)) * INNER_WIDTH),
    y: (value: number) => PLOT.top + INNER_HEIGHT - (value / max) * INNER_HEIGHT,
  };
}

export type Scale = ReturnType<typeof scale>;

/** A polyline through every point, as an SVG path. */
export function linePath(values: readonly number[], s: Scale): string {
  return values
    .map((value, index) => `${index === 0 ? 'M' : 'L'}${s.x(index)} ${s.y(value)}`)
    .join(' ');
}

/** The same polyline closed down to the baseline, for a filled area. */
export function areaPath(values: readonly number[], s: Scale): string {
  if (values.length === 0) return '';
  const baseline = PLOT.top + INNER_HEIGHT;
  const last = values.length - 1;
  return `${linePath(values, s)} L${s.x(last)} ${baseline} L${s.x(0)} ${baseline} Z`;
}

/**
 * Up to `count` evenly spaced indices, always including the first and the last.
 *
 * The x axis is labelled at these and nowhere else — the skill's rule is selective direct labels,
 * never a number on every point, and a weekly series over 26 weeks would otherwise collide with
 * itself.
 */
export function ticks(length: number, count = 3): number[] {
  if (length <= count) return Array.from({ length }, (_, index) => index);
  const step = (length - 1) / (count - 1);
  return Array.from({ length: count }, (_, index) => Math.round(index * step));
}

/** `2026-09-02` → `2 Sep`, the mockup's axis format. */
export function axisDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}
