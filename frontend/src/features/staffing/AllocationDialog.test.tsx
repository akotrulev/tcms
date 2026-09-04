import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { aUserRef, anAllocation } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { AllocationDialog } from './AllocationDialog'

/**
 * Allocating somebody, and the one behaviour this dialog exists for: **the 409 is inline**.
 *
 * The conflict is about a value the person has this second finished typing, and the server's
 * refusal names the range it collided with. A toast would scroll away from the dates it is about;
 * a generic "conflict" banner would drop the only actionable half of the message. So the assertion
 * is on the range appearing beside the form, and on the dialog staying open.
 *
 * The write arm is anchored — `/^\/projects\/[^/]+\/allocations$/` is the create and
 * `/projects/{id}/staffing` is the read, and a prefix match would serve one for the other.
 */

const MIRA = aUserRef({ id: 'u-mira', displayName: 'Mira Sund' })
const TOMAS = aUserRef({ id: 'u-tomas', displayName: 'Tomas Ek' })

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['staffing:allocate'])
    if (/^\/projects\/[^/]+\/members$/.test(path)) return [MIRA, TOMAS]
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDialog(allocation?: Parameters<typeof AllocationDialog>[0]['allocation']) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route="/projects/p1/staffing">
      <AllocationDialog projectId="p1" allocation={allocation} onClose={() => undefined} />
    </TestProviders>,
  )
}

async function fillCreate() {
  // The member read is what populates the options, and the select is enabled from the first frame
  // — so waiting on the CONTROL proves nothing. Wait on the option itself.
  await screen.findByRole('option', { name: 'Mira Sund' })
  await userEvent.selectOptions(screen.getByTestId('ProjectStaffing.Dialog.Person'), 'u-mira')
  await userEvent.clear(screen.getByTestId('ProjectStaffing.Dialog.Percent'))
  await userEvent.type(screen.getByTestId('ProjectStaffing.Dialog.Percent'), '60')
  await userEvent.type(screen.getByTestId('ProjectStaffing.Dialog.StartsOn'), '2026-09-01')
  await userEvent.type(screen.getByTestId('ProjectStaffing.Dialog.EndsOn'), '2026-09-15')
}

describe('Allocating somebody to a project', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends the person, the percentage and both inclusive dates', async () => {
    mockReads()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(anAllocation({ personId: 'u-mira', person: MIRA }) as never)
    renderDialog()

    await fillCreate()
    await userEvent.click(screen.getByTestId('ProjectStaffing.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = post.mock.calls[0]
    expect(path).toMatch(/^\/projects\/p1\/allocations$/)
    expect(body).toEqual({
      personId: 'u-mira',
      percent: 60,
      startsOn: '2026-09-01',
      endsOn: '2026-09-15',
      note: null,
    })
  })

  it('shows a 409 INLINE, naming the range it collided with, and keeps the dialog open', async () => {
    drivingAFailedWrite()
    mockReads()
    /*
      The server's own body. `detail` NAMES THE RANGE — "already allocated here" with no dates is a
      refusal the caller cannot act on, and the whole reason the server refuses rather than merging
      (017 D-n) is that somebody typed a number and is owed the chance to move it.
    */
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(
        409,
        'This person is already on this project from 2026-09-01 to 2026-09-15. One person has one bar per project, so change that allocation instead of adding a second one.',
        null,
        null,
        null,
        'Already allocated over those days',
      ),
    )
    renderDialog()

    await fillCreate()
    await userEvent.click(screen.getByTestId('ProjectStaffing.Dialog.Submit'))

    const conflict = await screen.findByTestId('ProjectStaffing.Dialog.Conflict')
    expect(conflict).toHaveTextContent('Already allocated over those days')
    expect(conflict).toHaveTextContent('from 2026-09-01 to 2026-09-15')

    // Beside the form, not instead of it: the dates the refusal names are still on screen and still
    // editable, which is the whole point of not raising this as a notification.
    expect(screen.getByTestId('ProjectStaffing.Dialog.StartsOn')).toBeInTheDocument()
    // And not ALSO in the generic banner — the same refusal twice, the weaker one second.
    expect(screen.queryByTestId('ProjectStaffing.Dialog.Error')).toBeNull()
  })

  it('refuses an end date before the start without asking the server', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post')
    renderDialog()

    await screen.findByRole('option', { name: 'Mira Sund' })
    await userEvent.selectOptions(screen.getByTestId('ProjectStaffing.Dialog.Person'), 'u-mira')
    await userEvent.type(screen.getByTestId('ProjectStaffing.Dialog.StartsOn'), '2026-09-15')
    await userEvent.type(screen.getByTestId('ProjectStaffing.Dialog.EndsOn'), '2026-09-01')
    await userEvent.click(screen.getByTestId('ProjectStaffing.Dialog.Submit'))

    expect(await screen.findByText(/end date cannot be before the start date/i)).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('patches only what moved, and never the person', async () => {
    mockReads()
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(anAllocation() as never)
    const existing = anAllocation({
      id: 'al-1',
      personId: 'u-mira',
      person: MIRA,
      percent: 50,
      startsOn: '2026-09-01',
      endsOn: '2026-11-30',
      note: 'Payments',
    })
    renderDialog(existing)

    await userEvent.clear(screen.getByTestId('ProjectStaffing.Dialog.Percent'))
    await userEvent.type(screen.getByTestId('ProjectStaffing.Dialog.Percent'), '70')
    await userEvent.click(screen.getByTestId('ProjectStaffing.Dialog.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    // A TRUE delta. `AllocationPatch` carries no `personId`, and moving an allocation between
    // people would slip past the overlap check, which keys on the person and the project.
    expect(patch.mock.calls[0][1]).toEqual({ percent: 70 })
    expect(screen.getByTestId('ProjectStaffing.Dialog.Person')).toBeDisabled()
  })
})
