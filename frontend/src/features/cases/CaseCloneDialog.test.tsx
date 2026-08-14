import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { CaseConditionRef, TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { aCase, aSuite } from '../../test/fixtures'
import { CaseCloneDialog } from './CaseCloneDialog'

/**
 * Cloning a case (§129 phase 3).
 *
 * Five behaviours are pinned, and none of them is visible to `tsc` or oxlint (the fifth is §195's
 * and has its own block below, with its own reasons):
 *
 *   1. **The title is suggested, and the suggestion is editable before it becomes a row.** The
 *      server refuses to invent one precisely so a human sees it first; a dialog that opened with
 *      an empty field would satisfy the contract and reinstate the defect from the other side.
 *   2. **An untouched folder sends NO `suiteId` at all.** Absent means "the source's suite"; a
 *      dialog helpfully sending `suiteId: ''` is a 400 on uuid format, and one sending the source's
 *      id resolves the destination at a different instant from the server.
 *   3. **The automation note appears for an `AUTOMATED` source and for no other.** The status is
 *      silently forced down to `TO_BE_AUTOMATED` because the key cannot be copied — a user who
 *      meets that as a surprise reads it as a defect, and a note that is always there is one
 *      nobody reads on the day it matters.
 *   4. **A field error lands on its field.** `title` has a control, so a banner would detach the
 *      server's sentence from the input it is about.
 *   5. **The Folder picker decides whether the source's condition exceptions come across**, and the
 *      dialog says so before the copy is made rather than after (§195).
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const suites = [
  aSuite({ id: 'suite-1', key: 'TS-1', name: 'Payments', caseCount: 12 }),
  aSuite({ id: 'suite-2', key: 'TS-2', name: 'Refunds', caseCount: 4 }),
]

/**
 * The two decline lists used to be spelled out *because* of an `as TestCase` below: the cast made a
 * required field optional to `tsc`, so the fixture could promise a shape the component then
 * destructured. Omitting them was fine while nothing read them and became a TypeError the moment
 * §195's alert spread them — §38's family, at the fixture end.
 *
 * **§196 removed the cast**, so every required field is spelled out and none of them is spelled out
 * by choice any more. The next one the contract adds is a build error on `sourceCase` below.
 */
function declined(over: Partial<CaseConditionRef> = {}): CaseConditionRef {
  return {
    id: 'attach-1',
    position: 1,
    title: 'The merchant account is in live mode',
    body: ['Switch the account out of sandbox.'],
    inline: false,
    inherited: true,
    inheritedFrom: 'Payments',
    ...over,
  }
}

function sourceCase(over: Partial<TestCase> = {}): TestCase {
  return {
    id: 'case-1',
    key: 'TC-7',
    keySeq: 7,
    title: 'Full refund of a settled card payment succeeds',
    suiteId: 'suite-1',
    suiteName: 'Payments',
    priority: 'HIGH',
    lifecycleStatus: 'APPROVED',
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
    severity: null,
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

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/suites')) return suites
    return []
  }) as never)
}

function renderDialog(testCase: TestCase, onCloned = vi.fn()) {
  render(
    <TestProviders client={createTestQueryClient()}>
      <CaseCloneDialog
        projectId={PROJECT}
        testCase={testCase}
        open
        onClose={() => {}}
        onCloned={onCloned}
      />
    </TestProviders>,
  )
  return onCloned
}

describe('CaseCloneDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockReads()
  })

  it('opens with the title suggested from the source', async () => {
    renderDialog(sourceCase())

    expect(await screen.findByTestId('CaseDetail.Clone.Title')).toHaveValue(
      'Copy of Full refund of a settled card payment succeeds',
    )
  })

  it('sends the edited title and no suiteId when the folder is left alone', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(aCase({ id: 'case-2', key: 'TC-8' }) as never)
    const onCloned = renderDialog(sourceCase())

    const title = await screen.findByTestId('CaseDetail.Clone.Title')
    await userEvent.clear(title)
    await userEvent.type(title, 'Full refund, EUR')
    await userEvent.click(screen.getByTestId('CaseDetail.Clone.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/projects/${PROJECT}/cases/case-1/clone`)
    expect(body.title).toBe('Full refund, EUR')
    // Absent, not null and not '': the endpoint reads an absent field as the source's suite, and
    // both of the other spellings are a different request.
    expect(body).not.toHaveProperty('suiteId')
    await waitFor(() => expect(onCloned).toHaveBeenCalled())
  })

  it('sends the chosen folder when one is picked', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(aCase({ id: 'case-2' }) as never)
    renderDialog(sourceCase())

    await screen.findByTestId('CaseDetail.Clone.Title')
    // MUI's select is a listbox behind a button, so the value reaches react-hook-form only by
    // opening the popover and clicking — `selectOptions` cannot drive it.
    await userEvent.click(screen.getByRole('combobox', { name: /folder/i }))
    await userEvent.click(await screen.findByTestId('CaseDetail.Clone.Suite.TS-2'))
    await userEvent.click(screen.getByTestId('CaseDetail.Clone.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.suiteId).toBe('suite-2')
  })

  /**
   * §195 — the Folder picker decides whether the source's condition exceptions come across, and
   * these four are the whole of the claim. None is visible to `tsc` or oxlint: the alert typechecks
   * whether it is rendered unconditionally, never, or on the wrong side of the comparison.
   */
  describe('the exceptions a copy filed elsewhere will not carry', () => {
    const withDeclines = () =>
      sourceCase({
        declinedPreconditions: [declined()],
        declinedPostconditions: [
          declined({ id: 'attach-2', title: 'The settlement batch is closed' }),
        ],
      })

    async function pickFolder(testId: string) {
      await screen.findByTestId('CaseDetail.Clone.Title')
      await userEvent.click(screen.getByRole('combobox', { name: /folder/i }))
      await userEvent.click(await screen.findByTestId(testId))
    }

    it('names them once a different folder is picked', async () => {
      renderDialog(withDeclines())
      await pickFolder('CaseDetail.Clone.Suite.TS-2')

      const alert = await screen.findByTestId('CaseDetail.Clone.NotCarriedDeclines')
      // Named, not counted: "2 exceptions" leaves the reader unable to find out which two.
      expect(alert).toHaveTextContent('2 condition exceptions')
      expect(alert).toHaveTextContent('The merchant account is in live mode')
      expect(alert).toHaveTextContent('The settlement batch is closed')
      // The source keeps them — a clone destroys nothing, and the alert must not read as a loss.
      expect(alert).toHaveTextContent(/TC-7 keeps its exceptions/)
    })

    it('says nothing while the copy is going to the source folder', async () => {
      renderDialog(withDeclines())
      // Paired with the test above: an unconditional alert passes that one and fails this. This is
      // the state the dialog opens in, and it is the state in which the declines DO come across.
      await screen.findByTestId('CaseDetail.Clone.Title')
      expect(screen.queryByTestId('CaseDetail.Clone.NotCarriedDeclines')).not.toBeInTheDocument()
    })

    it('says nothing when the source folder is picked from the list by name', async () => {
      renderDialog(withDeclines())
      await pickFolder('CaseDetail.Clone.Suite.TS-1')

      // The picker's own '' encoding is "same as the source", but choosing that same folder
      // explicitly is not a move either — the server compares ids, and so does this.
      expect(screen.queryByTestId('CaseDetail.Clone.NotCarriedDeclines')).not.toBeInTheDocument()
    })

    it('says nothing about a case that declines nothing', async () => {
      renderDialog(sourceCase())
      await pickFolder('CaseDetail.Clone.Suite.TS-2')

      // Empty is the ordinary state, so the alert must be keyed on the declines and not on the move
      // — a folder change alone would put a warning about nothing on most clones in the product.
      expect(screen.queryByTestId('CaseDetail.Clone.NotCarriedDeclines')).not.toBeInTheDocument()
    })
  })

  it('warns that the automation key is dropped — only for an automated source', async () => {
    renderDialog(sourceCase({ automationStatus: 'AUTOMATED' }))
    expect(await screen.findByTestId('CaseDetail.Clone.AutomationNote')).toHaveTextContent(
      /To be automated/i,
    )
  })

  it('says nothing about automation on a manual case', async () => {
    renderDialog(sourceCase())
    // Paired with the test above: an unconditional note passes that one and fails this.
    await screen.findByTestId('CaseDetail.Clone.Title')
    expect(screen.queryByTestId('CaseDetail.Clone.AutomationNote')).not.toBeInTheDocument()
  })

  it('lands a title error on the title field rather than in the banner', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        title: ['A case with this title already exists in that folder.'],
      }),
    )
    renderDialog(sourceCase())

    await screen.findByTestId('CaseDetail.Clone.Title')
    await userEvent.click(screen.getByTestId('CaseDetail.Clone.Submit'))

    expect(
      await screen.findByText('A case with this title already exists in that folder.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('CaseDetail.Clone.Error')).not.toBeInTheDocument()
  })

  it('puts a refusal with no field — a 403 — in the banner', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(new ApiError(403, 'You cannot create cases here.'))
    renderDialog(sourceCase())

    await screen.findByTestId('CaseDetail.Clone.Title')
    await userEvent.click(screen.getByTestId('CaseDetail.Clone.Submit'))

    // Dropped instead, the user watches a submit do nothing at all.
    expect(await screen.findByTestId('CaseDetail.Clone.Error')).toBeInTheDocument()
  })
})
