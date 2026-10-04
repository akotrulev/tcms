import { render, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { SettingsSubnav } from './SettingsSubnav'

function renderSubnav(permissions: PermissionKey[], route: string) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    throw new Error(`unmocked read: ${path}`)
  }) as never)
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <Routes>
        <Route path="/projects/:projectId/*" element={<SettingsSubnav projectId="p1" />} />
      </Routes>
    </TestProviders>,
  )
}

describe('SettingsSubnav', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('marks the section the route is on', async () => {
    renderSubnav(['project:read'], '/projects/p1/settings/workflow')

    const current = await screen.findByTestId('Settings.Subnav.Workflow')
    expect(current).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('Settings.Subnav.General')).not.toHaveAttribute('aria-current')
  })

  it('lists a gated section only for a caller who holds its key', async () => {
    renderSubnav(['project:read'], '/projects/p1/settings/general')
    await screen.findByTestId('Settings.Subnav.General')
    expect(screen.queryByTestId('Settings.Subnav.Imports')).not.toBeInTheDocument()
    expect(screen.queryByTestId('Settings.Subnav.Projectaudit')).not.toBeInTheDocument()
  })

  it('lists it when the key is held', async () => {
    renderSubnav(['project:read', 'issue:import', 'project:audit'], '/projects/p1/settings/general')
    await waitFor(() => expect(screen.getByTestId('Settings.Subnav.Imports')).toBeInTheDocument())
    expect(screen.getByTestId('Settings.Subnav.Projectaudit')).toBeInTheDocument()
  })
})
