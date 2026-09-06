import { useState, type ReactNode } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import {
  useCreateRunSchedule,
  useDeleteRunSchedule,
  useProject,
  useRunSchedules,
  useUpdateRunSchedule,
} from '../../api/queries';
import type { RunSchedule, RunScheduleInput } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { describeCron } from '../../lib/cron';
import { Identifier } from '../../components/Identifier';
import {
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { TabStrip } from '../../components/TabStrip';
import { TABS } from '../../app/nav';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Switch,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';

/**
 * The project's run schedules — **`V68`'s standing requests, and the screen that keeps them
 * honest** (§247).
 *
 * ## The one thing this page exists for
 *
 * A schedule that silently stops is the failure mode of scheduling anything. The poller records
 * *why* an attempt made no run in `lastFireError` — the owner lost `run:create`, the project was
 * archived, the resolved scope was empty, the approval gate refused — and **this is the only place
 * that string is ever visible**. So it is rendered as a banner on the row rather than as a tooltip
 * or a muted caption: everything else here is configuration, and that is a report.
 *
 * `nextFireAt === null` renders the sentence **"Will not fire again"** and never a blank cell. Null
 * is a real answer (an expression pinned to a date that has passed), and an empty cell reads as
 * "loading" or "soon" — both false, and both worse than the truth.
 *
 * ## Disable, don't hide (§31)
 *
 * Reading a schedule costs project visibility alone; creating, editing and deleting one costs
 * `run:create`, because a schedule is delegated run creation. A Viewer therefore sees the whole
 * list with the controls **disabled and explained**, which is the rail's rule everywhere except
 * §242's audit entries — and those were an argued exception about a screen whose subject is who
 * did what. This one's subject is a cadence.
 *
 * ## Filter state lives in the URL
 *
 * `?enabled=true` narrows the list to schedules the poller will actually ask. In the URL rather
 * than in component state, per the working agreement: a coordinator sharing "here are the paused
 * ones" must be able to send the link.
 *
 * ## The owner's name is not the run's author
 *
 * `createdByName` is the **authority** every fire is gated as — re-checked each time, so a schedule
 * outlives neither the grant nor the account. The runs it makes carry **no creator at all**. The
 * column header says "Fires as" and not "Created by" for exactly that reason: a reader who took
 * this name onto the run list would be reading a lie about who did something at 03:00.
 *
 * ## What the port took from the render, and what it did not
 *
 * `renders/01-index-tables.html` frame G is this screen, and its one structural note is that the
 * switch is the **first column** — this is the only index whose rows have an on/off.
 *
 * ~~That is deliberately not done here: the row's enabled flag is an Edit-dialog field, and
 * promoting it to a row control is a write on every click with no confirmation and no field-level
 * endpoint, so it is a behaviour change rather than a port.~~ → **superseded, 2026-08-30 (renders
 * audit 1.9).** Two of the three objections do not survive contact with what the control is. There
 * is no field-level endpoint, true, and the switch therefore PUTs the whole schedule — the same
 * total statement the Edit dialog makes, built the same way and for the same reason, so nothing is
 * dropped. "No confirmation" is the right policy for a destructive act and the wrong one here:
 * pausing a schedule is undone by the same switch in the same place, and a confirmation on a
 * reversible toggle is the dialog nobody reads. What remains true is that it is a behaviour change
 * — which is what the audit asked for, the render's caption calling this the organising idea of the
 * screen.
 *
 * The rest of the frame is applied — the paused row stays in the table at reduced weight with its
 * reason (*disable, don't hide*), and its next run is the sentence rather than a stale time.
 *
 * **The cadence is read out loud** (`lib/cron.ts`), not printed as `0 30 2 * * *`. The raw
 * expression stays as the cell's `title`, because a reader who needs to edit it needs the string.
 *
 * ~~And each row offers Delete.~~ → **superseded with the switch.** Rule 7's worked example for
 * this very screen is a paused schedule keeping its row with its date and reason; a Delete beside
 * the switch offers a destructive act as the peer of a reversible one. Deletion is still reachable,
 * from inside the Edit dialog, where it is a decision rather than a mis-click.
 *
 * This file also dropped the MUI slot-prop test-id helper, whose whole reason for existing was that
 * MUI types slot props too narrowly to accept a `data-*`. A Radix switch takes one directly.
 */
export function RunSchedulePage() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  const project = useProject(projectId);
  const schedules = useRunSchedules(projectId);

  const enabledOnly = params.get('enabled') === 'true';
  const [editing, setEditing] = useState<RunSchedule | 'new' | null>(null);

  return (
    <div>
      <PageHeader
        testId="RunSchedules.Header"
        title="Run schedules"
        documentTitle={
          project.data ? `Run schedules · ${project.data.name}` : 'Run schedules'
        }
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test runs', to: `/projects/${projectId}/runs` },
          { label: 'Schedules' },
        ]}
        subtitle="A standing request for a run — what to ask for, and when. The runs a schedule creates are ordinary runs; nobody is recorded as having created them, because nobody did. Each schedule fires with the grants of the person it is listed against, checked at the moment it fires."
        actions={
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed, reason }) => (
              <Button
                variant="primary"
                size="sm"
                disabled={!allowed}
                title={reason}
                onClick={() => setEditing('new')}
                data-testid="RunSchedules.New"
              >
                New schedule
              </Button>
            )}
          </PermissionGate>
        }
      />

      <TabStrip tabs={TABS.runs} projectId={projectId} label="Test run views" testId="Runs.Tabs" />

      <section data-testid="RunSchedules.Panel">
        <label className="mb-3 flex w-fit items-center gap-2 text-[13px] text-ink-3">
          <Switch
            checked={enabledOnly}
            aria-label="Only active schedules"
            data-testid="RunSchedules.EnabledOnly"
            onCheckedChange={(checked) => {
              // Straight into the URL, never into component state. A coordinator sharing
              // "here are the paused ones" has to be able to send the link.
              const next = new URLSearchParams(params);
              if (checked) next.set('enabled', 'true');
              else next.delete('enabled');
              setParams(next, { replace: true });
            }}
          />
          Only active schedules
        </label>

        <DataRegion
          testId="RunSchedules.Region"
          state={{ ...schedules, refetch: () => void schedules.refetch() }}
          isEmpty={(rows) => rows.filter((row) => !enabledOnly || row.enabled).length === 0}
          /*
            TWO empty states, and the distinction is §242 LG-3's lesson one screen over: "nothing is
            here" and "the filter found nothing" say different things, and only one of them is about
            something the reader can undo. So the filtered case is `no-filter-match` with a way out
            of the filter, and the unfiltered case explains what a schedule is for.
          */
          empty={
            enabledOnly
              ? {
                  kind: 'no-filter-match',
                  title: 'No active schedules',
                  body: 'Every schedule in this project is paused.',
                  onClearFilters: () => {
                    const next = new URLSearchParams(params);
                    next.delete('enabled');
                    setParams(next, { replace: true });
                  },
                }
              : {
                  kind: 'no-records',
                  title: 'No schedules yet',
                  body: 'A schedule asks for a run on a cadence, so a regression cycle happens because it is due rather than because somebody remembered.',
                }
          }
        >
          {(rows) => (
            <Table data-testid="RunSchedules.Table">
              <TableHead>
                <TableRow>
                  {/* First column, because it is the organising idea of this screen — the only
                      index whose rows have an on/off. Render G's caption says so. */}
                  <TableHeaderCell className="w-[72px]">Active</TableHeaderCell>
                  <TableHeaderCell>Name</TableHeaderCell>
                  <TableHeaderCell className="w-[230px]">Cadence</TableHeaderCell>
                  <TableHeaderCell className="w-[170px]">Next run</TableHeaderCell>
                  <TableHeaderCell className="w-[170px]">Last run</TableHeaderCell>
                  {/*
                    ~~"Owner"~~ → **"Fires as" (005 §6, item 11).** The design change asked for this
                    column to be drawn against `updatedAt` / `updatedBy` — who last changed the
                    schedule — and **the contract carries neither**: `RunSchedule` has `createdAt`,
                    `createdBy` and `createdByName` and nothing else about who touched it. So the
                    honest half of the change is applied and the other half is not invented: the
                    column says what the value IS — the authority every fire is gated as, re-checked
                    each time — rather than "owner", which reads as a person to ask and is not.
                  */}
                  <TableHeaderCell className="w-[150px]">Fires as</TableHeaderCell>
                  <TableHeaderCell align="right" className="w-[90px]">
                    <span className="sr-only">Actions</span>
                  </TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows
                  .filter((row) => !enabledOnly || row.enabled)
                  .map((row) => (
                    <ScheduleRow
                      key={row.id}
                      projectId={projectId}
                      schedule={row}
                      onEdit={() => setEditing(row)}
                    />
                  ))}
              </TableBody>
            </Table>
          )}
        </DataRegion>
      </section>

      {editing !== null && (
        <ScheduleDialog
          projectId={projectId}
          schedule={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}

function ScheduleRow({
  projectId,
  schedule,
  onEdit,
}: {
  projectId: string;
  schedule: RunSchedule;
  onEdit: () => void;
}) {
  const update = useUpdateRunSchedule(projectId, schedule.id);

  return (
    <TableRow
      data-testid="RunSchedules.Row"
      /*
        A paused schedule keeps its row at reduced weight rather than leaving the table — rule 7,
        and the render draws exactly this. Opacity rather than a colour, so the whole row dims
        together including its marks; recolouring the text alone reads as a rendering fault.
      */
      className={schedule.enabled ? undefined : 'opacity-60'}
    >
      <TableCell>
        <PermissionGate projectId={projectId} permission="run:create">
          {({ allowed, reason }) => (
            <Switch
              checked={schedule.enabled}
              disabled={!allowed || update.isPending}
              title={reason}
              aria-label={`${schedule.enabled ? 'Pause' : 'Resume'} ${schedule.name}`}
              onCheckedChange={(next) => {
                /*
                  A PUT of the WHOLE schedule, not a patch of one field — there is no field-level
                  endpoint, and the server reads an omission as a clear. Every field is restated
                  from the row exactly as the Edit dialog restates them from its form, which is the
                  rule §244 exists for: a payload that leaves out `assigneeId` does not fail to
                  save, it silently drops an assignee somebody set elsewhere.
                */
                update.mutate({
                  name: schedule.name,
                  cronExpression: schedule.cronExpression,
                  zoneId: schedule.zoneId,
                  scope: {
                    planId: schedule.scope.planId,
                    caseIds: schedule.scope.caseIds,
                    suiteIds: schedule.scope.suiteIds,
                    tags: schedule.scope.tags,
                  },
                  configurationId: schedule.configurationId,
                  assigneeId: schedule.assigneeId,
                  enabled: next,
                });
              }}
              data-testid="RunSchedules.Row.Active"
            />
          )}
        </PermissionGate>
      </TableCell>
      <TableCell
        variant="title"
        subtitle={
          !schedule.enabled || schedule.lastFireError ? (
            <>
              {!schedule.enabled && <span data-testid="RunSchedules.Row.Paused">Paused</span>}
              {/*
                The feature's honesty, and the reason it is a banner rather than a caption. This is
                the only place the poller's reason for making no run is ever visible, and a
                schedule that stopped silently is what the whole column exists to prevent.
              */}
              {schedule.lastFireError && (
                <Banner tone="warn" className="mt-1.5" data-testid="RunSchedules.Row.Error">
                  <span>{schedule.lastFireError}</span>
                </Banner>
              )}
            </>
          ) : undefined
        }
      >
        <span className="font-semibold">{schedule.name}</span>
      </TableCell>
      <TableCell>
        {/*
          The cadence in words, with the expression itself on the `title` — the render writes
          `Every day at 01:00 · Europe/Sofia`, and the raw form is what somebody about to edit it
          needs. `describeCron` hands back the expression unchanged for anything it cannot prove,
          so this cell never states a cadence the scheduler does not keep.
        */}
        <span title={schedule.cronExpression} data-testid="RunSchedules.Row.Cadence">
          {describeCron(schedule.cronExpression, schedule.zoneId)}
        </span>
      </TableCell>
      <TableCell
        className="font-mono text-[12px] tabular-nums text-ink-4"
        data-testid="RunSchedules.Row.NextFire"
      >
        {schedule.nextFireAt ? (
          formatDateTime(schedule.nextFireAt)
        ) : (
          // Never a blank cell. Null means the expression yields nothing further, and an empty
          // cell reads as "loading" or "soon" — both false.
          <span className="font-sans">Will not fire again</span>
        )}
      </TableCell>
      <TableCell className="text-[12px] text-ink-4">
        {schedule.lastRunId && schedule.lastRunKey ? (
          <Identifier>{schedule.lastRunKey}</Identifier>
        ) : (
          <span>{schedule.lastFiredAt ? formatDateTime(schedule.lastFiredAt) : 'Never'}</span>
        )}
      </TableCell>
      <TableCell className="text-[12px]">
        {/*
          The AUTHORITY every fire is gated as, never the author of the runs — those have none.
          A null name is not evidence about the id: an owner whose membership row is gone still
          owns the schedule, and the fire still runs their grant.
        */}
        {schedule.createdByName ?? 'Unknown'}
      </TableCell>
      <TableCell align="right">
        <span className="flex justify-end gap-2">
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed, reason }) => (
              <Button
                size="sm"
                disabled={!allowed}
                title={reason}
                onClick={onEdit}
                data-testid="RunSchedules.Row.Edit"
              >
                Edit
              </Button>
            )}
          </PermissionGate>
        </span>
      </TableCell>
    </TableRow>
  );
}

/**
 * The zones this form offers — a **picker, not a free-text box**.
 *
 * A mistyped zone is a `400` the person who typed it cannot diagnose, and it is the one refusal on
 * this form with no obvious remedy: `Europe/Sofiaa` looks exactly like `Europe/Sofia`. So the
 * control constrains the value rather than validating it afterwards.
 *
 * **A curated list, and deliberately not `Intl.supportedValuesOf('timeZone')`.** That is the
 * complete answer and it is not in this project's `lib` (`ES2023`), so reaching for it means either
 * widening the lib for one control or a `typeof` guard whose fallback is this list anyway. Six
 * hundred zones in a plain select is also not a better control than fourteen — a real picker with
 * search is its own slice.
 *
 * **The schedule's own value is always offered**, first, whatever it is. Without that, editing a
 * schedule set to a zone outside this list would silently move it to the first option on save,
 * which is the total-statement rule turning a curated list into data loss (`§247 SK-11` asks the
 * browser pass what it thinks of the scope control for the same family of reason).
 */
const COMMON_ZONES = [
  'UTC',
  'Europe/London',
  'Europe/Dublin',
  'Europe/Lisbon',
  'Europe/Madrid',
  'Europe/Paris',
  'Europe/Berlin',
  'Europe/Sofia',
  'Europe/Kyiv',
  'Asia/Jerusalem',
  'Asia/Kolkata',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Australia/Sydney',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'America/Sao_Paulo',
] as const;

function zoneOptions(current: string | undefined): string[] {
  const zones: string[] = [...COMMON_ZONES];
  return current && !zones.includes(current) ? [current, ...zones] : zones;
}

/** Comma-separated tags, both directions, so the form has one text control for the common scope. */
function parseTags(raw: string): string[] {
  return raw
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);
}

/**
 * One field, one column, the label above the control and its help or its refusal below.
 *
 * Written here rather than reached for: `TextField`'s `label` + `helperText` pair went with MUI,
 * this dialog is the only place in the ten index screens that needs it back, and a shared form
 * field earns its place on the second caller, not the first.
 */
function Field({
  label,
  help,
  error,
  children,
}: {
  label: string;
  help: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[12px] font-medium text-ink-2">{label}</span>
      {children}
      <span
        className={
          error ? 'text-[11.5px] text-[color:var(--color-failed)]' : 'text-[11.5px] text-ink-4'
        }
      >
        {error ?? help}
      </span>
    </label>
  );
}

function ScheduleDialog({
  projectId,
  schedule,
  onClose,
}: {
  projectId: string;
  schedule: RunSchedule | null;
  onClose: () => void;
}) {
  const create = useCreateRunSchedule(projectId);
  const update = useUpdateRunSchedule(projectId, schedule?.id ?? '');
  const remove = useDeleteRunSchedule(projectId);
  const save = schedule ? update : create;

  const [name, setName] = useState(schedule?.name ?? '');
  const [cronExpression, setCronExpression] = useState(
    schedule?.cronExpression ?? '0 0 3 * * MON-FRI',
  );
  const [zoneId, setZoneId] = useState(schedule?.zoneId ?? 'UTC');
  const [tags, setTags] = useState((schedule?.scope.tags ?? []).join(', '));
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true);
  /*
    A two-press delete rather than a second dialog on top of this one. A nested modal is the shape
    that traps focus twice and closes the wrong layer on Escape; the button restating itself is one
    element, one focus, and still an explicit second act.
  */
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  /*
    Two readings of one failure, and they are not interchangeable. `ApiError.fieldErrors` is keyed
    by the field name as the input schema spells it, so each message lands on the control that
    caused it; `describeError` is the one place an error becomes user-facing copy, and it is what
    carries a 403 or a 409 — neither of which has a field to land on.
  */
  const fieldErrors =
    save.error instanceof ApiError ? save.error.fieldErrors : null;
  const failure = save.error && !fieldErrors ? describeError(save.error) : null;

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid="RunSchedules.Dialog">
        <DialogHeader>
          <DialogTitle>{schedule ? 'Edit schedule' : 'New schedule'}</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          {failure && (
            <Banner tone="bad" data-testid="RunSchedules.Dialog.Error">
              <span>{failure.body}</span>
            </Banner>
          )}

          <Field
            label="Name"
            error={fieldErrors?.name?.[0]}
            help='Every run this makes is named "<name> — <date>", so a year of them reads as one series.'
          >
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              data-testid="RunSchedules.Dialog.Name"
            />
          </Field>

          <Field
            label="Cadence"
            error={fieldErrors?.cronExpression?.[0]}
            help='Six fields: second minute hour day-of-month month day-of-week. "0 0 3 * * MON-FRI" is 03:00 on weekdays.'
          >
            <Input
              value={cronExpression}
              onChange={(event) => setCronExpression(event.target.value)}
              className="font-mono"
              data-testid="RunSchedules.Dialog.Cron"
            />
          </Field>

          <Field
            label="Time zone"
            error={fieldErrors?.zoneId?.[0]}
            help='"Every weekday at 07:00" is not a time without one — the cadence is read in this zone.'
          >
            <select
              value={zoneId}
              onChange={(event) => setZoneId(event.target.value)}
              className="w-full rounded-[8px] border border-line bg-field px-3 py-1.5 text-[13px] text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]"
              data-testid="RunSchedules.Dialog.Zone"
            >
              {zoneOptions(schedule?.zoneId).map((zone) => (
                <option key={zone} value={zone}>
                  {zone}
                </option>
              ))}
            </select>
          </Field>

          <Field
            label="Tags to run"
            error={fieldErrors?.scope?.[0]}
            help="Comma separated. Re-resolved on every fire, so “smoke” means tonight’s smoke cases."
          >
            <Input
              value={tags}
              onChange={(event) => setTags(event.target.value)}
              data-testid="RunSchedules.Dialog.Tags"
            />
          </Field>

          <label className="flex w-fit items-center gap-2 text-[13px] text-ink-2">
            <Switch
              checked={enabled}
              aria-label="Active"
              data-testid="RunSchedules.Dialog.Enabled"
              onCheckedChange={setEnabled}
            />
            Active
          </label>
        </div>

        <DialogFooter>
          {/*
            Delete lives here rather than on the row (see this file's header). It keeps its own
            confirmation, which the row switch deliberately does not have: one is reversible by the
            control that did it and the other is not, and that difference is the whole reason they
            are not peers.
          */}
          {schedule && (
            <Button
              variant="danger"
              className="mr-auto"
              disabled={remove.isPending}
              onClick={() => {
                if (confirmingDelete) {
                  remove.mutate(schedule.id, { onSuccess: () => onClose() });
                  return;
                }
                setConfirmingDelete(true);
              }}
              data-testid="RunSchedules.Dialog.Delete"
            >
              {confirmingDelete ? 'Delete — are you sure?' : 'Delete schedule'}
            </Button>
          )}
          <Button onClick={onClose} data-testid="RunSchedules.Dialog.Cancel">
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={save.isPending}
            data-testid="RunSchedules.Dialog.Save"
            onClick={() => {
              /*
                EVERY field the schema declares, including the two this form has no control for.
                The PUT states the whole schedule and the server reads an omission as a CLEAR, so a
                field left out here would not fail to save — it would silently drop an assignee or a
                configuration somebody set elsewhere. That is the same rule §244 had to apply to two
                execution screens, and the same defect it prevents.
              */
              const input: RunScheduleInput = {
                name,
                cronExpression,
                zoneId,
                scope: {
                  planId: schedule?.scope.planId ?? null,
                  caseIds: schedule?.scope.caseIds ?? [],
                  suiteIds: schedule?.scope.suiteIds ?? [],
                  tags: parseTags(tags),
                },
                configurationId: schedule?.configurationId ?? null,
                assigneeId: schedule?.assigneeId ?? null,
                enabled,
              };
              save.mutate(input, { onSuccess: () => onClose() });
            }}
          >
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
