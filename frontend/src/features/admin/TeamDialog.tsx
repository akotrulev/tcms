import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateTeam, useUpdateTeam } from '../../api/queries';
import type { Team } from '../../api/types';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Textarea,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';

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
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <form onSubmit={submit} noValidate aria-busy={pending}>
          <DialogHeader>
            <DialogTitle>{team ? `Edit ${team.name}` : 'New team'}</DialogTitle>
          </DialogHeader>

          <div className="mt-4 flex flex-col gap-4">
            {errors.root && (
              <div data-testid={`${TestId}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{errors.root.message}</span>
                </Banner>
              </div>
            )}

            <Field
              label="Name"
              htmlFor="team-name"
              error={errors.name?.message}
              help='Unique in this company. "QA" and "qa" count as the same name.'
            >
              <Input
                id="team-name"
                required
                autoComplete="off"
                maxLength={MAX_NAME}
                data-testid={`${TestId}.Name`}
                {...register('name')}
              />
            </Field>

            <Field
              label="Description"
              htmlFor="team-description"
              error={errors.description?.message}
              help="Optional. What this team is responsible for."
            >
              <Textarea
                id="team-description"
                rows={2}
                maxLength={MAX_DESCRIPTION}
                data-testid={`${TestId}.Description`}
                {...register('description')}
              />
            </Field>

            <Field
              label="Sits under"
              htmlFor="team-parent"
              error={errors.parentTeamId?.message}
              help="Where this team is drawn in the chart. Nesting does not pass roles down."
            >
              {/*
                ~~A test id on a MUI Select's hidden native input cannot be clicked to open the
                menu — `pointer-events: none` — so `selectTestId` put it on the display element.~~
                → **superseded (007 T15).** The control *is* the native select now, so the id is on
                the element the reader operates and the options are its children.
              */}
              <NativeSelect
                id="team-parent"
                defaultValue={team?.parentTeamId ?? ''}
                data-testid={`${TestId}.Parent`}
                {...register('parentTeamId')}
              >
                <option value="">No parent — a top-level team</option>
                {parentOptions.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>

            {/*
              Said where the decision is made rather than learned later. Nesting is org-chart
              structure and never a permission edge (plan §6k) — an admin who reads the tree as an
              inheritance hierarchy will nest a team expecting it to gain the parent's access, and
              nothing about the resulting screen would correct them.
            */}
            <div data-testid={`${TestId}.Hint`}>
              <Banner tone="neutral">
                <span>
                  A team grants nothing on its own. Give it a role on a project from “Grant a role”,
                  and everyone in it — including people who join later — gets that role.
                </span>
              </Banner>
            </div>
          </div>

          <DialogFooter>
            <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.Cancel`}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" disabled={pending} data-testid={`${TestId}.Submit`}>
              {pending ? 'Saving…' : team ? 'Save changes' : 'Create team'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
