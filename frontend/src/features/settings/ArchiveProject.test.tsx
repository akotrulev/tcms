import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, Project, ProjectWorkflow } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aBudgetBurn, aProjectWorkflow, projectSettings } from '../../test/fixtures'
import { GeneralSettingsPage } from './GeneralSettingsPage'
import { ProjectsPage } from '../projects/ProjectsPage'

/**
 * Archiving a project, from the two screens that show the state (§159).
 *
 * ~~The button was in the project overview's header (`DashboardPage`).~~ → **moved in 011 T7** to
 * `settings-general.html`'s Danger zone, where the mockups put it and where a destructive action is
 * not sitting beside a percentage somebody opened the page to read. The three properties below are
 * unchanged; only the screen they are asserted from moved, which is why this file moved with it.
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
  color: 'project-1',
}

const RETIRED: Project = { ...LIVE, id: 'p2', key: 'OLD', name: 'Retired Platform', archived: true }

const WORKFLOW: ProjectWorkflow = aProjectWorkflow({ projectId: 'p1' })

function mockReads(permissions: PermissionKey[], project: Project = LIVE, projects: Project[] = [LIVE]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path === '/projects') return projects
    // Anchored so `/projects/p1/workflow` is not caught by the project arm's prefix — the harness
    // rule for a path-keyed mock with a sub-resource under it.
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) return WORKFLOW
    // The settings document, read since 015 T5 put D33's close gate on this screen. Anchored for
    // the same reason as the workflow arm above it.
    if (/^\/projects\/[^/]+\/settings$/.test(path)) return projectSettings({ projectId: 'p1' })
    // 019 T6's Budget row on the Time panel. A project with no budget — the state this file's
    // assertions are all indifferent to, and the one the row reads as *no budget set*.
    if (/^\/projects\/[^/]+\/budget-burn$/.test(path)) return aBudgetBurn()
    if (/^\/projects\/[^/]+$/.test(path)) return project
    /*
      018 T5's ClientPanel reads this. Answered EMPTY, which is the state every test in this file is
      about: with no clients the panel does not render at all, so none of these assertions has to
      account for a control that is not there. The panel's own two arms are asserted in
      `features/clients/ClientAbsence.test.tsx`, both directions.
    */
    if (/\/settings\/client-options$/.test(path)) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderSettings() {
  // The route matters: this page reads `projectId` from the path, and every request it makes is
  // built from it. `TestProviders` supplies the only router in the tree.
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route="/projects/p1/settings/general"
    >
      <Routes>
        <Route path="/projects/:projectId/settings/general" element={<GeneralSettingsPage />} />
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
    renderSettings()

    // Enabled first: the gate disables while the permission answer is in flight, so clicking
    // before it lands asserts the fail-closed arm by accident — §66's vacuous-barrier finding.
    //
    // AND THE ELEMENT IS RE-QUERIED EACH TIME, never held in a variable across the wait. The
    // denied branch of `PermissionGate` wraps the control in a tooltip anchor, so when the verdict
    // arrives the whole subtree is replaced and the node captured before it is detached from the
    // document — disabled for ever, in a test that would time out blaming the permission.
    await waitFor(() => expect(screen.getByTestId('GeneralSettings.Danger.Archive')).toBeEnabled())
    await userEvent.click(screen.getByTestId('GeneralSettings.Danger.Archive'))

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
    renderSettings()

    expect(await screen.findByTestId('GeneralSettings.Danger.Archive')).toHaveTextContent('Restore project')
    expect(await screen.findByTestId('GeneralSettings.Danger.Archived')).toBeInTheDocument()

    await waitFor(() => expect(screen.getByTestId('GeneralSettings.Danger.Archive')).toBeEnabled())
    await userEvent.click(screen.getByTestId('GeneralSettings.Danger.Archive'))
    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/projects/p1/archive', { archived: false }),
    )
  })

  it('disables the button, rather than hiding it, for a caller who may not archive', async () => {
    mockReads(['project:read'])
    renderSettings()

    await waitFor(() => expect(screen.getByTestId('GeneralSettings.Danger.Archive')).toBeDisabled())
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
