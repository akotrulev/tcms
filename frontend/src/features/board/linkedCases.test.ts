import { describe, expect, it } from 'vitest'
import { aLinkedCasesSummary } from '../../test/fixtures'
import { describeLinkedCases, sumLinkedCases } from './linkedCases'

/**
 * 021 §2.7 and render note 22 — the three zeroes.
 *
 * Every case here is a `0` that typechecks identically to the other two and means something a
 * tester would act on differently. There is no rendering involved, which is why this is a
 * `.test.ts`: the whole risk is in the arithmetic and the words, and a component test would prove
 * the same thing through three layers of markup.
 */
describe('The linked-cases line', () => {
  it('answers nothing at all for a summary the server has not served', () => {
    /*
      **The distinction this whole file exists for.** `linkedCases` is `null` on every card from a
      backend older than 021 T4, and the key is always present — so "absent" is not a signal. A
      `?? 0` here would put `0/0 passing`, or worse `no cases linked`, on every card of an instance
      that simply has not been upgraded: a claim about test coverage that nobody made.
    */
    expect(describeLinkedCases(null)).toBeNull()
  })

  it('says none are linked rather than none are passing when the card has no cases', () => {
    // `0/0 passing` reads as a failure of the cases; there are none to fail.
    expect(describeLinkedCases(aLinkedCasesSummary({ total: 0, passing: 0, failing: 0, blocked: 0, notRun: 0 })))
      .toEqual({ headline: 'no cases linked', tone: 'untested', blocked: 0 })
  })

  it('reads 0/6 run, never 0/6 passing, when nothing has been executed (render note 22)', () => {
    const line = describeLinkedCases(
      aLinkedCasesSummary({ total: 6, passing: 0, failing: 0, blocked: 0, notRun: 6 }),
    )

    expect(line?.headline).toBe('0/6 run')
    // The tone matters as much as the words: a red chip beside "0/6" is the accusation the words
    // were written to avoid, and it is what a reader takes in first.
    expect(line?.tone).toBe('untested')
  })

  it('still reads as not run when every linked case is BLOCKED', () => {
    /*
      A blocked case was never executed, so it is not a verdict — and this is the case where the
      distinction is load-bearing rather than pedantic: five blocked cases with `passing: 0` would
      read `0/5 passing` under any rule that counted blocked as judged, which says the feature is
      broken when what happened is that nobody could test it.
    */
    const line = describeLinkedCases(
      aLinkedCasesSummary({ total: 5, passing: 0, failing: 0, blocked: 5, notRun: 0 }),
    )

    expect(line).toEqual({ headline: '0/5 run', tone: 'untested', blocked: 5 })
  })

  it('counts only executed cases in the run ratio, so blocked and never-run are both outside it', () => {
    // Two of six have a verdict? No — none do. One was run and blocked out, three were never
    // picked up. `run` is total − notRun − blocked, which is what the reader can act on.
    const line = describeLinkedCases(
      aLinkedCasesSummary({ total: 6, passing: 0, failing: 0, blocked: 2, notRun: 4 }),
    )

    expect(line?.headline).toBe('0/6 run')
  })

  it('reports the passing ratio and keeps blocked as its own count', () => {
    const line = describeLinkedCases(aLinkedCasesSummary())

    // `4/6 passing` and `1 blocked` — the render's two chips. Folding the blocked case into the
    // ratio would move it into a verdict it never got.
    expect(line).toEqual({ headline: '4/6 passing', tone: 'failed', blocked: 1 })
  })

  it('is green only when every linked case passed', () => {
    expect(
      describeLinkedCases(
        aLinkedCasesSummary({ total: 9, passing: 9, failing: 0, blocked: 0, notRun: 0 }),
      )?.tone,
    ).toBe('passed')

    // 5/6 with one still to run is not a green card, and not a red one either.
    expect(
      describeLinkedCases(
        aLinkedCasesSummary({ total: 6, passing: 5, failing: 0, blocked: 0, notRun: 1 }),
      )?.tone,
    ).toBe('untested')
  })

  it('turns red on any failure, however many pass beside it', () => {
    expect(
      describeLinkedCases(
        aLinkedCasesSummary({ total: 20, passing: 19, failing: 1, blocked: 0, notRun: 0 }),
      )?.tone,
    ).toBe('failed')
  })
})

describe('A column’s summed linked cases (023 D8)', () => {
  it('answers null when no card was served a summary, so the header draws no row', () => {
    expect(sumLinkedCases([])).toBeNull()
    expect(sumLinkedCases([null, null])).toBeNull()
  })

  it('adds field by field and skips the cards that were not served', () => {
    const sum = sumLinkedCases([
      aLinkedCasesSummary({ total: 6, passing: 4, failing: 1, blocked: 1, notRun: 0 }),
      null,
      aLinkedCasesSummary({ total: 2, passing: 0, failing: 0, blocked: 0, notRun: 2 }),
    ])
    expect(sum).toEqual({ total: 8, passing: 4, failing: 1, blocked: 1, notRun: 2 })
  })

  it('keeps a zero total a real answer, so the wording downstream stays “none linked”', () => {
    const sum = sumLinkedCases([
      aLinkedCasesSummary({ total: 0, passing: 0, failing: 0, blocked: 0, notRun: 0 }),
      null,
    ])
    expect(sum).toEqual({ total: 0, passing: 0, failing: 0, blocked: 0, notRun: 0 })
    expect(describeLinkedCases(sum)?.headline).toBe('no cases linked')
  })

  it('reads 0/n run when nothing across the column was executed', () => {
    const sum = sumLinkedCases([
      aLinkedCasesSummary({ total: 3, passing: 0, failing: 0, blocked: 0, notRun: 3 }),
      aLinkedCasesSummary({ total: 2, passing: 0, failing: 0, blocked: 0, notRun: 2 }),
    ])
    expect(describeLinkedCases(sum)?.headline).toBe('0/5 run')
  })
})
