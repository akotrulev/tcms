import { describe, expect, it } from 'vitest'
import { formatComputedAt } from './format'

/**
 * `computedAt` — how old the *arithmetic* on a screen is (§101).
 *
 * The whole of this helper's job is that **two different ages must not read as one signal.**
 * `PlanStatistics.stale` says nothing has been *executed* against a plan in fourteen days; this says
 * how old the *sums* are. A plan executed an hour ago and rolled up ten minutes ago is fresh by one
 * measure and behind by the other, and plan §6p calls a page presenting old numbers as current the
 * most dangerous screen in the product.
 *
 * **Both directions are asserted, per §66's rule** — restated after §76's mutation that failed to
 * redden and §80's four first-run failures. A test that only checks the line is absent when
 * `computedAt` is null passes against a component that never renders it at all; the timestamp case
 * is the barrier.
 */
describe('formatComputedAt', () => {
  const NOW = Date.parse('2026-08-06T12:00:00Z')

  it('says "Computed now" for null — a positive statement, not a missing value', () => {
    // Null means the server computed from the executions themselves on this request: the WORST /
    // BEST / ALL policies, and any plan whose rollup has not been written yet. It is MORE current
    // than a timestamp, not less, so rendering it as "unknown" — or as nothing at all — would make
    // the freshest possible answer look like a missing one.
    expect(formatComputedAt(null, NOW)).toBe('Computed now')
    expect(formatComputedAt(undefined, NOW)).toBe('Computed now')
  })

  it('states the instant AND the age for a rollup timestamp', () => {
    // Both, deliberately. The instant is what a release meeting quotes; the age is what tells a
    // reader at a glance whether it matters. A screen showing only the instant makes every reader
    // do the subtraction, and a screen showing only the age cannot be quoted.
    const line = formatComputedAt('2026-08-06T11:45:00Z', NOW)

    expect(line).toContain('Figures as of')
    expect(line).toContain('15m ago')
  })

  it('does not read as "now" when the rollup is old — the case the field exists for', () => {
    // The mutation that matters: a helper returning "Computed now" unconditionally would satisfy
    // the null test above and be wrong in exactly the direction §6p warns about.
    const line = formatComputedAt('2026-07-30T09:00:00Z', NOW)

    expect(line).not.toBe('Computed now')
    expect(line).toContain('7d ago')
  })
})
