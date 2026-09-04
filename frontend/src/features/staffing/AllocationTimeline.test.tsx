import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AllocationSpan } from '../../api/types'
import { anAbsence, aPersonCapacity, aUserRef } from '../../test/fixtures'
import { AllocationTimeline } from './AllocationTimeline'

/**
 * The timeline's three claims, and none of them is about pixels.
 *
 * **Every bar is readable without sight** — the panel's whole content is positional, so a bar with
 * no `aria-label` naming its project and percentage is a row that says only somebody's name.
 * **The flag is the served boolean**, fired at 101 and not at 100, and read off the peak day rather
 * than a monthly sum (017 D-e). And **a person with no profile reads an em dash and *no profile
 * recorded***, never `0h` — the state `tcmsFaults.staffing('no-profile')` exists to reach.
 */

const WINDOW = { from: '2026-09-01', to: '2026-11-30' }

function allocation(overrides: Partial<AllocationSpan> = {}): AllocationSpan {
  return {
    projectId: 'p1',
    projectKey: 'ACME',
    projectName: 'Acme Storefront',
    percent: 100,
    startsOn: '2026-09-01',
    endsOn: '2026-11-30',
    ...overrides,
  }
}

describe('AllocationTimeline', () => {
  it('names the project and the percentage on every bar', () => {
    render(
      <AllocationTimeline
        window={WINDOW}
        people={[
          aPersonCapacity({
            allocations: [
              allocation({ percent: 60 }),
              allocation({ projectId: 'p2', projectKey: 'INT', projectName: 'Internal', percent: 40 }),
            ],
          }),
        ]}
        testId="Timeline"
      />,
    )

    // Role-free but accessible-name-keyed: the bars are spans, and the label is the only thing a
    // reader who cannot see the track gets.
    expect(screen.getByLabelText(/Acme Storefront 60%/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Internal 40%/)).toBeInTheDocument()
  })

  it('flags at 101 and not at 100', () => {
    render(
      <AllocationTimeline
        window={WINDOW}
        people={[
          aPersonCapacity({
            person: aUserRef({ id: 'u-at', displayName: 'At Capacity' }),
            allocatedPercent: 100,
            peakPercent: 100,
            overAllocated: false,
          }),
          aPersonCapacity({
            person: aUserRef({ id: 'u-over', displayName: 'Over Capacity' }),
            allocatedPercent: 101,
            peakPercent: 101,
            overAllocated: true,
          }),
        ]}
        testId="Timeline"
      />,
    )

    expect(screen.queryByTestId('Timeline.Row.u-at.Over')).toBeNull()
    expect(screen.getByTestId('Timeline.Row.u-at.Percent')).toHaveTextContent('100%')
    expect(screen.getByTestId('Timeline.Row.u-over.Over')).toHaveTextContent('101%')
  })

  it('reads an em dash and “no profile recorded” for a person with no profile, never 0h', () => {
    /*
      The five nulls together, because the server sends them together (017 correction 4). A fixture
      carrying only `profile: null` would describe a body no endpoint answers with, and would let a
      screen pass this test while printing a stale figure in production.
    */
    render(
      <AllocationTimeline
        window={WINDOW}
        people={[
          aPersonCapacity({
            person: aUserRef({ id: 'u-none', displayName: 'No Profile' }),
            profile: null,
            capacityHours: null,
            absenceHours: null,
            allocatedCapacityHours: null,
            approvedHours: null,
            allocations: [allocation({ percent: 50 })],
          }),
        ]}
        testId="Timeline"
      />,
    )

    const capacity = screen.getByTestId('Timeline.Row.u-none.Capacity')
    expect(capacity).toHaveTextContent('no profile recorded')
    expect(capacity).not.toHaveTextContent('0h')
    // The allocation is still real — a percentage is a share of a capacity nobody has recorded.
    expect(screen.getByLabelText(/Acme Storefront 50%/)).toBeInTheDocument()
  })

  it('draws absence as its own band, named by its dates', () => {
    render(
      <AllocationTimeline
        window={WINDOW}
        people={[
          aPersonCapacity({
            person: aUserRef({ id: 'u-away', displayName: 'Away Person' }),
            absences: [anAbsence({ id: 'ab-away', startsOn: '2026-10-05', endsOn: '2026-10-09' })],
          }),
        ]}
        testId="Timeline"
      />,
    )

    const row = screen.getByTestId('Timeline.Row.u-away')
    expect(within(row).getByLabelText(/^Absent /)).toBeInTheDocument()
  })

  it('drops an allocation that falls wholly outside the window rather than clamping it', () => {
    // A clamped zero-width bar would put a project's name on a row it does not belong to, which is
    // worse than the bar being absent: the label is what a screen reader reads out.
    render(
      <AllocationTimeline
        window={WINDOW}
        people={[
          aPersonCapacity({
            allocations: [
              allocation({ projectName: 'Last Year', startsOn: '2025-01-01', endsOn: '2025-02-01' }),
            ],
          }),
        ]}
        testId="Timeline"
      />,
    )

    expect(screen.queryByLabelText(/Last Year/)).toBeNull()
  })
})
