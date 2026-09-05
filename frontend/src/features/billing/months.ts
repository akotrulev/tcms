/**
 * The calendar month, on the client — for **navigation and the draft's default period only**.
 *
 * `features/time/periods.ts` makes the same argument for the semi-monthly fortnight and this file
 * is deliberately its neighbour rather than an addition to it: a timesheet period is the server's
 * to define (016 D-a) and this is not a period at all. `billing.html`'s picker reads *September
 * 2026*, `GET /billing/summary` takes any day inside the month it should compute over, and
 * `POST /invoices` takes an explicit `periodStart` / `periodEnd` — so the month is a **question**
 * the client asks, never an answer it renders back from arithmetic.
 *
 * The one rule that follows: nothing here labels a figure the server has already bounded. An
 * invoice's own period is drawn from `periodStart` / `periodEnd` on its payload, exactly as a
 * timesheet's range is drawn from `startsOn` / `endsOn` on its own.
 *
 * Dates are ISO `YYYY-MM-DD` and the arithmetic is done **in UTC**, for `periods.ts`' recorded
 * reason: a local `new Date('2026-09-01')` is midnight UTC read in the viewer's zone, so west of
 * Greenwich it is the 31st of August and a person in Denver would spend the first fifteen hours of
 * every month looking at the previous one.
 */

export interface MonthBounds {
  /** The first day of the month, `YYYY-MM-DD`. */
  startsOn: string;
  /** The last day of the month — the calendar's own answer, so February needs no case. */
  endsOn: string;
}

function partsOf(iso: string): { year: number; month: number; day: number } {
  const [year, month, day] = iso.split('-').map(Number);
  return { year: year ?? 1970, month: month ?? 1, day: day ?? 1 };
}

function iso(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** Day 0 of the following month is the last day of this one — asked of the calendar, not a table. */
function endOfMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/** The month a date falls in. */
export function monthContaining(date: string): MonthBounds {
  const { year, month } = partsOf(date);
  return { startsOn: iso(year, month, 1), endsOn: iso(year, month, endOfMonth(year, month)) };
}

/**
 * A date inside the month before the one containing `date` — the picker's step back.
 *
 * The day *before* this month's first, which is the last day of the previous one whether that is
 * the 28th, 29th, 30th or 31st. "Subtract thirty days" would land inside the same month from the
 * 31st of a long one, and inside the month before last from the 1st of March.
 */
export function previousMonth(date: string): string {
  const { startsOn } = monthContaining(date);
  const { year, month } = partsOf(startsOn);
  return month === 1 ? iso(year - 1, 12, 1) : iso(year, month - 1, 1);
}

/** A date inside the month after the one containing `date` — the picker's step forward. */
export function nextMonth(date: string): string {
  const { startsOn } = monthContaining(date);
  const { year, month } = partsOf(startsOn);
  return month === 12 ? iso(year + 1, 1, 1) : iso(year, month + 1, 1);
}

/**
 * `September 2026`, or `Sep 2026` short — the picker's label and the header's subtitle.
 *
 * `en-GB` for the month name, as the rest of the app is, and formatted in UTC so the name cannot
 * disagree with the bounds computed above.
 */
export function formatMonth(date: string, style: 'long' | 'short' = 'long'): string {
  const { year, month } = partsOf(date);
  const name = new Date(Date.UTC(year, month - 1, 1)).toLocaleDateString('en-GB', {
    month: style === 'long' ? 'long' : 'short',
    timeZone: 'UTC',
  });
  return `${name} ${year}`;
}

/**
 * `1–30 September 2026` — a bounded period, for the draft dialog's confirmation of what it will
 * ask for.
 *
 * Takes the two ends rather than a date, so an invoice that has been loaded is labelled from **its
 * own payload** and the arithmetic above never gets a say in what a reader is looking at. That is
 * `formatPeriodRange`'s rule one module over, restated because it is the one this file is most
 * likely to be misused against.
 *
 * **It does not assume the two ends share a month.** The picker only ever produces a range that
 * does, but an invoice loaded from the server carries whatever period it was drafted for — and a
 * range printed as `1–30 September` when it actually ran from August would be this screen
 * misreporting the work an invoice bills.
 */
export function formatMonthRange(startsOn: string, endsOn: string): string {
  const start = partsOf(startsOn);
  const end = partsOf(endsOn);
  const sameMonth = start.year === end.year && start.month === end.month;
  const endLabel = `${end.day} ${monthName(end.year, end.month)} ${end.year}`;
  if (sameMonth) return `${start.day}–${endLabel}`;
  const startLabel =
    start.year === end.year
      ? `${start.day} ${monthName(start.year, start.month)}`
      : `${start.day} ${monthName(start.year, start.month)} ${start.year}`;
  return `${startLabel} – ${endLabel}`;
}

function monthName(year: number, month: number): string {
  return new Date(Date.UTC(year, month - 1, 1)).toLocaleDateString('en-GB', {
    month: 'long',
    timeZone: 'UTC',
  });
}
