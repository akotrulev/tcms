import { Link as RouterLink, useParams } from 'react-router-dom';
import type {
  DashboardBreakdown,
  DashboardTile,
  LifecycleStatus,
  RepositoryDashboard,
  StatusKind,
} from '../../api/types';
import { useDashboard, useProject, useSetProjectArchived } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Badge } from '../../components/kit';
import { FigurePanel } from '../../components/kit/FigurePanel';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Button } from '../../components/ui';
import { formatCount, formatDateTime, formatPercent, plural, pluralNoun } from '../../lib/format';
import {
  automationLabels,
  healthLabels,
  labelForKey,
  lastRunLabels,
  lifecycleLabels,
} from '../../lib/labels';
import { lifecycleVar, severityVar, statusVar } from '../../lib/statusColors';

/**
 * Repository status dashboard (plan §6o).
 *
 * The design rule that makes this useful rather than decorative: **every tile is a link that
 * emits a filter predicate**, and predicates compose. A tile is not a number to look at, it
 * is the entrance to the working list of cases behind that number — where the user can
 * filter further and act in bulk. A dashboard whose tiles are dead ends produces a report
 * nobody can do anything with.
 *
 * Because the case list keeps its filters in the URL, "78 cases whose spec changed" is one
 * `<Link>` away from being a shareable, bulk-actionable destination.
 *
 * ~~Every region is a `Panel` with a title bar.~~ → **superseded (007 T15).** The regions are
 * the render's `.fig` — a figure with its own heading and a subtitle stating what the numbers
 * are of. Design language §6 is what changed the drawings underneath them, and it is computed
 * rather than judged: the activity chart is three one-series rows instead of one three-series
 * chart, because the accents that would have carried it measure 2.5 ΔE apart under
 * deuteranopia; "never run" is the empty track rather than a fill; there is no pie, donut or
 * gauge here and every bar starts at zero.
 */
export function DashboardPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const dashboard = useDashboard(projectId);

  return (
    <div>
      <PageHeader
        testId="Dashboard.Header"
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
                Stated on the project's own landing page as well as on the picker card, because
                this is where somebody arrives from a bookmark — and a retired project that looks
                identical to a live one is how work gets filed into it for another six months.

                A badge, not a chip: archived is state the system owns and there is nothing behind
                clicking it (design language §5).
              */}
              {project.data.archived && (
                <Badge tone="plain" data-testid="Dashboard.Archived">
                  Archived
                </Badge>
              )}
            </>
          )
        }
        subtitle={project.data?.description}
        actions={
          <>
            {project.data && (
              <ArchiveButton projectId={projectId} archived={project.data.archived} />
            )}
            <Button asChild variant="primary" data-testid="Dashboard.BrowseCasesButton">
              <RouterLink to={`/projects/${projectId}/cases`}>Browse test cases</RouterLink>
            </Button>
          </>
        }
      />

      <DataRegion
        testId="Dashboard.Content"
        state={{ ...dashboard, refetch: () => void dashboard.refetch() }}
      >
        {(data) => <DashboardBody data={data} projectId={projectId} />}
      </DataRegion>
    </div>
  );
}

/**
 * Retire this project, or bring it back (§159).
 *
 * Here rather than on a settings screen because this page is the project's front door and the two
 * settings screens are each about one subsystem — §6g's review policy, the classification
 * vocabulary — while this is about the project as a whole. It is the same shape the docs tree uses
 * for a page's publication state: **one button whose label is the move available from the state the
 * project is in**, never two of which one is always a no-op the server would answer 200 to.
 *
 * **No confirmation dialog, deliberately.** The action destroys nothing, hides nothing and is
 * undone by the button that replaces it. A dialog here would teach people to click through dialogs
 * on a screen that also has real ones.
 *
 * Disabled rather than hidden for a caller without `project:settings` — `PermissionGate`'s whole
 * content, and the reason a Test Engineer learns the capability exists and who to ask.
 */
function ArchiveButton({ projectId, archived }: { projectId: string; archived: boolean }) {
  const setArchived = useSetProjectArchived(projectId);

  return (
    <PermissionGate projectId={projectId} permission="project:settings">
      {({ allowed }) => (
        <Button
          variant="ghost"
          disabled={!allowed || setArchived.isPending}
          // The target state, not a toggle of what this render happens to hold: a stale copy then
          // asks for the state it can already see rather than for the opposite of it.
          onClick={() => setArchived.mutate(!archived)}
          data-testid="Dashboard.ArchiveButton"
        >
          {archived ? 'Restore project' : 'Archive project'}
        </Button>
      )}
    </PermissionGate>
  );
}

function DashboardBody({ data, projectId }: { data: RepositoryDashboard; projectId: string }) {
  const casesPath = `/projects/${projectId}/cases`;

  return (
    <div className="flex flex-col gap-3.5">
      <p className="text-[11.5px] text-ink-4" data-testid="Dashboard.GeneratedAt">
        Counted at {formatDateTime(data.generatedAt)}
      </p>

      <div className="grid gap-3.5 lg:grid-cols-3">
        <FigurePanel title="Authoring status" subtitle="every count opens the cases behind it" testId="Dashboard.Lifecycle">
          <TileRow
            tiles={data.lifecycle}
            labels={lifecycleLabels}
            casesPath={casesPath}
            testId="Dashboard.Lifecycle"
          />
        </FigurePanel>

        <FigurePanel
          title="Automation coverage"
          subtitle="of cases that can be automated"
          testId="Dashboard.Automation"
        >
          <AutomationCoverage coverage={data.automationCoverage} casesPath={casesPath} />
        </FigurePanel>

        <FigurePanel
          title="Activity"
          subtitle="last 30 days · two series, two rows"
          testId="Dashboard.Activity"
        >
          <ActivityChart activity={data.recentActivity} />
        </FigurePanel>
      </div>

      {/*
        Health is the section people act on, so it comes before the breakdowns and its tiles
        are ordered by severity rather than alphabetically — the two error-severity signals
        are the ones that mean something is actually wrong right now.
      */}
      <FigurePanel
        title="Needs attention"
        subtitle="computed from the health catalogue, not curated"
        testId="Dashboard.Health"
      >
        <HealthPanel data={data} casesPath={casesPath} />
      </FigurePanel>

      <div className="grid gap-3.5 lg:grid-cols-2">
        <FigurePanel
          title="By feature"
          subtitle="cases, split by where they are in authoring"
          testId="Dashboard.ByFeature"
        >
          <BreakdownList
            rows={data.byFeature}
            casesPath={casesPath}
            testId="Dashboard.ByFeature"
            emptyMessage="No case has been given a feature yet. Features are a per-project vocabulary you define, and cases are classified against it."
          />
        </FigurePanel>
        <FigurePanel
          title="By owner"
          subtitle="cases, split by where they are in authoring"
          testId="Dashboard.ByOwner"
        >
          <BreakdownList
            rows={data.byOwner}
            casesPath={casesPath}
            testId="Dashboard.ByOwner"
            emptyMessage="No case has an owner yet."
          />
        </FigurePanel>
      </div>

      {/*
        The third breakdown (005 N3/S8) — one bar per Feature split by **the outcome of each case's
        last execution**, which is a different question from the two above and the reason it is a
        figure of its own rather than a toggle on "By feature": those split by authoring status
        (is this case written and approved), this splits by result (does it pass). A control that
        swapped between them would make one figure answer two questions and hide whichever the
        reader was not looking at.

        The last result here is the one `TestCaseSummary.lastResult` shows, read from the same
        `case_health` row, so the bar and the grid it drills into cannot disagree.
      */}
      <FigurePanel title="By last run" subtitle="cases, split by how their last run went" testId="Dashboard.ByLastRun">
        <BreakdownList
          rows={data.byLastRun}
          casesPath={casesPath}
          testId="Dashboard.ByLastRun"
          segmentLabels={lastRunLabels}
          /*
            §6 of the design language, and the one place on this screen where the spec overruled
            what shipped. `NEVER_RUN` used to take the muted grey — a fill for the absence of a
            result, sitting next to `SKIPPED`, which is a result somebody chose. The validator
            measured those two 10.7 ΔE apart, below the normal-vision floor before CVD is even
            considered, and the fix is not a different grey: **never run is the empty track**.
            `transparent` leaves the row's own `bg-field` showing through at exactly the width the
            count deserves, which carries the magnitude and cannot be read as an outcome. The
            legend below the bars says the same thing in words.
          */
          segmentColor={(key) =>
            key === 'NEVER_RUN' ? 'transparent' : (statusVar[key as StatusKind] ?? 'transparent')
          }
          legend={LAST_RUN_LEGEND}
          emptyMessage="No case has a feature and a recorded result yet."
        />
      </FigurePanel>
    </div>
  );
}

/**
 * The four entries the render's legend carries, and the reason it exists at all: with `NEVER_RUN`
 * drawn as the empty track, a reader looking at a short bar cannot tell "nothing ran" from "the
 * bar ends here" without being told once, in words.
 */
const LAST_RUN_LEGEND: { label: string; color: string | null }[] = [
  { label: labelForKey(lastRunLabels, 'PASSED'), color: statusVar.PASSED },
  { label: labelForKey(lastRunLabels, 'FAILED'), color: statusVar.FAILED },
  { label: labelForKey(lastRunLabels, 'BLOCKED'), color: statusVar.BLOCKED },
  { label: labelForKey(lastRunLabels, 'NEVER_RUN'), color: null },
];

/**
 * The three states of "Needs attention", which are genuinely three and not two.
 *
 * An empty tile list is a **claim** that nothing is wrong. That claim is only true if somebody
 * looked, and `healthComputedAt` is the server saying whether anybody has. Rendering the empty
 * panel and the never-computed panel identically is the same defect as §46's `met: null` reading
 * as a failed gate — a reassuring sentence where the honest one is "we don't know yet".
 *
 * ~~`healthComputedAt` is `null` while `case_health` has no writer, which is every deployment
 * today.~~ → **superseded 2026-08-09 (§156).** It has three writers, the recompute is
 * `matchIfMissing = true` on an hourly schedule, so the timestamp is non-null within two minutes
 * of a deployment starting — and **five of the ten signals still have no computation behind them**
 * (`ArchitectureRulesTest.everyMappedColumnHasAWriter`). So the middle branch, which used to be
 * unreachable, is the one every deployment now lands in, and it was saying "Nothing needs
 * attention" about five checks that had never run.
 *
 * The copy is narrowed rather than the branch changed: this panel cannot know *which* signals the
 * server derives — that is a server fact, and a list of them here would be the second
 * implementation of it. What it can honestly say is what it actually knows, which is that no
 * signal was raised. Naming the gap needs the server to say which signals it computes, and that
 * is a contract change.
 *
 * Exported for its own test: two of these three branches are unreachable through a fixture that
 * always supplies both a timestamp and tiles, and §46's finding is that no test written before a
 * state is reachable can fail on it.
 */
export function HealthPanel({ data, casesPath }: { data: RepositoryDashboard; casesPath: string }) {
  if (data.healthComputedAt === null) {
    return (
      <p className="max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3" data-testid="Dashboard.Health.NotComputed">
        Health signals have not been computed for this project yet, so this panel is empty
        because nothing has been checked — not because nothing is wrong.
      </p>
    );
  }

  if (data.health.length === 0) {
    return (
      <p className="max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3" data-testid="Dashboard.Health.Clear">
        No health signal was raised. Last checked {formatDateTime(data.healthComputedAt)}.
      </p>
    );
  }

  return (
    /* THE FIRST TILE IS THE WORST ONE, and that is the server's doing rather than this
       component's — `DashboardService.health` sorts by severity and then by count descending
       (§184). Do not sort here. This is §179's rule at the case grid, applied to the second of
       the two screens that show a health severity: the ranking is a property of the answer, and a
       client-side sort is the second copy of it.

       There WAS one, and it was `severityRank` — four severities in a ternary chain, right here,
       thirty lines under the colour table §183 had just deleted for being a second copy of the
       same enum. It was a no-op, and it was one by coincidence: `Array.prototype.sort` is stable
       and its key was the server comparator's FIRST key, so the server's count-descending
       tiebreak survived a sort that had never heard of it. It was also not exhaustive — a fifth
       `Severity` member would have ranked bottom silently, where `severityVar` beside it is a
       `satisfies Record<…>` and would have failed the build. */
    <TileRow
      tiles={data.health}
      labels={healthLabels}
      casesPath={casesPath}
      testId="Dashboard.Health"
    />
  );
}

/**
 * Tiles carry their filter predicate as query parameters. Serializing it here — rather than
 * hard-coding a path per tile — is what makes the set extensible: a new health signal on the
 * server produces a working, drillable tile with no frontend change.
 *
 * **That claim is about the *link*, and §180 is where it stopped being made about the words too.**
 * The tile used to render a server-sent `label`, defended by this same sentence — but a new signal
 * is a new member of a contract enum, so it reaches `lib/labels.ts` as a compile error long before
 * it reaches a tile, and the grid chip and filter bar need the label anyway. The words were
 * duplicated, not extensible: six of the ten had drifted apart from ours. What survives untouched
 * is this function, which never has to know what a key means.
 */
function tileHref(casesPath: string, filter: Record<string, unknown> | undefined): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter ?? {})) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) value.forEach((v) => params.append(key, String(v)));
    else params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${casesPath}?${qs}` : casesPath;
}

/**
 * @param labels the catalogue this row's keys belong to — `lifecycleLabels` for the authoring
 *               panel, `healthLabels` for "Needs attention". The row is told which vocabulary it is
 *               rendering because `key` is a bare string on the wire and the two panels draw from
 *               different enums; a key with no entry falls back to itself (see `labelForKey`).
 *
 * The severity colour stays an inline style rather than a class. It is a value from
 * `severityVar`, resolved per tile at render time — a Tailwind class cannot carry a variable the
 * component computes, and `HealthPanel.test.tsx` reads it back with `getComputedStyle`, which in
 * jsdom sees inline styles and nothing else.
 */
function TileRow({
  tiles,
  labels,
  casesPath,
  testId,
}: {
  tiles: DashboardTile[];
  labels: Record<string, string>;
  casesPath: string;
  testId: string;
}) {
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,11rem),1fr))] gap-2">
      {tiles.map((tile) => (
        <RouterLink
          key={tile.key}
          to={tileHref(casesPath, tile.filter)}
          data-testid={`${testId}.Tile.${tile.key}`}
          className="block no-underline"
        >
          <div
            // §183: the shared table, not a private one. This line read
            // `severityColor[tile.severity ?? 'NEUTRAL']` against a four-key map declared thirty
            // lines up — same keys as the case chip's, one differing value, and INFO was the one
            // that differed. The `??` went with it: `DashboardTile.severity` is required on the
            // wire, so the fallback answered a question the contract does not ask.
            style={{ borderLeftColor: severityVar[tile.severity] }}
            className="h-full rounded-md border border-l-[3px] border-white/8 bg-panel px-3.5 py-3 transition-colors hover:border-white/14"
          >
            <span className="block font-mono text-[22px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
              {formatCount(tile.count)}
            </span>
            <span className="mt-1 block text-[12px] text-ink-3">{labelForKey(labels, tile.key)}</span>
          </div>
        </RouterLink>
      ))}
    </div>
  );
}

/**
 * Stacked bars, drawn in CSS. Each *segment* is its own link, so a user can go straight to
 * "the 78 drafts in Checkout" rather than to Checkout and then filtering by hand.
 *
 * The render's `.hb` row — label, track, count — with a 2px gap between segments and the track
 * showing through wherever a segment has no fill (design language §6: abutting fills of similar
 * lightness read as one segment at 8px tall).
 */
function BreakdownList({
  rows,
  casesPath,
  testId,
  emptyMessage,
  legend,
  segmentLabels = lifecycleLabels,
  segmentColor = (key) => lifecycleVar[key as LifecycleStatus] ?? 'transparent',
}: {
  rows: DashboardBreakdown[];
  casesPath: string;
  testId: string;
  emptyMessage: string;
  /** Drawn under the bars where the reader cannot otherwise tell a fill from the empty track. */
  legend?: { label: string; color: string | null }[];
  /**
   * How a segment key becomes a word — defaulted to the authoring lifecycle, which is what the two
   * original breakdowns split by.
   *
   * Parameterized for 005 S8's third breakdown, whose segments are execution outcomes and the
   * literal `NEVER_RUN`. **A second component would have been the wrong answer**: the bar, the
   * proportional widths, the drill-through and the accessible names are identical, and what differs
   * is one vocabulary — which is a parameter, not a screen.
   */
  segmentLabels?: Record<string, string>;
  /** The colour half of the same swap. See `lib/statusColors.ts` for why these are token names. */
  segmentColor?: (key: string) => string;
}) {
  /*
    A breakdown with no rows renders an empty bordered box — a figure with a heading and nothing
    under it, which reads as a failed load rather than as "nobody has classified anything yet".
    Live data reaches this immediately: a project's Feature vocabulary starts empty by design
    (inventing a client's taxonomy is how a controlled vocabulary gets worked around), so this is
    the FIRST state of the panel on every real project, not an edge case.
  */
  if (rows.length === 0) {
    return (
      <p className="max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3" data-testid={`${testId}.Empty`}>
        {emptyMessage}
      </p>
    );
  }

  const max = Math.max(
    1,
    ...rows.map((row) => row.segments.reduce((sum, segment) => sum + segment.count, 0)),
  );

  return (
    <div className="flex flex-col">
      {rows.map((row) => {
        const total = row.segments.reduce((sum, segment) => sum + segment.count, 0);
        return (
          <div
            key={row.id}
            className="grid grid-cols-[minmax(0,100px)_minmax(46px,1fr)_50px] items-center gap-[11px] py-[5px] text-[12.5px]"
          >
            <span className="truncate text-ink-2">{row.label}</span>
            <span className="flex h-2 gap-0.5 overflow-hidden rounded bg-field" style={{ width: `${(total / max) * 100}%` }}>
              {row.segments.map((segment) => {
                // §180: a segment's key is a LifecycleStatus, and both its words and its colour
                // come from this side now. `row.label` above is the opposite case and stays as it
                // arrives — a feature path or a person's name is data only the server can resolve.
                const segmentLabel = labelForKey(segmentLabels, segment.key);
                return (
                  <RouterLink
                    key={segment.key}
                    to={tileHref(casesPath, segment.filter)}
                    data-testid={`${testId}.Segment.${segment.key}`}
                    aria-label={`${row.label}, ${segmentLabel}: ${plural(segment.count, 'case')}`}
                    title={`${segmentLabel}: ${formatCount(segment.count)} — open these cases`}
                    style={{ flex: segment.count, backgroundColor: segmentColor(segment.key) }}
                    className="block h-full min-w-[2px] hover:brightness-125"
                  />
                );
              })}
            </span>
            <Identifier variant="muted">{formatCount(total)}</Identifier>
          </div>
        );
      })}
      {legend && (
        <div className="mt-2.5 flex flex-wrap gap-3.5 text-[11.5px] text-ink-3">
          {legend.map((entry) => (
            <span key={entry.label} className="flex items-center gap-1.5">
              <i
                aria-hidden
                style={entry.color ? { backgroundColor: entry.color } : undefined}
                className={
                  entry.color
                    ? 'block size-2.5 rounded-[2px]'
                    : 'block size-2.5 rounded-[2px] border border-white/14 bg-field'
                }
              />
              {entry.label}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

function AutomationCoverage({
  coverage,
  casesPath,
}: {
  coverage: RepositoryDashboard['automationCoverage'];
  casesPath: string;
}) {
  const rows = [
    { key: 'AUTOMATED' as const, count: coverage.automated },
    { key: 'TO_BE_AUTOMATED' as const, count: coverage.toBeAutomated },
    { key: 'MANUAL' as const, count: coverage.manual },
    { key: 'NOT_AUTOMATABLE' as const, count: coverage.notAutomatable },
  ];
  const total = rows.reduce((sum, row) => sum + row.count, 0);

  return (
    <div>
      <p className="mb-3 font-mono text-[30px] leading-none font-medium tracking-[-.03em] tabular-nums text-ink">
        {formatPercent(coverage.coveragePercent)}
      </p>
      {/*
        The denominator is stated, not implied. "57% automated" is meaningless unless the
        reader knows whether the 64 not-automatable cases are in the divisor — and honest
        coverage excludes them, because you cannot automate them.
      */}
      <p className="mb-3.5 max-w-[42ch] text-[11.5px] leading-[1.5] text-ink-4">
        Cases marked not automatable are excluded from the denominator.
      </p>

      <div className="flex flex-col">
        {rows.map((row) => (
          <div
            key={row.key}
            className="grid grid-cols-[minmax(0,110px)_minmax(46px,1fr)_44px] items-center gap-[11px] py-[5px] text-[12.5px]"
          >
            <RouterLink
              to={`${casesPath}?automationStatus=${row.key}`}
              data-testid={`Dashboard.Automation.${row.key}`}
              className="truncate text-ink-2 hover:text-ink"
            >
              {automationLabels[row.key]}
            </RouterLink>
            <span className="flex h-2 overflow-hidden rounded bg-field">
              <i
                style={{
                  width: `${(row.count / Math.max(total, 1)) * 100}%`,
                  backgroundColor: row.key === 'AUTOMATED' ? 'var(--color-passed)' : 'var(--color-steel)',
                }}
                className="block h-full"
              />
            </span>
            <Identifier variant="muted">{formatCount(row.count)}</Identifier>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * A 30-day activity chart, in CSS grid. No chart library: two series over thirty points
 * does not justify the bundle, and a hand-drawn bar column can carry a real accessible
 * label per day, which most chart wrappers make awkward.
 *
 * **There used to be an "Approved" series and it was removed with the contract's field.**
 * Nothing in the schema records *when* a case was approved — `lifecycleStatus` is current state,
 * and the audit trail deliberately stores the version number rather than a field diff — so the
 * server can only ever have sent zeros. A flat zero line labelled "Approved" states that nobody
 * approved anything this month, which is a claim about the client's work rather than an admission
 * that it is not measured. It comes back when the review workflow (§6g) gives approval a
 * timestamp.
 *
 * ~~Two series in one column of paired bars, with a legend mapping each to a colour.~~ →
 * **superseded (007 T15, design language §6).** One row per series, one series per row, no legend
 * and no shared colour: the product has no categorical palette because the accents that would
 * have carried one are 2.5 ΔE apart under deuteranopia, and a reader who cannot separate the two
 * fills is reading a chart that says nothing. The rows share an x-axis and each carries its own
 * total, which is the comparison the paired bars were being asked for.
 */
function ActivityChart({ activity }: { activity: RepositoryDashboard['recentActivity'] }) {
  const series = [
    { key: 'created' as const, label: 'Created' },
    { key: 'modified' as const, label: 'Modified' },
  ];

  return (
    <div data-testid="Dashboard.Activity.Chart">
      {series.map(({ key, label }) => {
        // Each row scales to its own peak. A shared scale would flatten "Created" to nothing
        // beside a busier "Modified", which is the failure small multiples exist to avoid — the
        // question each row answers is "when in the month", not "which series is bigger".
        const max = Math.max(1, ...activity.map((day) => day[key]));
        const total = activity.reduce((sum, day) => sum + day[key], 0);

        return (
          <div
            key={key}
            className="grid grid-cols-[minmax(0,72px)_minmax(46px,1fr)_46px] items-center gap-3 border-b border-white/8 py-[7px] last:border-b-0"
          >
            <span className="truncate text-[12.5px] text-ink-2">{label}</span>
            <span className="flex h-[34px] items-end gap-px" aria-hidden>
              {activity.map((day) => (
                <i
                  key={day.date}
                  title={`${day.date} — ${plural(day[key], 'case')} ${key}`}
                  style={{ height: `${(day[key] / max) * 100}%` }}
                  className="block min-h-px flex-1 rounded-t-[2px] bg-steel opacity-75"
                />
              ))}
            </span>
            <span className="text-right font-mono text-[12px] tabular-nums text-ink">
              {formatCount(total)}
            </span>
          </div>
        );
      })}
      <p className="mt-2 flex justify-between font-mono text-[10px] text-ink-4">
        <span>{activity[0]?.date}</span>
        <span>{activity[activity.length - 1]?.date}</span>
      </p>
    </div>
  );
}
