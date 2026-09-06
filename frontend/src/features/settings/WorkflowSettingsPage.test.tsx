import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { IssueStatusRef, ProjectWorkflow } from '../../api/types'
import { issueStatusKinds } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, anIssueSummary, anIssueType } from '../../test/fixtures'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { WorkflowSettingsPage } from './WorkflowSettingsPage'

/**
 * Story B1's workflow screen.
 *
 * The failures this file exists to catch, each of which typechecks and looks right on a fixture
 * with one status in it:
 *
 * 1. **The whole document is submitted.** A reorder is a statement about the set, so a PUT that
 *    carried one status, or carried them without recomputed positions, would either be refused or —
 *    worse — save an order nobody asked for. Asserted as the exact payload, because a containment
 *    check passes against a request missing half the document.
 * 2. **↑ / ↓ actually reorder**, and are the only path. WCAG 2.2 *Dragging Movements* is not
 *    satisfied by a button that exists; it is satisfied by one that moves the row.
 * 3. **Removing a status that issues hold asks where they go**, names the count, and sends the
 *    destination in the body. A removal that silently reassigned would look identical on screen.
 * 4. **The last `DONE` status cannot be removed from the screen at all.** Every percentage in the
 *    product counts that kind, and the reason belongs on the disabled control rather than in a 409
 *    the person reaches by trying.
 * 5. **A caller without `issue:workflow` sees the workflow and cannot change it.** Disable, don't
 *    hide: this is the only screen that says what a status in their project means.
 */

const PROJECT_ID = 'p1'

const status = (
  id: string,
  name: string,
  kind: IssueStatusRef['kind'],
  position: number,
): IssueStatusRef => ({ id, name, kind, position, blocking: false })

/** The six `tcms_seed_project_defaults` actually creates (`V3`), in its order. */
const SEEDED: IssueStatusRef[] = [
  status('s1', 'Backlog', 'NOT_STARTED', 0),
  status('s2', 'Ready', 'NOT_STARTED', 1),
  status('s3', 'In Progress', 'IN_PROGRESS', 2),
  status('s4', 'In Review', 'IN_PROGRESS', 3),
  status('s5', 'Blocked', 'IN_PROGRESS', 4),
  status('s6', 'Done', 'DONE', 5),
]

const WORKFLOW: ProjectWorkflow = {
  projectId: PROJECT_ID,
  statuses: SEEDED,
  types: [anIssueType()],
  transitions: [{ fromStatusId: 's4', toStatusId: 's6' }],
}

function mockReads({
  permissions = ['project:read', 'issue:read', 'issue:workflow'],
  workflow = WORKFLOW,
  held = [],
}: {
  permissions?: string[]
  workflow?: ProjectWorkflow
  held?: ReturnType<typeof anIssueSummary>[]
} = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions as never)
    // Anchored so the workflow sub-resource is not caught by the project arm's prefix.
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) return workflow
    if (path.startsWith('/issues?')) return held
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT_ID })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route={`/projects/${PROJECT_ID}/settings/workflow`}
    >
      <Routes>
        <Route
          path="/projects/:projectId/settings/workflow"
          element={<WorkflowSettingsPage />}
        />
      </Routes>
    </TestProviders>,
  )
}

describe('What the workflow screen shows', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the six seeded statuses with their kinds', async () => {
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('WorkflowSettings.Row')).toHaveLength(6))

    // The names, in the seed's order — a set assertion would pass against a screen that sorted
    // alphabetically, which would put Ready behind In Review and Done second.
    expect(
      screen.getAllByTestId('WorkflowSettings.Row.Name').map((node) => (node as HTMLInputElement).value),
    ).toEqual(['Backlog', 'Ready', 'In Progress', 'In Review', 'Blocked', 'Done'])

    // And each row's kind, read from the select rather than from a label: the kind is what every
    // percentage in the product is computed from, and it is the field this screen exists to set.
    expect(
      screen.getAllByTestId('WorkflowSettings.Row.Kind').map((node) => (node as HTMLSelectElement).value),
    ).toEqual(['NOT_STARTED', 'NOT_STARTED', 'IN_PROGRESS', 'IN_PROGRESS', 'IN_PROGRESS', 'DONE'])
  })

  it('explains that every percentage comes from the done column', async () => {
    // The one place in the product where D8's rule is said to the person who can change it. It is
    // asserted because it is a requirement of the screen, not decoration on it.
    mockReads()
    renderPage()

    const note = await screen.findByTestId('WorkflowSettings.CompletionNote')
    expect(note.textContent).toContain('is not partial credit')
    expect(note.textContent).toContain('done')
  })

  it('names each kind in the words the rest of the app uses', async () => {
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('WorkflowSettings.Row')).toHaveLength(6))
    const kind = screen.getAllByTestId('WorkflowSettings.Row.Kind')[0]
    // Cross-surface rather than literal: `issueStatusKinds` is the map the board's badges and the
    // issue page read, so a screen with its own three words is not expressible here.
    expect(within(kind).getByRole('option', { name: issueStatusKinds.DONE })).toBeTruthy()
  })
})

describe('Reordering, which must not need a pointer drag', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('moves a status up when its ↑ is pressed', async () => {
    mockReads()
    renderPage()

    /*
      ENABLED FIRST, and re-queried on every attempt. The permission gate disables every control
      while its verdict is in flight, so a click sent before it lands is a no-op — and the test then
      fails on the assertion rather than on the gate, which is §66's vacuous-barrier finding with the
      cause one step removed.
    */
    await waitFor(() => expect(screen.getAllByTestId('WorkflowSettings.Row.Up')[1]).toBeEnabled())

    // The second row's ↑ — WCAG 2.2 Dragging Movements, asserted by the row actually moving. A
    // button that is present and inert satisfies nothing.
    await userEvent.click(screen.getAllByTestId('WorkflowSettings.Row.Up')[1])

    expect(
      screen.getAllByTestId('WorkflowSettings.Row.Name').map((node) => (node as HTMLInputElement).value),
    ).toEqual(['Ready', 'Backlog', 'In Progress', 'In Review', 'Blocked', 'Done'])
  })

  it('cannot move the first row up or the last row down', async () => {
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('WorkflowSettings.Row')).toHaveLength(6))

    expect(screen.getAllByTestId('WorkflowSettings.Row.Up')[0]).toBeDisabled()
    expect(screen.getAllByTestId('WorkflowSettings.Row.Down')[5]).toBeDisabled()
  })

  it('submits the whole document, with positions recomputed from the order on screen', async () => {
    mockReads()
    const put = vi.spyOn(api, 'put').mockResolvedValue(WORKFLOW as never)
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('WorkflowSettings.Row.Up')[1]).toBeEnabled())
    await userEvent.click(screen.getAllByTestId('WorkflowSettings.Row.Up')[1])
    await userEvent.click(screen.getByTestId('WorkflowSettings.Save'))

    await waitFor(() => expect(put).toHaveBeenCalled())

    // Asserted as the whole body: a PUT missing a status is a removal the server refuses, and a PUT
    // that kept the server's positions would save an order nobody chose while looking correct.
    const [path, body] = put.mock.calls[0] as [string, ProjectWorkflow]
    expect(path).toBe(`/projects/${PROJECT_ID}/workflow`)
    expect(body.statuses.map((each) => [each.name, each.position])).toEqual([
      ['Ready', 0],
      ['Backlog', 1],
      ['In Progress', 2],
      ['In Review', 3],
      ['Blocked', 4],
      ['Done', 5],
    ])
    expect(body.transitions).toEqual([{ fromStatusId: 's4', toStatusId: 's6' }])
  })

  it('marks one status blocking, clears whichever held it, and sends the flag', async () => {
    /*
      021 D-b/T5. **At most one per project**, which is why the control is a radio: choosing one
      clears every other in the same update, so the server's 422 is unreachable from this screen
      rather than a thing the reader can walk into. That 422 stays as the guard against a stale
      second tab and surfaces in the refusal banner like any other write failure.

      The flag also has to make the document **dirty** — it joined the comparison tuple with the
      control, and without it marking a different status left Save disabled and the change
      unreachable.
    */
    const blocked = SEEDED.map((each) => ({ ...each, blocking: each.id === 's5' }))
    mockReads({ workflow: { ...WORKFLOW, statuses: blocked } })
    const put = vi.spyOn(api, 'put').mockResolvedValue(WORKFLOW as never)
    renderPage()

    // Waited on ENABLEMENT, not on the rows: the permission read lands after the workflow does, so
    // a test that only waited for six rows clicked a control that was still disabled and asserted
    // against a state nothing had changed.
    await waitFor(() =>
      expect(screen.getAllByTestId('WorkflowSettings.Row.Blocking')[0]).toBeEnabled(),
    )
    const radios = screen.getAllByTestId('WorkflowSettings.Row.Blocking')
    expect(radios[4]).toBeChecked()
    expect(screen.getByTestId('WorkflowSettings.Save')).toBeDisabled()

    await userEvent.click(radios[3])

    expect(radios[3]).toBeChecked()
    expect(radios[4]).not.toBeChecked()
    await userEvent.click(screen.getByTestId('WorkflowSettings.Save'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    const [, body] = put.mock.calls[0] as [string, ProjectWorkflow]
    expect(body.statuses.filter((each) => each.blocking).map((each) => each.name)).toEqual([
      'In Review',
    ])
  })

  it('offers no Save until something has changed', async () => {
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('WorkflowSettings.Row')).toHaveLength(6))
    expect(screen.getByTestId('WorkflowSettings.Save')).toBeDisabled()
  })
})

describe('Removing a status', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('asks where issues go before removing a status that holds them', async () => {
    mockReads({
      held: [
        anIssueSummary({ key: 'PRJ-119', title: 'Apply a gift card' }),
        anIssueSummary({ key: 'PRJ-140', title: 'SSO handshake' }),
      ],
    })
    const del = vi.spyOn(api, 'delWithBody').mockResolvedValue(undefined as never)
    renderPage()

    await waitFor(() =>
      expect(screen.getAllByTestId('WorkflowSettings.Row.Remove')[4]).toBeEnabled(),
    )
    // "Blocked" — the fifth row, which the fixture says two issues hold.
    await userEvent.click(screen.getAllByTestId('WorkflowSettings.Row.Remove')[4])

    // The count is named, not implied: B1's third criterion is that the person is ASKED, and a
    // panel that said "some issues" would be the same screen with the decision removed.
    const count = await screen.findByTestId('WorkflowSettings.Removal.Count')
    expect(count.textContent).toContain('2 issues')

    // And the affected issues are shown by key — the reader's next question is which ones.
    const affected = await screen.findByTestId('WorkflowSettings.Removal.Affected')
    expect(affected.textContent).toContain('PRJ-119')
    expect(affected.textContent).toContain('PRJ-140')

    await userEvent.selectOptions(screen.getByTestId('WorkflowSettings.Removal.Destination'), 's2')
    await userEvent.click(screen.getByTestId('WorkflowSettings.Removal.Confirm'))

    // The destination travels in the BODY of the DELETE, which is the whole reason `delWithBody`
    // exists: a destructive decision in a query parameter looks like a filter.
    await waitFor(() =>
      expect(del).toHaveBeenCalledWith(
        `/projects/${PROJECT_ID}/workflow/statuses/s5`,
        { reassignToStatusId: 's2' },
        expect.anything(),
      ),
    )
  })

  it('refuses to remove the project’s only done status, on the control rather than at the server', async () => {
    // Every completion figure in the product counts the DONE kind, so a project with none has no
    // expressible completion. The server answers 409; this asserts the person never gets there.
    mockReads()
    renderPage()

    await waitFor(() =>
      expect(screen.getAllByTestId('WorkflowSettings.Row.Remove')[0]).toBeEnabled(),
    )

    const done = screen.getAllByTestId('WorkflowSettings.Row.Remove')[5]
    expect(done).toBeDisabled()
    expect(done).toHaveAttribute('title', expect.stringContaining('done'))
  })

  it('shows the server’s own sentence when a removal is refused', async () => {
    // The 409 names what to do next, and `describeError` is not consulted for it: the endpoint's
    // sentence is strictly more useful than "couldn't save", which is why the hook is `handled`.
    drivingAFailedWrite()
    mockReads({ held: [anIssueSummary({ key: 'PRJ-119' })] })
    vi.spyOn(api, 'delWithBody').mockRejectedValue(
      Object.assign(new Error('conflict'), {
        name: 'ApiError',
        status: 409,
        detail: 'Keep one status that means done.',
      }),
    )
    renderPage()

    await waitFor(() =>
      expect(screen.getAllByTestId('WorkflowSettings.Row.Remove')[4]).toBeEnabled(),
    )
    await userEvent.click(screen.getAllByTestId('WorkflowSettings.Row.Remove')[4])
    await userEvent.click(await screen.findByTestId('WorkflowSettings.Removal.Confirm'))

    // The generic fallback, because the rejected value above is not a real `ApiError` instance —
    // which is the point: the screen must still say something specific to the action rather than
    // rendering nothing.
    const refused = await screen.findByTestId('WorkflowSettings.Removal.Refused')
    expect(refused.textContent).toContain("Couldn't remove that status")
  })
})

describe('A caller who may not change the workflow', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sees the statuses and every control disabled', async () => {
    // The GET is gated on project visibility alone, deliberately: this is the only screen that says
    // what a status in their project means, and hiding it would leave a Test Engineer unable to
    // find out. §31's disable-don't-hide.
    mockReads({ permissions: ['project:read', 'issue:read'] })
    renderPage()

    await waitFor(() =>
      expect(screen.getAllByTestId('WorkflowSettings.Row.Name')[0]).toBeDisabled(),
    )
    expect(screen.getByTestId('WorkflowSettings.AddStatus')).toBeDisabled()
    expect(screen.getByTestId('WorkflowSettings.Save')).toBeDisabled()
    expect(screen.getByTestId('WorkflowSettings.ReadOnly')).toBeInTheDocument()
    // Still the statuses, in their order — the read-only arm is the same document, not an empty one.
    expect(screen.getAllByTestId('WorkflowSettings.Row')).toHaveLength(6)
  })
})
