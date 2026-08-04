import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { RunCreateDialog } from './RunCreateDialog'

/**
 * Starting a run — the dialog that materialises a checklist.
 *
 * Three behaviours are locked here, and all three are invisible to `tsc` and to oxlint:
 *
 *   1. **A project with no plans cannot start a run**, and says why rather than presenting an
 *      empty select. The server refuses an empty scope with a 400; showing the reason before the
 *      click is the difference between an explanation and a rejection.
 *   2. **A field error with no control lands in the banner.** The server answers `caseIds`,
 *      `configurationId` and `assigneeId` — none of which this form has an input for — so a naive
 *      "map errors onto fields" drops them silently and the user watches a submit do nothing. That
 *      is the failure this whole error path exists to prevent, and it is a pure branch nothing
 *      else exercises.
 *   3. **The body is the contract's.** `planId` is sent, `configurationId` is null (meaning "the
 *      project's default"), and the case list is empty so the server resolves the plan's scope.
 *      Sending `caseIds: [plan's cases]` from here would look identical on screen and would pin
 *      the run to a scope resolved by the client at a different instant.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const plans = [
  {
    id: 'plan-1',
    key: 'TP-1',
    name: 'Release 4.3',
    status: 'ACTIVE',
    milestoneName: 'GA',
    milestoneDate: '2026-09-01',
    caseCount: 27,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    currentVersionNo: 1,
    lockVersion: 1,
  },
]

function mockPlans(rows: unknown[], held: string[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/plans')) return rows
    // §82. Answered explicitly rather than through the `[]` fallthrough, because the override
    // affordance is `PermissionGate`d: a bare `[]` reads as "holds nothing", which is a real
    // persona (the engineer) but makes the enabled case unreachable — and a gate asserted only in
    // its disabled state passes with no gate in the code at all (§66).
    if (path.includes('/me/permissions')) return heldPermissions(held, {}, PROJECT)
    return []
  }) as unknown as typeof api.get)
}

/** §6g's gating refusal, as `RunController.gatedProblem` sends it — a 409 with no `errors` map. */
function gateRefusal() {
  return new ApiError(
    409,
    'This project only runs approved test cases. 2 of the 2 in this run are not approved: ' +
      'TC-4, TC-9.',
    null,
    null,
    null,
    'This run needs approved cases',
  )
}

/**
 * MUI's `select` TextField is a listbox behind a button, not a native `<select>`, so
 * `selectOptions` cannot drive it — the value reaches react-hook-form through the hidden input
 * MUI updates when an option is clicked. Opening the popover is the only way to set it the way a
 * user does, and a test that faked the value would not exercise the registration at all.
 */
async function choosePlan() {
  await userEvent.click(screen.getByRole('combobox', { name: /plan/i }))
  await userEvent.click(await screen.findByTestId('Runs.Create.Plan.TP-1'))
}

describe('RunCreateDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('refuses to start a run in a project with no plans, and says why', async () => {
    mockPlans([])
    const post = vi.spyOn(api, 'post')

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    expect(await screen.findByTestId('Runs.Create.NoPlans')).toBeInTheDocument()
    expect(screen.getByTestId('Runs.Create.Submit')).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  })

  it('sends planId, a null configurationId and no caseIds — the server resolves the scope', async () => {
    mockPlans(plans)
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'run-1', key: 'TR-1' } as never)
    const onCreated = vi.fn()

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={onCreated} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Regression 4.3.0')
    await userEvent.type(screen.getByTestId('Runs.Create.Release'), '4.3.0')

    await choosePlan()

    await userEvent.click(screen.getByTestId('Runs.Create.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.name).toBe('Regression 4.3.0')
    expect(body.releaseVersion).toBe('4.3.0')
    expect(body.configurationId).toBeNull()
    expect(body.caseIds).toEqual([])
  })

  it('puts a field error with no input — caseIds — in the form-level banner', async () => {
    mockPlans(plans)
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        caseIds: ['This run would have nothing to execute.'],
      }),
    )

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Regression')
    await choosePlan()
    await userEvent.click(screen.getByTestId('Runs.Create.Submit'))

    // Dropped rather than shown, the user sees a submit that did nothing at all.
    expect(await screen.findByTestId('Runs.Create.Error')).toHaveTextContent(
      'This run would have nothing to execute.',
    )
  })

  it('lands a name error on the name input, not in the banner', async () => {
    mockPlans(plans)
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        name: ['Enter a run name.'],
      }),
    )

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Regression')
    await choosePlan()
    await userEvent.click(screen.getByTestId('Runs.Create.Submit'))

    expect(await screen.findByText('Enter a run name.')).toBeInTheDocument()
    expect(screen.queryByTestId('Runs.Create.Error')).not.toBeInTheDocument()
  })

  /**
   * §6g's gating rule and its lead override (§82).
   *
   * The failures these catch are all invisible to `tsc` and to the runner without them:
   *
   *   1. **A 409 that offers no way out.** The refusal names the unapproved cases and there is a
   *      documented override for exactly this situation; an error naming an action the UI does not
   *      offer is a design bug (`ui-rules`), and nothing else in this file exercises a 409 at all.
   *   2. **An override the caller cannot use, hidden rather than disabled.** §31's rule and the
   *      reason it matters here: a Test Engineer needs to learn that the way forward exists and is
   *      somebody else's to take.
   *   3. **The retry sending the same body as the first attempt** — which would produce the same
   *      409 and read as a dead button.
   *   4. **The flag leaking onto an ordinary create.** It must be absent unless the override was
   *      taken, or every run in the product claims an override it did not make.
   */
  describe('§6g gating', () => {
    async function fillAndSubmit() {
      await screen.findByTestId('Runs.Create.Name')
      await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Hotfix 4.3.1')
      await choosePlan()
      await userEvent.click(screen.getByTestId('Runs.Create.Submit'))
    }

    it('does not send overrideApprovalGate on an ordinary create', async () => {
      mockPlans(plans, ['case:approve'])
      const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'run-1' } as never)

      render(
        <TestProviders client={createTestQueryClient()}>
          <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
        </TestProviders>,
      )
      await fillAndSubmit()

      await waitFor(() => expect(post).toHaveBeenCalled())
      const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
      expect(body).not.toHaveProperty('overrideApprovalGate')
    })

    it('shows the refusal and offers the override to a caller holding case:approve', async () => {
      mockPlans(plans, ['case:approve'])
      vi.spyOn(api, 'post').mockRejectedValue(gateRefusal())

      render(
        <TestProviders client={createTestQueryClient()}>
          <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
        </TestProviders>,
      )
      await fillAndSubmit()

      expect(await screen.findByTestId('Runs.Create.Error')).toHaveTextContent('TC-4, TC-9')
      // Re-queried inside waitFor: `PermissionGate` swaps the DOM node between its denied
      // (tooltip-wrapped) and allowed branches, so a reference held from before the gate resolved
      // never becomes enabled (§64's harness trap).
      await waitFor(() =>
        expect(screen.getByTestId('Runs.Create.OverrideGate')).toBeEnabled(),
      )
    })

    it('disables the override for a caller without case:approve, rather than hiding it', async () => {
      mockPlans(plans, ['run:create'])
      vi.spyOn(api, 'post').mockRejectedValue(gateRefusal())

      render(
        <TestProviders client={createTestQueryClient()}>
          <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
        </TestProviders>,
      )
      await fillAndSubmit()

      // Present — §31, so the way out is discoverable by the person who has to go and ask for it.
      expect(await screen.findByTestId('Runs.Create.OverrideGate')).toBeInTheDocument()
      // Paired with the enabling assertion in the test above, or this passes with no gate at all:
      // `useCan` is fail-closed while pending (§66).
      await waitFor(() =>
        expect(screen.getByTestId('Runs.Create.OverrideGate')).toBeDisabled(),
      )
    })

    it('retries with the flag and the CURRENT form values, and creates the run', async () => {
      mockPlans(plans, ['case:approve'])
      const post = vi
        .spyOn(api, 'post')
        .mockRejectedValueOnce(gateRefusal())
        .mockResolvedValue({ id: 'run-1' } as never)
      const onCreated = vi.fn()

      render(
        <TestProviders client={createTestQueryClient()}>
          <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={onCreated} />
        </TestProviders>,
      )
      await fillAndSubmit()

      await waitFor(() =>
        expect(screen.getByTestId('Runs.Create.OverrideGate')).toBeEnabled(),
      )
      await userEvent.click(screen.getByTestId('Runs.Create.OverrideGate'))

      await waitFor(() => expect(post).toHaveBeenCalledTimes(2))
      const [, retried] = post.mock.calls[1] as [string, Record<string, unknown>]
      expect(retried.overrideApprovalGate).toBe(true)
      // The name is re-read from the form rather than replayed, so an edit between the refusal and
      // the override is the run that gets created.
      expect(retried.name).toBe('Hotfix 4.3.1')
      await waitFor(() => expect(onCreated).toHaveBeenCalled())
    })
  })
})
