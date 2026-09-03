import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import { aProject, aProjectRepository } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { RepositoriesSettingsPage } from './RepositoriesSettingsPage'

/**
 * `settings-repositories.html`.
 *
 * Three properties, and the first two are the ones a naive port gets wrong. **`primary` is the
 * server's**, computed from `position` — so the badge is asserted from the payload rather than
 * from the row index, which is what would silently disagree the day a reorder lands. **The empty
 * state says the project is not degraded**, because a blank list on a settings page otherwise
 * reads as something half-configured. And the writes are gated on `project:settings` while the
 * list is not, which is §31 read-only rather than a refusal on arrival.
 */

const PROJECT_ID = 'p1'

function mockReads(
  rows: unknown[],
  permissions: PermissionKey[] = ['project:read', 'project:settings'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (/^\/projects\/[^/]+\/repositories$/.test(path)) return rows
    if (/^\/projects\/[^/]+$/.test(path)) {
      return aProject({ id: PROJECT_ID, key: 'ACME', name: 'Acme Storefront' })
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route={`/projects/${PROJECT_ID}/settings/repositories`}
    >
      <Routes>
        <Route
          path="/projects/:projectId/settings/repositories"
          element={<RepositoriesSettingsPage />}
        />
      </Routes>
    </TestProviders>,
  )
}

describe('A project’s repositories', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('marks primary from the payload, on the row the server said', async () => {
    mockReads([
      aProjectRepository({ id: 'r1', name: 'acme-storefront', position: 0, primary: true }),
      aProjectRepository({
        id: 'r2',
        name: 'acme-checkout-service',
        defaultBranch: 'develop',
        subdirectory: '/services/checkout',
        buildCommand: './gradlew build',
        position: 1,
        primary: false,
      }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Repositories.Row')).toHaveLength(2))
    const badges = screen.getAllByTestId('Repositories.Row.Primary')
    expect(badges).toHaveLength(1)
    expect(screen.getAllByTestId('Repositories.Row.Name')[0]).toHaveTextContent('acme-storefront')
  })

  it('says a project with none is not degraded, rather than leaving a blank panel', async () => {
    mockReads([])
    renderPage()

    const empty = await screen.findByTestId('Repositories.Empty')
    expect(empty).toHaveTextContent('Nothing else in this project changes')
  })

  it('sends all five values on an add, because the write is a full replacement', async () => {
    mockReads([])
    const post = vi.spyOn(api, 'post').mockResolvedValue(aProjectRepository() as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('Repositories.Add'))
    await userEvent.type(screen.getByTestId('Repositories.Dialog.Name'), 'acme-storefront')
    await userEvent.type(screen.getByTestId('Repositories.Dialog.Url'), 'git@example.test:a/b.git')
    await userEvent.type(screen.getByTestId('Repositories.Dialog.Build'), 'npm test')
    await userEvent.click(screen.getByTestId('Repositories.Dialog.Save'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        `/projects/${PROJECT_ID}/repositories`,
        {
          name: 'acme-storefront',
          url: 'git@example.test:a/b.git',
          // The two defaults the form seeds rather than making a reader think about a monorepo
          // before they can save.
          defaultBranch: 'main',
          subdirectory: '/',
          buildCommand: 'npm test',
        },
        expect.anything(),
      ),
    )
  })

  it('shows the list to a reader who cannot change it, with no controls', async () => {
    mockReads([aProjectRepository()], ['project:read'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Repositories.Row')).toBeInTheDocument())
    expect(screen.queryByTestId('Repositories.Add')).toBeNull()
    expect(screen.queryByTestId('Repositories.Row.Edit')).toBeNull()
  })
})
