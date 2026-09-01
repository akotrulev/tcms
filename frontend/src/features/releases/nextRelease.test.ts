import { describe, expect, it } from 'vitest'
import { aReleaseSummary } from '../../test/fixtures'
import { nextRelease } from './nextRelease'

/**
 * The rule three screens share — a `.test.ts` with no `render()`, because it is not a component.
 *
 * Every case here is a shape the overview panel actually reaches on a real instance, and the two
 * worth reading are the last two: a released version is never "next", and `1.10` sorts after `1.9`.
 */

describe('nextRelease', () => {
  it('is null on a project with no releases — the ordinary state of a fresh instance', () => {
    expect(nextRelease([])).toBeNull()
  })

  it('is the earliest target date, not the first row the server sent', () => {
    const chosen = nextRelease([
      aReleaseSummary({ id: 'r1', version: '2.4', targetDate: '2026-09-18' }),
      aReleaseSummary({ id: 'r2', version: '2.5', targetDate: '2026-08-01' }),
    ])

    expect(chosen?.id).toBe('r2')
  })

  it('prefers a dated release over an undated one, whatever the versions say', () => {
    /*
      "Next" is a question about when. An undated 1.0 has no answer to it, so it cannot come before
      a 2.4 that is due on Friday — which is the reading a naive version sort would produce.
    */
    const chosen = nextRelease([
      aReleaseSummary({ id: 'r1', version: '1.0', targetDate: null }),
      aReleaseSummary({ id: 'r2', version: '2.4', targetDate: '2026-09-18' }),
    ])

    expect(chosen?.id).toBe('r2')
  })

  it('falls back to the lowest version among the undated, by precedence and not by string', () => {
    const chosen = nextRelease([
      aReleaseSummary({ id: 'r1', version: '1.10', targetDate: null }),
      aReleaseSummary({ id: 'r2', version: '1.9', targetDate: null }),
    ])

    expect(chosen?.id).toBe('r2')
  })

  it('never names a RELEASED version, even when it is the only one with a date', () => {
    const chosen = nextRelease([
      aReleaseSummary({ id: 'r1', version: '2.3', status: 'RELEASED', targetDate: '2026-07-01' }),
      aReleaseSummary({ id: 'r2', version: '2.4', status: 'PLANNED', targetDate: null }),
    ])

    expect(chosen?.id).toBe('r2')
  })

  it('is null when every release has shipped', () => {
    expect(nextRelease([aReleaseSummary({ status: 'RELEASED' })])).toBeNull()
  })
})
