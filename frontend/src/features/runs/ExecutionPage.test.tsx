import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes, useLocation } from 'react-router-dom'
import { api } from '../../api/client'
import type { ExecutionResultInput } from '../../api/queries'
import type { CaseStatus, RunStatus, TestExecution, TestExecutionSummary } from '../../api/types'
import { AnnouncerProvider } from '../../a11y/AnnouncerProvider'
import { anExecution, aProject, aRun } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { ExecutionPage } from './ExecutionPage'

/**
 * The execution screen's keyboard (§248) — and the first automated driver of `useSubmitResult`
 * anywhere in `frontend/src`.
 *
 * The arms are the four ways a keyboard on this screen can be wrong: it fires while somebody is
 * typing, it writes Blocked without the checklist, it acts where the screen would have refused
 * (frozen run, missing permission, unreadable duration), and it does something a screen reader is
 * never told about.
 *
 * **Scoped to the keyboard.** Durations, preconditions, step marks, evidence, defects and the 409
 * panel are NOT covered here — §244 left them to the browser queue and this file does not quietly
 * adopt them.
 */

const STATUSES: CaseStatus[] = [
  { id: 'st-passed', name: 'Passed', kind: 'PASSED', color: '#2e7d32', countsAsPass: true, isFinal: true },
  { id: 'st-failed', name: 'Failed', kind: 'FAILED', color: '#c62828', countsAsPass: false, isFinal: true },
  { id: 'st-blocked', name: 'Blocked', kind: 'BLOCKED', color: '#ef6c00', countsAsPass: false, isFinal: false },
  { id: 'st-skipped', name: 'Skipped', kind: 'SKIPPED', color: '#616161', countsAsPass: false, isFinal: true },
]

/*
  Prefix `2c` — pre-assigned to this area and grepped across backend/src, frontend/src and
  packages before minting. Three executions, so `j` and `k` both have somewhere to go and the
  middle one is neither end of the queue.
*/
const EX_1 = '2c000000-0000-4000-8000-000000000001'
const EX_2 = '2c000000-0000-4000-8000-000000000002'
const EX_3 = '2c000000-0000-4000-8000-000000000003'

const ROUTE = `/projects/p1/runs/r1/executions/${EX_2}`

function anExecutionSummary(id: string, caseKey: string): TestExecutionSummary {
  return {
    id,
    caseId: `case-${caseKey}`,
    caseKey,
    caseTitle: `The ${caseKey} case`,
    caseVersionNo: 1,
    status: null,
    assignee: null,
    executedBy: null,
    executedAt: null,
    durationMs: null,
    defects: [],
    quarantined: false,
  }
}

/** Everything this screen reads. `runStatus` and `permissions` are what the arms vary. */
function mockReads({
  runStatus = 'IN_PROGRESS',
  permissions = ['result:submit', 'result:amend', 'run:execute'],
  execution = anExecution({ id: EX_2, caseKey: 'TC-2', caseTitle: 'The TC-2 case', lockVersion: 3 }),
}: {
  runStatus?: RunStatus
  permissions?: string[]
  execution?: TestExecution
} = {}) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions as never)
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: 'p1' })
    if (/^\/projects\/[^/]+\/statuses$/.test(path)) return STATUSES
    // Anchored on the whole sub-resource so the execution path below is not swallowed by a
    // prefix — the fixtures rule, and this file reads four endpoints off one base.
    if (/^\/projects\/[^/]+\/runs\/[^/]+$/.test(path)) {
      return {
        ...aRun({ id: 'r1', status: runStatus }),
        executions: [
          anExecutionSummary(EX_1, 'TC-1'),
          anExecutionSummary(EX_2, 'TC-2'),
          anExecutionSummary(EX_3, 'TC-3'),
        ],
      }
    }
    if (/^\/projects\/[^/]+\/runs\/[^/]+\/executions\/[^/]+$/.test(path)) return execution
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

/** Every result this screen writes, recorded, so an arm can assert the body — or its absence. */
function mockSubmit() {
  const sent: ExecutionResultInput[] = []
  vi.spyOn(api, 'patch').mockImplementation((async (path: string, body: ExecutionResultInput) => {
    if (!/^\/projects\/[^/]+\/runs\/[^/]+\/executions\/[^/]+$/.test(path)) {
      throw new Error(`unmocked write: ${path}`)
    }
    sent.push(body)
    return anExecution({ id: EX_2, caseKey: 'TC-2', status: STATUSES[0], lockVersion: 4 })
  }) as never)
  return sent
}

/** The URL, readable by an assertion — `j` and `k` are navigations and nothing else shows them. */
function Where() {
  const location = useLocation()
  return <span data-testid="Where">{location.pathname}</span>
}

function tree(route: string) {
  return (
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <AnnouncerProvider>
        <Routes>
          <Route
            path="/projects/:projectId/runs/:runId/executions/:executionId"
            element={<ExecutionPage />}
          />
          <Route path="/projects/:projectId/runs/:runId" element={<div data-testid="RunDetail" />} />
        </Routes>
        <Where />
      </AnnouncerProvider>
    </TestProviders>
  )
}

async function renderScreen(route = ROUTE) {
  render(tree(route))
  // The form is what binds the keys, so no arm may press one before it is on screen.
  await screen.findByTestId('Execution.Actions')
}

describe('ExecutionPage keyboard', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('records Passed on `p`', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('p')

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ statusId: 'st-passed' })
  })

  it('THE DEFECT THIS FEATURE SHIPS BROKEN: typing in the notes box records nothing', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.CommentField'))
    // Every screen shortcut, typed as a word a tester would really write. Under a naive
    // implementation this marks the case Passed, then Blocked, then Skipped, then navigates.
    await user.keyboard('problem: staging kept failing, skipped for now')

    expect(sent).toHaveLength(0)
    expect(screen.getByTestId('Execution.CommentField')).toHaveValue(
      'problem: staging kept failing, skipped for now',
    )
  })

  it('`b` opens the precondition checklist and writes nothing', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('b')

    // Blocked is the one verdict that is never one keystroke, in every mode — the rule
    // executionMode.ts and ExecutionGrid.tsx both defend, and a keyboard would be the first thing
    // to break it.
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  it('records nothing on a run that has ended', async () => {
    const user = userEvent.setup()
    mockReads({ runStatus: 'COMPLETED' })
    const sent = mockSubmit()
    await renderScreen()
    await screen.findByTestId('Execution.RunClosed')

    await user.keyboard('p')

    expect(sent).toHaveLength(0)
  })

  it('records nothing for a caller who holds neither result key', async () => {
    const user = userEvent.setup()
    mockReads({ permissions: ['run:execute'] })
    const sent = mockSubmit()
    await renderScreen()
    await waitFor(() => expect(screen.getByTestId('Execution.Action.PASSED')).toBeDisabled())

    await user.keyboard('p')

    expect(sent).toHaveLength(0)
  })

  it("unbinds when the screen unmounts — the keys are this screen's, not the app's", async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    const { unmount } = render(tree(ROUTE))
    await screen.findByTestId('Execution.Actions')

    unmount()
    await user.keyboard('p')

    expect(sent).toHaveLength(0)
  })

  it("`j` and `k` walk the run in the rail's order", async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('j')
    await waitFor(() => expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_3}`))

    await user.keyboard('k')
    await waitFor(() => expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_2}`))
  })

  it('does not move past the end of the run', async () => {
    const user = userEvent.setup()
    mockReads({ execution: anExecution({ id: EX_3, caseKey: 'TC-3', caseTitle: 'The TC-3 case', lockVersion: 1 }) })
    mockSubmit()
    await renderScreen(`/projects/p1/runs/r1/executions/${EX_3}`)

    await user.keyboard('j')

    expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_3}`)
    expect(screen.getByTestId('Execution.Nav.Next')).toBeDisabled()
  })

  it('refuses to move while there is unsaved work, rather than discarding it', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.CommentField'))
    await user.keyboard('staging was down')
    // Out of the box first, or the shortcut is correctly guarded and the arm proves nothing.
    await user.keyboard('{Escape}')
    await user.keyboard('j')

    expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_2}`)
    expect(screen.getByTestId('Execution.Nav.Next')).toBeDisabled()
    expect(screen.getByTestId('Execution.CommentField')).toHaveValue('staging was down')
  })

  it('offers the move as buttons too — the keys are an accelerator, not the only route', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.Nav.Next'))

    await waitFor(() => expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_3}`))
  })

  it('`n` puts the caret in the notes box', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('n')

    expect(screen.getByTestId('Execution.CommentField')).toHaveFocus()
  })

  it('`Escape` leaves the notes box, and the verdict keys work again after it', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('n')
    await user.keyboard('it passed on the third try')
    expect(sent).toHaveLength(0)

    await user.keyboard('{Escape}')
    expect(screen.getByTestId('Execution.CommentField')).not.toHaveFocus()

    await user.keyboard('p')
    await waitFor(() => expect(sent).toHaveLength(1))
    // The note travels with the verdict — Escape leaves the box, it does not discard what is in it.
    expect(sent[0]).toMatchObject({ statusId: 'st-passed', comment: 'it passed on the third try' })
  })

  it('announces the verdict it recorded', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('p')

    // The announcer clears and re-sets in a microtask, so this waits rather than reads once.
    await waitFor(() =>
      expect(screen.getByTestId('Announcer')).toHaveTextContent('Recording Passed for TC-2.'),
    )
  })

  it('announces a refusal, so a dead key and a working one are not the same experience', async () => {
    const user = userEvent.setup()
    mockReads({ runStatus: 'COMPLETED' })
    mockSubmit()
    await renderScreen()
    await screen.findByTestId('Execution.RunClosed')

    await user.keyboard('p')

    await waitFor(() => expect(screen.getByTestId('Announcer')).toHaveTextContent('This run has ended'))
  })

  it('announces where a move landed, including which case and how far through', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('j')

    await waitFor(() =>
      expect(screen.getByTestId('Announcer')).toHaveTextContent('Case 3 of 3. TC-3, The TC-3 case.'),
    )
  })
})
