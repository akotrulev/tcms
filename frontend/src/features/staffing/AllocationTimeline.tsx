import type { AllocationSpan, PersonCapacity } from '../../api/types';
import { Badge } from '../../components/kit';
import { formatDate } from '../../lib/format';
import { monthsIn, type DateWindow } from '../releases/timelineScale';
import { allocationLabel, capacityLabel, NO_PROFILE, overAllocationLabel } from './capacityCopy';

/**
 * `staffing.html`'s Allocation panel — one row per person, a track per allocation across the window.
 *
 * <h2>A list of people, not a grid of divs</h2>
 *
 * The rows are a `<ul>` of `<li>` and each bar carries an `aria-label` naming its project and
 * percentage, because the *whole* content of this panel is positional: a reader who cannot see the
 * bars would otherwise get a person's name and nothing else. The month scale above is
 * `aria-hidden` — it labels pixels, and the dates a screen-reader user needs are already in each
 * bar's own label.
 *
 * <h2>The window is the served one, and the bars are clipped to it</h2>
 *
 * `from`/`to` come out of the payload rather than out of the picker, so the scale and the bars are
 * drawn against the range the server actually answered for. An allocation that starts before the
 * window or ends after it is clipped rather than dropped — a bar running off both edges is the
 * correct picture of "this person is on that project for longer than you are looking".
 *
 * <h2>Free stretches are drawn; absence is drawn differently, and neither is arithmetic done here</h2>
 *
 * The `unsched` styling under the bars is the track itself showing through, so "60% free" is not a
 * second bar this file computes — computing it would need the daily union of overlapping spans,
 * which is exactly the sum {@link overAllocationLabel} refuses to re-derive. The row label carries
 * the served `freePercent`, and absence is drawn as its own ghosted band from the served
 * `absences`, because a day somebody is away has **zero** capacity behind whatever is booked on it
 * — which is the panel foot's sentence and the reason the two cannot be one band.
 */
export interface AllocationTimelineProps {
  window: DateWindow;
  people: readonly PersonCapacity[];
  testId: string;
}

export function AllocationTimeline({ window, people, testId }: AllocationTimelineProps) {
  const months = monthsIn(window);

  return (
    <div data-testid={testId}>
      <div
        aria-hidden
        className="grid gap-2 border-b border-line px-3 py-2 text-[11px] font-medium text-ink-4"
        style={{ gridTemplateColumns: `190px repeat(${months.length}, minmax(0, 1fr))` }}
      >
        <div />
        {months.map((month) => (
          <div key={month.key}>{month.label}</div>
        ))}
      </div>

      <ul className="m-0 flex list-none flex-col p-0">
        {people.map((person) => (
          <PersonRow key={person.person.id} person={person} window={window} testId={testId} />
        ))}
      </ul>
    </div>
  );
}

function PersonRow({
  person,
  window,
  testId,
}: {
  person: PersonCapacity;
  window: DateWindow;
  testId: string;
}) {
  const flagged = overAllocationLabel(person);
  const rowTestId = `${testId}.Row.${person.person.id}`;

  return (
    <li
      className="grid items-center gap-2 border-b border-line px-3 py-2 last:border-b-0"
      style={{ gridTemplateColumns: '190px minmax(0, 1fr)' }}
      data-testid={rowTestId}
    >
      <div className="flex min-w-0 items-center gap-2">
        <Initials name={person.person.displayName} />
        <span className="min-w-0 truncate text-[12.5px] text-ink">
          {person.person.displayName}
        </span>
        {/*
          The badge and the plain figure are the same number seen two ways, and only one of them is
          a warning: `overAllocated` is served off the peak DAY (017 D-e), so a person at 100% for
          the first half of the window and 100% for the second is not flagged, where a monthly sum
          would have called them 200%.

          `failed` rather than the mockup's `b-blocked`, for `OverAllocatedPanel`'s reason: nothing
          is blocked here, and the kit's tone of that name says otherwise.
        */}
        {flagged ?
          <Badge tone="failed" data-testid={`${rowTestId}.Over`}>
            {flagged}
          </Badge>
        : <span className="font-mono text-[10.5px] text-ink-4" data-testid={`${rowTestId}.Percent`}>
            {allocationLabel(person.allocatedPercent)}
          </span>
        }
      </div>

      <div
        className="relative h-[22px] overflow-hidden rounded-md bg-wash-1"
        data-testid={`${rowTestId}.Track`}
      >
        {person.absences.map((absence) => {
          const span = spanBetween(absence.startsOn, absence.endsOn, window);
          if (!span) return null;
          return (
            <span
              key={absence.id}
              className="absolute top-[4px] h-[14px] rounded-sm bg-line-strong"
              style={{ left: `${span.left}%`, width: `${span.width}%` }}
              aria-label={`Absent ${formatDate(absence.startsOn)} to ${formatDate(absence.endsOn)}`}
              data-testid={`${rowTestId}.Absence.${absence.id}`}
            />
          );
        })}

        {person.allocations.map((allocation) => (
          <AllocationBar
            key={`${allocation.projectId}-${allocation.startsOn}`}
            allocation={allocation}
            window={window}
            testId={`${rowTestId}.Bar.${allocation.projectKey}`}
          />
        ))}
      </div>

      {/*
        The row's second line, and it is where the no-profile state surfaces on this screen: a
        person with no profile still has allocations — a percentage is a share of a capacity nobody
        has recorded — so the bars above are real and the hours are not.
      */}
      <div />
      <p className="m-0 mt-1 text-[11px] text-ink-4" data-testid={`${rowTestId}.Capacity`}>
        {person.profile ?
          `${capacityLabel(person.capacityHours, true)} capacity · ${person.freePercent}% free`
        : NO_PROFILE}
      </p>
    </li>
  );
}

function AllocationBar({
  allocation,
  window,
  testId,
}: {
  allocation: AllocationSpan;
  window: DateWindow;
  testId: string;
}) {
  const span = spanBetween(allocation.startsOn, allocation.endsOn, window);
  if (!span) return null;

  return (
    <span
      className="absolute top-[4px] flex h-[14px] items-center overflow-hidden rounded-sm px-1.5 text-[10px] font-medium whitespace-nowrap text-rail"
      style={{
        left: `${span.left}%`,
        width: `${span.width}%`,
        backgroundColor: 'var(--color-viz-2)',
      }}
      aria-label={`${allocation.projectName} ${allocation.percent}%, ${formatDate(allocation.startsOn)} to ${formatDate(allocation.endsOn)}`}
      data-testid={testId}
    >
      {`${allocation.projectName} · ${allocation.percent}%`}
    </span>
  );
}

/**
 * The person glyph, from the name rather than from an avatar the contract does not carry.
 *
 * `UserRef` has an optional `avatarUrl` and nothing in this product populates it, so initials are
 * the honest fallback rather than a broken image. `aria-hidden` because the name is rendered in
 * full beside it — a screen reader announcing "M S Mira Sund" is two readings of one fact.
 */
function Initials({ name }: { name: string }) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');

  return (
    <span
      aria-hidden
      className="flex size-5 shrink-0 items-center justify-center rounded-full bg-wash-2 text-[9.5px] font-semibold text-ink-3"
    >
      {initials}
    </span>
  );
}

/**
 * Where a date range sits inside the window, as `{ left, width }` percentages.
 *
 * Private to this file rather than in `timelineScale.ts` beside `spanOf`, because the shapes are
 * genuinely different: a release has a target and *may* have no start, so that module's three
 * shapes include a point marker and a null. An allocation and an absence both carry two dates that
 * are always present, and the only question here is how much of the window they cover.
 *
 * The window's `to` is **inclusive**, so the denominator runs to the day after it. Without that a
 * span covering the whole window comes out at slightly under 100% and the last day of the range is
 * a sliver nothing can ever fill.
 *
 * Returns `null` for a range wholly outside the window — the server does not send one, and drawing
 * a clamped zero-width bar for it would put a project's name on a row it does not belong to.
 */
function spanBetween(
  startsOn: string,
  endsOn: string,
  window: DateWindow,
): { left: number; width: number } | null {
  const start = Date.parse(`${window.from}T00:00:00Z`);
  const end = Date.parse(`${window.to}T00:00:00Z`) + DAY_MS;
  if (end <= start) return null;

  const from = Date.parse(`${startsOn}T00:00:00Z`);
  const to = Date.parse(`${endsOn}T00:00:00Z`) + DAY_MS;
  if (Number.isNaN(from) || Number.isNaN(to) || to <= start || from >= end) return null;

  const left = clamp(((Math.max(from, start) - start) / (end - start)) * 100);
  const right = clamp(((Math.min(to, end) - start) / (end - start)) * 100);
  return { left, width: Math.max(right - left, MIN_WIDTH) };
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * A bar narrower than this is a line nobody can read a label off. `timelineScale.ts` sets the same
 * floor for the same reason, and the two agreeing is worth more than either being tuned.
 */
const MIN_WIDTH = 1.5;

function clamp(percent: number): number {
  if (Number.isNaN(percent)) return 0;
  return Math.min(Math.max(percent, 0), 100);
}
