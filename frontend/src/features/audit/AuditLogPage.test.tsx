import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { AuditEvent, PermissionKey } from '../../api/types'
import { AT, aProject, anAuditEvent } from '../../test/fixtures'
import { openFilters } from '../../test/filters'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { AuditLogPage } from './AuditLogPage'

/**
 * The audit trail's two screens.
 *
 * Most of this file is about the shapes a naive reader gets wrong, because that is where the risk
 * is: `actorUserId`, `actorTokenId` and `projectId` are independently nullable and the interesting
 * rows are the ones where one of them is absent. The rest is the refusal, which must be a sentence
 * and never an empty grid — an empty audit trail reads as "nothing happened", which is a false
 * statement about the past the reader cannot detect.
 */

const SESSION_ACT = anAuditEvent({
  id: 'ae-1',
  action: 'case.updated',
  occurredAt: '2026-08-18T09:02:00Z',
})

/** A CI runner acting under a person's credential — BOTH actor columns set, which is normal. */
const TOKEN_ACT = anAuditEvent({
  id: 'ae-2',
  action: 'execution.recorded',
  actorUserId: 'user-1',
  actorUserName: 'A. Actor',
  actorTokenId: 'tok-1',
  actorTokenName: 'nightly-pipeline',
  occurredAt: '2026-08-18T09:01:00Z',
})

/** An unattended job: nobody asked for it. */
const AUTOMATIC_ACT = anAuditEvent({
  id: 'ae-3',
  action: 'run.reaped',
  actorUserId: null,
  actorUserName: null,
  occurredAt: '2026-08-18T09:00:00Z',
})

/**
 * Three rows of one bulk assignment (§245) — adjacent, sharing an id, stamped from one instant.
 *
 * That adjacency is a property of the writer and of the `(occurredAt DESC, id DESC)` sort, not a
 * convenience of this fixture: every row of one transaction is written from one `Instant`.
 */
const BULK_ACT = ['ae-b1', 'ae-b2', 'ae-b3'].map((id, index) =>
  anAuditEvent({
    id,
    action: 'execution.assigned',
    targetType: 'TEST_EXECUTION',
    targetIds: [`ex-${index}`],
    bulkOperationId: 'bulk-1',
    occurredAt: '2026-08-22T10:00:00Z',
  }),
)

function page(items: AuditEvent[], total = items.length) {
  return { items, page: 0, size: 50, total }
}

function mockApi({
  rows = [SESSION_ACT, TOKEN_ACT, AUTOMATIC_ACT],
  permissions = ['project:audit', 'audit:view'],
  onAuditRequest,
}: {
  rows?: AuditEvent[]
  // `PermissionKey[]`, not `string[]`: a misspelled key would otherwise be a string that grants
  // nothing, and the failure would surface as a refusal three assertions later.
  permissions?: PermissionKey[]
  onAuditRequest?: (path: string) => void
} = {}) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(permissions)
    }
    // Anchored on the segment, so `/projects/p1/audit-events` and `/audit-events` both match and
    // nothing else does — a bare `includes` would swallow a path added later.
    if (/\/audit-events(\?|$)/.test(path)) {
      onAuditRequest?.(path)
      return page(rows)
    }
    if (path === '/projects') return [aProject({ id: 'p1' })]
    if (path.startsWith('/projects/p1')) return aProject({ id: 'p1', key: 'PRJ', name: 'Banking' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderProjectTrail(route = '/projects/p1/audit') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <Routes>
        <Route path="/projects/:projectId/audit" element={<AuditLogPage />} />
      </Routes>
    </TestProviders>,
  )
}

function renderCompanyTrail(route = '/admin/audit') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <Routes>
        <Route path="/admin/audit" element={<AuditLogPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('AuditLogPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the project trail newest first', async () => {
    mockApi()
    renderProjectTrail()

    const rows = await screen.findAllByTestId('Audit.Row')
    expect(rows).toHaveLength(3)
    // The server sorts; this asserts the client does not re-order or reverse on the way through.
    expect(within(rows[0]).getByTestId('Audit.Row.Action')).toHaveTextContent('Case updated')
    expect(within(rows[1]).getByTestId('Audit.Row.Action')).toHaveTextContent('Result recorded')
  })

  it('names both the person and the credential on a token-driven act', async () => {
    mockApi({ rows: [TOKEN_ACT] })
    renderProjectTrail()

    const row = await screen.findByTestId('Audit.Row')
    // Both, not either. The caller identity on a token request is the token's OWNER, so hiding the
    // person would say a pipeline acted with nobody behind it and hiding the token would say a
    // human typed what a pipeline did.
    expect(within(row).getByTestId('Audit.Row.Actor')).toHaveTextContent('A. Actor')
    expect(within(row).getByTestId('Audit.Row.ByToken')).toBeInTheDocument()
  })

  it('renders an unattended act without blaming a person', async () => {
    mockApi({ rows: [AUTOMATIC_ACT] })
    renderProjectTrail()

    const row = await screen.findByTestId('Audit.Row')
    expect(within(row).getByTestId('Audit.Row.Actor')).toHaveTextContent('Automatic')
    expect(within(row).queryByTestId('Audit.Row.ByToken')).not.toBeInTheDocument()
  })

  it('renders a vanished membership as a former member, keeping the row', async () => {
    // A null name on a non-null id. Offboarding does NOT produce this — a departed colleague still
    // resolves — so the row must still be there and must not claim to know who it was.
    mockApi({ rows: [anAuditEvent({ actorUserId: 'ghost', actorUserName: null })] })
    renderProjectTrail()

    const row = await screen.findByTestId('Audit.Row')
    expect(within(row).getByTestId('Audit.Row.Actor')).toHaveTextContent('Former member')
  })

  it('labels an action this build does not know rather than leaving the cell blank', async () => {
    // The ordinary case during a rollout: the server records a verb the SPA has no label for.
    mockApi({ rows: [anAuditEvent({ action: 'suite.condition_attached' })] })
    renderProjectTrail()

    const row = await screen.findByTestId('Audit.Row')
    expect(within(row).getByTestId('Audit.Row.Action')).toHaveTextContent(
      'Suite condition attached',
    )
  })

  it('summarises a multi-target act by count rather than listing every id', async () => {
    mockApi({ rows: [anAuditEvent({ targetIds: ['c1', 'c2', 'c3'] })] })
    renderProjectTrail()

    const row = await screen.findByTestId('Audit.Row')
    expect(within(row).getByTestId('Audit.Row.Target')).toHaveTextContent('3 items')
  })

  it('sends the action filter to the server rather than narrowing in the client', async () => {
    const requested: string[] = []
    mockApi({ onAuditRequest: (path) => requested.push(path) })
    renderProjectTrail()
    await screen.findAllByTestId('Audit.Row')

    const user = userEvent.setup()
    await openFilters(user, 'Audit.Filters')
    await user.type(screen.getByTestId('Audit.Filter.Action'), 'case.created')

    // Filtering in the client would page over rows the reader never asked for, so the total and
    // the pager would both be counts of the wrong set.
    await waitFor(() => expect(requested.at(-1)).toContain('action=case.created'))
  })

  it('reads the filter back out of the URL, so a pasted trail arrives narrowed', async () => {
    // The half that proves the state is in the URL rather than in the component: nothing is typed
    // here, and both the control and the request come up pre-narrowed. A filtered trail is the
    // thing somebody pastes into a ticket, which is why this is not component state.
    //
    // `window.location` is NOT the observer for that — TestProviders renders a MemoryRouter, so
    // the browser URL never changes and asserting on it fails against an empty string.
    const requested: string[] = []
    mockApi({ onAuditRequest: (path) => requested.push(path) })
    renderProjectTrail('/projects/p1/audit?action=case.created')

    await screen.findAllByTestId('Audit.Row')
    await openFilters(userEvent.setup(), 'Audit.Filters')
    expect(screen.getByTestId('Audit.Filter.Action')).toHaveValue('case.created')
    expect(screen.getByTestId('Audit.Filters.Applied.action')).toHaveTextContent('case.created')
    expect(requested[0]).toContain('action=case.created')
  })

  it('refuses the project trail in words, and asks for no rows at all', async () => {
    const requested: string[] = []
    mockApi({ permissions: ['project:read'], onAuditRequest: (path) => requested.push(path) })
    renderProjectTrail()

    // findByText, not findByTestId: the element exists immediately carrying "Checking your
    // permissions…", so asserting on the testid resolves against the PENDING copy and reads as a
    // wrong-message failure rather than as a race.
    expect(await screen.findByText(/ask a test lead/i)).toBeInTheDocument()
    // An empty grid would read as "nothing happened here", which about an audit trail is a lie
    // the reader has no way to detect. And the request is never made: the server would answer 403
    // and the screen already knows the answer.
    expect(screen.queryByTestId('Audit.Row')).not.toBeInTheDocument()
    expect(requested).toHaveLength(0)
  })

  it('refuses the company trail to a caller holding only the project permission', async () => {
    mockApi({ permissions: ['project:audit'] })
    renderCompanyTrail()

    // The two keys are genuinely different, and a screen that asked the wrong one would let a
    // Test Lead read every project's acts through the company trail.
    expect(await screen.findByText(/company administrators/i)).toBeInTheDocument()
  })

  it('reads the company trail from the unscoped path', async () => {
    const requested: string[] = []
    mockApi({ onAuditRequest: (path) => requested.push(path) })
    renderCompanyTrail()
    await screen.findAllByTestId('Audit.Row')

    expect(requested.every((path) => path.startsWith('/audit-events'))).toBe(true)
  })

  it('reads an empty trail as "nothing recorded", not as a failure', async () => {
    mockApi({ rows: [] })
    renderProjectTrail()

    expect(await screen.findByTestId('Audit.List.Empty')).toBeInTheDocument()
    expect(screen.queryByTestId('Audit.List.Error')).not.toBeInTheDocument()
  })

  it('offers to clear the filters when a narrowed trail is empty', async () => {
    mockApi({ rows: [] })
    renderProjectTrail('/projects/p1/audit?action=nothing.matches')

    const empty = await screen.findByTestId('Audit.List.Empty')
    expect(empty).toHaveTextContent(/no acts match/i)
  })

  it('folds one bulk act into a single collapsible row', async () => {
    mockApi({ rows: BULK_ACT })
    renderProjectTrail()

    const group = await screen.findByTestId('Audit.Group')
    // Twelve lines for one act is what floods the trail, and it is the reason §242 left this as
    // area B's job rather than shipping the reader alone.
    expect(screen.queryAllByTestId('Audit.Row')).toHaveLength(0)
    expect(within(group).getByTestId('Audit.Group.Action')).toHaveTextContent('Execution assigned')
    expect(within(group).getByTestId('Audit.Group.Action')).toHaveTextContent('3 of one operation')
    expect(within(group).getByTestId('Audit.Group.Targets')).toHaveTextContent('3 items')
  })

  it('shows every row of the act when the group is expanded', async () => {
    mockApi({ rows: BULK_ACT })
    renderProjectTrail()

    const group = await screen.findByTestId('Audit.Group')
    await userEvent.click(within(group).getByTestId('Audit.Group.Toggle'))

    const rows = await screen.findAllByTestId('Audit.Row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByTestId('Audit.Row.Target')).toHaveTextContent('ex-0')
  })

  it('leaves a row with no bulk operation as a plain row', async () => {
    // Every row written before §245 carries a null id, which is every row in every deployment
    // until then — so the ungrouped rendering is the one that must not move.
    mockApi({ rows: [SESSION_ACT, ...BULK_ACT] })
    renderProjectTrail()

    await screen.findByTestId('Audit.Group')
    const plain = screen.getAllByTestId('Audit.Row')
    expect(plain).toHaveLength(1)
    expect(within(plain[0]).getByTestId('Audit.Row.Action')).toHaveTextContent('Case updated')
  })

  it('keeps two adjacent operations apart', async () => {
    const second = BULK_ACT.map((row, index) =>
      anAuditEvent({ ...row, id: `ae-c${index}`, bulkOperationId: 'bulk-2' }),
    )
    mockApi({ rows: [...BULK_ACT, ...second] })
    renderProjectTrail()

    const groups = await screen.findAllByTestId('Audit.Group')
    // A scan over the whole page keyed by id would merge these two if they ever shared one; a run
    // cannot, which is the property the grouping is written on.
    expect(groups).toHaveLength(2)
  })

  it('labels a mixed-action group neutrally rather than picking one of them', async () => {
    // A distribute that also takes rows off somebody writes both verbs in one act, so this is
    // reachable in ordinary use rather than hypothetical.
    mockApi({
      rows: [
        BULK_ACT[0],
        anAuditEvent({ ...BULK_ACT[1], action: 'execution.unassigned' }),
        BULK_ACT[2],
      ],
    })
    renderProjectTrail()

    const group = await screen.findByTestId('Audit.Group')
    expect(within(group).getByTestId('Audit.Group.Action')).toHaveTextContent(
      '3 changes in one operation',
    )
  })

  it('pages on the total rather than on the page length', async () => {
    // The pager must read `total`. Reading `items.length` would render "1–1 of 1" over a trail
    // with 140 rows in it, which on this screen is the same lie as an empty page.
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['project:audit'])
      if (/\/audit-events(\?|$)/.test(path)) {
        return { items: [anAuditEvent({ occurredAt: AT })], page: 0, size: 50, total: 140 }
      }
      if (path.startsWith('/projects/p1')) return aProject({ id: 'p1' })
      throw new Error(`unmocked read: ${path}`)
    }) as never)
    renderProjectTrail()

    await screen.findByTestId('Audit.Row')
    expect(await screen.findByTestId('Audit.Pager')).toHaveTextContent('140')
  })
})
