import type { OrgChart, OrgChartGap } from '../../api/types';
import { Badge, Banner } from '../../components/kit';
import { formatCount } from '../../lib/format';
import { permissionLabels } from '../../lib/permissions';

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
 *
 * ## The fourth gap is the server's, and it is the only one that could not be derived here (005 S6)
 *
 * *"Which permissions does no active member of this project hold?"* cannot be computed from the
 * document above: the chart names **roles**, and which permissions a role carries is the catalogue
 * and §A3's matrix, which this client deliberately does not hold a second copy of — it checks
 * permissions, never role names. So `OrgChart.gaps` is computed server-side and rendered here.
 *
 * It is grouped **by project**, because that is the unit somebody acts on: "nobody on Payments can
 * approve a case" is a staffing decision, and the same fact spread across a list of permissions is
 * not. Every key in the catalogue is a candidate, so a key this build cannot label renders as its
 * own text rather than being dropped — a gap the client is too old to name is still a gap.
 *
 * ## A gap that exists is an amber banner (007 T13)
 *
 * `renders/04-board-and-people.html` frame C draws each live gap as `.banner.warn` and says why:
 * *"an org chart that only shows what exists is a directory"*. Design language §5 gives amber to a
 * state that is **currently wrong**, which is exactly what a vacancy is — so a gap with a count is
 * a banner and a gap with none is one quiet line. ~~`Alert severity="success"`~~ for the healthy
 * case is `neutral`: the kit has no success tone, and a company with no vacancies is a
 * consequence-free statement of fact rather than a celebration.
 */
export function OrgChartGaps({ chart }: { chart: OrgChart }) {
  const inNoTeam = chart.people.filter((person) => person.teams.length === 0);
  const withoutALead = chart.teams.filter((team) => team.leads.length === 0);
  const withNoCell = chart.people.filter(
    (person) => person.projects.length === 0 && person.companyRoles.length === 0,
  );

  const gapsByProject = groupGaps(chart);

  if (
    inNoTeam.length === 0
    && withoutALead.length === 0
    && withNoCell.length === 0
    && gapsByProject.length === 0
  ) {
    return (
      <Banner tone="neutral" data-testid="OrgChart.Gaps.None">
        Everybody is in a team, every team has a lead, everybody has a role on at least one project
        you can open, and every project has somebody who can do each thing.
      </Banner>
    );
  }

  return (
    <div className="flex flex-col gap-2.5" data-testid="OrgChart.Gaps.List">
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
      {/*
        One section per project with an uncovered permission, rather than one flat list: the
        remedy is per project — grant somebody a role there — and a flat list would make the
        reader group it themselves before they could act on any of it.

        Rendered only where there IS a gap, unlike the three above, which show their zero. Those
        three are questions with a standing answer worth seeing; this one is a list of projects,
        and a row per healthy project would bury the unhealthy ones in it.
      */}
      {gapsByProject.map(({ projectId, projectName, permissions }) => (
        <Gap
          key={projectId}
          testId="OrgChart.Gaps.Uncovered"
          title={`Nobody on ${projectName} can`}
          count={permissions.length}
          labels={permissions.map(labelForPermission)}
        />
      ))}
    </div>
  );
}

/**
 * One gap. Rendered even at zero, with the count, rather than hidden — a section that disappears
 * when it is healthy makes the panel's shape change under the reader, and "teams with no lead" is
 * worth being able to see the answer to when the answer is none.
 *
 * The zero form is deliberately **not** a banner: a strip of amber that says "None." is the shape
 * of a problem wearing the words of a healthy state, and by the fourth one the reader has stopped
 * reading the words.
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
  if (count === 0) {
    return (
      <p className="m-0 text-[12.5px] text-ink-4" data-testid={testId}>
        {title} ({formatCount(count)}) — none.
      </p>
    );
  }

  return (
    <Banner tone="warn" data-testid={testId}>
      <div className="flex min-w-0 flex-col gap-1.5">
        <span className="font-medium text-ink">
          {title} ({formatCount(count)})
        </span>
        <span className="flex flex-wrap gap-1.5">
          {labels.map((label) => (
            <Badge key={label} tone="plain">
              {label}
            </Badge>
          ))}
        </span>
      </div>
    </Banner>
  );
}

/**
 * `OrgChart.gaps` regrouped by project, in the chart's own column order.
 *
 * The order matters more than it looks: the matrix above renders projects in `chart.projects`
 * order, and a panel that listed them alphabetically would have the reader hunting between two
 * orderings of the same set. A gap naming a project the document does not carry is skipped — it
 * cannot be labelled, and a section headed by a uuid is worse than one absent.
 */
function groupGaps(
  chart: OrgChart,
): { projectId: string; projectName: string; permissions: OrgChartGap[] }[] {
  return chart.projects
    .map((project) => ({
      projectId: project.id,
      projectName: project.name,
      permissions: chart.gaps.filter((gap) => gap.projectId === project.id),
    }))
    .filter((group) => group.permissions.length > 0);
}

/**
 * A catalogue key as the verb phrase this product uses everywhere else for it.
 *
 * Falls back to the raw key rather than dropping the entry: the contract's own description says
 * every key in the catalogue is a candidate, so a client older than the server must still render
 * the gap — legibly if it can, verbatim if it cannot.
 */
function labelForPermission(gap: OrgChartGap): string {
  return (permissionLabels as Record<string, string>)[gap.permission] ?? gap.permission;
}
