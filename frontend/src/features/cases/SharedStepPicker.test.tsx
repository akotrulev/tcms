import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { SharedStepPicker } from './SharedStepPicker'
import { heldPermissions } from '../../test/permissions'
import type { PermissionKey } from '../../api/types'

/**
 * The step-library picker — the first consumer `useSharedSteps` has ever had.
 *
 * What is locked here, none of it visible to the build or the linter:
 *
 *   1. **Picking hands back the ENTRY, and the caller inserts a reference to it.** The whole
 *      design is that a shared step is a reference: the body lives in the library and is resolved
 *      when the case is read. A picker that handed back the body would produce a step that looks
 *      identical on screen and silently stops tracking the entry it came from — so the assertion
 *      is on what `onPick` receives, not on what the list rendered.
 *   2. **`library:manage` gates writing a new block, and only that.** Reading the library needs no
 *      permission beyond the project grant, so a Viewer must reach the list and be refused only
 *      the create — gating the whole dialog would withhold something they may read.
 *   3. **The create sends the body as an ARRAY with blanks dropped.** Asserted on the payload
 *      (§57's rule): a form that splits wrongly still renders its own input back correctly, and
 *      every render-level assertion passes while the server stores an empty line in a list whose
 *      positions are the structure.
 *   4. **"Nothing matches" and "the library is empty" are different sentences.** Saying "no
 *      results" on a project whose library was never populated sends the reader hunting for a
 *      filter to clear — §47/§49/§63's defect class, copy that is accurate about its query and
 *      false about the thing it labels.
 */

const entries = [
  { id: 'ss-1', title: 'Submit the refund form', body: ['Enter the amount', 'Confirm'], usageCount: 12 },
  { id: 'ss-2', title: 'Verify the audit entry', body: ['Open the audit log'], usageCount: 1 },
  { id: 'ss-3', title: 'Open the settings drawer', body: ['Click the gear'], usageCount: 0 },
]

/** `library` is what the shared-steps endpoint answers; permissions drive the create gate. */
function mockReads(permissions: PermissionKey[], library = entries) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/shared-steps')) return library
    return []
  }) as never)
}

function renderPicker() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <SharedStepPicker projectId="p1" open onClose={onClose} onPick={onPick} />
    </TestProviders>,
  )
}

let onClose: () => void
let onPick: (step: unknown) => void

/**
 * Opens the create form, waiting for the permission verdict first.
 *
 * PermissionGate is fail-closed while `/me/permissions` is in flight, so clicking on the first
 * render hits a disabled button and user-event refuses the pointer interaction — which reads as
 * "the gate is wrong" rather than "the test was early".
 */
async function openCreateForm() {
  // Re-queried inside waitFor rather than captured once. PermissionGate renders the denied branch
  // wrapped in a tooltip anchor and the allowed branch bare, so the button is a DIFFERENT DOM node
  // either side of the verdict — a held reference never becomes enabled and the wait times out
  // against a node that is no longer in the document.
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Write a new block' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: 'Write a new block' }))
}

describe('SharedStepPicker', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    onClose = vi.fn()
    onPick = vi.fn()
    mockReads(['library:manage'])
  })

  it('hands the chosen entry back whole, so the caller can store a reference to it', async () => {
    renderPicker()

    await userEvent.click(await screen.findByText('Submit the refund form'))

    // The entry, not its body. A caller given only the text cannot set sharedStepId, and the
    // step it creates would be an untracked copy that looks the same on screen.
    expect(onPick).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'ss-1', title: 'Submit the refund form' }),
    )
    expect(onClose).toHaveBeenCalled()
  })

  it('shows the usage count, and says "unused" rather than "0 cases"', async () => {
    renderPicker()

    // The ranking signal has to be legible or the order reads as arbitrary. "0 cases" is
    // technically right and reads as a defect; "unused" is the same fact as a description.
    expect(await screen.findByText('12 cases')).toBeInTheDocument()
    expect(screen.getByText('1 case')).toBeInTheDocument()
    expect(screen.getByText('unused')).toBeInTheDocument()
  })

  it('offers the create to a holder of library:manage', async () => {
    renderPicker()

    // Awaited rather than asserted on the first render: PermissionGate is fail-closed while
    // /me/permissions is in flight, so a synchronous assertion here reads the pending state and
    // would pass for a component that never enables the button at all.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Write a new block' })).toBeEnabled(),
    )
  })

  it('disables the create for a Viewer, who can still read the library', async () => {
    mockReads(['case:read'])
    renderPicker()

    // Both halves matter. Disabled-and-listed is disable-don't-hide; disabled-and-empty would
    // mean the dialog withheld a list the caller is allowed to read.
    expect(await screen.findByText('Submit the refund form')).toBeInTheDocument()

    const button = await screen.findByRole('button', { name: 'Write a new block' })
    await waitFor(() => expect(button).toBeDisabled())

    // This assertion is only non-vacuous because of the test above it: PermissionGate is
    // fail-closed while /me/permissions is in flight, so "disabled" alone is also what pending
    // looks like. The pair is what distinguishes them — the same input path enables the button
    // for a holder and leaves it disabled here.
  })

  it('sends the body as an array with blank lines dropped, and inserts what it created', async () => {
    const created = { id: 'ss-new', title: 'Reconcile', body: ['One', 'Two'], usageCount: 0 }
    const post = vi.spyOn(api, 'post').mockResolvedValue(created as never)
    renderPicker()

    await openCreateForm()
    await userEvent.type(screen.getByTestId('SharedStepPicker.Create.Title'), 'Reconcile')
    // A trailing newline is what the textarea holds after the user presses Enter on the last
    // line, so it arrives on ordinary input rather than as a hostile case.
    await userEvent.type(screen.getByTestId('SharedStepPicker.Create.Body'), 'One\n\nTwo\n')
    await userEvent.click(screen.getByRole('button', { name: 'Add and insert' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/projects/p1/shared-steps', {
        title: 'Reconcile',
        body: ['One', 'Two'],
      }),
    )
    // Created AND inserted — a create that leaves the author back at the list has made them do
    // the picking twice for one intent.
    expect(onPick).toHaveBeenCalledWith(created)
  })

  it('will not submit a create with no actions', async () => {
    const post = vi.spyOn(api, 'post')
    renderPicker()

    await openCreateForm()
    await userEvent.type(screen.getByTestId('SharedStepPicker.Create.Title'), 'Title only')

    expect(screen.getByRole('button', { name: 'Add and insert' })).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  })

  it('distinguishes an empty library from a search that matched nothing', async () => {
    mockReads(['library:manage'], [])
    renderPicker()

    expect(await screen.findByText(/step library yet/)).toBeInTheDocument()

    await userEvent.type(screen.getByTestId('SharedStepPicker.Search'), 'zzz')

    // Same empty list, different reason, and the reader can act on exactly one of them.
    await waitFor(() => expect(screen.getByText('Nothing matches that')).toBeInTheDocument())
  })
})
