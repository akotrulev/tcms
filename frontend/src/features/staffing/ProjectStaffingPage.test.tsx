import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, ProjectStaffingView } from '../../api/types'
import { aProject, aProjectStaffingView, aUserRef, anAllocation } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ProjectStaffingPage } from './ProjectStaffingPage'

/**
 * `/projects/:projectId/staffing` — story S1's screen.
 *
 * The claim that matters most here is a **negative** one, and it is 017 D-f: a person at 130%
 * across the company shows the total and the flag and **no other project's name**. A Project
 * Manager holds no company-wide key, so a breakdown would disclose, through a project-scope grant,
 * the existence of projects they cannot see — which is a leak no assertion about what IS on screen
 * can catch. So the test asserts on the absence of the other project's name by name.
 *
 * The arms are anchored. `/^\/projects\/[^/]+\/staffing$/` and `/^\/projects\/[^/]+\/allocations$/`
 * are different endpoints answering different shapes, and a `startsWith` would serve one for the
 * other; `/projects/{id}` itself is a third, one segment shorter than both.
 */

const MIRA = aUserRef({ id: 'u-mira', displayName: 'Mira Sund' })
const TOMAS = aUserRef({ id: 'u-tomas', displayName: 'Tomas Ek' })

/**
 * Mira is on THIS project for half her week and at 130% across the company — the pair D-f is about.
 * Nothing in the payload says where the other 80% went, because the endpoint does not serve it.
 */
const OVER_ALLOCATED = aProjectStaffingView({
  allocations: [
    anAllocation({ id: 'al-mira', personId: 'u-mira', person: MIRA, percent: 50, note: 'Payments' }),
  ],
  people: [
    {
      person: MIRA,
      percentOnThisProject: 50,
      totalPercent: 130,
      overAllocated: true,
      capacityHours: 240,
    },
  ],
})

function mockReads(view: ProjectStaffingView, permissions: PermissionKey[] = ['staffing:read']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: 'p1', key: 'ACME', name: 'Acme Storefront' })
    if (/^\/projects\/[^/]+\/members$/.test(path)) return [MIRA, TOMAS]
    if (/^\/projects\/[^/]+\/staffing(\?|$)/.test(path)) return view
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/projects/p1/staffing">
      <Routes>
        <Route path="/projects/:projectId/staffing" element={<ProjectStaffingPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe("A project's staffing", () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the company-wide total and the flag, and names no other project', async () => {
    mockReads(OVER_ALLOCATED)
    renderPage()

    // The number is what makes the flag actionable — *already at 130%, do not add to them*.
    expect(await screen.findByTestId('ProjectStaffing.Load.u-mira.Total')).toHaveTextContent(
      '130% allocated',
    )
    expect(screen.getByTestId('ProjectStaffing.Row.al-mira.Percent')).toHaveTextContent('50%')

    /*
      017 D-f. The other 80% is on projects this reader may hold no grant on, so nothing on this
      screen may name one. Asserted against the whole page rather than one cell, because a leak
      would arrive in whichever cell somebody added it to.
    */
    expect(document.body).not.toHaveTextContent('Acme Loyalty')
    expect(document.body).not.toHaveTextContent('Bergstrom')
    expect(screen.getByTestId('ProjectStaffing.Load.Basis')).toHaveTextContent(
      'projects you cannot open',
    )
  })

  it('does not flag a person at 100 and does flag one at 101', async () => {
    /*
      The threshold is SERVED, computed from the peak day (017 D-e), and this screen reads
      `overAllocated` rather than comparing `totalPercent` to 100. The two rows differ by one
      percent and by the flag, so a client-side re-derivation would pass this test and a moved
      threshold would then be a lie only the server knew about.
    */
    mockReads(
      aProjectStaffingView({
        allocations: [anAllocation({ id: 'al-1', person: MIRA, personId: 'u-mira' })],
        people: [
          { person: MIRA, percentOnThisProject: 50, totalPercent: 100, overAllocated: false, capacityHours: 240 },
          { person: TOMAS, percentOnThisProject: 40, totalPercent: 101, overAllocated: true, capacityHours: 240 },
        ],
      }),
    )
    renderPage()

    const at = await screen.findByTestId('ProjectStaffing.Load.u-mira.Total')
    const over = screen.getByTestId('ProjectStaffing.Load.u-tomas.Total')
    expect(at).toHaveTextContent('100% allocated')
    expect(over).toHaveTextContent('101% allocated')
    /*
      The tone IS the flag, and `Badge` carries it as an inline custom property rather than in the
      class list — so this asserts the property. A `plain` badge is styleless, which is what makes
      the pair distinguishable at all: the two differ by one percent and by this.
    */
    expect(at).not.toHaveAttribute('style')
    expect(over.getAttribute('style')).toContain('--color-failed')
  })

  it('reads a person with no profile as an em dash, never 0h', async () => {
    mockReads(
      aProjectStaffingView({
        allocations: [anAllocation({ id: 'al-1', person: MIRA, personId: 'u-mira' })],
        people: [
          {
            person: MIRA,
            percentOnThisProject: 50,
            totalPercent: 50,
            overAllocated: false,
            // `ProjectStaffingPerson` carries no `profile`, so this null is the only evidence there
            // is none — and `0h` here would claim somebody wrote down zero contracted hours.
            capacityHours: null,
          },
        ],
      }),
    )
    renderPage()

    const row = await screen.findByTestId('ProjectStaffing.Load.u-mira')
    expect(row).toHaveTextContent('—')
    expect(row).not.toHaveTextContent('0h')
  })

  it('disables both writes with a reason for a reader who may only look', async () => {
    mockReads(OVER_ALLOCATED, ['staffing:read'])
    renderPage()

    // §31 — disabled, never hidden. A Test Lead who cannot allocate still learns that allocating is
    // a thing this product does and is worth asking for.
    expect(await screen.findByTestId('ProjectStaffing.Allocate')).toBeDisabled()
    // Awaited, because the header renders on the first frame and the rows arrive with the read.
    expect(await screen.findByTestId('ProjectStaffing.Row.al-mira.Edit')).toBeDisabled()
    expect(screen.getByTestId('ProjectStaffing.Row.al-mira.Remove')).toBeDisabled()
  })

  it('opens the dialog from the header for a caller who may allocate', async () => {
    mockReads(OVER_ALLOCATED, ['staffing:read', 'staffing:allocate'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('ProjectStaffing.Allocate')).toBeEnabled())
    await userEvent.click(screen.getByTestId('ProjectStaffing.Allocate'))

    expect(await screen.findByTestId('ProjectStaffing.Dialog')).toBeInTheDocument()
  })

  it('tells an unstaffed project apart from a failed read', async () => {
    mockReads(aProjectStaffingView())
    renderPage()

    const empty = await screen.findByTestId('ProjectStaffing.Region.Empty')
    expect(empty).toHaveTextContent('Nobody is allocated to this project')
    expect(screen.queryByTestId('ProjectStaffing.Region.Error')).toBeNull()
    // No summary panel under an empty table — the same nothing said twice.
    expect(screen.queryByTestId('ProjectStaffing.Load')).toBeNull()
  })
})
