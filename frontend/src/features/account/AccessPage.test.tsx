import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import { aProject } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { AccessPage } from './AccessPage'

/**
 * `/account/access` — brief finding 15's answer.
 *
 * Three things are pinned, and each of them is a way this page could be *worse than absent*:
 *
 * 1. **It states the caller's role per project.** A page that says "you have access" and not to
 *    what is a page nobody reads twice.
 * 2. **It lists a hidden entry only where the caller actually lacks the key**, and never while the
 *    answer is still in flight. An in-flight project contributes no verdict at all — the failure
 *    it would otherwise have is telling somebody an entry is hidden from them a moment before it
 *    appears in their launcher (⊞) or the project tabs, which is the exact confusion the page exists to end.
 * 3. **It names roles and not the screens' contents.** The nine stay hidden; what this adds is
 *    their names and who holds the key, and a test that let a reason drift into describing the
 *    protected thing would have let the page defeat all nine decisions at once.
 */

const P1 = 'p1'
const P2 = 'p2'

function mockReads(perProject: Record<string, PermissionKey[]>, company: PermissionKey[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/projects') {
      return [
        aProject({ id: P1, key: 'ACME', name: 'Acme Payments', myRole: 'Test Engineer' }),
        aProject({ id: P2, key: 'BETA', name: 'Beta Checkout', myRole: 'Viewer' }),
      ]
    }
    // Anchored per project, because the company question is the SAME path without the parameter
    // and a `startsWith` would answer one with the other — which is the silent way this page would
    // report a company key as held on the strength of a project's answer.
    for (const [projectId, keys] of Object.entries(perProject)) {
      if (path === `/me/permissions?projectId=${projectId}`) {
        return heldPermissions(keys, {}, projectId)
      }
    }
    if (path === '/me/permissions') return heldPermissions(company, {}, null)
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

/**
 * One hidden-entry row, by the entry it is about.
 *
 * `findAllByTestId('Access.HiddenEntry')` is not enough and the difference matters: the
 * company-scope entries resolve from one read and the project-scope ones from a read per project,
 * so the list is briefly the company half alone. A `findAll` is satisfied by that first paint, and
 * an assertion about a project-scope row then fails on a page that was merely early. Waiting for
 * the row itself is what makes these tests about the page rather than about its arrival order.
 */
async function hiddenEntry(label: string): Promise<HTMLElement> {
  const heading = await screen.findByText(label)
  const row = heading.closest('[data-testid="Access.HiddenEntry"]')
  expect(row, `"${label}" is on the page but not inside a hidden-entry row`).not.toBeNull()
  return row as HTMLElement
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route="/account/access" userId="u1">
      <AccessPage />
    </TestProviders>,
  )
}

describe('Your access', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('names the caller’s role on each project they can see', async () => {
    mockReads({ [P1]: ['project:read'], [P2]: ['project:read'] })
    renderPage()

    const grants = await screen.findAllByTestId('Access.Grant')
    expect(grants).toHaveLength(2)
    expect(within(grants[0] as HTMLElement).getByText('ACME')).toBeInTheDocument()
    expect(within(grants[0] as HTMLElement).getByText('Test Engineer')).toBeInTheDocument()
    expect(within(grants[1] as HTMLElement).getByText('Viewer')).toBeInTheDocument()

    // Grouped by role, which is the sentence somebody says out loud, and it names the company
    // rule the whole page rests on: membership authenticates, a grant is per project.
    const summary = await screen.findByTestId('Access.Summary')
    expect(summary).toHaveTextContent('a Test Engineer on ACME')
    expect(summary).toHaveTextContent('a Viewer on BETA')
    expect(summary).toHaveTextContent('each project needs its own grant')
  })

  it('lists a project-scope entry against every project it is hidden on', async () => {
    // Neither project grants `project:audit`, so `Project audit` is hidden on both.
    mockReads({ [P1]: ['project:read'], [P2]: ['project:read'] })
    renderPage()

    const audit = await hiddenEntry('Project audit')
    expect(audit).toHaveTextContent('ACME, BETA')
    // The recorded reason, from the gate that carries it — not a second copy in this screen.
    expect(audit).toHaveTextContent('the pair to Company audit')
  })

  it('drops an entry from the list on the projects where the caller does hold the key', async () => {
    mockReads({ [P1]: ['project:read', 'project:audit'], [P2]: ['project:read'] })
    renderPage()

    const audit = await hiddenEntry('Project audit')
    expect(audit).toHaveTextContent('BETA')
    expect(audit).not.toHaveTextContent('ACME')
  })

  it('names who holds the key, from §A3, without Instance Admin', async () => {
    mockReads({ [P1]: ['project:read'], [P2]: ['project:read'] })
    renderPage()

    const imports = await hiddenEntry('Imports')
    expect(imports).toHaveTextContent('Project Manager, Test Lead and Company Admin')
    // The operator of the instance is not somebody in this company to ask.
    expect(imports).not.toHaveTextContent('Instance Admin')
  })

  it('says nothing is hidden when the caller holds every gated key', async () => {
    const everyGate: PermissionKey[] = [
      'project:read',
      'project:audit',
      'issue:import',
      'integration:manage',
    ]
    mockReads({ [P1]: everyGate, [P2]: everyGate }, [
      'client:read',
      'billing:read',
      'token:issue',
      'company:idp',
      'audit:view',
    ])
    renderPage()

    expect(await screen.findByTestId('Access.NothingHidden')).toBeInTheDocument()
  })
})
