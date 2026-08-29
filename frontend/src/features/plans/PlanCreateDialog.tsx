import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Checkbox,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Textarea,
} from '../../components/ui';
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
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={create.isPending}>
        <DialogHeader>
          <DialogTitle>New test plan</DialogTitle>
        </DialogHeader>
        <div className="mt-4 flex flex-col gap-4">
          <div className="flex flex-col mt-2 gap-4">
            {errors.root && (
              <div data-testid={`${TestId}.Error`}>
            <Banner tone="bad"><span role="alert">
                {errors.root.message}
              </span></Banner>
          </div>
            )}
            <Field
              label="Name"
              htmlFor="name"
              error={errors.name?.message}
              help="The release or milestone this plan covers."
            >
            <Input
              id="name"
              autoComplete="off"
              required
              aria-invalid={!!errors.name}
              maxLength={MAX_NAME}
              data-testid={`${TestId}.Name`}
              {...register('name')}
            />
            </Field>
            <div className="flex gap-4">
              <Field
                label="Milestone"
                htmlFor="milestone"
                error={errors.milestoneName?.message}
                help="Optional — GA, Sprint 42."
              >
              <Input
                id="milestone"
                autoComplete="off"
                aria-invalid={!!errors.milestoneName}
                maxLength={MAX_MILESTONE_NAME}
                data-testid={`${TestId}.Milestone`}
                {...register('milestoneName')}
              />
              </Field>
              {/*
                ~~A date input renders its placeholder over the label unless the label is shrunk,
                which is MUI's documented case rather than a workaround.~~ → the label sits above
                the control now, always, so there is nothing to shrink and no overlap to avoid.
              */}
              <Field
                label="Milestone date"
                htmlFor="milestone-date"
                error={errors.milestoneDate?.message}
                help="Optional."
              >
              <Input
                id="milestone-date"
                type="date"
                aria-invalid={!!errors.milestoneDate}
                data-testid={`${TestId}.MilestoneDate`}
                {...register('milestoneDate')}
              />
              </Field>
            </div>
            <Field
              label="Description"
              htmlFor="description"
              error={errors.description?.message}
              help="Optional."
            >
            <Textarea
              id="description"
              rows={2}
              aria-invalid={!!errors.description}
              data-testid={`${TestId}.Description`}
              {...register('description')}
            />
            </Field>

            <div className="flex flex-col gap-1">
              <p className="m-0 text-sm font-medium">Scope</p>
              <span className="m-0 text-xs text-ink-2">
                A folder covers everything beneath it, now and as cases are added. You can leave
                this empty and add folders later.
              </span>
              <div
                data-testid={`${TestId}.Scope`}
                className="flex max-h-[200px] flex-col gap-1 overflow-y-auto"
              >
                {(suites.data ?? []).map((suite) => (
                  <label key={suite.id} className="flex cursor-pointer items-center gap-2">
                    <Checkbox
                      checked={scope.includes(suite.id)}
                      onCheckedChange={(next: boolean) =>
                        setScope((current) =>
                          next ? [...current, suite.id] : current.filter((id) => id !== suite.id),
                        )
                      }
                      aria-label={suite.name}
                      data-testid={`${TestId}.Scope.${suite.key}`}
                    />
                    <span className="flex items-baseline gap-2">
                      <Identifier variant="muted">{suite.key}</Identifier>
                      <span className="text-sm text-ink">{suite.name}</span>
                      <span className="text-xs text-ink-2">{plural(suite.caseCount, 'case')}</span>
                    </span>
                  </label>
                ))}
                {suites.data?.length === 0 && (
                  <p className="m-0 text-sm text-ink-2">
                    This project has no suites yet.
                  </p>
                )}
              </div>
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={create.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {create.isPending ? 'Creating…' : 'Create plan'}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
    </Dialog>
  );
}
