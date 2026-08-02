import { Button, MenuItem, MenuList } from '@mui/material'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { EffectivePermissions, PermissionKey } from '../api/types'
import { TestProviders } from '../test/TestProviders'
import { createTestQueryClient } from '../test/queryClient'
import { PermissionGate } from './PermissionGate'

/**
 * The one component that turns a denied permission into a disabled control with a reason.
 *
 * Both defects §31's browser pass found are locked here, because both were invisible to the
 * build and the linter:
 *
 *   1. **A `Tooltip` wrapped directly around a disabled MUI control never fires** — MUI sets
 *      `pointer-events: none`, so no hover event reaches it and the control greys out and
 *      explains nothing, which is the *worse* half of the original defect.
 *   2. **`wrapper="block"` inside a `Menu`.** `MenuList` is a plain `<ul>`; an `inline-flex`
 *      anchor in it makes the items flow along one line. The suite tree's kebab menu rendered
 *      its three items side by side, and both gates were green while it was unreadable.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

/**
 * A `/me/permissions` body. Keys are `ALL` unless named in `qualified` — the same "list only the
 * exceptions" rule the seeded catalogue follows, so a test that does not care about qualifiers
 * reads exactly as it did before §65 put them on the wire.
 */
const answer = (
  permissions: PermissionKey[],
  qualified: Partial<Record<PermissionKey, 'OWN' | 'PLAN_SCOPE'>> = {},
): EffectivePermissions => ({
  projectId: PROJECT,
  permissions: permissions.map((key) => ({ key, qualifier: qualified[key] ?? 'ALL' })),
})

function renderGated(ui: ReactNode) {
  return render(<TestProviders client={createTestQueryClient()}>{ui}</TestProviders>)
}

describe('PermissionGate', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the control enabled and unwrapped when the caller holds the permission', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(answer(['case:create']))

    renderGated(
      <PermissionGate projectId={PROJECT} permission="case:create">
        {({ allowed }) => (
          <Button disabled={!allowed} data-testid="NewCase">
            New case
          </Button>
        )}
      </PermissionGate>,
    )

    await waitFor(() => expect(screen.getByRole('button', { name: 'New case' })).toBeEnabled())
  })

  it('disables the control and explains why when the caller does not', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))

    renderGated(
      <PermissionGate projectId={PROJECT} permission="case:create">
        {({ allowed }) => (
          <Button disabled={!allowed} data-testid="NewCase">
            New case
          </Button>
        )}
      </PermissionGate>,
    )

    const button = await screen.findByRole('button', { name: 'New case' })
    await waitFor(() => expect(button).toBeDisabled())
    // The gated control keeps its testid — a disabled button is still the button under test.
    expect(button).toHaveAttribute('data-testid', 'NewCase')
  })

  /**
   * Defect 1. The assertion is on the *hover reaching the tooltip*, not on the wrapper's
   * existence — remove the span and MUI's `pointer-events: none` swallows the event, so this
   * test goes red for the reason the user would have noticed.
   */
  it('shows the reason on hover, which a Tooltip wrapped directly on a disabled control cannot', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))

    renderGated(
      <PermissionGate projectId={PROJECT} permission="case:delete">
        {({ allowed }) => (
          <Button disabled={!allowed} data-testid="Delete">
            Delete
          </Button>
        )}
      </PermissionGate>,
    )

    const button = await screen.findByRole('button', { name: 'Delete' })
    await waitFor(() => expect(button).toBeDisabled())

    await user.hover(button.parentElement as HTMLElement)

    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      "You don't have permission to delete test cases in this project.",
    )
  })

  it('does not wrap or explain an allowed control, so an enabled button carries no tooltip', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'get').mockResolvedValue(answer(['case:delete']))

    renderGated(
      <PermissionGate projectId={PROJECT} permission="case:delete">
        {({ allowed }) => (
          <Button disabled={!allowed} data-testid="Delete">
            Delete
          </Button>
        )}
      </PermissionGate>,
    )

    // Re-queried inside `waitFor` rather than captured once: the denied branch nests the control
    // in a Tooltip anchor and the allowed branch does not, so the transition REMOUNTS the button
    // and a reference taken while pending is detached by the time it is enabled.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeEnabled())

    await user.hover(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  /**
   * Defect 2. Asserted as CSS `display` on the anchor rather than as a screenshot: the readable
   * symptom was three menu items on one line, and the mechanical cause is an inline anchor
   * inside a `<ul>`.
   */
  describe('the wrapper the denied branch needs', () => {
    const denied = (wrapper: 'inline' | 'block') => (
      <MenuList>
        <PermissionGate projectId={PROJECT} permission="suite:delete" wrapper={wrapper}>
          {({ allowed }) => (
            <MenuItem disabled={!allowed} data-testid="Delete">
              Delete
            </MenuItem>
          )}
        </PermissionGate>
      </MenuList>
    )

    it('lays out as a block inside a menu, so the items stack instead of flowing inline', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))
      renderGated(denied('block'))

      const item = await screen.findByTestId('Delete')
      const anchor = item.parentElement as HTMLElement
      await waitFor(() => expect(item).toHaveAttribute('aria-disabled', 'true'))

      expect(getComputedStyle(anchor).display).toBe('block')
    })

    it('lays out inline by default, for a flex row of buttons', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))
      renderGated(denied('inline'))

      const anchor = (await screen.findByTestId('Delete')).parentElement as HTMLElement
      expect(getComputedStyle(anchor).display).toBe('inline-flex')
    })

    it('marks the wrapper presentational, so it does not break menu/menuitem for a screen reader', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))
      renderGated(denied('block'))

      const anchor = (await screen.findByTestId('Delete')).parentElement as HTMLElement
      expect(anchor).toHaveAttribute('role', 'none')
    })
  })

  describe('the three-state policy reaches the rendered control', () => {
    it('renders disabled while the answer is in flight, never enabled-then-disabled', () => {
      // The window in which a click would land before the answer does.
      vi.spyOn(api, 'get').mockReturnValue(new Promise(() => {}))

      renderGated(
        <PermissionGate projectId={PROJECT} permission="case:create">
          {({ allowed }) => (
            <Button disabled={!allowed} data-testid="NewCase">
              New case
            </Button>
          )}
        </PermissionGate>,
      )

      expect(screen.getByRole('button', { name: 'New case' })).toBeDisabled()
    })

    it('renders enabled when the permission call failed — the fall-open path, on screen', async () => {
      vi.spyOn(api, 'get').mockRejectedValue(new TypeError('Failed to fetch'))

      renderGated(
        <PermissionGate projectId={PROJECT} permission="case:create">
          {({ allowed }) => (
            <Button disabled={!allowed} data-testid="NewCase">
              New case
            </Button>
          )}
        </PermissionGate>,
      )

      await waitFor(() => expect(screen.getByRole('button', { name: 'New case' })).toBeEnabled())
    })
  })

  it('lets the child decide what disabled means for it, rather than cloning a prop on', async () => {
    // A render prop, not `cloneElement`: an `<a>` takes neither `disabled` nor `aria-disabled`
    // meaningfully, and cloning a prop onto whatever arrives is how a disabled-LOOKING anchor
    // ships — one that still navigates on Enter and on middle-click (§31).
    vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))

    renderGated(
      <PermissionGate projectId={PROJECT} permission="case:edit">
        {({ allowed }) =>
          allowed ? (
            <a href="/projects/x/cases/1/edit" data-testid="EditCase">
              Edit case
            </a>
          ) : (
            <button type="button" disabled data-testid="EditCase">
              Edit case
            </button>
          )
        }
      </PermissionGate>,
    )

    const control = await screen.findByTestId('EditCase')
    await waitFor(() => expect(control.tagName).toBe('BUTTON'))
    expect(control).not.toHaveAttribute('href')
    expect(control).toBeDisabled()
  })

  /**
   * `authorId` — the gate asking about the ROW, not only about the caller.
   *
   * Asserted through the rendered button rather than through `useCan`, because §62's lesson is
   * exactly that a component test can pass for the wrong reason: an element the framework never
   * processed is neither enabled nor disabled, and "not disabled" read identically to "not there".
   */
  describe('authorId — case:delete OWN', () => {
    const CALLER = '99999999-9999-4999-8999-999999999999'
    const COLLEAGUE = '88888888-8888-4888-8888-888888888888'

    const renderDelete = (authorId: string | null) =>
      render(
        <TestProviders client={createTestQueryClient()} userId={CALLER}>
          <PermissionGate projectId={PROJECT} permission="case:delete" authorId={authorId}>
            {({ allowed }) => (
              <Button disabled={!allowed} data-testid="Delete">
                Delete
              </Button>
            )}
          </PermissionGate>
        </TestProviders>,
      )

    it('enables Delete on the callers own case and disables it on a colleagues', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:delete'], { 'case:delete': 'OWN' }))

      const mine = renderDelete(CALLER)
      await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeEnabled())
      mine.unmount()

      renderDelete(COLLEAGUE)
      await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled())
    })

    it('explains it with the restriction, and the tooltip is reachable', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:delete'], { 'case:delete': 'OWN' }))
      renderDelete(COLLEAGUE)
      await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled())

      // Hovering the WRAPPER, not the button: MUI puts `pointer-events: none` on a disabled
      // control, which is the defect this component exists to prevent and would leave the
      // greyed-out button explaining nothing.
      await userEvent.hover(screen.getByTestId('Delete').parentElement as HTMLElement)
      expect(await screen.findByRole('tooltip')).toHaveTextContent(/you created/)
    })

    it('disables Delete on an UNAUTHORED case rather than treating null as a match', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:delete'], { 'case:delete': 'OWN' }))
      renderDelete(null)

      await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled())
    })

    it('leaves an unqualified grant enabled on every row, author or not', async () => {
      // The guard that keeps the three tests above from passing against a gate that disables
      // Delete for everybody — which is what a wrong `authorId` comparison would produce, and
      // which would look like a working feature to anyone who only tested the Test Engineer.
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:delete']))
      renderDelete(COLLEAGUE)

      await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeEnabled())
    })
  })
})
