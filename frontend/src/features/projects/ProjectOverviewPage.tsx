import { Hexagon } from 'lucide-react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  EPIC_COMPLETION_LIMIT,
  useDashboard,
  useEpicCompletions,
  useIssues,
  useProject,
  useProjectBudgetBurn,
  useReadiness,
  useReleases,
} from '../../api/queries';
import type {
  IssueCompletion,
  IssueSummary,
  ReleaseReadiness,
  ReleaseSummary,
  RepositoryDashboard,
} from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
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
import {
  EM_DASH,
  formatCount,
  formatDate,
  formatDueIn,
  formatHours,
  formatPercent,
  percentOf,
  plural,
  pluralNoun,
} from '../../lib/format';
import { labelForKey, lastRunLabels } from '../../lib/labels';
import { statusVar } from '../../lib/statusColors';
import { BurnMeter } from '../billing/BudgetPanel';
import { doneButFailing, releaseBadge } from '../releases/releaseBadge';
import { nextRelease } from '../releases/nextRelease';
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
 * - ~~**Budget burn** (M6) and **Team** (M7) are **not rendered at all**. A budget is a module that
 *   is not installed, and an empty-state card for one is an advertisement rather than an
 *   explanation.~~ → **superseded 2026-09-05 (019 T6) for the budget half:** the module exists, so
 *   {@link BudgetBurnPanel} reads `GET /projects/{projectId}/budget-burn`. That endpoint is gated
 *   on `project:read` and carries a percentage and hours and **no currency and no amount** (019
 *   D-h) — the mockup's own hint is *"hours, no rates"* and its copy is *"What an hour costs is
 *   Finance's, and is not on this page."* A `MONEY`-basis budget therefore renders as a percentage
 *   alone, and where the project's client has no rate card the burn is **unknown**, not zero. A
 *   project with **no budget renders no panel at all**, which is the state `billing.html`'s
 *   internal project is in and is not a deficiency (019 D-p). Team (M7) is still not rendered
 *   here: staffing has its own screen and this page links to none of it yet.
 *
 * ~~The two KPI tiles the mockup carries and this page does not are the same call: `2.4 complete`
 * and `To target date` are both a release's numbers (M3).~~ → **superseded 2026-09-01 (plan 013,
 * T6).** M3 landed, so both tiles are here — see {@link ReleaseCompleteTile} and
 * {@link TargetDateTile} — and {@link NextReleasePanel} reads real data. What did not change is the
 * panel's empty state: it is still `solo-overview.html`'s, word for word, because a project with no
 * releases is a permanent and ordinary state rather than an unbuilt one, and only the button is now
 * live.
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
              {/*
                The client chip (018 Mc) — and its ABSENCE is an acceptance criterion of this
                module, not a detail. `solo-overview.html` is this same screen for a company with
                no clients: *"There is no Client chip in the header, no client column anywhere, and
                no empty 'attach a client' prompt."* So this is rendered only when the project HAS
                a client, and there is no "None (internal)" chip beside it — a project without one
                is internal work, which is the common case, and nothing here marks it as missing
                anything (scope D1 rule 1).
              */}
              {project.data.client && (
                <span className="text-[13px] text-ink-3" data-testid={`${TEST_ID}.Client`}>
                  Client: {project.data.client.name}
                </span>
              )}
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

      {/*
        The mockup's four tiles, in the mockup's order — `2.4 complete` leads, because the release
        is what the page is about, and `To target date` closes. Both release tiles render an em dash
        with a `title` rather than disappearing when there is no next release: a tile that vanishes
        moves the three beside it, so the row would be a different shape on a project that has not
        typed a version yet.
      */}
      <div
        className="mb-3.5 grid gap-3.5 sm:grid-cols-2 xl:grid-cols-4"
        data-testid={`${TEST_ID}.Tiles`}
      >
        <ReleaseCompleteTile projectId={projectId} />
        <OpenIssuesTile projectId={projectId} />
        <TestsPassingTile projectId={projectId} />
        <TargetDateTile projectId={projectId} />
      </div>

      <div className="grid gap-3.5 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="flex flex-col gap-3.5">
          <EpicsPanel projectId={projectId} epics={epics} />
          <BacklogPanel projectId={projectId} backlog={backlog} epics={epics} />
        </div>

        <div className="flex flex-col gap-3.5">
          <NextReleasePanel projectId={projectId} />
          <TestHealthPanel projectId={projectId} dashboard={dashboard} />
          <BudgetBurnPanel projectId={projectId} />
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
  title,
  testId,
}: {
  value: string;
  label: string;
  detail?: string;
  /** Why this tile has no number, on the tiles that render an em dash rather than disappearing. */
  title?: string;
  testId: string;
}) {
  return (
    <div
      className="rounded-[10px] border border-line bg-panel px-3.5 py-3"
      title={title}
      data-testid={testId}
    >
      <span className="block font-mono text-[24px] leading-[1.1] font-semibold text-ink">{value}</span>
      <span className="mt-1 block text-[12px] text-ink-2">{label}</span>
      {detail && <span className="mt-0.5 block text-[11.5px] text-ink-4">{detail}</span>}
    </div>
  );
}

/**
 * The next release and its readiness, for the three places on this page that need the same pair.
 *
 * One hook rather than three copies of `useReleases` + `nextRelease` + `useReadiness`, so the two
 * tiles and the panel cannot name different versions — which is the two-contradictory-numbers
 * failure this page has already had to answer for once, three tiles along.
 *
 * `useReadiness` is gated on the release actually existing (`''` disables it), so a project with no
 * releases makes one request and not two.
 */
function useNextRelease(projectId: string) {
  const releases = useReleases(projectId);
  const release = nextRelease(releases.data ?? []);
  const readiness = useReadiness(release?.id ?? '');
  return { releases, release, readiness };
}

/**
 * `project-overview.html`'s first tile — **`2.4 complete`**, and its basis is named beneath it.
 *
 * **The mockup's basis is not the one on the wire, and the caption is the whole of the honesty
 * here.** It reads *"by estimate · 214 of 306 h"*; readiness carries `ReleaseStories.done` /
 * `.total` and no estimate roll-up at all — `scopeAddedAfterCreation` is the only other number on
 * that payload and it counts issues, not hours. So the tile is by **story count** and says so, which
 * is `IssueCompletion`'s own mitigation applied one screen over: a percentage never changes meaning
 * silently, and the caption is what keeps it from doing so. An estimate-based figure needs an
 * estimate roll-up on the readiness contract; it is not silently dropped.
 *
 * `stories.total === 0` is a real answer rather than a missing one — a release with no stories
 * pointed at it yet — and `percentOf` returns null over a zero denominator rather than `0%`, which
 * would be a claim about progress on work nobody has scoped.
 */
function ReleaseCompleteTile({ projectId }: { projectId: string }) {
  const { release, readiness } = useNextRelease(projectId);
  const stories = readiness.data?.stories;
  const percent = stories ? percentOf(stories.done, stories.total) : null;

  if (!release || !stories || percent === null) {
    return (
      <Tile
        value={EM_DASH}
        label="Release complete"
        detail={
          release
            ? `${release.version} has no stories pointed at it yet`
            : 'no release planned on this project'
        }
        title={
          release
            ? `${release.version} counts no stories, so there is no completion to state — not a completion of zero.`
            : 'A release is a version you ship from this project. Create one and this tile measures it.'
        }
        testId={`${TEST_ID}.ReleaseComplete.None`}
      />
    );
  }

  return (
    <Tile
      value={formatPercent(percent, 0)}
      label={`${release.version} complete`}
      detail={`by story count · ${formatCount(stories.done)} of ${formatCount(stories.total)}`}
      testId={`${TEST_ID}.ReleaseComplete.Tile`}
    />
  );
}

/**
 * `project-overview.html`'s fourth tile — **To target date**.
 *
 * `formatDueIn` and **not `formatAge`**, which reads a negative interval as *"just now"* and so
 * cannot answer a future deadline at all (013 T5 paid for this on the release header). It counts
 * whole UTC days, so "tomorrow" does not become "in 0 days" at eleven at night.
 *
 * A release with no target date gets the em dash and a sentence, not a zero: no target is an
 * ordinary state the release form offers on purpose, and a countdown to nothing is a number that
 * cannot be wrong in a way anybody would notice.
 */
function TargetDateTile({ projectId }: { projectId: string }) {
  const { release } = useNextRelease(projectId);

  if (!release || !release.targetDate) {
    return (
      <Tile
        value={EM_DASH}
        label="To target date"
        detail={release ? `${release.version} has no target date` : 'no release planned'}
        title={
          release
            ? 'A release does not need a target date. Set one on the release and this tile counts down to it.'
            : 'A release is a version you ship from this project. Create one with a target date and this tile counts down to it.'
        }
        testId={`${TEST_ID}.TargetDate.None`}
      />
    );
  }

  return (
    <Tile
      value={formatDueIn(release.targetDate)}
      label="To target date"
      detail={`${release.version} · ${formatDate(release.targetDate)}`}
      testId={`${TEST_ID}.TargetDate.Tile`}
    />
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
 * The next release — `project-overview.html`'s panel, and `solo-overview.html`'s empty state.
 *
 * **A missing section explains itself; it does not render empty.** `solo-overview.html` is the
 * template and it is not a different screen: it is this screen for a company with no clients, and
 * its release panel is the empty state this one uses whenever there are no releases — a glyph, "No
 * releases yet", a sentence saying what a release IS and what it buys, and the action that creates
 * one.
 *
 * ~~In M1 that state is the **only** state, because releases arrive in M3.~~ → **superseded
 * 2026-09-01 (plan 013, T6).** M3 landed, so the panel now reads `useReleases` and `useReadiness`
 * and draws the mockup's version, badge, target date, two meters, red note and *Open release
 * readiness*. What was written against this endpoint from the start is **kept**, not replaced: the
 * component boundary was here so M3 could turn it on by fetching, and that is what happened.
 *
 * **The empty state's copy is unchanged, word for word.** A project with no releases is not an
 * unbuilt feature any more, it is an ordinary and permanent state — the state every freshly
 * provisioned project is in, because nothing is backfilled into `release` when M3 ships (D14) — so
 * the sentence saying what a release is remains the right sentence. Only the button changed: it is
 * now **enabled and linked**, gated on `release:manage`, and disabled with its reason for a caller
 * who lacks the key rather than for an instance that lacks the module.
 *
 * The footer is the sentence that keeps the page honest, kept verbatim: *"the epics section above
 * keeps working regardless — the sections do not depend on each other."*
 */
function NextReleasePanel({ projectId }: { projectId: string }) {
  const { releases, release, readiness } = useNextRelease(projectId);

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Release`}>
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Next release</h2>
        <span className="flex-1" />
        {/* The mockup's `All releases` button. Ungated, like the rail entry it duplicates. */}
        <Button asChild size="sm" data-testid={`${TEST_ID}.Release.All`}>
          <RouterLink to={`/projects/${projectId}/releases`}>All releases</RouterLink>
        </Button>
      </div>

      {/*
        The region wraps the list read, not the readiness one. Readiness is a **second, dependent**
        request whose absence leaves the version, the date and the link renderable — so a failed
        readiness read degrades the panel's two meters in place rather than blanking a panel whose
        heading a reader is looking at (`data-fetching`'s critical-vs-optional split: the list is
        critical here and the readiness is not).
      */}
      <DataRegion
        testId={`${TEST_ID}.Release.Region`}
        state={{ ...releases, refetch: () => void releases.refetch() }}
        isEmpty={() => release === null}
        empty={{
          /*
            The locator M1 shipped, kept deliberately: `DataRegion` would name this
            `…Release.Region.Empty`, and `EmptyState`'s own `testId` wins over the derived one
            because `empty` is spread last. A renamed locator on an unchanged empty state is a
            broken page object for no gain.
          */
          testId: `${TEST_ID}.Release.Empty`,
          kind: 'by-design',
          icon: Hexagon,
          title: 'No releases yet',
          body: `A release is a version you ship from this project — 0.9, 1.0 — with an optional target date. Epics and stories can point at one, and readiness is measured against it.`,
          action: (
            <PermissionGate projectId={projectId} permission="release:manage">
              {({ allowed, reason }) =>
                allowed ? (
                  <Button asChild data-testid={`${TEST_ID}.Release.Create`}>
                    <RouterLink to={`/projects/${projectId}/releases/new`}>
                      New release
                    </RouterLink>
                  </Button>
                ) : (
                  // Disabled rather than hidden (§31), and the reason is now the caller's key
                  // rather than the module's absence.
                  <Button disabled title={reason} data-testid={`${TEST_ID}.Release.Create`}>
                    New release
                  </Button>
                )
              }
            </PermissionGate>
          ),
        }}
      >
        {() =>
          release && (
            <NextReleaseBody
              projectId={projectId}
              release={release}
              readiness={readiness.data}
              failedReadiness={readiness.isError}
            />
          )
        }
      </DataRegion>

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
 * The panel with a release in it — the mockup's version, badge, date, two meters and red note.
 *
 * <h2>`readiness` is optional and the panel says which half is missing</h2>
 *
 * It is a second request, so it can be in flight or failed while the release itself is on screen.
 * The version, the badge's fallback and the target date come off the `ReleaseSummary` the list
 * already carried; the meters and the note need readiness and are replaced by one line when it is
 * not there. Nothing here draws a meter from an absent number.
 *
 * <h2>Both meters are gated the way `release.html`'s are (D-d)</h2>
 *
 * `readiness.tests.hasRun` false means `passed`, `failed` and the rest are **null and not zero**, so
 * the cases meter is replaced by the words `no run yet` rather than drawn as an empty bar. A bar at
 * zero says "nothing passed"; the absence of a run says "nobody has looked". Those are not the same
 * claim and this module exists because they were being read as one.
 *
 * <h2>The red note is `doneButFailing`, not a second implementation of it</h2>
 *
 * The function is in `features/releases/releaseBadge.ts` — one file, four callers, and the sentence
 * it supports is the one this whole module exists to be able to write. The overview's copy is the
 * short form of `release.html`'s banner: one story, one case, and a link to the readiness screen for
 * the rest.
 */
function NextReleaseBody({
  projectId,
  release,
  readiness,
  failedReadiness,
}: {
  projectId: string;
  release: ReleaseSummary;
  readiness: ReleaseReadiness | undefined;
  failedReadiness: boolean;
}) {
  /*
    The row-sized readiness is **inline on every `ReleaseSummary`**, so the badge and the story
    meter have an answer before the second read lands and never flicker through a wrong one. The
    detail payload is what the *cases* meter and the note need, because only it carries the blockers.
  */
  const summary = release.readiness;
  const badge = releaseBadge(release.status, summary.verdict);
  const failing = readiness ? doneButFailing(readiness.blockers) : null;

  return (
    <div className="flex flex-col gap-3 p-3.5" data-testid={`${TEST_ID}.Release.Body`}>
      <div className="flex flex-wrap items-center gap-2">
        <RouterLink
          to={`/projects/${projectId}/releases/${release.id}`}
          data-testid={`${TEST_ID}.Release.Version`}
          className="text-[17px] font-semibold text-ink no-underline hover:text-steel hover:underline"
        >
          {release.version}
        </RouterLink>
        <Badge tone={badge.tone} data-testid={`${TEST_ID}.Release.Badge`}>
          {badge.label}
        </Badge>
        <span className="flex-1" />
        {/*
          Not omitted when there is no target date: a release without one is a state the release
          form offers deliberately, and an absent date reads as one that failed to load.
        */}
        <span className="text-[12px] text-ink-4" data-testid={`${TEST_ID}.Release.Target`}>
          {release.targetDate
            ? `${formatDate(release.targetDate)} · ${formatDueIn(release.targetDate)}`
            : 'no target date'}
        </span>
      </div>

      <ReleaseMeter
        label="Stories done"
        value={`${formatCount(summary.storiesDone)} / ${formatCount(summary.storiesTotal)}`}
        segments={[{ key: 'done', count: summary.storiesDone, color: 'var(--color-passed)' }]}
        total={summary.storiesTotal}
        testId={`${TEST_ID}.Release.Stories`}
      />

      {summary.hasRun ? (
        <ReleaseMeter
          label="Linked cases passing"
          value={`${formatCount(summary.casesPassed ?? 0)} / ${formatCount(summary.linkedCases)}`}
          segments={[
            { key: 'passed', count: summary.casesPassed ?? 0, color: 'var(--color-passed)' },
            { key: 'failed', count: summary.casesFailed ?? 0, color: 'var(--color-failed)' },
          ]}
          total={summary.linkedCases}
          testId={`${TEST_ID}.Release.Cases`}
        />
      ) : (
        <div data-testid={`${TEST_ID}.Release.NoRun`}>
          <div className="mb-1 flex justify-between text-[11.5px] text-ink-4">
            <span>Linked cases passing</span>
            <Identifier variant="muted">no run yet</Identifier>
          </div>
          <span className="block text-[11.5px] leading-[1.5] text-ink-4">
            {`${plural(summary.linkedCases, 'case')} linked to this version, and nothing has been executed against it. That is not a pass rate of zero.`}
          </span>
        </div>
      )}

      {failing && (
        <p
          className="m-0 text-[11.5px] leading-[1.55] text-[color:var(--color-failed)]"
          data-testid={`${TEST_ID}.Release.Failing`}
        >
          {`Not ready: `}
          <RouterLink
            to={`/issues/${encodeURIComponent(failing.key)}`}
            className="text-inherit hover:underline"
          >
            <strong>{failing.key}</strong>
          </RouterLink>
          {' is done but its '}
          {failing.failedCaseKeys.length === 1 ? 'case ' : 'cases '}
          <span className="identifier">{failing.failedCaseKeys.join(', ')}</span>
          {' failed in the latest run.'}
        </p>
      )}

      {/*
        The one thing this panel says about its own second read. Not a region of its own: the meters
        above are already correct without it, and a failed-state block here would replace a panel
        that is still telling the reader which version is next and when it is due.
      */}
      {failedReadiness && (
        <p className="m-0 text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Release.ReadinessFailed`}>
          The blockers behind this verdict could not be loaded. The counts above came with the
          release itself and are current.
        </p>
      )}

      <Button asChild data-testid={`${TEST_ID}.Release.Open`}>
        <RouterLink to={`/projects/${projectId}/releases/${release.id}`}>Open release readiness</RouterLink>
      </Button>
    </div>
  );
}

/**
 * One labelled meter — the mockup's `Stories done` and `Linked cases passing` rows.
 *
 * A zero total renders the empty track rather than dividing by it, which is the state a release with
 * nothing pointed at it is genuinely in.
 */
function ReleaseMeter({
  label,
  value,
  segments,
  total,
  testId,
}: {
  label: string;
  value: string;
  segments: { key: string; count: number; color: string }[];
  total: number;
  testId: string;
}) {
  return (
    <div data-testid={testId}>
      <div className="mb-1 flex justify-between text-[11.5px] text-ink-4">
        <span>{label}</span>
        <Identifier variant="muted">{value}</Identifier>
      </div>
      <div className="flex h-1.5 overflow-hidden rounded-full bg-line" aria-hidden>
        {total > 0
          && segments.map((segment) => (
            <span
              key={segment.key}
              style={{ width: `${(segment.count / total) * 100}%`, background: segment.color }}
            />
          ))}
      </div>
    </div>
  );
}

/**
 * `project-overview.html`'s **Budget burn** panel — *"hours, no rates"*, and never a currency.
 *
 * <h2>Nothing on this panel can carry money, because nothing on the endpoint does</h2>
 *
 * `GET /projects/{projectId}/budget-burn` is gated on `project:read` and `BudgetBurn` has **no
 * `amount` and no `currency` member at all** (019 D-h). That is what makes it safe for the one
 * reader who may call no other endpoint in the billing module: there is nothing here to withhold,
 * so gating a project's own ceiling behind a money key would only hide it from the people
 * delivering against it. The mockup's own copy is the statement — *"A Project Manager sees the
 * ceiling and the burn. What an hour costs is Finance's, and is not on this page."*
 *
 * A `MONEY`-basis budget therefore reaches this panel as a **percentage alone**: `budgetHours` is
 * null, the hours line shows what has been approved and no ceiling beside it, and the meter carries
 * the burn. `money.ts` is not imported here, and reaching for it would mean reaching for the wrong
 * hook.
 *
 * <h2>No budget is no panel, and that is not a deficiency</h2>
 *
 * `basis: null` is a project nobody has set a ceiling on — the common case, and the state
 * `billing.html`'s internal project is in. It draws nothing: no empty card, no warning tone, no
 * *set a budget* prompt (019 D-p). A **failed** read is different and does render, because a panel
 * that vanished on an outage would be indistinguishable from a project that has no budget, and
 * this screen would then be quietly reporting a fact it does not have.
 *
 * <h2>`BurnMeter` is the Billing screen's, imported rather than redrawn</h2>
 *
 * An overrun must not look one way here and another to Finance. 102% is a nearly-full bar with a
 * red tail on both screens, `null` reads as *unknown* rather than as zero on both, and neither
 * clamps at 100 — clamping hides the one row that matters.
 */
function BudgetBurnPanel({ projectId }: { projectId: string }) {
  const burn = useProjectBudgetBurn(projectId);

  // Nothing to draw and nothing to report: no ceiling set, or the answer has not arrived and a
  // card that appeared and then vanished would move the two panels above it.
  if (burn.isPending || burn.data?.basis === null) return null;

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Budget`}>
      <div className="flex items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Budget burn</h2>
        <span className="text-[11.5px] text-ink-4">hours, no rates</span>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Budget.Region`}
        state={{ ...burn, refetch: () => void burn.refetch() }}
      >
        {(data) => (
          <div className="flex flex-col gap-2.5 px-3.5 py-3">
            <div className="flex items-center justify-between gap-3">
              <span className="text-[12px] text-ink-3">Approved hours</span>
              <span
                className="font-mono text-[13px] tabular-nums text-ink"
                data-testid={`${TEST_ID}.Budget.Hours`}
              >
                {/*
                  `418h / 600h` for an hours ceiling; `418h` alone for a money one, where the
                  ceiling is a figure this page may not carry. Not `418h / —`: an em dash in a
                  ratio reads as a number that failed to load rather than as one that is Finance's.
                */}
                {data.budgetHours === null ?
                  formatHours(data.approvedHours)
                : `${formatHours(data.approvedHours)} / ${formatHours(data.budgetHours)}`}
              </span>
            </div>

            <BurnMeter percent={data.burnPercent} hasBudget testId={`${TEST_ID}.Budget.Meter`} />

            <p className="m-0 text-[11.5px] leading-[1.5] text-ink-4">
              A Project Manager sees the ceiling and the burn. What an hour costs is Finance&rsquo;s,
              and is not on this page.
            </p>
          </div>
        )}
      </DataRegion>
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
