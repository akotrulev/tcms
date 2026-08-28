import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as client from '../../api/client'
import * as download from '../../lib/download'
import type { ReportJob } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ReportJobCard } from './ReportJobCard'

/**
 * The card that renders one generation job.
 *
 * **It fetches nothing since §227** — the reports screen holds one poll over `GET …/reports/jobs`
 * and hands each card its row, so the polling pair that used to live here (a RUNNING job keeps
 * asking, a FAILED one stops) is asserted in `ReportsPage.test.tsx` over the list. What is left
 * here is what the card decides from a row, and three of the four are the specific defects the
 * M2.5 plan names for this screen:
 *
 *   1. **FAILED is visibly a failure, carrying the server's own sentence** — not an empty card,
 *      which is what a report with no rows would be (§85's 404-vs-`200 []`, one screen over). Its
 *      counterpart: no download button, because `downloadUrl` is null for ever on a failed job.
 *   2. **The download goes through `fetchArtifact`, not an anchor.** The session is a bearer token
 *      in a header, so an `<a href="/api/v1/…">` sends no credential and answers 401 — a link to a
 *      sign-in failure that renders as a working link. This test asserts the URL the SERVER sent
 *      is the URL fetched, verbatim: composing it client-side is the §67 defect the contract's
 *      `downloadUrl` exists to prevent.
 *   3. **A failed download is handed back beside the button**, with the row still on screen. The
 *      report exists; the fetch did not.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'
const JOB = 'job-1'
const CONTENT_URL = `/api/v1/projects/${PROJECT}/reports/artifacts/artifact-1/content`
const MODEL_URL = `/api/v1/projects/${PROJECT}/reports/jobs/${JOB}/model`

const job = (over: Partial<ReportJob> = {}): ReportJob => {
  const row: ReportJob = {
    jobId: JOB,
    type: 'run_summary',
    status: 'RUNNING',
    format: 'HTML',
    requestedAt: '2026-08-16T09:30:00Z',
    // Required and nullable since 005 S1. Null is the honest default here: the fixture's base state
    // is a RUNNING job, and a job that has not finished has written no artifact to have a size.
    sizeBytes: null,
    downloadUrl: null,
    modelUrl: null,
    error: null,
    ...over,
  }
  return row
}

function renderCard(row: ReportJob) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <ReportJobCard projectId={PROJECT} job={row} />
    </TestProviders>,
  )
}

describe('ReportJobCard', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a FAILED job as a failure carrying the server’s sentence, with no download', async () => {
    renderCard(
      job({
        status: 'FAILED',
        error: 'No run with that id belongs to this project.',
      }),
    )

    expect(await screen.findByTestId('Reports.Job.Failed')).toHaveTextContent(
      /No run with that id belongs to this project/i,
    )
    expect(screen.getByTestId('Reports.Job.Status')).toHaveTextContent('Failed')
    expect(screen.queryByTestId('Reports.Job.Download')).not.toBeInTheDocument()
    // Not still spinning: the terminal check the list polls on and the card's rendering are one
    // predicate, and a card showing a spinner beside a failure is what it looks like when they
    // disagree.
    expect(screen.queryByTestId('Reports.Job.Spinner')).not.toBeInTheDocument()
  })

  it('downloads through the authenticated fetch, using the URL the server sent verbatim', async () => {
    const fetchArtifact = vi.spyOn(client, 'fetchArtifact').mockResolvedValue({
      blob: new Blob(['<!DOCTYPE html>'], { type: 'text/html' }),
      filename: 'run_summary-artifact-1.html',
    })
    const saveBlob = vi.spyOn(download, 'saveBlob').mockImplementation(() => {})

    renderCard(job({ status: 'COMPLETED', downloadUrl: CONTENT_URL, modelUrl: MODEL_URL }))

    await userEvent.click(await screen.findByTestId('Reports.Job.Download'))

    await waitFor(() => expect(fetchArtifact).toHaveBeenCalled())
    expect(fetchArtifact).toHaveBeenCalledWith(CONTENT_URL)
    // The server's `Content-Disposition` name wins over anything composed here — it is the only
    // party that knows the artifact id.
    expect(saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'run_summary-artifact-1.html')
  })

  it('falls back to a composed filename when the response named none', async () => {
    vi.spyOn(client, 'fetchArtifact').mockResolvedValue({
      blob: new Blob(['# Run summary'], { type: 'text/markdown' }),
      filename: null,
    })
    const saveBlob = vi.spyOn(download, 'saveBlob').mockImplementation(() => {})

    renderCard(job({ status: 'COMPLETED', downloadUrl: CONTENT_URL }))

    await userEvent.click(await screen.findByTestId('Reports.Job.Download'))

    await waitFor(() => expect(saveBlob).toHaveBeenCalled())
    const [, filename] = saveBlob.mock.calls[0] as [Blob, string]
    // Extension from the format the job echoed back, never from the blob's media type: a
    // deployment that adds a renderer serves a type this client has never heard of.
    expect(filename).toMatch(/^run_summary-\d{4}-\d{2}-\d{2}\.html$/)
  })

  /*
    F2's affordance. It keys on `modelUrl` rather than on the status, for the same reason the
    download keys on `downloadUrl`: the two URLs are what the *server* says exists, and a job from a
    deployment older than §88's stored model would otherwise render a link to a page that can only
    say the model is missing.

    It is a real `<Link>`, asserted through its `href`: an `onClick` navigation would give up
    middle-click, ⌘-click and copy-link, which is how a report is handed to a colleague.
  */
  it('offers the report view for a job with a stored model, as a real link', async () => {
    renderCard(job({ status: 'COMPLETED', downloadUrl: CONTENT_URL, modelUrl: MODEL_URL }))

    expect(await screen.findByTestId('Reports.Job.View')).toHaveAttribute(
      'href',
      `/projects/${PROJECT}/reports/jobs/${JOB}`,
    )
  })

  it('offers no report view for a completed job with no stored model', async () => {
    renderCard(job({ status: 'COMPLETED', downloadUrl: CONTENT_URL, modelUrl: null }))

    // The download still stands — it is a different artifact, and the pair is what stops one
    // missing URL hiding the other affordance.
    expect(await screen.findByTestId('Reports.Job.Download')).toBeInTheDocument()
    expect(screen.queryByTestId('Reports.Job.View')).not.toBeInTheDocument()
  })

  it('hands a failed download back beside the button, keeping the row', async () => {
    vi.spyOn(client, 'fetchArtifact').mockRejectedValue(new client.ApiError(403, 'Forbidden'))

    renderCard(job({ status: 'COMPLETED', downloadUrl: CONTENT_URL }))

    await userEvent.click(await screen.findByTestId('Reports.Job.Download'))

    // `<testId>.Error`, composed by `DownloadReportButton` — F2 extracted the control so the card
    // and the report view share one download, and the failure renders where the button is.
    expect(await screen.findByTestId('Reports.Job.Download.Error')).toBeInTheDocument()
    // Still offered — the report exists and the next attempt may well work.
    expect(screen.getByTestId('Reports.Job.Download')).toBeEnabled()
  })
})
