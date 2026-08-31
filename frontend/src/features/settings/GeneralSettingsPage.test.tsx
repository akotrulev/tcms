import { render, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { IssueTypeRef, ProjectWorkflow } from '../../api/types'
import { issueLevels } from '../../lib/labels'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject } from '../../test/fixtures'
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
 * The other three assertions are absences, and an absence is exactly what a naive port gets wrong:
 * the Client select (Mc), the "Closing work" toggles (M5) and the "Time" panel (M6) are not here,
 * and neither is a rename form the API cannot honour.
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

function mockReads(types: IssueTypeRef[] = SEEDED_TYPES) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['project:read'])
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) return { ...WORKFLOW, types }
    if (/^\/projects\/[^/]+$/.test(path)) {
      return aProject({ id: PROJECT_ID, key: 'ACME', name: 'Acme Storefront', caseCount: 12 })
    }
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

  it('offers no review toggles and no time panel', async () => {
    // M5 and M6. A toggle that changes no behaviour is the no-op this repo has shipped once, and a
    // budget panel for a module that is not installed is an advertisement.
    mockReads()
    renderPage()

    await screen.findByTestId('GeneralSettings.Identity.Name')

    expect(screen.queryByText(/Require review to close issues/i)).toBeNull()
    expect(screen.queryByText(/Track time on this project/i)).toBeNull()
    expect(screen.queryByText(/Budget/i)).toBeNull()
  })
})
