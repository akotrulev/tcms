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
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateTeam, useUpdateTeam } from '../../api/queries';
import type { Team } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { selectTestId } from '../../lib/muiTestIds';

/**
 * Creating and editing a team — **the org chart's first write affordance.**
 *
 * `team` has had readers since the schema's first migration and got its writer in §71, so until
 * this dialog existed a company's org chart could only be populated by an operator with a psql
 * prompt. That is §34's "a provisioned company plus hand-written SQL" one screen over, and the
 * consequence reached further than the chart: `GrantRoleDialog` offers a "grant to a team" branch
 * that has always worked and had nothing to name.
 *
 * ## One dialog for both writes, because the two bodies are the same body
 *
 * The contract's create and edit take the same `TeamInput`, so a second component would be the same
 * three fields with a different mutation — and the pair would drift on the day a fourth field is
 * added. `team` decides which: absent is a create.
 *
 * ## The parent select excludes this team AND everything under it
 *
 * Not for tidiness — those are exactly the choices the server refuses, as a cycle. Offering them
 * would put the one refusal an administrator cannot reason about ("why can't Engineering go under
 * Payments?") behind a submit, when the picker can simply not offer it. The server still checks:
 * this list is built from a snapshot, and two administrators reorganising at once can make a choice
 * legal when it was rendered and a cycle by the time it arrives.
 */

/** Mirrors `TeamInput` in the contract, and `TeamEdit`'s own bounds. */
const MAX_NAME = 200;
const MAX_DESCRIPTION = 2000;

const TeamSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter a name for the team.')
    .max(MAX_NAME, `Keep the name to ${MAX_NAME} characters or fewer.`),
  description: z
    .string()
    .max(MAX_DESCRIPTION, `Keep the description to ${MAX_DESCRIPTION} characters or fewer.`),
  /** `''` is the "no parent" option — a select cannot hold `null`, so it is mapped on submit. */
  parentTeamId: z.string(),
});

type TeamFormInput = z.infer<typeof TeamSchema>;

const TestId = 'Admin.Team';

type FormField = 'name' | 'description' | 'parentTeamId';

const FORM_FIELDS: readonly FormField[] = ['name', 'description', 'parentTeamId'];

function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FORM_FIELDS as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      }
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

/** Every team that would sit beneath `rootId`, including it — the choices a move must not offer. */
function subtreeOf(rootId: string, teams: Team[]): Set<string> {
  const excluded = new Set<string>([rootId]);
  let grew = true;
  // Repeated passes rather than recursion: the list is a snapshot and may contain a cycle written
  // by something other than this API, and a recursive walk over one does not terminate.
  while (grew) {
    grew = false;
    for (const team of teams) {
      if (team.parentTeamId && excluded.has(team.parentTeamId) && !excluded.has(team.id)) {
        excluded.add(team.id);
        grew = true;
      }
    }
  }
  return excluded;
}

export function TeamDialog({
  open,
  team,
  teams,
  onClose,
}: {
  open: boolean;
  /** The team being edited; absent means create. */
  team?: Team;
  /** Every team in the company — the parent options, and what the cycle exclusion is computed over. */
  teams: Team[];
  onClose: () => void;
}) {
  const create = useCreateTeam();
  const update = useUpdateTeam();
  const pending = create.isPending || update.isPending;

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<TeamFormInput>({
    resolver: zodResolver(TeamSchema),
    defaultValues: {
      name: team?.name ?? '',
      description: team?.description ?? '',
      parentTeamId: team?.parentTeamId ?? '',
    },
    mode: 'onBlur',
  });

  // Focus only, no reset: the dialog is conditionally rendered by its opener so it mounts fresh,
  // and resetting here is what wiped the suite dialog's server errors before anyone read them (§30).
  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  const parentOptions = useMemo(() => {
    const excluded = team ? subtreeOf(team.id, teams) : new Set<string>();
    return teams
      .filter((candidate) => !excluded.has(candidate.id))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [team, teams]);

  const submit = handleSubmit(async (values) => {
    const input = {
      name: values.name.trim(),
      // `null`, never `''` — the column is nullable and the contract says so. An empty string is a
      // description that renders as a blank line rather than as no description.
      description: values.description.trim() || null,
      parentTeamId: values.parentTeamId || null,
    };
    try {
      if (team) await update.mutateAsync({ teamId: team.id, input });
      else await create.mutateAsync(input);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={pending}>
        <DialogTitle>{team ? `Edit ${team.name}` : 'New team'}</DialogTitle>
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
              helperText={
                errors.name?.message ?? 'Unique in this company. "QA" and "qa" count as the same name.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />

            <TextField
              label="Description"
              multiline
              minRows={2}
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional. What this team is responsible for.'}
              slotProps={{
                htmlInput: { 'data-testid': `${TestId}.Description`, maxLength: MAX_DESCRIPTION },
              }}
              {...register('description')}
            />

            <TextField
              select
              label="Sits under"
              defaultValue={team?.parentTeamId ?? ''}
              error={!!errors.parentTeamId}
              helperText={
                errors.parentTeamId?.message ??
                'Where this team is drawn in the chart. Nesting does not pass roles down.'
              }
              /* On the DISPLAY element, not `htmlInput`. A test id on a MUI Select's hidden
                 native input cannot be clicked to open the menu — `pointer-events: none` — so the
                 first draft of this test failed on the affordance rather than on the assertion.
                 `selectTestId` exists for exactly this and now lives in the shared layer. */
              slotProps={{ select: { SelectDisplayProps: selectTestId(`${TestId}.Parent`) } }}
              {...register('parentTeamId')}
            >
              <MenuItem value="">No parent — a top-level team</MenuItem>
              {parentOptions.map((option) => (
                <MenuItem key={option.id} value={option.id}>
                  {option.name}
                </MenuItem>
              ))}
            </TextField>

            {/*
              Said where the decision is made rather than learned later. Nesting is org-chart
              structure and never a permission edge (plan §6k) — an admin who reads the tree as an
              inheritance hierarchy will nest a team expecting it to gain the parent's access, and
              nothing about the resulting screen would correct them.
            */}
            <Alert severity="info" data-testid={`${TestId}.Hint`}>
              A team grants nothing on its own. Give it a role on a project from “Grant a role”, and
              everyone in it — including people who join later — gets that role.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={pending} data-testid={`${TestId}.Submit`}>
            {pending ? 'Saving…' : team ? 'Save changes' : 'Create team'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
