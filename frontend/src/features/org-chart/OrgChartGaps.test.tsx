import { ThemeProvider } from '@mui/material/styles'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { theme } from '../../app/theme'
import { anOrgChart, anOrgChartPerson, anOrgChartProject } from '../../test/fixtures'
import { OrgChartGaps } from './OrgChartGaps'

/**
 * The vacancies panel, and in particular the **fourth** gap (005 S6) — the permissions no active
 * member of a project holds.
 *
 * The first three are derived from the document the chart already carries. This one cannot be: the
 * chart names roles, and which permissions a role carries is §A3's matrix, which this client
 * deliberately does not hold a second copy of. So the assertions here are about rendering what the
 * server computed, and about the two ways of rendering it that would be wrong — dropping a key the
 * catalogue does not know, and grouping by anything other than the project.
 */

function renderGaps(chart = anOrgChart()) {
  return render(
    <ThemeProvider theme={theme}>
      <OrgChartGaps chart={chart} />
    </ThemeProvider>,
  )
}

describe('OrgChartGaps', () => {
  it('says everything is covered when nothing is missing', () => {
    renderGaps()
    expect(screen.getByTestId('OrgChart.Gaps.None')).toBeInTheDocument()
  })

  it('names the project and labels the permission from the catalogue', () => {
    renderGaps(
      anOrgChart({
        projects: [anOrgChartProject({ id: 'pr1', name: 'Payments' })],
        gaps: [{ projectId: 'pr1', permission: 'case:approve' }],
      }),
    )

    const gap = screen.getByTestId('OrgChart.Gaps.Uncovered')
    expect(gap).toHaveTextContent('Payments')
    expect(gap).toHaveTextContent('approve test cases')
  })

  it('renders a key this build cannot label as its own text rather than dropping it', () => {
    // Every key in the catalogue is a candidate, so a client older than the server must still show
    // the gap: legibly if it can, verbatim if it cannot. A dropped row is a gap nobody hears about.
    renderGaps(
      anOrgChart({
        projects: [anOrgChartProject({ id: 'pr1', name: 'Payments' })],
        gaps: [{ projectId: 'pr1', permission: 'future:thing' }],
      }),
    )
    expect(screen.getByTestId('OrgChart.Gaps.Uncovered')).toHaveTextContent('future:thing')
  })

  it('groups by project, because the remedy is per project', () => {
    renderGaps(
      anOrgChart({
        projects: [
          anOrgChartProject({ id: 'pr1', name: 'Payments' }),
          anOrgChartProject({ id: 'pr2', name: 'Search' }),
        ],
        gaps: [
          { projectId: 'pr1', permission: 'case:approve' },
          { projectId: 'pr2', permission: 'run:close' },
        ],
      }),
    )
    expect(screen.getAllByTestId('OrgChart.Gaps.Uncovered')).toHaveLength(2)
  })

  it('renders no section for a project with nothing missing', () => {
    // Unlike the three gaps above it, which show their zero: those are questions with a standing
    // answer, this one is a list of projects, and a row per healthy project buries the rest.
    renderGaps(
      anOrgChart({
        people: [anOrgChartPerson()],
        projects: [anOrgChartProject({ id: 'pr1', name: 'Payments' })],
        gaps: [],
      }),
    )
    expect(screen.queryByTestId('OrgChart.Gaps.Uncovered')).not.toBeInTheDocument()
  })
})
