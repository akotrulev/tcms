import { render, screen, waitFor, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, ReleaseSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, aReadinessSummary, aReleaseSummary } from '../../test/fixtures'
import { ReleaseListPage } from './ReleaseListPage'

/**
 * `releases.html`, and the two things on it that are requirements rather than layout.
 *
 * 1. **`no run yet`, and never `0/0`.** An untested version and a clean version are not the same
 *    claim (D-d), and the payload makes the distinction available exactly once — `hasRun`, with
 *    `casesPassed` and `casesFailed` **null** beside it. A screen that branched on a count instead
 *    would read `0/0`, which is a measurement nobody took.
 * 2. **Empty is the ordinary state on merge day** (D14). Nothing is migrated into `release`, so this
 *    screen is empty for every user in every instance on the day it ships. It has to say why and
 *    offer the create, or it reads as a screen that failed to load — and a reader has no way to tell
 *    the difference.
 */

const PROJECT = 'p1'

function mockReads(
  releases: ReleaseSummary[],
  permissions: PermissionKey[] = ['release:read', 'release:manage'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    // Anchored so `/projects/{id}/releases` cannot be served the project body, and vice versa.
    if (/^\/projects\/[^/]+\/releases$/.test(path)) return releases
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/releases`}>
      <Routes>
        <Route path="/projects/:projectId/releases" element={<ReleaseListPage />} />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the table', () => {
  it('draws a row per release with its version, target and readiness badge', async () => {
    mockReads([
      aReleaseSummary({
        id: 'r-24',
        version: '2.4',
        description: 'Checkout rebuild and the provider swap',
        targetDate: '2026-09-18',
        readiness: aReadinessSummary({
          verdict: 'NOT_READY',
          storiesDone: 21,
          storiesTotal: 27,
          linkedCases: 63,
          hasRun: true,
          casesPassed: 61,
          casesFailed: 2,
        }),
      }),
    ])
    renderPage()

    await screen.findByTestId('Releases.Table')

    expect(screen.getByTestId('Releases.Row.Version')).toHaveTextContent('2.4')
    expect(screen.getByText('Checkout rebuild and the provider swap')).toBeInTheDocument()
    expect(screen.getByTestId('Releases.Row.Stories')).toHaveTextContent('21/27')
    expect(screen.getByTestId('Releases.Row.Tests')).toHaveTextContent('61/63')
    expect(screen.getByTestId('Releases.Row.Readiness')).toHaveTextContent('Not ready')
  })

  it('says `no run yet` for an untested version, and does NOT draw a zero meter', async () => {
    /*
      The module's headline criterion. The fixture defaults to the untested state on purpose, so a
      `?? 0` between the wire and the bar would show up here as `0/0` rather than passing.
    */
    mockReads([
      aReleaseSummary({
        id: 'r-25',
        version: '2.5',
        readiness: aReadinessSummary({ storiesDone: 0, storiesTotal: 6, linkedCases: 4 }),
      }),
    ])
    renderPage()

    await screen.findByTestId('Releases.Table')

    expect(screen.getByTestId('Releases.Row.NoRun')).toHaveTextContent('no run yet')
    expect(screen.queryByTestId('Releases.Row.Tests')).not.toBeInTheDocument()
    expect(screen.queryByText('0/0')).not.toBeInTheDocument()
    expect(screen.queryByText('0/4')).not.toBeInTheDocument()

    // The stories half still has real numbers, and `0/6` there is a measurement: six counted
    // stories exist and none is done. Only the test half is unknown.
    expect(screen.getByTestId('Releases.Row.Stories')).toHaveTextContent('0/6')
  })

  it('badges a released version Released whatever its verdict (D-e)', async () => {
    mockReads([
      aReleaseSummary({
        id: 'r-23',
        version: '2.3',
        status: 'RELEASED',
        releasedAt: '2026-08-07T00:00:00Z',
        // Deliberately not READY: it shipped, so the question the verdict answers has been answered
        // by the fact, and the badge must not argue with it.
        readiness: aReadinessSummary({ verdict: 'NOT_READY', hasRun: true, casesPassed: 47, casesFailed: 1, linkedCases: 48 }),
      }),
    ])
    renderPage()

    await screen.findByTestId('Releases.Table')
    expect(screen.getByTestId('Releases.Row.Readiness')).toHaveTextContent('Released')
  })

  it('carries the panel foot about version ordering', async () => {
    mockReads([aReleaseSummary({})])
    renderPage()

    const foot = await screen.findByTestId('Releases.Foot')
    expect(foot).toHaveTextContent('2.10 sorts after 2.5')
    expect(foot).toHaveTextContent('segment by segment')
  })
})

describe('the empty state is an acceptance criterion, not a caveat', () => {
  it('explains why it is empty and offers the create', async () => {
    mockReads([])
    renderPage()

    const empty = await screen.findByTestId('Releases.Content.Empty')
    expect(empty).toHaveTextContent('Nothing is migrated into releases when this module ships')
    expect(within(empty).getByTestId('Releases.Empty.New')).toHaveTextContent('Create a release')
  })

  it('disables the create for a caller without `release:manage`, rather than hiding it', async () => {
    /*
      §31, and `PermissionGate` **remounts** the control it gates — its denied branch renders inside
      a tooltip anchor and drops it when the verdict lands, so a node captured before the answer is
      a detached element that stays disabled forever. The query is therefore inside the `waitFor`.
    */
    mockReads([], ['release:read'])
    renderPage()

    await screen.findByTestId('Releases.Content.Empty')
    await waitFor(() => {
      expect(screen.getByTestId('Releases.Empty.New')).toBeDisabled()
    })
    await waitFor(() => {
      expect(screen.getByTestId('Releases.New')).toBeDisabled()
    })
  })
})

describe('a failed read', () => {
  it('shows the failed region and keeps the header’s way out', async () => {
    const { drivingAFailedRead } = await import('../../test/renderFailures')
    // The subject: the release list answers 500 while the project loads, so the header renders.
    drivingAFailedRead()

    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['release:read'], {}, PROJECT)
      if (/^\/projects\/[^/]+\/releases$/.test(path)) throw new Error('releases are down')
      if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT })
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    renderPage()

    await screen.findByTestId('Releases.Content.Error')
    // The timeline link is above the region, so a failed list does not take the reader's exit with
    // it — `DataRegion`'s scoping rule, asserted rather than assumed.
    expect(screen.getByTestId('Releases.Timeline')).toBeInTheDocument()
  })
})
