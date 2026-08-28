import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { api } from '../../api/client'
import type {
  ClassificationDimension,
  Condition,
  GherkinParseResult,
  PermissionKey,
  StepSuggestion,
  Suite,
  TestCase,
  TestCaseSummary,
} from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { aCase, aCaseSummary, aStep } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { CaseEditorPage } from './CaseEditorPage'
import { newCaseDraftKey } from './useNewCaseDraft'

/**
 * The case editor around the document — **rewritten with the surface it tests** (§003 Phase 1+2).
 *
 * ## What left this file, and where its subject went
 *
 * Forty-four tests drove a step-row editor: auto-append, the ▲▼ reorder path, the drag handle, the
 * keyword select, the per-row completion, the three data modalities. That surface is deleted, so
 * those tests are not ported — a test of a control nobody can reach is a green assertion about
 * nothing. What they were *about* survives in three places, and none of it is untested:
 *
 * | Was proved by | Is proved by |
 * |---|---|
 * | trim-then-drop, "at least one step", the four per-row rules | `CaseSchema.test.ts` and `casePayload.test.ts`, which ask them directly |
 * | the corpus completing an action | `CaseDocument.test.tsx` (under the caret) and this file (the standing panel) |
 * | the three data spellings | `gherkinText.test.ts` per construct, and the server's parser |
 *
 * What this file keeps is the editor's own behaviour: the blank state's three starts, the standing
 * corpus, the rail as controls, the classification branch, and the two things that were never
 * about steps at all — the draft buffer and §187/§194's declines.
 *
 * The per-test budget stays raised for the reason `vitest.config.ts` records: this file renders a
 * whole editor per test and the runner is a shared six-core box.
 */
vi.setConfig({ testTimeout: 60_000 })

const PROJECT = 'p1'

const suites: Suite[] = [
  {
    id: 'suite-1',
    key: 'TS-1',
    name: 'Refunds',
    filedCount: 3,
    addedCount: 0,
    taggedCount: 0,
    caseCount: 3,
    tagRules: [],
    caseIds: [],
    lifecycleStatus: 'DRAFT',
    preconditions: [],
    postconditions: [],
    lockVersion: 1,
  },
]

const corpus: StepSuggestion[] = [
  { action: 'Refund the payment', testData: '120.00 EUR', expectedResult: 'The refund is accepted', useCount: 41 },
  { action: 'Refund it twice', testData: null, expectedResult: null, useCount: 2 },
]

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
]

const emptyDimensions: ClassificationDimension[] = [
  { id: 'd1', key: 'test_type', name: 'Test Type', cardinality: 'SINGLE', hierarchical: false, locked: true, values: [] },
  { id: 'd2', key: 'feature', name: 'Feature', cardinality: 'MULTI', hierarchical: true, locked: false, values: [] },
]

const oneFullDimension: ClassificationDimension[] = [
  {
    id: 'd1',
    key: 'test_type',
    name: 'Test Type',
    cardinality: 'SINGLE',
    hierarchical: false,
    locked: true,
    values: [{ id: 'cv-1', parentId: null, name: 'Functional', path: 'Functional', color: null }],
  },
  { id: 'd2', key: 'feature', name: 'Feature', cardinality: 'MULTI', hierarchical: true, locked: false, values: [] },
]

const recentCase: TestCaseSummary = aCaseSummary({
  id: 'c-recent',
  key: 'TC-9',
  title: 'The most recently edited case',
  updatedAt: '2026-08-27T12:00:00Z',
})

const RECENT_FEATURE = 'Feature: Refunds\n\n  Scenario: The most recently edited case\n    Given a payment\n'

function parsed(over: Partial<GherkinParseResult> = {}): GherkinParseResult {
  return {
    title: 'Double refund',
    objective: null,
    tags: [],
    background: [],
    steps: [
      { id: null, keyword: 'GIVEN', action: 'a settled payment', testData: null, expectedResult: null, docString: null, dataTable: null },
    ],
    ...over,
  }
}

type Reads = {
  permissions?: PermissionKey[]
  dimensions?: ClassificationDimension[]
  corpusRows?: StepSuggestion[]
  cases?: TestCaseSummary[]
  theCase?: TestCase
}

function mockReads({
  permissions = ['case:create', 'case:edit', 'library:manage'],
  dimensions = oneFullDimension,
  corpusRows = corpus,
  cases = [recentCase],
  theCase,
}: Reads = {}) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (path.includes('/step-suggestions')) return corpusRows
    if (path.includes('/conditions')) return library
    if (path.includes('/suites')) return suites
    if (path.includes('/classification')) return dimensions
    if (path.includes('/tags')) return []
    if (path.includes('/draft')) return null
    if (theCase && path.match(/\/cases\/[^/?]+$/)) return theCase
    if (path.includes('/cases')) return { items: cases, page: 0, size: 1, total: cases.length }
    return []
  }) as never)
}

const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderNew(search = '') {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/new${search}`}>
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

function renderEdit(caseId: string) {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/${caseId}/edit`}>
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <SnackbarProvider>
          <Routes>
            <Route path="/projects/:projectId/cases/:caseId/edit" element={<CaseEditorPage mode="edit" />} />
          </Routes>
        </SnackbarProvider>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('the blank state — the three starts (003 Phase 1)', () => {
  it('offers all three on create', async () => {
    mockReads()
    renderNew()

    expect(await screen.findByTestId('CaseEditor.Start.Write')).toBeInTheDocument()
    expect(screen.getByTestId('CaseEditor.Start.FromCase')).toBeInTheDocument()
    expect(screen.getByTestId('CaseEditor.Start.Paste')).toBeInTheDocument()
  })

  it('offers none of them on edit — there is already a case on screen to overwrite', async () => {
    mockReads({ theCase: aCase({ id: 'c1', steps: [aStep({ action: 'A step' })] }) })
    vi.spyOn(api, 'postForText').mockResolvedValue('Feature: Refunds\n\n  Scenario: X\n' as never)
    renderEdit('c1')

    await screen.findByTestId('CaseEditor.Document.Text')
    expect(screen.queryByTestId('CaseEditor.Starts')).not.toBeInTheDocument()
  })

  it('Start from a case copies the TEXT of the most recently edited one', async () => {
    const user = userEvent.setup()
    mockReads()
    const getText = vi.spyOn(api, 'getText').mockResolvedValue(RECENT_FEATURE as never)
    renderNew()

    await user.click(await screen.findByTestId('CaseEditor.Start.FromCase'))

    await waitFor(() => expect(getText).toHaveBeenCalled())
    expect(getText.mock.calls[0][0]).toBe(`/projects/${PROJECT}/cases/c-recent/feature`)
    // The `Feature:` line is chrome, so what lands in the document is the body below it.
    await waitFor(() =>
      expect(screen.getByTestId('CaseEditor.Document.Text')).toHaveValue(
        '  Scenario: The most recently edited case\n    Given a payment\n',
      ),
    )
  })

  it('disables Start from a case when the project has none, rather than hiding it', async () => {
    mockReads({ cases: [] })
    renderNew()
    expect(await screen.findByTestId('CaseEditor.Start.FromCase')).toBeDisabled()
  })

  it('opens the paste dialog from the start, not only from the panel button', async () => {
    const user = userEvent.setup()
    mockReads()
    renderNew()

    await user.click(await screen.findByTestId('CaseEditor.Start.Paste'))
    expect(await screen.findByTestId('PasteGherkin.Title')).toBeInTheDocument()
  })
})

describe('the standing corpus panel (003 Phase 1)', () => {
  it('lists the project’s steps with their use count, ranked as the server ranked them', async () => {
    mockReads()
    renderNew()

    const steps = await screen.findAllByTestId('CaseEditor.Corpus.Step')
    expect(steps[0]).toHaveTextContent('Refund the payment')
    expect(steps[0]).toHaveTextContent('41 cases')
  })

  it('inserts a step into the document on click', async () => {
    const user = userEvent.setup()
    mockReads()
    renderNew()

    await user.click((await screen.findAllByTestId('CaseEditor.Corpus.Step'))[0])
    expect(screen.getByTestId('CaseEditor.Document.Text')).toHaveValue('  Given Refund the payment')
  })

  it('reads the corpus ONCE per page, not once per step', async () => {
    const user = userEvent.setup()
    const get = mockReads()
    renderNew()

    await screen.findAllByTestId('CaseEditor.Corpus.Step')
    await user.click((await screen.findAllByTestId('CaseEditor.Corpus.Step'))[0])
    await user.click((await screen.findAllByTestId('CaseEditor.Corpus.Step'))[1])

    /*
      The whole reason `useStepCorpus` is a second hook with a second key: the completion's
      `enabled: q.trim().length > 0` gate is what stops eight mounted rows firing eight reads of a
      thousand-row list, and relaxing it would have been the cheap way to get this panel.
    */
    const corpusReads = get.mock.calls.filter((call) => String(call[0]).includes('/step-suggestions'))
    expect(corpusReads).toHaveLength(1)
    expect(String(corpusReads[0][0])).toBe(`/projects/${PROJECT}/step-suggestions`)
  })

  it('says the list fills itself, rather than showing an empty box', async () => {
    mockReads({ corpusRows: [] })
    renderNew()
    expect(await screen.findByTestId('CaseEditor.Corpus.Region.Empty')).toHaveTextContent(
      /fills itself/i,
    )
  })
})

describe('classification, when the vocabularies are empty (finding 3)', () => {
  it('renders NO control at all when every dimension is empty, and says where to configure them', async () => {
    mockReads({ dimensions: emptyDimensions })
    renderNew()

    const absent = await screen.findByTestId('CaseEditor.Classification.Absent')
    expect(absent).toHaveTextContent('Nothing configured, nothing shown.')
    expect(within(absent).getByRole('link', { name: /set up classification/i })).toBeInTheDocument()
    // Not four labelled controls that cannot be used, and not four copies of one sentence.
    expect(screen.queryByTestId('CaseEditor.Dimension.test_type')).not.toBeInTheDocument()
    expect(screen.queryByTestId('CaseEditor.Dimension.feature')).not.toBeInTheDocument()
  })

  it('renders the dimensions that have values, and ONE notice for those that do not', async () => {
    mockReads({ dimensions: oneFullDimension })
    renderNew()

    expect(await screen.findByTestId('CaseEditor.Dimension.test_type')).toBeInTheDocument()
    expect(screen.queryByTestId('CaseEditor.Dimension.feature')).not.toBeInTheDocument()
    expect(screen.getByTestId('CaseEditor.Classification.EmptyNotice')).toHaveTextContent('Feature')
  })
})

describe('the rail', () => {
  it('is present on the blank state, with the suite carried from the list', async () => {
    // UX#106 *Redundant Entry*: `/cases/new` used to be navigated to with no suite even when one
    // was selected, so the author picked Refunds again from a list of forty.
    mockReads()
    renderNew('?suiteId=suite-1')

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Field.Suite')).toHaveTextContent('Refunds'))
  })

  it('is seven rows of real controls with native roles, not clickable divs (UX#117)', async () => {
    mockReads()
    renderNew()

    const rail = await screen.findByTestId('CaseEditor.Rail')
    // Suite, Priority and Automation are comboboxes; Duration is a spinbutton. Postconditions is
    // the conditions panel, and Classification is a select. Every one of them is Tab-reachable.
    expect(within(rail).getAllByRole('combobox').length).toBeGreaterThanOrEqual(3)
    expect(within(rail).getByTestId('CaseEditor.Field.EstimatedDuration')).toHaveAttribute('type', 'number')
    expect(within(rail).getByTestId('CaseEditor.Postconditions')).toBeInTheDocument()
    expect(within(rail).getByTestId('CaseEditor.Preconditions')).toBeInTheDocument()
  })

  it('shows the automation disclosure only when the status makes it meaningful', async () => {
    const user = userEvent.setup()
    mockReads()
    renderNew()

    await screen.findByTestId('CaseEditor.Rail')
    expect(screen.queryByTestId('CaseEditor.Field.AutomationKey')).not.toBeInTheDocument()

    const status = screen.getByTestId('CaseEditor.Field.AutomationStatus')
    await user.click(within(status.closest('.MuiInputBase-root') as HTMLElement).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: /^automated$/i }))

    expect(await screen.findByTestId('CaseEditor.Field.AutomationKey')).toBeInTheDocument()
  })
})

describe('what reaches the API', () => {
  it('mirrors priority into severity — PATCH is a merge, and dropping it would leave it stale', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postText').mockResolvedValue(parsed() as never)
    const post = vi.spyOn(api, 'post').mockResolvedValue(aCase({ id: 'c9', key: 'TC-9' }) as never)
    renderNew()

    const area = await screen.findByTestId('CaseEditor.Document.Text')
    await user.click(area)
    await user.paste('  Scenario: Double refund\n    Given a settled payment')
    await user.tab()

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(post).toHaveBeenCalled())

    const body = post.mock.calls[0][1] as Record<string, unknown>
    expect(body.severity).toBe(body.priority)
    expect(body.title).toBe('Double refund')
  })

  it('refuses a case with nothing written in it, and does not post', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(aCase() as never)
    renderNew()

    await screen.findByTestId('CaseEditor.Rail')
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    expect(await screen.findByTestId('CaseEditor.ErrorSummary')).toHaveTextContent('Add at least one step.')
    expect(post).not.toHaveBeenCalled()
  })
})

describe('the New Case draft buffer (D1)', () => {
  const buffered = {
    updatedAt: '2026-08-27T09:00:00Z',
    content: { title: 'Half written', priority: 'HIGH', steps: [] },
  }

  it('offers what the last session typed, and restoring puts it back', async () => {
    const user = userEvent.setup()
    mockReads()
    window.localStorage.setItem(newCaseDraftKey(PROJECT), JSON.stringify(buffered))
    renderNew()

    await user.click(await screen.findByTestId('CaseEditor.DraftRestore'))
    await waitFor(() => expect(screen.getByTestId('CaseEditor.Field.Priority')).toHaveTextContent('High'))
  })

  it('never offers a draft buffered under another project', async () => {
    mockReads()
    window.localStorage.setItem(newCaseDraftKey('other-project'), JSON.stringify(buffered))
    renderNew()

    await screen.findByTestId('CaseEditor.Rail')
    expect(screen.queryByTestId('CaseEditor.DraftPrompt')).not.toBeInTheDocument()
  })

  it('discarding it means it is not offered again', async () => {
    const user = userEvent.setup()
    mockReads()
    window.localStorage.setItem(newCaseDraftKey(PROJECT), JSON.stringify(buffered))
    renderNew()

    await user.click(await screen.findByTestId('CaseEditor.DraftDiscard'))
    expect(window.localStorage.getItem(newCaseDraftKey(PROJECT))).toBeNull()
    expect(screen.queryByTestId('CaseEditor.DraftPrompt')).not.toBeInTheDocument()
  })
})

describe('declining an inherited condition (§187) and moving the case (§194)', () => {
  const CASE_ID = 'c-187'

  const inheritedCase = aCase({
    id: CASE_ID,
    key: 'TC-187',
    keySeq: 187,
    title: 'Log in with a bad password',
    suiteId: 'suite-1',
    suiteName: 'Refunds',
    steps: [aStep({ id: 'st-1', keyword: 'GIVEN', action: 'Open the login page' })],
    preconditions: [
      {
        id: 'attach-1',
        position: 0,
        conditionId: 'cond-1',
        title: 'Log in as standard user',
        body: ['Open the application'],
        inline: false,
        inherited: true,
        inheritedFrom: 'Refunds',
        declineCount: 0,
      },
    ],
    lockVersion: 3,
    currentVersionNo: 2,
  })

  const toggle = () => screen.getByRole('switch', { name: /applies to this case/i })

  function renderIt(theCase: TestCase = inheritedCase) {
    mockReads({ theCase })
    vi.spyOn(api, 'postForText').mockResolvedValue(
      'Feature: Refunds\n\n  Scenario: Log in with a bad password\n    Given Open the login page\n' as never,
    )
    return renderEdit(CASE_ID)
  }

  it('renders the inherited row in the rail with its switch on, and it is not an attachment editor', async () => {
    renderIt()
    // The case's OWN preconditions are the `Background:` block now; what stays here is the half
    // the text cannot say — see 003 D6 decision (a).
    expect(await screen.findByTestId('CaseEditor.Preconditions.Row')).toHaveTextContent(
      'Log in as standard user',
    )
    expect(toggle()).toBeChecked()
  })

  it('sends the ATTACHMENT id, never the library condition id', async () => {
    const user = userEvent.setup()
    renderIt()
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(inheritedCase as never)

    await user.click(await screen.findByRole('switch', { name: /applies to this case/i }))
    await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('CaseEditor.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { declinedConditionIds: string[] }
    // `attach-1`, not `cond-1`: the same entry attached at two folders is two attachments, and
    // declining one must leave the other in force.
    expect(body.declinedConditionIds).toEqual(['attach-1'])
  })

  it('NAMES the exception a pending move will end, and withdraws it when the picker goes back', async () => {
    const user = userEvent.setup()
    renderIt({ ...inheritedCase, preconditions: [], declinedPreconditions: inheritedCase.preconditions })

    await screen.findByTestId('CaseEditor.Preconditions.Row')
    expect(screen.queryByTestId('CaseEditor.Conditions.EndingDeclines')).not.toBeInTheDocument()

    const suite = screen.getByTestId('CaseEditor.Field.Suite')
    await user.click(within(suite.closest('.MuiInputBase-root') as HTMLElement).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: /not filed/i }))

    const warning = await screen.findByTestId('CaseEditor.Conditions.EndingDeclines')
    expect(warning).toHaveTextContent('Log in as standard user')

    await user.click(within(suite.closest('.MuiInputBase-root') as HTMLElement).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: /refunds/i }))
    // A preview, not a latch.
    await waitFor(() =>
      expect(screen.queryByTestId('CaseEditor.Conditions.EndingDeclines')).not.toBeInTheDocument(),
    )
  })
})
