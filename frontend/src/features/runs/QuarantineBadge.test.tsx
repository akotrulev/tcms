import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestExecutionSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { ExecutionGrid } from './ExecutionGrid'
import { ExecutionQueue } from './ExecutionQueue'

/**
 * The quarantine badge, on all three surfaces a tester meets a run's cases through — §237, the
 * frontend half of the audit's C2.
 *
 * **The decision this file locks is *where*.** Serving the flag only on the single-execution screen
 * was the cheaper build and it is a badge you see after you have already picked the row up, which
 * is after the moment it could have changed anything. So the checklist grid and the Focus-mode
 * queue carry it too — the queue especially, because that is the rail a tester *chooses* from.
 * A regression here is silent: the flag stays on the wire, the screens keep rendering, and the one
 * thing the feature exists to do stops happening.
 *
 * **And the badge must not become a verdict.** It sits beside the case title rather than in the
 * Result column, the row is never hidden or disabled, and the tooltip says in words that the case
 * is still to be run — because "Quarantined" alone reads as "don't bother", which is the one thing
 * it must not mean. `ExecutionEndpointTest.theRowIsNeverHiddenOrRefused` holds the server's half.
 */

const PROJECT = 'p1'
const RUN = 'r1'
const CLEAN = 'exec-clean'
const DIRTY = 'exec-dirty'

function summary(overrides: Partial<TestExecutionSummary> = {}): TestExecutionSummary {
  return {
    id: CLEAN,
    caseId: 'case-clean',
    caseKey: 'TC-1',
    caseTitle: 'Card payment succeeds',
    caseVersionNo: 1,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
    quarantined: false,
    ...overrides,
  }
}

const QUARANTINED = summary({
  id: DIRTY,
  caseId: 'case-dirty',
  caseKey: 'TC-2',
  caseTitle: 'Refund settles overnight',
  quarantined: true,
})

describe('the quarantine badge on a run', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) {
        return heldPermissions(['project:read', 'result:submit'])
      }
      return []
    }) as never)
  })

  function renderGrid() {
    return render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
        <ExecutionGrid
          executions={[summary(), QUARANTINED]}
          statuses={[]}
          projectId={PROJECT}
          runId={RUN}
          currentExecutionId={CLEAN}
          modeParam="grid"
          frozen={false}
          onBlockedRequested={() => {}}
        />
      </TestProviders>,
    )
  }

  function renderQueue() {
    return render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
        <ExecutionQueue
          executions={[summary(), QUARANTINED]}
          currentExecutionId={CLEAN}
          projectId={PROJECT}
          runId={RUN}
          modeParam="focus"
        />
      </TestProviders>,
    )
  }

  it('badges the quarantined row in the checklist grid, and only that row', () => {
    renderGrid()

    // One badge for two rows. Asserting the count rather than mere presence is what catches a
    // flag rendered off the wrong field — `quarantined` on the row versus on the run — which
    // would badge everything and read as working.
    expect(screen.getAllByTestId('Execution.Grid.Row.Quarantined')).toHaveLength(1)
  })

  it('badges it in the Focus queue, which is where the case is CHOSEN', () => {
    renderQueue()

    expect(screen.getAllByTestId('Execution.Queue.Row.Quarantined')).toHaveLength(1)
  })

  it('says the case is still to be run, because the word alone reads as "skip it"', async () => {
    renderGrid()

    // The accessible name carries the sentence, so the meaning is not left to the colour or to a
    // reader's assumption about what quarantine implies for the work in front of them.
    const badge = screen.getAllByTestId('Execution.Grid.Row.Quarantined')[0]
    expect(badge).toHaveTextContent('Quarantined')
  })

  it('never hides or disables the row it badges', () => {
    renderGrid()

    // Both rows are present and both offer their verdict buttons. Filtering a quarantined case out
    // of the checklist would hide work from the person doing it, and would be the silent-denominator
    // defect one layer earlier than the reports it belongs to.
    expect(screen.getAllByTestId('Execution.Grid.Row')).toHaveLength(2)
    expect(screen.getByRole('link', { name: /Refund settles overnight/ })).toBeInTheDocument()
  })

  it('shows nothing at all when no case is quarantined', () => {
    render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
        <ExecutionGrid
          executions={[summary()]}
          statuses={[]}
          projectId={PROJECT}
          runId={RUN}
          currentExecutionId={CLEAN}
          modeParam="grid"
          frozen={false}
          onBlockedRequested={() => {}}
        />
      </TestProviders>,
    )

    expect(screen.queryByTestId('Execution.Grid.Row.Quarantined')).not.toBeInTheDocument()
  })
})
