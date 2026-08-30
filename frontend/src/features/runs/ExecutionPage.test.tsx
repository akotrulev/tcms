import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes, useLocation } from 'react-router-dom'
import { api } from '../../api/client'
import type { ExecutionResultInput } from '../../api/queries'
import type { CaseStatus, RunStatus, TestExecution, TestExecutionSummary } from '../../api/types'
import { AnnouncerProvider } from '../../a11y/AnnouncerProvider'
import { anExecution, aProject, aRun, aStepResult, aUserRef } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { SessionRecoveryProvider } from '../../auth/SessionRecoveryProvider'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { EXECUTION_SHORTCUTS } from './executionKeys'
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
    // Keyed on the id in the path, not one execution for every id. `j` and `k` navigate, so an
    // arm that moves then reads is asking about the DESTINATION — and a double that answered the
    // starting row for every id made the move look like it had not happened, which is how the
    // announcement arm came to expect TC-3 and be told about TC-2. The `execution` override still
    // serves the row the arms start on; the neighbours are derived from the same keys the run's
    // summaries use, so the rail and the detail cannot disagree.
    const executionMatch = /^\/projects\/[^/]+\/runs\/[^/]+\/executions\/([^/]+)$/.exec(path)
    if (executionMatch) {
      const id = executionMatch[1]
      if (id === execution.id) return execution
      const key = { [EX_1]: 'TC-1', [EX_3]: 'TC-3' }[id]
      if (!key) throw new Error(`unmocked execution: ${id}`)
      return anExecution({ id, caseKey: key, caseTitle: `The ${key} case`, lockVersion: 1 })
    }
    // The assignee control reads the project's members. Not varied by any arm — the keyboard
    // never assigns — but a missing arm is a failed read, and a failed read on this screen took
    // every arm in the file down before a key was pressed.
    if (/^\/projects\/[^/]+\/members$/.test(path)) return [aUserRef()]
    // The defect panel asks whether this person has a tracker connection at all (004 Phase 3), so
    // it reads on every render of this screen. Empty is the honest default here: none of the arms
    // below files a defect, and an EMPTY list is what hides the file-a-defect control rather than
    // what breaks it.
    if (path === '/me/tracker-identities') return []
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
      {/*
        SessionRecoveryProvider is mounted here, not in TestProviders: that helper deliberately
        carries the smallest tree a unit test can need, and §248 gave ExecutionForm a
        `useUnsavedWork` registration — which is `useSessionRecovery`, which THROWS outside its
        provider. Without it every arm in this file died in the region boundary before a key was
        pressed. AuthContext is an ancestor already (TestProviders wraps the whole tree in it),
        which is what this provider needs to construct.
      */}
      <SessionRecoveryProvider>
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
      </SessionRecoveryProvider>
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

  it('`?` opens the overlay, and it lists every binding in the map', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('?')

    const overlay = await screen.findByTestId('Execution.Shortcuts.Overlay')
    // Derived from the map, not a literal count: adding a binding must update the help for free,
    // and an assertion on `9` would pass a build where the overlay silently dropped one.
    expect(within(overlay).getAllByTestId('Execution.Shortcuts.Row')).toHaveLength(
      EXECUTION_SHORTCUTS.length,
    )
    for (const shortcut of EXECUTION_SHORTCUTS) {
      expect(within(overlay).getByText(shortcut.description)).toBeInTheDocument()
    }
  })

  it('opens the overlay from a button too', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.Shortcuts.Open'))

    expect(await screen.findByTestId('Execution.Shortcuts.Overlay')).toBeInTheDocument()
  })
})

/**
 * The step checklist reads the PINNED snapshot (§255).
 *
 * These are the only arms in this file outside the keyboard, and they are here rather than in a new
 * file because the harness above is what makes them cheap — four reads, a real provider tree and a
 * `Where` probe. What they assert is the property `CaseFacts.PinnedStep` exists for: the two block
 * arguments the author wrote reach the tester who is running the version they were written into,
 * and a version written before §255 carries neither key and renders nothing rather than throwing.
 */
describe('ExecutionPage — the step row (§255)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  const withSteps = (steps: ReturnType<typeof aStepResult>[]) =>
    anExecution({
      id: EX_2,
      caseKey: 'TC-2',
      caseTitle: 'The TC-2 case',
      lockVersion: 3,
      stepFormat: 'GHERKIN',
      steps,
    })

  it('renders the keyword and the doc string on the step it belongs to', async () => {
    mockReads({
      execution: withSteps([
        aStepResult({ stepId: 's1', keyword: 'WHEN', action: 'post the payload', docString: '{ "amount": 120 }' }),
      ]),
    })
    await renderScreen()

    expect(screen.getByTestId('Execution.Steps.Row.Keyword')).toHaveTextContent('When')
    expect(screen.getByTestId('Execution.Steps.Row.Data.DocString')).toHaveTextContent('"amount": 120')
  })

  it('renders a data table as rows a tester can read', async () => {
    mockReads({
      execution: withSteps([
        aStepResult({
          stepId: 's1',
          keyword: 'THEN',
          action: 'the ledger reads',
          dataTable: [
            ['account', 'amount'],
            ['merchant', '-120.00'],
          ],
        }),
      ]),
    })
    await renderScreen()

    expect(screen.getAllByTestId('Execution.Steps.Row.Data.Table.Row')).toHaveLength(2)
    expect(screen.getByText('-120.00')).toBeInTheDocument()
  })

  it('renders a pre-§255 snapshot, which carries neither key, without a step showing anything', async () => {
    // A version written before the fields existed reads as null — correct, because that version
    // did not record one, and inventing today's value would put text on a checklist the tester's
    // own version never said.
    mockReads({ execution: withSteps([aStepResult({ stepId: 's1', action: 'do the thing' })]) })
    await renderScreen()

    expect(screen.getByTestId('Execution.Steps.Row.Action')).toHaveTextContent('do the thing')
    expect(screen.queryByTestId('Execution.Steps.Row.Data.DocString')).not.toBeInTheDocument()
    expect(screen.queryByTestId('Execution.Steps.Row.Data.Table')).not.toBeInTheDocument()
  })

  it('says the VERSION has no steps when the checklist is empty, not that the case is Gherkin', async () => {
    // The copy §255 replaced. A case is step rows in either lens now, so "no structured steps"
    // named a distinction that no longer exists — and told the tester nothing they could act on.
    mockReads({ execution: withSteps([]) })
    await renderScreen()

    expect(screen.getByText(/version being executed has no steps/i)).toBeInTheDocument()
  })
})

/**
 * The number keys (007 T12).
 *
 * `1`–`4` are the render's headline rule — *"the verdict is one keystroke"* — and they bind the
 * **step the tester is on**, not the case verdict its four letters bind. The arms are the three
 * ways that can be wrong: it marks the wrong step, it fires while somebody is typing, or the
 * failure it records asks for its description in a dialog that covers the step it is about.
 */
describe('ExecutionPage number keys', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  const threeSteps = () =>
    anExecution({
      id: EX_2,
      caseKey: 'TC-2',
      caseTitle: 'The TC-2 case',
      lockVersion: 3,
      stepFormat: 'GHERKIN',
      steps: [
        aStepResult({ stepId: 's1', position: 0, keyword: 'GIVEN', action: 'a settled payment exists' }),
        aStepResult({ stepId: 's2', position: 1, keyword: 'WHEN', action: 'the operator refunds it' }),
        aStepResult({ stepId: 's3', position: 2, keyword: 'THEN', action: 'the balance falls to zero' }),
      ],
    })

  it('records a verdict from the number key for that verdict', async () => {
    const user = userEvent.setup()
    mockReads({ execution: threeSteps() })
    mockSubmit()
    await renderScreen()

    await user.keyboard('1')

    expect(await screen.findByTestId('Execution.Step.1.Verdict')).toHaveTextContent('Passed')
  })

  it('marks the step the tester is on, and moves on to the next one', async () => {
    const user = userEvent.setup()
    mockReads({ execution: threeSteps() })
    mockSubmit()
    await renderScreen()

    // Three presses walk the procedure. The second must land on step 2 — a key that always marked
    // the first unmarked step from the top would look identical on one press and be wrong on two.
    await user.keyboard('1')
    await user.keyboard('4')

    expect(await screen.findByTestId('Execution.Step.1.Verdict')).toHaveTextContent('Passed')
    expect(await screen.findByTestId('Execution.Step.2.Verdict')).toHaveTextContent('Skipped')
    expect(screen.queryByTestId('Execution.Step.3.Verdict')).not.toBeInTheDocument()
  })

  it('marks nothing while the notes box has focus — a digit there is a digit', async () => {
    const user = userEvent.setup()
    mockReads({ execution: threeSteps() })
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.CommentField'))
    await user.keyboard('failed 1 of 4 times')

    expect(screen.queryByTestId('Execution.Step.1.Verdict')).not.toBeInTheDocument()
    expect(screen.getByTestId('Execution.CommentField')).toHaveValue('failed 1 of 4 times')
  })

  it('answers a failure in the rail rather than a dialog, and refuses one with no description', async () => {
    const user = userEvent.setup()
    mockReads({ execution: threeSteps() })
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('2')

    // In place, beside the step it is about (UX#55) — a modal would have covered the evidence.
    expect(await screen.findByText('Failed at step 1')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('Execution.Action.FAILED')).toBeDisabled()

    await user.type(screen.getByTestId('Execution.Failure.Description'), 'the ledger accepted it twice')

    expect(screen.getByTestId('Execution.Action.FAILED')).toBeEnabled()
    expect(sent).toHaveLength(0)
  })
})

/**
 * The render's most-specified moment, and the one the 2026-08-30 audit reached in one click.
 *
 * Render B: *"what '2' opens — the only modal-ish moment, and it is a rail section, not a dialog"*,
 * and its own note, *"A failure without a description is refused, and says so."* The panel and the
 * refusal were both implemented; the gate keyed on a STEP carrying the Failed verdict, so pressing
 * the CASE verdict with nothing marked walked straight past it and produced
 * *"TC-4 · Failed · 0 of 3 steps failed · 3 not marked"* — a failure with no reason and no defect.
 *
 * Both controls are exercised, because they were two paths: the button called `record` directly
 * and carried the refusal only in its `disabled`, which cannot express "press this to open".
 */
describe('a case cannot be recorded Failed without saying what happened', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('opens the panel instead of recording, when no step is marked', async () => {
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await userEvent.click(screen.getByTestId('Execution.Action.FAILED'))

    expect(sent).toHaveLength(0)
    expect(await screen.findByTestId('Execution.Failure')).toBeInTheDocument()
    expect(screen.getByTestId('Execution.Failure.Description')).toBeRequired()
  })

  it('refuses the second press while the description is empty', async () => {
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await userEvent.click(screen.getByTestId('Execution.Action.FAILED'))
    await screen.findByTestId('Execution.Failure')

    expect(screen.getByTestId('Execution.Action.FAILED')).toBeDisabled()
    expect(sent).toHaveLength(0)
  })

  it('records once the description is there, and carries it', async () => {
    // This fixture's case has no steps, which is an ordinary case and the branch that would
    // otherwise be the hole left behind the fix: with nothing to hang the sentence on, it goes on
    // the case's own comment rather than being skipped.
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await userEvent.click(screen.getByTestId('Execution.Action.FAILED'))
    await userEvent.type(
      await screen.findByTestId('Execution.Failure.Description'),
      'The price filter reset when the sort changed.',
    )
    await userEvent.click(screen.getByTestId('Execution.Action.FAILED'))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].comment).toContain('The price filter reset')
  })

  it('refuses the keyboard by the same path', async () => {
    // `f`, the screen's own binding for the case verdict — 1–4 mark the STEP the tester is on.
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await userEvent.keyboard('f')

    expect(sent).toHaveLength(0)
    expect(await screen.findByTestId('Execution.Failure')).toBeInTheDocument()
  })

  it('leaves Passed one press away, because only Failed owes an explanation', async () => {
    // The guard. Without it every assertion above is satisfied by a screen that refuses
    // everything, which would be a worse bug than the one being fixed.
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await userEvent.click(screen.getByTestId('Execution.Action.PASSED'))

    await waitFor(() => expect(sent).toHaveLength(1))
  })
})
