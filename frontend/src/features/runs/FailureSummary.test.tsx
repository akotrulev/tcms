import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { CaseStatus, StepResult, TestExecution, TestExecutionSummary, TestRunDetail } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { FailureSummary } from './FailureSummary'
import { drivingAFailedRead } from '../../test/renderFailures'

/**
 * The run's failure summary — **the state matrix and the two counting rules**.
 *
 * Everything asserted here typechecks and lints either way, and three of the five states are ones
 * a fixture-driven glance at the screen would never produce:
 *
 *   1. **Blocked is not failed.** The headline and the two lists must keep them apart; the panel
 *      sits under a "Needs attention" chip that deliberately merges them, so a copy of that
 *      merge here would look consistent and report the product broken where nobody tested.
 *   2. **Unmarked is not passed.** A case that failed on step 2 of 5 with the rest untouched must
 *      say "3 not marked" — otherwise the reader infers three passes from nothing, which is the
 *      exact inference `suggestedVerdict`'s vacuous `every()` made for the tester.
 *   3. **Empty is good news, not an empty table.** A run where nothing failed must read as such,
 *      and must be distinguishable from a panel whose reads failed.
 *   4. **A failed read is a failure, never an emptiness.** With the step reads erroring, the
 *      region must render `.Error` — a summary silently reporting "no failed steps" because a
 *      request 500'd is the worst possible version of this screen.
 *   5. **The reads are bounded and the panel says so** when the bound bites.
 */

const PROJECT = 'p1'
const RUN = 'r1'

/**
 * Declared, never cast — §79's rule. A required field added to `CaseStatus` must be one build
 * error here rather than eleven confusing runtime failures.
 */
const STATUSES: CaseStatus[] = [
  { id: 'st-PASSED', name: 'Passed', kind: 'PASSED', color: '#0a0', countsAsPass: true, isFinal: true },
  { id: 'st-FAILED', name: 'Failed', kind: 'FAILED', color: '#a00', countsAsPass: false, isFinal: true },
  { id: 'st-BLOCKED', name: 'Blocked', kind: 'BLOCKED', color: '#aa0', countsAsPass: false, isFinal: false },
]

function summary(
  caseKey: string,
  kind: 'PASSED' | 'FAILED' | 'BLOCKED' | null,
): TestExecutionSummary {
  return {
    id: `exec-${caseKey}`,
    caseId: `case-${caseKey}`,
    caseKey,
    caseTitle: `The ${caseKey} case`,
    caseVersionNo: 1,
    status: kind === null ? null : STATUSES.find((s) => s.kind === kind)!,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
    quarantined: false,
  }
}

function run(executions: TestExecutionSummary[], notRun = 0): TestRunDetail {
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
    counts: {
      total: executions.length + notRun,
      passed: executions.filter((e) => e.status?.kind === 'PASSED').length,
      failed: executions.filter((e) => e.status?.kind === 'FAILED').length,
      blocked: executions.filter((e) => e.status?.kind === 'BLOCKED').length,
      skipped: 0,
      notRun,
    },
    // No request recorded — B7's ordinary state for a fixture built by hand.
    scopeRequest: null,
    executions,
  }
}

function step(position: number, statusId: string | null, comment: string | null = null): StepResult {
  return {
    stepId: `step-${position}`,
    position,
    keyword: null,
    action: `Step ${position} action`,
    testData: null,
    expectedResult: null,
    statusId,
    comment,
    evidence: [],
  }
}

/**
 * One execution as `GET …/executions/{id}` serves it — **declared, never cast** (§79's rule,
 * applied here by §196). The panel reads `steps` and nothing else, and the two fields this used to
 * carry behind an `as unknown as TestExecution` left the other seventeen absent.
 */
function execution(id: string, steps: StepResult[]): TestExecution {
  return {
    id,
    caseId: 'case-1',
    caseKey: 'TC-1',
    caseTitle: 'Full refund of a settled card payment succeeds',
    caseVersionNo: 1,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
    quarantined: false,
    comment: null,
    releaseVersion: null,
    stepFormat: 'STRUCTURED',
    preconditions: [],
    postconditions: [],
    steps,
    evidence: [],
    lockVersion: 1,
  }
}

/** The steps each execution id answers with, keyed by id; anything absent answers `[]`. */
function mockReads(steps: Record<string, StepResult[]>, options: { stepsFail?: boolean } = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.endsWith('/statuses')) return STATUSES
    if (path.includes('/executions/')) {
      if (options.stepsFail) throw new Error('boom')
      const id = path.split('/executions/')[1]
      return execution(id, steps[id] ?? [])
    }
    return []
  }) as never)
}

function renderPanel(data: TestRunDetail) {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/runs/${RUN}`}>
      <FailureSummary data={data} projectId={PROJECT} runId={RUN} />
    </TestProviders>,
  )
}

describe('FailureSummary', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps blocked cases out of the failure count and lists them separately', async () => {
    mockReads({ 'exec-TC-1': [step(0, 'st-FAILED')] })
    renderPanel(run([summary('TC-1', 'FAILED'), summary('TC-2', 'BLOCKED'), summary('TC-3', 'BLOCKED')]))

    // "1 case failed", never "3 need attention" and never "3 failed".
    const headline = await screen.findByTestId('RunDetail.Failures.Headline')
    expect(headline).toHaveTextContent('1 case failed')
    expect(headline).toHaveTextContent('2 blocked')

    // And the blocked ones are in their own list with their own sentence.
    expect(await screen.findByTestId('RunDetail.Failures.Blocked')).toBeInTheDocument()
    expect(screen.getByText(/Not counted as failures/)).toBeInTheDocument()
  })

  it('states the unmarked step count, so nobody infers passes from an unfinished checklist', async () => {
    // The tester stopped at step 2 of 5. Two marked, one failed, three never touched.
    mockReads({
      'exec-TC-1': [
        step(0, 'st-PASSED'),
        step(1, 'st-FAILED'),
        step(2, null),
        step(3, null),
        step(4, null),
      ],
    })
    renderPanel(run([summary('TC-1', 'FAILED')]))

    const counts = await screen.findByTestId('RunDetail.Failures.Step.Counts')
    expect(counts).toHaveTextContent('1 of 5 steps failed')
    expect(counts).toHaveTextContent('3 not marked')
  })

  it('omits the unmarked clause when every step was marked', async () => {
    mockReads({ 'exec-TC-1': [step(0, 'st-PASSED'), step(1, 'st-FAILED')] })
    renderPanel(run([summary('TC-1', 'FAILED')]))

    const counts = await screen.findByTestId('RunDetail.Failures.Step.Counts')
    expect(counts).toHaveTextContent('1 of 2 steps failed')
    expect(counts).not.toHaveTextContent('not marked')
  })

  it('names the failed step, in the position the tester saw', async () => {
    mockReads({
      'exec-TC-1': [step(0, 'st-PASSED'), step(1, 'st-FAILED', 'Balance was 45.00, expected 0.00')],
    })
    renderPanel(run([summary('TC-1', 'FAILED')]))

    // `position` is 0-based on the wire and 1-based on screen.
    const rows = await screen.findAllByTestId('RunDetail.Failures.Step')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveTextContent('Step 1 action')
    expect(screen.getByTestId('RunDetail.Failures.Step.Comment')).toHaveTextContent('Balance was 45.00')
  })

  it('says a case with no structured steps has none, rather than "0 of 0 failed"', async () => {
    mockReads({ 'exec-TC-1': [] })
    renderPanel(run([summary('TC-1', 'FAILED')]))

    expect(await screen.findByTestId('RunDetail.Failures.Step.None')).toBeInTheDocument()
    expect(screen.queryByTestId('RunDetail.Failures.Step.Counts')).toBeNull()
  })

  it('reads a clean run as good news and not as an empty table', async () => {
    mockReads({})
    renderPanel(run([summary('TC-1', 'PASSED')]))

    expect(await screen.findByTestId('RunDetail.Failures.None')).toHaveTextContent(
      'Every case in this run produced a result and none of them failed',
    )
    // No step reads at all: an empty `useQueries` must resolve, never hang pending.
    expect(screen.queryByTestId('RunDetail.Failures.Steps.Loading')).toBeNull()
  })

  it('refuses to call an unfinished run clean', async () => {
    mockReads({})
    renderPanel(run([summary('TC-1', 'PASSED')], 4))

    expect(await screen.findByTestId('RunDetail.Failures.None')).toHaveTextContent('not finished')
  })

  it('renders a failed step read as a failure, never as "nothing failed"', async () => {
    // The failed step read is the subject: reading it as "nothing failed" is the defect.
    drivingAFailedRead()
    mockReads({}, { stepsFail: true })
    renderPanel(run([summary('TC-1', 'FAILED')]))

    // The whole point: the case list still says one case failed, and the step layer says it could
    // not be read — rather than rendering an empty step list that reads as "no failing steps".
    await waitFor(() => expect(screen.getByTestId('RunDetail.Failures.Steps.Error')).toBeInTheDocument())
    expect(screen.getByTestId('RunDetail.Failures.Headline')).toHaveTextContent('1 case failed')
  })

  it('lists every failed case but bounds the step reads, and says which is which', async () => {
    const many = Array.from({ length: 30 }, (_, i) => summary(`TC-${i + 1}`, 'FAILED'))
    mockReads({})
    renderPanel(run(many))

    const truncated = await screen.findByTestId('RunDetail.Failures.Truncated')
    expect(truncated).toHaveTextContent('Every failed case is listed')
    expect(truncated).toHaveTextContent('first 25')
    expect(truncated).toHaveTextContent('other 5')

    // "Every failed case is listed" is a claim; this is the assertion behind it.
    expect(screen.getAllByTestId('RunDetail.Failures.Case')).toHaveLength(30)
  })
})
