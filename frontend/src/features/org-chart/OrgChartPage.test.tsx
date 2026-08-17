import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { OrgChart } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import {
  aTeam,
  anOrgChart,
  anOrgChartAssignment,
  anOrgChartPerson,
  anOrgChartProject,
  aUserRef,
} from '../../test/fixtures'
import { OrgChartPage } from './OrgChartPage'

/**
 * The org chart screen — and the four things about it no type or lint rule can see.
 *
 *   1. **The standing line renders UNCONDITIONALLY.** The project axis is clipped to the reader's
 *      own reach, so somebody missing six of eight projects gets a two-column grid that looks
 *      complete. Rendering the line only when something is hidden would make its presence the
 *      existence claim the design rejected — so it is asserted here for a reader who sees
 *      everything, which is the case a conditional implementation would pass.
 *   2. **A Company Admin shows a badge and NO cell.** Authority over the company is not a default
 *      for its projects; a screen that filled every column with it would be the misreading putting
 *      the fact on the person exists to prevent, and it renders *plausibly*.
 *   3. **`via` is on the cell, in words.** DIRECT and TEAM are the two arms a naive matrix renders
 *      identically, so a cell that showed only the role name would hide the distinction this
 *      column exists for.
 *   4. **The gaps panel names all three vacancies**, and a company that has all three but renders
 *      an empty panel looks exactly like a healthy one.
 */

const ATLAS = anOrgChartProject({ id: 'p-atlas', key: 'ATL', name: 'Atlas' })
const BOREAL = anOrgChartProject({ id: 'p-boreal', key: 'BOR', name: 'Boreal Ledger' })

const QA = { id: 't-qa', name: 'QA' }

const ADMIN = anOrgChartPerson({
  ...aUserRef({ id: 'u-admin', displayName: 'A. Admin', email: 'admin@acme.test' }),
  companyRoles: ['Company Admin'],
  teams: [QA],
})

const LEAD = anOrgChartPerson({
  ...aUserRef({ id: 'u-lead', displayName: 'L. Lead', email: 'lead@acme.test' }),
  teams: [QA],
  leadsTeams: [QA],
  projects: [anOrgChartAssignment({ projectId: ATLAS.id, roleName: 'Test Lead', via: 'DIRECT' })],
})

const ENGINEER = anOrgChartPerson({
  ...aUserRef({ id: 'u-eng', displayName: 'E. Engineer', email: 'eng@acme.test' }),
  teams: [QA],
  projects: [
    anOrgChartAssignment({
      projectId: ATLAS.id,
      roleName: 'Test Engineer',
      via: 'TEAM',
      viaTeam: QA,
    }),
  ],
})

/** In no team, and with no cell in any column this reader can see — two gaps in one person. */
const NEWCOMER = anOrgChartPerson({
  ...aUserRef({ id: 'u-new', displayName: 'N. Newcomer', email: 'new@acme.test' }),
})

function aChart(overrides: Partial<OrgChart> = {}): OrgChart {
  return anOrgChart({
    projects: [ATLAS, BOREAL],
    people: [ADMIN, LEAD, ENGINEER, NEWCOMER],
    teams: [aTeam({ id: QA.id, name: QA.name, leads: [aUserRef({ id: LEAD.id })] })],
    ...overrides,
  })
}

function renderPage(chart: OrgChart = aChart()) {
  vi.spyOn(api, 'get').mockResolvedValue(chart as never)
  return render(
    <TestProviders client={createTestQueryClient()} userId="u-lead">
      <OrgChartPage />
    </TestProviders>,
  )
}

describe('OrgChartPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('reads the chart in ONE request — the three axes come from one snapshot', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue(aChart() as never)
    render(
      <TestProviders client={createTestQueryClient()}>
        <OrgChartPage />
      </TestProviders>,
    )

    await screen.findByTestId('OrgChart.Matrix.Grid')
    // A grid whose rows, columns and cells were read at three different moments renders a company
    // that never existed.
    expect(get.mock.calls.map(([path]) => path)).toEqual(['/org-chart'])
  })

  it('states that the columns are the reader’s own, even for a reader who sees everything', async () => {
    // The whole point of "unconditional": this reader is served every project the company has, so
    // an implementation that rendered the line only when something was hidden passes every other
    // test in this file and fails here.
    renderPage()

    expect(await screen.findByTestId('OrgChart.Matrix.Scope')).toHaveTextContent(
      'Only projects you can open are shown here',
    )
  })

  it('draws a column per project served, and nothing for one that was not', async () => {
    renderPage(aChart({ projects: [ATLAS] }))

    await screen.findByTestId('OrgChart.Matrix.Grid')
    expect(screen.getByText('Atlas')).toBeInTheDocument()
    // The server sent no Boreal column, so the screen has nothing to draw and — critically —
    // nothing that counts what is missing.
    expect(screen.queryByText('Boreal Ledger')).not.toBeInTheDocument()
    expect(screen.queryByText(/more project/i)).not.toBeInTheDocument()
  })

  it('shows a Company Admin’s badge and gives them no cell anywhere', async () => {
    renderPage()

    await screen.findByTestId('OrgChart.Matrix.Grid')
    const row = screen.getByText('A. Admin').closest('tr') as HTMLElement
    expect(within(row).getByText('Company Admin')).toBeInTheDocument()
    // Two columns, two empty cells — not two cells reading "Company Admin".
    expect(within(row).getAllByTestId('OrgChart.Matrix.Cell.Empty')).toHaveLength(2)
  })

  it('names the via arm in words, and names the team on a TEAM cell', async () => {
    renderPage()

    await screen.findByTestId('OrgChart.Matrix.Grid')
    // The two arms a matrix built from the grants that NAME a project renders identically.
    expect(screen.getByText('Direct grant')).toBeInTheDocument()
    expect(screen.getByText('Through a team: QA')).toBeInTheDocument()
  })

  it('renders an INHERITED cell as the company-wide default', async () => {
    renderPage(
      aChart({
        people: [
          anOrgChartPerson({
            ...aUserRef({ id: 'u-rover', displayName: 'R. Rover' }),
            projects: [
              anOrgChartAssignment({
                projectId: ATLAS.id,
                roleName: 'Test Engineer',
                via: 'INHERITED',
              }),
            ],
          }),
        ],
      }),
    )

    await screen.findByTestId('OrgChart.Matrix.Grid')
    expect(screen.getByText('Company-wide default')).toBeInTheDocument()
  })

  it('draws the team tree, orphans included, from the shared flattener', async () => {
    renderPage(
      aChart({
        teams: [
          aTeam({ id: 't-qa', name: 'QA', leads: [aUserRef({ id: LEAD.id })] }),
          aTeam({ id: 't-lost', name: 'Lost', parentTeamId: 't-gone' }),
        ],
      }),
    )

    const teams = await screen.findByTestId('OrgChart.Teams.List')
    // A team the tree does not draw is a team nobody can fix, and this screen is where somebody
    // goes to understand the chart — so a silently dropped orphan is one nobody thinks to look for.
    expect(within(teams).getByText('Lost')).toBeInTheDocument()
    expect(within(teams).getAllByTestId('OrgChart.Teams.Item')).toHaveLength(2)
  })

  it('names all three gaps — and a company that has all three must not look healthy', async () => {
    renderPage(
      aChart({
        teams: [aTeam({ id: 't-qa', name: 'QA', leads: [] })],
      }),
    )

    const gaps = await screen.findByTestId('OrgChart.Gaps.List')
    expect(within(gaps).getByTestId('OrgChart.Gaps.NoTeam')).toHaveTextContent('N. Newcomer')
    expect(within(gaps).getByTestId('OrgChart.Gaps.NoLead')).toHaveTextContent('QA')
    expect(within(gaps).getByTestId('OrgChart.Gaps.NoAssignment')).toHaveTextContent('N. Newcomer')
    expect(screen.queryByTestId('OrgChart.Gaps.None')).not.toBeInTheDocument()
  })

  it('a Company Admin is not counted as having no assignment — they legitimately have none', async () => {
    // NEWCOMER is here so the panel has a gap to draw at all — with only ADMIN, LEAD and ENGINEER
    // there are none, and the assertion would pass against a panel that renders nothing.
    renderPage(aChart({ people: [ADMIN, LEAD, ENGINEER, NEWCOMER] }))

    const gaps = await screen.findByTestId('OrgChart.Gaps.List')
    const noAssignment = within(gaps).getByTestId('OrgChart.Gaps.NoAssignment')
    // Otherwise the panel reports the person with the most access in the company as a vacancy.
    expect(noAssignment).not.toHaveTextContent('A. Admin')
    expect(noAssignment).toHaveTextContent('N. Newcomer')
  })

  it('says so plainly when there are no gaps at all', async () => {
    renderPage(
      aChart({
        people: [LEAD, ENGINEER],
        teams: [aTeam({ id: 't-qa', name: 'QA', leads: [aUserRef({ id: LEAD.id })] })],
      }),
    )

    expect(await screen.findByTestId('OrgChart.Gaps.None')).toBeInTheDocument()
  })

  it('renders the teams region when the matrix is empty — one document, separate emptiness', async () => {
    // A company whose reader can open no project at all: no columns, no people worth a grid, and a
    // team structure that is still worth reading.
    renderPage(aChart({ projects: [], people: [] }))

    await waitFor(() => {
      expect(screen.getByTestId('OrgChart.Teams.List')).toBeInTheDocument()
    })
    expect(screen.getByTestId('OrgChart.Matrix.Region.Empty')).toBeInTheDocument()
  })
})
