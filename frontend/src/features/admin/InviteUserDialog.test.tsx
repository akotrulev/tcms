import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { InviteUserDialog } from './InviteUserDialog'
import { heldPermissions } from '../../test/permissions'
import type { PermissionKey } from '../../api/types'

/**
 * Inviting a colleague — the write that gets a second person into the company at all.
 *
 * Four things are locked here, and every one of them is invisible to the build and the linter:
 *
 *   1. **The THREE outcomes the server distinguishes are rendered differently.** `emailed` is a
 *      plain success and closes the dialog. `issued && !emailed` is "this install has no relay".
 *      `!issued` is "nothing needed sending" — already invited, or already a member. The live pass
 *      found the version of this that read the middle one off `emailed` alone, which told an
 *      administrator with a working relay that their mail was broken on every second click.
 *   2. **The dialog stays open to say so.** The assertion is the one a refactor breaks by tidying
 *      the close into the happy path.
 *   3. **Server field errors land on the input that caused them**, not in a banner.
 *   4. **Client validation matches the server's**, so the form cannot refuse an address the API
 *      would accept.
 */

const invited = {
  id: 'user-new',
  displayName: 'Nora Newcomer',
  email: 'nora@example.com',
  avatarUrl: null,
  status: 'INVITED',
  lastLoginAt: null,
  teams: [],
}

/** The gate reads `company:members` at company scope, and nothing else here reads anything. */
function mockPermissions(permissions: PermissionKey[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDialog() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <InviteUserDialog open onClose={onClose} />
    </TestProviders>,
  )
}

let onClose: () => void

async function fillIn(email: string, displayName: string) {
  await userEvent.type(screen.getByTestId('Admin.Invite.Email'), email)
  await userEvent.type(screen.getByTestId('Admin.Invite.DisplayName'), displayName)
}

describe('InviteUserDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    onClose = vi.fn()
    mockPermissions(['company:members'])
  })

  it('sends the invitation and closes when a link is actually on its way', async () => {
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({
        user: invited,
        issued: true,
        emailed: true,
        expiresAt: '2026-08-08T00:00:00Z',
      })
    renderDialog()

    await fillIn('nora@example.com', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(post).toHaveBeenCalledWith('/users', {
      email: 'nora@example.com',
      displayName: 'Nora Newcomer',
    })
  })

  it('stays open and says so when the instance has no mail relay', async () => {
    // The invitation is valid and nothing was sent. Closing on a toast here is how an operator
    // never learns they have to carry the link across by hand.
    vi.spyOn(api, 'post').mockResolvedValue({
      user: invited,
      issued: true,
      emailed: false,
      expiresAt: '2026-08-08T00:00:00Z',
    })
    renderDialog()

    await fillIn('nora@example.com', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    expect(await screen.findByTestId('Admin.Invite.NotEmailed')).toHaveTextContent(
      /no mail relay/i,
    )
    expect(onClose).not.toHaveBeenCalled()
  })

  it('says "already a member" rather than "nothing was sent" when there was nothing to send', async () => {
    // The two `emailed: false` branches are told apart by `expiresAt`: null means there is nothing
    // outstanding to accept. Rendering one message for both would tell an admin their mail is
    // broken when the colleague is simply already here.
    vi.spyOn(api, 'post').mockResolvedValue({
      user: invited,
      issued: false,
      emailed: false,
      expiresAt: null,
    })
    renderDialog()

    await fillIn('nora@example.com', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    expect(await screen.findByTestId('Admin.Invite.NotEmailed')).toHaveTextContent(
      /already a member/i,
    )
  })

  it('says the existing link still stands when nothing new was minted', async () => {
    // The state the live pass added `issued` for. On an install WITH a relay this is what a second
    // click answers, and reading it off `emailed` alone reports a mail outage that is not
    // happening — while hiding the fact that actually matters: their first link is still good.
    vi.spyOn(api, 'post').mockResolvedValue({
      user: invited,
      issued: false,
      emailed: false,
      expiresAt: '2026-08-08T00:00:00Z',
    })
    renderDialog()

    await fillIn('nora@example.com', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    const alert = await screen.findByTestId('Admin.Invite.NotEmailed')
    expect(alert).toHaveTextContent(/already invited/i)
    expect(alert).not.toHaveTextContent(/no mail relay/i)
  })

  it('lands a server field error on the input that caused it', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        email: ['Enter a valid email address.'],
      }),
    )
    renderDialog()

    await fillIn('nora@example.com', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(screen.queryByTestId('Admin.Invite.Error')).not.toBeInTheDocument()
  })

  it('puts a refusal with no field in the form-level alert', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(403, 'You do not have permission to manage access here.'),
    )
    renderDialog()

    await fillIn('nora@example.com', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    expect(await screen.findByTestId('Admin.Invite.Error')).toBeInTheDocument()
  })

  it('refuses something that is not an address without a round trip', async () => {
    const post = vi.spyOn(api, 'post')
    renderDialog()

    await fillIn('Nora Newcomer', 'Nora Newcomer')
    await userEvent.click(screen.getByTestId('Admin.Invite.Submit'))

    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('says up front that an invitation grants nothing', async () => {
    // Membership conveys authentication and zero project visibility (roles spec §A1). An admin who
    // stops after inviting has added somebody who signs in to an empty product — better said
    // before they send it than discovered by the invitee.
    renderDialog()
    expect(await screen.findByTestId('Admin.Invite.Hint')).toHaveTextContent(/until you grant/i)
  })
})
