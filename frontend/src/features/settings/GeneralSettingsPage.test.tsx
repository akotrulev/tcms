import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BudgetBurn, IssueTypeRef, PermissionKey, ProjectWorkflow } from '../../api/types'
import { issueLevels } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aBudgetBurn, aProject, projectSettings } from '../../test/fixtures'
import { GeneralSettingsPage } from './GeneralSettingsPage'

/**
 * `settings-general.html`, minus the three panels M1 has no data for.
 *
 * What this file is really pinning is a disagreement with the mockup that was decided rather than
 * discovered: **the mockup lists five issue types and the product seeds six.** Both cannot be true
 * once D29 puts `Task` at the story level, because the mockup separately offers "Add sub-task" on a
 * story — which needs a `TASK`-level type. So there are six, and the screen names the level of each
 * so a reader can tell `Task` from `Sub-task`.
 *
 * The other assertions are absences, and an absence is exactly what a naive port gets wrong: the
 * Client select (Mc) is not here, ~~the "Closing work" toggles (M5) and the "Time" panel (M6)~~ →
 * both landed, in 015 T5 and 016 T5 — and neither is a rename form the API cannot honour. ~~What is
 * still absent from the Time panel is the mockup's **Budget** row, which is M8's, and that absence
 * is asserted below.~~ → **reversed 2026-09-05 (019 T6): the row is built.** What is asserted in
 * its place is what the row may and may not say — the ceiling in hours, *set in money* rather than
 * a currency for a MONEY budget (019 D-h), *no budget set* for a project with none, and an
 * `Edit in Billing` button **disabled with its reason** rather than hidden for the many callers who
 * can open this screen and cannot open that one (§31).
 */

const PROJECT_ID = 'p1'

/** The six `tcms_seed_project_defaults` creates (`V3`), in its order. */
const SEEDED_TYPES: IssueTypeRef[] = [
  { id: 't1', name: 'Epic', level: 'EPIC', countsTowardCompletion: false },
  { id: 't2', name: 'Story', level: 'STORY', countsTowardCompletion: true },
  { id: 't3', name: 'Task', level: 'STORY', countsTowardCompletion: true },
  { id: 't4', name: 'Bug', level: 'STORY', countsTowardCompletion: true },
  { id: 't5', name: 'Spike', level: 'STORY', countsTowardCompletion: false },
  { id: 't6', name: 'Sub-task', level: 'TASK', countsTowardCompletion: true },
]

const WORKFLOW: ProjectWorkflow = {
  projectId: PROJECT_ID,
  statuses: [],
  types: SEEDED_TYPES,
  transitions: [],
}

function mockReads(
  types: IssueTypeRef[] = SEEDED_TYPES,
  permissions: PermissionKey[] = ['project:read'],
  settings = projectSettings({ projectId: PROJECT_ID }),
  /** 019 M8. The default is a project with no budget — the common case, and the row says so. */
  burn: BudgetBurn = aBudgetBurn(),
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) return { ...WORKFLOW, types }
    // Anchored, so the settings sub-resource is not caught by the project prefix — the collision
    // the harness rules name explicitly.
    if (/^\/projects\/[^/]+\/settings$/.test(path)) return settings
    if (/^\/projects\/[^/]+\/budget-burn$/.test(path)) return burn
    if (/^\/projects\/[^/]+$/.test(path)) {
      return aProject({ id: PROJECT_ID, key: 'ACME', name: 'Acme Storefront', caseCount: 12 })
    }
    /*
      018 T5's ClientPanel reads this. Answered EMPTY, which is the state every test in this file is
      about: with no clients the panel does not render at all, so none of these assertions has to
      account for a control that is not there. The panel's own two arms are asserted in
      `features/clients/ClientAbsence.test.tsx`, both directions.
    */
    if (/\/settings\/client-options$/.test(path)) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route={`/projects/${PROJECT_ID}/settings/general`}
    >
      <Routes>
        <Route path="/projects/:projectId/settings/general" element={<GeneralSettingsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('The issue types this project holds', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists all six seeded types, not the mockup’s five', async () => {
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('GeneralSettings.Types.Row')).toHaveLength(6))

    expect(
      screen.getAllByTestId('GeneralSettings.Types.Row.Name').map((node) => node.textContent),
    ).toEqual(['Epic', 'Story', 'Task', 'Bug', 'Spike', 'Sub-task'])
  })

  it('names the level of each type, so Task and Sub-task are distinguishable', async () => {
    // The whole reason six types are not confusing. Cross-surface rather than literal: `issueLevels`
    // is the map the type picker groups by, so a screen with its own words is not expressible here.
    mockReads()
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('GeneralSettings.Types.Row')).toHaveLength(6))

    const levels = screen
      .getAllByTestId('GeneralSettings.Types.Row.Level')
      .map((node) => node.textContent)
    expect(levels[2]).toBe(issueLevels.STORY)
    expect(levels[5]).toBe(issueLevels.TASK)
    expect(levels[2]).not.toBe(levels[5])
  })

  it('describes a type from level and countsTowardCompletion, never from its name', async () => {
    /*
      A type is a ROW a project may rename, so a caption keyed on the English word "Spike" goes
      quietly wrong on the first project that renames it. This renames all six and asserts the
      captions are unchanged — which is only expressible because nothing here reads a name.
    */
    mockReads(SEEDED_TYPES.map((type, index) => ({ ...type, name: `Kind ${index}` })))
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('GeneralSettings.Types.Row')).toHaveLength(6))

    const badges = screen
      .getAllByTestId('GeneralSettings.Types.Row.Completion')
      .map((node) => node.textContent)
    expect(badges).toEqual([
      'counts by children',
      'in completion',
      'in completion',
      'in completion',
      'excluded from %',
      'in completion',
    ])
  })

  it('keeps D12’s promise to everybody who had a task before the upgrade', async () => {
    // Verbatim, because it is the only place the product says that a relabelled row still means
    // what its owner typed.
    mockReads()
    renderPage()

    const foot = await screen.findByTestId('GeneralSettings.Types.Foot')
    expect(foot.textContent).toContain('still called')
    expect(foot.textContent).toContain('Nothing you typed changed meaning underneath you.')
  })
})

describe('Identity, which the API cannot change', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the name and the key and says the key is immutable', async () => {
    mockReads()
    renderPage()

    expect((await screen.findByTestId('GeneralSettings.Identity.Name')).textContent).toBe(
      'Acme Storefront',
    )
    const key = await screen.findByTestId('GeneralSettings.Identity.Key')
    expect(key.textContent).toContain('ACME')
    expect(key.textContent).toContain('ACME-124')
    // The mockup's caption promises "existing links keep working if it changes". The schema calls
    // the key immutable, so the half that promises a rename is replaced by the fact.
    expect(key.textContent).toContain('immutable')
  })

  it('renders no rename form and no Save, because there is no endpoint behind either', async () => {
    /*
      A form that shows success and discards the value is `ui-rules`' highest-severity form defect,
      and a Save with no endpoint is that defect with no server to blame. Asserted as an absence,
      which is what a port of the mockup produces by default.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('GeneralSettings.Identity.Name')

    expect(screen.queryByRole('textbox', { name: /name/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^save/i })).toBeNull()
    expect(screen.getByTestId('GeneralSettings.Identity.ReadOnly')).toBeInTheDocument()
  })
})

describe('The panels this screen deliberately does not have', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers no client select, and does not nag a project for lacking one', async () => {
    /*
      Mc's, and the absence is the requirement: `Project` carries no client, so a select here could
      only ever say "None (internal)" — which is the empty prompt `solo-overview.html` rules out in
      as many words.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('GeneralSettings.Identity.Name')

    expect(screen.queryByLabelText(/client/i)).toBeNull()
    expect(screen.queryByText(/attach a client/i)).toBeNull()
  })

  it('draws only one of the mockup’s two closing-work rows', async () => {
    // ~~M5 and M6.~~ → the review toggle landed in 015 T5 and the Time panel in 016 T5, so what is
    // asserted now is the row beside them: `settings-general.html` draws an uneditable "An
    // assistant may never close — always on", and the product has no such rule. An assistant acts
    // as its owner, so with the gate off the same close succeeds for it (A6). A row promising an
    // unconditional block would be a claim the server contradicts.
    mockReads()
    renderPage()

    await screen.findByTestId('GeneralSettings.Identity.Name')

    expect(screen.queryByText(/An assistant may never close/i)).toBeNull()
  })
})

/**
 * D33's gate, on the screen the mockup puts it on — and the one thing that could go wrong with it.
 *
 * `PUT /projects/{id}/settings` is a **full replacement** and the server reads an absent boolean as
 * `false`. Two screens now edit that document, so a toggle here that sent only its own field would
 * switch off every review setting the Review page owns. The assertion is therefore on the *whole
 * body*, not on the one field that changed.
 */
describe('Require review to close issues', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('saves the whole settings document, not just the switch that moved', async () => {
    mockReads(
      SEEDED_TYPES,
      ['project:read', 'project:settings'],
      projectSettings({
        projectId: PROJECT_ID,
        reviewEnabledCase: true,
        reviewEnabledDoc: true,
        requiredPeerReviews: 2,
        reviewEnabledIssue: false,
      }),
    )
    const put = vi.spyOn(api, 'put').mockResolvedValue(
      projectSettings({ projectId: PROJECT_ID, reviewEnabledIssue: true }) as never,
    )
    renderPage()

    await userEvent.click(await screen.findByTestId('GeneralSettings.reviewEnabledIssue'))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(`/projects/${PROJECT_ID}/settings`, {
        reviewEnabledCase: true,
        reviewEnabledSuite: false,
        reviewEnabledPlan: false,
        reviewEnabledDoc: true,
        reviewEnabledIssue: true,
        requiredPeerReviews: 2,
        authorMayApproveOwnWork: false,
        editAfterApproval: 'RETAIN',
        requireApprovedCaseForRun: false,
        trackTime: true,
      }),
    )
  })

  it('is read-only for somebody who cannot change the project’s settings', async () => {
    mockReads(SEEDED_TYPES, ['project:read'])
    renderPage()
    await waitFor(() => expect(screen.getByTestId('GeneralSettings.reviewEnabledIssue')).toBeDisabled())
  })

  it('says the gate is inert while the author-cannot-approve rule is off', async () => {
    mockReads(
      SEEDED_TYPES,
      ['project:read', 'project:settings'],
      projectSettings({ projectId: PROJECT_ID, authorMayApproveOwnWork: true }),
    )
    renderPage()
    expect(await screen.findByText(/Currently inert/)).toBeInTheDocument()
  })
})

/**
 * 016 D-g's switch, and the two things about it a reader has to be able to trust.
 *
 * **It sends the whole document**, for the reason the block above gives — this is now the third
 * control on the second of two screens editing one `PUT`.
 *
 * **Its `false` does not get lost.** `trackTime` is the one boolean the server reads as `true` when
 * omitted, so a client that dropped it would silently re-enable tracking on the very save that was
 * meant to turn it off. The assertion below is on the field being present *and* `false`, which is
 * what makes the omission a failure rather than an accident that happens to work.
 */
describe('Track time on this project', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('is on by default, and switching it off sends trackTime: false in the whole document', async () => {
    mockReads(
      SEEDED_TYPES,
      ['project:read', 'project:settings'],
      projectSettings({ projectId: PROJECT_ID, requiredPeerReviews: 2, trackTime: true }),
    )
    const put = vi.spyOn(api, 'put').mockResolvedValue(
      projectSettings({ projectId: PROJECT_ID, trackTime: false }) as never,
    )
    renderPage()

    const toggle = await screen.findByTestId('GeneralSettings.trackTime')
    expect(toggle).toHaveAttribute('aria-checked', 'true')

    await userEvent.click(toggle)

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(`/projects/${PROJECT_ID}/settings`, {
        reviewEnabledCase: false,
        reviewEnabledSuite: false,
        reviewEnabledPlan: false,
        reviewEnabledDoc: false,
        reviewEnabledIssue: false,
        requiredPeerReviews: 2,
        authorMayApproveOwnWork: false,
        editAfterApproval: 'RETAIN',
        requireApprovedCaseForRun: false,
        trackTime: false,
      }),
    )
  })

  it('says off hides the figures and keeps the hours, rather than only saying it is off', async () => {
    mockReads(
      SEEDED_TYPES,
      ['project:read', 'project:settings'],
      projectSettings({ projectId: PROJECT_ID, trackTime: false }),
    )
    renderPage()

    expect(await screen.findByText(/still there and still approved/i)).toBeInTheDocument()
  })

  it('shows the hours ceiling and disables Edit in Billing for somebody without the money key', async () => {
    /*
      ~~The mockup's Budget row is not built — there is no ceiling, no rate and no Billing.~~ →
      reversed by 019 T6. Two things are pinned here at once: the ceiling arrives from the burn
      endpoint, which is gated on `project:read` and carries no currency (019 D-h), so a project
      administrator reads it without a money key — and the button to the screen that SETS it is
      disabled with the gate's own sentence rather than hidden (§31).
    */
    mockReads(
      SEEDED_TYPES,
      ['project:read', 'project:settings'],
      projectSettings({ projectId: PROJECT_ID }),
      aBudgetBurn({ basis: 'HOURS', budgetHours: 600, approvedHours: 418, burnPercent: 70 }),
    )
    renderPage()

    /*
      The row renders before its read answers, so the element is there with nothing in it — `find`
      would resolve on the empty one. The wait is on the CONTENT, which is what this asserts.
    */
    await waitFor(() =>
      expect(screen.getByTestId('GeneralSettings.Budget.Ceiling').textContent).toBe('600h'),
    )
    expect(screen.getByTestId('GeneralSettings.Budget.Edit')).toBeDisabled()
    expect(screen.queryByText(/€|CHF|\$/)).toBeNull()
  })

  it('says a money ceiling is set in money rather than drawing an em dash or an amount', async () => {
    // The dash means *absent* everywhere else in this app, and the ceiling is not absent — it is
    // not this reader's. `budgetHours` is null for a MONEY budget, and there is nothing else on the
    // payload to draw.
    mockReads(
      SEEDED_TYPES,
      ['project:read', 'project:settings'],
      projectSettings({ projectId: PROJECT_ID }),
      aBudgetBurn({ basis: 'MONEY', budgetHours: null, approvedHours: 418, burnPercent: 62 }),
    )
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('GeneralSettings.Budget.Ceiling').textContent).toBe('set in money'),
    )
  })

  it('reads “no budget set” for a project with none, with no prompt to set one', async () => {
    // 019 D-p: a project with no budget is not in a deficient state, and nothing here nags.
    mockReads(SEEDED_TYPES, ['project:read', 'project:settings'])
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('GeneralSettings.Budget.Ceiling').textContent).toBe('no budget set'),
    )
  })

  it('links Edit in Billing through for a caller who holds the money key', async () => {
    mockReads(SEEDED_TYPES, ['project:read', 'project:settings', 'billing:read'])
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('GeneralSettings.Budget.Edit')).toHaveAttribute('href', '/billing'),
    )
  })

  it('is read-only for somebody who cannot change the project’s settings', async () => {
    mockReads(SEEDED_TYPES, ['project:read'])
    renderPage()
    await waitFor(() => expect(screen.getByTestId('GeneralSettings.trackTime')).toBeDisabled())
  })
})
