import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { ReportsPage } from './ReportsPage'
import type { PermissionKey } from '../../api/types'

/**
 * The reports screen.
 *
 * Two things are locked here.
 *
 * **The Generate gate, asserted as a PAIR.** `useCan` is fail-closed while it is still pending, so
 * `expect(button).toBeDisabled()` for a caller without the key is satisfied by the *loading* state
 * and passes against a component with no gate in it at all — §66's finding, which cost §80 four
 * tests and §76 a mutation that failed to redden. The holder's ENABLED assertion is the barrier:
 * it can only pass once the gate has resolved and granted, and it is what gives the refusal half
 * its meaning.
 *
 * **The empty definitions panel is a rendering, not an omission.** `GET /reports` is served and
 * answers `[]` on every deployment until R5 writes to it, and this screen was built against that
 * — a panel that only learns to render once rows exist is one written against a shape nobody has
 * seen (§81, §87). There is deliberately no Save control, so its absence is asserted too: a later
 * reader adding one would be adding a button whose endpoint does not exist.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

function mockReads(held: PermissionKey[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/me/permissions')) return heldPermissions(held, {}, PROJECT)
    if (path.endsWith('/reports')) return []
    if (path.includes(`/projects/${PROJECT}`)) return { id: PROJECT, key: 'ACME', name: 'Acme' }
    return []
  }) as unknown as typeof api.get)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/reports`}>
      <ReportsPage />
    </TestProviders>,
  )
}

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useParams: () => ({ projectId: PROJECT }) }
})

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('enables Generate for a holder of report:export and disables it for everyone else', async () => {
    mockReads(['report:export', 'report:view'])
    const holder = renderPage()
    // The barrier. Until this passes the gate is still pending, and a pending gate disables the
    // control for a reason that has nothing to do with permissions.
    await waitFor(() => expect(screen.getByTestId('Reports.GenerateButton')).toBeEnabled())
    holder.unmount()

    vi.restoreAllMocks()
    mockReads(['report:view'])
    renderPage()
    await waitFor(() =>
      expect(screen.getByTestId('Reports.GenerateButton')).toBeDisabled(),
    )
    // Disabled, never hidden (§31): the person who has to go and ask can see the control exists.
    expect(screen.getByTestId('Reports.GenerateButton')).toBeInTheDocument()
  })

  it('renders the saved-definitions panel empty, with no control that could create one', async () => {
    mockReads(['report:export', 'report:view'])
    renderPage()

    expect(await screen.findByTestId('Reports.Definitions.Empty')).toBeInTheDocument()
    // R5 has not built the writer, so `ui-rules`' rule binds: no Add button whose endpoint does
    // not exist. Asserted by absence of the grid too — an empty list is not a table of nothing.
    expect(screen.queryByTestId('Reports.Definitions.Grid')).not.toBeInTheDocument()
  })

  it('shows no job cards until something has been asked for', async () => {
    mockReads(['report:export', 'report:view'])
    renderPage()

    await screen.findByTestId('Reports.Definitions.Empty')
    // The jobs panel is the set THIS TAB created, and nothing has been. It is absent rather than
    // an empty region, because "no reports requested yet" is not a state of the server's data.
    expect(screen.queryByTestId('Reports.Jobs')).not.toBeInTheDocument()
  })
})
