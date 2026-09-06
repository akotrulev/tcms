import { render, screen } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Client, ClientRef, PermissionKey } from '../../api/types'
import { GeneralSettingsPage } from '../settings/GeneralSettingsPage'
import { ProjectCreateDialog } from '../projects/ProjectCreateDialog'
import { ProjectsPage } from '../projects/ProjectsPage'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { aBudgetBurn, aClient, aProject, aProjectWorkflow, projectSettings } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'

/**
 * **The module's acceptance criterion, and it is an absence.**
 *
 * Scope D1 rule 3: *"The client surfaces reveal themselves. The Clients screen is always reachable,
 * so the first one can be created; the client column, filter and rollups appear only once at least
 * one client exists. A company managing its own products never sees a column that is empty by
 * definition."*
 *
 * `solo-overview.html` states the same thing as a screen: it is `project-overview.html` for a
 * company with no clients, and the note on it reads *"There is no Client chip in the header, no
 * client column anywhere, and no empty 'attach a client' prompt."*
 *
 * <h2>Why this is one file across four features</h2>
 *
 * The criterion is about the **product**, not about any one screen: a build where five surfaces
 * hide the control and the sixth renders an empty select has failed it, and a test per feature
 * folder is exactly how the sixth goes unnoticed. Every one of these is a conditional that could be
 * written backwards and still typecheck, lint clean and render — so each is asserted in **both
 * directions**, because a conditional proven only in its false arm is a conditional proven off.
 *
 * The owner's own worked example is the empty case, not the populated one: *"Given that I'm
 * developing my own products, there is no client to bill as the client is me."* So the absence is
 * the common path and the presence is the variant, which is the order they are asserted in.
 *
 * <h2>The fourth surface is asserted next door, and deliberately</h2>
 *
 * `solo-overview.html` versus `project-overview.html` is the named criterion, and both arms of it
 * live in `ProjectOverviewPage.test.tsx` rather than here: that screen makes thirteen reads behind
 * a mock its own file already owns, and a second copy of it in this file would be thirteen more
 * chances for these five assertions to fail for a reason that has nothing to do with clients.
 * Search that file for `ProjectOverview.Client`.
 */

const ACME: Client = aClient({ id: 'cl-acme', name: 'Acme Retail' })
const ACME_REF: ClientRef = { id: 'cl-acme', name: 'Acme Retail' }

const PROJECT_ID = 'p1'

function mockReads({
  clients,
  projectClient = null,
  options,
  permissions = ['client:read', 'project:create', 'project:settings'] as PermissionKey[],
}: {
  clients: Client[]
  projectClient?: ClientRef | null
  options?: ClientRef[]
  permissions?: PermissionKey[]
}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/permissions')) return heldPermissions(permissions, {}, null)
    if (/^\/clients(\?|$)/.test(path)) return clients
    if (/\/settings\/client-options$/.test(path)) return options ?? clients.map((c) => ({ id: c.id, name: c.name }))
    if (/\/projects\/[^/]+\/settings$/.test(path)) return projectSettings({ projectId: PROJECT_ID })
    // 019 M8's Budget row on General settings. A project with no budget, which is what every
    // assertion in this file is about — the catch-all below would serve `{}`, and the contract
    // double correctly refuses a body missing four required fields.
    if (/\/projects\/[^/]+\/budget-burn$/.test(path)) return aBudgetBurn()
    if (/\/projects\/[^/]+\/workflow$/.test(path)) return aProjectWorkflow()
    if (/^\/projects\/[^/]+$/.test(path)) {
      return aProject({ id: PROJECT_ID, key: 'ACME', name: 'Storefront', client: projectClient })
    }
    if (path === '/projects') {
      return [aProject({ id: PROJECT_ID, key: 'ACME', name: 'Storefront', client: projectClient })]
    }
    return {}
  }) as never)
}

const withProviders = (node: React.ReactNode, route: string) =>
  render(
    <TestProviders client={createTestQueryClient()} userId="u1" route={route}>
      {node}
    </TestProviders>,
  )

const projectRoute = (node: React.ReactNode) => (
  <Routes>
    <Route path="/projects/:projectId/*" element={node} />
  </Routes>
)

describe('a company with NO clients', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows no Client filter and no client on any project card', async () => {
    mockReads({ clients: [] })
    withProviders(<ProjectsPage />, '/projects')

    expect(await screen.findByText('Storefront')).toBeInTheDocument()
    expect(screen.queryByTestId('Projects.ClientFilter')).toBeNull()
    expect(screen.queryByTestId('Projects.Card.Client')).toBeNull()
  })

  it('shows no Client field when a project is created', async () => {
    mockReads({ clients: [] })
    withProviders(<ProjectCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />, '/projects')

    expect(await screen.findByTestId('Projects.Create.Name')).toBeInTheDocument()
    expect(screen.queryByTestId('Projects.Create.Client')).toBeNull()
  })

  it('shows no Client panel in a project’s General settings', async () => {
    mockReads({ clients: [], options: [] })
    withProviders(projectRoute(<GeneralSettingsPage />), `/projects/${PROJECT_ID}/settings/general`)

    expect(await screen.findByTestId('GeneralSettings.Identity.Name')).toBeInTheDocument()
    expect(screen.queryByTestId('GeneralSettings.Client.Panel')).toBeNull()
    expect(screen.queryByTestId('GeneralSettings.Client.Select')).toBeNull()
  })

  it('marks no project as missing a client, anywhere', async () => {
    mockReads({ clients: [] })
    withProviders(<ProjectsPage />, '/projects')

    await screen.findByText('Storefront')
    // The three shapes an "attach a client" nag could take. None of them may exist: a project
    // without a client is not incomplete, and there is no synthetic "Internal" row to name.
    expect(screen.queryByText(/attach a client/i)).toBeNull()
    expect(screen.queryByText(/no client/i)).toBeNull()
    expect(screen.queryByText(/None \(internal\)/i)).toBeNull()
  })
})

describe('a company with ONE client', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the Client filter and the client on the card it belongs to', async () => {
    mockReads({ clients: [ACME], projectClient: ACME_REF })
    withProviders(<ProjectsPage />, '/projects')

    expect(await screen.findByTestId('Projects.ClientFilter')).toBeInTheDocument()
    expect(screen.getByTestId('Projects.Card.Client')).toHaveTextContent('Acme Retail')
  })

  it('offers the Client field when a project is created', async () => {
    mockReads({ clients: [ACME] })
    withProviders(<ProjectCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />, '/projects')

    const picker = await screen.findByTestId('Projects.Create.Client')
    expect(picker).toBeInTheDocument()
    // "None (internal)" is offered HERE, where a select must say something in every state — and
    // nowhere a project is merely displayed. That is the difference between a value and a nag.
    expect(picker).toHaveTextContent(/None \(internal\)/)
    expect(picker).toHaveTextContent('Acme Retail')
  })

  it('offers the Client panel in General settings, on project:settings', async () => {
    mockReads({ clients: [ACME], projectClient: ACME_REF })
    withProviders(projectRoute(<GeneralSettingsPage />), `/projects/${PROJECT_ID}/settings/general`)

    expect(await screen.findByTestId('GeneralSettings.Client.Select')).toHaveValue('cl-acme')
  })

})
