import { useState } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Inbox } from 'lucide-react';
import { useDecideTimesheet, useTimesheetApprovals } from '../../api/queries';
import type { TimesheetQueueEntry } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, plural } from '../../lib/format';
import { timesheetStateLabels, timesheetStateTones } from '../../lib/labels';
import { EntryTable } from './EntryTable';
import { RejectDialog } from './RejectDialog';
import { formatPeriodRange, nextPeriod, periodContaining, previousPeriod, todayIso } from './periods';

/**
 * `timesheet-approvals.html` — one fortnight, one panel per person, one decision each.
 *
 * <h2>Approving somebody's period approves the whole of it (016 D-i)</h2>
 *
 * A period is per person and **spans projects**, while `time:approve` is a PROJECT-scope grant. So
 * a fortnight appears here when at least one of its entries names a project this caller may approve
 * for, and approving it approves everything in it — including entries against projects they do not
 * hold. That is the sharp edge, and the panel foot says it out loud rather than leaving it to be
 * discovered: the alternative, per-project partial approval, is a state this screen has no way to
 * render, because its Approve button is one button per person.
 *
 * <h2>`projectTotals` deliberately does not add up to `loggedHours`, and the labels say so</h2>
 *
 * A project-less entry has no row in it — the contract is explicit that the literal em dash is not
 * used, because a dash in a column of project names is a project called dash — and neither has an
 * entry against a project this approver cannot see. So the two numbers are labelled apart: the
 * header carries what the person logged, the totals row carries what it is possible to attribute
 * here, and the remainder is named rather than left as an unexplained gap. Two numbers on one panel
 * that do not agree, with nothing saying why, is the failure this labelling exists to prevent.
 *
 * <h2>The queue is not only the submitted ones</h2>
 *
 * The mockup draws an OPEN period as *"Not submitted · 41.0h so far"*, so the endpoint serves every
 * state and this screen decides what may be done with each. An unfinished period gets no approve
 * action at all — it is not something to approve — and the copy says so in the mockup's own words.
 *
 * <h2>Two affordances the mockup draws and this screen does not have</h2>
 *
 * **Approve all** is absent. It is one press that irreversibly posts four other people's fortnights
 * onto their issues — there is no un-approve, only `WITHDRAW_REJECTION`, which reverses the other
 * decision — and the contract has no batch operation to make it one transaction, so it would be N
 * separate writes with a partial outcome to word. **Remind** is absent because there is no endpoint
 * behind it: nothing in the contract sends a person a message about their timesheet. Both are named
 * here rather than quietly dropped, so a reader comparing this against the mockup finds the reason
 * instead of a gap.
 */

const TEST_ID = 'Approvals';

export function ApprovalsPage() {
  const [params, setParams] = useSearchParams();
  const on = params.get('on') ?? todayIso();

  const queue = useTimesheetApprovals(on);
  const decide = useDecideTimesheet();

  /** The period the reject dialog is open over, or null. One dialog, whichever panel opened it. */
  const [rejecting, setRejecting] = useState<TimesheetQueueEntry | null>(null);

  const step = (to: string) => {
    setRejecting(null);
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.set('on', to);
      return next;
    });
  };

  const bounds = periodContaining(on);
  const waiting = queue.data?.filter((row) => row.period.state === 'SUBMITTED').length ?? 0;

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Timesheet approvals"
        documentTitle="Timesheet approvals"
        subtitle={
          queue.data
            ? `Period ${formatPeriodRange(bounds.startsOn, bounds.endsOn)} · ${waiting} waiting on you`
            : `Period ${formatPeriodRange(bounds.startsOn, bounds.endsOn)}`
        }
        actions={
          <>
            <span className="flex items-center gap-1" data-testid={`${TEST_ID}.Picker`}>
              <Button
                size="icon"
                variant="quiet"
                onClick={() => step(previousPeriod(on))}
                aria-label="Previous period"
                data-testid={`${TEST_ID}.Picker.Previous`}
              >
                <ChevronLeft className="size-4" />
              </Button>
              <Identifier variant="muted" testId={`${TEST_ID}.Picker.Label`}>
                {formatPeriodRange(bounds.startsOn, bounds.endsOn, 'short')}
              </Identifier>
              <Button
                size="icon"
                variant="quiet"
                onClick={() => step(nextPeriod(on))}
                aria-label="Next period"
                data-testid={`${TEST_ID}.Picker.Next`}
              >
                <ChevronRight className="size-4" />
              </Button>
            </span>
            <Button size="sm" asChild>
              <RouterLink to="/timesheets" data-testid={`${TEST_ID}.Mine`}>
                My timesheet
              </RouterLink>
            </Button>
          </>
        }
      />

      {/*
        The mockup's standing note, and the one sentence on this screen that explains why the queue
        matters at all: nothing below has reached a budget, a utilisation figure or an invoice line
        until somebody here acts on it.
      */}
      <div className="mb-4">
        <Banner tone="steel" data-testid={`${TEST_ID}.Consequence`}>
          <span>
            Only <strong className="font-medium text-ink">approved</strong> hours reach a budget, a
            utilisation figure or an invoice line. Everything below is on hold until you act on it.
          </span>
        </Banner>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Queue`}
        state={{ ...queue, refetch: () => void queue.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        skeleton={<GridSkeleton rows={4} />}
        empty={{
          kind: 'no-records',
          icon: Inbox,
          title: 'Nothing in this fortnight',
          body:
            'Nobody has logged an hour against a project you can approve for in '
            + `${formatPeriodRange(bounds.startsOn, bounds.endsOn)}. A period appears here as soon `
            + 'as somebody does — before it is submitted, so you can see it coming.',
        }}
      >
        {(rows) => (
          <div className="flex flex-col gap-4">
            {rows.map((row) => (
              <PersonPanel
                key={row.period.id}
                row={row}
                deciding={decide.isPending}
                onApprove={() =>
                  decide.mutate({ periodId: row.period.id, input: { decision: 'APPROVE' } })
                }
                onWithdraw={() =>
                  decide.mutate({
                    periodId: row.period.id,
                    input: { decision: 'WITHDRAW_REJECTION' },
                  })
                }
                onReject={() => setRejecting(row)}
              />
            ))}
          </div>
        )}
      </DataRegion>

      {rejecting && (
        <RejectDialog
          personName={rejecting.period.personName}
          pending={decide.isPending}
          error={decide.error}
          onClose={() => setRejecting(null)}
          onReject={(note) =>
            decide.mutate(
              { periodId: rejecting.period.id, input: { decision: 'REJECT', note } },
              { onSuccess: () => setRejecting(null) },
            )
          }
        />
      )}
    </div>
  );
}

/**
 * One person's fortnight, and the actions its state admits.
 *
 * The four branches are the mockup's four panels. `SUBMITTED` is the only one with a decision to
 * make; `REJECTED` carries the note back with a way to undo the rejection; `APPROVED` is final and
 * says so; `OPEN` is somebody still working, shown greyed with no action, because — in the mockup's
 * own words — an unfinished period is not something to approve.
 */
function PersonPanel({
  row,
  deciding,
  onApprove,
  onReject,
  onWithdraw,
}: {
  row: TimesheetQueueEntry;
  deciding: boolean;
  onApprove: () => void;
  onReject: () => void;
  onWithdraw: () => void;
}) {
  const { period, projectTotals } = row;
  const [showing, setShowing] = useState(false);

  const attributed = projectTotals.reduce((sum, total) => sum + total.hours, 0);
  const elsewhere = Math.max(0, period.loggedHours - attributed);

  return (
    <Panel
      testId={`${TEST_ID}.Person`}
      title={
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2.5">
          <h2 className="m-0 text-[13px] font-semibold tracking-[-.01em] text-ink">
            {period.personName}
          </h2>
          <Badge tone={timesheetStateTones[period.state]} data-testid={`${TEST_ID}.Person.State`}>
            {period.state === 'OPEN'
              ? 'Not submitted'
              : `${timesheetStateLabels[period.state]}${stampOf(period.submittedAt, period.decidedAt, period.state)}`}
          </Badge>
          <span className="flex-1" />
          {/*
            What the person logged, which is the number the decision is about. Labelled rather than
            left bare, because the totals row below it is a different quantity — see the file header
            — and two unlabelled numbers on one panel read as a contradiction. An unsubmitted period
            says `so far`, the mockup's own word: the figure is still moving, and `logged` on a
            fortnight somebody is still filling in reads as a final one.
          */}
          <Identifier variant="muted" testId={`${TEST_ID}.Person.Hours`}>
            {`${period.loggedHours.toFixed(1)}h ${period.state === 'OPEN' ? 'so far' : 'logged'}`}
          </Identifier>
        </div>
      }
      actions={
        period.state === 'SUBMITTED' ? (
          <>
            <Button size="sm" disabled={deciding} onClick={onReject} data-testid={`${TEST_ID}.Person.Reject`}>
              Reject…
            </Button>
            <Button
              size="sm"
              variant="primary"
              disabled={deciding}
              onClick={onApprove}
              data-testid={`${TEST_ID}.Person.Approve`}
            >
              Approve
            </Button>
          </>
        ) : period.state === 'REJECTED' ? (
          <Button
            size="sm"
            disabled={deciding}
            onClick={onWithdraw}
            data-testid={`${TEST_ID}.Person.Withdraw`}
          >
            Withdraw rejection
          </Button>
        ) : undefined
      }
    >
      <div className="flex flex-col gap-3">
        {period.state === 'REJECTED' && period.decisionNote && (
          <Banner tone="bad" data-testid={`${TEST_ID}.Person.Note`}>
            <div className="min-w-0">
              <strong className="font-medium text-ink">Your note:</strong>{' '}
              <em>{period.decisionNote}</em>
            </div>
          </Banner>
        )}

        {period.state === 'REJECTED' && (
          <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
            {`The period is unlocked for ${period.personName} again, with this note visible at the top of it. `}
            A rejection is never silent, and never a deletion.
          </p>
        )}

        {period.state === 'OPEN' && (
          <p className="m-0 text-[12.5px] leading-[1.55] text-ink-3">
            {`Still editable by ${period.personName}. There is nothing to approve until it is `}
            submitted — an unfinished period is not something to act on.
          </p>
        )}

        <ProjectTotals
          totals={projectTotals}
          elsewhere={elsewhere}
          ghost={period.state === 'OPEN'}
        />

        {period.entries.length > 0 && (
          <div>
            <Button
              size="sm"
              variant="link"
              onClick={() => setShowing((open) => !open)}
              aria-expanded={showing}
              data-testid={`${TEST_ID}.Person.Toggle`}
            >
              {showing ? 'Hide entries' : `Show ${plural(period.entries.length, 'entry')}`}
            </Button>
            {showing && (
              <div className="mt-2 overflow-hidden rounded-lg border border-line">
                <EntryTable
                  entries={period.entries}
                  testId={`${TEST_ID}.Person.Table`}
                  withProject
                />
              </div>
            )}
          </div>
        )}

        {period.state === 'SUBMITTED' && (
          <p className="m-0 border-t border-line pt-3 text-[11.5px] leading-[1.55] text-ink-4">
            Approving posts these hours onto each issue and rolls them up to the story, the epic and
            the project. It approves the <strong className="font-medium text-ink-3">whole</strong>{' '}
            period, including any entry against a project you do not approve for.
          </p>
        )}
      </div>
    </Panel>
  );
}

/**
 * The per-project row, and the remainder named beside it.
 *
 * `elsewhere` is `loggedHours` minus what the totals account for, and it is rendered as its own
 * chip with the reason in its tooltip rather than being folded into the sum. Folding it in would
 * make the two numbers agree by inventing a project; dropping it would leave a reader adding the
 * row up and finding it short of the header.
 */
function ProjectTotals({
  totals,
  elsewhere,
  ghost,
}: {
  totals: TimesheetQueueEntry['projectTotals'];
  elsewhere: number;
  ghost: boolean;
}) {
  if (totals.length === 0 && elsewhere === 0) return null;

  return (
    <div
      data-testid={`${TEST_ID}.Person.Totals`}
      className={`flex flex-wrap items-center gap-x-4 gap-y-1 text-[12.5px] ${ghost ? 'text-ink-4' : 'text-ink-2'}`}
    >
      {totals.map((total) => (
        <span key={total.projectName}>
          {total.projectName}{' '}
          <strong className="font-mono font-medium">{`${total.hours.toFixed(1)}h`}</strong>
        </span>
      ))}
      {elsewhere > 0 && (
        <span
          className="text-ink-4"
          data-testid={`${TEST_ID}.Person.Elsewhere`}
          title={
            'Hours against no project at all — a meeting, leave, training or internal work — and '
            + 'hours against projects you do not approve for. Approving the period approves these too.'
          }
        >
          {`${elsewhere.toFixed(1)}h not attributed here`}
        </span>
      )}
    </div>
  );
}

/**
 * ` 1 Sep` after the state word, from whichever timestamp that state was set by.
 *
 * Absent rather than "unknown" when the payload carries none: an OPEN period has neither stamp, and
 * a state word with nothing after it reads correctly on its own.
 */
function stampOf(
  submittedAt: string | null | undefined,
  decidedAt: string | null | undefined,
  state: TimesheetQueueEntry['period']['state'],
): string {
  const at = state === 'SUBMITTED' ? submittedAt : decidedAt;
  return at ? ` ${formatDate(at)}` : '';
}
