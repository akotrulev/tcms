import { describe, expect, it } from 'vitest'
import { formatMonth, formatMonthRange, monthContaining, nextMonth, previousMonth } from './months'

/**
 * `months.ts` — the picker's arithmetic, and the three edges a month gives it.
 *
 * `periods.test.ts` pins the same three for the fortnight one module over. What is being protected
 * is not the maths but the stepping: a picker that lands inside the month it started in is a
 * control that appears to do nothing, and a picker that skips one is worse — the reader sees August
 * become June and cannot tell whether the data or the label is wrong.
 */
describe('monthContaining', () => {
  it.each([
    ['2026-09-01', '2026-09-01', '2026-09-30'],
    ['2026-09-30', '2026-09-01', '2026-09-30'],
    ['2026-01-31', '2026-01-01', '2026-01-31'],
    // February asks the calendar rather than a table of lengths, so a leap year needs no case of
    // its own — but it gets one, because that is the assertion the table would have got wrong.
    ['2028-02-14', '2028-02-01', '2028-02-29'],
    ['2026-02-14', '2026-02-01', '2026-02-28'],
  ])('bounds %s as %s → %s', (date, startsOn, endsOn) => {
    expect(monthContaining(date)).toEqual({ startsOn, endsOn })
  })
})

describe('stepping', () => {
  it.each([
    ['2026-09-15', '2026-08-01'],
    // From the 31st of a long month. "Subtract thirty days" lands on the 1st of the SAME month.
    ['2026-03-31', '2026-02-01'],
    ['2026-01-05', '2025-12-01'],
  ])('steps back from %s to a day inside %s', (from, expected) => {
    expect(previousMonth(from)).toBe(expected)
    expect(monthContaining(previousMonth(from)).startsOn).toBe(expected)
  })

  it.each([
    ['2026-09-15', '2026-10-01'],
    ['2026-01-31', '2026-02-01'],
    ['2026-12-02', '2027-01-01'],
  ])('steps forward from %s to a day inside %s', (from, expected) => {
    expect(nextMonth(from)).toBe(expected)
  })

  it('round-trips, so the picker returns to where it started', () => {
    expect(monthContaining(nextMonth(previousMonth('2026-09-17'))).startsOn).toBe('2026-09-01')
  })
})

describe('formatMonth', () => {
  it.each([
    ['2026-09-17', 'long', 'September 2026'],
    // `Sept`, not `Sep` — the app asks `en-GB` for the month name, exactly as `periods.ts` does,
    // and that locale abbreviates September with four letters. Pinned rather than worked around:
    // the two pickers must read the same way, and matching them is worth more than three letters.
    ['2026-09-17', 'short', 'Sept 2026'],
    // The first of the month formatted in UTC. Local parsing west of Greenwich reads this as the
    // 31st of August and the label would say the previous month for fifteen hours a day.
    ['2026-09-01', 'long', 'September 2026'],
  ] as const)('renders %s %s as %s', (date, style, expected) => {
    expect(formatMonth(date, style)).toBe(expected)
  })
})

describe('formatMonthRange', () => {
  it('reads a whole month as one month name', () => {
    expect(formatMonthRange('2026-09-01', '2026-09-30')).toBe('1–30 September 2026')
  })

  it('names both months when a period crosses one — an invoice carries whatever it was drafted for', () => {
    // A range printed as `1–30 September` when it actually ran from August would be this screen
    // misreporting the work an invoice bills.
    expect(formatMonthRange('2026-08-16', '2026-09-15')).toBe('16 August – 15 September 2026')
  })

  it('names both years when a period crosses one', () => {
    expect(formatMonthRange('2025-12-16', '2026-01-15')).toBe('16 December 2025 – 15 January 2026')
  })
})
