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

  it('lists an archived project by default, and offers a switch that says how many', async () => {
    /*
      Listed, NOT hidden. The render draws a `Show archived` switch, which reads as off-by-default,
      and off-by-default is the one thing this list may not do: the server returns retired projects
      deliberately because omitting one makes it unreachable rather than marked, and this card is
      the only route back to the screen that can un-archive it (`ArchiveProject.test.tsx` pins the
      same guarantee from the other side). So the control exists and defaults to keeping it.
    */
    renderProjects()

    expect(await screen.findByText('Storefront')).toBeInTheDocument()
    expect(screen.getByText('Legacy')).toBeInTheDocument()
    expect(screen.getByText(/Show archived \(1\)/)).toBeInTheDocument()
  })

  it('puts them away when the reader asks, and says how many it is holding', async () => {
    render(
      <TestProviders client={createTestQueryClient()} route="/projects?archived=false">
        <Routes>
          <Route path="/projects" element={<ProjectsPage />} />
        </Routes>
      </TestProviders>,
    )

    expect(await screen.findByText('Storefront')).toBeInTheDocument()
    expect(screen.queryByText('Legacy')).not.toBeInTheDocument()
    // The count is what keeps "hidden" from reading as "gone".
    expect(screen.getByText(/Show archived \(1\)/)).toBeInTheDocument()
  })
})
