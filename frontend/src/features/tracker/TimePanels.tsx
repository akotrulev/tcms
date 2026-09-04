import { Link as RouterLink } from 'react-router-dom';
import { useEpicTimeRollup, useIssueTimeRollup } from '../../api/queries';
import type { EpicTimeRollup, TimeRollup } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { formatHours } from '../../lib/format';

/**
 * `story.html`'s Time panel and `epic.html`'s "Estimate vs actual" — 016 M6's two issue surfaces.
 *
 * ## No figure here is a bare "hours" (016 D-d)
 *
 * The rollup carries `approvedHours` and `pendingHours` as two numbers and **nothing in the
 * product sums them.** They answer different questions: approved hours are a fact somebody signed
 * for, pending hours are a claim nobody has checked. `epic.html` labels its whole panel *"approved
 * hours only"* and `story.html` writes `14h (approved)` into the Details list, and both are
 * guarding the same ambiguity — a single "Logged 14h" is the number a reader cannot act on,
 * because they cannot tell whether questioning it is still possible.
 *
 * So every figure on these two panels says which of the two it is, and the story panel renders
 * pending as its own row rather than folding it into the meter.
 *
 * ## Each panel is its own region
 *
 * The rollup is an **optional** read on a page whose subject is the issue: the story renders
 * whether or not anybody has logged against it. Both hooks carry `meta.handled` for that reason,
 * and both panels sit in their own {@link DataRegion} — a failed rollup empties one panel and
 * leaves the description, the sub-tasks and the transition menu exactly where they were.
 *
 * `tcmsFaults.only('/time/')` plus `status(500)` is what makes that reachable in dev.
 *
 * ## Absent when the project does not track time (016 D-g)
 *
 * Both take `enabled`, threaded from `ProjectSettings.trackTime`, and the caller renders nothing
 * at all when it is off — not an empty panel. Off is a statement that this project does not work
 * this way, and a panel reading `— h` says the opposite. It hides a screen and **deletes nothing**:
 * the entries, their approvals and the rollup rows all survive, and turning it back on shows them.
 */

const TEST_ID = 'Issue.Time';

/**
 * The story rail's Time panel — estimate, approved, pending, and the way to log against it.
 *
 * The meter draws **approved against estimate**, which is the mockup's 14-of-16. Pending is
 * deliberately not drawn into it: a bar that filled up with hours nobody has approved would be
 * this file's own rule broken in the one place a reader takes a number without reading its label.
 * An issue with no estimate gets no meter — there is nothing to be a fraction of, and a bar
 * against zero would read as 100% done on the first hour logged.
 */
export function StoryTimePanel({
  issueKey,
  enabled = true,
}: {
  issueKey: string;
  enabled?: boolean;
}) {
  const rollup = useIssueTimeRollup(issueKey, enabled);

  if (!enabled) return null;

  return (
    <Panel title="Time" testId={TEST_ID}>
      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...rollup, refetch: () => void rollup.refetch() }}
      >
        {(data) => <StoryTimeBody rollup={data} issueKey={issueKey} />}
      </DataRegion>
    </Panel>
  );
}

function StoryTimeBody({ rollup, issueKey }: { rollup: TimeRollup; issueKey: string }) {
  const { estimateHours, approvedHours, pendingHours } = rollup;

  return (
    <div className="flex flex-col gap-2.5">
      <Figure label="Estimate" value={formatHours(estimateHours)} testId={`${TEST_ID}.Estimate`} />
      <Figure
        label="Logged (approved)"
        value={formatHours(approvedHours)}
        testId={`${TEST_ID}.Approved`}
      />
      {/*
        Rendered only when there is some, and that is not a tidiness choice: a `Pending 0h` row on
        every story in the product would train the reader to skip the line that matters on the one
        story where somebody is waiting for an approver.
      */}
      {pendingHours > 0 && (
        <Figure
          label="Pending approval"
          value={formatHours(pendingHours)}
          testId={`${TEST_ID}.Pending`}
        />
      )}

      {estimateHours != null && estimateHours > 0 && (
        <Meter fraction={approvedHours / estimateHours} testId={`${TEST_ID}.Meter`} />
      )}

      <RouterLink
        to="/timesheets"
        data-testid={`${TEST_ID}.Log`}
        className="self-start rounded-[6px] border border-line px-2.5 py-1 text-[12px] text-ink-2 no-underline hover:border-line-strong hover:text-ink"
      >
        {`Log time against ${issueKey}`}
      </RouterLink>

      <p className="m-0 text-[11px] leading-[1.5] text-ink-4" data-testid={`${TEST_ID}.Money`}>
        You never see what your hour costs or bills — nobody does, from this screen.
      </p>
    </div>
  );
}

/**
 * `epic.html`'s "Estimate vs actual" — a row per counted child, with the drift and the totals.
 *
 * **Drift is `approved − estimate`**, positive in the danger token and negative in the success
 * one, exactly as the mockup draws it: under an estimate is the good direction. A child nobody
 * estimated has **no drift at all** rather than a drift equal to its hours — the server does not
 * call it over-estimate for the same reason, and a `+5h` beside a blank estimate is a comparison
 * against a number that was never made.
 *
 * The footer's two totals are the server's, not summed here from the rows: the epic's own hours
 * count toward them (somebody who logs an afternoon against the epic itself has worked those
 * hours) and no row on this table carries them, so a client-side sum would be quietly smaller than
 * the timesheets it came from.
 */
export function EpicTimePanel({
  epicKey,
  enabled = true,
}: {
  epicKey: string;
  enabled?: boolean;
}) {
  const rollup = useEpicTimeRollup(epicKey, enabled);

  if (!enabled) return null;

  return (
    <Panel
      title="Estimate vs actual"
      testId={`${TEST_ID}.Epic`}
      actions={<span className="text-[11.5px] text-ink-4">approved hours only</span>}
    >
      <DataRegion
        testId={`${TEST_ID}.Epic.Region`}
        state={{ ...rollup, refetch: () => void rollup.refetch() }}
        isEmpty={(data) => data.children.length === 0}
        empty={{
          kind: 'no-records',
          title: 'Nothing to compare yet',
          body:
            'This table lines an estimate up against the hours somebody approved against it. '
            + 'An epic with no counted children has neither.',
        }}
      >
        {(data) => <EpicTimeTable rollup={data} />}
      </DataRegion>
    </Panel>
  );
}

function EpicTimeTable({ rollup }: { rollup: EpicTimeRollup }) {
  return (
    <>
      <ScrollX>
        <Table>
          <TableHead>
            <TableRow>
              <TableHeaderCell>Story</TableHeaderCell>
              <TableHeaderCell align="right">Estimate</TableHeaderCell>
              <TableHeaderCell align="right">Logged</TableHeaderCell>
              <TableHeaderCell align="right">Drift</TableHeaderCell>
              <TableHeaderCell>&nbsp;</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rollup.children.map((child) => (
              <TableRow
                key={child.issueKey}
                data-testid={`${TEST_ID}.Epic.Row`}
                className={
                  child.overEstimate
                    ? 'bg-[color-mix(in_srgb,var(--color-failed)_8%,transparent)]'
                    : undefined
                }
              >
                <TableCell variant="title">
                  <span className="flex min-w-0 items-center gap-2">
                    <RouterLink
                      to={`/issues/${encodeURIComponent(child.issueKey)}`}
                      className="text-inherit no-underline hover:text-steel hover:underline"
                    >
                      <Identifier variant="muted">{child.issueKey}</Identifier>
                    </RouterLink>
                    <span className="min-w-0 truncate">{child.title}</span>
                  </span>
                </TableCell>
                <TableCell align="right">
                  <Identifier variant="muted">{formatHours(child.estimateHours)}</Identifier>
                </TableCell>
                <TableCell align="right">
                  <Identifier variant="muted">{formatHours(child.approvedHours)}</Identifier>
                </TableCell>
                <TableCell align="right">
                  <Drift
                    estimateHours={child.estimateHours}
                    approvedHours={child.approvedHours}
                    testId={`${TEST_ID}.Epic.Drift`}
                  />
                </TableCell>
                <TableCell>
                  {child.estimateHours != null && child.estimateHours > 0 ? (
                    <Meter
                      fraction={child.approvedHours / child.estimateHours}
                      testId={`${TEST_ID}.Epic.Meter`}
                    />
                  ) : (
                    <span className="text-ink-4">&mdash;</span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </ScrollX>

      <p
        className="m-0 mt-2.5 border-t border-line pt-2.5 text-[11.5px] text-ink-4"
        data-testid={`${TEST_ID}.Epic.Foot`}
      >
        Over-estimate stories are highlighted. Totals:{' '}
        <strong className="font-mono text-ink-2">{formatHours(rollup.estimateHours)}</strong>{' '}
        estimated ·{' '}
        <strong className="font-mono text-ink-2">{formatHours(rollup.approvedHours)}</strong>{' '}
        approved
        {rollup.pendingHours > 0
          && ` · ${formatHours(rollup.pendingHours)} logged and not yet approved, counted in neither`}
      </p>
    </>
  );
}

/** One `label · value` line on the story panel, so the two halves cannot drift in the class list. */
function Figure({ label, value, testId }: { label: string; value: string; testId: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-[12px] text-ink-3">{label}</span>
      <Identifier variant="muted" testId={testId}>
        {value}
      </Identifier>
    </div>
  );
}

/**
 * `approved − estimate`, or nothing at all when there is no estimate to drift from.
 *
 * The sign is written out — `+3h` / `−2h` — because a bare `3h` in a Drift column is ambiguous in
 * the direction that matters, and the colour alone cannot carry it for a reader who does not see
 * colour.
 */
function Drift({
  estimateHours,
  approvedHours,
  testId,
}: {
  estimateHours: number | null;
  approvedHours: number;
  testId: string;
}) {
  if (estimateHours == null) {
    return <span className="text-ink-4">&mdash;</span>;
  }
  const drift = approvedHours - estimateHours;
  if (drift === 0) {
    return (
      <Identifier variant="muted" testId={testId}>
        0h
      </Identifier>
    );
  }
  return (
    <span
      data-testid={testId}
      className={
        drift > 0
          ? 'font-mono text-[12px] text-[color:var(--color-failed)]'
          : 'font-mono text-[12px] text-[color:var(--color-passed)]'
      }
    >
      {`${drift > 0 ? '+' : '−'}${formatHours(Math.abs(drift))}`}
    </span>
  );
}

/**
 * Approved against estimate, capped at the bar's own width.
 *
 * **Over-estimate draws the overflow in the danger token** rather than a bar that runs off or one
 * that silently stops at full: `epic.html` splits the last row's meter into done and risk, and a
 * bar pinned at 100% would make nineteen hours against sixteen look like the row that came in
 * exactly on target.
 */
function Meter({ fraction, testId }: { fraction: number; testId: string }) {
  const within = Math.min(fraction, 1);
  const over = Math.min(Math.max(fraction - 1, 0), 1);

  return (
    <span
      aria-hidden
      data-testid={testId}
      className="flex h-[7px] w-full min-w-[80px] overflow-hidden rounded-full bg-wash-2"
    >
      <span
        className="block h-full bg-[color:var(--color-passed)]"
        style={{ width: `${within * 100}%` }}
      />
      {over > 0 && (
        <span
          className="block h-full bg-[color:var(--color-failed)]"
          style={{ width: `${over * 100}%` }}
        />
      )}
    </span>
  );
}
