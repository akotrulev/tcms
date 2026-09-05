import { describe, expect, it } from 'vitest'
import { formatMoney, formatMoneyList, formatRate } from './money'

/**
 * `money.ts`, and the test that matters most is the one asserting a function **does not exist**.
 *
 * 019 D-l: every company-level total is a list of `Money`, one per currency, and there is no helper
 * anywhere in this feature that adds two of them. A test can only assert that negatively — by
 * pinning that the multi-currency helper returns a LIST and never a figure — so that is what the
 * last case here does. Somebody adding a `sumMoney` would not redden this file, but somebody
 * changing `formatMoneyList` to fold its input into one string would, which is the shape the
 * mistake actually takes.
 */
describe('formatMoney', () => {
  it.each([
    [0, 'EUR', '€0.00'],
    [6096, 'EUR', '€6,096.00'],
    // Cents are shown. An invoice's total is what a client owes, and `€6,096` for €6,096.42 is a
    // wrong number on the one screen whose whole subject is that figure.
    [6096.42, 'EUR', '€6,096.42'],
    // The separator between a code-style symbol and its figure is Intl's NON-BREAKING space, and
    // the expectation spells it as an escape rather than pasting one: a literal U+00A0 in a source
    // file is invisible, and a test that fails because somebody retyped it as a plain space is a
    // failure nobody can read.
    [12705, 'CHF', 'CHF\u00A012,705.00'],
  ])('formats %s %s as %s', (amount, currency, expected) => {
    expect(formatMoney({ amount, currency })).toBe(expected)
  })

  it('reads a null or missing figure as the em dash, never as zero', () => {
    // Zero is a claim that something was computed and came to nothing; absence is not that claim.
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined)).toBe('—')
  })

  it('falls back rather than throwing on a currency Intl will not accept', () => {
    /*
      `Intl.NumberFormat` raises a RangeError on a malformed code. A formatter that threw here would
      trip the region's error boundary and blank a whole panel — reporting a rendering failure for
      what is really three bad letters in one row.
    */
    expect(formatMoney({ amount: 12.5, currency: 'euros' })).toBe('euros 12.50')
  })
})

describe('formatRate', () => {
  it('puts the rate back together with its currency', () => {
    // A rate is the one figure in this feature that legitimately travels without its currency —
    // it is an input to a product, not a total — so exactly one place rejoins them.
    expect(formatRate(110, 'EUR')).toBe('€110.00 / h')
  })
})

describe('formatMoneyList', () => {
  it('answers one line per currency and never one figure (019 D-l)', () => {
    const lines = formatMoneyList([
      { amount: 52400, currency: 'EUR' },
      { amount: 12705, currency: 'CHF' },
    ])

    // Two lines, in the server's order. A single string here would be the cross-currency sum this
    // module is built to make impossible — wrong in both currencies, and unattributable later.
    expect(lines).toEqual(['€52,400.00', 'CHF\u00A012,705.00'])
  })

  it('answers an empty list for a company that has issued nothing', () => {
    // Not `['€0.00']`. The caller renders its own em dash, because "nothing issued" and "issued
    // nothing" are the same sentence in English and different claims on a tile.
    expect(formatMoneyList([])).toEqual([])
  })
})
