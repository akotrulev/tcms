import { render, screen, waitFor, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, Roadmap } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aRoadmap, aRoadmapEpic, aRoadmapRelease } from '../../test/fixtures'
import { RoadmapView } from './RoadmapView'

/**
 * `work-timeline.html`, and its two requirements.
 *
 * 1. **The empty roadmap is an acceptance criterion.** D14 migrates nothing into `release`, so on
 *    the day M3 merges this screen is empty for every user in every instance — and an empty timeline
 *    that explains nothing reads as a broken screen, with no way for a reader to tell the
 *    difference. It must name the reason and carry `create a release`.
 * 2. **Every bar is a real `<a>`.** The mockup's closing note is *"clicking any bar opens that
 *    epic"*, and a positioned `<div>` with an `onClick` is unreachable from the keyboard.
 */

const PROJECT = 'p1'
const TODAY = '2026-09-01'

function mockReads(roadmap: Roadmap, permissions: PermissionKey[] = ['release:read', 'release:manage']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (/^\/projects\/[^/]+\/roadmap$/.test(path)) return roadmap
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderView() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/work?view=timeline`}>
      <Routes>
        <Route
          path="/projects/:projectId/work"
          element={<RoadmapView projectId={PROJECT} today={TODAY} />}
        />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the empty roadmap explains itself', () => {
  it('names the reason it is empty and offers the create', async () => {
    mockReads(aRoadmap())
    renderView()

    const empty = await screen.findByTestId('Roadmap.Content.Empty')
    expect(empty).toHaveTextContent('No releases to lay out yet')
    expect(empty).toHaveTextContent('Nothing was migrated into releases when this module shipped')
    // The last clause is the point: without it a reader reasonably concludes the screen is broken.
    expect(empty).toHaveTextContent('it is not a fault in this screen')

    /*
      Inside the `waitFor` because `PermissionGate` **remounts** the control it gates: until the
      verdict lands the action is a `<button>` inside a tooltip anchor, and only afterwards is it the
      `<a>` this asserts on. A node captured before the answer is a detached element.
    */
    await waitFor(() => {
      const action = within(screen.getByTestId('Roadmap.Content.Empty')).getByTestId(
        'Roadmap.Empty.New',
      )
      expect(action).toHaveTextContent('New release')
      expect(action.closest('a')).toHaveAttribute('href', `/projects/${PROJECT}/releases/new`)
    })
  })

  it('disables the create for a caller without `release:manage` rather than hiding it', async () => {
    // The query sits inside the `waitFor` because `PermissionGate` remounts what it gates.
    mockReads(aRoadmap(), ['release:read'])
    renderView()

    await screen.findByTestId('Roadmap.Content.Empty')
    await waitFor(() => expect(screen.getByTestId('Roadmap.Empty.New')).toBeDisabled())
  })
})

describe('the bars', () => {
  it('draws a release row with its epics under it, every bar a link', async () => {
    mockReads(
      aRoadmap({
        releases: [
          aRoadmapRelease({
            id: 'r-24',
            version: '2.4',
            targetDate: '2026-09-18',
            verdict: 'NOT_READY',
            epics: [
              aRoadmapEpic({
                key: 'ACME-101',
                title: 'Checkout rebuild',
                startDate: '2026-07-10',
                targetDate: '2026-09-10',
                completionPercent: 70,
              }),
            ],
          }),
        ],
      }),
    )
    renderView()

    await screen.findByTestId('Roadmap.Timeline')

    const releaseBar = screen.getByTestId('Roadmap.Bar.Release.2.4')
    expect(releaseBar.tagName).toBe('A')
    expect(releaseBar).toHaveAttribute('href', `/projects/${PROJECT}/releases/r-24`)

    const epicBar = screen.getByTestId('Roadmap.Bar.Epic.ACME-101')
    expect(epicBar.tagName).toBe('A')
    expect(epicBar).toHaveAttribute('href', '/issues/ACME-101')
    expect(epicBar).toHaveTextContent('ACME-101 · 70%')
  })

  it('gives a release with no target date a row and no bar', async () => {
    /*
      `2.10`'s row in the mockup. The release has not disappeared from the plan — it appears in
      version order with its epics — it just has nothing to draw.
    */
    mockReads(
      aRoadmap({
        releases: [
          aRoadmapRelease({ id: 'r-24', version: '2.4', targetDate: '2026-09-18' }),
          aRoadmapRelease({ id: 'r-210', version: '2.10', targetDate: null }),
        ],
      }),
    )
    renderView()

    await screen.findByTestId('Roadmap.Timeline')
    expect(screen.getByTestId('Roadmap.Release.2.10')).toBeInTheDocument()
    expect(screen.queryByTestId('Roadmap.Bar.Release.2.10')).not.toBeInTheDocument()
    expect(screen.getByTestId('Roadmap.Bar.Release.2.4')).toBeInTheDocument()
  })

  it('marks today, and says the date in the foot', async () => {
    mockReads(aRoadmap({ releases: [aRoadmapRelease({ targetDate: '2026-09-18' })] }))
    renderView()

    expect(await screen.findByTestId('Roadmap.Today')).toHaveTextContent('today')
    expect(screen.getByTestId('Roadmap.Today')).toHaveTextContent('2026')
  })
})

describe('the unscheduled lane', () => {
  it('bars an epic with dates and no release, and lists one with no dates at all (D-o)', async () => {
    /*
      Two shapes, one lane, and they render differently because the data differs: an epic with dates
      has a span and just nowhere else to be drawn, while one with no dates has nothing to position —
      so it is a link rather than a bar at an invented offset, which is the one thing the mockup does
      that the payload cannot support.
    */
    mockReads(
      aRoadmap({
        releases: [aRoadmapRelease({ targetDate: '2026-09-18' })],
        unscheduled: [
          aRoadmapEpic({
            key: 'ACME-180',
            title: 'Fraud rules',
            startDate: '2026-08-01',
            targetDate: '2026-10-01',
          }),
          aRoadmapEpic({ key: 'ACME-190', title: 'Loyalty tie-in' }),
        ],
      }),
    )
    renderView()

    await screen.findByTestId('Roadmap.Unscheduled')
    expect(screen.getByTestId('Roadmap.Bar.Unscheduled.ACME-180')).toHaveAttribute(
      'href',
      '/issues/ACME-180',
    )
    expect(screen.getByTestId('Roadmap.Undated.ACME-190')).toHaveTextContent(
      'Loyalty tie-in — no dates',
    )
  })
})

describe('a failed read', () => {
  it('shows the failed region rather than an empty timeline', async () => {
    const { drivingAFailedRead } = await import('../../test/renderFailures')
    // The subject: the roadmap answers 500. "Broken" and "nothing here" must not look alike — which
    // matters more on this screen than anywhere else, because empty is its ordinary state.
    drivingAFailedRead()

    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['release:read'], {}, PROJECT)
      if (/^\/projects\/[^/]+\/roadmap$/.test(path)) throw new Error('the roadmap is down')
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    renderView()

    await screen.findByTestId('Roadmap.Content.Error')
    expect(screen.queryByTestId('Roadmap.Content.Empty')).not.toBeInTheDocument()
  })
})
