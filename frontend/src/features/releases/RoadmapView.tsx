import { CalendarRange } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import { useRoadmap } from '../../api/queries';
import type { Roadmap, RoadmapEpic, RoadmapRelease } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PermissionGate } from '../../components/PermissionGate';
import { Button } from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatDate } from '../../lib/format';
import {
  monthsIn,
  spanOf,
  spanOfEpic,
  todayAt,
  windowOf,
  type DateWindow,
  type Span,
} from './timelineScale';

/**
 * `work-timeline.html` — releases in version order, their epics inside them, and what cannot be
 * laid out at all.
 *
 * <h2>The empty roadmap is this screen's acceptance criterion, not its edge case</h2>
 *
 * D14 migrates nothing into `release`, so on the day M3 merges **this screen is empty for every
 * user in every instance**. An empty timeline that explains nothing reads as a broken screen and a
 * reader has no way to tell the difference — so the empty state says why, and offers the create.
 * `tcmsFaults.release('empty')` is the switch that makes the state reachable after somebody has
 * created one, and it landed before this file.
 *
 * <h2>Every bar is a real `<a>`</h2>
 *
 * The mockup's closing note is *"clicking any bar opens that epic"*, and a positioned `<div>` with
 * an `onClick` is not reachable by tab. So every bar is a `RouterLink`, which also gives it a focus
 * ring and a native middle-click. {@link timelineScale}'s `MIN_WIDTH` exists for the same reason: a
 * two-day span inside a nine-month window is a 6px link nobody can aim at.
 *
 * <h2>No filter strip, and that is not an omission</h2>
 *
 * `work-timeline.html` draws the shared chips with *"the filter came with you from the board"*.
 * `GET /projects/{id}/roadmap` declares **no filter parameters** — it is releases and their epics,
 * whole — so a strip here would be chrome that narrows nothing, which is worse than none: a reader
 * who removed a chip and saw no change would learn the filter is broken. The Calendar beside it does
 * carry the strip, because `GET /issues` does take the filter.
 *
 * <h2>The scale is derived, and it is not a component</h2>
 *
 * `timelineScale.ts` holds every span, the window and the month columns, with its own tests. It is
 * the one piece of arithmetic on this screen a reader could get wrong twice (D-o), and a release's
 * bar has no start date of its own — it is `min(epic.startDate) … targetDate`, with a point marker
 * when no epic under it has a start, and nothing at all when the release has no target.
 */

const TEST_ID = 'Roadmap';

export function RoadmapView({
  projectId,
  /** Pinned by the tests. `today` is the reader's reference point and must be inside the window. */
  today = new Date().toISOString().slice(0, 10),
}: {
  projectId: string;
  today?: string;
}) {
  const roadmap = useRoadmap(projectId);

  return (
    <section className="overflow-hidden rounded-[8px] border border-line bg-panel">
      <DataRegion
        testId={`${TEST_ID}.Content`}
        state={{ ...roadmap, refetch: () => void roadmap.refetch() }}
        isEmpty={(data) => data.releases.length === 0 && data.unscheduled.length === 0}
        empty={{
          kind: 'by-design',
          icon: CalendarRange,
          title: 'No releases to lay out yet',
          body:
            'The roadmap draws releases in version order with their epics inside them. Nothing was '
            + 'migrated into releases when this module shipped — deliberately, because version '
            + 'strings across a real instance are not as clean as they look — so this is empty '
            + 'until somebody creates the first one, and it is not a fault in this screen.',
          action: (
            <PermissionGate projectId={projectId} permission="release:manage">
              {({ allowed }) => (
                <Button
                  variant="primary"
                  size="sm"
                  disabled={!allowed}
                  asChild={allowed}
                  data-testid={`${TEST_ID}.Empty.New`}
                >
                  {allowed ? (
                    <RouterLink to={`/projects/${projectId}/releases/new`}>
                      New release
                    </RouterLink>
                  ) : (
                    <span>New release</span>
                  )}
                </Button>
              )}
            </PermissionGate>
          ),
        }}
      >
        {(data) => <Timeline projectId={projectId} roadmap={data} today={today} />}
      </DataRegion>
    </section>
  );
}

function Timeline({
  projectId,
  roadmap,
  today,
}: {
  projectId: string;
  roadmap: Roadmap;
  today: string;
}) {
  const window = windowOf(roadmap, today);
  const months = monthsIn(window);
  const marker = todayAt(window, today);

  return (
    <>
      <div className="overflow-x-auto p-4" data-testid={`${TEST_ID}.Timeline`}>
        <div className="min-w-[720px]">
          <Row label={<span aria-hidden />}>
            <div
              className="grid text-[11px] text-ink-4"
              style={{ gridTemplateColumns: `repeat(${months.length}, minmax(0, 1fr))` }}
            >
              {months.map((month) => (
                <span key={month.key} className="border-l border-line pl-1.5">
                  {month.label}
                </span>
              ))}
            </div>
          </Row>

          {roadmap.releases.map((release) => (
            <ReleaseRows
              key={release.id}
              projectId={projectId}
              release={release}
              window={window}
              marker={marker}
            />
          ))}

          {roadmap.unscheduled.length > 0 && (
            <UnscheduledLane epics={roadmap.unscheduled} window={window} marker={marker} />
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-line bg-field/60 px-[22px] py-2.5 text-[12px] text-ink-4">
        <span>2.10 sorts after 2.5, not between 2.1 and 2.2.</span>
        <span className="ml-auto flex items-center gap-1.5">
          <span aria-hidden style={{ color: 'var(--color-failed)' }}>
            &#9612;
          </span>
          <span data-testid={`${TEST_ID}.Today`}>{`today · ${formatDate(today)}`}</span>
        </span>
      </div>
    </>
  );
}

/** A release's own row, then one indented row per epic under it. */
function ReleaseRows({
  projectId,
  release,
  window,
  marker,
}: {
  projectId: string;
  release: RoadmapRelease;
  window: DateWindow;
  marker: number;
}) {
  const span = spanOf(release, window);

  return (
    <>
      <Row
        label={
          <RouterLink
            to={`/projects/${projectId}/releases/${release.id}`}
            data-testid={`${TEST_ID}.Release.${release.version}`}
            className="flex items-center gap-2 truncate font-semibold text-inherit no-underline hover:text-steel hover:underline"
          >
            <Glyph tone="release">R</Glyph>
            {release.version}
          </RouterLink>
        }
      >
        <Track marker={marker}>
          {/*
            No bar when the release has no target date — `2.10`'s row in the mockup. The row itself
            still exists, in version order, with its epics: a release without a date has not
            disappeared from the plan, it just has nothing to draw.
          */}
          {span && (
            <Bar
              span={span}
              tone="release"
              to={`/projects/${projectId}/releases/${release.id}`}
              testId={`${TEST_ID}.Bar.Release.${release.version}`}
              label={
                release.targetDate
                  ? `${release.version} · target ${formatDate(release.targetDate)}`
                  : release.version
              }
            />
          )}
        </Track>
      </Row>

      {release.epics.map((epic) => (
        <EpicRow key={epic.key} epic={epic} window={window} marker={marker} indented />
      ))}
    </>
  );
}

function EpicRow({
  epic,
  window,
  marker,
  indented,
}: {
  epic: RoadmapEpic;
  window: DateWindow;
  marker: number;
  indented?: boolean;
}) {
  const span = spanOfEpic(epic, window);

  return (
    <Row
      label={
        <RouterLink
          to={`/issues/${encodeURIComponent(epic.key)}`}
          data-testid={`${TEST_ID}.Epic.${epic.key}`}
          className={cn(
            'flex items-center gap-2 truncate text-inherit no-underline hover:text-steel hover:underline',
            indented && 'pl-3.5',
          )}
        >
          <Glyph tone="epic">E</Glyph>
          {epic.title}
        </RouterLink>
      }
    >
      <Track marker={marker}>
        {span ? (
          <Bar
            span={span}
            tone="epic"
            to={`/issues/${encodeURIComponent(epic.key)}`}
            testId={`${TEST_ID}.Bar.Epic.${epic.key}`}
            label={`${epic.key} · ${epic.completionPercent ?? 0}%`}
          />
        ) : (
          // An epic with neither date, under a release. It is in `unscheduled` too (D-o), so the
          // bar is absent here rather than invented — the lane below is where it can be acted on.
          <span
            data-testid={`${TEST_ID}.Bar.Epic.${epic.key}.NoDates`}
            className="pl-1.5 text-[11px] text-ink-4"
          >
            no dates
          </span>
        )}
      </Track>
    </Row>
  );
}

/**
 * The unscheduled lane — every epic that cannot be drawn under a release (D-o), and it holds two
 * shapes for two different reasons.
 *
 * An epic with **dates and no release** gets a real bar: it has a span, it just has nowhere else to
 * be drawn. An epic with **no dates at all** has nothing to position, so it is a link in a list
 * under the track rather than a bar at an invented offset — which is the one thing the mockup does
 * that the data cannot support, since it draws "Loyalty tie-in — no dates" at a left and a width.
 *
 * Both are here rather than dropped, which is the note this whole lane comes from: *"an epic with no
 * dates does not disappear from the roadmap — it drops into the unscheduled lane, where it can be
 * seen and given dates."*
 */
function UnscheduledLane({
  epics,
  window,
  marker,
}: {
  epics: readonly RoadmapEpic[];
  window: DateWindow;
  marker: number;
}) {
  const dated = epics.filter((epic) => spanOfEpic(epic, window) !== null);
  const undated = epics.filter((epic) => spanOfEpic(epic, window) === null);

  return (
    <div className="mt-2.5" data-testid={`${TEST_ID}.Unscheduled`}>
      <Row label={<span className="text-ink-4">Unscheduled</span>}>
        <Track marker={marker} recessed>
          {dated.map((epic) => {
            const span = spanOfEpic(epic, window) as Span;
            return (
              <Bar
                key={epic.key}
                span={span}
                tone="unscheduled"
                to={`/issues/${encodeURIComponent(epic.key)}`}
                testId={`${TEST_ID}.Bar.Unscheduled.${epic.key}`}
                label={`${epic.title} — no release`}
              />
            );
          })}
        </Track>
      </Row>

      {undated.length > 0 && (
        <div className="mt-1.5 grid grid-cols-[minmax(0,180px)_minmax(0,1fr)] gap-3">
          <span className="text-[11px] text-ink-4">No dates</span>
          <span className="flex flex-wrap gap-1.5">
            {undated.map((epic) => (
              <RouterLink
                key={epic.key}
                to={`/issues/${encodeURIComponent(epic.key)}`}
                data-testid={`${TEST_ID}.Undated.${epic.key}`}
                className="rounded-[5px] border border-dashed border-line px-[9px] py-[3px] text-[12px] text-ink-2 no-underline hover:border-line-strong hover:text-ink"
              >
                {`${epic.title} — no dates`}
              </RouterLink>
            ))}
          </span>
        </div>
      )}
    </div>
  );
}

function Row({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(0,180px)_minmax(0,1fr)] items-center gap-3 py-[3px] text-[12.5px] text-ink-2">
      <div className="min-w-0 truncate">{label}</div>
      <div className="min-w-0">{children}</div>
    </div>
  );
}

function Track({
  marker,
  recessed,
  children,
}: {
  marker: number;
  recessed?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        'relative h-[22px] rounded-[4px]',
        recessed ? 'bg-wash-1' : 'bg-transparent',
      )}
    >
      <span
        aria-hidden
        className="absolute inset-y-0 w-px"
        style={{ left: `${marker}%`, backgroundColor: 'var(--color-failed)' }}
      />
      {children}
    </div>
  );
}

const BAR_TONES = {
  release: 'var(--color-steel)',
  epic: 'var(--color-viz-1)',
  unscheduled: 'var(--color-skipped)',
} as const;

/**
 * One bar, and it is an `<a>` for the reason in this module's header.
 *
 * `width: 0` from {@link spanOf} is a **point marker** rather than a bar — a release whose epics have
 * no start dates. It gets a fixed 10px diamond-width link at the target so it is both visible and
 * clickable, and its accessible name is the same label the bar would have carried.
 */
function Bar({
  span,
  tone,
  to,
  label,
  testId,
}: {
  span: Span;
  tone: keyof typeof BAR_TONES;
  to: string;
  label: string;
  testId: string;
}) {
  const point = span.width === 0;

  return (
    <RouterLink
      to={to}
      data-testid={testId}
      title={label}
      aria-label={label}
      className={cn(
        'absolute top-[3px] flex h-4 items-center overflow-hidden rounded-[3px] px-1.5',
        'text-[11px] whitespace-nowrap text-ink no-underline',
        'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-given',
      )}
      style={{
        left: `${span.left}%`,
        ...(point ? { width: '10px', minWidth: '10px' } : { width: `${span.width}%` }),
        backgroundColor: `color-mix(in oklab, ${BAR_TONES[tone]} 30%, transparent)`,
        borderLeft: `2px solid ${BAR_TONES[tone]}`,
      }}
    >
      {!point && label}
    </RouterLink>
  );
}

function Glyph({ tone, children }: { tone: 'release' | 'epic'; children: React.ReactNode }) {
  return (
    <span
      aria-hidden
      className="inline-flex size-[18px] flex-none items-center justify-center rounded-[4px] font-mono text-[10px] font-semibold text-ink"
      style={{
        backgroundColor: `color-mix(in oklab, ${BAR_TONES[tone]} 30%, transparent)`,
      }}
    >
      {children}
    </span>
  );
}
