import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Route, Routes } from 'react-router-dom'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Condition, StepSuggestion, Suite, TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { CaseEditorPage } from './CaseEditorPage'

/**
 * The step editor — **the case editor's first test file**, added with §94's rework of it.
 *
 * The screen had none through eight slices, and the three behaviours this slice adds are all of
 * the kind neither `tsc` nor oxlint can see:
 *
 *   1. **Auto-append.** Typing in the last row adds the next one. There is no Add button any more,
 *      so if this stops working there is no way to write a second step and nothing on screen says
 *      so.
 *   2. **Trim, then drop.** The blank trailing row is submitted with the form and must never reach
 *      the API. A whitespace-only step is accepted by the server, renders as an empty numbered
 *      line forever, and asks a tester to perform nothing.
 *   3. **A pick fills all three fields.** Completing the action alone would leave exactly the two
 *      fields that distinguish two similar steps blank — the divergence the corpus exists to stop.
 *
 * The refine that pairs with (1) — "at least one step" counting non-empty rows — is proved in
 * `CaseSchema.test.ts`, where it can be asked directly. What is asserted here is that the editor
 * surfaces it rather than saving.
 *
 * Drag-to-reorder is asserted through the ▲▼ path and `steps.move`; driving a real pointer drag in
 * jsdom proves the library works, not that this screen wired it up.
 */

const PROJECT = 'p1'

const suites: Suite[] = [
  {
    id: 'suite-1',
    name: 'Refunds',
    path: 'refunds',
    parentId: null,
    caseCount: 3,
    lifecycleStatus: 'DRAFT',
  } as Suite,
]

/*
  Declared as `StepSuggestion[]` and NOT cast — §79's rule. A field added to the contract has to be
  one build error here rather than a page of runtime failures with zero type errors.
*/
const corpus: StepSuggestion[] = [
  {
    action: 'Refund the payment',
    testData: '120.00 EUR',
    expectedResult: 'The refund is accepted',
    useCount: 41,
  },
  { action: 'Refund it twice', testData: null, expectedResult: null, useCount: 2 },
]

/*
  Declared as `Condition[]` and NOT cast — §79's rule again. §95 added two required fields to this
  shape, and the whole point is that the next one is a build error here.
*/
const library: Condition[] = [
  {
    id: 'cond-1',
    title: 'Log in as standard user',
    type: 'PRE',
    body: ['Open the application'],
    usageCount: 4,
    suiteUsageCount: 3,
    lockVersion: 2,
  },
  {
    id: 'cond-2',
    title: 'Navigate to Billing',
    type: 'PRE',
    body: ['Open the account menu'],
    usageCount: 0,
    suiteUsageCount: 0,
    lockVersion: 0,
  },
]

function mockReads(permissions: string[] = ['case:create']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (path.includes('/step-suggestions')) return corpus
    if (path.includes('/conditions')) return library
    if (path.includes('/suites')) return suites
    if (path.includes('/classification')) return []
    return []
  }) as never)
}

/*
  Two providers the shared harness deliberately does not carry, stubbed the way
  `DocPageEditorPage.test.tsx` stubs them: the editor registers unsaved work (which throws without
  the session-recovery context, by design) and announces its save through notistack.
*/
const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderEditor() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/new`}>
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <SnackbarProvider>
          <Routes>
            <Route path="/projects/:projectId/cases/new" element={<CaseEditorPage mode="create" />} />
          </Routes>
        </SnackbarProvider>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

const actionFields = () => screen.getAllByTestId('CaseEditor.Steps.Field.Action')

/**
 * Fills in everything a create needs except the steps, and waits out the permission gate.
 *
 * The barrier is the Submit button becoming enabled: `useCan` is fail-closed while
 * `/me/permissions` is pending (§66), so a click landing before it resolves does nothing and the
 * assertion that follows fails for a reason that has nothing to do with the test.
 */
async function fillHeader(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByTestId('CaseEditor.Field.Title'), 'Double refund')

  const suite = screen.getByTestId('CaseEditor.Field.Suite')
  await user.click(within(suite.closest('.MuiInputBase-root') as HTMLElement).getByRole('combobox'))
  await user.click(await screen.findByRole('option', { name: /refunds/i }))

  await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
}

describe('CaseEditorPage — the step list', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('opens a new case on one blank step row, not on nothing', () => {
    mockReads()
    renderEditor()
    // Before §94 a new case rendered ZERO rows and the Add button was the only way to a first
    // step — an editor whose primary content was one click away from existing.
    expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(1)
  })

  it('has no Add step button — the blank last row is the affordance', () => {
    mockReads()
    renderEditor()
    expect(screen.queryByTestId('CaseEditor.Steps.AddButton')).not.toBeInTheDocument()
  })

  it('appends a row when the last row is typed into, and appends exactly one', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.type(actionFields()[0], 'Refund')
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(2))

    // Six more characters into the row that is no longer last must not append six more rows.
    await user.type(actionFields()[0], ' it')
    expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(2)

    await user.type(actionFields()[1], 'Check the balance')
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(3))
  })

  it('does not append on whitespace — spaces are not a step', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.type(actionFields()[0], '   ')
    expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(1)
  })

  it('keeps a blank row available after the last one is removed', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.type(actionFields()[0], 'Refund')
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(2))

    // Removing the trailing blank row would otherwise leave no way to write another step at all.
    await user.click(screen.getAllByTestId('CaseEditor.Steps.Remove')[1])
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(2))
  })
})

describe('CaseEditorPage — reordering', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps the ▲▼ buttons after the drag handle arrives, and they still move a step', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.type(actionFields()[0], 'First')
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(2))
    await user.type(actionFields()[1], 'Second')
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(3))

    // The keyboard and screen-reader path. §94 added a pointer gesture beside them and did not
    // replace them, which is the kind of thing a later slice deletes as redundant.
    await user.click(screen.getAllByTestId('CaseEditor.Steps.MoveDown')[0])

    await waitFor(() => expect(actionFields()[0]).toHaveValue('Second'))
    expect(actionFields()[1]).toHaveValue('First')
  })

  it('gives every row a labelled drag handle', () => {
    mockReads()
    renderEditor()
    // Named rather than merely present: an icon-only control with no accessible name is announced
    // as "button", which on a list of them says nothing about which step it moves.
    expect(screen.getByLabelText('Reorder step 1')).toBeInTheDocument()
    expect(screen.getAllByTestId('CaseEditor.Steps.DragHandle')).toHaveLength(1)
  })
})

describe('CaseEditorPage — what reaches the API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('trims every step and drops the rows that are empty once trimmed', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'c1', key: 'TC-1', currentVersionNo: 1 } as unknown as TestCase)
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], '  Refund the payment  ')
    await waitFor(() => expect(screen.getAllByTestId('CaseEditor.Steps.Row')).toHaveLength(2))
    // A row typed into and cleared again: whitespace, and it must not reach the API either.
    await user.type(actionFields()[1], '   ')

    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as { steps: { action: string }[] }
    // The whole array, not `toContainEqual`: the assertion is that the two other rows are ABSENT,
    // which a per-element check cannot express.
    expect(body.steps).toHaveLength(1)
    expect(body.steps[0].action).toBe('Refund the payment')
  })

  it('refuses to save a case whose only step is the blank row', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    renderEditor()

    await fillHeader(user)
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    // The defect auto-append introduces: `steps.length` is 1 on a form nobody has written a step
    // into, so the old refine passed and the case saved clean with a success toast.
    expect(await screen.findByTestId('CaseEditor.Steps.Error')).toHaveTextContent(/at least one step/i)
    expect(post).not.toHaveBeenCalled()
  })
})

describe('CaseEditorPage — tags and the unfiled case (§96)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends the tags it was given, trimmed and de-duplicated', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'c1', key: 'TC-1', currentVersionNo: 1 } as unknown as TestCase)
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')

    const tags = screen.getByTestId('CaseEditor.Field.Tags')
    await user.type(tags, 'smoke{Enter}  smoke  {Enter}regression{Enter}')

    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as { tags: string[] }
    // The whole array: `smoke` typed twice is one chip, because case_tag's key says a tag list is
    // a set — a duplicate is the same request, not a mistake to report.
    expect(body.tags).toEqual(['smoke', 'regression'])
  })

  it('sends an EMPTY tag list rather than omitting it', async () => {
    // The server reads absent as "leave them alone", so a case whose last tag was removed would
    // keep it — a removal that reports success and does nothing.
    const user = userEvent.setup()
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'c1', key: 'TC-1', currentVersionNo: 1 } as unknown as TestCase)
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as Record<string, unknown>
    expect(body.tags).toEqual([])
  })

  it('saves with no suite at all, sending null rather than an empty string', async () => {
    // '' is a 400 on a uuid field, so the mapping is not cosmetic. And the form must not require
    // a suite any more: a case may be written before anybody has decided where it goes.
    const user = userEvent.setup()
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'c1', key: 'TC-1', currentVersionNo: 1 } as unknown as TestCase)
    renderEditor()

    await user.type(screen.getByTestId('CaseEditor.Field.Title'), 'Unfiled for now')
    await user.type(actionFields()[0], 'Refund the payment')
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as Record<string, unknown>
    expect(body.suiteId).toBeNull()
  })
})

describe('CaseEditorPage — completing from the project corpus', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('asks for suggestions only once there is a fragment to narrow with', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    const asked = () =>
      vi.mocked(api.get).mock.calls.filter(([path]) => String(path).includes('/step-suggestions'))

    // An eight-step case would otherwise fire eight reads of the whole corpus on mount, and open a
    // popup over a field nobody has touched.
    expect(asked()).toHaveLength(0)

    await user.type(actionFields()[0], 'Refund')
    await waitFor(() => expect(asked().length).toBeGreaterThan(0))
    // Debounced: one request for six keystrokes, not six.
    expect(asked().length).toBeLessThan(3)
  })

  it('fills action, test data AND expected result from one pick', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.type(actionFields()[0], 'Refund')
    await user.click(await screen.findAllByTestId('CaseEditor.Steps.Suggestion').then((rows) => rows[0]))

    await waitFor(() => expect(actionFields()[0]).toHaveValue('Refund the payment'))
    expect(screen.getAllByTestId('CaseEditor.Steps.Field.TestData')[0]).toHaveValue('120.00 EUR')
    expect(screen.getAllByTestId('CaseEditor.Steps.Field.ExpectedResult')[0]).toHaveValue(
      'The refund is accepted',
    )
  })

  it('clears the fields a suggestion has no value for, rather than leaving the previous ones', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.type(screen.getAllByTestId('CaseEditor.Steps.Field.TestData')[0], 'stale')
    await user.type(actionFields()[0], 'Refund it')

    const options = await screen.findAllByTestId('CaseEditor.Steps.Suggestion')
    await user.click(options[options.length - 1])

    // `testData: null` on the suggestion means the step has none — leaving the author's previous
    // value behind would attach it to a step it was never written for.
    await waitFor(() => expect(actionFields()[0]).toHaveValue('Refund it twice'))
    expect(screen.getAllByTestId('CaseEditor.Steps.Field.TestData')[0]).toHaveValue('')
  })
})

/**
 * The conditions panels (§95).
 *
 * What these exist to catch, none of it visible to `tsc` or the linter:
 *
 *   1. **An inherited condition being submitted as the case's own.** The folder's conditions render
 *      in this panel, and an own row and an inherited row look almost identical. Seeding one into
 *      the form would attach the folder's condition *directly* to the case on the first save —
 *      permanently, invisibly, and on every case anybody edits.
 *   2. **The two lists sent as absent instead of empty.** The server reads absent as "leave the
 *      attachments alone", so detaching the last condition would report success and change nothing.
 *   3. **The impact warning missing before an edit that rewrites every case using the entry.**
 */
describe('CaseEditorPage — conditions', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers the project library, and drops an entry once it is attached', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    const attach = await screen.findByTestId('CaseEditor.Preconditions.Attach')
    await user.click(attach)
    expect(await screen.findByRole('option', { name: /log in as standard user/i })).toBeVisible()

    await user.click(screen.getByRole('option', { name: /log in as standard user/i }))

    // Attaching the same entry twice in one list is a row with no meaning and the server collapses
    // it silently. Removing it from the picker is the honest version: the mistake is unavailable.
    await user.click(screen.getByTestId('CaseEditor.Preconditions.Attach'))
    await waitFor(() =>
      expect(screen.queryByRole('option', { name: /log in as standard user/i })).not.toBeInTheDocument(),
    )
  })

  it('sends the attached ids as objects, and sends an EMPTY list rather than omitting it', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'c9', key: 'TC-9', currentVersionNo: 1 } as never)
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')

    await user.click(screen.getByTestId('CaseEditor.Preconditions.Attach'))
    await user.click(await screen.findByRole('option', { name: /log in as standard user/i }))

    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as {
      preconditions: { conditionId: string }[]
      postconditions: unknown[]
    }
    expect(body.preconditions).toEqual([{ conditionId: 'cond-1' }])
    // Present and empty, never absent — the distinction the server acts on.
    expect(body.postconditions).toEqual([])
  })

  it('reorders through the ▲▼ path, which is the keyboard and screen-reader route', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'c9', key: 'TC-9', currentVersionNo: 1 } as never)
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')

    await user.click(screen.getByTestId('CaseEditor.Preconditions.Attach'))
    await user.click(await screen.findByRole('option', { name: /log in as standard user/i }))
    await user.click(screen.getByTestId('CaseEditor.Preconditions.Attach'))
    await user.click(await screen.findByRole('option', { name: /navigate to billing/i }))

    // Move the second one up. Order IS the content here — a condition list is a procedure.
    const up = screen.getAllByTestId('CaseEditor.Preconditions.Up')
    await user.click(up[1])

    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(post).toHaveBeenCalled())

    const body = post.mock.calls[0][1] as { preconditions: { conditionId: string }[] }
    expect(body.preconditions).toEqual([{ conditionId: 'cond-2' }, { conditionId: 'cond-1' }])
  })

  it('detaching removes it from the payload', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'c9', key: 'TC-9', currentVersionNo: 1 } as never)
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')

    await user.click(screen.getByTestId('CaseEditor.Preconditions.Attach'))
    await user.click(await screen.findByRole('option', { name: /log in as standard user/i }))
    await user.click(screen.getByTestId('CaseEditor.Preconditions.Remove'))

    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(post).toHaveBeenCalled())

    expect((post.mock.calls[0][1] as { preconditions: unknown[] }).preconditions).toEqual([])
  })

  it('states the blast radius BEFORE an edit, naming the object and both counts', async () => {
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await user.click(await screen.findByTestId('CaseEditor.Preconditions.Attach'))
    await user.click(await screen.findByRole('option', { name: /log in as standard user/i }))
    await user.click(screen.getByTestId('CaseEditor.Preconditions.Edit'))

    const impact = await screen.findByTestId('CaseEditor.Condition.Impact')
    // Names the object (ui-rules §4), and keeps the two counts apart: 4 + 3 = 7 must not appear.
    expect(impact).toHaveTextContent('Log in as standard user')
    expect(impact).toHaveTextContent('Used by 4 cases')
    expect(impact).toHaveTextContent('3 suites')
    expect(impact).not.toHaveTextContent('7')

    // Verb-labeled and naming the consequence, not a neutral "Save".
    expect(screen.getByTestId('CaseEditor.Condition.Save')).toHaveTextContent('Change everywhere')
  })

  it('creating a condition attaches it to the list it was created from', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'post').mockImplementation((async (path: string) => {
      if (path.includes('/conditions')) {
        return {
          id: 'cond-new',
          title: 'Seed a captured payment',
          type: 'PRE',
          body: ['Insert it'],
          usageCount: 0,
          suiteUsageCount: 0,
          lockVersion: 0,
        }
      }
      return { id: 'c9', key: 'TC-9', currentVersionNo: 1 }
    }) as never)
    renderEditor()

    await user.click(await screen.findByTestId('CaseEditor.Preconditions.Create'))
    await user.type(screen.getByTestId('CaseEditor.Condition.Title'), 'Seed a captured payment')
    await user.type(screen.getByTestId('CaseEditor.Condition.Body'), 'Insert it')
    await user.click(screen.getByTestId('CaseEditor.Condition.Save'))

    // Leaving the author to find it in the picker they just left is the workflow defect —
    // they asked for a condition HERE.
    expect(await screen.findByText('Seed a captured payment')).toBeVisible()
  })
})
