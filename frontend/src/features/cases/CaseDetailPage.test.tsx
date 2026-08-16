import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, projectSettings } from '../../test/fixtures'
import { CaseDetailPage } from './CaseDetailPage'

/**
 * The case detail — and specifically **the one gate in the app that asks about the row**.
 *
 * **These drive a qualifier no seeded role carries any more.** `case:delete` was `OWN` for Test
 * Engineer until `V51` (§207); CRUD on plans, cases and steps now belongs to every non-Viewer
 * contributor whoever wrote the row. The tests are unchanged and still pass because they **mock**
 * the qualifier rather than reading the catalogue — and they are kept for what they actually
 * assert: that this page passes `authorId` through. That wiring is retained on purpose (the
 * qualifier is server-side data), so it needs coverage exactly as much as before, and this is now
 * the only thing that would catch its removal.
 *
 * With that grant in play — whether from a future migration or from the mock below — whether Delete
 * is offered depends on who wrote *this* case, not only on what the caller holds. `PermissionGate`
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
    classificationValueIds: [],
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
    // The never-scored state, which is what most cases are: no `case_flake` row at all. Required by
    // the contract, so leaving it off is a build error rather than a panel that silently renders
    // its "not assessed" branch for the wrong reason — §65's cast class, caught by `required`.
    flake: null,
    // Empty, not absent — and it is a LIST where `flake` above is nullable, because the two
    // absences are different claims. No `case_flake` row means nobody has looked; no attachment
    // row means nothing is attached, which is an ordinary state with one spelling.
    attachments: [],
    // Required since §167, same terms as `attachments`: empty is the ordinary state, not a
    // third spelling of "not computed" — `case_doc_link` cannot say that (V46 §5).
    relatedDocuments: [],
    // §187: required, so their absence is a `tsc` error here rather than a runtime gap —
    // the same way `relatedDocuments` above landed in §167.
    declinedPreconditions: [],
    declinedPostconditions: [],
  }
}

/**
 * @param qualifier `'OWN'` for a Test Engineer's grant; **undefined for the unqualified one** —
 *   `heldPermissions` takes only the exceptions, so `'ALL'` is not a value it accepts. That is the
 *   seeded catalogue's own rule and it is enforced by the type rather than by a convention.
 */
function mockReads(
  createdBy: string | null,
  qualifier?: 'OWN',
  overrides: Partial<TestCase> = {},
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(
        ['case:read', 'case:edit', 'case:delete'],
        qualifier ? { 'case:delete': qualifier } : {},
      )
    }
    if (/\/cases\/[^/]+$/.test(path)) return { ...caseBody(createdBy), ...overrides }
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
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

/**
 * The attachment panel's **wiring**, which is a different defect from the panel itself.
 *
 * `AttachmentList.test.tsx` proves the panel gates on whatever key it is handed and refuses no row
 * on authorship. Nothing there proves this page hands it `case:edit` — and the two plausible wrong
 * answers are both silent. `case:delete` would compile, render, and refuse a caller whose served
 * grant carries an `OWN` qualifier — which this fixture's does, deliberately: no seeded grant has
 * carried `OWN` since `V51` (§207), and the fabricated shape is what keeps the retained client arm
 * distinguishable from the key beside it. `evidence:attach` would compile too and refuse everybody
 * outside a run.
 *
 * So the check is a caller who holds `case:edit` and whose served `case:delete` carries `OWN`, over
 * a **colleague's** case: Delete on the case is disabled, and Attach on its files is not. One
 * fixture, two opposite verdicts, and only the right key produces both.
 */
describe('CaseDetailPage — the attachment panel is gated on case:edit', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers Attach to a Test Engineer who may not delete this case', async () => {
    mockReads(COLLEAGUE, 'OWN')
    renderPage()
    await permissionsResolved()

    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.Attachments.Attach')).toBeEnabled(),
    )
    // The same render, the same caller: the two gates disagree, which is what proves they are
    // reading different keys rather than one verdict shared by accident.
    expect(deleteButton()).toBeDisabled()
  })

  it('refuses Attach to a caller with no case:edit', async () => {
    // The guard: without it the test above passes against a panel gated on nothing at all.
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['case:read'])
      if (/\/cases\/[^/]+$/.test(path)) return caseBody(CALLER)
      if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
      if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
      return []
    }) as never)
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.Attachments')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('CaseDetail.Attachments.Attach')).toBeDisabled()
  })

  it('says what belongs there rather than hiding the panel on a case with no files', async () => {
    // `attachments: []` is the ordinary state and the panel is unconditional — a section that
    // appears only once something is in it is a section nobody discovers.
    mockReads(CALLER)
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.Attachments.Empty')).toBeInTheDocument(),
    )
  })
})

/**
 * The declined inherited conditions (§187).
 *
 * The failure this catches is one the page had by construction until the field existed: a rule the
 * folder sets and this case has turned off rendered **identically to a rule nobody ever set**,
 * because the row was simply absent from `preconditions`. A reader deciding whether to log in
 * before running the case could not tell "somebody decided this case does not need it" from
 * "nobody has thought about it", and the two lead to opposite actions.
 */
describe('CaseDetailPage — a rule this case turned off', () => {
  const declined = [
    {
      id: 'attach-1',
      position: 0,
      conditionId: 'cond-1',
      title: 'Log in as standard user',
      body: ['Open the application'],
      inline: false,
      inherited: true,
      inheritedFrom: 'Payments',
      declineCount: 0,
    },
  ]

  it('renders it struck through, and NAMES the folder it still applies to', async () => {
    // The folder is the actionable half. "Declined" alone tells a reader a decision was made and
    // not where the rule still holds — which is the same reason the inherited chip is named rather
    // than merely marked.
    mockReads(CALLER, undefined, { declinedPreconditions: declined } as Partial<TestCase>)
    renderPage()

    const section = await screen.findByTestId('CaseDetail.Preconditions.Declined')
    expect(section).toHaveTextContent('Log in as standard user')
    expect(section).toHaveTextContent(/still applies to every other case in Payments/i)
  })

  it('keeps it OUT of the procedure — the checklist is what a tester performs', async () => {
    // Not a struck-through line inside the list: that is a step a tester has to decide about, and
    // it is the same argument the server makes for serving two lists instead of one flagged one.
    mockReads(CALLER, undefined, { declinedPreconditions: declined } as Partial<TestCase>)
    renderPage()

    await screen.findByTestId('CaseDetail.Preconditions.Declined')
    expect(screen.queryByTestId('CaseDetail.Preconditions.Item')).not.toBeInTheDocument()
  })

  it('shows nothing at all when the case declines nothing — the ordinary state', async () => {
    mockReads(CALLER)
    renderPage()

    await screen.findByTestId('CaseDetail.Preconditions')
    expect(screen.queryByTestId('CaseDetail.Preconditions.Declined')).not.toBeInTheDocument()
  })
})

/**
 * The lifecycle menu — §227 / audit A1.
 *
 * Three things are locked, and each would ship silently without its assertion.
 *
 * 1. **The menu offers only the edges the case's current status has.** Offering one the server
 *    refuses with a 409 is §31's failure inverted — a live control that fails. The pair below is a
 *    DRAFT (no Approve) and an IN_REVIEW (Approve), so a table that offered everything from
 *    everywhere fails the first and a table that offered nothing fails the second.
 * 2. **Each item is gated on the key its own edge costs**, so a Test Engineer sees Approve greyed
 *    beside a live Return to draft. The enabled half is the barrier: `useCan` is fail-closed while
 *    pending, so a disabled-only assertion passes against a page with no gate at all (§66) — and it
 *    passed against every draft of this file that forgot `permissionsResolved`.
 * 3. **The click sends the target the item names**, to `PUT …/cases/{id}/status` and not through
 *    the bulk endpoint. Delegating this control to `POST /cases/bulk` would turn its 403 into a
 *    200-with-a-skip, which is a refusal a person looking at one case cannot act on.
 */
describe('CaseDetailPage — the lifecycle menu', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  function withStatus(status: TestCase['lifecycleStatus'], keys: string[]) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(keys as never)
      if (/\/cases\/[^/]+$/.test(path)) {
        return { ...caseBody(CALLER), lifecycleStatus: status }
      }
      if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
      if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
      return []
    }) as never)
  }

  async function openMenu() {
    await userEvent.click(await screen.findByTestId('CaseDetail.StatusButton'))
    await screen.findByTestId('CaseDetail.StatusMenu')
  }

  it('offers a draft Submit and Deprecate, and no Approve at all', async () => {
    withStatus('DRAFT', ['case:read', 'case:edit'])
    renderPage()
    await openMenu()

    expect(screen.getByTestId('CaseDetail.StatusMenu.IN_REVIEW')).toBeInTheDocument()
    expect(screen.getByTestId('CaseDetail.StatusMenu.DEPRECATED')).toBeInTheDocument()
    // §6g's one gate: APPROVED is reachable only from IN_REVIEW, so the item is not there to be
    // greyed — a disabled Approve on a draft would say the caller lacks a permission when what
    // they lack is a review.
    expect(screen.queryByTestId('CaseDetail.StatusMenu.APPROVED')).not.toBeInTheDocument()
  })

  it('greys Approve for a contributor and leaves Return to draft live', async () => {
    withStatus('IN_REVIEW', ['case:read', 'case:edit'])
    renderPage()
    await openMenu()

    // The barrier. Until this passes the gate is still pending and every disabled assertion below
    // is satisfied by the loading state.
    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.StatusMenu.DRAFT')).not.toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
    expect(screen.getByTestId('CaseDetail.StatusMenu.APPROVED')).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('lets a lead approve, and sends the target to the single-case status endpoint', async () => {
    withStatus('IN_REVIEW', ['case:read', 'case:edit', 'case:approve'])
    const put = vi.spyOn(api, 'put').mockResolvedValue({
      ...caseBody(CALLER),
      lifecycleStatus: 'APPROVED',
    } as never)
    renderPage()
    await openMenu()

    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.StatusMenu.APPROVED')).not.toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
    await userEvent.click(screen.getByTestId('CaseDetail.StatusMenu.APPROVED'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/cases/c1/status`, {
      status: 'APPROVED',
    })
  })

  it('offers a deprecated case exactly one way back, and it lands on DRAFT', async () => {
    withStatus('DEPRECATED', ['case:read', 'case:edit'])
    renderPage()
    await openMenu()

    // Not straight back to APPROVED: approval attaches to reviewed content by a named person, so a
    // revived case has to pass review again. §6g's clone rule, on this screen.
    expect(screen.getByTestId('CaseDetail.StatusMenu.DRAFT')).toBeInTheDocument()
    expect(screen.queryByTestId('CaseDetail.StatusMenu.APPROVED')).not.toBeInTheDocument()
    expect(screen.queryByTestId('CaseDetail.StatusMenu.IN_REVIEW')).not.toBeInTheDocument()
  })
})
