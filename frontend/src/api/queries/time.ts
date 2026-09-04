/** Timesheets: the caller's own fortnight, the approvals queue, every write, and the two rollups. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type {
  EpicTimeRollup,
  TimeEntry,
  TimeEntryInput,
  TimeRollup,
  TimesheetDecisionInput,
  TimesheetPeriod,
  TimesheetQueueEntry,
} from '../types';
import { keys } from './keys';

/**
 * The caller's own period for a date — **and the read creates the row.**
 *
 * That is the contract's "created lazily if it has no rows yet", and it is what makes this screen
 * work at all: `id` is what Submit posts to, so a person who has logged nothing would otherwise
 * have to log something before the button existed. There is therefore **no create-a-period hook**
 * anywhere in this module, and looking for one is looking for something the design removed.
 *
 * Keyed by the date rather than by the id it answers with, per `keys.time`'s own note.
 */
export function useCurrentTimesheetPeriod(on: string): UseQueryResult<TimesheetPeriod> {
  return useQuery({
    queryKey: keys.time.currentPeriod(on),
    queryFn: ({ signal }) =>
      api.get<TimesheetPeriod>(`/timesheets/periods/current${qs({ on })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: on !== '',
  });
}

/**
 * The caller's own periods, newest first — the "Previous period" panel.
 *
 * It is not decoration beside the current fortnight: a rejection *unlocks* a period and leaves its
 * note on it, so this list is the only route back to work that was sent back. The Approved KPI on
 * the timesheet is read from here too, because `TimesheetPeriod.approvedHours` is zero on anything
 * that is not APPROVED and the tile is about the fortnight before this one.
 */
export function useTimesheetPeriods(limit = 6): UseQueryResult<TimesheetPeriod[]> {
  return useQuery({
    queryKey: keys.time.periods,
    queryFn: ({ signal }) =>
      api.get<TimesheetPeriod[]>(`/timesheets/periods${qs({ limit })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
  });
}

/** One period by id — the caller's own for `time:log`, anybody else's for `time:read`. */
export function useTimesheetPeriod(periodId: string): UseQueryResult<TimesheetPeriod> {
  return useQuery({
    queryKey: keys.time.period(periodId),
    queryFn: ({ signal }) =>
      api.get<TimesheetPeriod>(`/timesheets/periods/${periodId}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: periodId !== '',
  });
}

/**
 * The approval queue for a fortnight — `time:approve`, and 403 for everybody else.
 *
 * **Not only the submitted ones.** `timesheet-approvals.html` draws an OPEN period as
 * *"Not submitted · 41.0h so far"*, so the state is served and the client decides what may be done
 * with it — which is why the screen branches on `period.state` rather than assuming every row here
 * is actionable.
 */
export function useTimesheetApprovals(on: string): UseQueryResult<TimesheetQueueEntry[]> {
  return useQuery({
    queryKey: keys.time.approvals(on),
    queryFn: ({ signal }) =>
      api.get<TimesheetQueueEntry[]>(`/timesheets/approvals${qs({ on })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: on !== '',
  });
}

/**
 * Logging an hour.
 *
 * ## No `Idempotency-Key`, and that is a decision rather than an omission
 *
 * The contract declares the header and a `200` replay arm; the server **ignores both**, because
 * `V9` gave `time_entry` no column to store a key beside the row it created. So a retry after a
 * timeout logs the hour twice, and sending the header would be this client asserting a guarantee
 * nothing upstream provides — the worse of the two failures, because the reader would then believe
 * the retry was safe.
 *
 * The guard until that column exists is on the client and is single-flight: `isPending` disables
 * the button, so a second press cannot start a second request. It does not survive a timeout, and
 * nothing here pretends it does — the honest state after one is "we do not know", which is what the
 * form says.
 */
export function useLogTime() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TimeEntryInput) =>
      api.post<TimeEntry>('/timesheets/entries', input, { timeoutMs: TIMEOUTS.interactive }),
    /*
      The 409 is the lock the whole feature turns on and the screen renders it beside the form, with
      the period's state in the sentence — strictly more useful than "couldn't save", so the global
      notification handler stays out of it. `useTransitionIssue` carries `meta.handled` for exactly
      this reason one module over.
    */
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.timeRoot });
    },
  });
}

/**
 * Amending one entry — a **whole-body replacement**, not a delta.
 *
 * `TimeEntryInput` is four fields and a note, so a partial amend of a row this small buys nothing
 * and would give the server two spellings of one write. The contract makes the same argument.
 */
export function useAmendTimeEntry() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ entryId, input }: { entryId: string; input: TimeEntryInput }) =>
      api.patch<TimeEntry>(`/timesheets/entries/${entryId}`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.timeRoot });
    },
  });
}

/**
 * Removing one entry — a hard delete, and the contract says why: an hour logged by mistake is not a
 * state worth keeping, and the rollup is recomputed from what remains.
 */
export function useDeleteTimeEntry() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (entryId: string) =>
      api.del<void>(`/timesheets/entries/${entryId}`, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.timeRoot });
    },
  });
}

/**
 * Submitting a period — **only ever the caller's own**, which is why it takes no person.
 *
 * Not a fifth permission key (016 D-e): submitting is the same act as logging, one step later, so
 * it costs `time:log`. A REJECTED period may be submitted again, which is the whole point of a
 * rejection unlocking it.
 */
export function useSubmitTimesheet() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (periodId: string) =>
      api.post<TimesheetPeriod>(`/timesheets/periods/${periodId}/submit`, undefined, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.timeRoot });
    },
  });
}

/**
 * Approve, reject with a note, or withdraw a rejection — one endpoint, one hook, three verbs.
 *
 * One hook because the server takes one body for all three and answers the same period, and
 * because splitting them would give the sweep below three places to be got wrong. Approving posts
 * the fortnight's hours onto each issue and rolls them up synchronously (016 D-h), so what goes
 * stale is not only this queue: the owner's own timesheet, their history, and every issue, story
 * and epic figure the approved hours reach. `keys.timeRoot` is the one sweep that covers all of it.
 *
 * **`REJECT` without a note is a 400 the dialog never lets happen** — the button stays disabled
 * until there is one — but the server refuses it independently, because a client-side guard is an
 * affordance and not a rule.
 */
export function useDecideTimesheet() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ periodId, input }: { periodId: string; input: TimesheetDecisionInput }) =>
      api.post<TimesheetPeriod>(`/timesheets/periods/${periodId}/decision`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.timeRoot });
    },
  });
}

/**
 * Estimate against approved **and** pending hours for one issue (016 D-d).
 *
 * `meta.handled`, because this is an optional region on a page whose subject is the issue: the
 * story renders whether or not anybody has logged against it, and a screen that turned into an
 * error because one extra read failed is the scoping rule broken in the obvious way.
 */
export function useIssueTimeRollup(issueKey: string, enabled = true): UseQueryResult<TimeRollup> {
  return useQuery({
    queryKey: keys.time.issueRollup(issueKey),
    queryFn: ({ signal }) =>
      api.get<TimeRollup>(`/time/rollup/issues/${encodeURIComponent(issueKey)}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && issueKey !== '',
    meta: { handled: true },
  });
}

/**
 * The work table's `Logged` column and its footer — one read for the rows on screen.
 *
 * **A key the caller cannot read comes back absent, not as `0h`** (the operation's own rule), so
 * the caller looks each row up by key and renders nothing where there is no answer. Zero is a
 * different claim: it says somebody looked and nobody had logged an hour.
 *
 * `meta.handled` and its own region, for {@link useIssueTimeRollup}'s reason — a table whose rows
 * arrived must not turn into an error page because an optional column failed.
 */
export function useIssueTimeRollups(
  issueKeys: readonly string[],
  enabled = true,
): UseQueryResult<TimeRollup[]> {
  return useQuery({
    queryKey: keys.time.issueRollups(issueKeys),
    queryFn: ({ signal }) =>
      api.get<TimeRollup[]>(`/time/rollup/issues${qs({ key: [...issueKeys] })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && issueKeys.length > 0,
    meta: { handled: true },
  });
}

/** The epic panel — totals, and a row per counted child. Optional for {@link useIssueTimeRollup}'s reason. */
export function useEpicTimeRollup(epicKey: string, enabled = true): UseQueryResult<EpicTimeRollup> {
  return useQuery({
    queryKey: keys.time.epicRollup(epicKey),
    queryFn: ({ signal }) =>
      api.get<EpicTimeRollup>(`/time/rollup/epics/${encodeURIComponent(epicKey)}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && epicKey !== '',
    meta: { handled: true },
  });
}
