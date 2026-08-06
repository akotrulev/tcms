import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { PlanStatisticsPage } from './PlanStatisticsPage'

/**
 * The **Test cases** tab (§97 phase 6) — the four fill mechanisms, sections, and the one thing this
 * tab must never do, which is create anything before Save.
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **Nothing is created until Save.** §93's bar created a case on the click; the whole model
 *      changed to make that wrong, and the check is that typing three titles fires no `POST`.
 *   2. **The trailing blank row is not a case.** It is permanently present, so a banner keyed on
 *      `newCases.length` would be up on every plan page ever rendered (§94's refine defect).
 *   3. **The payload sends references, not cases** — and a new row's created id lands in the group
 *      it was authored under, which is the only way to tell a case in a section from one beside it.
 *   4. **A served count is hidden the moment the draft moves.** A number recomputed on save,
 *      rendered beside a section the author has just filled, is wrong in the direction of the work
 *      they just did.
 *   5. **Deleting a section asks what happens to its references.** Two answers, both defensible,
 *      and the plan's total moves in one and not the other.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    name: 'Release 4.4 regression',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 12,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    suiteIds: [],
    caseIds: [],
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 7,
    ...overrides,
  } as unknown as TestPlan
}

const SUITES = [
  { id: 'suite-1', key: 'TS-1', name: 'Payments', caseCount: 12, parentId: null, path: '1' },
  { id: 'suite-2', key: 'TS-2', name: 'Login', caseCount: 3, parentId: null, path: '2' },
]

const CASES = [
  { id: 'case-1', key: 'TC-1', title: 'Refund a settled payment', suiteId: 'suite-1', tags: [] },
  { id: 'case-2', key: 'TC-2', title: 'Sign in with SSO', suiteId: 'suite-2', tags: [] },
]

function mockReads(
  plan: TestPlan,
  permissions: string[] = ['project:read', 'plan:edit', 'case:create'],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes(`/plans/${PLAN}/statistics`)) {
      return {
        counts: { total: 0, passed: 0, failed: 0, blocked: 0, skipped: 0, retest: 0, notRun: 0 },
        coveragePercent: 0,
        stale: false,
        byConfiguration: [],
        bySuite: [],
        blockers: [],
        notRun: [],
        trend: [],
        exitCriteria: [],
        latestExecutionAt: null,
      }
    }
    if (path.includes(`/plans/${PLAN}`)) return plan
    if (path.includes('/suites')) return SUITES
    if (path.includes('/tags')) return [{ tag: 'smoke', caseCount: 9 }, { tag: 'checkout', caseCount: 4 }]
    if (path.includes('/cases')) return { items: CASES, page: 0, size: 20, total: CASES.length }
    if (path.endsWith('/plans')) return [plan]
    if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
    return []
  }) as never)
}

const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/plans/${PLAN}?tab=cases`}
    >
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <Routes>
          <Route path="/projects/:projectId/plans/:planId" element={<PlanStatisticsPage />} />
        </Routes>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/**
 * The unsectioned group's first new-case row, **once the `case:create` verdict has landed**.
 *
 * Waiting for `toBeEnabled` rather than for presence, and re-querying inside `waitFor` rather than
 * holding the node: `useCan` is fail-closed while pending, so the row renders disabled for an
 * instant and `userEvent.type` into a disabled input silently does nothing — and `PermissionGate`'s
 * denied branch wraps the rows in a tooltip span, so the enabled row is a NEW node (§97 phase 5's
 * hour).
 */
async function firstRow() {
  await waitFor(() =>
    expect(screen.getByTestId('PlanCases.NewCase.unsectioned.0')).toBeEnabled())
  return screen.getByTestId('PlanCases.NewCase.unsectioned.0')
}

async function save() {
  await waitFor(() => expect(screen.getByTestId('PlanStats.Save')).toBeEnabled())
  await userEvent.click(screen.getByTestId('PlanStats.Save'))
}

describe('the Test cases tab', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('opens with one blank row per group and no banner', async () => {
    mockReads(planBody())
    renderPage()

    expect(await firstRow()).toHaveValue('')
    // The blank row is permanent, so anything counting rows would report this page as dirty from
    // the moment it rendered — and `useUnsavedWork` would prompt on every navigation away.
    expect(screen.queryByTestId('PlanStats.Dirty')).not.toBeInTheDocument()
  })

  it('appends the next row as the last one is typed in, and creates nothing', async () => {
    mockReads(planBody())
    const post = vi.spyOn(api, 'post')
    renderPage()

    await userEvent.type(await firstRow(), 'Open the ledger')
    expect(await screen.findByTestId('PlanCases.NewCase.unsectioned.1')).toBeInTheDocument()

    await userEvent.type(screen.getByTestId('PlanCases.NewCase.unsectioned.1'), 'Close it again')
    expect(await screen.findByTestId('PlanCases.NewCase.unsectioned.2')).toBeInTheDocument()

    // §93's bar created the case on the click. The whole model changed to make that wrong.
    expect(post).not.toHaveBeenCalled()
    expect(await screen.findByTestId('PlanStats.Dirty')).toHaveTextContent('Test cases')
  })

  it('creates each typed row on Save, and files it in the section it was authored under', async () => {
    mockReads(planBody())
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'case-new', key: 'TC-41' } as never)
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.type(await screen.findByTestId('PlanCases.NewSection'), 'Navigation')
    await userEvent.click(screen.getByTestId('PlanCases.AddSection'))
    await waitFor(() =>
      expect(screen.getByTestId('PlanCases.NewCase.Navigation.0')).toBeEnabled())
    await userEvent.type(screen.getByTestId('PlanCases.NewCase.Navigation.0'), 'Open the ledger')
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    // One case, from one non-blank row: the trailing blanks in both groups must not be POSTed.
    expect(post).toHaveBeenCalledTimes(1)
    const body = (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
    expect(body.sections).toEqual([
      { name: 'Navigation', caseIds: ['case-new'], suiteIds: [], tags: [] },
    ])
    // NOT in the unsectioned group as well — a case the author filed under a heading, landing
    // outside every section, is invisible on screen.
    expect(body.caseIds).toEqual([])
  })

  it('adds a suite, a tag and an existing case as REFERENCES', async () => {
    mockReads(planBody())
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.click(await screen.findByRole('combobox', { name: /add a suite/i }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))

    await userEvent.type(screen.getByTestId('PlanCases.AddTag.unsectioned'), 'smo')
    await userEvent.click(await screen.findByRole('option', { name: 'smoke' }))

    await userEvent.type(screen.getByTestId('PlanCases.AddCase.unsectioned'), 'Refund')
    await userEvent.click(await screen.findByRole('option', { name: /TC-1/ }))

    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
    expect(body.suiteIds).toEqual(['suite-1'])
    expect(body.tags).toEqual(['smoke'])
    expect(body.caseIds).toEqual(['case-1'])
  })

  /*
    The add path's VISIBILITY, which the test above does not cover: it asserts the saved payload,
    and the one that renders chips seeds them from the server. So a reference that was added but
    never drawn would pass both — the author picks a suite, sees nothing appear, and picks it again.

    The second half is the sibling defect: removing one reference must take only that one. A remove
    that also drops the suite added beside it is invisible until Save, by which point the plan is
    missing an arm the author put in.
  */
  it('draws an added suite and tag immediately, and a removed tag takes only itself', async () => {
    mockReads(planBody())
    renderPage()

    await userEvent.click(await screen.findByRole('combobox', { name: /add a suite/i }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))

    await userEvent.type(screen.getByTestId('PlanCases.AddTag.unsectioned'), 'smo')
    await userEvent.click(await screen.findByRole('option', { name: 'smoke' }))

    const group = await screen.findByTestId('PlanCases.Unsectioned')
    await waitFor(() =>
      expect(within(screen.getByTestId('PlanCases.Unsectioned')).getByTestId('PlanCases.SuiteChip'))
        .toHaveTextContent('Payments'))
    expect(within(group).getByTestId('PlanCases.TagChip')).toHaveTextContent('smoke')

    await userEvent.click(within(group).getByTestId('PlanCases.TagChip').querySelector('svg')!)

    await waitFor(() =>
      expect(screen.queryByTestId('PlanCases.TagChip')).not.toBeInTheDocument())
    // The suite added beside it survives — the whole point of the second half.
    expect(within(screen.getByTestId('PlanCases.Unsectioned')).getByTestId('PlanCases.SuiteChip'))
      .toHaveTextContent('Payments')
  })

  /*
    The same two properties inside a NAMED section, which is a different code path: the unsectioned
    group's references live on the draft's own three arrays, a section's on a row of `draft.sections`
    matched by name. A defect in one is invisible in the other.
  */
  it('draws a reference added to a named section, and removes only the one taken off', async () => {
    mockReads(
      planBody({
        sections: [
          { name: 'Create', position: 0, caseIds: [], suiteIds: [], tags: [], caseCount: 0 },
        ],
      } as unknown as Partial<TestPlan>),
    )
    renderPage()

    const section = await screen.findByTestId('PlanCases.Section')
    // Scoped to the section: with one present there are two of every picker on screen, and an
    // unscoped query resolves to the unsectioned group's — testing the path already covered above.
    await userEvent.click(within(section).getByRole('combobox', { name: /add a suite/i }))
    await userEvent.click(await screen.findByRole('option', { name: 'Payments' }))

    await userEvent.type(screen.getByTestId('PlanCases.AddTag.Create'), 'smo')
    await userEvent.click(await screen.findByRole('option', { name: 'smoke' }))

    await waitFor(() =>
      expect(within(screen.getByTestId('PlanCases.Section')).getByTestId('PlanCases.SuiteChip'))
        .toHaveTextContent('Payments'))
    expect(within(section).getByTestId('PlanCases.TagChip')).toHaveTextContent('smoke')

    await userEvent.click(within(section).getByTestId('PlanCases.TagChip').querySelector('svg')!)

    await waitFor(() =>
      expect(screen.queryByTestId('PlanCases.TagChip')).not.toBeInTheDocument())
    expect(within(screen.getByTestId('PlanCases.Section')).getByTestId('PlanCases.SuiteChip'))
      .toHaveTextContent('Payments')
  })

  it('shows what defines a group, and lets a reference be taken back off', async () => {
    mockReads(
      planBody({
        suiteIds: ['suite-1'],
        tags: ['checkout'],
        caseIds: ['case-2'],
      } as Partial<TestPlan>),
    )
    renderPage()

    const group = await screen.findByTestId('PlanCases.Unsectioned')
    // Waited for: the suite tree and the case list arrive after the plan, and a chip asserted
    // before them passes against a component that never resolves the name at all.
    await waitFor(() =>
      expect(within(screen.getByTestId('PlanCases.Unsectioned')).getByTestId('PlanCases.SuiteChip'))
        .toHaveTextContent('Payments'))
    expect(within(group).getByTestId('PlanCases.TagChip')).toHaveTextContent('checkout')
    // Resolved to its key through the case list, rather than rendered as a UUID.
    expect(within(group).getByTestId('PlanCases.CaseChip')).toHaveTextContent('TC-2')

    await userEvent.click(within(group).getByTestId('PlanCases.TagChip').querySelector('svg')!)
    await waitFor(() =>
      expect(screen.queryByTestId('PlanCases.TagChip')).not.toBeInTheDocument())
    expect(await screen.findByTestId('PlanStats.Dirty')).toHaveTextContent('Test cases')
  })

  it('hides the served counts once the draft has moved past them', async () => {
    mockReads(
      planBody({
        sections: [
          { name: 'Create', position: 0, caseIds: [], suiteIds: ['suite-1'], tags: [], caseCount: 12 },
        ],
      } as Partial<TestPlan>),
    )
    renderPage()

    const section = await screen.findByTestId('PlanCases.Section')
    expect(section).toHaveTextContent('12 cases')

    await userEvent.type(await firstRow(), 'A new case')

    // The count is the server's arithmetic over the references IT holds. Left on screen beside a
    // draft that has moved, it is wrong in the direction of the work the author just did.
    await waitFor(() => expect(screen.getByTestId('PlanCases.CountsStale')).toBeInTheDocument())
    expect(screen.getByTestId('PlanCases.Section')).not.toHaveTextContent('12 cases')
  })

  it('asks what happens to a deleted section’s references, and keeps them when told to', async () => {
    mockReads(
      planBody({
        sections: [
          { name: 'Create', position: 0, caseIds: [], suiteIds: ['suite-1'], tags: ['smoke'], caseCount: 12 },
        ],
      } as Partial<TestPlan>),
    )
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(planBody() as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('PlanCases.DeleteSection.0'))
    await userEvent.click(await screen.findByTestId('PlanCases.DeleteSection.Keep'))
    await save()

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = (patch.mock.calls[0] as unknown[])[1] as Record<string, unknown>
    expect(body.sections).toEqual([])
    // Kept, because that is what was chosen. "Remove them" is the other button and empties these.
    expect(body.suiteIds).toEqual(['suite-1'])
    expect(body.tags).toEqual(['smoke'])
  })

  it('refuses to save two sections with one name, and says so', async () => {
    mockReads(planBody({
      sections: [{ name: 'Create', position: 0, caseIds: [], suiteIds: [], tags: [], caseCount: 0 }],
    } as Partial<TestPlan>))
    renderPage()

    await userEvent.type(await screen.findByTestId('PlanCases.NewSection'), 'Create')
    await userEvent.click(screen.getByTestId('PlanCases.AddSection'))

    // Sections have no ids — the server keys them by name — so two of one name are ONE section
    // holding the union of both on the way back.
    expect(await screen.findByTestId('PlanStats.Blocked')).toHaveTextContent('Two sections')
    expect(screen.getByTestId('PlanStats.Save')).toBeDisabled()
  })

  it('disables the new-case rows, with the reason, for a caller without case:create', async () => {
    mockReads(planBody(), ['project:read', 'plan:edit'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('PlanCases.NewCase.unsectioned.0')).toBeDisabled())
    // The rest of the tab still works — §31 disables the section, not the Save: this caller can
    // still add existing cases, reorder sections and save the plan.
    expect(screen.getByRole('combobox', { name: /add a suite/i })).toBeEnabled()
  })
})
