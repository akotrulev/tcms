import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { aWorkItem } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { TaskCreateDialog } from './TaskCreateDialog'
import { drivingAFailedRead } from '../../test/renderFailures'

/**
 * Adding a task, and the assignee picker §54 built.
 *
 * What is worth pinning here is the part that is a *decision* rather than markup:
 *
 *   1. **The picker is fed by `GET /projects/{id}/members`, not `GET /users`.** The company
 *      directory would list colleagues who hold no grant on this project — the server refuses them
 *      as assignees, so the form would offer a choice the submit rejects. This is the one
 *      assertion that fails if somebody "simplifies" the hook to the directory it looks like.
 *   2. **"Unassigned" sends `null`, not `''`.** The contract types `assigneeId` as `uuid | null`,
 *      and the empty string is the default value of the unassigned
 *      option. Sending it would 400 on the option the form starts on, which is the most-used path through this dialog.
 *   3. **A members request that fails does not block the create.** The assignment is optional; a
 *      dialog that refused to submit because an optional list did not load would let one failing
 *      request cost the task.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const members = [
  { id: 'user-ana', displayName: 'Ana Petrova', email: 'ana@example.com', avatarUrl: null },
  { id: 'user-marco', displayName: 'Marco Silva', email: 'marco@example.com', avatarUrl: null },
]

/** Records every path the dialog reads, so a test can assert which endpoint fed the picker. */
function mockGets(result: unknown, paths: string[] = []) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    paths.push(path)
    return result
  }) as unknown as typeof api.get)
}

function renderDialog(onCreated = vi.fn()) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <TaskCreateDialog
        projectId={PROJECT}
        open
        onClose={() => {}}
        onCreated={onCreated}
      />
    </TestProviders>,
  )
}

async function chooseAssignee(value: string) {
  await userEvent.selectOptions(screen.getByRole('combobox', { name: /assignee/i }), value)
}

describe('TaskCreateDialog — the assignee picker', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it("reads the project's members, not the company directory", async () => {
    const paths: string[] = []
    mockGets(members, paths)

    renderDialog()
    await waitFor(() => expect(paths).toContain(`/projects/${PROJECT}/members`))

    // The distinction the server draws and the picker must not blur: /users is every person in
    // the company, including those who cannot open this project at all.
    expect(paths).not.toContain('/users')
  })

  it('offers each member by display name', async () => {
    mockGets(members)
    renderDialog()

    await userEvent.click(await screen.findByRole('combobox', { name: /assignee/i }))

    expect(await screen.findByText('Ana Petrova')).toBeInTheDocument()
    expect(screen.getByText('Marco Silva')).toBeInTheDocument()
  })

  it('sends the chosen member as assigneeId', async () => {
    mockGets(members)
    const post = vi.spyOn(api, 'post').mockResolvedValue(aWorkItem({ id: 't1', title: 'X' }) as never)

    renderDialog()
    // The list having loaded is observed through the field leaving its disabled loading state.
    await waitFor(() => expect(screen.getByRole('combobox', { name: /assignee/i })).not.toHaveAttribute('aria-disabled', 'true'))

    await userEvent.type(screen.getByTestId('Board.Create.Title'), 'Update the refund cases')
    await chooseAssignee('user-marco')
    await userEvent.click(screen.getByTestId('Board.Create.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toMatchObject({ assigneeId: 'user-marco' })
  })

  it('sends null — not an empty string — when the task is left unassigned', async () => {
    mockGets(members)
    const post = vi.spyOn(api, 'post').mockResolvedValue(aWorkItem({ id: 't1', title: 'X' }) as never)

    renderDialog()
    await userEvent.type(screen.getByTestId('Board.Create.Title'), "Nobody's yet")
    await userEvent.click(screen.getByTestId('Board.Create.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    // '' is a malformed uuid, not an absent one: the server would answer 400 for the option this
    // form starts on, which is the path most tasks take.
    expect(post.mock.calls[0][1]).toMatchObject({ assigneeId: null })
  })

  it('carries an Idempotency-Key, so a retry cannot leave two cards on the board', async () => {
    mockGets(members)
    const post = vi.spyOn(api, 'post').mockResolvedValue(aWorkItem({ id: 't1', title: 'X' }) as never)

    renderDialog()
    await userEvent.type(screen.getByTestId('Board.Create.Title'), 'Only once')
    await userEvent.click(screen.getByTestId('Board.Create.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    // The header was declared and honoured in §54 together with the column behind it (V17). It
    // sat undeclared until then precisely so the SPA could not believe in a deduplication the
    // server was not performing.
    expect(post.mock.calls[0][2]).toMatchObject({ idempotencyKey: expect.any(String) })
  })

  it('still creates the task when the members list fails to load', async () => {
    // The failed members read is the subject: the dialog has to stay usable without it.
    drivingAFailedRead()
    vi.spyOn(api, 'get').mockRejectedValue(new Error('members unavailable'))
    const post = vi.spyOn(api, 'post').mockResolvedValue(aWorkItem({ id: 't1', title: 'X' }) as never)

    renderDialog()
    await userEvent.type(screen.getByTestId('Board.Create.Title'), 'Still addable')
    await userEvent.click(screen.getByTestId('Board.Create.Submit'))

    // An optional field must not be able to stop a create. The dialog says the list failed and
    // stays usable at "Unassigned".
    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toMatchObject({ assigneeId: null })
  })
})
