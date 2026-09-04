import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, StaffingCapacityView } from '../../api/types'
import { aPersonCapacity, aStaffingCapacityView, aUserRef } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { StaffingPage } from './StaffingPage'

/**
 * `staffing.html`.
 *
 * Four claims, and three of them are about a *pair* of readings that look identical on screen:
 * **101 flags and 100 does not** (017 D-e's peak day, not a monthly sum); **a person with no
 * profile reads an em dash and *no profile recorded***, never `0h`; **the over-allocated chip is
 * the served subset**, so it cannot disagree with the badge on the row; and the **refusal names the
 * project screen**, which is the only refusal in this product that can point at a page the reader
 * actually wants.
 *
 * The read arms are anchored — `/staffing/capacity` and `/staffing/capacity/me` are one segment
 * apart and answer different shapes, so a `startsWith` here would serve a company view where the
 * caller's own figure was expected.
 */

function mockReads(
  view: StaffingCapacityView,
  permissions: PermissionKey[] = ['staffing:directory'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (/^\/staffing\/capacity(\?|$)/.test(path)) return view
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(route = '/staffing') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route={route}>
      <Routes>
        <Route path="/staffing" element={<StaffingPage />} />
      </Routes>
    </TestProviders>,
  )
}

const AT_CAPACITY = aPersonCapacity({
  person: aUserRef({ id: 'u-at', displayName: 'Dana Petrov' }),
  allocatedPercent: 100,
  peakPercent: 100,
  overAllocated: false,
  freePercent: 0,
  allocations: [
    {
      projectId: 'p1',
      projectKey: 'ACME',
      projectName: 'Acme Storefront',
      percent: 100,
      startsOn: '2026-09-01',
      endsOn: '2026-11-30',
    },
  ],
})

const OVER = aPersonCapacity({
  person: aUserRef({ id: 'u-over', displayName: 'Mira Sund' }),
  allocatedPercent: 130,
  peakPercent: 130,
  peakOn: '2026-10-12',
  overAllocated: true,
  freePercent: 0,
  allocations: [
    {
      projectId: 'p1',
      projectKey: 'ACME',
      projectName: 'Acme Storefront',
      percent: 50,
      startsOn: '2026-09-01',
      endsOn: '2026-11-30',
    },
    {
      projectId: 'p2',
      projectKey: 'LOYAL',
      projectName: 'Acme Loyalty',
      percent: 50,
      startsOn: '2026-09-01',
      endsOn: '2026-11-30',
    },
    {
      projectId: 'p3',
      projectKey: 'BERG',
      projectName: 'Bergstrom Portal',
      percent: 30,
      startsOn: '2026-10-01',
      endsOn: '2026-10-31',
    },
  ],
})

describe('Company staffing', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('takes the window from the answer, not from the picker', async () => {
    // `from`/`to` go out empty so the SERVER defaults them, and everything on screen is drawn from
    // the window it answered with. Two spellings of "next three months" is how a heading comes to
    // disagree with the timeline under it.
    mockReads(
      aStaffingCapacityView({ from: '2026-09-01', to: '2026-11-30', people: [AT_CAPACITY] }),
    )
    renderPage()

    // Awaited on the timeline rather than on the header: the header renders synchronously with
    // its fallback subtitle, so asserting on it first asserts the state before the answer.
    await screen.findByTestId('Staffing.Timeline')
    expect(screen.getByTestId('Staffing.Header')).toHaveTextContent('2026-09-01')
    expect(screen.getByTestId('Staffing.Header')).toHaveTextContent('2026-11-30')
  })

  it('flags at 101 and not at 100, and the chip reads the served subset', async () => {
    mockReads(
      aStaffingCapacityView({ people: [AT_CAPACITY, OVER], overAllocated: [OVER] }),
    )
    renderPage()

    expect(await screen.findByTestId('Staffing.OverChip')).toHaveTextContent('1 over-allocated')
    expect(screen.getByTestId('Staffing.Timeline.Row.u-over.Over')).toHaveTextContent('130%')
    expect(screen.queryByTestId('Staffing.Timeline.Row.u-at.Over')).toBeNull()

    // The breakdown, which exists ONLY on this screen (017 D-f): the project view names no other
    // project, because a project-scope grant must not disclose projects the reader cannot see.
    expect(screen.getByTestId('Staffing.OverAllocated.Breakdown.u-over')).toHaveTextContent(
      'Acme Storefront 50% + Acme Loyalty 50% + Bergstrom Portal 30%',
    )
    // The peak DAY, because that is the date somebody has to go and fix.
    expect(screen.getByTestId('Staffing.OverAllocated.Person.u-over')).toHaveTextContent('2026-10-12')
  })

  it('keeps the mockup’s sentence about what it will not decide', async () => {
    mockReads(aStaffingCapacityView({ people: [OVER], overAllocated: [OVER] }))
    renderPage()

    expect(await screen.findByTestId('Staffing.OverAllocated')).toHaveTextContent(
      'Flagged, not blocked. The product does not know which of the three matters most — you do.',
    )
  })

  it('reads a person with no profile as an em dash, never 0h and never the failed state', async () => {
    const noProfile = aPersonCapacity({
      person: aUserRef({ id: 'u-none', displayName: 'New Starter' }),
      profile: null,
      capacityHours: null,
      absenceHours: null,
      allocatedCapacityHours: null,
      approvedHours: null,
    })
    mockReads(aStaffingCapacityView({ people: [noProfile] }))
    renderPage()

    expect(await screen.findByTestId('Staffing.Timeline.Row.u-none.Capacity')).toHaveTextContent(
      'no profile recorded',
    )
    // The utilisation row shows the em dash rather than a zero on both halves of the ratio.
    expect(screen.getByTestId('Staffing.Utilisation.Person.u-none')).toHaveTextContent('—')
    expect(screen.getByTestId('Staffing.Utilisation.Percent.u-none')).toHaveTextContent(
      'not allocated',
    )
    // Not the region's failed state: the read answered, and it answered a fact about a person.
    expect(screen.queryByTestId('Staffing.Region.Error')).toBeNull()
  })

  it('names its utilisation denominator, as every other time figure in this product does', async () => {
    mockReads(
      aStaffingCapacityView({
        people: [
          aPersonCapacity({
            person: aUserRef({ id: 'u-util', displayName: 'Jun Park' }),
            allocatedCapacityHours: 240,
            approvedHours: 180,
            utilisationPercent: 75,
          }),
        ],
      }),
    )
    renderPage()

    expect(await screen.findByTestId('Staffing.Utilisation.Basis')).toHaveTextContent(
      'approved hours ÷ allocated capacity',
    )
    expect(screen.getByTestId('Staffing.Utilisation.Percent.u-util')).toHaveTextContent('75%')
  })

  it('refuses a caller without staffing:directory with copy naming the project screen', async () => {
    /*
      `staffing.html` carries `data-role="Project Manager"` and the roles spec does not give a PM
      this key (017 D-g, plan correction 3). The rail entry is still shown to them — §31,
      disable-don't-hide — so this refusal is the screen they actually reach, and it is the one
      refusal in the product that can point at a page they can use.
    */
    mockReads(aStaffingCapacityView(), ['staffing:read'])
    renderPage()

    /*
      Awaited on the COPY, not on the element. `useCan` fails closed while pending, so the refusal
      panel is on screen from the first frame reading *Checking your permissions…* — and a
      `findByTestId` on it resolves against that. The sentence is what this test is about.
    */
    await screen.findByText(/Staffing across the company is for Account Managers and Finance/)
    const denied = screen.getByTestId('Staffing.DeniedText')
    expect(denied).toHaveTextContent('open that project’s Staffing')
    // No timeline, and no range picker to change a question that is not being answered.
    expect(screen.queryByTestId('Staffing.Timeline')).toBeNull()
    expect(screen.queryByTestId('Staffing.Range')).toBeNull()
  })

  it('offers Allocate disabled, because the write is on a project and this screen names none', async () => {
    mockReads(aStaffingCapacityView({ people: [AT_CAPACITY] }))
    renderPage()

    const allocate = await screen.findByTestId('Staffing.Allocate')
    expect(allocate).toBeDisabled()
    expect(allocate).toHaveAttribute('title', 'Allocate from the project you are staffing')
  })

  it('holds the range in the URL, so the answer is a link somebody can send', async () => {
    mockReads(aStaffingCapacityView({ people: [AT_CAPACITY] }))
    renderPage('/staffing?from=2026-09-01&to=2026-09-30')

    // The URL is the question; the select reflects it rather than owning it.
    await screen.findByTestId('Staffing.Timeline')
    await userEvent.selectOptions(screen.getByTestId('Staffing.Range'), '6m')

    // Whatever bounds the option produced, both parameters are now on the URL — which is the
    // property that makes the range shareable and the query key move with it.
    const get = vi.mocked(api.get)
    const paths = get.mock.calls.map(([path]) => String(path))
    expect(paths.some((path) => /^\/staffing\/capacity\?from=.+&to=.+$/.test(path))).toBe(true)
  })

  it('says nobody is allocated in this range rather than drawing an empty timeline', async () => {
    mockReads(aStaffingCapacityView({ from: '2026-09-01', to: '2026-11-30' }))
    renderPage()

    const empty = await screen.findByTestId('Staffing.Region.Empty')
    expect(empty).toHaveTextContent('Nobody is allocated in this range')
    expect(empty).toHaveTextContent('2026-09-01')
  })
})
