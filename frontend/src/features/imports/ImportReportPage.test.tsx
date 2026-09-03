import { render, screen, waitFor, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import {
  aDroppedField,
  aProject,
  aUserRef,
  anImportPreview,
  anImportRun,
} from '../../test/fixtures'
import { ImportReportPage } from './ImportReportPage'
import { PreviewStep } from './PreviewStep'

/**
 * The report, and the four claims the screen cannot make on its own.
 *
 * 1. **It renders from `GET /imports/{id}` and nothing else.** The module's fourth done-when is
 *    *"reachable a year later with the source system gone"*, and the only way to hold it is to fail
 *    the render on any other call — a lookup of a status name or a person would work today and be a
 *    screen of dashes the year the workflow is renamed. The breadcrumb's project read is the one
 *    exception and it is allowed by name rather than by pattern.
 * 2. **A `RUNNING` run polls and then stops.** The poll reads the query's own data, so the thing
 *    worth asserting is that the refetching ends on the response that completed the run rather than
 *    one tick after it.
 * 3. **The dropped table is the preview's component.** Rendered from one fixture into both screens
 *    and compared cell for cell — D-f is a promise about the wire *and* the screen, and two tables
 *    that agree today are what it exists to prevent.
 * 4. **A FAILED run is not drawn as a report.** Every count is zero and the dropped list is empty
 *    because nothing was written, so tiles would claim a migration that lost nothing.
 */

const PROJECT = 'p1'
const RUN = 'ir-9'

/**
 * The reads this screen is allowed to make, and the assertion is on the list rather than on a
 * count: `mockReads` throws on anything else, so an added fetch fails the test with the path it
 * asked for instead of a number that moved.
 */
function mockReads(run = anImportRun({ id: RUN, projectId: PROJECT })) {
  const seen: string[] = []
  const get = vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    seen.push(path)
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['issue:read', 'issue:import'], {}, PROJECT)
    }
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    if (path === `/imports/${RUN}`) return run
    throw new Error(`the report asked the server for ${path}`)
  }) as never)
  return { get, seen }
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/imports/${RUN}`}
    >
      <Routes>
        <Route path="/projects/:projectId/imports/:importRunId" element={<ImportReportPage />} />
      </Routes>
    </TestProviders>,
  )
}

/** A completed run with something in every panel. */
function aFullReport() {
  return anImportRun({
    id: RUN,
    projectId: PROJECT,
    status: 'COMPLETED',
    startedBy: aUserRef({ id: 'u1', displayName: 'Oskar Bergman' }),
    issuesImported: 1482,
    releasesCreated: 38,
    commentsImported: 4892,
    labelsCreated: 214,
    droppedValueCount: 3918,
    labelPerSprint: false,
    backlogOrderPreserved: true,
    droppedFields: [
      aDroppedField({ name: 'Story Points', fieldType: 'number', valueCount: 598 }),
      aDroppedField({
        name: 'Sprint',
        fieldType: 'picker',
        valueCount: 1204,
        reason: 'releases replace sprints (D6); this run kept nothing in their place',
      }),
    ],
    statusMapping: [
      {
        sourceStatusName: 'Awaiting Deploy',
        targetStatusId: 's2',
        targetStatusName: 'In Review',
        targetStatusKind: 'IN_PROGRESS',
        issueCount: 87,
      },
    ],
    people: [
      { sourceName: 'Rae Lindqvist', matched: true, userId: 'u2', userName: 'Rae Lindqvist' },
      { sourceName: 'j.arnold@old-vendor.test', matched: false, userId: null, userName: null },
    ],
  })
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the report', () => {
  it('renders the whole report from one read, and asks the source system nothing', async () => {
    const { seen } = mockReads(aFullReport())
    renderPage()

    expect(await screen.findByTestId('ImportReport.Tiles')).toBeInTheDocument()

    /*
      The done-when, made checkable. The status name, the person and the dropped sentences are all
      on the row the report served — the mapping's `targetStatusName` is captured at run time (D-g)
      precisely so this screen never has to resolve one.
    */
    expect(screen.getByTestId('ImportReport.Mapping.Row')).toHaveTextContent('In Review')
    expect(screen.getByTestId('ImportReport.People')).toHaveTextContent('kept as text')

    await waitFor(() => {
      expect(seen.filter((path) => path.startsWith('/imports'))).toEqual([`/imports/${RUN}`])
    })
    // Nothing outbound at all: no tracker call, no source-project list, no preview.
    expect(seen.some((path) => path.includes('source-projects') || path.includes('preview'))).toBe(
      false,
    )
  })

  it('draws no tiles for a failed run, and says the project is untouched', async () => {
    /*
      The state `tcmsFaults` cannot produce, so it comes from a fixture: a FAILED run has every
      count at zero and an EMPTY dropped list, because the write is one transaction after the read
      pass and it never ran (D-b).
    */
    mockReads(
      anImportRun({
        id: RUN,
        projectId: PROJECT,
        status: 'FAILED',
        finishedAt: null,
        failureReason: 'The source system stopped answering after 1 100 of 1 482 issues.',
      }),
    )
    renderPage()

    const failure = await screen.findByTestId('ImportReport.Failure')
    expect(failure).toHaveTextContent(/stopped answering/)
    expect(failure).toHaveTextContent(/exactly as it was/)
    expect(screen.queryByTestId('ImportReport.Tiles')).not.toBeInTheDocument()
    expect(screen.getByTestId('ImportReport.Status')).toHaveTextContent(
      'Failed — nothing was imported',
    )
  })

  it('survives a run nobody is named for', async () => {
    /*
      `anImportRun` defaults `startedBy` to null on purpose, and it is not an edge case:
      `import_run.started_by` carries no foreign key, so an account deactivated in the year between
      the migration and somebody re-reading its report is exactly this shape. `accountEmail` is null
      alongside it on the two providers that authenticate with a bare PAT.
    */
    mockReads(anImportRun({ id: RUN, projectId: PROJECT, startedBy: null, accountEmail: null }))
    renderPage()

    expect(await screen.findByTestId('ImportReport.Tiles')).toBeInTheDocument()
    expect(screen.queryByTestId('ImportReport.StartedBy')).not.toBeInTheDocument()
    // The report itself is intact — the absent person is a missing line, not a missing screen.
    expect(screen.getByTestId('ImportReport.Status')).toHaveTextContent('Completed')
  })

  it('names the person and the account when both still resolve', async () => {
    mockReads(aFullReport())
    renderPage()

    expect(await screen.findByTestId('ImportReport.StartedBy')).toHaveTextContent('Oskar Bergman')
  })

  /**
   * Real time rather than fake timers, for `ReportsPage.test.tsx`' reason: TanStack's interval,
   * `userEvent`'s advance-on-act and `waitFor`'s scheduler all read the same clock, and driving one
   * by hand desynchronises the other two. The interval is named here and the wait derived from it,
   * so the two cannot drift apart.
   */
  it('polls while a run is RUNNING and stops once it is not', async () => {
    const POLL_INTERVAL_MS = 3_000
    const oneAndAHalfIntervals = POLL_INTERVAL_MS * 1.5
    const settle = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

    const running = mockReads(
      anImportRun({ id: RUN, projectId: PROJECT, status: 'RUNNING', finishedAt: null }),
    )
    const first = renderPage()

    expect(await screen.findByTestId('ImportReport.Running')).toHaveTextContent(
      /safe to leave and come back/,
    )
    // No tiles while it runs: a `0` on a run that is still reading the source is a wrong number,
    // not a small one.
    expect(screen.queryByTestId('ImportReport.Tiles')).not.toBeInTheDocument()

    const afterFirstAnswer = running.get.mock.calls.length
    await settle(oneAndAHalfIntervals)
    expect(running.get.mock.calls.length).toBeGreaterThan(afterFirstAnswer)
    first.unmount()

    vi.restoreAllMocks()

    const finished = mockReads(aFullReport())
    renderPage()

    await screen.findByTestId('ImportReport.Tiles')
    const afterFinishedAnswer = finished.get.mock.calls.length
    await settle(oneAndAHalfIntervals)
    // The poll reads the query's own data, so it stopped on the response that finished the run
    // rather than one tick after it.
    expect(finished.get.mock.calls.length).toBe(afterFinishedAnswer)
  }, 20_000)
})

describe('the dropped-field table', () => {
  it('renders the same cells in the preview step and in the report', async () => {
    const fields = [
      aDroppedField({ name: 'Custom: "Squad"', fieldType: 'picker', valueCount: 137 }),
      aDroppedField({
        name: 'Iteration Path',
        fieldType: 'picker',
        valueCount: 1204,
        reason: 'releases replace sprints (D6); each sprint was kept as a label',
      }),
    ]

    // One input, two screens: the run's own dropped list replaced with the same array the preview
    // is handed, so any difference in the output is the two components and nothing else.
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) {
        return heldPermissions(['issue:read', 'issue:import'], {}, PROJECT)
      }
      if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme' })
      if (path === `/imports/${RUN}`) return anImportRun({ ...aFullReport(), droppedFields: fields })
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    renderPage()
    const reportTable = await screen.findByTestId('ImportReport.Dropped.Table')
    const inTheReport = within(reportTable)
      .getAllByTestId('ImportReport.Dropped.Row')
      .map((row) => row.textContent)

    render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/imports/new`}>
        <PreviewStep
          projectId={PROJECT}
          projectName="Acme"
          preview={anImportPreview({ projectId: PROJECT, droppedFields: fields })}
          onBack={() => {}}
          onContinue={() => {}}
        />
      </TestProviders>,
    )
    const inThePreview = screen
      .getAllByTestId('ImportPreview.Dropped.Row')
      .map((row) => row.textContent)

    /*
      D-f on screen. The server asserts the two RESPONSES are equal element for element; this
      asserts the two RENDERS are, which is the half a wire test cannot reach. It passes because
      there is one component — and that is the point: it would keep passing for a copy until the
      day somebody edited one of them.
    */
    expect(inTheReport).toEqual(inThePreview)
    expect(inTheReport[1]).toContain('kept as a label')
  })
})
