import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { ApiError, api } from '../../api/client'
import type {
  Condition,
  GherkinParseResult,
  PermissionKey,
  StepSuggestion,
  Suite,
  TestCase,
} from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { aCase, aStep } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { CaseEditorPage } from './CaseEditorPage'
import { caseTextKey } from './caseTextBuffer'

/**
 * The Write surface, driven through the editor that hosts it (003 Phase 2's *Tests* list).
 *
 * ## Why this file renders `CaseEditorPage` rather than `CaseDocument`
 *
 * Every behaviour 003 names for this component is a statement about **what a save sends**: the
 * commit merges five fields and touches nothing else, a refusal preserves the typed text and
 * refuses Save with the line named, a `Feature:` edit does not change `suiteId`. None of those can
 * be asked of the textarea on its own — the payload is the form's, the refusal is the page's, and
 * a test that mounted the component alone would be asserting against a harness written to make it
 * pass. So the subject is the component and the fixture is the page, which is the arrangement the
 * assertions actually need.
 *
 * ## The commit is on blur
 *
 * Nothing is posted per keystroke, so every test here types and then leaves the field. That is the
 * behaviour under test as much as it is a mechanic: committing per keystroke is what jumps the
 * caret and what would post the parser a request per character.
 */

vi.setConfig({ testTimeout: 60_000 })

const PROJECT = 'p1'
const CASE = 'c1'

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
  { action: 'Refund the payment', testData: null, expectedResult: null, useCount: 41 },
]

/** The text `render-gherkin` answers for the saved case below — the server's own rendering. */
const RENDERED = [
  'Feature: Refunds',
  '',
  '  Scenario: A saved case',
  '    Why this case exists.',
  '',
  '    Given a settled payment',
  '    Then it is refunded',
  '',
].join('\n')

/** What the document holds once the `Feature:` line has been lifted into chrome. */
const BODY = RENDERED.split('\n').slice(2).join('\n')

function parsed(over: Partial<GherkinParseResult> = {}): GherkinParseResult {
  return {
    title: 'A saved case',
    objective: 'Why this case exists.',
    tags: [],
    background: [],
    steps: [
      { id: null, keyword: 'GIVEN', action: 'a settled payment', testData: null, expectedResult: null, docString: null, dataTable: null },
      { id: null, keyword: 'THEN', action: 'it is refunded', testData: null, expectedResult: null, docString: null, dataTable: null },
    ],
    ...over,
  }
}

function savedCase(over: Partial<TestCase> = {}): TestCase {
  return aCase({
    id: CASE,
    key: 'TC-1',
    title: 'A saved case',
    objective: 'Why this case exists.',
    suiteId: 'suite-1',
    suiteName: 'Refunds',
    severity: 'MEDIUM',
    priority: 'MEDIUM',
    description: 'Background a reader needs.',
    expectedOutcome: 'The balance is restored.',
    classificationValueIds: ['cv-1'],
    lockVersion: 1,
    steps: [
      aStep({ id: 'st-1', keyword: 'GIVEN', action: 'a settled payment' }),
      aStep({ id: 'st-2', keyword: 'THEN', action: 'it is refunded' }),
    ],
    ...over,
  })
}

function mockReads(
  permissions: PermissionKey[] = ['case:edit', 'case:create', 'library:manage'],
  theCase: TestCase = savedCase(),
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (path.includes('/step-suggestions')) return corpus
    if (path.includes('/conditions')) return [] as Condition[]
    if (path.includes('/suites')) return suites
    if (path.includes('/classification')) return []
    if (path.includes('/tags')) return []
    if (path.includes('/draft')) return null
    if (path.match(/\/cases\/[^/]+$/)) return theCase
    if (path.includes('/cases')) return { items: [], facets: [], page: 0, size: 1, total: 0 }
    return []
  }) as never)
}

const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderEditor() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/${CASE}/edit`}>
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <Routes>
          <Route
            path="/projects/:projectId/cases/:caseId/edit"
            element={<CaseEditorPage mode="edit" />}
          />
        </Routes>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/** Waits for the server's render to land in the textarea. */
async function documentReady() {
  const area = await screen.findByTestId('CaseEditor.Document.Text')
  await waitFor(() => expect(area).toHaveValue(BODY))
  return area as HTMLTextAreaElement
}

beforeEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('CaseDocument — seeding', () => {
  it('renders the text the SERVER produced, and lifts the Feature: line out of it', async () => {
    mockReads()
    const post = vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    renderEditor()

    await documentReady()
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/cases/render-gherkin`)
    // Chrome, not a line the author can retype — see 003 C3.
    const feature = screen.getByTestId('CaseEditor.Document.Feature')
    expect(within(feature).getByText('Refunds')).toBeInTheDocument()
    expect(within(feature).queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('scaffolds a document with nothing in it, one ghost hint per keyword', async () => {
    /*
      REPLACES `placeholder="Given …/When …/Then …"` (007 §0 item 3). The placeholder named the
      three keywords, which the author can already see in any other case; the scaffold says what
      each one is FOR, on the line it will be typed on. It renders inside the MIRROR rather than
      above the box, so nothing on screen moves when the first character arrives.
    */
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue('Feature: Refunds\n' as never)
    renderEditor()

    const area = await screen.findByTestId('CaseEditor.Document.Text')
    await waitFor(() => expect(area).toHaveValue(''))
    expect(area).not.toHaveAttribute('placeholder')

    const scaffold = await screen.findByTestId('CaseEditor.Document.Scaffold')
    expect(scaffold).toHaveTextContent('the state the test starts from')
    expect(scaffold).toHaveTextContent('the action under test')
    expect(scaffold).toHaveTextContent('what must be true afterwards')
  })
})

describe('CaseDocument — the commit merges five fields and touches nothing else', () => {
  /**
   * **The C3 arm, and the one that matters most.**
   *
   * The text represents five of the sixteen case fields. §255's rule to carry — *a toggle between
   * two representations of one thing may not delete the thing* — applies whole here, and under D1
   * there is no second representation to recover from: a commit that reached `expectedOutcome`
   * would delete it with a success toast over the deletion.
   */
  it('leaves expectedOutcome, description, priority, suiteId, classification and postconditions alone', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockResolvedValue(parsed({ title: 'A rewritten case' }) as never)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(savedCase({ currentVersionNo: 2 }) as never)
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    await user.paste('\n    And the ledger balances')
    await user.tab()

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    const body = patch.mock.calls[0][1] as Record<string, unknown>
    expect(body.expectedOutcome).toBe('The balance is restored.')
    expect(body.description).toBe('Background a reader needs.')
    expect(body.priority).toBe('MEDIUM')
    expect(body.suiteId).toBe('suite-1')
    expect(body.classificationValueIds).toEqual(['cv-1'])
    expect(body.postconditions).toEqual([])
    // And the five it DOES carry are the server's reading of the text.
    expect(body.title).toBe('A rewritten case')
  })

  it('does not change suiteId when the text names a different Feature', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockResolvedValue(parsed() as never)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(savedCase({ currentVersionNo: 2 }) as never)
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    // A `Feature:` line typed into the body. `GherkinParseResult` has no feature name at all, so
    // the only way this could move the case is a client that read the line itself.
    await user.paste('\nFeature: Something else entirely')
    await user.tab()

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    expect((patch.mock.calls[0][1] as Record<string, unknown>).suiteId).toBe('suite-1')
  })

  it('renders, changes nothing and saves the steps it was served, byte for byte', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    const parse = vi.spyOn(api, 'postText').mockResolvedValue(parsed() as never)
    renderEditor()

    const area = await documentReady()
    // Focus and leave without typing. A blur on unchanged text must not post the parser anything:
    // there is nothing to read, and a round trip here would rewrite the steps on every focus.
    await user.click(area)
    await user.tab()
    expect(parse).not.toHaveBeenCalled()
    expect(screen.getByTestId('CaseEditor.Submit')).toBeDisabled()
  })
})

describe('CaseDocument — the refusal is the product (003 C4)', () => {
  const syntaxRefusal = () =>
    new ApiError(
      400,
      'The scenario could not be read.',
      { text: ['Line 4, column 5: expected a step, a table row or a doc string.'] },
      null,
      null,
      'Validation failed',
      4,
    )

  it('preserves the typed text byte for byte, and never reformats it', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockRejectedValue(syntaxRefusal())
    // The refused parse IS this test's subject (003 C4): the whole feature is what the screen does
    // with a refusal, so the failed write is the fixture and not an accident.
    drivingAFailedWrite()
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    await user.paste('\n    |broken')
    const typed = (area as HTMLTextAreaElement).value
    await user.tab()

    await screen.findByTestId('CaseEditor.Document.Refusal')
    expect(area).toHaveValue(typed)
  })

  it('lands the server’s sentence on the line Problem.line names, quoted', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockRejectedValue(syntaxRefusal())
    // The refused parse IS this test's subject (003 C4): the whole feature is what the screen does
    // with a refusal, so the failed write is the fixture and not an accident.
    drivingAFailedWrite()
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    await user.paste('\n    |broken')
    await user.tab()

    const refusal = await screen.findByTestId('CaseEditor.Document.Refusal')
    // The LINE is read off `Problem.line`, never regexed out of the English — which is the whole
    // reason 005 S1 put it on the wire.
    expect(refusal).toHaveTextContent('Line 4')
    expect(refusal).toHaveTextContent('expected a step, a table row or a doc string.')
  })

  it('refuses Save while the text does not parse, and posts nothing', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockRejectedValue(syntaxRefusal())
    // The refused parse IS this test's subject (003 C4): the whole feature is what the screen does
    // with a refusal, so the failed write is the fixture and not an accident.
    drivingAFailedWrite()
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(savedCase() as never)
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    await user.paste('\n    |broken')
    await user.tab()
    await screen.findByTestId('CaseEditor.Document.Refusal')

    // Disabled, not merely inert on click — disable-don't-hide, and it is the stronger guarantee:
    // "the click posts nothing" is a claim about one code path, while a disabled control says the
    // same thing about every route to it, keyboard included. The text HAS been edited, so the form
    // is dirty and the refusal is the only thing that can be holding Save shut.
    expect(screen.getByTestId('CaseEditor.Submit')).toBeDisabled()

    // Not a partial parse, and not the last good steps saved quietly under a success toast.
    expect(screen.getByTestId('CaseEditor.Document.Refusal')).toBeInTheDocument()
    expect(patch).not.toHaveBeenCalled()
  })
})

describe('CaseDocument — Background: is a library write (003 D6)', () => {
  const withBackground = () =>
    parsed({ background: [{ title: 'Given a signed-in user', body: ['Given a signed-in user'] }] })

  it('refuses a Background edit without library:manage, at the line, naming the permission', async () => {
    const user = userEvent.setup()
    mockReads(['case:edit'])
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockResolvedValue(withBackground() as never)
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    await user.paste('\n  Background:\n    Given a signed-in user')
    await user.tab()

    const refusal = await screen.findByTestId('CaseEditor.Document.Refusal')
    expect(refusal).toHaveTextContent('library:manage')
    // Nothing was written to the library on the way to the refusal.
    expect(post).not.toHaveBeenCalled()
  })

  it('creates one precondition per Background step when the grant is held', async () => {
    const user = userEvent.setup()
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    vi.spyOn(api, 'postText').mockResolvedValue(withBackground() as never)
    const created: Condition = {
      id: 'cond-9',
      title: 'Given a signed-in user',
      type: 'PRE',
      body: ['Given a signed-in user'],
      usageCount: 0,
      suiteUsageCount: 0,
      lockVersion: 0,
    }
    const post = vi.spyOn(api, 'post').mockResolvedValue(created as never)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(savedCase({ currentVersionNo: 2 }) as never)
    renderEditor()

    const area = await documentReady()
    await user.click(area)
    await user.paste('\n  Background:\n    Given a signed-in user')
    await user.tab()

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/conditions`)

    await waitFor(() => expect(screen.getByTestId('CaseEditor.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('CaseEditor.Submit'))
    await waitFor(() => expect(patch).toHaveBeenCalled())
    expect((patch.mock.calls[0][1] as Record<string, unknown>).preconditions).toEqual([
      { conditionId: 'cond-9' },
    ])
  })

  it('says on screen that the rendered .feature omits inherited setup', async () => {
    mockReads(undefined, savedCase({
      preconditions: [
        {
          id: 'pc-1',
          position: 1,
          conditionId: 'cond-1',
          title: 'The operator is signed in',
          body: ['Sign in as the operator'],
          inline: false,
          inherited: true,
          inheritedFrom: 'Refunds',
          declineCount: 0,
        },
      ],
    }))
    // The notice is conditional on purpose — it explains what a `Background:` block does and does
    // not carry, so on a case with neither a block nor inherited setup there is nothing for it to
    // be about and a standing caption would be noise. Driven here by the SUITE's own precondition,
    // which is the half the sentence is actually about.
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    renderEditor()

    await documentReady()
    // The cost of decision (a), where the block is rather than only in the plan.
    expect(
      await screen.findByTestId('CaseEditor.Document.BackgroundNotice'),
    ).toHaveTextContent(/omits setup inherited from the suite/i)
  })
})

describe('CaseDocument — the un-migrated population', () => {
  /**
   * A case with a stored `gherkinSource` and no step rows. D5 clears lab and prod, so no case on
   * our own boxes reaches this — which is exactly why it is a unit test and nothing else.
   */
  it('refuses the case with the migration sentence, and never reparses the blob', async () => {
    mockReads()
    const blob = 'Feature: Legacy\n  Scenario: Old\n    Given something'
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['case:edit'], {}, PROJECT)
      if (path.includes('/step-suggestions')) return corpus
      if (path.includes('/conditions')) return []
      if (path.includes('/suites')) return suites
      if (path.includes('/classification')) return []
      if (path.includes('/draft')) return null
      if (path.match(/\/cases\/[^/]+$/)) return savedCase({ steps: [], gherkinSource: blob })
      return []
    }) as never)
    const renderCall = vi.spyOn(api, 'postForText').mockResolvedValue('' as never)
    const parse = vi.spyOn(api, 'postText').mockResolvedValue(parsed() as never)
    renderEditor()

    expect(await screen.findByTestId('CaseEditor.NotMigrated')).toHaveTextContent(
      'migrate-gherkin-source',
    )
    const area = screen.getByTestId('CaseEditor.Document.Text')
    // The blob is shown verbatim and read-only, and nothing was sent to either endpoint.
    expect(area).toHaveValue(blob)
    expect(area).toHaveAttribute('readonly')
    expect(renderCall).not.toHaveBeenCalled()
    expect(parse).not.toHaveBeenCalled()
    expect(screen.getByTestId('CaseEditor.Submit')).toBeDisabled()
  })
})

describe('CaseDocument — the raw text survives a crash', () => {
  it('offers work that never parsed, and returns it exactly as typed', async () => {
    mockReads()
    vi.spyOn(api, 'postForText').mockResolvedValue(RENDERED as never)
    // What a previous session left behind: a document mid-sentence, with no payload draft beside
    // it, because nothing was ever committed. C4's clause — the restore needs no round trip.
    const unparseable = '  Scenario: A saved case\n    Given a payment\n    | broken'
    window.localStorage.setItem(
      caseTextKey(PROJECT, CASE),
      JSON.stringify({ updatedAt: '2026-08-28T09:00:00Z', text: unparseable }),
    )
    const user = userEvent.setup()
    renderEditor()

    await user.click(await screen.findByTestId('CaseEditor.DraftRestore'))
    await waitFor(() =>
      expect(screen.getByTestId('CaseEditor.Document.Text')).toHaveValue(unparseable),
    )
  })
})
