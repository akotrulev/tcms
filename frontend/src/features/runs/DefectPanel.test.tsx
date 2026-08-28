import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { DefectLink, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { DefectPanel } from './DefectPanel'

/**
 * The defect-link panel — `defect_link`'s first writer reaching a screen (audit C5).
 *
 * The interesting assertions are about what the form does **not** offer. `provider` and
 * `statusCache` are both absent by decision (`V62`), and an absent control is exactly the thing a
 * later "completeness" edit adds back without reading why — so both are pinned here rather than
 * left as an observation in a migration header.
 */

const PROJECT = 'p1'
const CALLER = '99999999-9999-4999-8999-999999999999'

function defect(overrides: Partial<DefectLink> = {}): DefectLink {
  return {
    id: 'd1',
    externalKey: 'PAY-991',
    provider: 'EXTERNAL',
    url: 'https://tracker.acme.test/browse/PAY-991',
    statusCache: null,
    ...overrides,
  }
}

function mockPermissions(keys: PermissionKey[] = ['defect:create']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(keys)
    }
    return []
  }) as never)
}

function renderPanel(rows: DefectLink[], frozen = false) {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/runs/r1/executions/x1`}
      userId={CALLER}
    >
      <DefectPanel
        projectId={PROJECT}
        runId="r1"
        executionId="x1"
        defects={rows}
        frozen={frozen}
      />
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed — without it every "disabled" assertion passes for
 * the wrong reason, since `useCan` is fail-closed while pending. `EvidencePanel.test.tsx` records
 * the same barrier and why it is needed.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId('Execution.Defects.Add')).toBeEnabled())
}

describe('DefectPanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a linked defect as an external link carrying its key', async () => {
    mockPermissions()
    renderPanel([defect()])

    const link = await screen.findByTestId('Execution.Defects.Link')
    expect(link).toHaveAttribute('href', 'https://tracker.acme.test/browse/PAY-991')
    expect(link).toHaveTextContent('PAY-991')
  })

  it('says nothing is linked rather than rendering an empty list', async () => {
    mockPermissions()
    renderPanel([])

    expect(await screen.findByText('No defect linked to this result.')).toBeInTheDocument()
    expect(screen.queryByTestId('Execution.Defects.List')).not.toBeInTheDocument()
  })

  it('offers no provider and no status control — the server owns both (V62)', async () => {
    mockPermissions()
    renderPanel([])
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('Execution.Defects.Add'))

    expect(screen.getByTestId('Execution.Defects.Key')).toBeInTheDocument()
    expect(screen.getByTestId('Execution.Defects.Url')).toBeInTheDocument()
    expect(screen.queryByLabelText(/provider/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/status/i)).not.toBeInTheDocument()
  })

  it('sends the key and the URL and nothing else', async () => {
    mockPermissions()
    const post = vi.spyOn(api, 'post').mockResolvedValue(defect() as never)
    renderPanel([])
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('Execution.Defects.Add'))
    await userEvent.type(screen.getByTestId('Execution.Defects.Key'), 'PAY-991')
    await userEvent.type(
      screen.getByTestId('Execution.Defects.Url'),
      'https://tracker.acme.test/browse/PAY-991',
    )
    await userEvent.click(screen.getByTestId('Execution.Defects.Save'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]).toBe(
      `/projects/${PROJECT}/runs/r1/executions/x1/defects`,
    )
    expect(post.mock.calls[0][1]).toEqual({
      externalKey: 'PAY-991',
      url: 'https://tracker.acme.test/browse/PAY-991',
    })
  })

  it('will not submit a half-filled link', async () => {
    mockPermissions()
    renderPanel([])
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('Execution.Defects.Add'))
    await userEvent.type(screen.getByTestId('Execution.Defects.Key'), 'PAY-991')

    expect(screen.getByTestId('Execution.Defects.Save')).toBeDisabled()
  })

  it('refuses both writes without defect:create, and STILL shows the link', async () => {
    mockPermissions([])
    renderPanel([defect()])

    // The link is the record of what happened; a reader who cannot write it must still see it.
    expect(await screen.findByTestId('Execution.Defects.Link')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('Execution.Defects.Add')).toBeDisabled())
    expect(screen.getByTestId('Execution.Defects.Unlink')).toBeDisabled()
  })

  it('freezes both writes on a closed run while leaving the link readable', async () => {
    mockPermissions()
    renderPanel([defect()], true)

    expect(await screen.findByTestId('Execution.Defects.Link')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('Execution.Defects.Add')).toBeDisabled())
    expect(screen.getByTestId('Execution.Defects.Unlink')).toBeDisabled()
  })

  it('unlinks by id', async () => {
    mockPermissions()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPanel([defect()])
    await permissionsResolved()

    await waitFor(() => expect(screen.getByTestId('Execution.Defects.Unlink')).toBeEnabled())
    await userEvent.click(screen.getByTestId('Execution.Defects.Unlink'))

    await waitFor(() =>
      expect(del).toHaveBeenCalledWith(`/projects/${PROJECT}/runs/r1/executions/x1/defects/d1`),
    )
  })
})

/**
 * Filing a defect from a failed step (004 Phase 3).
 *
 * The one behaviour worth a test of its own is the third reason the control can be unavailable:
 * **the caller has connected no tracker**. Permissions cannot express it — filing happens with the
 * caller's own credential, so somebody holding every key in the catalogue still cannot file — and
 * a button that opens a dialog which then reports a 409 teaches them the product is broken rather
 * than that they have a connection to make.
 */
describe('DefectPanel — filing (004 Phase 3)', () => {
  const filing = {
    caseKey: 'TC-1',
    caseTitle: 'Refund rounds to the cent',
    runName: 'Regression 12',
    environment: 'Chrome',
    releaseVersion: '2.4.0',
    verdict: 'Failed',
    failedStep: 'step 3 — apply the refund',
  }

  function renderFiling(identities: unknown[], frozen = false) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['defect:create'])
      if (path === '/me/tracker-identities') return identities
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    return render(
      <TestProviders
        client={createTestQueryClient()}
        route={`/projects/${PROJECT}/runs/r1/executions/x1`}
        userId={CALLER}
      >
        <DefectPanel
          projectId={PROJECT}
          runId="r1"
          executionId="x1"
          defects={[]}
          frozen={frozen}
          filing={filing}
        />
      </TestProviders>,
    )
  }

  it('offers filing to a connected caller', async () => {
    renderFiling([{ id: 'ti1' }])
    await waitFor(() => expect(screen.getByTestId('Execution.Defects.File')).toBeEnabled())
  })

  it('disables filing with a way out when the caller has connected nothing', async () => {
    renderFiling([])
    await waitFor(() => expect(screen.getByTestId('Execution.Defects.File')).toBeDisabled())
    // Hidden-with-a-reason, not hidden: the reason is actionable and the action is beside it.
    expect(screen.getByTestId('Execution.Defects.Connect')).toBeInTheDocument()
  })

  it('freezes filing on a closed run, exactly as linking is frozen', async () => {
    renderFiling([{ id: 'ti1' }], true)
    await waitFor(() => expect(screen.getByTestId('Execution.Defects.File')).toBeDisabled())
    // No connect prompt on a frozen run: reopening it is the remedy, not connecting.
    expect(screen.queryByTestId('Execution.Defects.Connect')).not.toBeInTheDocument()
  })

  it('pre-fills the description from the execution, composed client-side', async () => {
    // What a defect report says is a product decision this SPA makes visibly, not a template
    // buried in an adapter — so the content is asserted here rather than trusted to the server.
    renderFiling([{ id: 'ti1' }])
    await waitFor(() => expect(screen.getByTestId('Execution.Defects.File')).toBeEnabled())
    await userEvent.click(screen.getByTestId('Execution.Defects.File'))

    const description = await screen.findByTestId('Execution.Defects.FileDescription')
    expect((description as HTMLTextAreaElement).value).toContain('TC-1')
    expect((description as HTMLTextAreaElement).value).toContain('Regression 12')
    expect((description as HTMLTextAreaElement).value).toContain('Chrome')
    expect((description as HTMLTextAreaElement).value).toContain('step 3 — apply the refund')
  })
})
