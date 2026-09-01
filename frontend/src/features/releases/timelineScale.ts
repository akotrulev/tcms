import type { RoadmapEpic, RoadmapRelease } from '../../api/types';

/**
 * The roadmap's horizontal scale — the one piece of arithmetic on `work-timeline.html` a reader
 * could get wrong twice, so it is a pure function with its own tests and not a component (D-o).
 *
 * ## Three span shapes, and the third one draws nothing
 *
 * A release has a **target date and no start**, which is why this file exists at all:
 *
 *  1. Epics under it carry start dates → the bar spans `min(epic.startDate) … targetDate`.
 *  2. No epic under it has a start → a **point marker** at the target, not a zero-width bar. A bar
 *     of no width is invisible and reads as a missing release rather than as a release with nothing
 *     scheduled inside it.
 *  3. **No target date at all** → `null`, and the row appears in version order with its epics and no
 *     bar of its own. That is what the mockup draws for `2.10`, and the release-new form promises it
 *     in as many words: *"Without one the release still exists, and shows on the timeline."*
 *
 * An epic's own bar is the same three shapes over `startDate … targetDate`, which is why
 * {@link spanOfEpic} is beside this rather than in the component: an epic with one date and not the
 * other is common, and the fallback has to be the date it *has*.
 *
 * ## Percentages, not pixels
 *
 * Every span is `{ left, width }` as a percentage of the window, because the track's width is a CSS
 * grid column and this file must not know it. `width` is `0` on a point marker; the caller draws a
 * marker rather than a bar when it sees one, which is a decision about affordance and stays there.
 */
export interface DateWindow {
  /** Inclusive, as `YYYY-MM-DD`. */
  from: string;
  /** Inclusive. */
  to: string;
}

export interface Span {
  /** Percent from the left edge of the window, clamped to `0…100`. */
  left: number;
  /** Percent of the window's width. `0` means a point marker at {@link left}. */
  width: number;
}

export function spanOf(release: RoadmapRelease, window: DateWindow): Span | null {
  if (!release.targetDate) return null;

  const starts = release.epics
    .map((epic) => epic.startDate)
    .filter((date): date is string => date !== null && date !== undefined);

  if (starts.length === 0) return pointAt(release.targetDate, window);

  const earliest = starts.reduce((min, date) => (date < min ? date : min), starts[0]);
  return between(earliest, release.targetDate, window);
}

/**
 * An epic's bar. Two dates, either of which may be absent — and an epic with **neither** is not
 * drawn here at all: it is in the roadmap's `unscheduled` lane, which is a list rather than a track
 * (D-o), so `null` from this function means the caller was handed a row it should not have drawn.
 */
export function spanOfEpic(epic: RoadmapEpic, window: DateWindow): Span | null {
  const { startDate, targetDate } = epic;
  if (!startDate && !targetDate) return null;
  if (!startDate) return pointAt(targetDate as string, window);
  if (!targetDate) return pointAt(startDate, window);
  return between(startDate, targetDate, window);
}

/**
 * The window the whole roadmap is drawn in — **derived from the data plus today**, never a fixed
 * six months.
 *
 * A project whose only release targets next year would otherwise draw an empty grid with everything
 * off the right edge. Today is always inside it, because the `today` marker is the reader's
 * reference point and a marker outside the window is a red line clamped to an edge, which reads as a
 * date rather than as "now".
 *
 * Both ends are rounded to a month boundary so the month scale above the track has whole columns,
 * and one month of padding is added on each side: a bar flush against the edge cannot be told from
 * one that continues past it.
 */
export function windowOf(roadmap: { releases: readonly RoadmapRelease[] }, today: string): DateWindow {
  const dates = [
    today,
    ...roadmap.releases.flatMap((release) => [
      release.targetDate,
      ...release.epics.flatMap((epic) => [epic.startDate, epic.targetDate]),
    ]),
  ].filter((date): date is string => date !== null && date !== undefined);

  const earliest = dates.reduce((min, date) => (date < min ? date : min), today);
  const latest = dates.reduce((max, date) => (date > max ? date : max), today);

  return { from: monthStart(earliest, -1), to: monthStart(latest, 2) };
}

/**
 * Every month the window covers, as the scale's column labels.
 *
 * `Jul`, `Aug` — the mockup's form, and short because a month column is ~60px wide at the density
 * the rest of the screen uses. The year is not repeated per column: the panel foot carries today's
 * full date, which is the one place a reader needs to know which year they are looking at.
 */
export function monthsIn(window: DateWindow): { key: string; label: string }[] {
  const months: { key: string; label: string }[] = [];
  const end = new Date(`${window.to}T00:00:00Z`);
  let cursor = new Date(`${monthStart(window.from, 0)}T00:00:00Z`);

  while (cursor < end) {
    months.push({
      key: cursor.toISOString().slice(0, 7),
      label: MONTH.format(cursor),
    });
    cursor = new Date(Date.UTC(cursor.getUTCFullYear(), cursor.getUTCMonth() + 1, 1));
  }
  return months;
}

/** Where today sits in the window, as a percentage — the red marker's only input. */
export function todayAt(window: DateWindow, today: string): number {
  return clamp(fraction(today, window) * 100);
}

/**
 * `en-US`, not the `en-GB` the rest of the app formats dates in, and the difference is the point:
 * `en-GB`'s short September is **`Sept`** — four characters — which breaks the mockup's `Jul Aug Sep`
 * scale and the column rhythm it sets. This is a scale label rather than a date a reader copies, so
 * the three-letter form wins.
 */
const MONTH = new Intl.DateTimeFormat('en-US', { month: 'short', timeZone: 'UTC' });

function between(from: string, to: string, window: DateWindow): Span {
  // A release whose earliest epic starts AFTER its target date is a real state — somebody moved the
  // target in — and drawing it as a negative width would flip the bar. It becomes a point marker at
  // the target, which is the date the release is actually about.
  if (from >= to) return pointAt(to, window);

  const left = clamp(fraction(from, window) * 100);
  const right = clamp(fraction(to, window) * 100);
  return { left, width: Math.max(right - left, MIN_WIDTH) };
}

/**
 * A bar narrower than this is a line nobody can aim at. Two days inside a nine-month window is
 * 0.7%, which at 900px is 6px — under the 24px minimum target `accessibility` asks for, and every
 * bar here is a link.
 */
const MIN_WIDTH = 1.5;

function pointAt(date: string, window: DateWindow): Span {
  return { left: clamp(fraction(date, window) * 100), width: 0 };
}

/** Where a date sits in the window, `0…1` before clamping. */
function fraction(date: string, window: DateWindow): number {
  const start = Date.parse(`${window.from}T00:00:00Z`);
  const end = Date.parse(`${window.to}T00:00:00Z`);
  if (end <= start) return 0;
  return (Date.parse(`${date}T00:00:00Z`) - start) / (end - start);
}

function clamp(percent: number): number {
  if (Number.isNaN(percent)) return 0;
  return Math.min(Math.max(percent, 0), 100);
}

/** The first of the month `offset` months from the one `date` falls in, as `YYYY-MM-DD`. */
function monthStart(date: string, offset: number): string {
  const at = new Date(`${date}T00:00:00Z`);
  return new Date(Date.UTC(at.getUTCFullYear(), at.getUTCMonth() + offset, 1))
    .toISOString()
    .slice(0, 10);
}
