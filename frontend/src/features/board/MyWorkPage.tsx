import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { useCurrentTimesheetPeriod, useMyCapacity, useMyWork } from '../../api/queries';
import type { BoardCard, TimesheetPeriod } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button, Skeleton } from '../../components/ui';
import { NO_PROFILE, capacityLabel } from '../staffing/capacityCopy';
import { cn } from '../../lib/cn';
import { formatDate, formatHours, plural } from '../../lib/format';
/*
  The tone comes from what a status MEANS, never from its name: a status a project invented has no
  tone of its own (D2). Aliased because `CardStatus` below reads it as a table rather than as a
  label map.
*/
import { issueStatusKindTones as STATUS_TONE } from '../../lib/labels';
import { formatPeriodRange, todayIso } from '../time/periods';
import { CardNoteDialog } from './CardNoteDialog';
import { linkFor } from './cardLink';

const TEST_ID = 'MyWork';

/** A card that can carry a private note — the two fields the annotation is keyed on are present. */
type Annotatable = BoardCard & { sourceType: string; sourceId: string };

function annotatable(card: BoardCard): card is Annotatable {
  return card.sourceType !== null && card.sourceId !== null;
}

/**
 * §6l's cross-project **"My Work"** screen (§83) — everything assigned to this person, in every
 * project they can see.
 *
 * ## It is two tables, not a kanban (011 T7)
 *
 * ~~The same `Board` component as the project board, with `mine=true` pre-applied — *"the same
 * board, one filter pre-applied, so it is the same component with a different empty state"*.~~ →
 * **superseded.** `my-work.html` renders **two tables** — *Assigned issues* and *Test executions &
 * reviews* — beside a private *My notes* panel, and the mockups are the source of truth for what a
 * screen shows. The reason the mockup is right is legible once the two are put side by side: a
 * kanban's columns are one project's workflow, and this screen spans projects whose statuses are
 * different rows that merely happen to share English words. Columns here were a grouping the data
 * cannot support; a `Status` column is the same fact without the claim.
 *
 * The reuse argument the old version made survives in the place it still holds: `linkFor` is
 * imported from `cardLink.ts` rather than reimplemented, so one card opens one screen from whichever
 * of the three boards you found it on.
 *
 * ## One request, split on the client
 *
 * Both tables read **one** `GET /board?mine=true`. That is not an optimisation — two requests would
 * let the halves disagree about who the caller is and about when "now" was, so a reader could see an
 * issue in one table that the other half's snapshot says is no longer theirs. The composite board is
 * one operation (D11); the split is `kind === 'ISSUE'` over one response.
 *
 * ## What this screen deliberately does not have
 *
 * **No "Everyone" chip.** `GET /board` with no project and `mine=false` is a **400** — there is no
 * board that is every project's work unfiltered, and the one response whose size is the company's is
 * not a screen anybody could work from. A chip that produced it would be `ui-rules` §4's
 * render-then-reject.
 *
 * **No "Hide done" toggle**, unlike the mockup's filter strip. `BoardFacts.assignedIssues` does not
 * return an issue in a `DONE`-kind status at all — §6l's "card leaves" rule — so a done card cannot
 * arrive here and a control to hide one would have nothing to act on.
 *
 * **No estimate column**, which the mockup has. `BoardCard` carries no `estimateHours`: the board's
 * card is not `IssueSummary`, and adding the field is a contract change no module has asked for.
 * Recorded rather than faked — an "Est" column reading `—` on every row is worse than no column.
 *
 * **No "New issue".** A create needs a project and this screen has none; asking for one in a dialog
 * would be a project picker on the one screen that exists to not have a project frame.
 *
 * **Never 404s, and an empty list is not an error.** A member holding no project grants gets an
 * empty response — reachable and empty — which is the distinction the project board has to make
 * (`[]` vs 404) and this one, naming no project, cannot fail to make.
 *
 * **Absent, with the module that lands each (D-f):** the `AI` tags and the "My assistant" panel
 * (M5). ~~the "This week" hours KPI (M6).~~ → **landed 2026-09-04 (plan 016, T5)** as
 * {@link ThisPeriodPanel}, with two differences from `my-work.html` that are both decisions:
 *
 *   - **it is a period, not a week.** 016 D-a makes the timesheet fortnight semi-monthly and
 *     nothing configures it, so a panel headed "This week" would report a figure over a range the
 *     product does not have. It names its own dates instead. **This bullet is unchanged and still
 *     correct** — the capacity below is asked for over the same period, never over a week.
 *   - ~~**the `Capacity 32h` tile and the meter under it are absent** (016 D-j), not zero. Capacity
 *     is computed from `person_profile`, `allocation` and `absence` — all three are M7's tables and
 *     M7 has not started — and a figure this module invented is the two-contradictory-percentages
 *     failure CLAUDE.md records. A meter needs a denominator, so the meter goes with it.~~ →
 *     **superseded 2026-09-04 (plan 017).** M7 landed those three tables (`V10`) and
 *     `staffing.Capacity` derives the figure, so the denominator exists and the meter arrives with
 *     it. The reasoning is kept because it was right about the state it refused to invent: a person
 *     with no `person_profile` row still reads `—` and *no profile recorded*, and the meter is
 *     absent for them rather than drawn against a made-up 40. The capacity read is `staffing`'s
 *     (017 D-m) and has its **own** `DataRegion` inside this panel, so a staffing outage takes the
 *     capacity figure and the meter and leaves the logged hours alone.
 *
 * **It is not behind `trackTime`**, and that is not an oversight: 016 D-g's switch is a *project's*
 * decision and a period spans projects (D-i), so no one project's switch can speak for a figure
 * that adds up somebody's whole fortnight. This screen names no project either — that is the point
 * of it — so there is nothing here to read the switch from.
 */
export function MyWorkPage() {
  const items = useMyWork();
  const [noting, setNoting] = useState<Annotatable | null>(null);

  /*
    ~~Raised by `PageHeader`.~~ → superseded by 007 T13, which replaced that header with the
    render's top strip. Kept rather than dropped: a route change that announces nothing leaves a
    screen-reader user on a page they have no way to know has changed.
  */
  const announce = useAnnounce();
  useEffect(() => {
    announce('My work page loaded');
  }, [announce]);

  return (
    <div>
      <title>My work · TCMS</title>

      {/*
        No breadcrumbs, unlike every project screen: this route has no ancestor. A single crumb
        reading "My work" above a title reading "My work" is chrome that says nothing twice.
      */}
      <header
        data-testid={`${TEST_ID}.Header`}
        className="mb-3 flex flex-wrap items-start gap-3 border-b border-line pb-3"
      >
        <div className="min-w-0 flex-1">
          <h1 className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink">My work</h1>
          <p className="m-0 mt-1 max-w-[74ch] text-[13px] text-ink-3">
            Everything assigned to you, across every project you can see — executions to record,
            reviews you have been asked for, and your own issues.
          </p>
        </div>
      </header>

      <DataRegion
        testId={`${TEST_ID}.Content`}
        state={{ ...items, refetch: () => void items.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          /*
            The empty state has to be readable as GOOD NEWS, because on this screen it usually is —
            unlike the project board's, where nothing on the board is a project nobody has started.
            It must also not read as a failure: a person newly added to a company sees this before
            they see anything else, and "nothing found" would tell them the product is broken rather
            than that their queue is clear.
          */
          title: 'Nothing assigned to you',
          body: 'No executions, reviews or issues are waiting on you in any project right now.',
        }}
      >
        {(rows) => (
          <MyWorkBody rows={rows} onNote={(card) => setNoting(card)} />
        )}
      </DataRegion>

      {noting && <CardNoteDialog card={noting} onClose={() => setNoting(null)} />}
    </div>
  );
}

function MyWorkBody({
  rows,
  onNote,
}: {
  rows: readonly BoardCard[];
  onNote: (card: Annotatable) => void;
}) {
  const issues = useMemo(() => rows.filter((card) => card.kind === 'ISSUE'), [rows]);
  const queue = useMemo(() => rows.filter((card) => card.kind !== 'ISSUE'), [rows]);
  const noted = useMemo(
    () =>
      rows.filter(
        (card) =>
          card.annotation != null
          && (card.annotation.note != null || card.annotation.personalDueDate != null),
      ),
    [rows],
  );
  const overdue = rows.filter((card) => card.overdue).length;

  return (
    <div className="grid gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="flex flex-col gap-3.5">
        <Panel
          title="Assigned issues"
          count={issues.length}
          testId={`${TEST_ID}.Issues`}
          /*
            Story B5's first criterion, and the sentence the mockup puts under the table. It is the
            claim this screen exists to make: the request names no project, so nothing here asked
            which one you meant.
          */
          foot="Two projects, one list — nothing here asks which project you meant."
        >
          {issues.length === 0 ? (
            <Nothing testId={`${TEST_ID}.Issues.None`}>
              No issue in any project is assigned to you. Executions and reviews are below.
            </Nothing>
          ) : (
            <Table data-testid={`${TEST_ID}.Issues.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Key</TableHeaderCell>
                  <TableHeaderCell>Issue</TableHeaderCell>
                  <TableHeaderCell>Project</TableHeaderCell>
                  <TableHeaderCell>Status</TableHeaderCell>
                  <TableHeaderCell>Due</TableHeaderCell>
                  <TableHeaderCell align="right">Note</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {issues.map((card) => (
                  <TableRow key={card.id} data-testid={`${TEST_ID}.Issues.Row`}>
                    <TableCell>
                      {/* An ISSUE card's `id` IS its key — `ACME-124` — which is what the route takes. */}
                      <Identifier variant="muted">{card.id}</Identifier>
                    </TableCell>
                    <TableCell variant="title">
                      <RouterLink
                        to={linkFor(card)}
                        data-testid={`${TEST_ID}.Issues.Row.Title`}
                        className="text-inherit no-underline hover:text-steel hover:underline"
                      >
                        {card.title}
                      </RouterLink>
                    </TableCell>
                    <TableCell data-testid={`${TEST_ID}.Issues.Row.Project`}>
                      <Identifier variant="muted">{card.projectKey}</Identifier>
                    </TableCell>
                    <TableCell>
                      <CardStatus card={card} testId={`${TEST_ID}.Issues.Row.Status`} />
                    </TableCell>
                    <Due card={card} testId={`${TEST_ID}.Issues.Row.Due`} />
                    <NoteCell card={card} onNote={onNote} />
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Panel>

        <Panel
          title="Test executions & reviews"
          count={queue.length}
          testId={`${TEST_ID}.Queue`}
        >
          {queue.length === 0 ? (
            <Nothing testId={`${TEST_ID}.Queue.None`}>
              Nothing is waiting on you in the repository — no execution to record, no review asked
              of you, no case flagged for maintenance.
            </Nothing>
          ) : (
            <Table data-testid={`${TEST_ID}.Queue.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>What</TableHeaderCell>
                  <TableHeaderCell>Where</TableHeaderCell>
                  <TableHeaderCell>Kind</TableHeaderCell>
                  <TableHeaderCell>Due</TableHeaderCell>
                  <TableHeaderCell align="right">Note</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {queue.map((card) => (
                  <TableRow key={card.id} data-testid={`${TEST_ID}.Queue.Row`}>
                    <TableCell variant="title" subtitle={card.linkedKey ?? undefined}>
                      <RouterLink
                        to={linkFor(card)}
                        data-testid={`${TEST_ID}.Queue.Row.Title`}
                        className="text-inherit no-underline hover:text-steel hover:underline"
                      >
                        {card.title}
                      </RouterLink>
                    </TableCell>
                    <TableCell data-testid={`${TEST_ID}.Queue.Row.Project`}>
                      <Identifier variant="muted">{card.projectKey}</Identifier>
                    </TableCell>
                    <TableCell>
                      <Badge tone="plain" data-testid={`${TEST_ID}.Queue.Row.Kind`}>
                        {kindLabels[card.kind]}
                      </Badge>
                    </TableCell>
                    <Due card={card} testId={`${TEST_ID}.Queue.Row.Due`} />
                    <NoteCell card={card} onNote={onNote} />
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Panel>
      </div>

      <div className="flex flex-col gap-3.5">
        <ThisPeriodPanel />

        <Panel
          title="My notes"
          hint="private to me"
          testId={`${TEST_ID}.Notes`}
          foot="Teammates do not see any of this — a note is keyed on the person who wrote it, not on the card."
        >
          {noted.length === 0 ? (
            <Nothing testId={`${TEST_ID}.Notes.None`}>
              You have written none. A note is your own reminder on a card — including on a review
              somebody else asked of you, which is the case it exists for.
            </Nothing>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
              {noted.map((card) => (
                <li
                  key={card.id}
                  className="rounded-[8px] border border-line bg-paper px-2.5 py-2"
                  data-testid={`${TEST_ID}.Notes.Row`}
                >
                  <RouterLink
                    to={linkFor(card)}
                    className="text-[12.5px] font-medium text-ink no-underline hover:text-steel"
                  >
                    {card.title}
                  </RouterLink>
                  {card.annotation?.note != null && (
                    <p
                      className="mt-1 mb-0 text-[12.5px] leading-[1.5] text-ink-2 italic"
                      data-testid={`${TEST_ID}.Notes.Row.Note`}
                    >
                      {card.annotation.note}
                    </p>
                  )}
                  <div className="mt-1.5 flex flex-wrap items-center gap-2 text-[11px] text-ink-4">
                    {card.annotation?.personalDueDate != null && (
                      <span data-testid={`${TEST_ID}.Notes.Row.Due`}>
                        {`My due date: ${formatDate(card.annotation.personalDueDate)}`}
                      </span>
                    )}
                    {annotatable(card) && (
                      <Button
                        size="sm"
                        variant="quiet"
                        onClick={() => onNote(card)}
                        data-testid={`${TEST_ID}.Notes.Row.Edit`}
                      >
                        Edit
                      </Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        {/*
          The mockup's "7 open · 2 due today" chip, with the second half changed to what the server
          actually says. `overdue` is computed server-side in UTC against the card's due date; "due
          today" would be a second definition of today living in the browser's timezone, and the two
          disagree for every reader west of UTC after 23:00 — the failure that made the board's own
          overdue rule a server fact in the first place.
        */}
        <p className="m-0 text-[12px] text-ink-4" data-testid={`${TEST_ID}.Summary`}>
          {`${plural(rows.length, 'open item')}${overdue > 0 ? ` · ${overdue} overdue` : ''}`}
        </p>
      </div>
    </div>
  );
}

/**
 * A card's kind, in the reader's words.
 *
 * The same map the board's cards carry, restated here rather than imported because the board's is
 * private to that file and this is the second reader of it — a shared one is earned if a third
 * arrives. `DOC_UPDATE` and `DEFECT` have no generator behind them and appear on no board today;
 * they are in the map so that the day one lands, the row reads as itself rather than as a code.
 */
const kindLabels: Record<BoardCard['kind'], string> = {
  ISSUE: 'Issue',
  EXECUTION: 'Execution',
  REVIEW: 'Review',
  DOC_UPDATE: 'Documentation',
  CASE_MAINTENANCE: 'Maintenance',
  DEFECT: 'Defect',
  QUARANTINE: 'Quarantined',
};

function CardStatus({ card, testId }: { card: BoardCard; testId: string }) {
  return (
    <Badge tone={STATUS_TONE[card.status.kind]} data-testid={testId}>
      {card.status.name}
    </Badge>
  );
}

/**
 * A due date, red when the server says it is past.
 *
 * **A word in red, never a red row.** Colour alone fails a greyscale print and a colour-blind
 * reader, and a whole red row drowns everything else on it — the board's own rule, and the reason
 * "Overdue" is spelled out rather than implied by the colour.
 */
function Due({ card, testId }: { card: BoardCard; testId: string }) {
  return (
    <TableCell
      data-testid={testId}
      className={cn(card.overdue && card.dueDate && 'text-[color:var(--color-failed)]')}
    >
      {card.dueDate == null ? (
        <span className="text-ink-4">&mdash;</span>
      ) : card.overdue ? (
        `Overdue · ${formatDate(card.dueDate)}`
      ) : (
        formatDate(card.dueDate)
      )}
    </TableCell>
  );
}

/**
 * The note affordance, **absent rather than disabled** on a card that has no `sourceId`.
 *
 * §31's disable-don't-hide is about a capability this reader lacks — a greyed control teaches that
 * it exists and is worth asking for. This is not that: the annotation is keyed on the pair the card
 * carries, so a card without one cannot be annotated by anybody, and a disabled button would
 * promise a permission that would never help.
 */
function NoteCell({
  card,
  onNote,
}: {
  card: BoardCard;
  onNote: (card: Annotatable) => void;
}) {
  const has = card.annotation?.note != null || card.annotation?.personalDueDate != null;

  return (
    <TableCell align="right">
      {annotatable(card) ? (
        <Button
          size="sm"
          variant="quiet"
          onClick={() => onNote(card)}
          data-testid={`${TEST_ID}.Row.Note`}
          aria-label={`${has ? 'Edit' : 'Add'} your private note on ${card.title}`}
        >
          {has ? 'Note ✓' : 'Note'}
        </Button>
      ) : (
        <span className="text-ink-4">&mdash;</span>
      )}
    </TableCell>
  );
}

/**
 * `my-work.html`'s "This week", as the fortnight the product actually keeps (016 D-a).
 *
 * **Logged and Capacity, with the meter under them** — `my-work.html`'s own `g2` row.
 * ~~Logged alone (016 D-j).~~ → superseded 2026-09-04 (plan 017); the file header carries the
 * reason the second figure was absent and why the absence ended.
 *
 * The logged figure is `TimesheetPeriod.loggedHours` — everything in the period, whatever anybody
 * has decided about it — and the label says so, because 016 D-d rules out a bare "hours" and this
 * one is deliberately not the approved figure: it is the person's own view of their own fortnight,
 * where what they have written down is the useful number and approval is somebody else's step.
 *
 * Its own region, so a failed timesheet read empties this panel and leaves the two tables and the
 * notes exactly where they were. `useCurrentTimesheetPeriod` is a **write** — it creates the period
 * row so Submit has an id — which is why the link below it can always reach a real timesheet.
 */
function ThisPeriodPanel() {
  const today = todayIso();
  const period = useCurrentTimesheetPeriod(today);

  return (
    <Panel title="This period" testId={`${TEST_ID}.Period`}>
      <div className="px-3.5 py-3.5">
        <DataRegion
          testId={`${TEST_ID}.Period.Region`}
          state={{ ...period, refetch: () => void period.refetch() }}
        >
          {(loaded) => <PeriodFigures period={loaded} />}
        </DataRegion>
      </div>
    </Panel>
  );
}

/**
 * The two figures and the meter — a **second** read, over the loaded period's own bounds.
 *
 * <h2>The window is the period, and it comes off the payload</h2>
 *
 * `period.startsOn` / `period.endsOn`, never `periods.ts`' arithmetic: that module is navigation
 * and labelling only (016 D-a), and a second endpoint asked over a client-derived fortnight could
 * be answered for a range this panel is not labelled with. `GET /staffing/capacity/me` makes both
 * bounds **required**, unlike every other window in that module, so there is no defaulted answer to
 * fall back on if one were dropped.
 *
 * <h2>The capacity read is its own region, nested inside this one</h2>
 *
 * Capacity is `staffing`'s and the hours are `time`'s (017 D-m). One region over both would let a
 * staffing outage blank the logged hours — a number a different module served correctly — so the
 * failure is scoped to the half that could not be answered.
 *
 * The **meter renders off the same loaded payload and is absent without it**: no capacity figure
 * means no denominator, which is a bar with nothing to be a fraction of. That covers all three
 * absent cases at once — the read failed, the read is still in flight, or it answered for somebody
 * with no `person_profile` row — and none of them may be drawn as a bar against a made-up 40.
 */
function PeriodFigures({ period }: { period: TimesheetPeriod }) {
  const capacity = useMyCapacity(period.startsOn, period.endsOn);
  const hours = capacity.data?.hasProfile === true ? capacity.data.capacityHours : null;

  return (
    <>
      <div className="grid gap-3.5 sm:grid-cols-2">
        <PeriodFigure
          testId={`${TEST_ID}.Period.Logged`}
          value={formatHours(period.loggedHours)}
          label="Logged"
        />
        <DataRegion
          testId={`${TEST_ID}.Period.Capacity.Region`}
          state={{ ...capacity, refetch: () => void capacity.refetch() }}
          skeleton={<Skeleton className="h-[46px]" />}
        >
          {(loaded) => (
            <PeriodFigure
              testId={`${TEST_ID}.Period.Capacity`}
              value={capacityLabel(loaded.capacityHours, loaded.hasProfile)}
              label="Capacity"
              detail={loaded.hasProfile ? undefined : NO_PROFILE}
            />
          )}
        </DataRegion>
      </div>

      {hours !== null && hours > 0 && (
        <PeriodMeter logged={period.loggedHours} capacity={hours} />
      )}

      <span className="mt-3 block text-[11.5px] text-ink-4">
        {formatPeriodRange(period.startsOn, period.endsOn, 'short')}
      </span>
      <RouterLink
        to="/timesheets"
        data-testid={`${TEST_ID}.Period.Open`}
        className="mt-3 inline-block text-[12.5px] text-steel no-underline hover:underline"
      >
        Open my timesheet →
      </RouterLink>
    </>
  );
}

/** One of the panel's two figures. The kit's `Tile` is a `<button>` and neither of these has rows. */
function PeriodFigure({
  testId,
  value,
  label,
  detail,
}: {
  testId: string;
  value: string;
  label: string;
  detail?: string;
}) {
  return (
    <div data-testid={testId}>
      <span className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
        {value}
      </span>
      <span className="mt-1.5 block text-[12.5px] text-ink-2">{label}</span>
      {detail && <span className="mt-[3px] block text-[11.5px] text-ink-4">{detail}</span>}
    </div>
  );
}

/**
 * `my-work.html`'s `meter tall` — logged against capacity, clamped, with a tone above it.
 *
 * **Clamped at 100% and re-toned rather than overflowing.** A bar drawn at 140% either runs out of
 * its track or silently rescales the one beside it; the honest rendering of "you have logged more
 * than your contracted hours" is a full bar in the over tone plus the percentage said in words
 * next to it, which is the number a reader would act on anyway.
 *
 * `aria-hidden` on the track, because the two figures above it and the caption beside it already
 * say everything the bar does — a `progressbar` role here would announce a third reading of the
 * same fact.
 */
function PeriodMeter({ logged, capacity }: { logged: number; capacity: number }) {
  const percent = Math.round((logged / capacity) * 100);
  const over = percent > 100;

  return (
    <div className="mt-3" data-testid={`${TEST_ID}.Period.Meter`}>
      {/* `.meter.tall` from `assets/tcms-mock.css`, on the app's own tokens: 9px, `--color-overlay-3`
          as the track (`wash-3` here) and `.done` / `.risk` as the two fills. */}
      <span aria-hidden className="flex h-[9px] overflow-hidden rounded-full bg-wash-3">
        <span
          className="block h-full"
          style={{
            width: `${Math.min(percent, 100)}%`,
            backgroundColor: over ? 'var(--color-danger)' : 'var(--color-success)',
          }}
        />
      </span>
      <span
        className={cn(
          'mt-1.5 block text-[11.5px]',
          over ? 'text-[color:var(--color-danger)]' : 'text-ink-4',
        )}
        data-testid={`${TEST_ID}.Period.Meter.Caption`}
      >
        {over ?
          `${percent}% of your capacity — more than your contracted hours`
        : `${percent}% of your capacity`}
      </span>
    </div>
  );
}

function Panel({
  title,
  count,
  hint,
  foot,
  testId,
  children,
}: {
  title: string;
  count?: number;
  hint?: string;
  foot?: string;
  testId: string;
  children: ReactNode;
}) {
  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={testId}>
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">{title}</h2>
        {count !== undefined && <Identifier variant="muted">{String(count)}</Identifier>}
        {hint && <span className="text-[11.5px] text-ink-4">{hint}</span>}
      </div>
      <div>{children}</div>
      {foot && (
        <div className="border-t border-line px-3.5 py-2.5">
          <span className="text-[11.5px] leading-[1.55] text-ink-4">{foot}</span>
        </div>
      )}
    </section>
  );
}

/**
 * One half of this screen being empty while the other is not.
 *
 * Not a `DataRegion` empty state: the response arrived and is not empty — it simply has nothing of
 * *this* kind in it. Saying "nothing found" in a panel beside a populated one is the reading failure
 * design language §7 is about, so each of these says what the panel would hold.
 */
function Nothing({ children, testId }: { children: ReactNode; testId: string }) {
  return (
    <p className="m-0 max-w-[62ch] px-3.5 py-4 text-[12.5px] leading-[1.55] text-ink-4" data-testid={testId}>
      {children}
    </p>
  );
}
