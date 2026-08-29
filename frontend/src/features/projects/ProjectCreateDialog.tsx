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
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, type ChangeEvent } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateProject } from '../../api/queries';
import type { Project } from '../../api/types';
import { describeError } from '../../lib/describeError';

/**
 * Creating a project — the affordance that ends "a provisioned company plus hand-written SQL".
 *
 * Until this dialog existed, the only way to get a second project into a company was an operator
 * with a psql prompt: `provision-company` takes an *optional first* project and nothing else
 * created one. Three backend slices in a row named it as the blocker, and two of them skipped
 * their live browser pass because of it.
 *
 * ## The key is typed, and the form says so before the server does
 *
 * Every other identifier in this product is allocated (`TC-1`, `TS-2`). A project key is not: it
 * is a short label a person chooses and then reads back in URLs. The rules are mirrored here so a
 * typo is answered without a round trip — and the mirror is deliberately *only* the format. Whether
 * a key is already taken is a fact only the server has, so that message arrives from the server and
 * lands on the same input through `errors.key`.
 *
 * The field is upper-cased **as the user types**, where they can see it happen. The server refuses
 * a lower-case key rather than correcting it, for a reason worth keeping on both sides: a save that
 * quietly stores something other than what was typed is a save whose result contradicts the form —
 * and the very next screen shows the key back.
 */

/** Mirrors `ck_project_key_format` in V1 and `ProjectInput.key`'s pattern in the contract. */
const KEY_PATTERN = /^[A-Z0-9]{2,10}$/;
/** Mirrors `ProjectEdit.MAX_NAME`. */
const MAX_NAME = 200;
const MAX_DESCRIPTION = 2000;

const ProjectSchema = z.object({
  key: z
    .string()
    .min(1, 'Enter a project key.')
    .regex(KEY_PATTERN, 'Use 2–10 upper-case letters or digits, like OLB.'),
  name: z
    .string()
    .min(1, 'Enter a project name.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  description: z.string().max(MAX_DESCRIPTION).optional(),
});

type ProjectFormInput = z.infer<typeof ProjectSchema>;

const TestId = 'Projects.Create';

type FormField = 'key' | 'name' | 'description';

/**
 * Maps a rejected create onto the form.
 *
 * The server answers RFC-7807 with an `errors` map keyed by the *input schema's* field names, so
 * "another project already uses that key" lands on the key input — which is where the user's next
 * action is. A 403 has no field to land on and goes to the form-level alert through
 * `describeError`, the one place an error becomes user-facing copy.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if (field === 'key' || field === 'name' || field === 'description') {
        setError(field, messages.join(' '));
        landed = true;
      }
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function ProjectCreateDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (project: Project) => void;
}) {
  const create = useCreateProject();
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    setValue,
    formState: { errors },
  } = useForm<ProjectFormInput>({
    resolver: zodResolver(ProjectSchema),
    defaultValues: { key: '', name: '', description: '' },
    mode: 'onBlur',
  });

  /*
    Focus only — no `reset`. The dialog is conditionally rendered by the picker, so it mounts fresh
    each time it opens and `defaultValues` already carries the right values. Resetting here with a
    `defaults` object built by the caller is what wiped the suite dialog's server errors before
    anyone could read them (handoff §30): correct types, wrong runtime graph, both gates green.
  */
  useEffect(() => {
    if (open) setFocus('key');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync({
        key: values.key.trim(),
        name: values.name.trim(),
        description: values.description?.trim() || null,
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
          <DialogTitle>New project</DialogTitle>
        </DialogHeader>
        <div className="mt-4 flex flex-col gap-4">
            {errors.root && (
              <div data-testid={`${TestId}.Error`}>
            <Banner tone="bad"><span role="alert">
                {errors.root.message}
              </span></Banner>
          </div>
            )}
            <Field
              label="Key"
              htmlFor="project-key"
              error={errors.key?.message}
              help="Short, upper-case, and permanent — OLB, MOB."
            >
              <Input
                id="project-key"
                required
                autoComplete="off"
                aria-invalid={!!errors.key}
                maxLength={10}
                data-testid={`${TestId}.Key`}
                {...register('key', {
                  // Upper-cased where the user can see it, rather than silently on the server. The
                  // cursor stays put because the value is the same length either way.
                  onChange: (event: ChangeEvent<HTMLInputElement>) =>
                    setValue('key', event.target.value.toUpperCase()),
                })}
              />
            </Field>
            <Field
              label="Name"
              htmlFor="project-name"
              error={errors.name?.message}
              help="The whole product or engagement — not a feature area."
            >
              <Input
                id="project-name"
                required
                autoComplete="off"
                aria-invalid={!!errors.name}
                maxLength={MAX_NAME}
                data-testid={`${TestId}.Name`}
                {...register('name')}
              />
            </Field>
            <Field
              label="Description"
              htmlFor="project-description"
              error={errors.description?.message}
              help="Optional."
            >
              <Textarea
                id="project-description"
                rows={2}
                aria-invalid={!!errors.description}
                data-testid={`${TestId}.Description`}
                {...register('description')}
              />
            </Field>
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
            {create.isPending ? 'Creating…' : 'Create project'}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
    </Dialog>
  );
}
