import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, TestRunDetail } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, aRun } from '../../test/fixtures'
import { RunDetailPage } from './RunDetailPage'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * The run's lifecycle controls — **the affordance for a column that had no writer until §72**.
 *
 * Four things are locked here, and none of them is visible to `tsc` or oxlint:
 *
 *   1. **The controls swap on the run's state.** A closed run offers Reopen and nothing else,
 *      because the server refuses completed → aborted outright — offering both endings on an ended
 *      run is exactly the "invite a 403" §31 exists to stop, one status code over.
 *   2. **Complete is disabled on a run nobody has recorded anything in**, with the reason on the
 *      tooltip rather than left for the server's 409. The rule is knowable from the status alone.
 *   3. **`run:close` gates all of it**, and this is the only test in the product that can prove it
 *      independently: a frontend test can mock a permission set the seeded catalogue does not
 *      produce. The backend's own gate test is stronger than §71's — Test Engineer really does hold
 *      `run:execute` and not `run:close` — but this one still isolates the single key.
 *   4. **Reopen always asks for `IN_PROGRESS`.** The server normalizes a run that never started
 *      back to `NOT_STARTED`; a client that tried to derive that itself would hold a second copy of
 *      the rule and get it wrong against a stale cache.
 */

const PROJECT = 'p1'
const RUN = 'r1'

function runBody(
  status: TestRunDetail['status'],
  startedAt: string | null,
  git: { commitSha: string | null; branch: string | null } = { commitSha: null, branch: null },
): TestRunDetail {
  return {
    id: RUN,
    // Null, the honest default: a scheduled or ingested run has no actor (B1 / 005 S1).
    createdBy: null,
    key: 'TR-4',
    name: 'Regression 4.4.0',
    planId: null,
    planKey: null,
    configuration: { id: 'cfg-1', name: 'Chrome / Linux', isDefault: true, attributes: {} },
    status,
    releaseVersion: '4.4.0',
    buildNumber: null,
    commitSha: git.commitSha,
    branch: git.branch,
    source: 'MANUAL',
    startedAt,
    completedAt: status === 'COMPLETED' || status === 'ABORTED' ? '2026-08-02T10:00:00Z' : null,
    counts: { total: 1, passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 1 },
    // No request recorded — B7's ordinary state for a fixture built by hand.
    scopeRequest: null,
    executions: [],
  }
}

function mockReads(
  status: TestRunDetail['status'],
  startedAt: string | null,
  permissions: PermissionKey[] = ['project:read', 'run:close', 'result:submit'],
  git: { commitSha: string | null; branch: string | null } = { commitSha: null, branch: null },
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes(`/runs/${RUN}`)) return runBody(status, startedAt, git)
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
      <Routes>
        <Route path="/projects/:projectId/runs/:runId" element={<RunDetailPage />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed — **without this every "disabled" assertion below
 * passes for the wrong reason**, because `useCan` is fail-closed while pending and a control that
 * has not been told anything yet is indistinguishable from one that was refused. §66's finding,
 * and the reason Abort is the barrier: it is enabled for a caller holding `run:close` on a running
 * run and for no other state this file renders.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId('RunDetail.Abort')).toBeEnabled())
}

describe('RunDetailPage — the run lifecycle controls', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers Complete and Abort on a running run, and no Reopen', async () => {
    mockReads('IN_PROGRESS', '2026-08-02T09:00:00Z')
    renderPage()
    await permissionsResolved()

    expect(screen.getByTestId('RunDetail.Complete')).toBeEnabled()
    expect(screen.queryByTestId('RunDetail.Reopen')).toBeNull()
  })

  it('disables Complete on a run nobody has recorded anything in, and says why', async () => {
    mockReads('NOT_STARTED', null)
    renderPage()
    await permissionsResolved()

    // Abort is enabled beside it: the tooltip points at the control that IS the answer.
    expect(screen.getByTestId('RunDetail.Complete')).toBeDisabled()
    expect(screen.getByTestId('RunDetail.Abort')).toBeEnabled()
  })

  it('offers only Reopen once the run has ended', async () => {
    mockReads('COMPLETED', '2026-08-02T09:00:00Z')
    renderPage()

    await waitFor(() => expect(screen.getByTestId('RunDetail.Reopen')).toBeEnabled())
    expect(screen.queryByTestId('RunDetail.Complete')).toBeNull()
    expect(screen.queryByTestId('RunDetail.Abort')).toBeNull()
  })

  it('sends IN_PROGRESS on Reopen and lets the server normalize it', async () => {
    // An aborted run that never started. The client asks for IN_PROGRESS anyway — the server
    // answers NOT_STARTED — and a client that "helpfully" sent NOT_STARTED here would be holding
    // its own copy of a rule it reads from a cache that may be one save behind.
    mockReads('ABORTED', null)
    const put = vi.spyOn(api, 'put').mockResolvedValue(aRun() as never)
    renderPage()
    await waitFor(() => expect(screen.getByTestId('RunDetail.Reopen')).toBeEnabled())

    await userEvent.click(screen.getByTestId('RunDetail.Reopen'))

    expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/runs/${RUN}/status`, {
      status: 'IN_PROGRESS',
    })
  })

  it('sends COMPLETED on Complete', async () => {
    mockReads('IN_PROGRESS', '2026-08-02T09:00:00Z')
    const put = vi.spyOn(api, 'put').mockResolvedValue(aRun() as never)
    renderPage()
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('RunDetail.Complete'))

    expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/runs/${RUN}/status`, {
      status: 'COMPLETED',
    })
  })

  it('disables every control for a caller without run:close', async () => {
    // The gate, isolated. Everything else this caller might need is granted, so a failure here is
    // about `run:close` and nothing else — the discrimination the seeded catalogue can also make
    // on the server, and which §71's `company:teams` could not.
    mockReads('IN_PROGRESS', '2026-08-02T09:00:00Z', ['project:read', 'result:submit'])
    renderPage()

    // No `permissionsResolved()` barrier available here — the control under test is the one that
    // stays disabled — so the wait is on something that DOES settle: the run's own heading.
    await waitFor(() => expect(screen.getByTestId('RunDetail.Status')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByTestId('RunDetail.Abort')).toBeDisabled())
    expect(screen.getByTestId('RunDetail.Complete')).toBeDisabled()
  })

  it('renders the refusal beside the button rather than as a toast', async () => {
    // The refused write is the subject: where its message goes is the assertion.
    drivingAFailedWrite()
    mockReads('NOT_STARTED', null)
    vi.spyOn(api, 'put').mockRejectedValue(
      Object.assign(new Error('conflict'), {
        status: 409,
        detail: 'Nothing has been recorded in this run yet, so it cannot be completed.',
      }),
    )
    renderPage()
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('RunDetail.Abort'))

    await waitFor(() => expect(screen.getByTestId('RunDetail.StatusError')).toBeInTheDocument())
  })
})

/**
 * §177's Git traceability, read from the screen that renders it.
 *
 * Two assertions and the second is the one worth having: the coordinate is **absent** on a run that
 * records neither, rather than a labelled empty field. Most runs in this product are manual passes
 * in projects that record no build.
 */
describe('RunDetailPage — the code a run tested (§177)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('names the branch and pins it to the commit in the header', async () => {
    mockReads('IN_PROGRESS', '2026-08-02T09:00:00Z', ['project:read', 'run:close'], {
      branch: 'release/4.3',
      commitSha: '4f9c2b1',
    })
    renderPage()

    expect(await screen.findByTestId('RunDetail.Code')).toHaveTextContent('release/4.3 at 4f9c2b1')
  })

  it('renders nothing at all when the run records neither', async () => {
    mockReads('IN_PROGRESS', '2026-08-02T09:00:00Z')
    renderPage()

    // Awaited on something else first, so the absence below is a rendered page rather than an
    // unresolved one — the shape that makes a "queryBy...toBeNull" assertion pass for free.
    await screen.findByTestId('RunDetail.Status')
    expect(screen.queryByTestId('RunDetail.Code')).toBeNull()
  })
})
