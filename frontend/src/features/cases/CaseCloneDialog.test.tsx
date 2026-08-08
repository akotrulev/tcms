import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { CaseCloneDialog } from './CaseCloneDialog'

/**
 * Cloning a case (§129 phase 3).
 *
 * Four behaviours are pinned, and none of them is visible to `tsc` or oxlint:
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
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const suites = [
  { id: 'suite-1', key: 'TS-1', name: 'Payments', caseCount: 12 },
  { id: 'suite-2', key: 'TS-2', name: 'Refunds', caseCount: 4 },
]

function sourceCase(over: Partial<TestCase> = {}): TestCase {
  return {
    id: 'case-1',
    key: 'TC-7',
    title: 'Full refund of a settled card payment succeeds',
    suiteId: 'suite-1',
    suiteName: 'Payments',
    automationStatus: 'MANUAL',
    ...over,
  } as TestCase
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
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'case-2', key: 'TC-8' } as never)
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
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'case-2' } as never)
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
