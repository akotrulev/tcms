import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, Release, ReleaseReadiness } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import {
  aProject,
  aRelease,
  aReleaseBlocker,
  aReleaseReadiness,
  aReleaseStories,
  aReleaseTests,
  aRoadmapEpic,
  aStatusRef,
  anIssueType,
} from '../../test/fixtures'
import { ReleaseDetailPage } from './ReleaseDetailPage'

/**
 * `release.html`, and the four things on it that are requirements rather than layout.
 *
 * 1. **The blocking stories are named**, with their real status by name — the contract has no
 *    `blocked` count (D2), so "Blocked" is a word only the row can carry.
 * 2. **Done and failing gets the red banner**, derived here because both the story key and the case
 *    key are links and a server-assembled sentence cannot carry them.
 * 3. **A version with no run shows the *"If nothing had run"* panel, never a zero meter** (D-d).
 * 4. **`Mark released` is gated on `release:publish`** — a key `release:manage` does not imply — and
 *    it is *not* gated on readiness: the confirmation names what is outstanding and lets the person
 *    go ahead, because a team recording a release with a known failure is a real thing.
 */

const PROJECT = 'p1'
const RELEASE = 'r-24'

const DONE = aStatusRef({ id: 's-done', name: 'Done', kind: 'DONE', position: 4 })
const REVIEW = aStatusRef({ id: 's-review', name: 'In Review', kind: 'IN_PROGRESS', position: 2 })
const BLOCKED = aStatusRef({ id: 's-blocked', name: 'Blocked', kind: 'IN_PROGRESS', position: 3 })

function mockReads(
  readiness: ReleaseReadiness,
  release: Release = aRelease({ id: RELEASE, version: '2.4', targetDate: '2026-09-18' }),
  permissions: PermissionKey[] = ['release:read', 'release:manage', 'release:publish'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    // Anchored one segment tighter than the release read, which is the collision the harness warns
    // about: `/releases/{id}` and `/releases/{id}/readiness` share a prefix.
    if (/^\/releases\/[^/]+\/readiness$/.test(path)) return readiness
    if (/^\/releases\/[^/]+$/.test(path)) return release
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/releases/${RELEASE}`}
    >
      <Routes>
        <Route
          path="/projects/:projectId/releases/:releaseId"
          element={<ReleaseDetailPage />}
        />
      </Routes>
    </TestProviders>,
  )
}

/** 2.4's shape from the mockup: 21/27 stories, 61/63 cases, two failures, six blockers. */
function readyish(): ReleaseReadiness {
  return aReleaseReadiness({
    releaseId: RELEASE,
    version: '2.4',
    verdict: 'NOT_READY',
    stories: aReleaseStories({ done: 21, total: 27, inProgress: 3, notStarted: 3 }),
    tests: aReleaseTests({
      hasRun: true,
      linkedCases: 63,
      withResult: 63,
      passed: 61,
      failed: 2,
      blocked: 0,
      untested: 0,
      latestExecutedAt: '2026-09-01T10:00:00Z',
      latestRunId: 'run-1',
      latestRunName: '2.4 regression',
    }),
    blockers: [
      aReleaseBlocker({
        key: 'ACME-118',
        title: 'Guest checkout without an account',
        type: anIssueType({ name: 'Story', level: 'STORY' }),
        status: DONE,
        casesPassed: 3,
        casesFailed: 1,
        failedCaseKeys: ['TC-311'],
      }),
      aReleaseBlocker({
        key: 'ACME-119',
        title: 'Apply a gift card at checkout',
        status: BLOCKED,
        casesUntested: 2,
      }),
      aReleaseBlocker({
        key: 'ACME-131',
        title: 'Saved card list renders twice',
        status: REVIEW,
      }),
    ],
    epics: [
      aRoadmapEpic({ key: 'ACME-101', title: 'Checkout rebuild', completionPercent: 70 }),
    ],
    scopeAddedAfterCreation: 4,
  })
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('readiness, with the blocking stories named', () => {
  it('draws the three tiles from the server’s numbers', async () => {
    mockReads(readyish())
    renderPage()

    await screen.findByTestId('Release.Tile.Stories')

    expect(screen.getByTestId('Release.Tile.Stories')).toHaveTextContent('21 / 27')
    expect(screen.getByTestId('Release.Tile.Stories')).toHaveTextContent('3 in progress')
    expect(screen.getByTestId('Release.Tile.Tests')).toHaveTextContent('61 / 63')
    expect(screen.getByTestId('Release.Tile.Tests')).toHaveTextContent('2 failed')
    expect(screen.getByTestId('Release.Tile.Scope')).toHaveTextContent('+4')
    expect(screen.getByTestId('Release.Tile.Scope')).toHaveTextContent(
      '4 stories added after the release was created',
    )
  })

  it('says which of the numbers can lag, because only one comes from the projection (D-f)', async () => {
    mockReads(readyish())
    renderPage()

    // `scopeAddedAfterCreation` is the one figure read from `issue_fact`, whose delivery has a
    // one-minute floor. Every other number here is a live `issue` read.
    expect(await screen.findByTestId('Release.Tile.Scope.AsOf')).toHaveTextContent(
      /up to a minute behind/,
    )
  })

  it('says how far off the target is, and not “just now”', async () => {
    /*
      `formatAge` reads a negative interval as *"just now"*, so every future target rendered as
      `target 18 Sep 2026 · just now` until `formatDueIn` existed. The date is the mockup's own.
    */
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-01T09:00:00Z'))
    try {
      mockReads(readyish())
      renderPage()
      await vi.waitFor(() => expect(screen.getByTestId('Release.Target')).toBeInTheDocument())
      expect(screen.getByTestId('Release.Target')).toHaveTextContent('in 17 days')
    } finally {
      vi.useRealTimers()
    }
  })

  it('lists each blocker with its project-chosen status name, not a kind', async () => {
    mockReads(readyish())
    renderPage()

    const table = await screen.findByTestId('Release.Blockers.Table')
    expect(within(table).getByText('Guest checkout without an account')).toBeInTheDocument()
    // "Blocked" is a status a project named. The contract counts three kinds and cannot count this
    // one, so the row is the only place the word can appear.
    expect(within(table).getByText('Blocked')).toBeInTheDocument()
    expect(within(table).getByText('In Review')).toBeInTheDocument()
  })

  it('says `none linked` rather than `0 pass` for a story with no cases', async () => {
    mockReads(readyish())
    renderPage()

    const rows = await screen.findAllByTestId('Release.Blockers.Row')
    // ACME-131 has four zero counts: a `0 pass` badge would claim a measurement nobody took.
    expect(within(rows[2]).getByTestId('Release.Blockers.Row.NoCases')).toHaveTextContent(
      'none linked',
    )
  })

  it('derives the Verdict column from the status and the failed keys', async () => {
    mockReads(readyish())
    renderPage()

    const rows = await screen.findAllByTestId('Release.Blockers.Row')
    expect(within(rows[0]).getByTestId('Release.Blockers.Row.Verdict')).toHaveTextContent(
      'TC-311 failed',
    )
    expect(within(rows[1]).getByTestId('Release.Blockers.Row.Verdict')).toHaveTextContent(
      'in flight',
    )
  })
})

describe('the headline banner', () => {
  it('names the story and the case when something is done and failing', async () => {
    mockReads(readyish())
    renderPage()

    const banner = await screen.findByTestId('Release.Headline')
    expect(banner).toHaveTextContent('2.4 is not ready.')
    expect(banner).toHaveTextContent('ACME-118 · Guest checkout without an account')
    expect(banner).toHaveTextContent('TC-311')
    expect(banner).toHaveTextContent('Done and proven have to be the same answer.')
    // Both are links, which is the whole reason the sentence is assembled here rather than sent.
    expect(screen.getByTestId('Release.Headline.Issue')).toHaveAttribute(
      'href',
      '/issues/ACME-118',
    )
  })

  it('is absent when nothing is done-and-failing, rather than replaced by a green one', async () => {
    /*
      A banner on every visit is chrome the reader learns to skip past the one time it matters. The
      badge and the tiles already say where the release stands.
    */
    mockReads(
      aReleaseReadiness({
        releaseId: RELEASE,
        verdict: 'NOT_READY',
        stories: aReleaseStories({ done: 1, total: 2, notStarted: 1 }),
        blockers: [aReleaseBlocker({ key: 'ACME-119', status: BLOCKED })],
      }),
    )
    renderPage()

    await screen.findByTestId('Release.Blockers.Table')
    expect(screen.queryByTestId('Release.Headline')).not.toBeInTheDocument()
  })
})

describe('a version nothing has run against', () => {
  it('shows the "If nothing had run" panel in both places, and no zero meter', async () => {
    mockReads(
      aReleaseReadiness({
        releaseId: RELEASE,
        version: '2.5',
        verdict: 'NOT_TESTED',
        stories: aReleaseStories({ total: 6, notStarted: 6 }),
        tests: aReleaseTests({ hasRun: false, linkedCases: 4 }),
      }),
      aRelease({ id: RELEASE, version: '2.5' }),
    )
    renderPage()

    await screen.findByTestId('Release.Tile.Tests')

    // The tile's meter is replaced, not accompanied: a bar beside this sentence would be the
    // contradiction the sentence exists to prevent.
    expect(screen.getByTestId('Release.Tile.Tests.NoRun')).toHaveTextContent(
      'No test run against 2.5',
    )
    expect(screen.getByTestId('Release.Tile.Tests.NoRun')).toHaveTextContent(
      'That is not zero failures — it is no evidence.',
    )
    expect(screen.getByTestId('Release.Evidence.NoRun')).toBeInTheDocument()

    // The counts are null on the wire, so nothing may render them — a `?? 0` would show `0 / 4`.
    expect(screen.getByTestId('Release.Tile.Tests')).toHaveTextContent('no run yet')
    expect(screen.queryByText('0 / 4')).not.toBeInTheDocument()
    expect(screen.queryByText(/0 failed/)).not.toBeInTheDocument()
    expect(screen.getByTestId('Release.Badge')).toHaveTextContent('Not tested')
  })
})

describe('Mark released', () => {
  it('confirms, names what is outstanding, and still lets it through', async () => {
    mockReads(readyish())
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(
        aRelease({ id: RELEASE, version: '2.4', status: 'RELEASED' }) as never,
      )
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Release.Publish')).toBeEnabled())
    await user.click(screen.getByTestId('Release.Publish'))

    const dialog = await screen.findByTestId('Release.Publish.Confirm')
    // Readiness does not gate this — the contract is explicit that refusing would assert a release
    // policy this product cannot know — so the dialog reports rather than blocks.
    expect(dialog).toHaveTextContent('6 of 27 counted stories are not done')

    await user.click(screen.getByTestId('Release.Publish.Confirm.Yes'))
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/releases/${RELEASE}/publish`, {}, expect.anything()))
  })

  it('is disabled without `release:publish`, which `release:manage` does not imply', async () => {
    // `PermissionGate` remounts the control it gates, so the query is inside the `waitFor`.
    mockReads(readyish(), undefined, ['release:read', 'release:manage'])
    renderPage()

    await screen.findByTestId('Release.Tile.Stories')
    await waitFor(() => expect(screen.getByTestId('Release.Publish')).toBeDisabled())
  })

  it('is absent on an already-released release', async () => {
    /*
      The one place §31's disable-don't-hide does not apply: the state is reached, so there is no
      capability left to advertise and hiding says nothing about the reader's access.
    */
    mockReads(
      aReleaseReadiness({ releaseId: RELEASE, status: 'RELEASED', verdict: 'READY' }),
      aRelease({ id: RELEASE, status: 'RELEASED', releasedAt: '2026-08-07T00:00:00Z' }),
    )
    renderPage()

    await screen.findByTestId('Release.Tile.Stories')
    expect(screen.queryByTestId('Release.Publish')).not.toBeInTheDocument()
    expect(screen.getByTestId('Release.Badge')).toHaveTextContent('Released')
  })
})

describe('a failed readiness read', () => {
  it('shows the failed region and keeps the header, which reads the other endpoint', async () => {
    const { drivingAFailedRead } = await import('../../test/renderFailures')
    // The subject: readiness answers 500 while the release itself loads.
    drivingAFailedRead()

    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['release:read'], {}, PROJECT)
      if (/^\/releases\/[^/]+\/readiness$/.test(path)) throw new Error('readiness is down')
      if (/^\/releases\/[^/]+$/.test(path)) return aRelease({ id: RELEASE, version: '2.4' })
      if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT })
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    renderPage()

    await screen.findByTestId('Release.Content.Error')
    // Two reads, and the header is the first one's: the version and the breadcrumbs survive.
    expect(screen.getByTestId('Release.Header.Title')).toHaveTextContent('Release 2.4')
    // And no badge, because there is no verdict — rather than one guessed from the status.
    expect(screen.queryByTestId('Release.Badge')).not.toBeInTheDocument()
  })
})
