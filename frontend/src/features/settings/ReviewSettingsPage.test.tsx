import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, ProjectSettings } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject } from '../../test/fixtures'
import { ReviewSettingsPage } from './ReviewSettingsPage'

/**
 * The project's §6g policy screen — **shipped in §77 with no test file at all**, which §82 found
 * while adding the two gating switches to it. The gap mattered: this is a `PUT` of the whole value
 * onto a server that reads an absent boolean as `false`, so the failure mode of every change to
 * this form is *silent*, and there was nothing to catch it.
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **THE test — the save carries every field.** A property listed on the type and omitted from
 *      the submit does not fail; it switches that setting OFF on the next save of this form. For a
 *      gating rule that means an admin correcting the peer-review count quietly reopens the gate,
 *      with a success message. `ui-rules` calls this the highest-severity form defect, and the
 *      assertion is written against the whole body rather than the fields §82 happened to add, so
 *      the ninth setting is covered by it before anybody writes a test for the ninth setting.
 *   2. **The author switch is stored inverted.** The wire field is `authorMayApproveOwnWork` and
 *      §6g's rule — and the control — is "the author *cannot* approve". A save that dropped the
 *      inversion would turn the setting §6g calls the single most valuable one off for everybody
 *      who ever touches this form.
 *   3. **The gating switches are not governed by the review opt-ins**, which is the whole §82
 *      decision: they are saveable and readable with review switched off everywhere.
 *   4. **Disable-don't-hide** (§31): a caller without `project:settings` sees the policy and cannot
 *      change it, rather than being sent away with a 403.
 */

const PROJECT = 'p1'

function settings(overrides: Partial<ProjectSettings> = {}): ProjectSettings {
  return {
    projectId: PROJECT,
    reviewEnabledCase: false,
    reviewEnabledSuite: false,
    reviewEnabledPlan: false,
    // B1's fourth subject (005 S9b). Off — this file is not about doc review.
    reviewEnabledDoc: false,
    requiredPeerReviews: 0,
    authorMayApproveOwnWork: false,
    editAfterApproval: 'RETAIN',
    requireApprovedCaseForRun: false,
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  }
}

/*
  Declared as `ProjectSettings` and NOT cast — §79's rule, which paid for itself in §80, in §82's
  gating pair, and again when §97 REMOVED `requireApprovedPlanToStart`: this factory is the reason
  each of those was one build error in one place rather than a page of runtime failures with zero
  type errors.
*/
function mockReads(current: ProjectSettings, permissions: PermissionKey[] = ['project:settings']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (path.includes('/settings')) return current
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/settings/review`}>
      <Routes>
        <Route path="/projects/:projectId/settings/review" element={<ReviewSettingsPage />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed — without it every "disabled" assertion below passes
 * for the wrong reason, because `useCan` is fail-closed while pending (§66).
 */
async function enabled(testId: string) {
  await waitFor(() => expect(screen.getByTestId(testId)).toBeEnabled())
}

describe('ReviewSettingsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders §6g gating as its own section, separate from where review applies', async () => {
    mockReads(settings())
    renderPage()

    expect(await screen.findByTestId('ReviewSettings.Gating')).toBeInTheDocument()
    await enabled('ReviewSettings.requireApprovedCaseForRun')
    // One switch since §97 (M7) removed `requireApprovedPlanToStart`. Asserted as an absence as
    // well, because a panel that kept rendering a control the contract has dropped would still
    // pass the enabling assertion above.
    expect(screen.queryByTestId('ReviewSettings.requireApprovedPlanToStart')).toBeNull()
  })

  it('warns while either gate is on, and says nothing while both are off', async () => {
    mockReads(settings())
    renderPage()
    await enabled('ReviewSettings.requireApprovedCaseForRun')

    expect(screen.queryByTestId('ReviewSettings.GatingWarning')).toBeNull()

    await userEvent.click(screen.getByTestId('ReviewSettings.requireApprovedCaseForRun'))

    // §6g calls gating "powerful and genuinely obstructive"; an admin turning it on must be told
    // what it stops before they save, not by the first person it blocks.
    expect(await screen.findByTestId('ReviewSettings.GatingWarning')).toBeInTheDocument()
  })

  /**
   * The one that would have caught a whole class of future defects. Asserted as an exact object,
   * deliberately: `toMatchObject` would pass with a field silently dropped, which is the failure.
   */
  it('sends EVERY setting on save — an omitted one is switched off, not left alone', async () => {
    mockReads(
      settings({
        reviewEnabledCase: true,
        reviewEnabledSuite: true,
        reviewEnabledPlan: true,
        requiredPeerReviews: 2,
        authorMayApproveOwnWork: true,
        editAfterApproval: 'RE_REVIEW',
        requireApprovedCaseForRun: true,
      }),
    )
    const put = vi.spyOn(api, 'put').mockResolvedValue(settings() as never)
    renderPage()

    // Change exactly one thing, so every OTHER field in the asserted body is one this form is
    // merely carrying — which is the property under test. It was the plan gate until §97 removed
    // it; the case gate is the only switch in this panel now.
    await enabled('ReviewSettings.requireApprovedCaseForRun')
    await userEvent.click(screen.getByTestId('ReviewSettings.requireApprovedCaseForRun'))
    await userEvent.click(screen.getByTestId('ReviewSettings.Save'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    expect(put.mock.calls[0][1]).toEqual({
      reviewEnabledCase: true,
      reviewEnabledSuite: true,
      reviewEnabledPlan: true,
      requiredPeerReviews: 2,
      authorMayApproveOwnWork: true,
      editAfterApproval: 'RE_REVIEW',
      // The one that was toggled.
      requireApprovedCaseForRun: false,
    })
  })

  it('stores the author rule inverted — the control says "cannot", the field says "may"', async () => {
    mockReads(settings({ authorMayApproveOwnWork: true }))
    const put = vi.spyOn(api, 'put').mockResolvedValue(settings() as never)
    renderPage()

    await enabled('ReviewSettings.authorCannotApprove')
    // Stored `true` means the rule is OFF, so the "author cannot approve" switch reads unchecked.
    expect(screen.getByTestId('ReviewSettings.authorCannotApprove')).not.toBeChecked()

    await userEvent.click(screen.getByTestId('ReviewSettings.authorCannotApprove'))
    await userEvent.click(screen.getByTestId('ReviewSettings.Save'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    const body = put.mock.calls[0][1] as Record<string, unknown>
    expect(body.authorMayApproveOwnWork).toBe(false)
  })

  /**
   * §82's decision, asserted rather than described. Gating reads the status ladder, which a project
   * with review switched off still has — so a client that ANDed the two would leave these controls
   * inert on exactly the projects that want them.
   */
  it('leaves gating usable with review switched off everywhere', async () => {
    mockReads(settings({ requireApprovedCaseForRun: true }))
    renderPage()

    // Review is off for all three types, and the gate is still on and still editable.
    expect(await screen.findByTestId('ReviewSettings.AllOff')).toBeInTheDocument()
    await enabled('ReviewSettings.requireApprovedCaseForRun')
    expect(screen.getByTestId('ReviewSettings.requireApprovedCaseForRun')).toBeChecked()
  })

  it('disables every control for a caller without project:settings, rather than hiding them', async () => {
    mockReads(settings(), ['project:read'])
    renderPage()

    // The gating switches render — §6g's policy is visible to anybody who can see the project,
    // because the read is gated on visibility alone and the client has to decide what to show.
    const gate = await screen.findByTestId('ReviewSettings.requireApprovedCaseForRun')
    // Paired with the enabling assertions above, or this passes with no gate at all (§66).
    await waitFor(() => expect(gate).toBeDisabled())
    expect(screen.getByTestId('ReviewSettings.Save')).toBeDisabled()
  })
})
