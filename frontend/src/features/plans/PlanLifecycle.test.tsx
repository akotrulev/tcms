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

  it('offers Submit for review and Start on a draft, and no Reopen', async () => {
    mockReads('DRAFT')
    renderPage()
    await enabled('PlanStats.Move.IN_REVIEW')

    expect(screen.getByTestId('PlanStats.Move.ACTIVE')).toBeEnabled()
    expect(screen.queryByTestId('PlanStats.Move.CLOSED')).toBeNull()
  })

  it('offers only Reopen once the plan is closed, and it asks for ACTIVE', async () => {
    mockReads('CLOSED')
    const put = vi.spyOn(api, 'put').mockResolvedValue(planBody('ACTIVE') as never)
    renderPage()
    await enabled('PlanStats.Move.ACTIVE')

    expect(screen.queryByTestId('PlanStats.Move.CLOSED')).toBeNull()
    expect(screen.queryByTestId('PlanStats.Move.IN_REVIEW')).toBeNull()

    await userEvent.click(screen.getByTestId('PlanStats.Move.ACTIVE'))
    expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/plans/${PLAN}/status`, {
      status: 'ACTIVE',
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

    // The caller holds plan:edit, so Start is enabled — which is also the barrier proving the
    // permission answer has landed before the assertion below is trusted.
    await enabled('PlanStats.Move.ACTIVE')
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
    await enabled('PlanStats.Move.ACTIVE')

    await userEvent.click(screen.getByTestId('PlanStats.Move.ACTIVE'))

    await waitFor(() => expect(screen.getByTestId('PlanStats.StatusError')).toBeInTheDocument())
  })

  /**
   * §6g's gating rule and its lead override (§82).
   *
   * The refusal is a 409 like the ladder's above, and everything that distinguishes it is in what
   * the screen does *next*. Three failures, none of them visible to `tsc`:
   *
   *   1. **A refusal with no way out.** §6g mandates a documented lead override for exactly this;
   *      an error naming an action the UI does not offer is a design bug (`ui-rules`).
   *   2. **The override offered on the wrong refusal.** The gate can only refuse a *start*, so a
   *      ladder refusal on any other target must not sprout an override button — which would put
   *      "Start it anyway" under a sentence about approving a plan.
   *   3. **The retry not carrying the flag**, which would produce the same 409 and read as a dead
   *      button.
   */
  describe('§6g gating', () => {
    function gateRefusal() {
      return new ApiError(
        409,
        'This project only starts approved plans. Submit this plan for review and have it ' +
          'approved before starting it.',
        null,
        null,
        null,
        'This plan needs approval',
      )
    }

    it('offers the override after a refused start, and sends the flag on the retry', async () => {
      mockReads('DRAFT')
      const put = vi
        .spyOn(api, 'put')
        .mockRejectedValueOnce(gateRefusal())
        .mockResolvedValue(planBody('ACTIVE') as never)
      renderPage()
      await enabled('PlanStats.Move.ACTIVE')

      await userEvent.click(screen.getByTestId('PlanStats.Move.ACTIVE'))

      // Re-queried inside waitFor: `PermissionGate` swaps the DOM node between its denied
      // (tooltip-wrapped) and allowed branches (§64's harness trap).
      await waitFor(() => expect(screen.getByTestId('PlanStats.OverrideGate')).toBeEnabled())
      await userEvent.click(screen.getByTestId('PlanStats.OverrideGate'))

      await waitFor(() => expect(put).toHaveBeenCalledTimes(2))
      expect(put.mock.calls[1][1]).toEqual({ status: 'ACTIVE', overrideApprovalGate: true })
    })

    it('disables the override for a caller without plan:approve, rather than hiding it', async () => {
      mockReads('DRAFT', ['project:read', 'plan:edit'])
      vi.spyOn(api, 'put').mockRejectedValue(gateRefusal())
      renderPage()
      await enabled('PlanStats.Move.ACTIVE')

      await userEvent.click(screen.getByTestId('PlanStats.Move.ACTIVE'))

      // Present — §31, so the person who has to go and ask can see the way out exists. Paired with
      // the enabling assertion above, or this passes with no gate in the code at all (§66).
      expect(await screen.findByTestId('PlanStats.OverrideGate')).toBeInTheDocument()
      await waitFor(() => expect(screen.getByTestId('PlanStats.OverrideGate')).toBeDisabled())
    })

    it('does NOT offer it on a refusal the gate cannot have caused', async () => {
      mockReads('DRAFT')
      vi.spyOn(api, 'put').mockRejectedValue(
        new ApiError(
          409,
          'Only a draft plan can be submitted for review. Return this plan to draft first.',
          null,
          null,
          null,
          'Not possible right now',
        ),
      )
      renderPage()
      await enabled('PlanStats.Move.IN_REVIEW')

      // IN_REVIEW rather than APPROVED, and the reason is the rule in test 1 of this file: the
      // controls swap on the plan's state, so a draft plan offers no Approve button to click.
      await userEvent.click(screen.getByTestId('PlanStats.Move.IN_REVIEW'))

      await waitFor(() => expect(screen.getByTestId('PlanStats.StatusError')).toBeInTheDocument())
      expect(screen.queryByTestId('PlanStats.OverrideGate')).toBeNull()
    })
  })

  it('renders the delete refusal inside the dialog, which stays open', async () => {
    // The 409 the client cannot predict: nothing on this screen knows the plan's run count, so
    // unlike a permission this refusal has no gate that could have disabled the control.
    mockReads('ACTIVE')
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(
        409,
        '2 test runs have been executed against this plan. Close the plan instead.',
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
    expect(error).toHaveTextContent('Close the plan instead.')
    expect(screen.getByTestId('PlanStats.DeleteDialog.Confirm')).toBeInTheDocument()
  })
})
