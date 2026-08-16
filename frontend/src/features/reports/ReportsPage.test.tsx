import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { aProject } from '../../test/fixtures'
import { ReportsPage } from './ReportsPage'
import type { PermissionKey, ReportDefinition, ReportJob } from '../../api/types'

/**
 * The reports screen.
 *
 * **The Generate gate, asserted as a PAIR.** `useCan` is fail-closed while it is still pending, so
 * `expect(button).toBeDisabled()` for a caller without the key is satisfied by the *loading* state
 * and passes against a component with no gate in it at all — §66's finding, which cost §80 four
 * tests and §76 a mutation that failed to redden. The holder's ENABLED assertion is the barrier:
 * it can only pass once the gate has resolved and granted, and it is what gives the refusal half
 * its meaning.
 *
 * **The job list is the server's, and the poll stops.** ~~The set of jobs this screen shows is the
 * set this tab created.~~ → §227. The polling pair moved here from `ReportJobCard.test.tsx` when
 * the card stopped fetching, and it is still a pair for the reason it always was: "a finished list
 * stops polling" alone is satisfied by a component that never polls, by a query client with
 * intervals disabled, and by a wait shorter than one interval. Only the running half proves the
 * clock is running; only the finished half proves the check exists.
 *
 * **The saved-definitions panel has a writer at last.** ~~There is deliberately no Save control, so
 * its absence is asserted too.~~ → §227: saving happens on the generate dialog, and each row
 * regenerates or deletes. What is asserted here is the two row controls and their two different
 * permission keys — `report:export` to regenerate, `report:view` to delete — which look wrong
 * together and are the contract's decision.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const aJob = (over: Partial<ReportJob> = {}): ReportJob => {
  const row: ReportJob = {
    jobId: 'job-1',
    type: 'run_summary',
    status: 'COMPLETED',
    format: 'HTML',
    requestedAt: '2026-08-16T09:30:00Z',
    downloadUrl: `/api/v1/projects/${PROJECT}/reports/artifacts/artifact-1/content`,
    modelUrl: `/api/v1/projects/${PROJECT}/reports/jobs/job-1/model`,
    error: null,
    ...over,
  }
  return row
}

const aDefinition = (over: Partial<ReportDefinition> = {}): ReportDefinition => {
  const row: ReportDefinition = {
    id: '22222222-2222-2222-2222-222222222222',
    type: 'run_summary',
    name: 'Release cycle summary',
    parameters: {},
    owner: {
      id: '33333333-3333-3333-3333-333333333333',
      displayName: 'A. Tester',
      email: 'a@acme.test',
    },
    ...over,
  }
  return row
}

function mockReads(
  held: PermissionKey[],
  rows: { jobs?: ReportJob[]; definitions?: ReportDefinition[] } = {},
) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/me/permissions')) return heldPermissions(held, {}, PROJECT)
    // Anchored on the exact suffix, because `…/reports` is a prefix of `…/reports/jobs` and a
    // `includes` arm would answer the list with the definitions.
    if (path.endsWith('/reports/jobs')) return rows.jobs ?? []
    if (path.endsWith('/reports')) return rows.definitions ?? []
    if (path.includes(`/projects/${PROJECT}`))
      return aProject({ id: PROJECT, key: 'ACME', name: 'Acme' })
    return []
  }) as unknown as typeof api.get)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/reports`}>
      <ReportsPage />
    </TestProviders>,
  )
}

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useParams: () => ({ projectId: PROJECT }) }
})

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('enables Generate for a holder of report:export and disables it for everyone else', async () => {
    mockReads(['report:export', 'report:view'])
    const holder = renderPage()
    // The barrier. Until this passes the gate is still pending, and a pending gate disables the
    // control for a reason that has nothing to do with permissions.
    await waitFor(() => expect(screen.getByTestId('Reports.GenerateButton')).toBeEnabled())
    holder.unmount()

    vi.restoreAllMocks()
    mockReads(['report:view'])
    renderPage()
    await waitFor(() => expect(screen.getByTestId('Reports.GenerateButton')).toBeDisabled())
    // Disabled, never hidden (§31): the person who has to go and ask can see the control exists.
    expect(screen.getByTestId('Reports.GenerateButton')).toBeInTheDocument()
  })

  it('renders every report the project has generated, not only this tab’s', async () => {
    mockReads(['report:export', 'report:view'], {
      jobs: [aJob(), aJob({ jobId: 'job-2', type: 'coverage_matrix', status: 'FAILED', error: 'No.' })],
    })
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Reports.Job')).toHaveLength(2))
    // The whole point of the endpoint: this session asked for none of them.
    expect(screen.getByTestId('Reports.Jobs.List')).toBeInTheDocument()
  })

  it('says so when the project has generated nothing, rather than hiding the panel', async () => {
    mockReads(['report:export', 'report:view'])
    renderPage()

    // An empty *server* list is a state of the data and gets an empty region — unlike the old
    // tab-local set, whose absence was not something the server could be asked about.
    expect(await screen.findByTestId('Reports.Jobs.Empty')).toBeInTheDocument()
  })

  /**
   * Real time rather than fake timers: TanStack's interval, `userEvent`'s advance-on-act and
   * `waitFor`'s scheduler all read the same clock, and driving one by hand desynchronises the other
   * two. The cost is one interval per half, which is why the interval is named here and the wait
   * derived from it rather than both being magic numbers that can drift apart.
   */
  it('keeps polling while a job is running and stops when none is', async () => {
    const POLL_INTERVAL_MS = 2_000
    const oneAndAHalfIntervals = POLL_INTERVAL_MS * 1.5
    const settle = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

    const running = mockReads(['report:view'], { jobs: [aJob({ status: 'RUNNING' })] })
    const first = renderPage()
    await screen.findByTestId('Reports.Job.Spinner')
    const afterFirstAnswer = running.mock.calls.length
    await settle(oneAndAHalfIntervals)
    expect(running.mock.calls.length).toBeGreaterThan(afterFirstAnswer)
    first.unmount()

    vi.restoreAllMocks()

    const finished = mockReads(['report:view'], { jobs: [aJob({ status: 'COMPLETED' })] })
    renderPage()
    await screen.findByTestId('Reports.Job.Download')
    const afterFinishedAnswer = finished.mock.calls.length
    await settle(oneAndAHalfIntervals)
    expect(finished.mock.calls.length).toBe(afterFinishedAnswer)
  })

  it('offers regenerate and delete on a saved report, under their two different keys', async () => {
    mockReads(['report:view'], { definitions: [aDefinition()] })
    renderPage()

    const row = await screen.findByTestId('Reports.Definitions.Row')
    // Delete costs `report:view`, which this caller holds — so it is enabled, and the enabled half
    // is the barrier that gives the disabled half below its meaning.
    await waitFor(() =>
      expect(within(row).getByTestId('Reports.Definitions.Delete')).toBeEnabled(),
    )
    // Regenerating is generating, so it costs `report:export`, which this caller does not hold.
    expect(within(row).getByTestId('Reports.Definitions.Generate')).toBeDisabled()
  })

  it('confirms a delete by naming the report, and says the reports it produced survive', async () => {
    mockReads(['report:view'], { definitions: [aDefinition()] })
    renderPage()

    const row = await screen.findByTestId('Reports.Definitions.Row')
    await waitFor(() =>
      expect(within(row).getByTestId('Reports.Definitions.Delete')).toBeEnabled(),
    )
    await userEvent.click(within(row).getByTestId('Reports.Definitions.Delete'))

    // Named, not "are you sure?" — and the consequence a reader is most likely to have backwards
    // is the one the dialog spends a sentence on.
    expect(await screen.findByTestId('Reports.Definitions.Confirm.Target')).toHaveTextContent(
      /Release cycle summary/,
    )
    expect(screen.getByTestId('Reports.Definitions.Confirm')).toHaveTextContent(
      /remain downloadable/i,
    )
  })
})
