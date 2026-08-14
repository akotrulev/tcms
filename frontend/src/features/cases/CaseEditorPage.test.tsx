import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Route, Routes } from 'react-router-dom'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Condition, PermissionKey, StepSuggestion, Suite, TagUsage, TestCase } from '../../api/types'
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

/*
  Declared as `Suite[]` and NOT cast — §79's rule, which the three fixtures below it have stated
  since §79 and this one broke until §196. The `as Suite` that used to close each element is what
  let six required fields go missing ten lines above the first statement of the rule.
*/
const suites: Suite[] = [
  {
    id: 'suite-1',
    key: 'TS-1',
    name: 'Refunds',
    caseCount: 3,
    tagRules: [],
    caseIds: [],
    lifecycleStatus: 'DRAFT',
    preconditions: [],
    postconditions: [],
    lockVersion: 1,
  },
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

/**
 * `GET /projects/{id}/tags` — most-used first, which is the order the picker must preserve.
 *
 * Declared as `TagUsage[]` and not cast, §79's rule: a field added to the shape is a build error
 * here rather than a fixture that quietly disagrees with the contract.
 */
const projectTags: TagUsage[] = [
  { tag: 'smoke', caseCount: 9 },
  { tag: 'smoke-extended', caseCount: 2 },
  { tag: 'regression', caseCount: 4 },
]

/**
 * A case as `GET …/cases/{id}` serves it — **one complete body for the whole file** (§196).
 *
 * The three fixtures that used to stand in for a loaded case each spelled out the fields their own
 * describe block reads and closed with `as unknown as TestCase`, so between them they were missing
 * ten, fourteen and thirty-four required fields. None of that was visible: the cast is the third
 * statement of §79's rule in this file being contradicted by the file itself.
 *
 * Everything a test actually asserts on is passed as an override, so a reader still sees the
 * distinguishing values at the call site. What is here is the ordinary answer for every other
 * field — and it is what a required field added to `TestCase` now collides with, in one place.
 */
function loadedCase(over: Partial<TestCase> = {}): TestCase {
  return {
    id: 'c1',
    key: 'TC-1',
    keySeq: 1,
    title: 'A saved case',
    suiteId: 'suite-1',
    suiteName: 'Refunds',
    priority: 'MEDIUM',
    lifecycleStatus: 'DRAFT',
    automationStatus: 'MANUAL',
    stepFormat: 'STRUCTURED',
    owner: null,
    createdBy: null,
    features: [],
    tags: [],
    healthSignals: [],
    lastResult: null,
    updatedAt: '2026-08-01T12:00:00Z',
    attachments: [],
    flake: null,
    classificationValueIds: [],
    description: null,
    objective: null,
    expectedOutcome: null,
    severity: 'MEDIUM',
    estimatedDurationSec: null,
    automationTags: [],
    automationKey: null,
    notAutomatableReason: null,
    gherkinSource: null,
    steps: [],
    preconditions: [],
    postconditions: [],
    declinedPreconditions: [],
    declinedPostconditions: [],
    relatedDocuments: [],
    currentVersionNo: 1,
    lockVersion: 1,
    ...over,
  }
}

function mockReads(permissions: PermissionKey[] = ['case:create']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (path.includes('/step-suggestions')) return corpus
    if (path.includes('/conditions')) return library
    if (path.includes('/suites')) return suites
    if (path.includes('/classification')) return []
    if (path.includes('/tags')) return projectTags
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
      .mockResolvedValue(loadedCase({ currentVersionNo: 1 }))
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
      .mockResolvedValue(loadedCase({ currentVersionNo: 1 }))
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')

    const tags = screen.getByTestId('CaseEditor.Field.Tags')
    await user.type(tags, 'smoke{Enter}  smoke  {Enter}regression{Enter}')

    // VISIBLE, not merely held. The first draft of this control replaced MUI's `slotProps` in
    // order to add a testid, which drops the `input.startAdornment` the chips live in: every tag
    // was in the form, was sent on save, and never appeared on screen — so an author types a tag,
    // sees nothing, and types it again. The payload assertion below passed against that.
    expect(await screen.findByText('smoke')).toBeVisible()
    expect(screen.getByText('regression')).toBeVisible()

    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as { tags: string[] }
    // The whole array: `smoke` typed twice is one chip, because case_tag's key says a tag list is
    // a set — a duplicate is the same request, not a mistake to report.
    expect(body.tags).toEqual(['smoke', 'regression'])
  })

  it('suggests the tags the project already uses, and still takes an unlisted one', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(loadedCase({ currentVersionNo: 1 }))
    renderEditor()

    await fillHeader(user)
    await user.type(actionFields()[0], 'Refund the payment')

    // Typing FILTERS the served list rather than replacing it — both `smoke` tags match `smo`,
    // and the endpoint's most-used-first order is what decides which is offered first.
    await user.type(screen.getByTestId('CaseEditor.Field.Tags'), 'smo')
    expect(await screen.findByRole('option', { name: 'smoke' })).toBeVisible()
    expect(screen.getByRole('option', { name: 'smoke-extended' })).toBeVisible()
    // `regression` is in the project but does not match what was typed.
    expect(screen.queryByRole('option', { name: 'regression' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('option', { name: 'smoke' }))

    /*
      A PICKER'S LIST AND NEVER A VALIDATOR'S. `case_tag` is free text, so a tag no case carries
      yet must still be typeable — otherwise the first use of any new tag is impossible and the
      vocabulary is closed, which is precisely what this endpoint is not.
    */
    await user.type(screen.getByTestId('CaseEditor.Field.Tags'), 'brand-new-tag{Enter}')

    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as { tags: string[] }
    expect(body.tags).toEqual(['smoke', 'brand-new-tag'])
  })

  it('does not offer a tag this case already carries', async () => {
    // Re-picking one is a no-op the de-duplicating `Set` swallows in silence, which reads as a
    // dead menu row: the author clicks it, nothing happens, and nothing says why.
    const user = userEvent.setup()
    mockReads()
    renderEditor()

    await fillHeader(user)
    await user.type(screen.getByTestId('CaseEditor.Field.Tags'), 'smo')
    await user.click(await screen.findByRole('option', { name: 'smoke' }))

    await user.type(screen.getByTestId('CaseEditor.Field.Tags'), 'smo')

    expect(await screen.findByRole('option', { name: 'smoke-extended' })).toBeVisible()
    expect(screen.queryByRole('option', { name: 'smoke' })).not.toBeInTheDocument()
  })

  it('sends an EMPTY tag list rather than omitting it', async () => {
    // The server reads absent as "leave them alone", so a case whose last tag was removed would
    // keep it — a removal that reports success and does nothing.
    const user = userEvent.setup()
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(loadedCase({ currentVersionNo: 1 }))
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
      .mockResolvedValue(loadedCase({ currentVersionNo: 1 }))
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

  /**
   * Pick a suggestion by its action text, re-finding the row on every attempt.
   *
   * The debounce is 250ms of *real* time and `userEvent` types a character at a time, so a machine
   * under full-suite load runs several debounce rounds for one `type()` — 'Refund', 'Refund i',
   * 'Refund it'. Each round is a new query key, so `suggestions.data` is `undefined` until it
   * resolves, the popup renders no rows, and the `<li>` a `findAllByTestId` handed back a moment
   * earlier is detached. Clicking a detached node fires nothing and the field keeps the typed text
   * — which is exactly how this file failed in CI on 2026-08-13, and only ever under load.
   *
   * Capturing the row and clicking it therefore has to be one retried step, not two awaited ones.
   * A pick is idempotent, so a retry after a click that did land is harmless.
   */
  async function pickSuggestion(user: ReturnType<typeof userEvent.setup>, action: string) {
    await waitFor(async () => {
      const row = screen
        .queryAllByTestId('CaseEditor.Steps.Suggestion')
        .find((option) => option.textContent?.includes(action))
      expect(row).toBeDefined()
      await user.click(row!)
      expect(actionFields()[0]).toHaveValue(action)
    })
  }

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
    await pickSuggestion(user, 'Refund the payment')

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

    await pickSuggestion(user, 'Refund it twice')

    // `testData: null` on the suggestion means the step has none — leaving the author's previous
    // value behind would attach it to a step it was never written for.
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

/**
 * The autosave buffer's screen half (§128).
 *
 * The editor is rendered in **edit** mode here, unlike everything above it, because the buffer's
 * primary key is a foreign key to `test_case` — a case being created has nothing to key a draft on,
 * so the New Case form has no autosave at all and that is asserted rather than assumed.
 *
 * What these cover that no gate can:
 *
 *   1. **The offer names who typed it.** There is ONE buffer per case, so the draft may be a
 *      colleague's, and restoring it silently publishes their half-finished sentence under your
 *      name.
 *   2. **A stale draft says so and is still offered.** Advisory, never enforcing.
 *   3. **Restore replaces the whole form**, not the fields the draft happens to carry — a hybrid of
 *      draft and saved case is neither, and the author cannot tell it from either.
 *   4. **Discard removes the row**, so the same rejected draft is not re-offered on the next mount.
 */
describe('the draft buffer', () => {
  const EXISTING_CASE = 'c-42'

  const savedCase = loadedCase({
    id: EXISTING_CASE,
    key: 'TC-42',
    keySeq: 42,
    title: 'The saved title',
    objective: 'What the server has stored',
    steps: [
      { id: 's1', position: 0, keyword: null, action: 'A saved step', testData: null, expectedResult: null },
    ],
    lockVersion: 3,
    currentVersionNo: 2,
  })

  /** A buffered draft, as `GET …/draft` serves it. */
  function draft(overrides: Record<string, unknown> = {}) {
    return {
      lockVersion: 3,
      stale: false,
      updatedAt: new Date(Date.now() - 60_000).toISOString(),
      updatedBy: { id: 'u9', displayName: 'E. Engineer', email: 'eng@acme.test', avatarUrl: null },
      content: {
        title: 'The unsaved title',
        suiteId: 'suite-1',
        objective: 'What the author was still writing',
        steps: [{ id: null, action: 'A drafted step', testData: null, expectedResult: null }],
        stepFormat: 'STRUCTURED',
        priority: 'MEDIUM',
        automationStatus: 'MANUAL',
        classificationValueIds: [],
        preconditions: [],
        postconditions: [],
        tags: [],
      },
      ...overrides,
    }
  }

  /** `mockReads`, plus the case and its draft. `null` for the draft is the 204 — no unsaved work. */
  function mockEditReads(theDraft: unknown) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['case:edit'], {}, PROJECT)
      if (path.includes('/draft')) return theDraft
      if (path.includes('/step-suggestions')) return corpus
      if (path.includes('/conditions')) return library
      if (path.includes('/suites')) return suites
      if (path.includes('/classification')) return []
      if (path.includes('/tags')) return projectTags
      if (path.includes(`/cases/${EXISTING_CASE}`)) return savedCase
      return []
    }) as never)
  }

  function renderEditMode() {
    return render(
      <TestProviders
        client={createTestQueryClient()}
        route={`/projects/${PROJECT}/cases/${EXISTING_CASE}/edit`}
      >
        <SessionRecoveryContext.Provider value={sessionRecovery}>
          <SnackbarProvider>
            <Routes>
              <Route
                path="/projects/:projectId/cases/:caseId/edit"
                element={<CaseEditorPage mode="edit" />}
              />
            </Routes>
          </SnackbarProvider>
        </SessionRecoveryContext.Provider>
      </TestProviders>,
    )
  }

  it('offers the buffered draft and NAMES WHO TYPED IT', async () => {
    // The 1:1 primary key's cost, on screen. There is one buffer per case, so the draft on offer
    // may be a colleague's — restoring it without saying so publishes their half-finished sentence
    // under your name, with nothing in the audit trail showing it had ever been theirs.
    mockEditReads(draft())
    renderEditMode()

    const prompt = await screen.findByTestId('CaseEditor.DraftPrompt')
    expect(prompt).toHaveTextContent('E. Engineer')
    expect(prompt).toHaveTextContent(/without saving/i)
  })

  it('warns when the draft is stale, and still offers it', async () => {
    // Advisory, never enforcing: the words are the author's, and dropping them would be the
    // product deciding that somebody else's edit invalidates your sentence.
    mockEditReads(draft({ stale: true }))
    renderEditMode()

    const prompt = await screen.findByTestId('CaseEditor.DraftPrompt')
    expect(prompt).toHaveTextContent(/saved by someone else/i)
    expect(within(prompt).getByTestId('CaseEditor.DraftRestore')).toBeEnabled()
  })

  it('shows no prompt when there is no buffered draft', async () => {
    // The ordinary state of almost every case. A 204 is `null` here, and it must read as loaded —
    // not as a failure, and not as a decision the author has to make.
    mockEditReads(null)
    renderEditMode()

    expect(await screen.findByTestId('CaseEditor.Field.Title')).toHaveValue('The saved title')
    expect(screen.queryByTestId('CaseEditor.DraftPrompt')).not.toBeInTheDocument()
  })

  it('restoring replaces the form with the draft', async () => {
    const user = userEvent.setup()
    mockEditReads(draft())
    renderEditMode()

    await user.click(await screen.findByTestId('CaseEditor.DraftRestore'))

    expect(screen.getByTestId('CaseEditor.Field.Title')).toHaveValue('The unsaved title')
    // The WHOLE form, not the fields the draft happened to carry: a hybrid of draft and saved case
    // is neither, and the author cannot tell it apart from either.
    expect(screen.getByTestId('CaseEditor.Field.Objective')).toHaveValue(
      'What the author was still writing',
    )
    expect(actionFields()[0]).toHaveValue('A drafted step')
    // The offer is answered, so it goes.
    expect(screen.queryByTestId('CaseEditor.DraftPrompt')).not.toBeInTheDocument()
  })

  it('restoring leaves the form dirty, so Save stays available', async () => {
    // `reset` clears the dirty flag by default, which would disable Save on the restored content
    // and switch the autosave off — on the one screen where both must be running.
    const user = userEvent.setup()
    mockEditReads(draft())
    renderEditMode()

    await user.click(await screen.findByTestId('CaseEditor.DraftRestore'))

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
  })

  it('discarding deletes the row, so the same draft is not re-offered', async () => {
    // Keeping it would re-offer a draft the author has already refused — and the autosave is about
    // to overwrite it anyway, so leaving it would be a promise to keep something we are replacing.
    const user = userEvent.setup()
    mockEditReads(draft())
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderEditMode()

    await user.click(await screen.findByTestId('CaseEditor.DraftDiscard'))

    expect(del).toHaveBeenCalledWith(
      expect.stringContaining(`/cases/${EXISTING_CASE}/draft`),
    )
    expect(screen.queryByTestId('CaseEditor.DraftPrompt')).not.toBeInTheDocument()
    // The saved case is still what the form holds — discarding a draft is not discarding the case.
    expect(screen.getByTestId('CaseEditor.Field.Title')).toHaveValue('The saved title')
  })

  it('the CREATE form asks for no draft at all', async () => {
    // Stated rather than left to be discovered: the buffer's key is a foreign key to `test_case`,
    // so there is nothing to key a draft on until the case exists. The New Case form is exactly as
    // lossy as it was before this slice.
    mockReads()
    renderEditor()

    await screen.findByTestId('CaseEditor.Field.Title')
    const paths = vi.mocked(api.get).mock.calls.map(([path]) => path)
    expect(paths.some((path) => String(path).includes('/draft'))).toBe(false)
  })
})

/**
 * The opt-out toggle (§187) — §6f's "not this one", on an inherited condition.
 *
 * What these exist to catch, none of it visible to `tsc` or the linter:
 *
 *   1. **A declined row that disappears from the panel when it is declined.** The toggle lives
 *      beside the inherited marker; remove the row and the only control that can put the rule back
 *      goes with it, so the action is one-way from the one screen that offers it. That was the
 *      server's state before this slice, one layer down, which is what makes it the likely mistake
 *      rather than a hypothetical one.
 *   2. **`declinedConditionIds` sent as absent instead of empty.** The server reads absent as
 *      "leave the declines alone", so switching the last rule back on would report success and
 *      change nothing — `postconditions`' defect (§95) on a field added ninety sections later.
 *   3. **Two Controllers over one array.** Both panels write into the same field; if each bound its
 *      own, the second to fire would overwrite the first's toggle with a value read before it.
 */
describe('CaseEditorPage — declining an inherited condition (§187)', () => {
  const CASE_ID = 'c-187'

  /** SECOND is attached at the folder; the case declines nothing yet. */
  const inheritedCase = loadedCase({
    id: CASE_ID,
    key: 'TC-187',
    keySeq: 187,
    title: 'Log in with a bad password',
    suiteName: 'Payments',
    steps: [
      { id: 's1', position: 0, keyword: null, action: 'Open the login page', testData: null, expectedResult: null },
    ],
    preconditions: [
      {
        id: 'attach-1',
        position: 0,
        conditionId: 'cond-1',
        title: 'Log in as standard user',
        body: ['Open the application'],
        inline: false,
        inherited: true,
        inheritedFrom: 'Payments',
      },
    ],
    lockVersion: 3,
    currentVersionNo: 2,
  })

  /** The same case with the attachment already declined — what a reload serves after a save. */
  const declinedCase: TestCase = {
    ...inheritedCase,
    preconditions: [],
    declinedPreconditions: inheritedCase.preconditions,
  }

  function mockDeclineReads(theCase: TestCase) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['case:edit'], {}, PROJECT)
      if (path.includes('/draft')) return null
      if (path.includes('/step-suggestions')) return corpus
      if (path.includes('/conditions')) return library
      if (path.includes('/suites')) return suites
      if (path.includes('/classification')) return []
      if (path.includes('/tags')) return projectTags
      if (path.includes(`/cases/${CASE_ID}`)) return theCase
      return []
    }) as never)
  }

  function renderDeclineEditor() {
    return render(
      <TestProviders
        client={createTestQueryClient()}
        route={`/projects/${PROJECT}/cases/${CASE_ID}/edit`}
      >
        <SessionRecoveryContext.Provider value={sessionRecovery}>
          <SnackbarProvider>
            <Routes>
              <Route
                path="/projects/:projectId/cases/:caseId/edit"
                element={<CaseEditorPage mode="edit" />}
              />
            </Routes>
          </SnackbarProvider>
        </SessionRecoveryContext.Provider>
      </TestProviders>,
    )
  }

  const toggle = () => screen.getByRole('switch', { name: /applies to this case/i })

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a declined row STILL IN THE PANEL, switched off — not removed from it', async () => {
    // The server serves it on `declinedPreconditions`, which the editor unions onto the inherited
    // group. If it did not, the row would be absent from the one screen that can restore it.
    mockDeclineReads(declinedCase)
    renderDeclineEditor()

    const row = await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    expect(row).toHaveAttribute('data-declined', 'true')
    expect(row).toHaveTextContent('Log in as standard user')
    expect(toggle()).not.toBeChecked()
  })

  it('an inherited condition that is NOT declined shows the switch on', async () => {
    mockDeclineReads(inheritedCase)
    renderDeclineEditor()

    const row = await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    expect(row).toHaveAttribute('data-declined', 'false')
    expect(toggle()).toBeChecked()
  })

  it('sends the ATTACHMENT id, never the library condition id', async () => {
    // The distinction the whole table rests on: "Log in" attached at two folders is two
    // attachments, and declining one must leave the other in force. The server refuses a
    // conditionId here with a field error, so getting this wrong is a 400 rather than a silent
    // over-decline — but the 400 lands on a save the author will read as broken.
    const user = userEvent.setup()
    mockDeclineReads(inheritedCase)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ id: CASE_ID, currentVersionNo: 3 } as never)
    renderDeclineEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await user.click(toggle())
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { declinedConditionIds: string[] }
    expect(body.declinedConditionIds).toEqual(['attach-1'])
  })

  it('sends an EMPTY array when the last decline is switched back on, never omits the field', async () => {
    // Absent means "leave the declines alone", so omitting it would make un-declining a save that
    // reports success and restores nothing. §95's `postconditions` defect, one field over.
    const user = userEvent.setup()
    mockDeclineReads(declinedCase)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ id: CASE_ID, currentVersionNo: 3 } as never)
    renderDeclineEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await user.click(toggle())
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { declinedConditionIds: string[] }
    expect(body.declinedConditionIds).toEqual([])
  })

  it('a save that touches nothing else still carries the declines it was served', async () => {
    // The form sends the whole editable set on every save, so a title fix must not read as an
    // instruction to switch every declined rule back on.
    const user = userEvent.setup()
    mockDeclineReads(declinedCase)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ id: CASE_ID, currentVersionNo: 3 } as never)
    renderDeclineEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await user.type(screen.getByTestId('CaseEditor.Field.Title'), '!')
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { declinedConditionIds: string[] }
    expect(body.declinedConditionIds).toEqual(['attach-1'])
  })

  it('declining does NOT seed the folder condition into the case as its own', async () => {
    // The failure this panel's whole design guards against, now reachable through a new control:
    // a decline that also attached the folder's condition directly to the case would turn "not
    // this one" into "this one for ever, unlinked from the folder".
    const user = userEvent.setup()
    mockDeclineReads(inheritedCase)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ id: CASE_ID, currentVersionNo: 3 } as never)
    renderDeclineEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await user.click(toggle())
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { preconditions: unknown[] }
    expect(body.preconditions).toEqual([])
  })
})

/**
 * The move the editor used to make quietly (§194) — §193's item, on the single-case path.
 *
 * §192 made relocation the one operation in the product that ends a stored decision, and §193 gave
 * the bulk action a preview and a record of it. `PATCH /cases/{id}` ends declines by exactly the
 * same rule and said nothing about it on either side of the save. Worse than nothing, in fact:
 *
 *   1. **The inherited group is the folder the case is filed in NOW.** `inheritedPre` is built from
 *      the loaded case, so moving the Suite picker leaves a panel full of the departing folder's
 *      rules with live switches on them. `CaseEdit.normalizeDeclines` intersects the submitted set
 *      with what the DESTINATION imposes, and since §98 two folders share no attachment — so a
 *      decline toggled here is accepted by validation and dropped by the same request. A control
 *      whose effect the save silently discards is the defect; the alert is the smaller half.
 *   2. **The snackbar is the only record that survives the navigation** on the next line, exactly
 *      as `CaseListPage`'s is for the bulk path.
 *
 * The two halves are deliberately built from different things and the last test here is what pins
 * it: the alert PREDICTS from §98's flatness, the snackbar MEASURES what the response no longer
 * carries. Give the server an answer that kept the decline and the alert still warns while the
 * snackbar reports nothing — which is what "measured" has to mean.
 */
describe('CaseEditorPage — moving a case out of the folder it declines (§194)', () => {
  const CASE_ID = 'c-194'

  const moveSuites: Suite[] = [
    suites[0],
    {
      id: 'suite-2',
      key: 'TS-2',
      name: 'Chargebacks',
      caseCount: 1,
      tagRules: [],
      caseIds: [],
      lifecycleStatus: 'DRAFT',
      preconditions: [],
      postconditions: [],
      lockVersion: 1,
    },
  ]

  /** The declined row, as the server serves it: on `declinedPreconditions`, not `preconditions`. */
  const declinedRow = {
    id: 'attach-1',
    position: 0,
    conditionId: 'cond-1',
    title: 'Log in as standard user',
    body: ['Open the application'],
    inline: false,
    inherited: true,
    inheritedFrom: 'Refunds',
  }

  /** Filed in Refunds, declining the one rule Refunds imposes. */
  const declining = loadedCase({
    id: CASE_ID,
    key: 'TC-194',
    keySeq: 194,
    title: 'Log in with a bad password',
    steps: [
      { id: 's1', position: 0, keyword: null, action: 'Open the login page', testData: null, expectedResult: null },
    ],
    declinedPreconditions: [declinedRow],
    lockVersion: 3,
    currentVersionNo: 2,
  })

  /** The same case with the rule in force — a move costs it nothing. */
  const notDeclining: TestCase = {
    ...declining,
    preconditions: [declinedRow],
    declinedPreconditions: [],
  }

  function mockMoveReads(theCase: TestCase) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['case:edit'], {}, PROJECT)
      if (path.includes('/draft')) return null
      if (path.includes('/step-suggestions')) return corpus
      if (path.includes('/conditions')) return library
      if (path.includes('/suites')) return moveSuites
      if (path.includes('/classification')) return []
      if (path.includes('/tags')) return projectTags
      if (path.includes(`/cases/${CASE_ID}`)) return theCase
      return []
    }) as never)
  }

  function renderMoveEditor() {
    return render(
      <TestProviders
        client={createTestQueryClient()}
        route={`/projects/${PROJECT}/cases/${CASE_ID}/edit`}
      >
        <SessionRecoveryContext.Provider value={sessionRecovery}>
          <SnackbarProvider>
            <Routes>
              <Route
                path="/projects/:projectId/cases/:caseId/edit"
                element={<CaseEditorPage mode="edit" />}
              />
            </Routes>
          </SnackbarProvider>
        </SessionRecoveryContext.Provider>
      </TestProviders>,
    )
  }

  /** Drives the Placement picker to `name` — the MUI Select path `fillHeader` uses. */
  async function pickSuite(user: ReturnType<typeof userEvent.setup>, name: RegExp) {
    const field = screen.getByTestId('CaseEditor.Field.Suite')
    await user.click(within(field.closest('.MuiInputBase-root') as HTMLElement).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name }))
  }

  const alert = () => screen.queryByTestId('CaseEditor.Conditions.EndingDeclines')
  const toggle = () => screen.getByRole('switch', { name: /applies to this case/i })

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('says nothing until the picker moves — the case is still where it was', async () => {
    mockMoveReads(declining)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    expect(alert()).not.toBeInTheDocument()
    // And the toggle is the ordinary, working control it has been since §187.
    expect(toggle()).toBeEnabled()
  })

  it('NAMES the exception the move will end, once the picker has moved', async () => {
    // A count alone would leave the author to work out which of the rules on screen they are about
    // to lose — `BulkResultView`'s "a bare `affected: 38` cannot be rendered as a preview" (§6f),
    // applied to a list the editor already holds the titles for.
    const user = userEvent.setup()
    mockMoveReads(declining)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await pickSuite(user, /chargebacks/i)

    const banner = await screen.findByTestId('CaseEditor.Conditions.EndingDeclines')
    expect(banner).toHaveTextContent('Moving this case ends 1 condition exception')
    expect(banner).toHaveTextContent('Log in as standard user')
    expect(banner).toHaveTextContent(/will not bring it back/i)
  })

  it('a move that costs nothing says nothing — the event is the DECLINE ending, not the move', async () => {
    const user = userEvent.setup()
    mockMoveReads(notDeclining)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await pickSuite(user, /chargebacks/i)

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Field.Suite')).toHaveTextContent(/chargebacks/i))
    expect(alert()).not.toBeInTheDocument()
  })

  it('withdraws the warning when the picker goes back — it is a preview, not a latch', async () => {
    const user = userEvent.setup()
    mockMoveReads(declining)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await pickSuite(user, /chargebacks/i)
    await screen.findByTestId('CaseEditor.Conditions.EndingDeclines')

    await pickSuite(user, /refunds/i)
    await waitFor(() => expect(alert()).not.toBeInTheDocument())
    expect(toggle()).toBeEnabled()
  })

  it('DISABLES the decline toggle while the move is pending, rather than accepting a discarded act', async () => {
    // The defect this slice is mostly about. The rows below the picker belong to the folder being
    // left; `normalizeDeclines` bounds the submitted set by the DESTINATION's attachments, which
    // share nothing with them (§98), so a switch flipped here changes the request and not the case.
    const user = userEvent.setup()
    mockMoveReads(declining)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...declining, currentVersionNo: 3 } as never)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await pickSuite(user, /chargebacks/i)

    await waitFor(() => expect(toggle()).toBeDisabled())
    // Asserted through the REQUEST, not the attribute alone: `disabled` is how the browser is asked
    // to swallow the click, and what matters is that the set the save carries is the one the author
    // last chose while the case was still in Refunds.
    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { declinedConditionIds: string[] }
    expect(body.declinedConditionIds).toEqual(['attach-1'])
  })

  it('reports the cost on the way out, as a warning and in one composed snackbar', async () => {
    // The only record that survives the navigate() on the next line — `CaseListPage`'s treatment of
    // the same event (§193), on the path that has no dialog to close.
    const user = userEvent.setup()
    mockMoveReads(declining)
    vi.spyOn(api, 'patch').mockResolvedValue({
      ...declining,
      suiteId: 'suite-2',
      currentVersionNo: 3,
      declinedPreconditions: [],
      declinedPostconditions: [],
    } as never)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await pickSuite(user, /chargebacks/i)
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    expect(await screen.findByText(/Saved TC-194 as v3 · the move ended 1 condition exception/)).toBeInTheDocument()
  })

  it('reports NOTHING when the response kept the decline — the snackbar is measured, not predicted', async () => {
    // What makes the two halves different instruments. The alert predicts from §98's flatness; this
    // reads what the saved case no longer carries. Hand back a server that kept the row and the
    // record stays true while the prediction is the thing that was wrong — the right way round, and
    // the reason `CaseConditions#ancestry`'s "a widening is a decision with a slice behind it" has
    // somewhere to land.
    const user = userEvent.setup()
    mockMoveReads(declining)
    vi.spyOn(api, 'patch').mockResolvedValue({
      ...declining,
      suiteId: 'suite-2',
      currentVersionNo: 3,
      declinedPreconditions: [declinedRow],
      declinedPostconditions: [],
    } as never)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await pickSuite(user, /chargebacks/i)
    await screen.findByTestId('CaseEditor.Conditions.EndingDeclines')
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    expect(await screen.findByText('Saved TC-194 as v3')).toBeInTheDocument()
    expect(screen.queryByText(/the move ended/)).not.toBeInTheDocument()
  })

  it('a save that does not move the case never asks the question', async () => {
    // The gate on the subtraction. Without it a response served by anything less than the whole
    // contract shape would read as "the server dropped every decline" on an ordinary title fix.
    const user = userEvent.setup()
    mockMoveReads(declining)
    vi.spyOn(api, 'patch').mockResolvedValue({ ...declining, currentVersionNo: 3 } as never)
    renderMoveEditor()

    await screen.findByTestId('CaseEditor.Preconditions.Inherited.Item')
    await user.type(screen.getByTestId('CaseEditor.Field.Title'), '!')
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    expect(await screen.findByText('Saved TC-194 as v3')).toBeInTheDocument()
    expect(screen.queryByText(/the move ended/)).not.toBeInTheDocument()
  })
})
