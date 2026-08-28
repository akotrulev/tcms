import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCloneRun, useConfigurations, useProjectMembers } from '../../api/queries';
import type { TestRun } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { copyOf } from '../../lib/clone';
import { describeError } from '../../lib/describeError';
import { plural } from '../../lib/format';

/**
 * Cloning a test run (§129 phase 3) — *"run this again against the new build"*, the highest-value
 * of the three clones and the most repeated action this product has.
 *
 * ## ~~Three fields, and one that is deliberately absent~~ → five, and both absences are closed
 *
 * ~~Name, release and build. **Configuration and assignee are not offered**, and neither absence is
 * an oversight:~~
 *
 * - ~~There is **no configuration picker anywhere in this SPA** — `RunCreateDialog` sends
 *   `configurationId: null` and lets the server use the project's default — so a select here would
 *   be the first, built over a list no hook reads.~~ → **A4: `GET /configurations` is that hook,
 *   and this is one of its two consumers.** The argument was never that changing the environment
 *   was wrong — it was that the read did not exist, and it named the slice that would close it.
 *   Omitting the field still keeps the *source's* configuration, which is why the first option
 *   says so rather than being blank.
 * - ~~**A run has no assignee to copy.** The column is on `test_execution`, one per case, so the
 *   field would assign the whole checklist to one person — a real thing to want, and not the
 *   default this dialog should quietly apply.~~ → **A4: offered, and still not applied quietly.**
 *   "A real thing to want" is exactly the case for a control; what the old note refused was a
 *   *default*, and there still is none — the field opens on "nobody", so a clone left alone
 *   produces the unassigned checklist it always did.
 *
 * ## Release and build are NOT prefilled, and that is the interesting decision
 *
 * The endpoint reads an absent release as *keep the source's*, and there is no way at all to say
 * *clear it*. So a prefilled field a user empties would be a control whose most obvious use is
 * silently ignored — `ui-rules` §3's worst form defect, a value that looks edited and is discarded.
 * Left empty with the source's value named in the helper text, the field means exactly what the
 * wire means: type to override, leave it to keep.
 *
 * ## §6g's gate is re-evaluated, and the override is never inherited
 *
 * A run started last month under a documented override clones into a **409** unless the cases are
 * approved now or the override is taken again, by this caller, on the record. That is the same
 * refusal `RunCreateDialog` handles and the same affordance answers it — disabled rather than
 * hidden for a caller without `case:approve`, so the way forward is discoverable by the person who
 * has to go and ask for it.
 */

/** Mirrors `TestRunCloneInput.name.maxLength` — `RunEdit.MAX_NAME`. */
const MAX_NAME = 200;
/** Mirrors `RunEdit.MAX_RELEASE`, which caps both the release and the build. */
const MAX_RELEASE = 100;

const CloneSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter a name for the copy.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  releaseVersion: z.string().max(MAX_RELEASE).optional(),
  buildNumber: z.string().max(MAX_RELEASE).optional(),
  // '' means "leave the field off the request", which is what the two absences meant before they
  // had controls — the source's configuration, and nobody assigned.
  configurationId: z.string(),
  assigneeId: z.string(),
  /** B7 — re-resolve the source's arms rather than re-run its case list. Off by default. */
  rescope: z.boolean(),
});

type CloneFormInput = z.infer<typeof CloneSchema>;

const TestId = 'RunDetail.Clone';

type FormField = 'name' | 'releaseVersion' | 'buildNumber' | 'configurationId' | 'assigneeId';

const FIELDS: readonly FormField[] = [
  'name',
  'releaseVersion',
  'buildNumber',
  'configurationId',
  'assigneeId',
];

/**
 * Maps a refused clone onto the form.
 *
 * ~~`configurationId` and `assigneeId` have no control here — by the decision above — so their
 * sentences go to the banner rather than nowhere~~ → both have one since A4, so both land on
 * themselves. The banner branch stays for the next field the contract grows, which is
 * `RunCreateDialog`'s rule and the reason that dialog has a test for exactly this case.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    const stray: string[] = [];
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FIELDS as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      } else {
        stray.push(messages.join(' '));
      }
    }
    if (stray.length > 0) {
      setError('root', stray.join(' '));
      return;
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

/**
 * The source's recorded request, in words (B7).
 *
 * Arm counts and never a case count: the request named suites and tags, and how many cases those
 * resolve to today is precisely what the re-ask is for. A plan-scoped run says so by name, because
 * "the plan" is what its author asked for.
 */
function describeScopeRequest(scope: TestRun['scopeRequest']): string {
  if (!scope) return 'the original scope';
  const arms: string[] = [];
  if (scope.planId) arms.push("the plan's scope");
  if (scope.suiteIds.length > 0) {
    arms.push(`${scope.suiteIds.length} ${scope.suiteIds.length === 1 ? 'suite' : 'suites'}`);
  }
  if (scope.tags.length > 0) arms.push(`tags ${scope.tags.join(', ')}`);
  return arms.length > 0 ? arms.join(' and ') : 'the original scope';
}

export function RunCloneDialog({
  projectId,
  run,
  caseCount,
  open,
  onClose,
  onCloned,
}: {
  projectId: string;
  run: TestRun;
  /** How many executions the source has — the size of the checklist the copy will get. */
  caseCount: number;
  open: boolean;
  onClose: () => void;
  onCloned: (created: TestRun) => void;
}) {
  const clone = useCloneRun(projectId, run.id);

  /*
    Whether the source has a request to re-ask (B7). A run created before `V61` or opened by an
    ingested report has none — the server refuses the flag by name there, and a control whose only
    outcome is a field error is worse than no control.

    A recorded request whose only arm is a bare case list re-resolves to that same list, so the
    checkbox is withheld there too: it would be an option that changes nothing, which is the other
    way to teach somebody to distrust a form.
  */
  const rescopable =
    !!run.scopeRequest
    && (run.scopeRequest.suiteIds.length > 0
      || run.scopeRequest.tags.length > 0
      || run.scopeRequest.planId !== null);
  const configurations = useConfigurations(projectId, open);
  const members = useProjectMembers(projectId, open);

  const {
    register,
    control,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<CloneFormInput>({
    resolver: zodResolver(CloneSchema),
    defaultValues: {
      name: copyOf(run.name, MAX_NAME),
      releaseVersion: '',
      buildNumber: '',
      configurationId: '',
      assigneeId: '',
      rescope: false,
    },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  /*
    §6g's gating refusal is the ONLY 409 this endpoint answers — every other refusal is a field
    error — so the status alone identifies it and the client holds no copy of the rule. The
    sentence itself lands in the banner through `applyServerError` (a 409 carries no `errors` map);
    this flag only decides whether the follow-up appears beside it.
  */
  const [gateRefused, setGateRefused] = useState(false);

  const cloneRun = async (values: CloneFormInput, overrideApprovalGate: boolean) => {
    setGateRefused(false);
    try {
      const created = await clone.mutateAsync({
        name: values.name.trim(),
        // Omitted, never null-when-blank: absent means "keep the source's", and an empty string
        // would be an override to nothing that the source's own validation never accepted.
        ...(values.releaseVersion?.trim() ? { releaseVersion: values.releaseVersion.trim() } : {}),
        ...(values.buildNumber?.trim() ? { buildNumber: values.buildNumber.trim() } : {}),
        // Same omitted-not-null rule for both of A4's fields, and it means different things on
        // each: an absent `configurationId` keeps the SOURCE's environment, an absent `assigneeId`
        // assigns nobody. The contract states both, and the two helper texts say them out loud
        // because a reader cannot tell one default from the other by looking at the form.
        ...(values.configurationId ? { configurationId: values.configurationId } : {}),
        ...(values.assigneeId ? { assigneeId: values.assigneeId } : {}),
        // Absent unless the override was taken, or every cloned run in the product claims an
        // override nobody made.
        ...(overrideApprovalGate ? { overrideApprovalGate: true } : {}),
        // Absent unless asked for, like every other override here: `false` on the wire would be a
        // client stating a default it does not own.
        ...(values.rescope ? { rescope: true } : {}),
      });
      onCloned(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
      // Not re-offered after an override that was itself refused: the caller lacks `case:approve`
      // and a button producing the same refusal twice is an affordance the product does not have.
      setGateRefused(!overrideApprovalGate && error instanceof ApiError && error.status === 409);
    }
  };

  const submit = handleSubmit((values) => cloneRun(values, false));
  /* Re-validated rather than replayed, so an edit made after the refusal is the run that gets made. */
  const submitWithOverride = handleSubmit((values) => cloneRun(values, true));

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={clone.isPending}>
        <DialogTitle>Clone this test run</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <DialogContentText variant="body2">
              <Identifier>{run.key}</Identifier> {run.name}
            </DialogContentText>

            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
                {gateRefused && (
                  <PermissionGate projectId={projectId} permission="case:approve">
                    {({ allowed }) => (
                      <Button
                        size="small"
                        color="warning"
                        sx={{ mt: 1, display: 'block' }}
                        disabled={!allowed || clone.isPending}
                        data-testid={`${TestId}.OverrideGate`}
                        onClick={() => void submitWithOverride()}
                      >
                        Clone it anyway
                      </Button>
                    )}
                  </PermissionGate>
                )}
              </Alert>
            )}

            <TextField
              label="Name"
              required
              autoComplete="off"
              error={!!errors.name}
              helperText={errors.name?.message ?? 'Suggested from the source — usually the new build.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />

            <Stack direction="row" spacing={2}>
              <TextField
                label="Release"
                autoComplete="off"
                fullWidth
                error={!!errors.releaseVersion}
                helperText={
                  errors.releaseVersion?.message ??
                  (run.releaseVersion
                    ? `Leave empty to keep ${run.releaseVersion}.`
                    : 'The source names none.')
                }
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Release`, maxLength: MAX_RELEASE },
                }}
                {...register('releaseVersion')}
              />
              <TextField
                label="Build"
                autoComplete="off"
                fullWidth
                error={!!errors.buildNumber}
                helperText={
                  errors.buildNumber?.message ??
                  (run.buildNumber
                    ? `Leave empty to keep ${run.buildNumber}.`
                    : 'The source names none.')
                }
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Build`, maxLength: MAX_RELEASE },
                }}
                {...register('buildNumber')}
              />
            </Stack>

            {/*
              A4's two, and their defaults point in OPPOSITE directions — which is why each says
              so on itself rather than sharing a line. Leaving the configuration alone keeps the
              source's; leaving the assignee alone gives the copy to nobody, because a run has no
              assignee for the clone to inherit (the column is per execution).
            */}
            <Stack direction="row" spacing={2}>
              <TextField
                label="Configuration"
                select
                fullWidth
                defaultValue=""
                error={!!errors.configurationId}
                helperText={
                  errors.configurationId?.message ?? `Leave as-is to keep ${run.configuration.name}.`
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Configuration` } }}
                {...register('configurationId')}
              >
                <MenuItem value="" data-testid={`${TestId}.Configuration.Keep`}>
                  <Box component="span" sx={{ color: 'text.secondary' }}>
                    Keep {run.configuration.name}
                  </Box>
                </MenuItem>
                {(configurations.data ?? [])
                  // The source's own row is already the first option, under the label that says
                  // what leaving it does — offering it twice would be two controls' worth of
                  // choice for one outcome.
                  .filter((configuration) => configuration.id !== run.configuration.id)
                  .map((configuration) => (
                    <MenuItem
                      key={configuration.id}
                      value={configuration.id}
                      data-testid={`${TestId}.Configuration.${configuration.id}`}
                    >
                      {configuration.name}
                    </MenuItem>
                  ))}
              </TextField>
              <TextField
                label="Assign to"
                select
                fullWidth
                defaultValue=""
                error={!!errors.assigneeId}
                helperText={
                  errors.assigneeId?.message ??
                  'Optional — gives every case in the copy to one person.'
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Assignee` } }}
                {...register('assigneeId')}
              >
                <MenuItem value="" data-testid={`${TestId}.Assignee.None`}>
                  <Box component="span" sx={{ color: 'text.secondary' }}>
                    Nobody — the team picks it up
                  </Box>
                </MenuItem>
                {(members.data ?? []).map((member) => (
                  <MenuItem
                    key={member.id}
                    value={member.id}
                    data-testid={`${TestId}.Assignee.${member.id}`}
                  >
                    {member.displayName}
                  </MenuItem>
                ))}
              </TextField>
            </Stack>

            {/*
              The frozen-scope sentence. A reader expects "the same suites" and gets "the same
              cases": ~~a run stores no scope~~ → since B7 it stores the REQUEST, and the
              executions are still the checklist — §99's arms are resolved once, at creation, and
              nothing re-resolves them. So the default clone carries the case set as it was
              resolved then, and cases added to those suites since are not picked up. That is the
              clone-versus-template distinction, and it is the one thing on this screen somebody
              would otherwise learn from a short checklist.
            */}
            <DialogContentText variant="caption">
              The copy covers the same {plural(caseCount, 'case')} as this run, every one
              untested. Results, evidence, defect links and the run’s own timings stay with{' '}
              <Identifier variant="muted">{run.key}</Identifier>. Cases added to those folders since
              this run started are not picked up — a run records the checklist it was given.
            </DialogContentText>

            {/*
              B7 — the other intention, offered where the sentence above states its absence.

              "Run the smoke suite again" and "re-run Friday's pass" are different requests, and
              until the arms were recorded only the second was expressible: the first meant leaving
              this dialog and building a run by hand, losing the link to the source. The checkbox is
              off by default because the default is the one that cannot surprise anybody — a set
              that quietly grew would make two runs with one name cover different work.

              Rendered only when the source HAS a recorded request. A run created before `V61`, or
              by an ingested report, has none and the server refuses the flag by name; offering a
              control whose only outcome is a field error is worse than not offering it.
            */}
            {rescopable && (
              <Controller
                control={control}
                name="rescope"
                render={({ field }) => (
                  <FormControlLabel
                    control={
                      <Checkbox
                        checked={field.value}
                        onChange={(event) => field.onChange(event.target.checked)}
                        data-testid={`${TestId}.Rescope`}
                      />
                    }
                    label={
                      <Stack sx={{ gap: 0.25 }}>
                        <Typography variant="body2">
                          Re-resolve the original scope instead
                        </Typography>
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          Runs {describeScopeRequest(run.scopeRequest)} again as they stand now, so
                          cases added since are included. The copy may be longer or shorter than
                          this run.
                        </Typography>
                      </Stack>
                    }
                  />
                )}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={clone.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {clone.isPending ? 'Cloning…' : 'Clone run'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
