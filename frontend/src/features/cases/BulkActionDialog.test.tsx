import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { BulkActionDialog } from './BulkActionDialog'
import { lifecycleLabels } from '../../lib/labels'
import { heldPermissions } from '../../test/permissions'
import type { PermissionKey } from '../../api/types'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * Bulk edits — preview-then-commit, and the one option in the dialog that is a permission gate.
 *
 * Five things are locked here, and every one of them is invisible to the build and the linter:
 *
 *   1. **"Approved" is disabled for a caller without `case:approve`, and enabled with it.** This is
 *      what the whole slice turns on. `case:edit` reaches Test Engineer while `case:approve` stops
 *      at Test Lead, so the server's permission depends on the *value* of a select rather than on
 *      the action — and §31's disable-don't-hide therefore applies to one option, not to the
 *      dialog. Ungated, an engineer gets a form that submits and comes back 403.
 *   2. **Commit is unreachable without a preview.** The point of preview-then-commit is lost if the
 *      Apply button works from a fresh dialog, and "it's disabled" is a claim only a test makes.
 *   3. **The preview call sends `dryRun: true` and the commit sends `false`.** Asserted on the
 *      *payload*, not on what came back: a dialog that previews correctly and then commits with
 *      `dryRun` still true reports success and changes nothing, and every render-level assertion
 *      passes.
 *   4. **Changing the argument discards the preview.** Otherwise Apply commits an operation the
 *      preview never described — the user approves what they read and something else happens.
 *   5. **A 400 lands on the control it names**, not in a banner, because the field is one the user
 *      can see and fix.
 */

const previewResult = {
  dryRun: true,
  total: 3,
  changed: ['TC-1', 'TC-2'],
  unchanged: ['TC-3'],
  skipped: [],
  declinesEnded: [],
  auditEventId: null,
}

const committedResult = { ...previewResult, dryRun: false, auditEventId: 'audit-1' }

/** The dialog reads `case:approve`; the lists it fetches are mocked empty. */
function mockReads(permissions: PermissionKey[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/tags')) return [{ tag: 'smoke', caseCount: 9 }, { tag: 'regression', caseCount: 4 }]
    // Conditions, suites and members — all pickers this test does not drive.
    return []
  }) as never)
}

function renderDialog() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <BulkActionDialog
        open
        onClose={onClose}
        projectId="p1"
        selectedIds={['c1', 'c2', 'c3']}
        onCommitted={onCommitted}
      />
    </TestProviders>,
  )
}

let onClose: () => void
let onCommitted: () => void

/** MUI renders a select's options into a portal, so the listbox is found on the document. */
async function openSelect(testId: string) {
  const select = screen.getByTestId(testId).closest('.MuiInputBase-root')!
  await userEvent.click(within(select as HTMLElement).getByRole('combobox'))
}

describe('BulkActionDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    onClose = vi.fn()
    onCommitted = vi.fn()
    mockReads(['case:edit'])
  })

  describe('the approve gate', () => {
    it('disables "Approved" for a caller holding case:edit but not case:approve', async () => {
      // THE test of this file. A Test Engineer holds case:edit and every other status is theirs to
      // set; approval is the one the server refuses, so it is the one option that greys out.
      //
      // Asserted through `role="option"`, NOT through the testid, and that is the whole lesson of
      // this file's first live pass. MUI's Select clones its DIRECT children to inject the option
      // role and the handler that selects the value, so a MenuItem wrapped in anything renders as
      // an inert `role="menuitem"` that cannot be chosen at all. Looked up by testid, that broken
      // item is findable and carries no `aria-disabled` — so the disabled assertion below passed
      // and the enabled one below it passed too, on an option nobody could select.
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Field.LifecycleStatus')

      const approved = await screen.findByRole('option', { name: /^Approved/ })
      expect(approved).toHaveAttribute('aria-disabled', 'true')
    })

    it('offers "Approved" as a SELECTABLE option to a caller holding case:approve', async () => {
      // The other direction, and it is not ceremony twice over: a gate that disables the option for
      // everybody passes the test above, and an option MUI never processed is unselectable for
      // everybody while looking present. So this asserts the value actually lands in the payload.
      mockReads(['case:edit', 'case:approve'])
      const post = vi.spyOn(api, 'post').mockResolvedValue(previewResult as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Field.LifecycleStatus')
      const approved = await screen.findByRole('option', { name: /^Approved/ })
      expect(approved).not.toHaveAttribute('aria-disabled', 'true')

      await userEvent.click(approved)
      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({
        payload: { lifecycleStatus: 'APPROVED' },
      })
    })

    it('renders all four statuses as options — none is lost to a wrapper', async () => {
      // The structural check, derived from the label catalogue rather than a hand-written list, so
      // a fifth lifecycle status is covered without anyone remembering this test exists.
      mockReads(['case:edit', 'case:approve'])
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Field.LifecycleStatus')

      await waitFor(() =>
        expect(screen.getAllByRole('option')).toHaveLength(
          Object.keys(lifecycleLabels).length,
        ),
      )
    })

    it('leaves the other three statuses enabled for an engineer', async () => {
      // Gating the whole select would be the over-correction, and it would stop an engineer doing
      // the thing §6g explicitly gives them: submitting for review.
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Field.LifecycleStatus')

      for (const name of [/^Draft$/, /^In review$/, /^Deprecated$/]) {
        expect(await screen.findByRole('option', { name })).not.toHaveAttribute(
          'aria-disabled',
          'true',
        )
      }
    })
  })

  describe('preview, then commit', () => {
    it('cannot commit until a preview has been seen', async () => {
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      expect(screen.getByTestId('Cases.BulkDialog.Confirm')).toBeDisabled()
    })

    it('previews with dryRun true and commits with dryRun false', async () => {
      // Asserted on the REQUEST. A dialog that commits with dryRun still true gets a 200 back, shows
      // a success toast and changes nothing — and every render-level assertion in this file passes.
      const post = vi
        .spyOn(api, 'post')
        .mockResolvedValueOnce(previewResult as never)
        .mockResolvedValueOnce(committedResult as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))
      await screen.findByTestId('Cases.BulkDialog.Preview.Result')

      expect(post.mock.calls[0][1]).toMatchObject({
        action: 'SET_STATUS',
        caseIds: ['c1', 'c2', 'c3'],
        dryRun: true,
        payload: { lifecycleStatus: 'IN_REVIEW' },
      })

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Confirm'))

      await waitFor(() => expect(onCommitted).toHaveBeenCalled())
      expect(post.mock.calls[1][1]).toMatchObject({ dryRun: false })
    })

    it('names the skipped cases and their reasons rather than counting them', async () => {
      // "2 skipped" alone leaves the user with no way to find out which two or what to do.
      vi.spyOn(api, 'post').mockResolvedValue({
        ...previewResult,
        changed: ['TC-1'],
        unchanged: [],
        skipped: [{ key: 'TC-9', reason: 'A draft must be reviewed before it can be approved.' }],
      } as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      expect(await screen.findByText('TC-9')).toBeInTheDocument()
      expect(
        screen.getByText(/A draft must be reviewed before it can be approved/),
      ).toBeInTheDocument()
    })

    it('names the cases a move takes a condition exception from, BEFORE it happens', async () => {
      // §193. The server deletes those declines as part of the move and no other screen ever
      // mentions it, so the preview is the only moment the person can decide not to. Named rather
      // than counted, on the skip list's argument exactly.
      vi.spyOn(api, 'post').mockResolvedValue({
        ...previewResult,
        changed: ['TC-1', 'TC-6'],
        unchanged: [],
        declinesEnded: ['TC-6'],
      } as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      expect(await screen.findByText('Losing a condition exception')).toBeInTheDocument()
      expect(screen.getByText(/moving them back will not bring it back/)).toBeInTheDocument()
      // The warning is on the same footing as a skip: this operation does something the caller
      // did not ask for, so the panel must not read as an ordinary informational preview.
      expect(screen.getByRole('alert')).toHaveClass('MuiAlert-colorWarning')
    })

    it('discards the preview when the argument changes', async () => {
      // Otherwise Apply commits an operation the preview never described.
      vi.spyOn(api, 'post').mockResolvedValue(previewResult as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))
      await screen.findByTestId('Cases.BulkDialog.Preview.Result')
      expect(screen.getByTestId('Cases.BulkDialog.Confirm')).toBeEnabled()

      await openSelect('Cases.BulkDialog.Field.LifecycleStatus')
      await userEvent.click(await screen.findByRole('option', { name: /^Deprecated$/ }))

      await waitFor(() => {
        expect(screen.queryByTestId('Cases.BulkDialog.Preview.Result')).not.toBeInTheDocument()
      })
      expect(screen.getByTestId('Cases.BulkDialog.Confirm')).toBeDisabled()
    })
  })

  describe('the eight actions', () => {
    it('SUBMIT_FOR_REVIEW sends no status — the verb carries it', async () => {
      // A contradicting lifecycleStatus is refused by the server, so sending the leftover value of
      // a control that is no longer displayed would be a 400 on a field the user cannot see.
      const post = vi.spyOn(api, 'post').mockResolvedValue(previewResult as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Operation')
      await userEvent.click(await screen.findByRole('option', { name: 'Submit for review' }))
      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({ action: 'SUBMIT_FOR_REVIEW', payload: {} })
    })

    it('SET_OWNER sends null for "No owner" rather than omitting the field', async () => {
      // null is the unassign and an absent member is "no argument". The two mean different things
      // to the server, and `''` would be neither.
      const post = vi.spyOn(api, 'post').mockResolvedValue(previewResult as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Operation')
      await userEvent.click(await screen.findByRole('option', { name: 'Assign an owner' }))
      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({ action: 'SET_OWNER', payload: { ownerId: null } })
    })

    it('ADD_TAGS suggests the tags the project already uses', async () => {
      /*
        The bulk tag is the one most worth suggesting: it lands on every selected case at once, so
        a tag typed slightly differently from the one already in use splits the vocabulary across
        the whole selection, and the repair is a second bulk action nobody knows to run.
      */
      const post = vi.spyOn(api, 'post').mockResolvedValue(previewResult as never)
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Operation')
      await userEvent.click(await screen.findByRole('option', { name: 'Add tags' }))

      await userEvent.type(screen.getByTestId('Cases.BulkDialog.Field.Tags'), 'smo')
      await userEvent.click(await screen.findByRole('option', { name: 'smoke' }))
      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({ action: 'ADD_TAGS', payload: { tags: ['smoke'] } })
    })

    it('asks for a reason only when the automation status is NOT_AUTOMATABLE', async () => {
      // V1's check constraint. The field appears exactly when it applies rather than being always
      // present and conditionally validated.
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await openSelect('Cases.BulkDialog.Operation')
      await userEvent.click(await screen.findByRole('option', { name: 'Change automation status' }))

      expect(
        screen.queryByTestId('Cases.BulkDialog.Field.NotAutomatableReason'),
      ).not.toBeInTheDocument()

      await openSelect('Cases.BulkDialog.Field.AutomationStatus')
      await userEvent.click(await screen.findByRole('option', { name: /not automatable/i }))

      expect(
        await screen.findByTestId('Cases.BulkDialog.Field.NotAutomatableReason'),
      ).toBeInTheDocument()
      // And it blocks the round trip until it is filled, rather than sending a request the
      // database would refuse.
      expect(screen.getByTestId('Cases.BulkDialog.Preview')).toBeDisabled()
    })
  })

  describe('refusals', () => {
    it('lands a 400 on the control it names', async () => {
      // The refused write is the subject: this asserts which control the 400 lands on.
      drivingAFailedWrite()
      vi.spyOn(api, 'post').mockRejectedValue(
        new ApiError(400, 'Check the highlighted fields.', {
          lifecycleStatus: ['Choose a status.'],
        }),
      )
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      expect(await screen.findByText('Choose a status.')).toBeInTheDocument()
      expect(onClose).not.toHaveBeenCalled()
    })

    it('shows a bodiless 403 in the dialog alert rather than swallowing it', async () => {
      // The refused write is the subject: a 403 with no body still has to be shown.
      drivingAFailedWrite()
      // A refusal naming no field must not vanish — a rejected operation showing nothing at all is
      // worse than a vague message.
      vi.spyOn(api, 'post').mockRejectedValue(
        new ApiError(403, 'Only a test lead can approve cases. Submit them for review instead.'),
      )
      renderDialog()
      await waitFor(() => expect(api.get).toHaveBeenCalled())

      await userEvent.click(screen.getByTestId('Cases.BulkDialog.Preview'))

      expect(await screen.findByTestId('Cases.BulkDialog.Error')).toBeInTheDocument()
    })
  })
})
