import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { PermissionKey, RunSchedule } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { aProject, aRunSchedule } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { RunSchedulePage } from './RunSchedulePage'

/**
 * The run-schedule screen (§247).
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **`lastFireError` is rendered.** It is the only place the poller's reason for making no run
 *      is ever visible, and a schedule that stops silently is the failure mode of scheduling
 *      anything. A row that dropped it would look identical to a healthy one.
 *   2. **`nextFireAt === null` renders a sentence, never a blank cell.** Null is a real answer; an
 *      empty cell reads as "loading" or "soon", both false.
 *   3. **Disable-don't-hide (§31).** A Viewer sees the whole list with the controls disabled and
 *      explained, rather than being sent away.
 *   4. **The save states the WHOLE schedule.** The `PUT` is a total replacement and the server reads
 *      an omission as a clear, so a field this form has no control for — `assigneeId`,
 *      `configurationId`, the id arms of the scope — must still be sent back. §244's rule, on a
 *      screen that shows fewer fields than the body carries.
 *
 * Every body crossing the double is compared against `openapi.yaml` by `test/contractDoubles.ts`,
 * in both directions, without this file opting in.
 */

const PROJECT = 'p1'

function mockReads(
  schedules: RunSchedule[],
  permissions: PermissionKey[] = ['run:create', 'project:read'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    // Anchored so the collection arm does not swallow the item — an unanchored `includes`
    // would serve the list body to `GET /run-schedules/{id}` and the shapes differ.
    if (/^\/projects\/[^/]+\/run-schedules$/.test(path)) return schedules
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

function renderPage(route = `/projects/${PROJECT}/run-schedules`) {
  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <Routes>
        <Route path="/projects/:projectId/run-schedules" element={<RunSchedulePage />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed.
 *
 * Without it every "enabled" assertion below passes for the wrong reason: `useCan` is fail-closed
 * while pending (§66), so a control is disabled during the load whatever the caller holds.
 */
async function settled(testId: string) {
  await waitFor(() => expect(screen.getByTestId(testId)).toBeEnabled())
}

describe('RunSchedulePage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the empty state when the project has no schedules', async () => {
    mockReads([])
    renderPage()

    expect(await screen.findByTestId('RunSchedules.Region.Empty')).toBeInTheDocument()
  })

  it('renders a schedule with its cadence and zone', async () => {
    mockReads([aRunSchedule()])
    renderPage()

    const row = await screen.findByTestId('RunSchedules.Row')
    expect(within(row).getByText('Nightly smoke')).toBeInTheDocument()
    expect(within(row).getByText('0 0 3 * * MON-FRI')).toBeInTheDocument()
    expect(within(row).getByText('Europe/Sofia')).toBeInTheDocument()
  })

  /** The feature's honesty, and the assertion that keeps it. */
  it('shows the last fire error prominently', async () => {
    mockReads([
      aRunSchedule({
        lastFireError: 'This project is archived, so the schedule did not run.',
      }),
    ])
    renderPage()

    const error = await screen.findByTestId('RunSchedules.Row.Error')
    expect(error).toHaveTextContent('This project is archived')
  })

  it('says "Will not fire again" rather than leaving the cell blank', async () => {
    mockReads([aRunSchedule({ nextFireAt: null })])
    renderPage()

    const cell = await screen.findByTestId('RunSchedules.Row.NextFire')
    expect(cell).toHaveTextContent('Will not fire again')
  })

  it('shows a paused schedule as paused', async () => {
    mockReads([aRunSchedule({ enabled: false })])
    renderPage()

    expect(await screen.findByTestId('RunSchedules.Row.Paused')).toBeInTheDocument()
  })

  /** §31 — the control is present and refused, not absent. */
  it('disables the write controls for a caller without run:create', async () => {
    mockReads([aRunSchedule()], ['project:read'])
    renderPage()

    const create = await screen.findByTestId('RunSchedules.New')
    await waitFor(() => expect(create).toBeDisabled())

    expect(screen.getByTestId('RunSchedules.Row.Edit')).toBeDisabled()
    expect(screen.getByTestId('RunSchedules.Row.Delete')).toBeDisabled()
  })

  it('keeps the enabled-only filter in the URL, not in component state', async () => {
    mockReads([aRunSchedule({ id: 'rs-1', name: 'Active' }), aRunSchedule({
      id: 'rs-2',
      name: 'Paused',
      enabled: false,
    })])
    renderPage(`/projects/${PROJECT}/run-schedules?enabled=true`)

    await waitFor(() => expect(screen.getAllByTestId('RunSchedules.Row')).toHaveLength(1))
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.queryByText('Paused')).not.toBeInTheDocument()
  })

  /**
   * <strong>THE test.</strong> The `PUT` states the whole schedule and the server reads an omission
   * as a clear, so a field this form has no control for must still be sent back. Asserted against
   * the whole body rather than against the fields that happen to be off-screen today, so the next
   * property added to the schema is covered before anybody writes a test for it.
   */
  it('sends every field on a save, including the ones it shows no control for', async () => {
    const stored = aRunSchedule({
      assigneeId: 'user-9',
      assigneeName: 'A. Ana',
      configurationId: 'cfg-1',
      scope: { planId: 'plan-1', caseIds: ['c1'], suiteIds: ['s1'], tags: ['smoke'] },
    })
    mockReads([stored])
    const put = vi.spyOn(api, 'put').mockResolvedValue(stored as never)
    renderPage()

    await settled('RunSchedules.Row.Edit')
    await userEvent.click(screen.getByTestId('RunSchedules.Row.Edit'))
    await userEvent.click(await screen.findByTestId('RunSchedules.Dialog.Save'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    expect(put.mock.calls[0][1]).toEqual({
      name: 'Nightly smoke',
      cronExpression: '0 0 3 * * MON-FRI',
      zoneId: 'Europe/Sofia',
      scope: { planId: 'plan-1', caseIds: ['c1'], suiteIds: ['s1'], tags: ['smoke'] },
      configurationId: 'cfg-1',
      assigneeId: 'user-9',
      enabled: true,
    })
  })

  it('creates a schedule and invalidates the list', async () => {
    mockReads([])
    const post = vi.spyOn(api, 'post').mockResolvedValue(aRunSchedule() as never)
    renderPage()

    await settled('RunSchedules.New')
    await userEvent.click(screen.getByTestId('RunSchedules.New'))

    await userEvent.type(await screen.findByTestId('RunSchedules.Dialog.Name'), 'Nightly smoke')
    await userEvent.type(screen.getByTestId('RunSchedules.Dialog.Tags'), 'smoke')
    await userEvent.click(screen.getByTestId('RunSchedules.Dialog.Save'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/run-schedules`)
    expect(post.mock.calls[0][1]).toMatchObject({
      name: 'Nightly smoke',
      scope: { planId: null, caseIds: [], suiteIds: [], tags: ['smoke'] },
    })
    // No Idempotency-Key: the operation declares none, because V68 has no idempotency column and
    // a header the server does not read is §205's defect arriving from this side.
    expect(post.mock.calls[0][2]).toBeUndefined()
  })

  it('lands a 400 on the control that caused it', async () => {
    // The refusal IS this arm's subject — it asserts the field error lands on `cronExpression`
    // rather than in the corner — so the failed write is declared rather than left to look like
    // an unmocked endpoint. `renderFailures` fails the test otherwise, which is what it is for.
    drivingAFailedWrite()
    mockReads([])
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        cronExpression: ['That is not a schedule this server can read.'],
      }),
    )
    renderPage()

    await settled('RunSchedules.New')
    await userEvent.click(screen.getByTestId('RunSchedules.New'))
    await userEvent.type(await screen.findByTestId('RunSchedules.Dialog.Name'), 'Nightly')
    await userEvent.click(screen.getByTestId('RunSchedules.Dialog.Save'))

    expect(
      await screen.findByText('That is not a schedule this server can read.'),
    ).toBeInTheDocument()
  })

  it('deletes a schedule', async () => {
    mockReads([aRunSchedule()])
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    await settled('RunSchedules.Row.Delete')
    await userEvent.click(screen.getByTestId('RunSchedules.Row.Delete'))

    await waitFor(() => expect(del).toHaveBeenCalledWith(
      `/projects/${PROJECT}/run-schedules/rs-1`,
    ))
  })
})
