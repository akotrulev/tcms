import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { anExecution } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { ExecutionAssignee } from './ExecutionAssignee'
import type { PermissionKey } from '../../api/types'

/**
 * A4's control — `test_execution.assignee_id`'s first writer in the SPA.
 *
 * Four behaviours are locked here, none of them visible to `tsc` or to oxlint:
 *
 *   1. **The write actually sends `null` to unassign.** `''` is what the select's own "Nobody"
 *      option carries, and sending it would be a 400 on a uuid's format — the same shape as the
 *      `planId: ''` correction `RunCreateDialog` records.
 *   2. **The gate is `run:execute`, disabled rather than hidden.** §31: a tester learns the
 *      affordance exists and is somebody else's to take. A control that vanished would teach
 *      nothing, and one gated on `result:submit` would enable for the `Automation Service`
 *      credential the server refuses.
 *   3. **An assignee who is not in the member list still shows.** `GET /members` answers the
 *      narrower "holds a grant on this project" while the column takes any active company member,
 *      so a select built only from the list renders a value matching no option — MUI shows that as
 *      blank, and the screen says "unassigned" about a case that is assigned.
 *   4. **A refusal lands in place.** The 409 that matters is "the run ended while you had this
 *      open", and a corner toast detached from the control is not where that sentence belongs.
 */

const PROJECT = 'p1'
const RUN = 'r1'
const ANA = '11111111-1111-4111-8111-111111111111'
const MARCO = '22222222-2222-4222-8222-222222222222'

const person = (id: string, displayName: string) => ({
  id,
  displayName,
  email: `${displayName.toLowerCase().replace(/\W/g, '')}@example.test`,
  avatarUrl: null,
})

const members = [person(ANA, 'Ana Petrova'), person(MARCO, 'Marco Silva')]

function mockReads(held: PermissionKey[] = ['run:execute'], roster = members) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/me/permissions')) return heldPermissions(held, {}, PROJECT)
    if (path.includes('/members')) return roster
    return []
  }) as unknown as typeof api.get)
}

function renderControl(execution = anExecution(), frozen = false) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <ExecutionAssignee
        projectId={PROJECT}
        runId={RUN}
        execution={execution}
        frozen={frozen}
      />
    </TestProviders>,
  )
}

/**
 * MUI's `select` is a listbox behind a button — see `RunCreateDialog.test.tsx`'s `choosePlan`.
 *
 * The `waitFor` is not ceremony: `PermissionGate` renders **disabled** until `/me/permissions`
 * answers, so a click fired on the first paint opens nothing and the failure reads as "the option
 * does not exist" rather than "the control was not ready yet".
 */
async function choose(optionTestId: string) {
  const select = () => screen.getByRole('combobox', { name: /assigned to/i })
  await waitFor(() => expect(select()).toBeEnabled())
  await userEvent.selectOptions(select(), await screen.findByTestId(optionTestId))
}

describe('ExecutionAssignee', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('assigns the person the reader picked', async () => {
    mockReads()
    const put = vi
      .spyOn(api, 'put')
      .mockResolvedValue(anExecution({ assignee: person(MARCO, 'Marco Silva') }) as never)

    renderControl()
    await screen.findByTestId('Execution.Assignee.Select')

    await choose(`Execution.Assignee.${MARCO}`)

    await waitFor(() => expect(put).toHaveBeenCalled())
    const [path, body] = put.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toContain(`/runs/${RUN}/executions/ex1/assignee`)
    expect(body).toEqual({ assigneeId: MARCO })
  })

  it('sends null to unassign, never the empty string the option carries', async () => {
    mockReads()
    const put = vi.spyOn(api, 'put').mockResolvedValue(anExecution() as never)

    renderControl(anExecution({ assignee: person(ANA, 'Ana Petrova') }))
    await screen.findByTestId('Execution.Assignee.Select')

    await choose('Execution.Assignee.None')

    await waitFor(() => expect(put).toHaveBeenCalled())
    const [, body] = put.mock.calls[0] as [string, Record<string, unknown>]
    // '' would be a 400 on the uuid's format — a control whose most obvious use cannot succeed.
    expect(body).toEqual({ assigneeId: null })
  })

  it('disables the control for a caller without run:execute, rather than hiding it', async () => {
    // The Viewer, and the Automation Service credential: neither holds this key, and both would
    // see four live options if the gate were `result:submit`.
    mockReads([])

    renderControl()

    // Present — §31 — and not usable.
    expect(await screen.findByTestId('Execution.Assignee')).toBeInTheDocument()
    // The native control's own `disabled`, not an ARIA echo of it: T16's `NativeSelect` is a real
    // `<select>`, so being disabled is a property of the element rather than a state it announces.
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: /assigned to/i })).toBeDisabled(),
    )
  })

  it('disables it on a closed run and says why', async () => {
    mockReads()

    renderControl(anExecution(), true)

    expect(await screen.findByText(/can no longer be reorganised/i)).toBeInTheDocument()
  })

  it('keeps showing an assignee the member list does not carry', async () => {
    // Assigned, then their project grant was revoked — a real row, and the list is the narrower
    // question. Dropping them would render the case as unassigned.
    mockReads(['run:execute'], [person(MARCO, 'Marco Silva')])

    renderControl(anExecution({ assignee: person(ANA, 'Ana Petrova') }))

    expect(await screen.findByText('Ana Petrova')).toBeInTheDocument()
  })

  it('shows the run-ended refusal beside the control', async () => {
    // The refused write is the subject: this asserts where its sentence lands.
    drivingAFailedWrite()
    mockReads()
    vi.spyOn(api, 'put').mockRejectedValue(
      new ApiError(
        409,
        'This run has ended, so its checklist can no longer be reorganised. Reopen the run to make changes.',
        null,
        null,
        null,
        'This run cannot change that way',
      ),
    )

    renderControl()
    await screen.findByTestId('Execution.Assignee.Select')

    await choose(`Execution.Assignee.${ANA}`)

    expect(await screen.findByTestId('Execution.Assignee.Error')).toHaveTextContent(
      /no longer be reorganised/i,
    )
  })
})
