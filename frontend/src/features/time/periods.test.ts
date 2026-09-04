import { describe, expect, it } from 'vitest'
import {
  formatPeriodRange,
  nextPeriod,
  periodContaining,
  previousPeriod,
  todayIso,
} from './periods'

/**
 * D-a's arithmetic, and the three edges the month gives it.
 *
 * Every case here is one a "subtract fifteen days" implementation gets wrong, which is the reason
 * the module does the work rather than the call sites: stepping back from the 1st of a 31-day month
 * lands on the 17th, and stepping back from the 16th of March lands on the 1st — the same period
 * both times.
 */

describe('the semi-monthly fortnight', () => {
  it.each([
    ['2026-09-01', '2026-09-01', '2026-09-15'],
    ['2026-09-15', '2026-09-01', '2026-09-15'],
    ['2026-09-16', '2026-09-16', '2026-09-30'],
    ['2026-08-31', '2026-08-16', '2026-08-31'],
    // February needs no case of its own in the source, and this is why: the end comes from the
    // calendar. 2028 is the leap year the next one after it is not.
    ['2027-02-20', '2027-02-16', '2027-02-28'],
    ['2028-02-20', '2028-02-16', '2028-02-29'],
  ])('puts %s in %s–%s', (date, startsOn, endsOn) => {
    expect(periodContaining(date)).toEqual({ startsOn, endsOn })
  })

  it.each([
    // The step a naive "minus fifteen days" gets wrong in both directions.
    ['2026-09-01', '2026-08-16', '2026-08-31'],
    ['2026-09-20', '2026-09-01', '2026-09-15'],
    ['2026-03-16', '2026-03-01', '2026-03-15'],
    ['2026-01-05', '2025-12-16', '2025-12-31'],
  ])('steps back from %s into %s–%s', (date, startsOn, endsOn) => {
    expect(periodContaining(previousPeriod(date))).toEqual({ startsOn, endsOn })
  })

  it.each([
    ['2026-09-15', '2026-09-16', '2026-09-30'],
    ['2026-09-16', '2026-10-01', '2026-10-15'],
    ['2026-12-20', '2027-01-01', '2027-01-15'],
    ['2028-02-29', '2028-03-01', '2028-03-15'],
  ])('steps forward from %s into %s–%s', (date, startsOn, endsOn) => {
    expect(periodContaining(nextPeriod(date))).toEqual({ startsOn, endsOn })
  })

  it('reads today in the viewer’s own zone, not UTC', () => {
    // 23:30 on the 15th, local. Read as UTC in a zone ahead of Greenwich this is the 16th, which
    // would open the screen on the wrong fortnight for the last half-hour of every period.
    const late = new Date(2026, 8, 15, 23, 30)

    expect(todayIso(late)).toBe('2026-09-15')
  })
})

describe('the period label', () => {
  it('writes the mockup’s heading', () => {
    expect(formatPeriodRange('2026-09-01', '2026-09-15')).toBe('1–15 September 2026')
  })

  it('writes the mockup’s picker', () => {
    expect(formatPeriodRange('2026-08-16', '2026-08-31', 'short')).toBe('16–31 Aug')
  })
})
