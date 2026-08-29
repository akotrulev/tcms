import type { OrgChartAssignment, OrgChartPerson, OrgChartProject } from '../../api/types';
import { Badge } from '../../components/kit';
import { cn } from '../../lib/cn';
import { assignmentViaLabels } from '../../lib/labels';

/**
 * People × the projects **this reader** can open.
 *
 * The columns are dynamic because they are the caller's own reach — a project they cannot open
 * contributes no column, no cell and no count, so two colleagues open this and legitimately see
 * grids of different widths. `OrgChartPage` states that unconditionally beside the table; a matrix
 * that only said so when something was hidden would make its own presence the disclosure.
 *
 * <h2>Why this is a plain table and not a grid component, against the plan's own §9.2</h2>
 *
 * ~~The plan says DataGrid with dynamic columns, and DataGrid is what `CaseGrid` uses.~~ →
 * the MUI data grid left the tree in 007 T9 and `CaseGrid` runs on TanStack Table; the argument
 * below survives the swap unchanged, because neither reason was about MUI:
 *
 *   1. **Every grid feature this screen would use is absent.** The whole document arrives in one
 *      response, so there is no server-side paging, sorting or filtering to mode-switch — which is
 *      what `CaseGrid` is actually built around. What would be left is a table with a scrollbar.
 *   2. **A virtualized grid does not render in jsdom**, because its virtualization measures through
 *      a `ResizeObserver` that the test shim never fires, so the scroller comes back 0×0 with no
 *      rows in it. Paying that price here would leave the §9.4 standing line, the
 *      Company-Admin-has-no-cell rule and the `via` labels — the three things on this screen most
 *      worth pinning — assertable only through a browser pass.
 *
 * Nothing here branches on a role name: the strings are display only (plan §6b), which is also why
 * a cell renders the name it was given rather than mapping it through anything.
 *
 * <h2>Three arms, three fills — and the words stay (007 T13)</h2>
 *
 * `renders/04-board-and-people.html` frame C is the source: a direct grant, a grant through a team
 * and a company-wide default are three fills, because *"a matrix built the obvious way draws direct
 * and team grants identically and omits company-wide defaults entirely"*.
 *
 * **The render's legend is deliberately not ported.** It decodes a fill for cells that carry only a
 * role name; ours carry the arm **in words** on the cell's second line, which is the stronger form
 * of the same information — colour is never the only carrier — and a legend repeating those three
 * phrases underneath would put every one of them on screen twice.
 *
 * The column heads are rotated rather than truncated, which is the render's other rule here: a
 * project name is a distinguishing name (UX#113) and must stay whole.
 */
export function OrgChartMatrix({
  people,
  projects,
}: {
  people: OrgChartPerson[];
  projects: OrgChartProject[];
}) {
  return (
    <table
      data-testid="OrgChart.Matrix.Grid"
      className="w-full border-separate border-spacing-[5px] text-xs"
    >
      <thead>
        <tr>
          <th className="px-1 pb-1 text-left text-[11px] font-medium text-ink-3">Person</th>
          <th className="px-1 pb-1 text-left text-[11px] font-medium text-ink-3">Company</th>
          <th className="px-1 pb-1 text-left text-[11px] font-medium text-ink-3">Teams</th>
          {projects.map((project) => (
            <th
              key={project.id}
              data-testid="OrgChart.Matrix.Column"
              className="h-[100px] p-0 pb-1.5 align-bottom"
            >
              <div className="flex rotate-180 items-center gap-1.5 whitespace-nowrap [writing-mode:vertical-rl]">
                <span className="text-[11px] font-medium text-ink-3">{project.name}</span>
                <span className="font-mono text-[10.5px] text-ink-4">{project.key}</span>
              </div>
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {people.map((person) => (
          <tr key={person.id} data-testid="OrgChart.Matrix.Row">
            <td className="whitespace-nowrap pr-2 align-middle">
              <span className="block text-[13px] text-ink-2">{person.displayName}</span>
              <span className="block text-[11px] text-ink-4">{person.email}</span>
            </td>
            <td className="align-middle">
              {/*
                On the PERSON, never smeared across the project columns. A COMPANY-scope role is
                authority over the company rather than a default for its projects, so a Company
                Admin correctly shows a badge here and NO cell anywhere to the right — and an empty
                row of cells for them is the truth rather than "they have no access".
              */}
              <span className="flex flex-wrap gap-1">
                {person.companyRoles.map((role) => (
                  <Badge key={role} tone="steel">
                    {role}
                  </Badge>
                ))}
              </span>
            </td>
            <td className="align-middle">
              <span className="flex flex-wrap gap-1">
                {person.teams.map((team) => (
                  <Badge
                    key={team.id}
                    tone={person.leadsTeams.some((led) => led.id === team.id) ? 'steel' : 'plain'}
                  >
                    {team.name}
                  </Badge>
                ))}
              </span>
            </td>
            {projects.map((project) => (
              <td key={project.id} className="align-middle">
                <Cell assignment={cellFor(person, project.id)} />
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function cellFor(person: OrgChartPerson, projectId: string): OrgChartAssignment | undefined {
  return person.projects.find((assignment) => assignment.projectId === projectId);
}

/** `.cell.d` / `.cell.t` / `.cell.i` — one fill per arm, in the render's own colours. */
const VIA_FILL: Record<OrgChartAssignment['via'], string> = {
  DIRECT:
    'border-[color:color-mix(in_oklab,var(--color-steel)_22%,transparent)] ' +
    'bg-[color:color-mix(in_oklab,var(--color-steel)_12%,transparent)] text-[color:var(--color-steel)]',
  TEAM:
    'border-[color:color-mix(in_oklab,var(--color-passed)_26%,transparent)] ' +
    'bg-[color:color-mix(in_oklab,var(--color-passed)_12%,transparent)] text-[color:var(--color-passed)]',
  INHERITED: 'border-white/8 bg-white/4 text-ink-3',
};

/**
 * One cell. An absent assignment renders an em dash rather than nothing, so "no role here" and
 * "the table did not draw this cell" are distinguishable at a glance.
 *
 * ~~The arm was a tooltip.~~ → 007 T13: it is the cell's second line. A fact that decides whether
 * a grant can be revoked from the team page or has to be revoked from the project is not something
 * to hover for, and a tooltip is unreachable on the touch device the render is drawn for.
 */
function Cell({ assignment }: { assignment: OrgChartAssignment | undefined }) {
  if (!assignment) {
    return (
      <span
        data-testid="OrgChart.Matrix.Cell.Empty"
        className="grid h-[30px] place-items-center rounded-[5px] font-mono text-[11px] text-ink-4"
      >
        —
      </span>
    );
  }

  const via = assignmentViaLabels[assignment.via];
  const detail = assignment.viaTeam ? `${via}: ${assignment.viaTeam.name}` : via;

  return (
    <span
      data-testid="OrgChart.Matrix.Cell"
      className={cn(
        'flex flex-col items-center gap-px rounded-[5px] border px-2 py-1 text-center',
        VIA_FILL[assignment.via],
      )}
    >
      <span className="font-mono text-[11px] leading-tight">{assignment.roleName}</span>
      <span className="text-[10.5px] leading-tight text-ink-4">{detail}</span>
    </span>
  );
}
