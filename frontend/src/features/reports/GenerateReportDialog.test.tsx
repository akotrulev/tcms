import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { GenerateReportDialog } from './GenerateReportDialog'

/**
 * Asking for a report.
 *
 * Four behaviours are locked here, and none of them is visible to `tsc` or to oxlint:
 *
 *   1. **The request body is the contract's.** `runId` goes inside `parameters`, not beside
 *      `type` and `format` — the server freezes that object onto the artifact verbatim and prints
 *      it as the report's selection block (§88), so a `runId` sent at the top level is dropped
 *      silently, produces a job that FAILS with "this report needs a run", and looks on screen
 *      exactly like a correct request. Nothing else in the app can catch that shape.
 *   2. **A project with no runs cannot ask for a run summary**, and says why before the click.
 *   3. **The format refusal lands on the format field.** PDF is offered on purpose — the enum is
 *      not narrowed to what a deployment has installed — so the 400 naming what the server *can*
 *      produce is the whole affordance, and it is worthless in a banner where the picker is not.
 *   4. **PDF is offered at all.** Asserted directly, because "don't render an action the backend
 *      rejects" is the rule a later reader would apply to remove it, and the reason not to is the
 *      contract's own (`ReportFormat`'s comment). A test is where that decision survives.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const runs = [
  {
    id: 'run-1',
    key: 'TR-1',
    name: 'Regression 4.3.0',
    planId: 'plan-1',
    planKey: 'TP-1',
    status: 'IN_PROGRESS',
    releaseVersion: '4.3.0',
    buildNumber: null,
    source: 'MANUAL',
    startedAt: '2026-08-01T09:00:00Z',
    completedAt: null,
    configuration: { id: 'cfg-1', name: 'Default', isDefault: true },
    counts: { total: 2, executed: 1, passed: 1, failed: 0, blocked: 0, skipped: 0, retest: 0, untested: 1 },
  },
]

function mockReads(rows: unknown[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/runs')) return rows
    if (path.includes('/me/permissions')) return heldPermissions(['report:export', 'report:view'], {}, PROJECT)
    return []
  }) as unknown as typeof api.get)
}

/**
 * MUI's `select` TextField is a listbox behind a button rather than a native `<select>`, so the
 * value reaches react-hook-form through the hidden input MUI updates on click. Driving it any
 * other way would not exercise the registration at all (`RunCreateDialog.test`'s note).
 */
async function chooseRun() {
  await userEvent.click(screen.getByRole('combobox', { name: /run/i }))
  await userEvent.click(await screen.findByTestId('Reports.Generate.Run.TR-1'))
}

async function chooseFormat(format: string) {
  await userEvent.click(screen.getByRole('combobox', { name: /format/i }))
  await userEvent.click(await screen.findByTestId(`Reports.Generate.Format.${format}`))
}

describe('GenerateReportDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends runId inside parameters, where the contract puts it', async () => {
    mockReads(runs)
    const post = vi.spyOn(api, 'post').mockResolvedValue({
      jobId: 'job-1',
      type: 'run_summary',
      status: 'PENDING',
      format: 'HTML',
      downloadUrl: null,
      modelUrl: null,
      error: null,
    } as never)
    const onQueued = vi.fn()

    render(
      <TestProviders client={createTestQueryClient()}>
        <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={onQueued} />
      </TestProviders>,
    )

    await screen.findByTestId('Reports.Generate.Run')
    await chooseRun()
    await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    // An exact object, not `toMatchObject`: the defect this guards is a `runId` that landed at the
    // top level, and a partial match is satisfied by a body carrying it in both places (§82's
    // rule, from the save that dropped a field).
    expect(body).toEqual({
      type: 'run_summary',
      format: 'HTML',
      parameters: { runId: 'run-1' },
    })
    expect(onQueued).toHaveBeenCalledWith(expect.objectContaining({ jobId: 'job-1' }))
  })

  it('refuses to ask for a run summary in a project with no runs, and says why', async () => {
    mockReads([])
    const post = vi.spyOn(api, 'post')

    render(
      <TestProviders client={createTestQueryClient()}>
        <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
      </TestProviders>,
    )

    expect(await screen.findByTestId('Reports.Generate.NoRuns')).toBeInTheDocument()
    expect(screen.getByTestId('Reports.Generate.Submit')).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  })

  it('offers PDF even though every deployment refuses it today', async () => {
    mockReads(runs)

    render(
      <TestProviders client={createTestQueryClient()}>
        <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Reports.Generate.Format')
    await userEvent.click(screen.getByRole('combobox', { name: /format/i }))

    // All three, because the set is the contract's and not this deployment's — a picker narrowed
    // to what is installed would be a copy of somebody else's configuration, wrong in the other
    // direction the day PDF is installed.
    expect(await screen.findByTestId('Reports.Generate.Format.HTML')).toBeInTheDocument()
    expect(screen.getByTestId('Reports.Generate.Format.MD')).toBeInTheDocument()
    expect(screen.getByTestId('Reports.Generate.Format.PDF')).toBeInTheDocument()
  })

  it("puts the server's format refusal on the format field, where the picker is", async () => {
    mockReads(runs)
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        format: ['PDF rendering is not installed on this server. It can produce: MD, HTML.'],
      }),
    )

    render(
      <TestProviders client={createTestQueryClient()}>
        <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Reports.Generate.Run')
    await chooseRun()
    await chooseFormat('PDF')
    await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

    expect(await screen.findByText(/PDF rendering is not installed/i)).toBeInTheDocument()
    // And NOT in the root banner — that is the branch a naive "show the detail" would take, and it
    // reads as a broken server rather than as a choice the picker can change.
    expect(screen.queryByTestId('Reports.Generate.Error')).not.toBeInTheDocument()
  })
})
