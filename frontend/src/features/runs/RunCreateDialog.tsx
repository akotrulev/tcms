import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateRun, usePlans } from '../../api/queries';
import type { TestRun } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatCount } from '../../lib/format';

/**
 * Starting a run — **the affordance that materialises a checklist**.
 *
 * Creating a run is not creating a row. The server resolves the plan's scope, resolves each case's
 * current version, and writes one execution per case pinned to it. That is the point of the whole
 * slice: until it existed, the execution screen — the product's most important one — could not be
 * reached from the UI at all, because nothing could produce a thing to record against.
 *
 * ## The plan is required by this form, and optional in the contract
 *
 * `test_run.plan_id` is nullable and the API accepts an ad-hoc run scoped by `caseIds`. That path
 * belongs to a "run selected cases" affordance on the case grid, where a selection already exists.
 * Offering it here would mean building a second case picker whose only use is to reconstruct, by
 * hand, the set a plan already names — and a run created that way is one nobody can later ask
 * "against which plan?" of.
 *
 * So the picker lists plans, and a project with none says so rather than presenting an empty
 * select the user cannot act on. The plan's own case count is shown beside it, because "how long
 * will this run be" is the question somebody is actually asking at this moment, and a plan whose
 * scope is empty is refused by the server — visible here before the click rather than after it.
 */

/** Mirrors `RunEdit.MAX_NAME`. */
const MAX_NAME = 200;
/** Mirrors `RunEdit.MAX_RELEASE`. */
const MAX_RELEASE = 100;

const RunSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter a run name.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  planId: z.string().min(1, 'Choose the plan this run executes.'),
  releaseVersion: z.string().max(MAX_RELEASE).optional(),
  buildNumber: z.string().max(MAX_RELEASE).optional(),
});

type RunFormInput = z.infer<typeof RunSchema>;

const TestId = 'Runs.Create';

type FormField = 'name' | 'planId' | 'releaseVersion' | 'buildNumber';

const FIELDS: readonly FormField[] = ['name', 'planId', 'releaseVersion', 'buildNumber'];

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
        // `caseIds`, `configurationId` and `assigneeId` have no control on this form, so their
        // sentences go to the banner rather than nowhere.
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

export function RunCreateDialog({
  projectId,
  open,
  onClose,
  onCreated,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  onCreated: (run: TestRun) => void;
}) {
  const create = useCreateRun(projectId);
  const plans = usePlans(projectId);
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<RunFormInput>({
    resolver: zodResolver(RunSchema),
    defaultValues: { name: '', planId: '', releaseVersion: '', buildNumber: '' },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  /*
    §82. §6g's gating rule refuses a run whose cases are not approved, and it is the ONLY 409 this
    endpoint answers — every other refusal here is a field error. So the status alone identifies it,
    and the client never has to hold a copy of the rule or match the server's sentence.

    The refusal itself lands in the root alert through `applyServerError` (a 409 carries no `errors`
    map), which is where the server's list of unapproved keys is read. This flag only decides
    whether the follow-up affordance appears beside it — `ui-rules`: an error naming an action the
    UI does not offer is a design bug.
  */
  const [gateRefused, setGateRefused] = useState(false);

  const createRun = async (values: RunFormInput, overrideApprovalGate: boolean) => {
    setGateRefused(false);
    try {
      const created = await create.mutateAsync({
        name: values.name.trim(),
        planId: values.planId,
        // Absent means "the project's default configuration", which every project is seeded with.
        configurationId: null,
        releaseVersion: values.releaseVersion?.trim() || null,
        buildNumber: values.buildNumber?.trim() || null,
        caseIds: [],
        assigneeId: null,
        // Omitted rather than sent false, so an ordinary create is byte-identical to what every
        // caller sent before this slice.
        ...(overrideApprovalGate ? { overrideApprovalGate: true } : {}),
      });
      onCreated(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
      // Not re-offered after an override that was itself refused: the caller lacks `case:approve`,
      // and a button that produces the same refusal twice is an affordance the product does not
      // have. The disabled control below is what tells them so the first time.
      setGateRefused(!overrideApprovalGate && error instanceof ApiError && error.status === 409);
    }
  };

  const submit = handleSubmit((values) => createRun(values, false));

  /*
    Re-validates before retrying rather than reusing the values the first attempt sent — the form is
    still open and editable, so the run created by "create it anyway" must be the one on screen now.
  */
  const submitWithOverride = handleSubmit((values) => createRun(values, true));

  const available = plans.data ?? [];

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={create.isPending}>
        <DialogTitle>New test run</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
                {/*
                  §6g's lead override, offered only where the gate actually refused. Disabled rather
                  than hidden for a caller without `case:approve` (§31), so the way out is
                  discoverable by the person who has to go and ask for it — and the gate's tooltip
                  is what names who to ask. Inside the alert because it is the answer to this
                  sentence, not a second action on the dialog.
                */}
                {gateRefused && (
                  <PermissionGate projectId={projectId} permission="case:approve">
                    {({ allowed }) => (
                      <Button
                        size="small"
                        color="warning"
                        sx={{ mt: 1, display: 'block' }}
                        disabled={!allowed || create.isPending}
                        data-testid={`${TestId}.OverrideGate`}
                        onClick={() => void submitWithOverride()}
                      >
                        Create it anyway
                      </Button>
                    )}
                  </PermissionGate>
                )}
              </Alert>
            )}
            {plans.isSuccess && available.length === 0 && (
              <Alert severity="info" data-testid={`${TestId}.NoPlans`}>
                This project has no test plans yet. A run executes a plan&apos;s scope, so create a
                plan first.
              </Alert>
            )}
            <TextField
              label="Name"
              required
              autoComplete="off"
              error={!!errors.name}
              helperText={errors.name?.message ?? 'What this pass is — “Regression 4.3.0”.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />
            <TextField
              label="Plan"
              required
              select
              defaultValue=""
              error={!!errors.planId}
              helperText={
                errors.planId?.message ??
                'Every case in the plan’s scope becomes a row to record against.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Plan` } }}
              {...register('planId')}
            >
              {available.map((plan) => (
                <MenuItem key={plan.id} value={plan.id} data-testid={`${TestId}.Plan.${plan.key}`}>
                  <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                    <Typography variant="body2">
                      {plan.key} · {plan.name}
                    </Typography>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {formatCount(plan.caseCount)} cases
                    </Typography>
                  </Stack>
                </MenuItem>
              ))}
            </TextField>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Release"
                autoComplete="off"
                fullWidth
                error={!!errors.releaseVersion}
                helperText={errors.releaseVersion?.message ?? 'Optional — 4.3.0.'}
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
                // Distinct from the release on purpose: conflating them costs the ability to ask
                // whether 4.3.0 improved between build 4470 and 4471 (V1's own column comment).
                helperText={errors.buildNumber?.message ?? 'Optional — 4471.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Build`, maxLength: MAX_RELEASE },
                }}
                {...register('buildNumber')}
              />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={create.isPending || available.length === 0}
            data-testid={`${TestId}.Submit`}
          >
            {create.isPending ? 'Starting…' : 'Start run'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
