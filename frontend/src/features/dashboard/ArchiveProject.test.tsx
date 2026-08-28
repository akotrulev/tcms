import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, Project, RepositoryDashboard } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { DashboardPage } from './DashboardPage'
import { ProjectsPage } from '../projects/ProjectsPage'

/**
 * Archiving a project, from the two screens that show the state (§159).
 *
 * `archived` was a **required** field of `Project` from M0 and no file in this app outside a test
 * fixture read it, because nothing anywhere could set it — the writerless column's expensive half,
 * where the consumer draws a conclusion from a value the product never produces. These are the
 * tests that would have failed on the day it started being true, and they cover the three things
 * neither `tsc` nor the server's own suite can see:
 *
 *   1. **The button sends the target state, not a toggle.** The endpoint is idempotent on purpose
 *      so that a stale render is a no-op; a component sending "the opposite of what I last saw"
 *      throws that property away at the one call site that has it.
 *   2. **It is disabled, not hidden, without `project:settings`** — and the picker still marks the
 *      project, because the marker is a fact and the action is a permission.
 *   3. **An archived project is still listed.** The server returns it deliberately; a client-side
 *      filter here would make it unreachable rather than marked, and this page is the only route
 *      back to the button that restores it.
 */

const LIVE: Project = {
  id: 'p1',
  key: 'OLB',
  name: 'Acme Online Banking',
  description: 'Retail and wealth',
  archived: false,
  caseCount: 12,
  myRole: 'Test Lead',
}

const RETIRED: Project = { ...LIVE, id: 'p2', key: 'OLD', name: 'Retired Platform', archived: true }

const DASHBOARD: RepositoryDashboard = {
  generatedAt: '2026-08-01T09:00:00Z',
  lifecycle: [],
  health: [],
  healthComputedAt: null,
  byFeature: [],
  byOwner: [],
  // 005 N3's third segmentation — by the outcome of each case's last execution. Empty
  // here: these files are about the tiles, not the breakdown (B1).
  byLastRun: [],
  automationCoverage: {
    automated: 0,
    toBeAutomated: 0,
    manual: 0,
    notAutomatable: 0,
    coveragePercent: 0,
  },
  recentActivity: [],
}

function mockReads(permissions: PermissionKey[], project: Project = LIVE, projects: Project[] = [LIVE]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path === '/projects') return projects
    if (path.startsWith('/projects/p1/dashboard') || path.includes('/dashboard')) return DASHBOARD
    if (/^\/projects\/[^/]+$/.test(path)) return project
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDashboard() {
  // The route matters: this page reads `projectId` from the path, and every request it makes is
  // built from it. `TestProviders` supplies the only router in the tree.
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route="/projects/p1">
      <Routes>
        <Route path="/projects/:projectId" element={<DashboardPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Archiving a project', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends the target state rather than a toggle', async () => {
    mockReads(['project:read', 'project:settings'])
    const put = vi.spyOn(api, 'put').mockResolvedValue({ ...LIVE, archived: true } as never)
    renderDashboard()

    // Enabled first: the gate disables while the permission answer is in flight, so clicking
    // before it lands asserts the fail-closed arm by accident — §66's vacuous-barrier finding.
    //
    // AND THE ELEMENT IS RE-QUERIED EACH TIME, never held in a variable across the wait. The
    // denied branch of `PermissionGate` wraps the control in a tooltip anchor, so when the verdict
    // arrives the whole subtree is replaced and the node captured before it is detached from the
    // document — disabled for ever, in a test that would time out blaming the permission.
    await waitFor(() => expect(screen.getByTestId('Dashboard.ArchiveButton')).toBeEnabled())
    await userEvent.click(screen.getByTestId('Dashboard.ArchiveButton'))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/projects/p1/archive', { archived: true }),
    )
  })

  it('offers the way back, and only that, once the project is archived', async () => {
    // The docs tree's rule for an archived page, one level up: a state offers the move that means
    // something from it. "Archive" on an archived project is an action that is always available
    // and never does anything.
    mockReads(['project:read', 'project:settings'], RETIRED)
    const put = vi.spyOn(api, 'put').mockResolvedValue({ ...RETIRED, archived: false } as never)
    renderDashboard()

    expect(await screen.findByTestId('Dashboard.ArchiveButton')).toHaveTextContent('Restore project')
    expect(await screen.findByTestId('Dashboard.Archived')).toBeInTheDocument()

    await waitFor(() => expect(screen.getByTestId('Dashboard.ArchiveButton')).toBeEnabled())
    await userEvent.click(screen.getByTestId('Dashboard.ArchiveButton'))
    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/projects/p1/archive', { archived: false }),
    )
  })

  it('disables the button, rather than hiding it, for a caller who may not archive', async () => {
    mockReads(['project:read'])
    renderDashboard()

    await waitFor(() => expect(screen.getByTestId('Dashboard.ArchiveButton')).toBeDisabled())
  })

  it('marks an archived project in the picker and still lists it', async () => {
    mockReads(['project:read'], LIVE, [LIVE, RETIRED])
    render(
      <TestProviders client={createTestQueryClient()} userId="user-1">
        <ProjectsPage />
      </TestProviders>,
    )

    const cards = await screen.findAllByTestId('Projects.Card')
    expect(cards).toHaveLength(2)
    expect(screen.getAllByTestId('Projects.Card.Archived')).toHaveLength(1)
    expect(screen.getByText('Retired Platform')).toBeInTheDocument()
  })
})
