import { useOrgChart } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { Banner } from '../../components/kit';
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
 *
 * <h2>The layout is `renders/04-board-and-people.html` frame C (007 T13)</h2>
 *
 * ~~Three stacked `Panel`s.~~ → the render's `.split.wide-side`: the matrix is the subject and gets
 * a **bare** pane (rule 1 — no panel around the only thing on screen), while Teams and Gaps are the
 * side column's two `.sec` sections, which is where the render puts the two things you read
 * *about* the matrix rather than *in* it.
 *
 * The three regions stay mounted side by side rather than behind the render's lens tabs. Gaps are
 * *"the point of the screen"* by that render's own note, and a tab is a place to put something you
 * are content for the reader not to see.
 */
export function OrgChartPage() {
  const chart = useOrgChart();
  const state = { ...chart, refetch: () => void chart.refetch() };

  return (
    <div>
      <PageHeader
        testId="OrgChart"
        title="Org chart"
        subtitle="The company's people and teams, and what they can do on your projects."
      />

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
        <section className="min-w-0" data-testid="OrgChart.Matrix" aria-label="People and projects">
          {/*
            §9.4, and it is NOT optional and NOT conditional.

            The project axis is clipped to the projects this reader can open, so somebody missing
            six of the company's eight projects gets a two-column grid THAT LOOKS COMPLETE.
            Rendering this line only when something is hidden would make its presence the existence
            claim the plan's §4.3 rejected — the "+3 more" count wearing a different hat — so it
            renders for every reader including a Company Admin who sees everything. It needs no
            server field, which is why the document has no `viewer` block.

            `steel` rather than `neutral`: design language §5 gives steel to something the instance
            itself is telling the reader about what they are looking at, which is exactly what a
            statement about the shape of this table is.
          */}
          <Banner tone="steel" data-testid="OrgChart.Matrix.Scope" className="mb-3">
            Only projects you can open are shown here. A colleague may see more columns than you
            do, or fewer.
          </Banner>

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
        </section>

        <aside className="flex flex-col rounded-xl border border-line bg-panel">
          <section className="border-b border-line p-4" data-testid="OrgChart.Teams">
            <h2 className="m-0 mb-3 text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
              Teams
            </h2>
            <DataRegion
              testId="OrgChart.Teams.Region"
              state={state}
              isEmpty={(data) => data.teams.length === 0}
            >
              {(data) => <OrgChartTeams teams={data.teams} />}
            </DataRegion>
          </section>

          <section className="p-4" data-testid="OrgChart.Gaps">
            <h2 className="m-0 mb-3 text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
              Gaps
            </h2>
            <DataRegion testId="OrgChart.Gaps.Region" state={state}>
              {(data) => <OrgChartGaps chart={data} />}
            </DataRegion>
          </section>
        </aside>
      </div>
    </div>
  );
}
