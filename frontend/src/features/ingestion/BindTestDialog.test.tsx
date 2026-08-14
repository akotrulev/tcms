import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { UnmatchedTest } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aCaseSummary } from '../../test/fixtures'
import { BindTestDialog } from './BindTestDialog'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * The binder — and the one assertion this whole file exists for.
 *
 * **`testName` must go to the server RAW.** The normalization rule lives on the server beside the
 * parser that applies it to every incoming report; a client that normalized would be a second
 * implementation of the one string that must agree exactly, and when the two drift *every* mapping
 * in the company silently stops matching — with no error anywhere, because a mapping that matches
 * nothing looks identical to a test nobody bound.
 *
 * Nothing on screen can show the difference: the dialog renders the raw name either way, and the
 * server would accept `com.acme.refundtest#refunds in full` just as happily as
 * `com.acme.RefundTest#refunds  in full`. So the assertion is on the **request body**, which is the
 * only observer there is.
 */

const TEST: UnmatchedTest = {
  // Deliberately carrying both things a normalizer would change: capitals, and a double space.
  name: 'com.acme.RefundTest#refunds  In Full',
  normalizedName: 'com.acme.refundtest#refunds in full',
  outcome: 'FAILED',
}

const CASES = {
  items: [
    aCaseSummary({ id: 'case-1', key: 'TC-42', title: 'Refund the whole captured amount' }),
    aCaseSummary({ id: 'case-2', key: 'TC-43', title: 'Partial refund rounds correctly' }),
  ],
  facets: [],
  total: 2,
  page: 0,
  size: 20,
}

function mockApi() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['case:read', 'case:edit'])
    if (path.includes('/cases')) return CASES
    throw new Error(`unmocked read: ${path}`)
  }) as never)
  return vi.spyOn(api, 'post').mockImplementation((async () => ({
    id: 'map-1',
    testName: TEST.normalizedName,
    caseId: 'case-1',
    caseKey: 'TC-42',
    confirmedBy: 'user-1',
    confirmedAt: '2026-08-07T09:00:00Z',
  })) as never)
}

function renderDialog(onClose = vi.fn()) {
  render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route="/projects/p1/ingestions">
      <BindTestDialog open projectId="p1" test={TEST} onClose={onClose} />
    </TestProviders>,
  )
  return onClose
}

describe('BindTestDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('posts the test name EXACTLY as the report wrote it, not normalized', async () => {
    // THE HEADLINE — see this file's header. A client-side `toLowerCase().replace(/\s+/g, ' ')`
    // here would pass every visual check and break every mapping the day the rules diverge.
    const post = mockApi()
    renderDialog()

    await userEvent.click(screen.getByTestId('BindTest.Case'))
    await userEvent.click(await screen.findByText(/TC-42/))
    await userEvent.click(screen.getByTestId('BindTest.Confirm'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = vi.mocked(post).mock.calls[0]
    expect(path).toBe('/projects/p1/automation-mappings')
    expect(body).toEqual({ testName: 'com.acme.RefundTest#refunds  In Full', caseId: 'case-1' })
  })

  it('cannot be confirmed until a case is chosen', async () => {
    // The server would answer a missing caseId with a field error; letting the button fire it
    // would spend a round trip to tell somebody they have not filled the one control on screen.
    mockApi()
    renderDialog()

    expect(screen.getByTestId('BindTest.Confirm')).toBeDisabled()
  })

  it('shows the test name read-only, so the row being bound can be checked', async () => {
    mockApi()
    renderDialog()

    const name = screen.getByTestId('BindTest.Name')
    expect(name).toHaveValue(TEST.name)
    expect(name).toHaveAttribute('readonly')
  })

  it('keeps the dialog open and names the refusal when the server rejects the case', async () => {
    // The refused write is the subject: the dialog stays open and says why.
    drivingAFailedWrite()
    mockApi()
    vi.spyOn(api, 'post').mockRejectedValue(
      Object.assign(new Error('bad'), { status: 400, body: {} }),
    )
    const onClose = renderDialog()

    await userEvent.click(screen.getByTestId('BindTest.Case'))
    await userEvent.click(await screen.findByText(/TC-42/))
    await userEvent.click(screen.getByTestId('BindTest.Confirm'))

    // The refusal is about the control the reader just used, so it lands on the dialog rather
    // than as a global notification over a screen they can no longer correct.
    expect(await screen.findByTestId('BindTest.Error')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on success, because the credential problem §118 has does not exist here', async () => {
    // The opposite decision from IssueTokenDialog, deliberately: nothing here is unrecoverable —
    // the mapping is readable in the list afterwards and can be re-pointed at any time.
    mockApi()
    const onClose = renderDialog()

    await userEvent.click(screen.getByTestId('BindTest.Case'))
    await userEvent.click(await screen.findByText(/TC-42/))
    await userEvent.click(screen.getByTestId('BindTest.Confirm'))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })
})
