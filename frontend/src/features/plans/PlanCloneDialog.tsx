import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useClonePlan } from '../../api/queries';
import type { TestPlan } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { copyOf } from '../../lib/clone';
import { describeError } from '../../lib/describeError';

/**
 * Cloning a test plan (§129 phase 3) — *"the same plan again, for the next release"*.
 *
 * ## One field, and the reason it is not `PlanCreateDialog` with a source id
 *
 * `POST /plans` takes six fields, deliberately: a plan is named before anybody has subdivided it.
 * A clone routed through it would drop the sections, the tag arm, both prose fields and both
 * criteria lists — most of what §97 says a plan detail consists of — and answer `201` with
 * something that reads as a faithful copy. The clone endpoint shares the create's key allocation,
 * starting status and version writer and supplies the rest, so the only thing a caller has to
 * answer is the name.
 *
 * ## What the copy notice says, and what it deliberately does not
 *
 * It names the two facts a reader would otherwise discover by opening the copy: the whole scope
 * comes across, and the status resets to draft. It does **not** list every copied field — a
 * sentence enumerating twelve of them is one nobody reads, and the ones that matter are the two
 * that surprise.
 */

/** Mirrors `TestPlanCloneInput.name.maxLength`, itself `PlanEdit.MAX_NAME`. */
const MAX_NAME = 200;

const CloneSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter a name for the copy.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
});

type CloneFormInput = z.infer<typeof CloneSchema>;

const TestId = 'PlanStats.Clone';

function applyServerError(
  error: unknown,
  setError: (field: 'name' | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors?.name) {
    setError('name', error.fieldErrors.name.join(' '));
    return;
  }
  // `name` is this endpoint's only field, so anything else — a 403 from a grant narrowed between
  // load and click, a 404 for a plan deleted in another tab — has nowhere to land but the banner.
  setError('root', describeError(error).body);
}

export function PlanCloneDialog({
  projectId,
  plan,
  open,
  onClose,
  onCloned,
}: {
  projectId: string;
  plan: TestPlan;
  open: boolean;
  onClose: () => void;
  onCloned: (created: TestPlan) => void;
}) {
  const clone = useClonePlan(projectId, plan.id);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<CloneFormInput>({
    resolver: zodResolver(CloneSchema),
    defaultValues: { name: copyOf(plan.name, MAX_NAME) },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await clone.mutateAsync({ name: values.name.trim() });
      onCloned(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={clone.isPending}>
        <DialogTitle>Clone this test plan</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <DialogContentText variant="body2">
              <Identifier>{plan.key}</Identifier> {plan.name}
            </DialogContentText>

            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}

            <TextField
              label="Name"
              required
              autoComplete="off"
              error={!!errors.name}
              helperText={
                errors.name?.message ?? 'Suggested from the source — usually the next release.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />

            <DialogContentText variant="caption">
              The whole scope comes across — the suites, cases and tags, every section, the scope
              prose and both criteria lists. The runs and their results stay with{' '}
              <Identifier variant="muted">{plan.key}</Identifier>, and the copy starts as a draft
              even if this plan is approved.
            </DialogContentText>
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
            {clone.isPending ? 'Cloning…' : 'Clone plan'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
