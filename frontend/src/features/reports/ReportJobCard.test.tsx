import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as client from '../../api/client'
import { api } from '../../api/client'
import * as download from '../../lib/download'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ReportJobCard } from './ReportJobCard'

/**
 * The card that watches one generation job.
 *
 * Everything here is a behaviour the build cannot see, and three of the four are the specific
 * defects the M2.5 plan names for this screen:
 *
 *   1. **A FAILED job stops the poll.** A poller written as `status !== 'COMPLETED'` keeps asking
 *      a dead job for ever — on every card, for as long as the tab is open — and renders as a
 *      report permanently about to be ready. The assertion is a request COUNT that stops moving,
 *      because a card that *looks* right is exactly what that defect produces.
 *   2. **FAILED is visibly a failure, carrying the server's own sentence** — not an empty card,
 *      which is what a report with no rows would be (§85's 404-vs-`200 []`, one screen over). Its
 *      counterpart: no download button, because `downloadUrl` is null for ever on a failed job.
 *   3. **The download goes through `fetchArtifact`, not an anchor.** The session is a bearer token
 *      in a header, so an `<a href="/api/v1/…">` sends no credential and answers 401 — a link to a
 *      sign-in failure that renders as a working link. This test asserts the URL the SERVER sent
 *      is the URL fetched, verbatim: composing it client-side is the §67 defect the contract's
 *      `downloadUrl` exists to prevent.
 *   4. **A failed download is handed back beside the button**, with the row still on screen. The
 *      report exists; the fetch did not.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'
const JOB = 'job-1'
const CONTENT_URL = `/api/v1/projects/${PROJECT}/reports/artifacts/artifact-1/content`

const job = (over: Record<string, unknown> = {}) => ({
  jobId: JOB,
  type: 'run_summary',
  status: 'RUNNING',
  format: 'HTML',
  downloadUrl: null,
  modelUrl: null,
  error: null,
  ...over,
})

function renderCard() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <ReportJobCard projectId={PROJECT} jobId={JOB} />
    </TestProviders>,
  )
}

describe('ReportJobCard', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders a FAILED job as a failure carrying the server’s sentence, with no download', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(
      job({
        status: 'FAILED',
        error: 'No run with that id belongs to this project.',
      }) as never,
    )

    renderCard()

    expect(await screen.findByTestId('Reports.Job.Failed')).toHaveTextContent(
      /No run with that id belongs to this project/i,
    )
    expect(screen.getByTestId('Reports.Job.Status')).toHaveTextContent('Failed')
    expect(screen.queryByTestId('Reports.Job.Download')).not.toBeInTheDocument()
    // Not still spinning: the poll's terminal check and the card's are one predicate, and a card
    // showing a spinner beside a failure is what it looks like when they disagree.
    expect(screen.queryByTestId('Reports.Job.Spinner')).not.toBeInTheDocument()
  })

  /**
   * The poll's terminal check, asserted as a PAIR — and the pair is the whole test.
   *
   * "A FAILED job stops polling" alone is satisfied by a component that never polls at all, by a
   * query client that disabled intervals, and by a wait shorter than one interval. Only the
   * running half proves the clock is actually running, and only the failed half proves the check
   * exists; each stops the other being vacuous. §84's rule, applied to a timer.
   *
   * Real time rather than fake timers: TanStack's interval, `userEvent`'s advance-on-act and
   * `waitFor`'s scheduler all read the same clock, and driving one of them by hand desynchronises
   * the other two. The cost is one interval per half, which is why the interval is named here and
   * the wait derived from it rather than both being magic numbers that can drift apart.
   */
  it('keeps polling a RUNNING job and stops on a FAILED one', async () => {
    const POLL_INTERVAL_MS = 2_000
    const oneAndAHalfIntervals = POLL_INTERVAL_MS * 1.5
    const settle = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

    const running = vi.spyOn(api, 'get').mockResolvedValue(job({ status: 'RUNNING' }) as never)
    const first = renderCard()
    await screen.findByTestId('Reports.Job.Spinner')
    const afterRunningFirstAnswer = running.mock.calls.length
    await settle(oneAndAHalfIntervals)
    expect(running.mock.calls.length).toBeGreaterThan(afterRunningFirstAnswer)
    first.unmount()

    vi.restoreAllMocks()

    const failed = vi
      .spyOn(api, 'get')
      .mockResolvedValue(job({ status: 'FAILED', error: 'It did not work.' }) as never)
    renderCard()
    await screen.findByTestId('Reports.Job.Failed')
    const afterFailedFirstAnswer = failed.mock.calls.length
    await settle(oneAndAHalfIntervals)
    expect(failed.mock.calls.length).toBe(afterFailedFirstAnswer)
  })

  it('downloads through the authenticated fetch, using the URL the server sent verbatim', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(
      job({ status: 'COMPLETED', downloadUrl: CONTENT_URL, modelUrl: CONTENT_URL }) as never,
    )
    const fetchArtifact = vi.spyOn(client, 'fetchArtifact').mockResolvedValue({
      blob: new Blob(['<!DOCTYPE html>'], { type: 'text/html' }),
      filename: 'run_summary-artifact-1.html',
    })
    const saveBlob = vi.spyOn(download, 'saveBlob').mockImplementation(() => {})

    renderCard()

    await userEvent.click(await screen.findByTestId('Reports.Job.Download'))

    await waitFor(() => expect(fetchArtifact).toHaveBeenCalled())
    expect(fetchArtifact).toHaveBeenCalledWith(CONTENT_URL)
    // The server's `Content-Disposition` name wins over anything composed here — it is the only
    // party that knows the artifact id.
    expect(saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'run_summary-artifact-1.html')
  })

  it('falls back to a composed filename when the response named none', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(
      job({ status: 'COMPLETED', downloadUrl: CONTENT_URL }) as never,
    )
    vi.spyOn(client, 'fetchArtifact').mockResolvedValue({
      blob: new Blob(['# Run summary'], { type: 'text/markdown' }),
      filename: null,
    })
    const saveBlob = vi.spyOn(download, 'saveBlob').mockImplementation(() => {})

    renderCard()

    await userEvent.click(await screen.findByTestId('Reports.Job.Download'))

    await waitFor(() => expect(saveBlob).toHaveBeenCalled())
    const [, filename] = saveBlob.mock.calls[0] as [Blob, string]
    // Extension from the format the job echoed back, never from the blob's media type: a
    // deployment that adds a renderer serves a type this client has never heard of.
    expect(filename).toMatch(/^run_summary-\d{4}-\d{2}-\d{2}\.html$/)
  })

  it('hands a failed download back beside the button, keeping the row', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(
      job({ status: 'COMPLETED', downloadUrl: CONTENT_URL }) as never,
    )
    vi.spyOn(client, 'fetchArtifact').mockRejectedValue(
      new client.ApiError(403, 'Forbidden'),
    )

    renderCard()

    await userEvent.click(await screen.findByTestId('Reports.Job.Download'))

    expect(await screen.findByTestId('Reports.Job.DownloadError')).toBeInTheDocument()
    // Still offered — the report exists and the next attempt may well work.
    expect(screen.getByTestId('Reports.Job.Download')).toBeEnabled()
  })
})
