import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { ExecutionBulkOperation, ExecutionBulkResult } from '../../api/types'
import { aBulkAssignResult, anAssignmentChange, aUserRef } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { TestProviders } from '../../test/TestProviders'
import { AssignExecutionsDialog } from './AssignExecutionsDialog'

/**
 * Preview-then-commit over a run's checklist (§244).
 *
 * The arms are the four places this dialog can lie: committing without a preview, committing a
 * preview that described a *different* act, dropping a person who received nothing, and putting a
 * refusal somewhere other than the control that caused it.
 */

const ANA = aUserRef({ id: 'user-2', displayName: 'A. Ana' })
const BEN = aUserRef({ id: 'user-3', displayName: 'B. Ben' })
const CHO = aUserRef({ id: 'user-4', displayName: 'C. Cho' })

const SELECTED = ['ex1', 'ex2', 'ex3']

function mockReads() {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['run:execute'])
    if (/\/projects\/[^/]+\/members$/.test(path)) return [ANA, BEN, CHO]
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

/** Every write this dialog makes, recorded, so an arm can assert the body that went out. */
function mockWrite(answer: (body: ExecutionBulkOperation) => ExecutionBulkResult) {
  const sent: ExecutionBulkOperation[] = []
  const spy = vi
    .spyOn(api, 'post')
    // Anchored on the whole sub-resource so a sibling path added later is not swallowed by a
    // prefix — the fixtures rule, applied to a write.
    .mockImplementation((async (path: string, body: ExecutionBulkOperation) => {
      if (!/^\/projects\/[^/]+\/runs\/[^/]+\/executions\/bulk$/.test(path)) {
        throw new Error(`unmocked write: ${path}`)
      }
      sent.push(body)
      return answer(body)
    }) as never)
  return { sent, spy }
}

function renderDialog(onCommitted = vi.fn()) {
  render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route="/projects/p1/runs/r1">
      <AssignExecutionsDialog
        open
        onClose={vi.fn()}
        projectId="p1"
        runId="r1"
        selectedIds={SELECTED}
        onCommitted={onCommitted}
      />
    </TestProviders>,
  )
  return onCommitted
}

async function chooseSpread(user: ReturnType<typeof userEvent.setup>, people: string[]) {
  await user.click(screen.getByTestId('RunDetail.AssignDialog.Mode.SPREAD'))
  for (const name of people) {
    await user.click(screen.getByTestId('RunDetail.AssignDialog.People'))
    await user.click(await screen.findByText(name))
  }
}

describe('AssignExecutionsDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockReads()
  })

  it('previews with dryRun true and writes nothing until the commit', async () => {
    const user = userEvent.setup()
    const { sent } = mockWrite((body) =>
      aBulkAssignResult({
        dryRun: body.dryRun !== false,
        total: 3,
        changed: [anAssignmentChange({ executionId: 'ex1', caseKey: 'TC-1' })],
        bulkOperationId: body.dryRun === false ? 'bulk-1' : null,
      }),
    )
    renderDialog()

    await user.click(screen.getByTestId('RunDetail.AssignDialog.Preview'))

    await screen.findByTestId('RunDetail.AssignDialog.Preview.Result')
    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({ action: 'ASSIGN', dryRun: true, executionIds: SELECTED })
  })

  it('commits only after a preview, and hands the result back', async () => {
    const user = userEvent.setup()
    const { sent } = mockWrite((body) =>
      aBulkAssignResult({
        dryRun: body.dryRun !== false,
        total: 3,
        changed: [anAssignmentChange({ executionId: 'ex1', caseKey: 'TC-1' })],
        bulkOperationId: body.dryRun === false ? 'bulk-1' : null,
      }),
    )
    const onCommitted = renderDialog()

    expect(screen.getByTestId('RunDetail.AssignDialog.Confirm')).toBeDisabled()

    await user.click(screen.getByTestId('RunDetail.AssignDialog.Preview'))
    await screen.findByTestId('RunDetail.AssignDialog.Preview.Result')
    await user.click(screen.getByTestId('RunDetail.AssignDialog.Confirm'))

    await waitFor(() => expect(onCommitted).toHaveBeenCalledTimes(1))
    expect(sent).toHaveLength(2)
    expect(sent[1].dryRun).toBe(false)
    expect(onCommitted.mock.calls[0][0]).toMatchObject({ bulkOperationId: 'bulk-1' })
  })

  it('drops a stale preview when the argument changes', async () => {
    const user = userEvent.setup()
    mockWrite(() =>
      aBulkAssignResult({
        total: 3,
        changed: [anAssignmentChange({ executionId: 'ex1', caseKey: 'TC-1' })],
      }),
    )
    renderDialog()

    await user.click(screen.getByTestId('RunDetail.AssignDialog.Preview'))
    await screen.findByTestId('RunDetail.AssignDialog.Preview.Result')

    // Switching mode describes a different act, so the preview on screen no longer describes what
    // Confirm would do — which is the whole defect preview-then-commit exists to prevent.
    await user.click(screen.getByTestId('RunDetail.AssignDialog.Mode.SPREAD'))

    await waitFor(() =>
      expect(screen.queryByTestId('RunDetail.AssignDialog.Preview.Result')).toBeNull(),
    )
    expect(screen.getByTestId('RunDetail.AssignDialog.Confirm')).toBeDisabled()
  })

  it('shows a chosen person who receives nothing, with a count of zero', async () => {
    const user = userEvent.setup()
    mockWrite(() =>
      aBulkAssignResult({
        total: 2,
        changed: [
          anAssignmentChange({ executionId: 'ex1', caseKey: 'TC-1', assigneeId: ANA.id, assigneeName: ANA.displayName }),
          anAssignmentChange({ executionId: 'ex2', caseKey: 'TC-2', assigneeId: BEN.id, assigneeName: BEN.displayName }),
        ],
      }),
    )
    renderDialog()

    await chooseSpread(user, [ANA.displayName, BEN.displayName, CHO.displayName])
    await user.click(screen.getByTestId('RunDetail.AssignDialog.Preview'))

    const tally = await screen.findByTestId('RunDetail.AssignDialog.Preview.Tally')
    const shares = within(tally).getAllByTestId('RunDetail.AssignDialog.Preview.Share')
    expect(shares).toHaveLength(3)
    // A groupBy over `changed` would drop this row entirely, which is the one a coordinator most
    // needs to see: somebody expecting work and getting none.
    expect(shares[2]).toHaveTextContent('C. Cho')
    expect(shares[2]).toHaveTextContent('0')
  })

  it('lands a 400 on the people control rather than in the corner', async () => {
    const user = userEvent.setup()
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        'payload.assigneeIds': ['These people are not members of this company: user-9'],
      }),
    )
    renderDialog()

    await chooseSpread(user, [ANA.displayName])
    await user.click(screen.getByTestId('RunDetail.AssignDialog.Preview'))

    const message = await screen.findByRole('alert')
    expect(message).toHaveTextContent('not members of this company')
    expect(screen.queryByTestId('RunDetail.AssignDialog.Error')).toBeNull()
  })

  it('renders a 409 in place, saying the run has ended', async () => {
    const user = userEvent.setup()
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(409, 'This run has ended, so its checklist can no longer be reorganised.'),
    )
    renderDialog()

    await user.click(screen.getByTestId('RunDetail.AssignDialog.Preview'))

    const shown = await screen.findByTestId('RunDetail.AssignDialog.Error')
    expect(shown).toBeInTheDocument()
  })
})
