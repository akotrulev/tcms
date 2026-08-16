import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aPlan, aProject } from '../../test/fixtures'
import { PlanListPage } from './PlanListPage'

/**
 * Archiving a plan from the list — **D2** (audit
 * `docs/audits/2026-08-15-flow-review-and-improvements.md`, `V65`).
 *
 * A plan that runs were executed against can never be deleted — `PlanDeleteService` 409s, and it is
 * right to — and `V32` removed `CLOSED`, so such a plan had no way at all to leave the working
 * list. This is that way out, and these lock the four properties that make it the *visibility* act
 * it is meant to be rather than a fourth status:
 *
 *   1. **The archive is off by default and the toggle is in the URL**, so a lead who found last
 *      spring's plan can send somebody the link that shows it.
 *   2. **The request says `archived=true`** — a widening. A client that instead filtered the
 *      working list locally would show an empty archive for ever, since the server never sent one.
 *   3. **An archived row is marked**, beside the name and *not* in the status column: putting it
 *      there would read as a fourth status, the conflation the column exists to avoid.
 *   4. **The control is gated on `plan:edit`, disabled rather than hidden**, and it is not
 *      `plan:delete` — charging the delete key would put a reversible tidying act behind the
 *      product's most destructive plan permission.
 */

const PROJECT = 'p1'

const WORKING = aPlan({ id: 'tp1', key: 'TP-1', keySeq: 1, name: 'Release 4.4' })
const ARCHIVED = aPlan({
  id: 'tp2',
  key: 'TP-2',
  keySeq: 2,
  name: 'Release 4.3',
  status: 'APPROVED',
  archived: true,
})

function mockReads(permissions: PermissionKey[] = ['project:read', 'plan:edit', 'plan:create']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    // The server's own split, mirrored: the default list is the working one, and `archived=true`
    // WIDENS it. A double that returned both from one arm would hide the defect this test exists
    // for — a client that never sends the parameter looks identical.
    if (path.includes('/plans')) {
      return path.includes('archived=true') ? [WORKING, ARCHIVED] : [WORKING]
    }
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

function renderList(route = `/projects/${PROJECT}/plans`) {
  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <Routes>
        <Route path="/projects/:projectId/plans" element={<PlanListPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('archiving a plan (D2)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the working list by default, and the archived plan is not in it', async () => {
    mockReads()
    renderList()

    expect(await screen.findByText('Release 4.4')).toBeInTheDocument()
    expect(screen.queryByText('Release 4.3')).not.toBeInTheDocument()
  })

  it('the toggle asks the SERVER for the archive, and marks the row it gets back', async () => {
    const user = userEvent.setup()
    mockReads()
    renderList()

    await screen.findByText('Release 4.4')
    await user.click(screen.getByTestId('Plans.ShowArchived'))

    expect(await screen.findByText('Release 4.3')).toBeInTheDocument()
    const paths = vi.mocked(api.get).mock.calls.map(([path]) => String(path))
    expect(paths.some((path) => path.includes('/plans?archived=true'))).toBe(true)

    // The mark is beside the name, and the status is untouched by archiving.
    expect(screen.getByTestId('Plans.Row.Archived')).toBeInTheDocument()
    const statuses = screen.getAllByTestId('Plans.Row.Status').map((chip) => chip.textContent)
    expect(statuses).not.toContain('Archived')
  })

  it('the toggle is in the URL, so the view can be linked', async () => {
    // A component-state toggle would send a colleague a list the plan they were told about is
    // not in.
    mockReads()
    renderList(`/projects/${PROJECT}/plans?archived=true`)

    expect(await screen.findByText('Release 4.3')).toBeInTheDocument()
  })

  it('archiving sends the flag and refreshes the list', async () => {
    const user = userEvent.setup()
    mockReads()
    const put = vi
      .spyOn(api, 'put')
      .mockResolvedValue({ ...WORKING, archived: true } as never)
    renderList()

    await screen.findByText('Release 4.4')
    await waitFor(() => expect(screen.getByTestId('Plans.Row.ArchiveToggle')).toBeEnabled())
    await user.click(screen.getByTestId('Plans.Row.ArchiveToggle'))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(expect.stringContaining(`/plans/${WORKING.id}/archive`), {
        archived: true,
      }),
    )
  })

  it('an archived row offers to RESTORE it, sending false', async () => {
    const user = userEvent.setup()
    mockReads()
    const put = vi.spyOn(api, 'put').mockResolvedValue({ ...ARCHIVED, archived: false } as never)
    renderList(`/projects/${PROJECT}/plans?archived=true`)

    const restore = await screen.findByRole('button', { name: /restore TP-2/i })
    await waitFor(() => expect(restore).toBeEnabled())
    await user.click(restore)

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(expect.stringContaining(`/plans/${ARCHIVED.id}/archive`), {
        archived: false,
      }),
    )
  })

  it('DISABLES the control without plan:edit rather than hiding it (§31)', async () => {
    // And it is `plan:edit`, not `plan:delete`: this caller holds the delete key and still may not
    // archive, which is the assertion a copy-pasted gate fails.
    mockReads(['project:read', 'plan:delete'])
    renderList()

    await screen.findByText('Release 4.4')
    await waitFor(() => expect(screen.getByTestId('Plans.Row.ArchiveToggle')).toBeDisabled())
  })
})
