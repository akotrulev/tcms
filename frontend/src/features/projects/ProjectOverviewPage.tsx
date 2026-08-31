import { Hexagon } from 'lucide-react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  EPIC_COMPLETION_LIMIT,
  useDashboard,
  useEpicCompletions,
  useIssues,
  useProject,
} from '../../api/queries';
import type { IssueCompletion, IssueSummary, RepositoryDashboard } from '../../api/types';
import { DataRegion, EmptyState } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatCount, formatPercent, plural, pluralNoun } from '../../lib/format';
import { labelForKey, lastRunLabels } from '../../lib/labels';
import { statusVar } from '../../lib/statusColors';
import { CompletionMeter } from '../tracker/CompletionMeter';
import { StatusBadge } from '../tracker/StatusBadge';
import { TypeIcon } from '../tracker/TypeIcon';
import { DashboardBody } from '../dashboard/DashboardPage';

const TEST_ID = 'ProjectOverview';

/**
 * `project-overview.html` — **where opening a project lands** (D27): delivery and quality together.
 *
 * ~~`DashboardPage`, the repository status dashboard, was this route.~~ → **superseded by 011 T7.**
 * D27 says the overview *reuses the existing DashboardPage regions* as its quality half, and it does
 * — {@link DashboardBody} is mounted below, unchanged, so nothing that screen showed was lost. What
 * is new above it is the delivery half the tracker made possible: the epics with their completion
 * meters, the unscheduled backlog, and two KPI tiles.
 *
 * ## `solo-overview.html` is not a second screen — it is this one, and an acceptance criterion
 *
 * *"The same screen, for the company that has no clients. There is no Client chip in the header, no
 * client column anywhere, and no empty 'attach a client' prompt."*
 *
 * In M1 that criterion holds **structurally**: `Project` carries no client at all — clients are
 * module Mc — so there is nothing here that could render an empty `Client: —` chip, which is exactly
 * what a naive port produces. It is asserted anyway, in this page's test, so that the day Mc adds
 * the field the absence is a decision somebody has to reverse deliberately rather than a chip that
 * appears empty on every internal project.
 *
 * The solo case's *other* half is not structural and is built: `solo-overview.html` replaces
 * "Unscheduled backlog" with **"Quick tasks — no epic, no story, no ceremony"**, and that is the
 * same rows, the same request, one filter — a project with no epics *is* a project whose whole
 * backlog is quick tasks. See {@link BacklogPanel}.
 *
 * ## A section M1 has no data for explains itself; it does not render empty
 *
 * {@link NextReleasePanel} is the case, and the difference between it and the two panels that are
 * not here at all is the whole rule:
 *
 * - **Next release** renders `solo-overview.html`'s empty state, because a release is a thing this
 *   project *will* have and has none **yet**.
 * - **Budget burn** (M6) and **Team** (M7) are **not rendered at all**. A budget is a module that is
 *   not installed, and an empty-state card for one is an advertisement rather than an explanation.
 *
 * The two KPI tiles the mockup carries and this page does not are the same call: `2.4 complete` and
 * `To target date` are both a release's numbers (M3).
 */
export function ProjectOverviewPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const epics = useIssues(projectId, { level: 'EPIC' }, 'key');
  const backlog = useIssues(projectId, { unparented: true }, 'key');
  const dashboard = useDashboard(projectId);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title={project.data?.name ?? 'Overview'}
        documentTitle={project.data ? `Overview · ${project.data.name}` : 'Overview'}
        meta={
          project.data && (
            <>
              <Identifier variant="strong">{project.data.key}</Identifier>
              <span className="text-[13px] text-ink-3">
                <Identifier variant="muted">{formatCount(project.data.caseCount)}</Identifier>{' '}
                {pluralNoun(project.data.caseCount, 'case')}
              </span>
              {/* Omitted rather than empty when myRole is null — see ProjectsPage for why a
                  COMPANY-scope grant legitimately has no per-project role name. */}
              {project.data.myRole !== null && (
                <span className="text-[13px] text-ink-3">Your role: {project.data.myRole}</span>
              )}
              {/*
                Stated on the project's own landing page as well as on the picker card, because this
                is where somebody arrives from a bookmark — and a retired project that looks
                identical to a live one is how work gets filed into it for another six months.

                A badge, not a chip: archived is state the system owns and there is nothing behind
                clicking it (design language §5). The button that changes it is on
                `settings-general.html`'s Danger zone, where the mockups put it.
              */}
              {project.data.archived && (
                <Badge tone="plain" data-testid={`${TEST_ID}.Archived`}>
                  Archived
                </Badge>
              )}
            </>
          )
        }
        subtitle="Delivery and quality, together — this is where opening a project lands."
        actions={
          <>
            <Button asChild data-testid={`${TEST_ID}.Settings`}>
              <RouterLink to={`/projects/${projectId}/settings/general`}>Settings</RouterLink>
            </Button>
            <Button asChild variant="primary" data-testid={`${TEST_ID}.OpenWork`}>
              <RouterLink to={`/projects/${projectId}/work`}>Open work</RouterLink>
            </Button>
          </>
        }
      />

      <div className="mb-3.5 grid gap-3.5 sm:grid-cols-2" data-testid={`${TEST_ID}.Tiles`}>
        <OpenIssuesTile projectId={projectId} />
        <TestsPassingTile projectId={projectId} />
      </div>

      <div className="grid gap-3.5 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="flex flex-col gap-3.5">
          <EpicsPanel projectId={projectId} epics={epics} />
          <BacklogPanel projectId={projectId} backlog={backlog} epics={epics} />
        </div>

        <div className="flex flex-col gap-3.5">
          <NextReleasePanel projectId={projectId} />
          <TestHealthPanel projectId={projectId} dashboard={dashboard} />
        </div>
      </div>

      {/*
        D27's "the existing DashboardPage regions are reused" — mounted whole, below the delivery
        half rather than instead of it, because the repository dashboard was this route and removing
        it would have cost the product a screen to gain a layout.
      */}
      <section className="mt-4" data-testid={`${TEST_ID}.Repository`}>
        <h2 className="mb-2 font-mono text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase">
          Test repository
        </h2>
        <DataRegion
          testId={`${TEST_ID}.Repository.Region`}
          state={{ ...dashboard, refetch: () => void dashboard.refetch() }}
        >
          {(data) => <DashboardBody data={data} projectId={projectId} />}
        </DataRegion>
      </section>
    </div>
  );
}

function Tile({
  value,
  label,
  detail,
  testId,
}: {
  value: string;
  label: string;
  detail?: string;
  testId: string;
}) {
  return (
    <div className="rounded-[10px] border border-line bg-panel px-3.5 py-3" data-testid={testId}>
      <span className="block font-mono text-[24px] leading-[1.1] font-semibold text-ink">{value}</span>
      <span className="mt-1 block text-[12px] text-ink-2">{label}</span>
      {detail && <span className="mt-0.5 block text-[11.5px] text-ink-4">{detail}</span>}
    </div>
  );
}

/**
 * The mockup's second tile — **Open issues**, with how many of them are overdue.
 *
 * The mockup's sub-line reads *"6 blocked or overdue"*. "Blocked" is a status **name** a project
 * chose, not a kind, so counting it here would be a hardcoded English word deciding a number — the
 * failure `TypeIcon` refuses for its colours. `overdue` is on the wire and means one thing.
 *
 * Read from the same list the work table reads. Counted here rather than server-side, and that is
 * the one place on this page a count is: `GET /issues` has no total, the rows are all present, and
 * `length` of a list you are holding is not the second definition of a number D8 warns about — it
 * is the list.
 */
function OpenIssuesTile({ projectId }: { projectId: string }) {
  const issues = useIssues(projectId, {}, 'key');
  const open = (issues.data ?? []).filter((issue) => issue.status.kind !== 'DONE');
  const overdue = open.filter((issue) => issue.overdue).length;

  return (
    <DataRegion
      testId={`${TEST_ID}.OpenIssues`}
      state={{ ...issues, refetch: () => void issues.refetch() }}
    >
      {() => (
        <Tile
          value={formatCount(open.length)}
          label="Open issues"
          detail={overdue > 0 ? `${overdue} overdue` : 'none overdue'}
          testId={`${TEST_ID}.OpenIssues.Tile`}
        />
      )}
    </DataRegion>
  );
}

/**
 * The mockup's third tile — **Tests passing**, and its denominator is stated because it has to be.
 *
 * There is **no project-wide latest-result-per-case count on the contract.** What there is, is
 * `RepositoryDashboard.byLastRun` — one bar per Feature, split by the outcome of each case's last
 * execution — so a case with no Feature is in none of those rows. Summing them and calling the
 * result "of 154 cases" would be a percentage whose denominator silently excludes part of the
 * project, sitting on the same page as a case count that does not: the two-contradictory-numbers
 * failure this repo has shipped once.
 *
 * So the tile is summed from `byLastRun` and **says what it counted**, which is `IssueCompletion`'s
 * own mitigation applied to a different number: a percentage never changes meaning silently, and the
 * caption is the whole of it. It reads the same rows as the `By last run` figure below, so the tile
 * and the figure cannot disagree.
 *
 * **Not rendered at all when nothing has run**, rather than `0%`: no case with a Feature has a
 * result, and zero-per-cent-passing is a claim about quality that the absence of data cannot make.
 */
function TestsPassingTile({ projectId }: { projectId: string }) {
  const dashboard = useDashboard(projectId);
  const segments = (dashboard.data?.byLastRun ?? []).flatMap((row) => row.segments);
  const total = segments.reduce((sum, segment) => sum + segment.count, 0);
  const passed = segments
    .filter((segment) => segment.key === 'PASSED')
    .reduce((sum, segment) => sum + segment.count, 0);

  if (total === 0) {
    return (
      <div
        className="rounded-[10px] border border-line bg-panel px-3.5 py-3"
        data-testid={`${TEST_ID}.TestsPassing.None`}
      >
        <span className="block text-[12px] text-ink-2">Tests passing</span>
        <span className="mt-1 block max-w-[46ch] text-[11.5px] leading-[1.5] text-ink-4">
          No case with a feature has a recorded result yet, so there is no pass rate to state — not
          a pass rate of zero.
        </span>
      </div>
    );
  }

  return (
    <Tile
      value={formatPercent((passed * 100) / total, 0)}
      label="Tests passing"
      detail={`${formatCount(passed)} of ${formatCount(total)} · latest run per case, cases with a feature`}
      testId={`${TEST_ID}.TestsPassing.Tile`}
    />
  );
}

/**
 * The epics, each with its completion meter and its basis.
 *
 * **The completion is a second read per epic**, because `IssueSummary` does not carry one:
 * completion is epics-only and computed server-side (D8), so the only honest way to a meter is
 * `GET /issues/{key}` per epic. {@link useEpicCompletions} does that as one combined region state
 * and caps the count — see {@link EPIC_COMPLETION_LIMIT} and this panel's footer, which says when
 * the cap was reached rather than silently drawing fewer meters than there are epics.
 *
 * A meter is rendered only where a completion actually arrived. An epic whose read came back with
 * `completion: null` gets the row and no meter: that field is nullable on the wire, and a meter
 * reading 0% where the server sent nothing is a percentage of nothing.
 */
function EpicsPanel({
  projectId,
  epics,
}: {
  projectId: string;
  epics: ReturnType<typeof useIssues>;
}) {
  const rows = epics.data ?? [];
  const completions = useEpicCompletions(rows.map((epic) => epic.key));
  const capped = rows.length > EPIC_COMPLETION_LIMIT;

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Epics`}>
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Epics</h2>
        <Identifier variant="muted">{String(rows.length)}</Identifier>
        <span className="flex-1" />
        <PermissionGate projectId={projectId} permission="issue:create">
          {({ allowed }) =>
            allowed ? (
              <Button asChild size="sm" data-testid={`${TEST_ID}.Epics.New`}>
                <RouterLink to={`/projects/${projectId}/work/new`}>+ New epic</RouterLink>
              </Button>
            ) : (
              // Disabled rather than hidden (§31): a Viewer learns the capability exists.
              <Button size="sm" disabled data-testid={`${TEST_ID}.Epics.New`}>
                + New epic
              </Button>
            )
          }
        </PermissionGate>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Epics.Region`}
        state={{ ...epics, refetch: () => void epics.refetch() }}
        isEmpty={(data) => data.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No epics yet',
          body: 'An epic is a body of work with a goal — it holds stories, and its completion is computed from them. A project can run perfectly well without one.',
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-3 p-3.5">
            {data.map((epic) => (
              <RouterLink
                key={epic.key}
                to={`/issues/${encodeURIComponent(epic.key)}`}
                data-testid={`${TEST_ID}.Epics.Row`}
                className="block rounded-[8px] border border-line bg-paper px-2.5 py-2.5 no-underline hover:border-line-strong"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <TypeIcon type={epic.type} />
                  <strong className="text-[13px] text-ink">{epic.title}</strong>
                  <Identifier variant="muted">{epic.key}</Identifier>
                  <StatusBadge status={epic.status} />
                </div>

                <div className="mt-2">
                  <EpicCompletion
                    completion={completions.data?.[epic.key] ?? null}
                    pending={completions.isPending}
                    failed={completions.isError}
                  />
                </div>
              </RouterLink>
            ))}
          </div>
        )}
      </DataRegion>

      {capped && (
        <div className="border-t border-line px-3.5 py-2.5">
          <span className="text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Epics.Capped`}>
            {`Completion is shown for the first ${EPIC_COMPLETION_LIMIT} epics — each one is a read of its own, and a project with ${rows.length} of them is not asking what each one's percentage is.`}
          </span>
        </div>
      )}
    </section>
  );
}

/**
 * One epic's meter, or the honest absence of one.
 *
 * Three states and they are genuinely three: the reads are in flight, they failed, or they arrived
 * and this epic has no completion. Collapsing the last two would say "no percentage" about an epic
 * whose percentage merely could not be fetched.
 */
function EpicCompletion({
  completion,
  pending,
  failed,
}: {
  completion: IssueCompletion | null;
  pending: boolean;
  failed: boolean;
}) {
  if (pending) {
    return (
      <span className="text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Epics.Row.Loading`}>
        Counting…
      </span>
    );
  }
  if (failed) {
    return (
      <span className="text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Epics.Row.Failed`}>
        Completion could not be read — open the epic for its own figure.
      </span>
    );
  }
  if (completion === null) {
    return (
      <span className="text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Epics.Row.NoCompletion`}>
        No completion for this epic.
      </span>
    );
  }
  return <CompletionMeter completion={completion} testId={`${TEST_ID}.Epics.Row.Completion`} />;
}

/**
 * The unparented, story-level backlog — **and `solo-overview.html`'s "Quick tasks" is this panel.**
 *
 * The two mockups show the same rows under two headings, and which heading is right is decided by
 * one fact this page already has: a project with **no epics** is a project whose whole backlog is
 * quick tasks — *"no epic, no story, no ceremony"* — and a project with epics has a backlog of work
 * that is not scheduled into one yet. So this is a filter over the same list, never a second
 * endpoint, which is what T7's own acceptance criterion asks for.
 *
 * **`unparented` is a flag on the wire, not `epicKey=null`.** An absent parameter already means
 * "don't filter", and a client cannot spell the difference — the contract says so at the parameter.
 */
function BacklogPanel({
  projectId,
  backlog,
  epics,
}: {
  projectId: string;
  backlog: ReturnType<typeof useIssues>;
  epics: ReturnType<typeof useIssues>;
}) {
  /*
    Solo until proven otherwise would be wrong the other way: while the epics read is in flight
    `data` is undefined, and treating that as "no epics" would flash the quick-tasks heading on
    every load of every project that has them. An epic list that arrived and is empty is the only
    thing that means solo.
  */
  const solo = epics.data !== undefined && epics.data.length === 0;

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Backlog`}>
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink" data-testid={`${TEST_ID}.Backlog.Title`}>
          {solo ? 'Quick tasks' : 'Unscheduled backlog'}
        </h2>
        <span className="text-[11.5px] text-ink-4">
          {solo ? 'no epic, no story, no ceremony' : 'no epic'}
        </span>
        <span className="flex-1" />
        <Button asChild size="sm" data-testid={`${TEST_ID}.Backlog.All`}>
          <RouterLink to={`/projects/${projectId}/work`}>Open work table</RouterLink>
        </Button>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Backlog.Region`}
        state={{ ...backlog, refetch: () => void backlog.refetch() }}
        isEmpty={(data) => data.length === 0}
        empty={{
          kind: 'no-records',
          title: solo ? 'No tasks yet' : 'Nothing unscheduled',
          body: solo
            ? 'Everything here would be an issue with no epic above it. There are none.'
            : 'Every story-level issue in this project sits under an epic.',
        }}
      >
        {(data) => (
          <Table data-testid={`${TEST_ID}.Backlog.Table`}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>Key</TableHeaderCell>
                <TableHeaderCell>Issue</TableHeaderCell>
                <TableHeaderCell>Status</TableHeaderCell>
                <TableHeaderCell align="right">Est</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((issue) => (
                <BacklogRow key={issue.key} issue={issue} />
              ))}
            </TableBody>
          </Table>
        )}
      </DataRegion>
    </section>
  );
}

function BacklogRow({ issue }: { issue: IssueSummary }) {
  return (
    <TableRow data-testid={`${TEST_ID}.Backlog.Row`}>
      <TableCell>
        <Identifier variant="muted">{issue.key}</Identifier>
      </TableCell>
      <TableCell variant="title">
        <span className="flex items-center gap-1.5">
          <TypeIcon type={issue.type} />
          <RouterLink
            to={`/issues/${encodeURIComponent(issue.key)}`}
            className="text-inherit no-underline hover:text-steel hover:underline"
          >
            {issue.title}
          </RouterLink>
        </span>
      </TableCell>
      <TableCell>
        <StatusBadge status={issue.status} />
      </TableCell>
      <TableCell align="right">
        {issue.estimateHours != null ? (
          <Identifier variant="muted">{`${issue.estimateHours}h`}</Identifier>
        ) : (
          <span className="text-ink-4">&mdash;</span>
        )}
      </TableCell>
    </TableRow>
  );
}

/**
 * The next release, on a project that has none — **which in M1 is every project.**
 *
 * **A missing section explains itself; it does not render empty.** `solo-overview.html` is the
 * template and it is not a different screen: it is this screen for a company with no clients, and
 * its release panel is the empty state this one uses whenever there are no releases — a glyph, "No
 * releases yet", a sentence saying what a release IS and what it buys, and the action that creates
 * one.
 *
 * In M1 that state is the **only** state, because releases arrive in M3: there is no `/releases`
 * path on the contract, so this panel has nothing to read and no `Release` type to read it into. The
 * component boundary is here rather than the panel being written inline, so M3 turns it on by
 * fetching and passing the list — not by replacing what a reader has learned to look at.
 *
 * **The action is disabled with its reason**, and that is the one deviation from the mockup, whose
 * button links to `releases.html`. There is no releases route in this build, and a link to a page
 * that does not exist is the render-then-reject failure with the reject left to the reader. §31's
 * disable-don't-hide is the established answer: the affordance is visible, so somebody learns the
 * product will do this, and it says why it cannot yet.
 *
 * The footer is the sentence that keeps the page honest in the meantime, kept verbatim: *"the epics
 * section above keeps working regardless — the sections do not depend on each other."*
 */
function NextReleasePanel({ projectId }: { projectId: string }) {
  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Release`}>
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Next release</h2>
      </div>

      <EmptyState
        testId={`${TEST_ID}.Release.Empty`}
        kind="by-design"
        icon={Hexagon}
        title="No releases yet"
        body={`A release is a version you ship from this project — 0.9, 1.0 — with an optional target date. Epics and stories can point at one, and readiness is measured against it.`}
        action={
          <Button
            disabled
            title="Releases arrive with the release and roadmap module; this instance has nowhere to create one yet."
            data-testid={`${TEST_ID}.Release.Create`}
          >
            Create a release
          </Button>
        }
      />

      <div className="border-t border-line px-3.5 py-2.5">
        <span className="text-[11.5px] leading-[1.55] text-ink-4" data-testid={`${TEST_ID}.Release.Foot`}>
          The epics section keeps working regardless — the sections do not depend on each other.{' '}
          <RouterLink to={`/projects/${projectId}/work`} className="text-steel no-underline hover:underline">
            Open the work table
          </RouterLink>
          .
        </span>
      </div>
    </section>
  );
}

/**
 * `project-overview.html`'s **Test health** panel — M1's, and not deferred (D-f).
 *
 * 009's M1 says this page arrives "without its release and test-health panels". That is wrong in the
 * cheap direction: `DashboardPage` already rendered these numbers against endpoints that exist, and
 * D27 says the overview reuses those regions — so the panel costs a move rather than a build.
 * Recorded in 011's close-out.
 *
 * It reads `byLastRun`, the same rows the `Tests passing` tile above sums and the same rows the
 * `By last run` figure below draws, so the three cannot disagree. `NEVER_RUN` is the empty track
 * rather than a fill, for design language §6's measured reason: drawn as a grey it sits 10.7 ΔE from
 * `SKIPPED`, below the normal-vision floor before colour-blindness is considered, and "nothing ran"
 * is not an outcome.
 */
function TestHealthPanel({
  projectId,
  dashboard,
}: {
  projectId: string;
  dashboard: ReturnType<typeof useDashboard>;
}) {
  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.TestHealth`}>
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Test health</h2>
        <span className="text-[11.5px] text-ink-4">latest run per case</span>
      </div>

      <DataRegion
        testId={`${TEST_ID}.TestHealth.Region`}
        state={{ ...dashboard, refetch: () => void dashboard.refetch() }}
      >
        {(data) => <TestHealthBody data={data} projectId={projectId} />}
      </DataRegion>
    </section>
  );
}

function TestHealthBody({ data, projectId }: { data: RepositoryDashboard; projectId: string }) {
  const counts = new Map<string, number>();
  for (const row of data.byLastRun) {
    for (const segment of row.segments) {
      counts.set(segment.key, (counts.get(segment.key) ?? 0) + segment.count);
    }
  }
  const total = [...counts.values()].reduce((sum, count) => sum + count, 0);
  const order = ['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED', 'NEVER_RUN'].filter((key) =>
    counts.has(key),
  );

  if (total === 0) {
    return (
      <p className="m-0 max-w-[46ch] px-3.5 py-4 text-[12.5px] leading-[1.55] text-ink-4" data-testid={`${TEST_ID}.TestHealth.None`}>
        No case with a feature has a recorded result yet. This panel counts each case's latest run,
        so it is empty because nothing has run — not because nothing passed.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3 p-3.5">
      <div aria-hidden className="flex h-[11px] overflow-hidden rounded-full bg-field">
        {order.map((key) => (
          <span
            key={key}
            className="block h-full"
            style={{
              width: `${(counts.get(key)! * 100) / total}%`,
              background: key === 'NEVER_RUN' ? 'transparent' : statusVar[key as keyof typeof statusVar],
            }}
          />
        ))}
      </div>

      <ul className="m-0 flex list-none flex-wrap gap-2.5 p-0">
        {order.map((key) => (
          <li key={key} className="text-[11.5px] text-ink-3" data-testid={`${TEST_ID}.TestHealth.${key}`}>
            <span
              aria-hidden
              className="mr-1.5 inline-block size-2 rounded-full align-[-1px]"
              style={{
                background: key === 'NEVER_RUN' ? 'transparent' : statusVar[key as keyof typeof statusVar],
                border: key === 'NEVER_RUN' ? '1px solid var(--color-line-strong)' : undefined,
              }}
            />
            {`${labelForKey(lastRunLabels, key)} ${formatCount(counts.get(key)!)}`}
          </li>
        ))}
      </ul>

      <p className="m-0 text-[11.5px] text-ink-4">
        {`${plural(total, 'case')} carry a feature and a recorded result. Cases without a feature are not counted here.`}
      </p>

      <div className="flex flex-wrap gap-2">
        <Button asChild size="sm">
          <RouterLink to={`/projects/${projectId}/runs`}>Runs</RouterLink>
        </Button>
        <Button asChild size="sm">
          <RouterLink to={`/projects/${projectId}/plans`}>Plans</RouterLink>
        </Button>
        <Button asChild size="sm">
          <RouterLink to={`/projects/${projectId}/cases`}>Cases</RouterLink>
        </Button>
      </div>
    </div>
  );
}
