import {
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import type { OrgChartAssignment, OrgChartPerson, OrgChartProject } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { assignmentViaLabels } from '../../lib/labels';

/**
 * People × the projects **this reader** can open.
 *
 * The columns are dynamic because they are the caller's own reach — a project they cannot open
 * contributes no column, no cell and no count, so two colleagues open this and legitimately see
 * grids of different widths. `OrgChartPage` states that unconditionally beside the table; a matrix
 * that only said so when something was hidden would make its own presence the disclosure.
 *
 * <h2>Why this is a `Table` and not `DataGrid`, against the plan's own §9.2</h2>
 *
 * The plan says DataGrid with dynamic columns, and DataGrid is what `CaseGrid` uses. Two reasons
 * not to here, and the second is the one that decided it:
 *
 *   1. **Every DataGrid feature this screen would use is absent.** The whole document arrives in
 *      one response, so there is no server-side paging, sorting or filtering to mode-switch — which
 *      is what `CaseGrid` is actually built around. What would be left is a table with a scrollbar.
 *   2. **DataGrid does not render in jsdom**, because its virtualization measures through a
 *      `ResizeObserver` that the test shim never fires, so the scroller comes back 0×0 with no rows
 *      in it. That is not hypothetical: `CaseListPage` is the repo's only DataGrid screen and has
 *      **no component test at all**. Paying that price here would leave the §9.4 standing line, the
 *      Company-Admin-has-no-cell rule and the `via` labels — the three things on this screen most
 *      worth pinning — assertable only through a browser pass that §65 has deferred.
 *
 * The measured bundle delta is the other half of the argument and it is recorded in the slice's
 * handoff. Nothing here branches on a role name: the strings are display only (plan §6b), which is
 * also why a cell renders the name it was given rather than mapping it through anything.
 */
export function OrgChartMatrix({
  people,
  projects,
}: {
  people: OrgChartPerson[];
  projects: OrgChartProject[];
}) {
  return (
    <Table size="small" data-testid="OrgChart.Matrix.Grid">
      <TableHead>
        <TableRow>
          <TableCell>Person</TableCell>
          <TableCell>Company</TableCell>
          <TableCell>Teams</TableCell>
          {projects.map((project) => (
            <TableCell key={project.id} data-testid="OrgChart.Matrix.Column">
              <Stack sx={{ gap: 0.25 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {project.name}
                </Typography>
                <Identifier variant="muted">{project.key}</Identifier>
              </Stack>
            </TableCell>
          ))}
        </TableRow>
      </TableHead>
      <TableBody>
        {people.map((person) => (
          <TableRow key={person.id} data-testid="OrgChart.Matrix.Row">
            <TableCell>
              <Typography variant="body2">{person.displayName}</Typography>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                {person.email}
              </Typography>
            </TableCell>
            <TableCell>
              {/*
                On the PERSON, never smeared across the project columns. A COMPANY-scope role is
                authority over the company rather than a default for its projects, so a Company
                Admin correctly shows a badge here and NO cell anywhere to the right — and an empty
                row of cells for them is the truth rather than "they have no access".
              */}
              <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                {person.companyRoles.map((role) => (
                  <Chip key={role} size="small" color="primary" variant="outlined" label={role} />
                ))}
              </Stack>
            </TableCell>
            <TableCell>
              <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                {person.teams.map((team) => (
                  <Chip
                    key={team.id}
                    size="small"
                    variant={
                      person.leadsTeams.some((led) => led.id === team.id) ? 'filled' : 'outlined'
                    }
                    label={team.name}
                  />
                ))}
              </Stack>
            </TableCell>
            {projects.map((project) => (
              <TableCell key={project.id}>
                <Cell assignment={cellFor(person, project.id)} />
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function cellFor(person: OrgChartPerson, projectId: string): OrgChartAssignment | undefined {
  return person.projects.find((assignment) => assignment.projectId === projectId);
}

/**
 * One cell. An absent assignment renders an em dash rather than nothing, so "no role here" and
 * "the table did not draw this cell" are distinguishable at a glance.
 */
function Cell({ assignment }: { assignment: OrgChartAssignment | undefined }) {
  if (!assignment) {
    return (
      <Typography
        variant="body2"
        sx={{ color: 'text.disabled' }}
        data-testid="OrgChart.Matrix.Cell.Empty"
      >
        —
      </Typography>
    );
  }

  const via = assignmentViaLabels[assignment.via];
  const detail = assignment.viaTeam ? `${via}: ${assignment.viaTeam.name}` : via;

  return (
    <Tooltip title={detail}>
      <Stack sx={{ gap: 0.25 }} data-testid="OrgChart.Matrix.Cell">
        <Typography variant="body2">{assignment.roleName}</Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {detail}
        </Typography>
      </Stack>
    </Tooltip>
  );
}
