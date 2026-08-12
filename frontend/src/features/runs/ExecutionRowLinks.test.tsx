import { render, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestExecutionSummary, TestRunDetail } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { ExecutionGrid } from './ExecutionGrid'
import { RunDetailPage } from './RunDetailPage'

/**
 * The two links on an execution row — **the one thing about these grids that nothing else can
 * check.**
 *
 * `npm run build` sees two well-typed `<Link to=…>`s wherever they point; oxlint sees nothing at
 * all. And the defect this file exists to catch was not a crash: Grid mode sent the *key* to the
 * execution and left the title inert, so a tester in Grid mode had **no way to open the test
 * case** — the screen worked, it merely could not do one thing, and the sibling grid two files
 * away had the same pair the other way round.
 *
 * Four things are locked here:
 *
 *   1. **The key goes to the case** — `/projects/{p}/cases/{caseId}`, keyed on `caseId` and never
 *      on the execution's own id. Those are two uuids of the same shape on the same row, so a
 *      swap is invisible to every gate and produces a 404 only in a browser.
 *   2. **The title goes to the execution**, which is where a result is recorded.
 *   3. **The two links have different accessible names.** Two links in one row pointing at two
 *      places, both announced "TC-41", is a screen-reader dead end — and it is the failure mode a
 *      test is most likely to be written without.
 *   4. **Both grids agree**, asserted over both rather than over the shared module alone: a
 *      module used correctly by one caller and bypassed by the other is exactly the state §100
 *      shipped, and a unit test on `ExecutionRowLinks` would have passed throughout it.
 */

const PROJECT = 'p1'
const RUN = 'r1'
const EXECUTION = 'exec-9'
const CASE = 'case-9'

function summary(): TestExecutionSummary {
  return {
    id: EXECUTION,
    caseId: CASE,
    caseKey: 'TC-41',
    caseTitle: 'Card payment is declined when the CVV is wrong',
    caseVersionNo: 3,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
  }
}

/** The href a link must carry, spelled out once so the two grids are compared against one string. */
const CASE_HREF = `/projects/${PROJECT}/cases/${CASE}`
const EXECUTION_HREF = `/projects/${PROJECT}/runs/${RUN}/executions/${EXECUTION}`

describe('ExecutionGrid — the row is two links to two places', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['project:read', 'result:submit'])
      return []
    }) as never)
  })

  function renderGrid(modeParam: string | null = 'grid') {
    return render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
        <ExecutionGrid
          executions={[summary()]}
          statuses={[]}
          projectId={PROJECT}
          runId={RUN}
          currentExecutionId={EXECUTION}
          modeParam={modeParam}
          frozen={false}
          onBlockedRequested={() => {}}
        />
      </TestProviders>,
    )
  }

  it('sends the case key to the test case, not to the execution', () => {
    renderGrid()

    // `caseId`, never `execution.id` — the assertion is on the whole href for that reason.
    expect(screen.getByTestId('Execution.Grid.Row.CaseLink')).toHaveAttribute('href', CASE_HREF)
  })

  it('sends the case title to the execution, carrying the mode so the tester stays in Grid', () => {
    renderGrid('grid')

    expect(screen.getByTestId('Execution.Grid.Row.Link')).toHaveAttribute(
      'href',
      `${EXECUTION_HREF}?mode=grid`,
    )
  })

  it('omits the mode suffix on the default mode, so two links to one screen are one string', () => {
    renderGrid(null)

    expect(screen.getByTestId('Execution.Grid.Row.Link')).toHaveAttribute('href', EXECUTION_HREF)
  })

  it('gives the two links different accessible names, and names the destination on each', () => {
    renderGrid()

    // The key's name is the tooltip's sentence: a bare `TC-41` says nothing about where it leads,
    // and is also what the row's "Record TC-41 as …" buttons are about.
    const key = screen.getByRole('link', { name: 'Open test case TC-41' })
    // The title's name CONTAINS its visible text (WCAG 2.5.3) and adds the destination.
    const title = screen.getByRole('link', {
      name: 'Card payment is declined when the CVV is wrong — record a result for TC-41 in this run',
    })

    expect(key).not.toBe(title)
    expect(key).toHaveAttribute('href', CASE_HREF)
    expect(title).toHaveAttribute('href', `${EXECUTION_HREF}?mode=grid`)
  })
})

describe('RunDetailPage — the same pair, from the other grid', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['project:read'])
      if (path.includes(`/runs/${RUN}`)) return runBody()
      if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
      return []
    }) as never)
  })

  function runBody(): TestRunDetail {
    return {
      id: RUN,
      key: 'TR-4',
      name: 'Regression 4.4.0',
      planId: null,
      planKey: null,
      configuration: { id: 'cfg-1', name: 'Chrome / Linux', isDefault: true, attributes: {} },
      status: 'IN_PROGRESS',
      releaseVersion: null,
      buildNumber: null,
      commitSha: null,
      branch: null,
      source: 'MANUAL',
      startedAt: '2026-08-06T09:00:00Z',
      completedAt: null,
      counts: { total: 1, passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 1 },
      executions: [summary()],
    }
  }

  it('keeps the key on the case and the title on the execution', async () => {
    render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
        <Routes>
          <Route path="/projects/:projectId/runs/:runId" element={<RunDetailPage />} />
        </Routes>
      </TestProviders>,
    )

    await waitFor(() =>
      expect(screen.getByTestId('RunDetail.Executions.Row.CaseLink')).toHaveAttribute('href', CASE_HREF),
    )
    // No `?mode=` here: this grid is not a mode of the execution screen.
    expect(screen.getByTestId('RunDetail.Executions.Row.Link')).toHaveAttribute('href', EXECUTION_HREF)
    expect(screen.getByRole('link', { name: 'Open test case TC-41' })).toBeInTheDocument()
  })
})
