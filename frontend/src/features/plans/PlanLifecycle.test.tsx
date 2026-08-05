import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { PlanStatus, TestPlan } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { PlanStatisticsPage } from './PlanStatisticsPage'

/**
 * The plan's ladder controls — **the affordance for a column that had no writer until §73**.
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **The controls swap on the plan's state.** A closed plan offers Reopen and nothing else,
 *      because the server refuses every other move outright — offering one that cannot succeed is
 *      what §31 exists to stop.
 *   2. **The gate is read from the EDGE, not the target.** "Return to draft" asks `plan:edit` on a
 *      plan in review and `plan:approve` on an approved one, mirroring `PlanLifecycle.permissionFor`.
 *      This is the property a target-keyed table gets wrong, and it is invisible everywhere else:
 *      the button renders either way, and the server's 403 is what the user sees instead.
 *   3. **`plan:approve` isolated from `plan:edit`.** The backend's own gate test is stronger here
 *      than §71's was — a Test Engineer really holds one and not the other — but this test can
 *      still mock the pair independently of the seeded catalogue.
 *   4. **Reopen asks for ACTIVE**, never DRAFT: a closed release that needs more testing is still
 *      the release it was, and the server refuses the other spelling.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(status: PlanStatus): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    name: 'Release 4.4 regression',
    status,
    milestoneName: null,
    milestoneDate: null,
    caseCount: 0,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    currentVersionNo: 1,
    lockVersion: 1,
  } as unknown as TestPlan
}

function mockReads(
  status: PlanStatus,
  permissions: string[] = ['project:read', 'plan:edit', 'plan:approve', 'plan:delete'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/statistics')) {
      return {
        planId: PLAN,
        planKey: 'TP-4',
        planName: 'Release 4.4 regression',
        aggregationPolicy: 'LATEST',
        counts: { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0 },
        coveragePercent: 0,
        byConfiguration: [],
        bySuite: [],
        blockers: [],
        notRun: [],
        trend: [],
        exitCriteria: [],
        latestExecutionAt: null,
      }
    }
    if (path.includes(`/plans/${PLAN}`)) return planBody(status)
    if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/plans/${PLAN}`}>
      <Routes>
        <Route path="/projects/:projectId/plans/:planId" element={<PlanStatisticsPage />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed — **without this every "disabled" assertion below
 * passes for the wrong reason**, because `useCan` is fail-closed while pending and a control that
 * has not been told anything yet is indistinguishable from one that was refused (§66).
 */
async function enabled(testId: string) {
  await waitFor(() => expect(screen.getByTestId(testId)).toBeEnabled())
}

describe('PlanStatisticsPage — the plan lifecycle controls', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers Submit for review on a draft, and nothing else', async () => {
    mockReads('DRAFT')
    renderPage()
    await enabled('PlanStats.Move.IN_REVIEW')

    // The controls SWAP rather than accumulate: a draft plan cannot be approved, so no button
    // offers a move the server would refuse (§31). Asserted as absences, because the failure this
    // catches is a table that lists every target on every row.
    expect(screen.queryByTestId('PlanStats.Move.APPROVED')).toBeNull()
    expect(screen.queryByTestId('PlanStats.Move.DRAFT')).toBeNull()
  })

  it('offers only Return to draft once the plan is approved, and it asks for DRAFT', async () => {
    mockReads('APPROVED')
    const put = vi.spyOn(api, 'put').mockResolvedValue(planBody('DRAFT') as never)
    renderPage()
    await enabled('PlanStats.Move.DRAFT')

    // §97 (M7): an approved plan used to offer Start. There is no such move — a plan is an outline
    // and whether its testing is under way is answered by its runs — and the withdrawal is the one
    // thing left, which is what makes this an exact pair of absences.
    expect(screen.queryByTestId('PlanStats.Move.IN_REVIEW')).toBeNull()
    expect(screen.queryByTestId('PlanStats.Move.APPROVED')).toBeNull()

    await userEvent.click(screen.getByTestId('PlanStats.Move.DRAFT'))
    expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/plans/${PLAN}/status`, {
      status: 'DRAFT',
    })
  })

  /**
   * The two halves of the edge rule, in one test because the contrast is the assertion: the same
   * button, the same target, the same caller — and the answer differs by where the plan is now.
   */
  it('gates "Return to draft" on plan:edit from review and on plan:approve from approved', async () => {
    mockReads('IN_REVIEW', ['project:read', 'plan:edit'])
    const inReview = renderPage()
    await enabled('PlanStats.Move.DRAFT')
    inReview.unmount()

    vi.restoreAllMocks()
    mockReads('APPROVED', ['project:read', 'plan:edit'])
    renderPage()

    // The caller holds plan:edit, so Edit is enabled — which is also the barrier proving the
    // permission answer has landed before the assertion below is trusted. It has to be a control
    // OTHER than the one under test, or `useCan`'s fail-closed pending state passes this against a
    // component with no gate at all (§66).
    await enabled('PlanStats.Edit')
    expect(screen.getByTestId('PlanStats.Move.DRAFT'))
      .toBeDisabled()
  })

  it('disables Approve for a caller who holds only plan:edit', async () => {
    mockReads('IN_REVIEW', ['project:read', 'plan:edit'])
    renderPage()

    // The barrier is the control this caller DOES hold.
    await enabled('PlanStats.Move.DRAFT')
    expect(screen.getByTestId('PlanStats.Move.APPROVED')).toBeDisabled()
  })

  it('disables Delete for a caller without plan:delete, and enables it with', async () => {
    mockReads('DRAFT', ['project:read', 'plan:edit'])
    const without = renderPage()
    await enabled('PlanStats.Move.IN_REVIEW')
    expect(screen.getByTestId('PlanStats.Delete')).toBeDisabled()
    without.unmount()

    vi.restoreAllMocks()
    mockReads('DRAFT')
    renderPage()
    await enabled('PlanStats.Delete')
  })

  it('renders the ladder refusal beside the buttons rather than as a toast', async () => {
    mockReads('DRAFT')
    // A real `ApiError`, not a shaped object: `describeError` narrows on the class, so a
    // look-alike falls through to the network-fault copy and the assertion would be about the
    // fallback rather than about the server's sentence.
    vi.spyOn(api, 'put').mockRejectedValue(
      new ApiError(
        409,
        'A plan is approved after it has been reviewed. Submit it for review first.',
        null,
        null,
        null,
        'Not possible right now',
      ),
    )
    renderPage()
    await enabled('PlanStats.Move.IN_REVIEW')

    await userEvent.click(screen.getByTestId('PlanStats.Move.IN_REVIEW'))

    await waitFor(() => expect(screen.getByTestId('PlanStats.StatusError')).toBeInTheDocument())
  })

  /*
   * §6g's gating rule and its lead override (§82) had four tests here, all of them about the
   * "Start it anyway" affordance offered after a refused DRAFT → ACTIVE. §97 (M7) removed that
   * edge, the gate, the setting and the request field, so the tests go with them rather than being
   * rewritten to assert an override the contract no longer has. §6g's CASE gate is untouched and
   * is tested on the run-create path, which is where its rule still lives.
   */

  it('renders the delete refusal inside the dialog, which stays open', async () => {
    // The 409 the client cannot predict: nothing on this screen knows the plan's run count, so
    // unlike a permission this refusal has no gate that could have disabled the control.
    mockReads('APPROVED')
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(
        409,
        '2 test runs have been executed against this plan, so deleting it would leave those runs '
          + 'pointing at a plan nobody can open. Delete those runs first if this plan really was '
          + 'never used.',
        null,
        null,
        null,
        'Not possible right now',
      ),
    )
    renderPage()
    await enabled('PlanStats.Delete')

    await userEvent.click(screen.getByTestId('PlanStats.Delete'))
    await userEvent.click(await screen.findByTestId('PlanStats.DeleteDialog.Confirm'))

    const error = await screen.findByTestId('PlanStats.DeleteDialog.Error')
    expect(error).toHaveTextContent('Delete those runs first')
    expect(screen.getByTestId('PlanStats.DeleteDialog.Confirm')).toBeInTheDocument()
  })
})
