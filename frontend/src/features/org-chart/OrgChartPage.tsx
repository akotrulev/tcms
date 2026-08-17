import { Alert, Box, Stack } from '@mui/material';
import { useOrgChart } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { OrgChartGaps } from './OrgChartGaps';
import { OrgChartMatrix } from './OrgChartMatrix';
import { OrgChartTeams } from './OrgChartTeams';

/**
 * The org chart (plan §6k) — who is here, which team they are in, and what they can do.
 *
 * **Gated on nothing**, which is the decision this screen exists on the far side of. Org structure
 * is not sensitive *within* a company and hiding it defeats the purpose; §84 recorded that
 * inventing a permission key for it would be a 403 no administrator could grant their way out of.
 * So there is no `PermissionGate` on this page and no `enabled` term on its query — and that is
 * why it lives at `/org-chart` rather than under `/admin`, where every affordance answers to
 * `company:teams`. The URL is the part of a screen people quote to each other.
 *
 * **One query, three regions.** The three axes have to come from one snapshot or the grid's rows,
 * columns and cells were read at three different moments. A `DataRegion` per region regardless:
 * they share a failure — honestly, since they share a request — but they do not share an *empty*,
 * and a company with teams and no visible projects should get a drawn tree beside an empty matrix
 * rather than one verdict for the page.
 */
export function OrgChartPage() {
  const chart = useOrgChart();
  const state = { ...chart, refetch: () => void chart.refetch() };

  return (
    <Box>
      <PageHeader
        title="Org chart"
        documentTitle="Org chart"
        subtitle="The company's people and teams, and what they can do on your projects."
        testId="OrgChart"
      />

      <Stack sx={{ gap: 3 }}>
        <Panel title="People and projects" testId="OrgChart.Matrix">
          {/*
            §9.4, and it is NOT optional and NOT conditional.

            The project axis is clipped to the projects this reader can open, so somebody missing
            six of the company's eight projects gets a two-column grid THAT LOOKS COMPLETE.
            Rendering this line only when something is hidden would make its presence the existence
            claim the plan's §4.3 rejected — the "+3 more" count wearing a different hat — so it
            renders for every reader including a Company Admin who sees everything. It needs no
            server field, which is why the document has no `viewer` block.
          */}
          <Alert severity="info" sx={{ mb: 2 }} data-testid="OrgChart.Matrix.Scope">
            Only projects you can open are shown here. A colleague may see more columns than you
            do, or fewer.
          </Alert>

          <DataRegion
            testId="OrgChart.Matrix.Region"
            state={state}
            isEmpty={(data) => data.people.length === 0}
          >
            {(data) => (
              <ScrollX>
                <OrgChartMatrix people={data.people} projects={data.projects} />
              </ScrollX>
            )}
          </DataRegion>
        </Panel>

        <Panel title="Teams" testId="OrgChart.Teams">
          <DataRegion
            testId="OrgChart.Teams.Region"
            state={state}
            isEmpty={(data) => data.teams.length === 0}
          >
            {(data) => <OrgChartTeams teams={data.teams} />}
          </DataRegion>
        </Panel>

        <Panel title="Gaps" testId="OrgChart.Gaps">
          <DataRegion testId="OrgChart.Gaps.Region" state={state}>
            {(data) => <OrgChartGaps chart={data} />}
          </DataRegion>
        </Panel>
      </Stack>
    </Box>
  );
}
