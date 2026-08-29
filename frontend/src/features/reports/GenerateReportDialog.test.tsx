import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { aConfiguration, aDocPageSummary, aPlan, aRun, noCounts } from '../../test/fixtures'
import { GenerateReportDialog } from './GenerateReportDialog'
import { drivingAFailedWrite } from '../../test/renderFailures'

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
 *   3. **The format refusal lands on the format field.** The 400 naming what the server *can*
 *      produce is the whole affordance, and it is worthless in a banner where the picker is not.
 *      Still reachable after B5, and the test says how: the capability read is a courtesy to the
 *      picker and never the authority, so a format it offered can still be refused.
 *   4. ~~**PDF is offered at all.**~~ → **what the SERVER says it can render is offered, both
 *      ways** (B5). The old assertion defended a real decision — the enum is not narrowed to what
 *      is installed — by making every deployment offer an option every deployment refuses. Two
 *      tests now: PDF absent where the server does not have it, present where it does. The
 *      decision it was protecting is what makes the second one possible.
 *
 * R6 adds four more, and three of them are about the *second* report being reachable and honest:
 *
 *   5. **A project with no runs may still ask for the aggregate report**, asserted as
 *      `toBeEnabled()`. The inverted assertion is the finding: `toBeDisabled()` passes against the
 *      dialog as it stood before R6, so the test that "covers" this behaviour is green while the
 *      report is unreachable in precisely the projects that have not run anything yet (§66, §99).
 *   6. **The date range is converted, not passed through.** The form's `to` is inclusive and the
 *      API's is exclusive, so the assertion is on the *instant* — 31 August typed, 1 September
 *      sent. Getting this wrong drops the last day of every range and produces a smaller number
 *      that looks entirely reasonable.
 *   7. **Untouched facets are absent from `parameters`**, not present and empty. The server reads
 *      an empty array as absent either way, so this is not about the numbers: `parameters` is
 *      frozen onto the artifact and printed as the report's selection block, where `"planIds": []`
 *      tells the reader a filter was applied that was not.
 *   8. **A backwards range is refused here**, because the server refuses it in the worker — the
 *      dialog is closed by then and the answer is a FAILED job card.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const runs = [
  aRun({
    id: 'run-1',
    key: 'TR-1',
    name: 'Regression 4.3.0',
    planId: 'plan-1',
    planKey: 'TP-1',
    status: 'IN_PROGRESS',
    releaseVersion: '4.3.0',
    startedAt: '2026-08-01T09:00:00Z',
    configuration: aConfiguration({ id: 'cfg-1' }),
    // The six the contract declares. This fixture also carried `executed`, `retest` and
    // `untested`, none of which `ResultCounts` has ever had: the server folds RETEST, UNTESTED and
    // WIP into `notRun` (`ExecutionViews`), and the three invented ones were a vocabulary no
    // endpoint can answer with.
    counts: noCounts({ total: 2, passed: 1, notRun: 1 }),
  }),
]

const plans = [
  aPlan({ id: 'plan-1', key: 'TP-1', name: 'Release 4.3.0', status: 'APPROVED' }),
  aPlan({ id: 'plan-2', key: 'TP-2', name: 'Hardening', status: 'APPROVED' }),
]

const pages = [
  aDocPageSummary({
    id: 'page-1',
    key: 'DOC-1',
    title: 'Payments',
    slug: 'payments',
    status: 'PUBLISHED',
    updatedAt: '2026-08-01T09:00:00Z',
  }),
]

/**
 * @param formats what this deployment says it can render (B5). Two by default, which is every
 *     shipped image; a test that needs PDF offered says so, because the point of the parameter is
 *     that the picker is the server's answer and not a constant.
 */
function mockReads(
  rows: unknown[],
  docPages: unknown[] = pages,
  formats: string[] = ['HTML', 'MD'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/doc-pages')) return docPages
    // `types` is required by the contract as of §254 — the report catalogue is now an ANSWER
    // rather than something a caller discovers by being refused. The picker does not read it yet;
    // the conformance check does, and a fixture short of a required field is a fixture the
    // server would never send.
    if (path.includes('/reports/capabilities')) {
      return { formats, types: ['aggregate_execution', 'coverage_matrix', 'run_summary'] }
    }
    if (path.includes('/runs')) return rows
    if (path.includes('/plans')) return plans
    if (path.includes('/me/permissions')) return heldPermissions(['report:export', 'report:view'], {}, PROJECT)
    return []
  }) as unknown as typeof api.get)
}

async function chooseType(type: string) {
  await userEvent.click(screen.getByRole('combobox', { name: /report/i }))
  await userEvent.click(await screen.findByTestId(`Reports.Generate.Type.${type}`))
}

function queued(type: string) {
  return {
    jobId: 'job-1',
    type,
    status: 'PENDING',
    format: 'HTML',
    requestedAt: '2026-08-16T09:30:00Z',
    downloadUrl: null,
    modelUrl: null,
    error: null,
    // PENDING, so there is no artifact to have a size — null, and required, per 005 S1.
    sizeBytes: null,
  } as never
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
      requestedAt: '2026-08-16T09:30:00Z',
      downloadUrl: null,
      modelUrl: null,
      error: null,
      sizeBytes: null,
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

  it('offers what this server can render, and not what the enum can name (B5)', async () => {
    mockReads(runs)

    render(
      <TestProviders client={createTestQueryClient()}>
        <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Reports.Generate.Format')
    await userEvent.click(screen.getByRole('combobox', { name: /format/i }))

    expect(await screen.findByTestId('Reports.Generate.Format.HTML')).toBeInTheDocument()
    expect(screen.getByTestId('Reports.Generate.Format.MD')).toBeInTheDocument()
    // ~~All three, because the set is the contract's and not this deployment's.~~ → the enum is
    // still the contract's and the PICKER is this deployment's: PDF is in `ReportFormat`, in the
    // labels, and in the request body's enum, and it is not offered here because this server
    // answered that it cannot render one. The old assertion was the right decision read one step
    // too far — it made every deployment offer an option every deployment refuses.
    expect(screen.queryByTestId('Reports.Generate.Format.PDF')).not.toBeInTheDocument()
  })

  it('offers PDF where the server says it has a renderer', async () => {
    // The other direction, and the reason the enum was never narrowed: a deployment that installs
    // the PDF renderer must be askable for one without a contract change or a new build.
    mockReads(runs, pages, ['HTML', 'MD', 'PDF'])

    render(
      <TestProviders client={createTestQueryClient()}>
        <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Reports.Generate.Format')
    await userEvent.click(screen.getByRole('combobox', { name: /format/i }))

    expect(await screen.findByTestId('Reports.Generate.Format.PDF')).toBeInTheDocument()
  })

  it("puts the server's format refusal on the format field, where the picker is", async () => {
    // The refused write is the subject: this asserts which field the refusal lands on.
    drivingAFailedWrite()
    // Offered AND refused, which is not a contradiction: the capability read is a courtesy to the
    // picker and never the authority. An operator removing the renderer between the two calls is
    // the ordinary way to be here, and it is why the 400 survives B5 unchanged.
    mockReads(runs, pages, ['HTML', 'MD', 'PDF'])
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

  describe('the aggregate execution report (R6)', () => {
    it('can be asked for in a project with no runs at all', async () => {
      mockReads([])

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('aggregate_execution')

      // `toBeEnabled`, and that direction is the point: the pre-R6 dialog disabled Generate for
      // any project with no runs, so an assertion that the button is DISABLED passes against a
      // dialog where this report cannot be requested at all.
      expect(screen.getByTestId('Reports.Generate.Submit')).toBeEnabled()
      // And the run summary's explanation must not be standing over a form that needs no run.
      expect(screen.queryByTestId('Reports.Generate.NoRuns')).not.toBeInTheDocument()
      expect(screen.queryByTestId('Reports.Generate.Run')).not.toBeInTheDocument()
    })

    it('sends only what was narrowed on, with the policy always stated', async () => {
      mockReads(runs)
      const post = vi.spyOn(api, 'post').mockResolvedValue(queued('aggregate_execution'))

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('aggregate_execution')
      await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
      // Exact, so an empty `planIds`/`featureIds` or a `runId` riding along would redden this.
      // `policy` is present at its default on purpose — it is the one parameter whose absence
      // changes what the numbers mean rather than which rows produced them.
      expect(body).toEqual({
        type: 'aggregate_execution',
        format: 'HTML',
        parameters: { policy: 'LATEST' },
      })
    })

    it('sends the day AFTER the last day, because the wire range is half-open', async () => {
      mockReads(runs)
      const post = vi.spyOn(api, 'post').mockResolvedValue(queued('aggregate_execution'))

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('aggregate_execution')

      await userEvent.click(screen.getByRole('combobox', { name: /aggregation policy/i }))
      await userEvent.click(await screen.findByTestId('Reports.Generate.Policy.WORST'))
      // `fireEvent` rather than `type` for the two date inputs: jsdom's date input takes a whole
      // value and not keystrokes, and react-hook-form registers the same `onChange` either way.
      fireEvent.change(screen.getByTestId('Reports.Generate.From'), {
        target: { value: '2026-08-01' },
      })
      fireEvent.change(screen.getByTestId('Reports.Generate.To'), {
        target: { value: '2026-08-31' },
      })
      await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
      expect(body).toEqual({
        type: 'aggregate_execution',
        format: 'HTML',
        parameters: {
          policy: 'WORST',
          from: '2026-08-01T00:00:00Z',
          // Not 2026-08-31. The API's `to` is exclusive, so sending the typed date verbatim would
          // silently drop the 31st from every August report.
          to: '2026-09-01T00:00:00Z',
        },
      })
    })

    it('refuses a backwards range here, because the server refuses it in the worker', async () => {
      mockReads(runs)
      const post = vi.spyOn(api, 'post')

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('aggregate_execution')
      fireEvent.change(screen.getByTestId('Reports.Generate.From'), {
        target: { value: '2026-08-31' },
      })
      fireEvent.change(screen.getByTestId('Reports.Generate.To'), {
        target: { value: '2026-08-01' },
      })
      await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

      expect(await screen.findByText(/covers nothing/i)).toBeInTheDocument()
      // Nothing was sent, which is the whole value: the server's own refusal for this arrives as a
      // FAILED job minutes later, on a card the person who typed it has already navigated away from.
      expect(post).not.toHaveBeenCalled()
    })
  })

  /**
   * §115's third report, and the two things about it a gate cannot see.
   *
   * The first is the request body, for the same reason `runId` has a test: `docPageId` lives inside
   * `parameters`, and sent at the top level it is dropped silently and the job FAILS minutes later
   * saying the report needs a page.
   *
   * The second is the one this slice would most plausibly have shipped broken. R6 spelled the run
   * gate `!isAggregate`, which was the same statement as "needs a run" while there were two types
   * and stops being one at three: `coverage_matrix` is not the aggregate, so it would have inherited
   * the run gate and been **unreachable in every project with documentation and no runs**. That is
   * the defect R6 itself fixed, arriving a second time through the shape of the fix.
   */
  describe('the coverage matrix (§115)', () => {
    it('sends docPageId inside parameters, and nothing else', async () => {
      mockReads(runs)
      const post = vi.spyOn(api, 'post').mockResolvedValue(queued('coverage_matrix'))

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('coverage_matrix')
      await userEvent.click(screen.getByRole('combobox', { name: /document/i }))
      await userEvent.click(await screen.findByTestId('Reports.Generate.Document.DOC-1'))
      await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
      // Exact: a `runId` or a `policy` riding along would be printed in the artifact's selection
      // block as a filter the reader believes narrowed the numbers.
      expect(body).toEqual({
        type: 'coverage_matrix',
        format: 'HTML',
        parameters: { docPageId: 'page-1' },
      })
    })

    it('can be asked for in a project with no runs at all', async () => {
      // THE regression test for the negated gate. With `!isAggregate` this button is disabled and
      // the run picker is rendered, so both assertions redden — which is what makes them worth
      // more than the passing version of themselves.
      mockReads([])

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('coverage_matrix')

      expect(screen.getByTestId('Reports.Generate.Submit')).toBeEnabled()
      expect(screen.queryByTestId('Reports.Generate.NoRuns')).not.toBeInTheDocument()
      expect(screen.queryByTestId('Reports.Generate.Run')).not.toBeInTheDocument()
    })

    it('cannot be asked for in a project with no documentation, and says why', async () => {
      // The mirror of the run summary's gate, and a real one rather than symmetry: this report's
      // whole spine is a document, so a project with no pages has nothing to report on.
      mockReads(runs, [])

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('coverage_matrix')

      expect(await screen.findByTestId('Reports.Generate.NoDocuments')).toBeInTheDocument()
      expect(screen.getByTestId('Reports.Generate.Submit')).toBeDisabled()
    })

    it('requires the document, because the server would only refuse it in the worker', async () => {
      mockReads(runs)
      const post = vi.spyOn(api, 'post')

      render(
        <TestProviders client={createTestQueryClient()}>
          <GenerateReportDialog projectId={PROJECT} open onClose={() => {}} onQueued={() => {}} />
        </TestProviders>,
      )

      await screen.findByTestId('Reports.Generate.Type')
      await chooseType('coverage_matrix')
      await userEvent.click(screen.getByTestId('Reports.Generate.Submit'))

      expect(await screen.findByText(/choose the document/i)).toBeInTheDocument()
      expect(post).not.toHaveBeenCalled()
    })
  })
})
