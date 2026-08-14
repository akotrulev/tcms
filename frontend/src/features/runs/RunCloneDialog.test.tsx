import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { PermissionKey, TestRun } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aRun } from '../../test/fixtures'
import { RunCloneDialog } from './RunCloneDialog'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * Cloning a run (§129 phase 3) — the highest-value of the three clones.
 *
 * What is pinned here, and what each assertion is standing in front of:
 *
 *   1. **An untouched release or build is OMITTED, not sent empty.** Absent means *keep the
 *      source's*; `''` is an override to nothing, which the source's own validation never
 *      accepted. This is the whole reason the two fields open empty rather than prefilled — a
 *      prefilled field a user clears would be a control whose most obvious use is discarded.
 *   2. **A typed value overrides.** The pair is what makes the empty-means-keep rule a rule rather
 *      than a dialog that ignores two inputs.
 *   3. **§6g's gate refusal offers the override, disabled rather than hidden without
 *      `case:approve`** — and the retry re-reads the form. A 409 naming an action the UI does not
 *      offer is a design bug (`ui-rules`), and this endpoint's 409 is *only* the gate.
 *   4. **`overrideApprovalGate` is absent from an ordinary clone**, or every cloned run in the
 *      product claims an override nobody made.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

function sourceRun(over: Partial<TestRun> = {}): TestRun {
  return {
    id: 'run-1',
    key: 'TR-7',
    name: 'Regression 4.3.0',
    planId: null,
    planKey: null,
    configuration: { id: 'cfg-1', name: 'Chrome / Linux', isDefault: true, attributes: {} },
    status: 'COMPLETED',
    releaseVersion: '4.3.0',
    buildNumber: '4470',
    commitSha: null,
    branch: null,
    source: 'MANUAL',
    startedAt: '2026-08-01T09:00:00Z',
    completedAt: '2026-08-01T17:00:00Z',
    counts: { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 0 },
    ...over,
  }
}

function mockPermissions(held: PermissionKey[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/me/permissions')) return heldPermissions(held, {}, PROJECT)
    return []
  }) as never)
}

/** §6g's refusal as `RunController.gatedProblem` sends it — a 409 with no `errors` map. */
function gateRefusal() {
  return new ApiError(
    409,
    'This project only runs approved test cases. 2 of the 12 in this run are not approved: ' +
      'TC-4, TC-9.',
    null,
    null,
    null,
    'This run needs approved cases',
  )
}

function renderDialog(run: TestRun = sourceRun(), onCloned = vi.fn()) {
  render(
    <TestProviders client={createTestQueryClient()}>
      <RunCloneDialog
        projectId={PROJECT}
        run={run}
        caseCount={12}
        open
        onClose={() => {}}
        onCloned={onCloned}
      />
    </TestProviders>,
  )
  return onCloned
}

describe('RunCloneDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockPermissions()
  })

  it('opens with the name suggested and the release and build EMPTY, naming what is kept', async () => {
    renderDialog()

    expect(await screen.findByTestId('RunDetail.Clone.Name')).toHaveValue('Copy of Regression 4.3.0')
    expect(screen.getByTestId('RunDetail.Clone.Release')).toHaveValue('')
    // The source's value is on screen as the consequence of leaving it alone, which is the only
    // way an empty field can mean "keep 4.3.0" to a reader.
    expect(screen.getByText('Leave empty to keep 4.3.0.')).toBeInTheDocument()
    expect(screen.getByText('Leave empty to keep 4470.')).toBeInTheDocument()
  })

  it('omits an untouched release and build rather than sending empty strings', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(aRun({ id: 'run-2', key: 'TR-8' }) as never)
    const onCloned = renderDialog()

    await screen.findByTestId('RunDetail.Clone.Name')
    await userEvent.click(screen.getByTestId('RunDetail.Clone.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/projects/${PROJECT}/runs/run-1/clone`)
    expect(body).toEqual({ name: 'Copy of Regression 4.3.0' })
    await waitFor(() => expect(onCloned).toHaveBeenCalled())
  })

  it('sends the release and build that were typed', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(aRun({ id: 'run-2' }) as never)
    renderDialog()

    await userEvent.type(await screen.findByTestId('RunDetail.Clone.Release'), '4.3.1')
    await userEvent.type(screen.getByTestId('RunDetail.Clone.Build'), '4471')
    await userEvent.click(screen.getByTestId('RunDetail.Clone.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(body.releaseVersion).toBe('4.3.1')
    expect(body.buildNumber).toBe('4471')
  })

  it('states that the copied checklist is frozen at the source’s cases', async () => {
    renderDialog()
    expect(await screen.findByText(/are not picked up/i)).toBeInTheDocument()
  })

  describe('§6g gating', () => {
    it('does not send overrideApprovalGate on an ordinary clone', async () => {
      mockPermissions(['case:approve'])
      const post = vi.spyOn(api, 'post').mockResolvedValue(aRun({ id: 'run-2' }) as never)
      renderDialog()

      await screen.findByTestId('RunDetail.Clone.Name')
      await userEvent.click(screen.getByTestId('RunDetail.Clone.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      const [, body] = post.mock.calls[0] as [string, Record<string, unknown>]
      expect(body).not.toHaveProperty('overrideApprovalGate')
    })

    it('offers the override to a caller holding case:approve, and retries with the flag', async () => {
      // The gate's refusal is the subject: it is the thing that raises the override.
      drivingAFailedWrite()
      mockPermissions(['case:approve'])
      const post = vi
        .spyOn(api, 'post')
        .mockRejectedValueOnce(gateRefusal())
        .mockResolvedValue(aRun({ id: 'run-2' }) as never)
      const onCloned = renderDialog()

      await screen.findByTestId('RunDetail.Clone.Name')
      await userEvent.click(screen.getByTestId('RunDetail.Clone.Submit'))

      expect(await screen.findByTestId('RunDetail.Clone.Error')).toHaveTextContent('TC-4, TC-9')
      // Re-queried inside `waitFor`: `PermissionGate` swaps the DOM node between its branches, so a
      // reference held from before the gate resolved never becomes enabled (§64's harness trap).
      await waitFor(() => expect(screen.getByTestId('RunDetail.Clone.OverrideGate')).toBeEnabled())
      await userEvent.click(screen.getByTestId('RunDetail.Clone.OverrideGate'))

      await waitFor(() => expect(post).toHaveBeenCalledTimes(2))
      const [, retried] = post.mock.calls[1] as [string, Record<string, unknown>]
      expect(retried.overrideApprovalGate).toBe(true)
      expect(retried.name).toBe('Copy of Regression 4.3.0')
      await waitFor(() => expect(onCloned).toHaveBeenCalled())
    })

    it('disables the override for a caller without case:approve rather than hiding it', async () => {
      // The gate's refusal is the subject: it is what puts the disabled override on screen.
      drivingAFailedWrite()
      mockPermissions(['run:create'])
      vi.spyOn(api, 'post').mockRejectedValue(gateRefusal())
      renderDialog()

      await screen.findByTestId('RunDetail.Clone.Name')
      await userEvent.click(screen.getByTestId('RunDetail.Clone.Submit'))

      // Present — §31, so the way out is discoverable by whoever has to go and ask for it. Paired
      // with the enabling assertion above, or this passes with no gate at all (`useCan` is
      // fail-closed while pending, §66).
      expect(await screen.findByTestId('RunDetail.Clone.OverrideGate')).toBeInTheDocument()
      await waitFor(() => expect(screen.getByTestId('RunDetail.Clone.OverrideGate')).toBeDisabled())
    })
  })
})
