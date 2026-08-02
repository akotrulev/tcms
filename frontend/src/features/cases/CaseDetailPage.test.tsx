import { render, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { CaseDetailPage } from './CaseDetailPage'

/**
 * The case detail — and specifically **the one gate in the app that asks about the row**.
 *
 * `case:delete` is `OWN`-qualified for a Test Engineer (roles spec §A5), so whether Delete is
 * offered depends on who wrote *this* case, not only on what the caller holds. `PermissionGate`
 * has its own tests for the verdict; these exist for the **wiring**, which is a different defect
 * and the one with the worse history:
 *
 *   - `PermissionGate.test.tsx` proves the component disables a colleague's row when it is *given*
 *     the author.
 *   - Nothing proved this page **passes** it. A call site that drops `authorId` falls back to the
 *     unqualified verdict silently — `PermissionGate` treats an omitted prop as "this permission is
 *     not author-qualified", which is correct for every other gate in the app and wrong here.
 *
 * That is §62's lesson exactly: the component was fine, the call site was not, and every gate was
 * green while the feature did not work. So these assert through the rendered button on the real
 * page rather than through the hook.
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

/** The smallest case body the page renders without falling into its error boundary. */
function caseBody(createdBy: string | null): TestCase {
  return {
    id: 'c1',
    key: 'TC-1',
    keySeq: 1,
    title: 'Full refund of a settled card payment succeeds',
    suiteId: 's1',
    suiteName: 'Refunds',
    priority: 'HIGH',
    lifecycleStatus: 'APPROVED',
    automationStatus: 'MANUAL',
    stepFormat: 'STRUCTURED',
    owner: person(COLLEAGUE),
    // The field under test. Owner is deliberately somebody ELSE on every row here: a page that
    // gated on `owner` instead would pass an author-equals-owner fixture and fail nothing.
    createdBy: createdBy === null ? null : person(createdBy),
    testType: null,
    testLevel: null,
    features: [],
    tags: [],
    healthSignals: [],
    lastResult: null,
    updatedAt: '2026-07-28T12:00:00Z',
    description: null,
    objective: null,
    expectedOutcome: null,
    severity: null,
    estimatedDurationSec: null,
    automationTags: [],
    automationKey: null,
    notAutomatableReason: null,
    gherkinSource: null,
    steps: [],
    preconditions: [],
    postconditions: [],
    currentVersionNo: 1,
    lockVersion: 1,
  } as TestCase
}

/**
 * @param qualifier `'OWN'` for a Test Engineer's grant; **undefined for the unqualified one** —
 *   `heldPermissions` takes only the exceptions, so `'ALL'` is not a value it accepts. That is the
 *   seeded catalogue's own rule and it is enforced by the type rather than by a convention.
 */
function mockReads(createdBy: string | null, qualifier?: 'OWN') {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(
        ['case:read', 'case:edit', 'case:delete'],
        qualifier ? { 'case:delete': qualifier } : {},
      )
    }
    if (path.includes('/cases/')) return caseBody(createdBy)
    if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/cases/c1`}
      userId={CALLER}
    >
      <Routes>
        <Route path="/projects/:projectId/cases/:caseId" element={<CaseDetailPage />} />
      </Routes>
    </TestProviders>,
  )
}

const deleteButton = () => screen.getByTestId('CaseDetail.DeleteButton')

/**
 * Wait until the permission answer has actually landed — **without this, every "disabled"
 * assertion below passes for the wrong reason.**
 *
 * `useCan` is fail-closed while pending, so Delete is disabled on the very first render for a
 * caller who is about to be allowed. `waitFor(() => expect(...).toBeDisabled())` is satisfied
 * immediately by that pending state, and the test then proves nothing about authorship: the first
 * draft of this file passed all four assertions against a page that had `authorId` deleted
 * outright.
 *
 * Edit is the barrier because `case:edit` is granted unqualified in these fixtures, so it is
 * disabled while pending and enabled once — and only once — the answer has resolved.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId('CaseDetail.EditButton')).toBeEnabled())
}

describe('CaseDetailPage — the Delete gate reads the row, not just the caller', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers Delete on a case the caller wrote', async () => {
    mockReads(CALLER, 'OWN')
    renderPage()

    await waitFor(() => expect(deleteButton()).toBeEnabled())
  })

  it('refuses it on a colleagues case, even though the caller holds case:delete', async () => {
    // The defect this slice closes: before `createdBy` was on the wire this button enabled here,
    // and the caller learned whose case it was from a 403.
    mockReads(COLLEAGUE, 'OWN')
    renderPage()
    await permissionsResolved()

    expect(deleteButton()).toBeDisabled()
  })

  it('refuses it on an unauthored case', async () => {
    // `test_case.created_by` is nullable and the server answers a null author `false` for an OWN
    // grant. These are the oldest rows in an installation, so getting this wrong would look like a
    // defect in the import rather than in the gate.
    mockReads(null, 'OWN')
    renderPage()
    await permissionsResolved()

    expect(deleteButton()).toBeDisabled()
  })

  it('offers Delete on every row to an unqualified holder', async () => {
    // The guard. Without it, all three tests above pass equally well against a page that disables
    // Delete for everybody — which is what dropping `authorId` in the *other* direction produces,
    // and it would lock out every Test Lead in the product.
    mockReads(COLLEAGUE)
    renderPage()

    await waitFor(() => expect(deleteButton()).toBeEnabled())
  })
})
