import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
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
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { inputTestId, selectTestId } from '../../lib/muiTestIds';

/**
 * The project's run schedules — **`V68`'s standing requests, and the screen that keeps them
 * honest** (§247).
 *
 * ## The one thing this page exists for
 *
 * A schedule that silently stops is the failure mode of scheduling anything. The poller records
 * *why* an attempt made no run in `lastFireError` — the owner lost `run:create`, the project was
 * archived, the resolved scope was empty, the approval gate refused — and **this is the only place
 * that string is ever visible**. So it is rendered as an alert on the row rather than as a tooltip
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
 * column header says "Owner" and not "Created by" for exactly that reason: a reader who took this
 * name onto the run list would be reading a lie about who did something at 03:00.
 */
export function RunSchedulePage() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  const project = useProject(projectId);
  const schedules = useRunSchedules(projectId);

  const enabledOnly = params.get('enabled') === 'true';
  const [editing, setEditing] = useState<RunSchedule | 'new' | null>(null);

  return (
    <Box>
      <PageHeader
        testId="RunSchedules.Header"
        title="Run schedules"
        documentTitle={
          project.data ? `Run schedules · ${project.data.name}` : 'Run schedules'
        }
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Runs', to: `/projects/${projectId}/runs` },
          { label: 'Schedules' },
        ]}
        subtitle="A standing request for a run — what to ask for, and when. The runs a schedule creates are ordinary runs; nobody is recorded as having created them, because nobody did. Each schedule fires with the grants of the person it is listed against, checked at the moment it fires."
        actions={
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed, reason }) => (
              <Button
                variant="contained"
                size="small"
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

      <Panel testId="RunSchedules.Panel">
        <FormControlLabel
          sx={{ mb: 1 }}
          control={
            <Switch
              size="small"
              checked={enabledOnly}
              slotProps={inputTestId('RunSchedules.EnabledOnly', 'Only active schedules')}
              onChange={(event) => {
                // Straight into the URL, never into component state. A coordinator sharing
                // "here are the paused ones" has to be able to send the link.
                const next = new URLSearchParams(params);
                if (event.target.checked) next.set('enabled', 'true');
                else next.delete('enabled');
                setParams(next, { replace: true });
              }}
            />
          }
          label={<Typography variant="body2">Only active schedules</Typography>}
        />

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
            <Table size="small" data-testid="RunSchedules.Table">
              <TableHead>
                <TableRow>
                  <TableCell>Name</TableCell>
                  <TableCell>Cadence</TableCell>
                  <TableCell>Next run</TableCell>
                  <TableCell>Last run</TableCell>
                  {/*
                    ~~"Owner"~~ → **"Fires as" (005 §6, item 11).** The design change asked for this
                    column to be drawn against `updatedAt` / `updatedBy` — who last changed the
                    schedule — and **the contract carries neither**: `RunSchedule` has `createdAt`,
                    `createdBy` and `createdByName` and nothing else about who touched it. So the
                    honest half of the change is applied and the other half is not invented: the
                    column says what the value IS — the authority every fire is gated as, re-checked
                    each time — rather than "owner", which reads as a person to ask and is not.
                  */}
                  <TableCell>Fires as</TableCell>
                  <TableCell />
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
      </Panel>

      {editing !== null && (
        <ScheduleDialog
          projectId={projectId}
          schedule={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </Box>
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
  const remove = useDeleteRunSchedule(projectId);

  return (
    <TableRow data-testid="RunSchedules.Row" hover>
      <TableCell>
        <Stack sx={{ gap: 0.5 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {schedule.name}
          </Typography>
          {!schedule.enabled && (
            <Chip size="small" label="Paused" data-testid="RunSchedules.Row.Paused" />
          )}
          {/*
            The feature's honesty, and the reason it is an Alert rather than a caption. This is the
            only place the poller's reason for making no run is ever visible, and a schedule that
            stopped silently is what the whole column exists to prevent.
          */}
          {schedule.lastFireError && (
            <Alert severity="warning" sx={{ py: 0 }} data-testid="RunSchedules.Row.Error">
              <Typography variant="caption">{schedule.lastFireError}</Typography>
            </Alert>
          )}
        </Stack>
      </TableCell>
      <TableCell>
        <Stack sx={{ gap: 0.25 }}>
          <Identifier variant="muted">{schedule.cronExpression}</Identifier>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {schedule.zoneId}
          </Typography>
        </Stack>
      </TableCell>
      <TableCell data-testid="RunSchedules.Row.NextFire">
        {schedule.nextFireAt ? (
          formatDateTime(schedule.nextFireAt)
        ) : (
          // Never a blank cell. Null means the expression yields nothing further, and an empty
          // cell reads as "loading" or "soon" — both false.
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Will not fire again
          </Typography>
        )}
      </TableCell>
      <TableCell>
        {schedule.lastRunId && schedule.lastRunKey ? (
          <Identifier>{schedule.lastRunKey}</Identifier>
        ) : (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {schedule.lastFiredAt ? formatDateTime(schedule.lastFiredAt) : 'Never'}
          </Typography>
        )}
      </TableCell>
      <TableCell>
        {/*
          The AUTHORITY every fire is gated as, never the author of the runs — those have none.
          A null name is not evidence about the id: an owner whose membership row is gone still
          owns the schedule, and the fire still runs their grant.
        */}
        <Typography variant="caption">{schedule.createdByName ?? 'Unknown'}</Typography>
      </TableCell>
      <TableCell align="right">
        <Stack direction="row" sx={{ gap: 1, justifyContent: 'flex-end' }}>
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed, reason }) => (
              <Button
                size="small"
                disabled={!allowed}
                title={reason}
                onClick={onEdit}
                data-testid="RunSchedules.Row.Edit"
              >
                Edit
              </Button>
            )}
          </PermissionGate>
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed, reason }) => (
              <Button
                size="small"
                color="error"
                disabled={!allowed || remove.isPending}
                title={reason}
                onClick={() => remove.mutate(schedule.id)}
                data-testid="RunSchedules.Row.Delete"
              >
                Delete
              </Button>
            )}
          </PermissionGate>
        </Stack>
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
 * hundred zones in a plain `Select` is also not a better control than fourteen — a real picker with
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
  const save = schedule ? update : create;

  const [name, setName] = useState(schedule?.name ?? '');
  const [cronExpression, setCronExpression] = useState(
    schedule?.cronExpression ?? '0 0 3 * * MON-FRI',
  );
  const [zoneId, setZoneId] = useState(schedule?.zoneId ?? 'UTC');
  const [tags, setTags] = useState((schedule?.scope.tags ?? []).join(', '));
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true);

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
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid="RunSchedules.Dialog">
      <DialogTitle>{schedule ? 'Edit schedule' : 'New schedule'}</DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2, mt: 1 }}>
          {failure && (
            <Alert severity="error" data-testid="RunSchedules.Dialog.Error">
              {failure.body}
            </Alert>
          )}

          <TextField
            label="Name"
            size="small"
            value={name}
            onChange={(event) => setName(event.target.value)}
            error={!!fieldErrors?.name}
            helperText={
              fieldErrors?.name?.[0] ??
              'Every run this makes is named "<name> — <date>", so a year of them reads as one series.'
            }
            slotProps={{ htmlInput: { 'data-testid': 'RunSchedules.Dialog.Name' } }}
          />

          <TextField
            label="Cadence"
            size="small"
            value={cronExpression}
            onChange={(event) => setCronExpression(event.target.value)}
            error={!!fieldErrors?.cronExpression}
            helperText={
              fieldErrors?.cronExpression?.[0] ??
              'Six fields: second minute hour day-of-month month day-of-week. "0 0 3 * * MON-FRI" is 03:00 on weekdays.'
            }
            slotProps={{ htmlInput: { 'data-testid': 'RunSchedules.Dialog.Cron' } }}
          />

          <TextField
            select
            label="Time zone"
            size="small"
            value={zoneId}
            onChange={(event) => setZoneId(event.target.value)}
            error={!!fieldErrors?.zoneId}
            helperText={
              fieldErrors?.zoneId?.[0] ??
              '"Every weekday at 07:00" is not a time without one — the cadence is read in this zone.'
            }
            slotProps={{ select: { SelectDisplayProps: selectTestId('RunSchedules.Dialog.Zone') } }}
          >
            {zoneOptions(schedule?.zoneId).map((zone) => (
              <MenuItem key={zone} value={zone}>
                {zone}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            label="Tags to run"
            size="small"
            value={tags}
            onChange={(event) => setTags(event.target.value)}
            error={!!fieldErrors?.scope}
            helperText={
              fieldErrors?.scope?.[0] ??
              'Comma separated. Re-resolved on every fire, so "smoke" means tonight’s smoke cases.'
            }
            slotProps={{ htmlInput: { 'data-testid': 'RunSchedules.Dialog.Tags' } }}
          />

          <FormControlLabel
            control={
              <Switch
                checked={enabled}
                slotProps={inputTestId('RunSchedules.Dialog.Enabled', 'Active')}
                onChange={(event) => setEnabled(event.target.checked)}
              />
            }
            label={<Typography variant="body2">Active</Typography>}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="RunSchedules.Dialog.Cancel">
          Cancel
        </Button>
        <Button
          variant="contained"
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
      </DialogActions>
    </Dialog>
  );
}
