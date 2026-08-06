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
 * Four behaviours are locked here, and all four are invisible to `tsc` and to oxlint:
 *
 *   1. ~~**A project with no plans cannot start a run**, and says why rather than presenting an
 *      empty select.~~ → **§99 inverts this.** A run is scoped by a plan *or* by suites, tags and
 *      cases, so a project with no plans is the ordinary first caller rather than a dead end. What
 *      is asserted now is that the submit is **live** there — the previous assertion
 *      (`toBeDisabled`) is exactly what a client that never grew the arms would still pass.
 *   2. **A field error with no control lands in the banner.** The server answers
 *      `configurationId` and `assigneeId` — neither of which this form has an input for — so a
 *      naive "map errors onto fields" drops them silently and the user watches a submit do nothing.
 *      `caseIds` moved OUT of that set in §99 by gaining a control, which is the point of the pair
 *      of tests below: one error that must land on a control, one that must not.
 *   3. **The body is the contract's.** `planId` is sent, `configurationId` is null (meaning "the
 *      project's default"), and the three scope arms are empty so the server resolves the plan's
 *      scope. Sending `caseIds: [plan's cases]` from here would look identical on screen and would
 *      pin the run to a scope resolved by the client at a different instant.
 *   4. **The arms are sent, and they replace the plan's scope** — §99. The client's job is to say
 *      what was chosen; resolving it is the server's, and a dialog that resolved locally would
 *      produce a checklist assembled at a different instant from the one the executions pin.
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

/** §99's scope arms, as their endpoints answer them. */
const suites = [
  { id: 'suite-1', key: 'TS-1', name: 'Payments', caseCount: 12 },
  { id: 'suite-2', key: 'TS-2', name: 'Refunds', caseCount: 4 },
]
const tagUsage = [
  { tag: 'smoke', caseCount: 9 },
  { tag: 'payments', caseCount: 3 },
]
const caseRows = [
  { id: 'case-1', key: 'TC-1', title: 'Refund the whole amount' },
  { id: 'case-2', key: 'TC-2', title: 'Partial refund rounds' },
]

function mockPlans(rows: unknown[], held: string[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/plans')) return rows
    // §82. Answered explicitly rather than through the `[]` fallthrough, because the override
    // affordance is `PermissionGate`d: a bare `[]` reads as "holds nothing", which is a real
    // persona (the engineer) but makes the enabled case unreachable — and a gate asserted only in
    // its disabled state passes with no gate in the code at all (§66).
    if (path.includes('/me/permissions')) return heldPermissions(held, {}, PROJECT)
    if (path.includes('/suites')) return suites
    if (path.includes('/tags')) return tagUsage
    if (path.includes('/cases')) return { items: caseRows, page: 0, size: 200, total: 2 }
    return []
  }) as unknown as typeof api.get)
}

/** Picks one option out of an MUI `Autocomplete` the way a user does — type, then click. */
async function chooseFrom(testId: string, label: string) {
  await userEvent.type(screen.getByTestId(testId), label.slice(0, 4))
  await userEvent.click(await screen.findByRole('option', { name: new RegExp(label, 'i') }))
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

  it('a project with no plans can still start a run — §99 points the note the other way', async () => {
    mockPlans([])

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    expect(await screen.findByTestId('Runs.Create.NoPlans')).toHaveTextContent(
      /suites, tags or cases/i,
    )
    // The barrier assertion. `toBeDisabled()` was the old one and it passes against a dialog with
    // no scope arms at all, so it would have gone on passing after §99 while the feature was
    // unreachable in exactly the project that needs it (§66's fail-closed lesson, inverted).
    expect(screen.getByTestId('Runs.Create.Submit')).toBeEnabled()
  })

  it('sends the three arms, and the plan is still recorded beside them', async () => {
    mockPlans(plans)
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'run-1', key: 'TR-1' } as never)

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Smoke, build 4471')
    await choosePlan()
    await chooseFrom('Runs.Create.Suites', 'Payments')
    await chooseFrom('Runs.Create.Tags', 'smoke')
    await chooseFrom('Runs.Create.Cases', 'TC-2')
    await userEvent.click(screen.getByTestId('Runs.Create.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.suiteIds).toEqual(['suite-1'])
    expect(body.tags).toEqual(['smoke'])
    expect(body.caseIds).toEqual(['case-2'])
    // The plan travels as the association even though its scope is not used — which is what makes
    // the run readable later as "against TP-1, this subset" rather than as an orphan.
    expect(body.planId).toBe('plan-1')
  })

  it('says the plan’s scope is not used once an arm is chosen', async () => {
    mockPlans(plans)

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await choosePlan()
    // Both directions, because the sentence that is always there teaches nothing and the one that
    // is never there is a rule the user meets as a surprise.
    expect(screen.getByText(/becomes a row to record against/i)).toBeInTheDocument()

    await chooseFrom('Runs.Create.Tags', 'smoke')
    expect(screen.getByText(/scope is not used/i)).toBeInTheDocument()
  })

  it('refuses a submit that names no scope at all, without asking the server', async () => {
    mockPlans(plans)
    const post = vi.spyOn(api, 'post')

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Nothing chosen')
    await userEvent.click(screen.getByTestId('Runs.Create.Submit'))

    expect(
      await screen.findByText(/Choose a plan, or the cases, suites or tags/i),
    ).toBeInTheDocument()
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
    // All three arms empty, so the server resolves the plan's scope — the substitution rule read
    // from the other end.
    expect(body.caseIds).toEqual([])
    expect(body.suiteIds).toEqual([])
    expect(body.tags).toEqual([])
  })

  it('lands a scope error on its own control — §99 gave caseIds and tags a place to go', async () => {
    mockPlans(plans)
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        tags: ['This run would have nothing to execute.'],
      }),
    )

    render(
      <TestProviders client={createTestQueryClient()}>
        <RunCreateDialog projectId={PROJECT} open onClose={() => {}} onCreated={() => {}} />
      </TestProviders>,
    )

    await screen.findByTestId('Runs.Create.Name')
    await userEvent.type(screen.getByTestId('Runs.Create.Name'), 'Regression')
    await chooseFrom('Runs.Create.Tags', 'smoke')
    await userEvent.click(screen.getByTestId('Runs.Create.Submit'))

    // On the control and NOT in the banner: the server names every arm the caller used, and a
    // banner would collapse "your tags matched nothing" into one line beside three live controls.
    expect(
      await screen.findByText('This run would have nothing to execute.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('Runs.Create.Error')).not.toBeInTheDocument()
  })

  it('puts a field error with no input — configurationId — in the form-level banner', async () => {
    mockPlans(plans)
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        configurationId: ['That configuration is not in this project.'],
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
      'That configuration is not in this project.',
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
