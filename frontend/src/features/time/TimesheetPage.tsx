import { useMemo, useState, type ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Clock } from 'lucide-react';
import {
  useAmendTimeEntry,
  useCurrentTimesheetPeriod,
  useDeleteTimeEntry,
  useLogTime,
  useMyCapacity,
  useSubmitTimesheet,
  useTimesheetPeriods,
} from '../../api/queries';
import type { SelfCapacity, TimeEntry, TimeEntryInput, TimesheetPeriod } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner, TableFoot } from '../../components/kit';
import { Button, Skeleton } from '../../components/ui';
import { NO_PROFILE, capacityLabel } from '../staffing/capacityCopy';
import { plural } from '../../lib/format';
import { timesheetStateLabels, timesheetStateTones } from '../../lib/labels';
import { EntryForm } from './EntryForm';
import { EntryTable } from './EntryTable';
import { formatPeriodRange, nextPeriod, periodContaining, previousPeriod, todayIso } from './periods';

/**
 * `timesheets.html` — one person's own fortnight, and the only screen in the product that creates
 * a time entry.
 *
 * <h2>~~Three KPI tiles, not four, and the fourth is absent rather than invented (016 D-j)~~ →
 * superseded 2026-09-04 (plan 017). Four tiles, and the fourth is a figure that now exists</h2>
 *
 * ~~The mockup shows a `Period capacity 64h` tile. Capacity is computed from `person_profile`,
 * `allocation` and `absence` — all three are M7's tables and M7 has not started — so there is no
 * figure to put there that this module would not be making up. A capacity number invented here is
 * the "two contradictory percentages on one screen" failure CLAUDE.md records, and it would be
 * contradicted by M7's real one the week after. It is therefore missing, and stays missing.~~
 *
 * The old reasoning is kept rather than deleted because it was right, and because what changed is
 * its **premise** and not its argument: M7 landed those three tables on 2026-09-04 (`V10`, plan
 * 017), and `staffing.Capacity` derives the figure from them on every read. So nothing here is
 * invented — and D-j's actual refusal survives in the one state that would otherwise be a `40h`
 * somebody made up: where the caller has no `person_profile` row the tile reads `—` and *no
 * profile recorded*, never `0h` and never a default week.
 *
 * <h2>The capacity figure is a SECOND read, and it is `staffing`'s (017 D-m)</h2>
 *
 * `TimesheetPeriod` cannot carry `capacityHours`, because `time` cannot compute it — capacity comes
 * from `person_profile`, `allocation` and `absence`, none of which this module owns. So the screen
 * makes two reads, and the second is its **own** `DataRegion` inside the tile: a staffing outage
 * empties that one tile and leaves the logged hours, which are this module's and were fine, exactly
 * where they were.
 *
 * The window it asks for is **this period's own `startsOn` / `endsOn`, off the loaded payload** —
 * `periods.ts` is for navigation and labelling only (016 D-a), and re-deriving a fortnight here to
 * hand to a second endpoint is the thing that file's header forbids.
 *
 * <h2>The screen never opens a period, because the read creates one</h2>
 *
 * `GET /timesheets/periods/current` writes the row if it is not there, which is what makes `id`
 * available to Submit on a fortnight nobody has logged into yet. There is no "create period" call
 * anywhere in this module and looking for one is looking for something the design removed.
 *
 * <h2>Nothing here derives the lock</h2>
 *
 * `period.editable` and `entry.editable` are served, and this screen reads them rather than
 * computing anything from `state`: the Add form is present when the period is editable and gone
 * when it is not, and the row controls are per row. A SUBMITTED fortnight therefore renders no form
 * and no pencils without this file containing the rule, and a REJECTED one renders both — which is
 * the half a reader expects to be the other way round. `tcmsFaults.time('rejected')` is what makes
 * that state reachable without a second person.
 *
 * <h2>The picker is a URL parameter, because filter state lives in the URL</h2>
 *
 * `?on=` is any date inside the wanted fortnight, which is what the endpoint takes. The two arrows
 * step it with `periods.ts`' arithmetic and nothing else on the screen uses that arithmetic: every
 * range rendered comes from the payload's own `startsOn` / `endsOn`.
 */

const TEST_ID = 'Timesheet';

export function TimesheetPage() {
  const [params, setParams] = useSearchParams();
  const on = params.get('on') ?? todayIso();

  const period = useCurrentTimesheetPeriod(on);
  const history = useTimesheetPeriods();

  const log = useLogTime();
  const amend = useAmendTimeEntry();
  const remove = useDeleteTimeEntry();
  const submit = useSubmitTimesheet();

  const [editing, setEditing] = useState<TimeEntry | null>(null);

  const step = (to: string) => {
    setEditing(null);
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.set('on', to);
      return next;
    });
  };

  /*
    The previous fortnight, out of the caller's own history — not out of the queue and not derived.
    It is what the Approved tile counts and what the right-hand panel renders, and it is found by
    its bounds rather than by position: the list is newest-first, so `[1]` would be the previous
    period only while the picker is on today.
  */
  const previousBounds = periodContaining(previousPeriod(on));
  const previous = useMemo(
    () => history.data?.find((row) => row.startsOn === previousBounds.startsOn) ?? null,
    [history.data, previousBounds.startsOn],
  );

  const writing = log.isPending || amend.isPending || remove.isPending;

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Timesheets"
        documentTitle="My timesheet"
        subtitle={
          period.data
            ? `${period.data.personName} · period ${formatPeriodRange(period.data.startsOn, period.data.endsOn)}`
            : 'Hours per issue and per category, for one fortnight at a time.'
        }
        actions={
          <>
            <PeriodPicker
              on={on}
              label={
                period.data
                  ? formatPeriodRange(period.data.startsOn, period.data.endsOn, 'short')
                  : formatPeriodRange(
                      periodContaining(on).startsOn,
                      periodContaining(on).endsOn,
                      'short',
                    )
              }
              onStep={step}
              testId={`${TEST_ID}.Picker`}
            />
            {/*
              Listed for everybody, like every other entry into a screen in this app: the queue
              refuses a caller without `time:approve` with a sentence of its own, and there is no
              way to ask "do you hold a PROJECT-scope grant on any project" without making the
              refused read the whole company would get. See `AppShell`'s note on the rail entry.
            */}
            <Button size="sm" asChild>
              <RouterLink to="/timesheets/approvals" data-testid={`${TEST_ID}.Approvals`}>
                Approvals queue
              </RouterLink>
            </Button>
            <SubmitButton period={period.data} submit={submit} testId={`${TEST_ID}.Submit`} />
          </>
        }
      />

      <DataRegion
        testId={`${TEST_ID}.Period`}
        state={{ ...period, refetch: () => void period.refetch() }}
        skeleton={<GridSkeleton rows={6} />}
      >
        {(loaded) => (
          <>
            <Kpis period={loaded} previous={previous} />

            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_300px]">
              <div className="flex flex-col gap-4">
                {loaded.state === 'REJECTED' && loaded.decisionNote && (
                  <Banner tone="warn" data-testid={`${TEST_ID}.RejectionNote`}>
                    <div className="min-w-0">
                      <p className="m-0 text-[13px] font-semibold text-ink">
                        {`${loaded.decidedByName ?? 'Your approver'} sent this period back`}
                      </p>
                      <p className="mt-1 mb-0 italic">{loaded.decisionNote}</p>
                      <p className="mt-2 mb-0">
                        The period is unlocked again, and editable. Fix it and re-submit.
                      </p>
                    </div>
                  </Banner>
                )}

                {loaded.state === 'SUBMITTED' && (
                  <Banner tone="steel" data-testid={`${TEST_ID}.Locked`}>
                    <span>
                      This period is with your approver and nothing in it can be changed. If
                      something is wrong, ask them to send it back — a rejection unlocks it and
                      keeps the note.
                    </span>
                  </Banner>
                )}

                {loaded.state === 'APPROVED' && (
                  <Banner tone="neutral" data-testid={`${TEST_ID}.Approved`}>
                    <span>
                      {`Approved${loaded.decidedByName ? ` by ${loaded.decidedByName}` : ''}. These hours are `}
                      posted onto their issues and rolled up to the story, the epic and the project.
                      An approved period is final.
                    </span>
                  </Banner>
                )}

                <Panel title="Entries" testId={`${TEST_ID}.Entries`} dense>
                  {loaded.entries.length === 0 ? (
                    <div className="px-4 py-6 text-[12.5px] text-ink-3">
                      Nothing logged in this fortnight yet.
                      {loaded.editable
                        ? ' Add the first entry below — it does not have to be an issue.'
                        : ''}
                    </div>
                  ) : (
                    <>
                      <EntryTable
                        entries={loaded.entries}
                        testId={`${TEST_ID}.Table`}
                        busy={writing}
                        onEdit={loaded.editable ? setEditing : undefined}
                        onDelete={
                          loaded.editable ? (entry) => remove.mutate(entry.id) : undefined
                        }
                      />
                      <TableFoot
                        data-testid={`${TEST_ID}.Foot`}
                        summary={
                          <>
                            {`${plural(loaded.entries.length, 'entry')} · ${describeSpread(loaded)} · Total `}
                            <strong className="font-mono font-medium text-ink-2">
                              {`${loaded.loggedHours.toFixed(1)}h`}
                            </strong>
                          </>
                        }
                      />
                    </>
                  )}
                </Panel>

                {/*
                  The form is present exactly when the period says it is editable, and absent
                  otherwise — never disabled. A greyed form on a submitted fortnight invites somebody
                  to fill it in and find out at the end that they could not.

                  Keyed on the entry being amended, so switching rows re-seeds the fields without an
                  effect. See `EntryForm`'s own note.
                */}
                {loaded.editable && (
                  <Panel
                    title={editing ? 'Amend this entry' : 'Add an entry'}
                    testId={`${TEST_ID}.Form`}
                  >
                    <EntryForm
                      key={editing?.id ?? 'new'}
                      defaultDate={defaultDateFor(loaded, on)}
                      editing={editing}
                      pending={writing}
                      onCancelEdit={() => setEditing(null)}
                      onSubmit={async (input: TimeEntryInput) => {
                        if (editing) {
                          await amend.mutateAsync({ entryId: editing.id, input });
                          setEditing(null);
                          return;
                        }
                        await log.mutateAsync(input);
                      }}
                    />
                  </Panel>
                )}
              </div>

              <div className="flex flex-col gap-4">
                <PreviousPanel state={history} previous={previous} onOpen={step} />

                {/*
                  `timesheets.html`'s *"What you never see here"* panel, kept because it is the
                  screen saying out loud what the scope document calls the sharpest boundary in the
                  product. It is the one place a person is told the omission is a decision rather
                  than a screen somebody has not built yet, and deleting it would leave the money
                  question unanswered on the only screen that provokes it.
                */}
                <Panel title="What you never see here" testId={`${TEST_ID}.NoMoney`}>
                  <p className="m-0 text-[12.5px] leading-[1.6] text-ink-2">
                    No rate, no cost, no invoice value. Logging time tells you <em>where the day
                    went</em> and nothing about what it is worth — that is a different job, on a
                    screen you do not have.
                  </p>
                </Panel>
              </div>
            </div>
          </>
        )}
      </DataRegion>
    </div>
  );
}

/**
 * Logged · Period capacity · Approved · Status. ~~**Three, and the fourth is D-j's absence.**~~ →
 * superseded 2026-09-04 (plan 017): **four**, in the order `timesheets.html` draws them.
 *
 * `Tile` from the kit is deliberately not used, for `ReadinessTiles`' reason: it is a `<button>`
 * that navigates to its rows, and none of these four has rows to navigate to. The entries are the
 * table directly below, a capacity is a computation, and a status is not a list.
 */
function Kpis({
  period,
  previous,
}: {
  period: TimesheetPeriod;
  previous: TimesheetPeriod | null;
}) {
  /*
    The period's OWN bounds, off the loaded payload — not `periodContaining(on)`. `periods.ts` is
    navigation and labelling only (016 D-a): the server decides where a fortnight starts, and a
    second read asked over a client-derived range could be answered for a window the tile beside it
    is not labelled with.

    The hook is here rather than in the page body because this is where the loaded period is, and
    `Kpis` renders only inside the outer region's loaded branch.
  */
  const capacity = useMyCapacity(period.startsOn, period.endsOn);

  return (
    <div className="mb-4 grid gap-3 md:grid-cols-4">
      <KpiTile
        testId={`${TEST_ID}.Kpi.Logged`}
        label="Logged this period"
        value={`${period.loggedHours.toFixed(1)}h`}
        detail="everything in it, whatever anybody has decided about it"
      />
      <CapacityTile state={capacity} />
      {/*
        The PREVIOUS period's approved hours, which is what the mockup's tile counts and says. This
        one's `approvedHours` is zero until somebody approves it (D-d), so a tile reading "Approved"
        off the fortnight you are still filling in would say 0h every day of it.
      */}
      <KpiTile
        testId={`${TEST_ID}.Kpi.Approved`}
        label="Approved"
        value={previous ? `${previous.approvedHours.toFixed(1)}h` : '—'}
        detail={
          previous
            ? `from ${formatPeriodRange(previous.startsOn, previous.endsOn, 'short')}`
            : 'no previous period on record'
        }
      />
      <KpiTile
        testId={`${TEST_ID}.Kpi.Status`}
        label="Status"
        value={
          <Badge tone={timesheetStateTones[period.state]}>
            {timesheetStateLabels[period.state]}
          </Badge>
        }
        detail={STATUS_DETAIL[period.state]}
      />
    </div>
  );
}

/**
 * `timesheets.html`'s `Period capacity 64h` — the tile 016 D-j left out, in its own region.
 *
 * <h2>Its own `DataRegion`, and that is the whole point of it being a component</h2>
 *
 * The figure comes from `staffing`, the three beside it from `time` (017 D-m). One region over the
 * four would let a staffing outage blank `Logged this period` — a number a different module served
 * correctly — so the failure is scoped to the tile that could not be answered, and the other three
 * stay on screen. `DataRegion` also owns the *third* state `capacityCopy.ts` names: a read that did
 * not answer is a fact about the network, and printing *no profile recorded* over it would be this
 * screen telling the reader a fact about a person instead.
 *
 * <h2>`—` is not `0h`, and it is not `40h`</h2>
 *
 * {@link capacityLabel} consults `hasProfile` as well as the hours, because the server sends the
 * two nulls together and either alone is enough to know there is no number. A person nobody has
 * profiled has no contracted hours on record — {@link NO_PROFILE} is the sentence for that, spelled
 * once in the shared layer so this tile, My Work's meter, the timeline, the directory card and the
 * person page cannot end up with five wordings of the same absence.
 */
function CapacityTile({ state }: { state: UseQueryResult<SelfCapacity> }) {
  return (
    <DataRegion
      testId={`${TEST_ID}.Kpi.Capacity.Region`}
      state={{ ...state, refetch: () => void state.refetch() }}
      skeleton={<Skeleton className="h-[84px] rounded-[8px]" />}
    >
      {(loaded) => (
        <KpiTile
          testId={`${TEST_ID}.Kpi.Capacity`}
          label="Period capacity"
          value={capacityLabel(loaded.capacityHours, loaded.hasProfile)}
          detail={
            loaded.hasProfile
              ? 'contracted hours over this period, net of absence'
              : NO_PROFILE
          }
        />
      )}
    </DataRegion>
  );
}

/** What each state means for the person looking at their own fortnight, in one line. */
const STATUS_DETAIL = {
  OPEN: 'editable until you submit',
  SUBMITTED: 'with your approver — editable by nobody',
  APPROVED: 'final, and posted onto the issues',
  REJECTED: 'unlocked again — fix it and re-submit',
} as const;

function KpiTile({
  label,
  value,
  detail,
  testId,
}: {
  label: string;
  value: ReactNode;
  detail: string;
  testId: string;
}) {
  return (
    <section
      data-testid={testId}
      className="rounded-[8px] border border-line bg-panel px-[15px] py-[13px]"
    >
      <span className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
        {value}
      </span>
      <span className="mt-1.5 block text-[12.5px] text-ink-2">{label}</span>
      <span className="mt-[3px] block text-[11.5px] text-ink-4">{detail}</span>
    </section>
  );
}

/** Two arrows and the range, on the URL. `Today` only when the picker has left it. */
function PeriodPicker({
  on,
  label,
  onStep,
  testId,
}: {
  on: string;
  label: string;
  onStep: (to: string) => void;
  testId: string;
}) {
  const today = todayIso();
  const away = periodContaining(on).startsOn !== periodContaining(today).startsOn;

  return (
    <span className="flex items-center gap-1" data-testid={testId}>
      <Button
        size="icon"
        variant="quiet"
        onClick={() => onStep(previousPeriod(on))}
        aria-label="Previous period"
        data-testid={`${testId}.Previous`}
      >
        <ChevronLeft className="size-4" />
      </Button>
      <Identifier variant="muted" testId={`${testId}.Label`}>
        {label}
      </Identifier>
      <Button
        size="icon"
        variant="quiet"
        onClick={() => onStep(nextPeriod(on))}
        aria-label="Next period"
        data-testid={`${testId}.Next`}
      >
        <ChevronRight className="size-4" />
      </Button>
      {away && (
        <Button size="sm" onClick={() => onStep(today)} data-testid={`${testId}.Today`}>
          Today
        </Button>
      )}
    </span>
  );
}

/**
 * Submit — the caller's own period, and only from a state that can be submitted.
 *
 * Disabled with a reason rather than hidden: an approver's queue is what a submitted fortnight is
 * waiting in, and a person who cannot find the button on a period they have already sent needs to
 * be told which of the two it is. `time:log` is what it costs (D-e), which every role holds, so
 * there is no `PermissionGate` here — the only thing that can refuse this is the period's state.
 */
function SubmitButton({
  period,
  submit,
  testId,
}: {
  period: TimesheetPeriod | undefined;
  submit: { mutate: (periodId: string) => void; isPending: boolean };
  testId: string;
}) {
  if (!period) return null;
  const sendable = period.state === 'OPEN' || period.state === 'REJECTED';

  return (
    <Button
      variant="primary"
      size="sm"
      disabled={!sendable || submit.isPending}
      title={sendable ? undefined : SUBMIT_REFUSAL[period.state]}
      onClick={() => submit.mutate(period.id)}
      data-testid={testId}
    >
      {period.state === 'REJECTED' ? 'Re-submit for approval' : 'Submit for approval'}
    </Button>
  );
}

const SUBMIT_REFUSAL = {
  OPEN: undefined,
  REJECTED: undefined,
  SUBMITTED: 'Already with your approver. Ask them to send it back if something is wrong.',
  APPROVED: 'Approved periods are final.',
} as const;

/**
 * The right-hand *Previous period* panel — and the way back to a fortnight that was sent back.
 *
 * It is a region of its own with its own failure, because the history is a second read beside the
 * current period and a screen that turned red because the panel beside the table could not load is
 * the scoping rule broken in the obvious way.
 */
function PreviousPanel({
  state,
  previous,
  onOpen,
}: {
  state: {
    data: TimesheetPeriod[] | undefined;
    isPending: boolean;
    isError: boolean;
    error: unknown;
    refetch: () => void;
  };
  previous: TimesheetPeriod | null;
  onOpen: (on: string) => void;
}) {
  return (
    <Panel title="Previous period" testId={`${TEST_ID}.Previous`}>
      <DataRegion
        testId={`${TEST_ID}.Previous.Region`}
        state={{ ...state, refetch: () => state.refetch() }}
        skeleton={<GridSkeleton rows={2} />}
      >
        {() =>
          previous ? (
            <div className="flex flex-col gap-2.5">
              <div className="flex items-center gap-2">
                <Badge tone={timesheetStateTones[previous.state]}>
                  {timesheetStateLabels[previous.state]}
                </Badge>
                <span className="flex-1" />
                <span className="text-[11.5px] text-ink-4">
                  {formatPeriodRange(previous.startsOn, previous.endsOn, 'short')}
                </span>
              </div>

              {previous.state === 'REJECTED' && previous.decisionNote && (
                <Banner tone="warn" data-testid={`${TEST_ID}.Previous.Note`}>
                  <div className="min-w-0">
                    <strong className="font-medium text-ink">
                      {`${previous.decidedByName ?? 'Your approver'} wrote:`}
                    </strong>{' '}
                    <em>{previous.decisionNote}</em>
                  </div>
                </Banner>
              )}

              <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
                {previous.state === 'REJECTED'
                  ? 'The period is unlocked again, and editable. Fix it and re-submit.'
                  : `${previous.loggedHours.toFixed(1)}h logged, ${previous.approvedHours.toFixed(1)}h approved.`}
              </p>

              <Button
                size="sm"
                onClick={() => onOpen(previous.startsOn)}
                data-testid={`${TEST_ID}.Previous.Open`}
              >
                {`Open ${formatPeriodRange(previous.startsOn, previous.endsOn, 'short')}`}
              </Button>
            </div>
          ) : (
            <p className="m-0 flex items-start gap-2 text-[12.5px] leading-[1.55] text-ink-3">
              <Clock aria-hidden className="mt-0.5 size-3.5 flex-none text-ink-4" />
              Nothing before this one yet. A period appears here once you have logged into it.
            </p>
          )
        }
      </DataRegion>
    </Panel>
  );
}

/**
 * The mockup's *"two projects · two non-issue categories"* footer, counted rather than written.
 *
 * The projects are counted off the entries that name one, so a fortnight of meetings says only what
 * it is — the same reason `EntryTable` refuses to call a project-less row "—".
 */
function describeSpread(period: TimesheetPeriod): string {
  const projects = new Set(
    period.entries.map((entry) => entry.subject?.projectId).filter((id) => id != null),
  );
  const categories = new Set(
    period.entries.filter((entry) => !entry.subject).map((entry) => entry.subjectType),
  );

  const parts: string[] = [];
  if (projects.size > 0) parts.push(plural(projects.size, 'project'));
  if (categories.size > 0) parts.push(`${plural(categories.size, 'category')} outside an issue`);
  return parts.length > 0 ? parts.join(' · ') : 'nothing against an issue or a category';
}

/**
 * The day the Add form opens on.
 *
 * Today when today is inside the fortnight being looked at, and the period's own first day
 * otherwise — because a form seeded with today's date on a period two months back would answer a
 * `409` from a fortnight the person is not even looking at, and the refusal would name a period
 * they cannot see.
 */
function defaultDateFor(period: TimesheetPeriod, on: string): string {
  const today = todayIso();
  if (today >= period.startsOn && today <= period.endsOn) return today;
  return periodContaining(on).startsOn;
}
