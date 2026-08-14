import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Evidence } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { EvidencePanel } from './EvidencePanel'

/**
 * The evidence strip — and specifically the **second** gate in the app that asks about the row.
 *
 * **These drive a qualifier no seeded role carries any more** — `evidence:delete` lost its `OWN`
 * arm at `V51` (§207) alongside `case:delete`. They pass unchanged because they mock the qualifier
 * instead of reading the catalogue, and they are kept for the same reason `CaseDetailPage.test.tsx`
 * is: the wiring they cover is retained deliberately, so removing it must still fail something.
 *
 * With that grant in play, `evidence:delete` behaves exactly like `case:delete` did.
 * `PermissionGate` has its own tests for the verdict; these exist for the
 * **wiring**, which is a different defect and the one with the worse history — a call site that
 * drops `authorId` falls back to the unqualified verdict *silently*, because an omitted prop
 * correctly means "this permission is not author-qualified" for every other gate in the app.
 *
 * §66 committed the same file one entity over and recorded that its first draft passed every
 * assertion against a page with the wiring deleted. The barrier below is why this one does not.
 */

const PROJECT = 'p1'
const CALLER = '99999999-9999-4999-8999-999999999999'
const COLLEAGUE = '88888888-8888-4888-8888-888888888888'

const person = (id: string) => ({
  id,
  displayName: id === CALLER ? 'Ana Petrova' : 'Marco Silva',
  email: `${id}@example.test`,
  avatarUrl: null,
})

function evidence(createdBy: string | null, id = 'e1'): Evidence {
  return {
    id,
    filename: 'balance-assertion.png',
    contentType: 'image/png',
    sizeBytes: 184_320,
    url: `/api/v1/projects/${PROJECT}/runs/r1/executions/x1/evidence/${id}/content`,
    thumbnailUrl: null,
    caption: null,
    createdBy: createdBy === null ? null : person(createdBy),
    createdAt: '2026-07-28T09:14:00Z',
  }
}

/**
 * @param qualifier `'OWN'` for a Test Engineer's grant; **undefined for the unqualified one** —
 *   `heldPermissions` takes only the exceptions, so `'ALL'` is not a value it accepts.
 */
function mockPermissions(qualifier?: 'OWN') {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(
        ['evidence:attach', 'evidence:delete'],
        qualifier ? { 'evidence:delete': qualifier } : {},
      )
    }
    return []
  }) as never)
}

function renderPanel(rows: Evidence[]) {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/runs/r1/executions/x1`}
      userId={CALLER}
    >
      <EvidencePanel projectId={PROJECT} runId="r1" executionId="x1" evidence={rows} />
    </TestProviders>,
  )
}

const deleteButtons = () => screen.getAllByTestId('Execution.Evidence.Delete')

/**
 * Wait until the permission answer has actually landed — **without this, every "disabled"
 * assertion below passes for the wrong reason.**
 *
 * `useCan` is fail-closed while pending, so Delete is disabled on the very first render even for a
 * caller who is about to be allowed, and `waitFor(() => expect(...).toBeDisabled())` is satisfied
 * immediately by that pending state. Attach is the barrier because `evidence:attach` is granted
 * unqualified in these fixtures, so it flips only once — and not before — the answer resolves.
 */
async function permissionsResolved() {
  await waitFor(() =>
    expect(screen.getByTestId('Execution.Evidence.Attach')).toBeEnabled(),
  )
}

describe('EvidencePanel — the Delete gate reads the row, not just the caller', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers Delete on evidence the caller attached', async () => {
    mockPermissions('OWN')
    renderPanel([evidence(CALLER)])

    await waitFor(() => expect(deleteButtons()[0]).toBeEnabled())
  })

  it('refuses it on a colleagues evidence, though the caller holds evidence:delete', async () => {
    mockPermissions('OWN')
    renderPanel([evidence(COLLEAGUE)])
    await permissionsResolved()

    expect(deleteButtons()[0]).toBeDisabled()
  })

  it('refuses it on evidence with no recorded author', async () => {
    // `evidence.created_by` is nullable and the server answers a null author `false` for an OWN
    // grant. These are the oldest rows in an installation, so getting it wrong would read as a
    // defect in the import rather than in the gate — and no fixture contains one unless it is put
    // there deliberately, which is why this arm gets its own test.
    mockPermissions('OWN')
    renderPanel([evidence(null)])
    await permissionsResolved()

    expect(deleteButtons()[0]).toBeDisabled()
  })

  it('offers Delete on every row to an unqualified holder', async () => {
    // The guard. Without it the three assertions above pass equally well against a panel that
    // disables Delete for EVERYBODY — which is what dropping `authorId` in the other direction
    // produces, and which would lock every test lead out of their own runs.
    mockPermissions()
    renderPanel([evidence(COLLEAGUE), evidence(null, 'e2')])
    await permissionsResolved()

    await waitFor(() => {
      expect(deleteButtons()[0]).toBeEnabled()
      expect(deleteButtons()[1]).toBeEnabled()
    })
  })

  it('shows every attachment even when the caller may not remove any of them', async () => {
    // Evidence is part of the record of what happened. Disable-don't-hide (§31): a Viewer sees the
    // screenshots and cannot remove them, rather than being shown an execution that looks bare.
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions([])
      return []
    }) as never)
    renderPanel([evidence(CALLER), evidence(COLLEAGUE, 'e2')])

    expect(screen.getAllByTestId('Execution.Evidence.Row')).toHaveLength(2)
    await waitFor(() => expect(deleteButtons()[0]).toBeDisabled())
    expect(screen.getByTestId('Execution.Evidence.Attach')).toBeDisabled()
  })
})
