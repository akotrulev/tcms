import { render, screen } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as client from '../../api/client'
import { ApiError, api } from '../../api/client'
import { reportJobStatusLabels } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { aProject } from '../../test/fixtures'
import { ReportViewPage } from './ReportViewPage'
import { drivingAFailedRead } from '../../test/renderFailures'

/**
 * The on-screen report — a job first, a report second.
 *
 * The page is rendered through a `<Route>` rather than bare, for §69's reason: mounted bare, both
 * route params are `''`, every query is disabled and permanently pending, and an assertion that
 * something is *absent* passes against a page with no logic in it at all.
 *
 * What it asserts, and why each would otherwise ship:
 *
 *   1. **A running job shows the job running**, not a skeleton for a fetch that has not been made.
 *      `useReportModel` is gated on `modelUrl`, and a dependent query left rendering its skeleton
 *      is the eternal-spinner defect `data-fetching` names by name.
 *   2. **A FAILED job renders the worker's sentence and offers the way back.** An error naming a
 *      state with no path out of it is the design bug `ui-rules` §4 names.
 *   3. **A COMPLETED job renders the STORED model** — the artifact the export was rendered from,
 *      fetched from the URL the server composed, verbatim. If this screen ever recomputed instead,
 *      §6e's "what you see is what exports" would become a promise rather than a property.
 *   4. **The 403 is worded, not generic.** ~~And it is the slice's finding: the artifact route is
 *      gated on `report:export`, so a persona holding `report:view` alone can watch this job
 *      succeed and then be refused the report it watched being made.~~ → closed in §227, which
 *      gave the model its own route under `report:view`. What is left is the ordinary refusal,
 *      still worded rather than generic.
 *   5. **A COMPLETED job with no stored model says so.** With no URL there is no request to
 *      succeed or fail, so anything less than an explicit statement is a spinner that never stops.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'
const JOB = 'job-1'
const MODEL_URL = `/api/v1/projects/${PROJECT}/reports/jobs/${JOB}/model`
const DOWNLOAD_URL = `/api/v1/projects/${PROJECT}/reports/artifacts/artifact-1/content`

const job = (over: Record<string, unknown> = {}) => ({
  jobId: JOB,
  type: 'run_summary',
  status: 'COMPLETED',
  format: 'HTML',
  requestedAt: '2026-08-16T09:30:00Z',
  downloadUrl: DOWNLOAD_URL,
  modelUrl: MODEL_URL,
  error: null,
  ...over,
})

const storedModel = {
  header: {
    reportType: 'run_summary',
    description: 'A single run: status breakdown, progress per tester, and results by suite.',
    company: 'Northwind Trading',
    projectScope: 'ACME — Acme Platform',
    scopeNote: 'This report covers ACME — Acme Platform in full and no other project.',
    generatedBy: 'Dana Lead',
    generatedAt: '2026-08-04 12:00:00 UTC',
    selection: [{ label: 'runId', value: 'TR-3' }],
  },
  report: {
    title: 'Run summary — TR-3',
    metadata: [],
    sections: [
      {
        heading: 'Results',
        narrative: 'Shares are of the 2 executions recorded in this run.',
        table: { columns: ['Status', 'Executions'], rows: [['PASSED', '1']] },
        series: [],
      },
    ],
  },
}

/**
 * The project read and the job poll go through one spy, keyed on the path — the page makes both and
 * a blanket `mockResolvedValue` would answer the project request with a job, which renders a
 * breadcrumb reading "run_summary" and hides that the page never asked for the project at all.
 */
function answerReads(row: Record<string, unknown>) {
  return vi.spyOn(api, 'get').mockImplementation((path: string) => {
    if (path.includes('/reports/jobs/')) return Promise.resolve(row as never)
    return Promise.resolve(aProject({ id: PROJECT, key: 'ACME', name: 'Acme Platform' }) as never)
  })
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/reports/jobs/${JOB}`}>
      <Routes>
        <Route path="/projects/:projectId/reports/jobs/:jobId" element={<ReportViewPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('ReportViewPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a running job as running, and asks for no model', async () => {
    answerReads(job({ status: 'RUNNING', downloadUrl: null, modelUrl: null }))
    const fetchArtifactJson = vi.spyOn(client, 'fetchArtifactJson')

    renderPage()

    // Derived from the label map rather than spelled out: `RUNNING` reads "Building", and a test
    // asserting its own wording would either fail on a copy change or, worse, pin copy the app
    // stopped using (`lib/labels.ts` is the one authority for every enum's words).
    expect(await screen.findByTestId('ReportView.Waiting.Text')).toHaveTextContent(
      reportJobStatusLabels.RUNNING,
    )
    // The gate resolving to a STATE rather than to limbo: no URL, so no request — and the page says
    // what is happening instead of showing the report's own skeleton.
    expect(fetchArtifactJson).not.toHaveBeenCalled()
    expect(screen.queryByTestId('ReportView.Model.Loading')).not.toBeInTheDocument()
  })

  it('renders a failed job with the worker’s sentence and a way back', async () => {
    answerReads(
      job({
        status: 'FAILED',
        downloadUrl: null,
        modelUrl: null,
        error: 'No run with that id belongs to this project.',
      }),
    )

    renderPage()

    expect(await screen.findByTestId('ReportView.Failed')).toHaveTextContent(
      /No run with that id belongs to this project/,
    )
    expect(screen.getByTestId('ReportView.Failed.Back')).toHaveAttribute(
      'href',
      `/projects/${PROJECT}/reports`,
    )
  })

  it('renders the stored model, fetched from the URL the server composed', async () => {
    answerReads(job())
    const fetchArtifactJson = vi
      .spyOn(client, 'fetchArtifactJson')
      .mockResolvedValue(storedModel)

    renderPage()

    expect(await screen.findByTestId('Report')).toBeInTheDocument()
    expect(screen.getByTestId('Report.Section.Grid')).toHaveTextContent('PASSED')
    // Verbatim — the server composes this URL so the client holds no second copy of the routing
    // (§67), and re-prefixing it is the `/api/v1/api/v1/…` 404 §89 found.
    expect(fetchArtifactJson).toHaveBeenCalledWith(MODEL_URL, expect.anything())
  })

  /*
    The title comes from the model rather than the job, so the browser tab, the heading and the
    exported document all read the same words. Before the model lands there is still a title, and it
    is the job's subject rather than a generic "Report" — `ui-rules` §6.
  */
  it('titles the page with the report’s own title once the model is readable', async () => {
    answerReads(job())
    vi.spyOn(client, 'fetchArtifactJson').mockResolvedValue(storedModel)

    renderPage()

    expect(await screen.findByRole('heading', { name: 'Run summary — TR-3' })).toBeInTheDocument()
  })

  it('explains the 403 rather than showing the generic permission error', async () => {
    // The failed model read is the subject: its 403 is what the page has to explain.
    drivingAFailedRead()
    answerReads(job())
    vi.spyOn(client, 'fetchArtifactJson').mockRejectedValue(new ApiError(403, 'Forbidden'))

    renderPage()

    const refusal = await screen.findByTestId('ReportView.Forbidden')
    // Names the key, because "the permission this view needs" is true and useless. ~~And says the
    // on-screen view costs the same key as the download, which is the surprising part.~~ → §227
    // gave the model its own route under `report:view`, so the surprising half is gone and the
    // key named is the one that is actually missing.
    expect(refusal).toHaveTextContent(/report:view/)
    expect(screen.queryByTestId('ReportView.Model.Error')).not.toBeInTheDocument()
  })

  it('says a completed job has no stored model rather than spinning for ever', async () => {
    answerReads(job({ modelUrl: null }))

    renderPage()

    expect(await screen.findByTestId('ReportView.NoModel')).toBeInTheDocument()
    // The other file is a different artifact and still exists, so the download stays offered.
    expect(screen.getByTestId('ReportView.Download')).toBeInTheDocument()
  })

  /*
    A parse failure must reach the region as a FAILURE. Cast rather than parsed, a drifted document
    renders as a report with no sections — which is what a selection matching nothing looks like,
    and the two must never be confusable.
  */
  it('renders a drifted stored model as a failed read, not as an empty report', async () => {
    // The failed read is the subject: a drifted body must read as failed rather than empty.
    drivingAFailedRead()
    answerReads(job())
    vi.spyOn(client, 'fetchArtifactJson').mockResolvedValue({ report: { title: 'Half a model' } })

    renderPage()

    expect(await screen.findByTestId('ReportView.Model.Error')).toBeInTheDocument()
    expect(screen.queryByTestId('Report.NoSections')).not.toBeInTheDocument()
  })
})
