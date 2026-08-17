import { Alert, Chip, Stack, Typography } from '@mui/material';
import type { OrgChart } from '../../api/types';
import { formatCount } from '../../lib/format';

/**
 * The vacancies — **the reason somebody opens an org chart in the first place** (plan §6k).
 *
 * Three questions, each of which is invisible on the matrix itself because the answer is an
 * *absence*: a person in no team is a blank in a column nobody scans, a team with no lead is a
 * missing chip, and a person with no assignment is a row of em dashes among forty other rows.
 *
 * **Pure presentation over the document already served.** No second query and no second
 * authorization path — which is not an optimization but the whole safety property: the third gap
 * below is computed from `person.projects`, and those are already clipped to what this reader may
 * see, so it can never name a project or a person's reach the reader was not already shown.
 *
 * That clipping is also why the third gap's wording is careful. "No assignment on any project you
 * can open" is the true claim; "no assignments" would be a claim about the company that this
 * reader's document cannot support.
 */
export function OrgChartGaps({ chart }: { chart: OrgChart }) {
  const inNoTeam = chart.people.filter((person) => person.teams.length === 0);
  const withoutALead = chart.teams.filter((team) => team.leads.length === 0);
  const withNoCell = chart.people.filter(
    (person) => person.projects.length === 0 && person.companyRoles.length === 0,
  );

  if (inNoTeam.length === 0 && withoutALead.length === 0 && withNoCell.length === 0) {
    return (
      <Alert severity="success" data-testid="OrgChart.Gaps.None">
        Everybody is in a team, every team has a lead, and everybody has a role on at least one
        project you can open.
      </Alert>
    );
  }

  return (
    <Stack sx={{ gap: 2 }} data-testid="OrgChart.Gaps.List">
      <Gap
        testId="OrgChart.Gaps.NoTeam"
        title="In no team"
        count={inNoTeam.length}
        labels={inNoTeam.map((person) => person.displayName)}
      />
      <Gap
        testId="OrgChart.Gaps.NoLead"
        title="Teams with no lead"
        count={withoutALead.length}
        labels={withoutALead.map((team) => team.name)}
      />
      <Gap
        testId="OrgChart.Gaps.NoAssignment"
        title="No role on any project you can open"
        count={withNoCell.length}
        labels={withNoCell.map((person) => person.displayName)}
      />
    </Stack>
  );
}

/**
 * One gap. Rendered even at zero, with the count, rather than hidden — a section that disappears
 * when it is healthy makes the panel's shape change under the reader, and "teams with no lead" is
 * worth being able to see the answer to when the answer is none.
 */
function Gap({
  testId,
  title,
  count,
  labels,
}: {
  testId: string;
  title: string;
  count: number;
  labels: string[];
}) {
  return (
    <Stack sx={{ gap: 0.75 }} data-testid={testId}>
      <Typography variant="subtitle2">
        {title} ({formatCount(count)})
      </Typography>
      {count === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          None.
        </Typography>
      ) : (
        <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
          {labels.map((label) => (
            <Chip key={label} size="small" label={label} />
          ))}
        </Stack>
      )}
    </Stack>
  );
}
