import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { User } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { RemoveUserDialog } from './RemoveUserDialog'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * Removing a person from the company — the confirmation for the one write here that takes access
 * away rather than granting it.
 *
 * Three things are locked, and none of them is visible to the build or the linter:
 *
 *   1. **It names the person and states the two consequences that do not follow from "Remove"** —
 *      that the session ends immediately, and that their roles are kept, so inviting them back
 *      restores what they had. An administrator who assumes a removal deletes something will
 *      re-invite and be surprised by a project list.
 *   2. **The refusal is rendered on the dialog rather than swallowed.** The self-removal 400
 *      should be unreachable — the row's button is disabled for the caller's own row — which is
 *      exactly why this has to work: a disable that stops working is silent otherwise.
 *   3. **The dialog stays open when the server refuses**, so the sentence explaining what to do
 *      instead is still on screen.
 */

const colleague: User = {
  id: 'user-departing',
  displayName: 'Dee Departed',
  email: 'dee@example.com',
  avatarUrl: null,
  status: 'ACTIVE',
  lastLoginAt: null,
  teams: [],
}

function renderDialog() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <RemoveUserDialog open user={colleague} onClose={onClose} />
    </TestProviders>,
  )
}

let onClose: () => void

describe('RemoveUserDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    onClose = vi.fn()
  })

  it('names the person and both consequences of removing them', () => {
    renderDialog()

    expect(screen.getByTestId('Admin.Users.Remove')).toHaveTextContent('Dee Departed')
    // "their session ends immediately" is the answer to the question an administrator offboarding
    // somebody in a hurry is actually asking, and it is not inferable from the verb.
    expect(screen.getByTestId('Admin.Users.Remove')).toHaveTextContent(/session ends immediately/i)
    expect(screen.getByTestId('Admin.Users.Remove')).toHaveTextContent(/inviting them back/i)
  })

  it('removes the person and closes', async () => {
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderDialog()

    await userEvent.click(screen.getByTestId('Admin.Users.Remove.Confirm'))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(del).toHaveBeenCalledWith('/users/user-departing')
  })

  it('renders the self-removal refusal on the dialog, and stays open', async () => {
    // The refused write is the subject: the dialog stays open and says why.
    drivingAFailedWrite()
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        userId: ['You cannot remove yourself from this company. Ask another administrator to do it.'],
      }),
    )
    renderDialog()

    await userEvent.click(screen.getByTestId('Admin.Users.Remove.Confirm'))

    // The server's own sentence, not a generic "something went wrong": it is the only one that
    // says what to do instead.
    expect(await screen.findByTestId('Admin.Users.Remove.Error')).toHaveTextContent(
      /ask another administrator/i,
    )
    expect(onClose).not.toHaveBeenCalled()
  })

  it('falls back to the described error when the failure carries no field errors', async () => {
    // The refused write is the subject: this asserts the described-error fallback.
    drivingAFailedWrite()
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(403, 'You do not have permission to manage access here.'),
    )
    renderDialog()

    await userEvent.click(screen.getByTestId('Admin.Users.Remove.Confirm'))

    expect(await screen.findByTestId('Admin.Users.Remove.Error')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })
})
