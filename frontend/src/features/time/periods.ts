/**
 * The semi-monthly fortnight, on the client — for **navigation and labelling only** (016 D-a).
 *
 * ## What this is allowed to decide, and what it is not
 *
 * The server derives a period's real bounds from the `on` date it is sent, and `TimesheetPeriod`
 * carries `startsOn` / `endsOn` back. **Every range this app renders for a period it has loaded
 * comes from that payload**, never from the arithmetic below — which is the whole of D-a's "two
 * clients cannot disagree about where a period starts".
 *
 * What the payload cannot answer is *which date to ask for next*. Stepping the picker back a
 * fortnight means naming a day inside the previous one, and the approvals queue can be **empty** —
 * a fortnight nobody has logged into has no row and therefore no bounds to step from. So the rule
 * lives here too, in one place, with a test that pins the three edges the month gives it.
 *
 * The rule: `1–15`, and `16` to the end of the month. February is 16–28 or 16–29 and needs no case
 * of its own, because {@link endOfMonth} asks the calendar rather than a table of lengths.
 *
 * Dates are ISO `YYYY-MM-DD` strings throughout, and the arithmetic is done in **UTC**. A local
 * `new Date('2026-09-01')` is midnight UTC read in the viewer's zone, so west of Greenwich it is
 * the 31st of August — which would put a person in Denver on the wrong fortnight for the first
 * fifteen hours of every period. `format.ts` makes the same argument for `formatDueIn`.
 */

export interface PeriodBounds {
  startsOn: string;
  endsOn: string;
}

/** `YYYY-MM-DD` → the three numbers, with no `Date` in between to shift a zone. */
function partsOf(iso: string): { year: number; month: number; day: number } {
  const [year, month, day] = iso.split('-').map(Number);
  return { year: year ?? 1970, month: month ?? 1, day: day ?? 1 };
}

function iso(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** Day 0 of the following month is the last day of this one — the calendar's own answer. */
function endOfMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/** The fortnight a date falls in. */
export function periodContaining(date: string): PeriodBounds {
  const { year, month, day } = partsOf(date);
  return day <= 15
    ? { startsOn: iso(year, month, 1), endsOn: iso(year, month, 15) }
    : { startsOn: iso(year, month, 16), endsOn: iso(year, month, endOfMonth(year, month)) };
}

/**
 * A date inside the fortnight before the one containing `date` — the picker's step back.
 *
 * The day *before* this period's first, which is the last day of the previous one whether that is
 * the 15th or the 28th, 30th or 31st. Answering "subtract fifteen days" instead would land on the
 * 17th from a 1st in a 31-day month, which is the same period again.
 */
export function previousPeriod(date: string): string {
  const { startsOn } = periodContaining(date);
  const { year, month, day } = partsOf(startsOn);
  return isoOf(new Date(Date.UTC(year, month - 1, day - 1)));
}

/** A date inside the fortnight after the one containing `date` — the day after this period's last. */
export function nextPeriod(date: string): string {
  const { endsOn } = periodContaining(date);
  const { year, month, day } = partsOf(endsOn);
  return isoOf(new Date(Date.UTC(year, month - 1, day + 1)));
}

function isoOf(at: Date): string {
  return at.toISOString().slice(0, 10);
}

/** Today, in the viewer's own zone, as the `on` parameter spells it. */
export function todayIso(now: Date = new Date()): string {
  return iso(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

/**
 * `1–15 September 2026`, or `1–15 Sep` short — the mockup's heading and its picker.
 *
 * Takes the two ends rather than a date, so a loaded period is labelled from **its own payload**
 * and the arithmetic above never gets a say in what a reader is looking at.
 *
 * `en-GB` for the month, as the rest of the app is. The two days are joined with an en dash rather
 * than a hyphen because it is a range and not a compound, which is the mockup's own character.
 */
export function formatPeriodRange(
  startsOn: string,
  endsOn: string,
  style: 'long' | 'short' = 'long',
): string {
  const start = partsOf(startsOn);
  const end = partsOf(endsOn);
  const month = new Date(Date.UTC(end.year, end.month - 1, 1)).toLocaleDateString('en-GB', {
    month: style === 'long' ? 'long' : 'short',
    timeZone: 'UTC',
  });
  const range = `${start.day}–${end.day} ${month}`;
  return style === 'long' ? `${range} ${end.year}` : range;
}
