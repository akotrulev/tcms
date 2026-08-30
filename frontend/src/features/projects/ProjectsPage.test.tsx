import { render, screen } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { aProject } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { ProjectsPage } from './ProjectsPage'

/**
 * The picker's two claims about its own data, both of which the 2026-08-30 renders audit found
 * broken or missing (finding 1.12):
 *
 *   1. **The card states a count.** It rendered `pluralNoun`, which returns the noun ALONE — the
 *      dashboard wants it that way because it wraps the number in an `Identifier` — so the line
 *      read "cases" with no number, and with `myRole` null for a Company Admin (correctly omitted)
 *      the whole metadata line was one dangling word.
 *   2. **A retired project can be put away.** The list showed every archived project
 *      unconditionally. Hiding them needs a switch that says how many it is holding, because the
 *      archived badge's own comment is right that a project simply absent is unreachable.
 */

const PROJECTS = [
  aProject({ id: 'p1', key: 'WEB', name: 'Storefront', caseCount: 10 }),
  aProject({ id: 'p2', key: 'LEG', name: 'Legacy', caseCount: 3, archived: true }),
]

function mockReads() {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/projects') return PROJECTS
    if (path.endsWith('/permissions')) return heldPermissions([])
    return {}
  }) as typeof api.get)
}

function renderProjects() {
  return render(
    <TestProviders client={createTestQueryClient()} route="/projects">
      <Routes>
        <Route path="/projects" element={<ProjectsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('the project card states its count, and the list can put a retired project away', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockReads()
  })

  it('prints the number beside the noun', async () => {
    renderProjects()
    const meta = await screen.findAllByTestId('Projects.Card.Meta')
    expect(meta[0].textContent).toMatch(/\d+\s+cases?/)
  })

  it('hides an archived project by default, behind a switch that says how many', async () => {
    renderProjects()

    expect(await screen.findByText('Storefront')).toBeInTheDocument()
    expect(screen.queryByText('Legacy')).not.toBeInTheDocument()
    expect(screen.getByTestId('Projects.ShowArchived')).toBeInTheDocument()
    // The count is the half that keeps the old comment's guarantee: a project that is hidden is
    // marked as hidden, never simply absent.
    expect(screen.getByText(/Show archived \(1\)/)).toBeInTheDocument()
  })

  it('shows it again when the URL asks', async () => {
    render(
      <TestProviders client={createTestQueryClient()} route="/projects?archived=true">
        <Routes>
          <Route path="/projects" element={<ProjectsPage />} />
        </Routes>
      </TestProviders>,
    )
    expect(await screen.findByText('Legacy')).toBeInTheDocument()
  })
})
