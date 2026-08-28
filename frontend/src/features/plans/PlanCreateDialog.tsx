import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreatePlan, useSuites } from '../../api/queries';
import type { TestPlan } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { describeError } from '../../lib/describeError';
import { plural } from '../../lib/format';

/**
 * Creating a test plan — the first half of what unblocks the product's own execution loop.
 *
 * Until this dialog and its sibling `RunCreateDialog` existed, a run had to be seeded with SQL, so
 * nothing in the product's most important screen — recording a result — could be reached from the
 * UI at all. §38 and §41 both name it as the blocker.
 *
 * ## The scope is folders, not cases, and that is a product decision rather than a simplification
 *
 * A scope item may name either, and the server accepts both. This form offers **suites only**,
 * because a suite item means *the folder and everything beneath it, whenever the question is
 * asked* — so a plan scoped to folders stays correct as cases are authored into them, which is
 * what somebody building a release plan means. Picking two hundred cases individually produces a
 * plan that is wrong by the next case written and that nobody notices is wrong.
 *
 * Case-level scope belongs with a "add selected cases to a plan" affordance on the case grid,
 * where the selection already exists. It is deliberately not reproduced here as a second, worse
 * case picker.
 *
 * ## The rules mirrored here are only the ones the client can actually answer
 *
 * Length and emptiness, exactly as `ProjectCreateDialog` mirrors the key format. Whether a suite is
 * still live is a fact only the server has, and that message arrives from the server and lands on
 * the same control through `errors.suiteIds`.
 */

/** Mirrors `PlanEdit.MAX_NAME`. */
const MAX_NAME = 200;
/** Mirrors `PlanEdit.MAX_DESCRIPTION`. */
const MAX_DESCRIPTION = 2000;
/** Mirrors `PlanEdit.MAX_MILESTONE_NAME`. */
const MAX_MILESTONE_NAME = 200;

const PlanSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter a plan name.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  description: z.string().max(MAX_DESCRIPTION).optional(),
  milestoneName: z.string().max(MAX_MILESTONE_NAME).optional(),
  milestoneDate: z.string().optional(),
});

type PlanFormInput = z.infer<typeof PlanSchema>;

const TestId = 'Plans.Create';

type FormField = 'name' | 'description' | 'milestoneName' | 'milestoneDate';

const FIELDS: readonly FormField[] = ['name', 'description', 'milestoneName', 'milestoneDate'];

/**
 * Maps a rejected create onto the form.
 *
 * `suiteIds` and `caseIds` have no `TextField` to land on, so they go to the form-level alert with
 * the server's own sentence rather than being dropped — a refusal the user cannot see is the
 * failure this whole error path exists to prevent.
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

export function PlanCreateDialog({
  projectId,
  open,
  onClose,
  onCreated,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  onCreated: (plan: TestPlan) => void;
}) {
  const create = useCreatePlan(projectId);
  const suites = useSuites(projectId);
  const [scope, setScope] = useState<string[]>([]);
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<PlanFormInput>({
    resolver: zodResolver(PlanSchema),
    defaultValues: { name: '', description: '', milestoneName: '', milestoneDate: '' },
    mode: 'onBlur',
  });

  // Focus only, no `reset` — the dialog is conditionally rendered and mounts fresh each time it
  // opens, and resetting here is what wiped the suite dialog's server errors in §30.
  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync({
        name: values.name.trim(),
        description: values.description?.trim() || null,
        milestoneName: values.milestoneName?.trim() || null,
        // An empty date input submits '', which is not a date. Null is what "no milestone" means
        // in the column and in the contract.
        milestoneDate: values.milestoneDate?.trim() || null,
        suiteIds: scope,
        caseIds: [],
      });
      onCreated(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={create.isPending}>
        <DialogTitle>New test plan</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
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
              helperText={errors.name?.message ?? 'The release or milestone this plan covers.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />
            <Stack direction="row" spacing={2}>
              <TextField
                label="Milestone"
                autoComplete="off"
                fullWidth
                error={!!errors.milestoneName}
                helperText={errors.milestoneName?.message ?? 'Optional — GA, Sprint 42.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Milestone`, maxLength: MAX_MILESTONE_NAME },
                }}
                {...register('milestoneName')}
              />
              <TextField
                label="Milestone date"
                type="date"
                fullWidth
                error={!!errors.milestoneDate}
                helperText={errors.milestoneDate?.message ?? 'Optional.'}
                // A date input renders its placeholder over the label unless the label is shrunk,
                // which is MUI's documented case rather than a workaround.
                slotProps={{
                  inputLabel: { shrink: true },
                  htmlInput: { 'data-testid': `${TestId}.MilestoneDate` },
                }}
                {...register('milestoneDate')}
              />
            </Stack>
            <TextField
              label="Description"
              multiline
              minRows={2}
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Description` } }}
              {...register('description')}
            />

            <Stack spacing={0.5}>
              <Typography variant="subtitle2">Scope</Typography>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                A folder covers everything beneath it, now and as cases are added. You can leave
                this empty and add folders later.
              </Typography>
              <FormGroup data-testid={`${TestId}.Scope`} sx={{ maxHeight: 200, overflowY: 'auto' }}>
                {(suites.data ?? []).map((suite) => (
                  <FormControlLabel
                    key={suite.id}
                    control={
                      <Checkbox
                        size="small"
                        checked={scope.includes(suite.id)}
                        onChange={(event) =>
                          setScope((current) =>
                            event.target.checked
                              ? [...current, suite.id]
                              : current.filter((id) => id !== suite.id),
                          )
                        }
                        slotProps={{ input: { 'aria-label': suite.name } }}
                        data-testid={`${TestId}.Scope.${suite.key}`}
                      />
                    }
                    label={
                      <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                        <Identifier variant="muted">{suite.key}</Identifier>
                        <Typography variant="body2">{suite.name}</Typography>
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          {plural(suite.caseCount, 'case')}
                        </Typography>
                      </Stack>
                    }
                  />
                ))}
                {suites.data?.length === 0 && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    This project has no suites yet.
                  </Typography>
                )}
              </FormGroup>
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
            disabled={create.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {create.isPending ? 'Creating…' : 'Create plan'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
