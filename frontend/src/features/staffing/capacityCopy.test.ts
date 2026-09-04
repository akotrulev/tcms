import { describe, expect, it } from 'vitest'
import {
  NO_FIGURE,
  NO_PROFILE,
  UTILISATION_BASIS,
  capacityDetail,
  capacityLabel,
  dayFractionLabel,
  freeCapacityLabel,
  hasProfile,
  overAllocationLabel,
  utilisationLabel,
} from './capacityCopy'
import { aPersonCapacity, aSelfCapacity } from '../../test/fixtures'

/**
 * The three states a capacity figure can be in, and the two that look identical if written per
 * screen.
 *
 * Every assertion here is about the difference between **absent** and **zero**. `0h` says somebody
 * recorded zero contracted hours; `0%` says a person is idle. Neither is what a null means, and
 * both are what a `?? 0` turns a null into.
 */
describe('capacityLabel', () => {
  it('is an em dash when there is no profile, whatever the hours say', () => {
    // The five nulls arrive together, but a caller that trusted only `hours` would print a stale
    // figure the moment one of them did not. `hasProfile` is the answer, not the hours.
    expect(capacityLabel(null, false)).toBe(NO_FIGURE)
    expect(capacityLabel(64, false)).toBe(NO_FIGURE)
  })

  it('is an em dash when the profile exists and the figure does not', () => {
    expect(capacityLabel(null, true)).toBe(NO_FIGURE)
  })

  it('never renders a null as 0h — zero is a different claim', () => {
    expect(capacityLabel(null, true)).not.toBe('0h')
    // …and a real zero still reads as one, because somebody contracted for no hours is a fact.
    expect(capacityLabel(0, true)).toBe('0h')
  })

  it('spells hours the product’s one way', () => {
    expect(capacityLabel(64, true)).toBe('64h')
    expect(capacityLabel(7.5, true)).toBe('7.5h')
  })
})

describe('capacityDetail', () => {
  it('says no profile recorded rather than leaving the em dash unexplained', () => {
    expect(capacityDetail(false)).toBe(NO_PROFILE)
    expect(capacityDetail(false)).toBe('no profile recorded')
  })

  it('says the figure is already net of absence, so nothing subtracts it twice', () => {
    expect(capacityDetail(true)).toBe('net of absence')
  })
})

describe('utilisationLabel', () => {
  it('reads “not allocated” for null and never 0%', () => {
    // 017 D-i: 0% would say the person is idle; the true statement is that nobody planned
    // their time, and those are two different things to go and fix.
    expect(utilisationLabel(null)).toBe('not allocated')
    expect(utilisationLabel(null)).not.toBe('0%')
  })

  it('renders a real zero as 0%, because somebody allocated and nothing was approved', () => {
    expect(utilisationLabel(0)).toBe('0%')
  })

  it('names its denominator, as every other time figure in this product does', () => {
    expect(UTILISATION_BASIS).toBe('approved hours ÷ allocated capacity')
  })
})

describe('overAllocationLabel', () => {
  it.each([
    [100, false, null],
    [101, true, '101%'],
    [130, true, '130%'],
  ])('reads %i%% / flagged=%s as %s', (peakPercent, overAllocated, expected) => {
    expect(overAllocationLabel({ peakPercent, overAllocated })).toBe(expected)
  })

  it('reads the served flag and does not re-derive the threshold', () => {
    // The peak is over 100 and the server says it is not a flag. That combination is unreachable
    // today and is exactly what a client-side `peakPercent > 100` would silently override the day
    // the rule moves — so the assertion is that this function believes the server.
    expect(overAllocationLabel({ peakPercent: 130, overAllocated: false })).toBeNull()
  })
})

describe('freeCapacityLabel', () => {
  it('reads “no free capacity” at zero rather than 0% free', () => {
    expect(freeCapacityLabel(0)).toBe('no free capacity')
  })

  it('reads the percentage otherwise', () => {
    expect(freeCapacityLabel(60)).toBe('60% free')
  })
})

describe('hasProfile', () => {
  it('reads PersonCapacity through its profile and SelfCapacity through its flag', () => {
    // The two payloads answer the same question with different fields, which is the reason this
    // helper exists: `SelfCapacity` carries `hasProfile` explicitly so a screen can say *no
    // profile recorded* instead of *failed to load*, and `PersonCapacity` carries the profile.
    expect(hasProfile(aPersonCapacity())).toBe(true)
    expect(hasProfile(aPersonCapacity({ profile: null }))).toBe(false)
    expect(hasProfile(aSelfCapacity())).toBe(true)
    expect(hasProfile(aSelfCapacity({ hasProfile: false, capacityHours: null }))).toBe(false)
  })
})

describe('dayFractionLabel', () => {
  it.each([
    [1, 'full day'],
    [0.5, 'half day'],
    [0.25, '25% of each day'],
  ])('reads %s as %s', (fraction, expected) => {
    // 017 D-d: `dayFraction` is a number rather than a boolean, so `member.html`'s *half day* is a
    // value and not a second table. The third reading is a percentage rather than a decimal because
    // `0.25 day` reads as a quantity of days and this is a share of every day in the range.
    expect(dayFractionLabel(fraction)).toBe(expected)
  })
})
