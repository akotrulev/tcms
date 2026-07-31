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
import type { Suite } from '../../api/types';
import { ApiError } from '../../api/client';
import { Identifier } from '../../components/Identifier';
import { useCreateSuite, useDeleteSuite, useRenameSuite } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Creating, renaming and deleting a folder — the tree's write affordances.
 *
 * Until these existed, a freshly provisioned project had nowhere to put a case: nothing seeds a
 * folder, deliberately, and a case requires a suite. `useCreateSuite` had been in the data module
 * since Phase 2 with **no consumer at all**, which is the same dead-hook trap the backend slices
 * kept finding — a hook and an endpoint that only ever met in a type signature.
 *
 * Three dialogs rather than one: a create and a rename genuinely are the same form, and a delete
 * is not a form at all. Merging the last one in would give a destructive confirmation a text field
 * and a Save button.
 */

/** Mirrors the server's own rule, so the message lands on the field without a round trip. */
const MAX_NAME = 150;

const SuiteSchema = z.object({
  name: z.string().min(1, 'Enter a name.').max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  description: z.string().max(2000).optional(),
});

type SuiteFormInput = z.infer<typeof SuiteSchema>;

const TestIdPrefix = 'Suites.Dialog';

/**
 * Maps a rejected write onto the form.
 *
 * The server answers RFC-7807 with an `errors` map keyed by the *input schema's* field names, so a
 * `name` error lands on the name input. Anything else — a 403 from a Viewer, a 409, a network
 * failure — has no field to land on and goes to the form-level alert through `describeError`, the
 * one place an error becomes user-facing copy.
 */
function applyServerError(
  error: unknown,
  setError: (field: 'name' | 'description' | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if (field === 'name' || field === 'description') {
        setError(field, messages.join(' '));
        landed = true;
      }
    }
    // `parentId` and `If-Match` are keyed fields with no input on this form — a move the server
    // refused, or a precondition it could not parse. Dropping them silently would leave a
    // rejected save with nothing shown at all.
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

/**
 * The create/rename form. One component because the two differ only in their title, their verb
 * and which mutation they call — two copies would be two places the name rule drifts.
 */
function SuiteFormDialog({
  open,
  onClose,
  title,
  submitLabel,
  defaults,
  onSubmit,
  isPending,
  testId,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  submitLabel: string;
  defaults: SuiteFormInput;
  onSubmit: (values: SuiteFormInput, setError: Parameters<typeof applyServerError>[1]) => Promise<void>;
  isPending: boolean;
  testId: string;
}) {
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<SuiteFormInput>({
    resolver: zodResolver(SuiteSchema),
    defaultValues: defaults,
    mode: 'onBlur',
  });

  /*
    Focus only — NO reset, and that is a fix rather than an omission.

    This effect used to call `reset(defaults)` with `defaults` in its dependency array, and
    `defaults` is an object literal built by the caller on every render. So a failed submit set the
    field errors, React re-rendered, the identity changed, the effect re-ran, and `reset()` wiped
    the errors before anyone saw them: submitting an empty name focused the field and displayed
    absolutely nothing. Both gates were green — it typechecks and it lints, and it is invisible
    until you press the button.

    A reset is not needed at all: each dialog is conditionally rendered by the tree, so it mounts
    fresh every time it opens and `defaultValues` already carries the right values.
  */
  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    await onSubmit(values, (field, message) => setError(field, { type: 'server', message }));
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={testId}>
      <form onSubmit={submit} noValidate aria-busy={isPending}>
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
                {errors.root.message}
              </Alert>
            )}
            <TextField
              label="Name"
              required
              autoComplete="off"
              error={!!errors.name}
              helperText={errors.name?.message ?? ' '}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />
            <TextField
              label="Description"
              multiline
              minRows={2}
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Description` } }}
              {...register('description')}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestIdPrefix}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={isPending}
            data-testid={`${TestIdPrefix}.Submit`}
          >
            {isPending ? 'Saving…' : submitLabel}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function CreateSuiteDialog({
  open,
  onClose,
  projectId,
  parent,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  /** The folder the new one goes under, or null for a root. */
  parent: Suite | null;
  onCreated: (suite: Suite) => void;
}) {
  const create = useCreateSuite(projectId);

  return (
    <SuiteFormDialog
      open={open}
      onClose={onClose}
      title={parent ? `New suite in ${parent.name}` : 'New suite'}
      submitLabel="Create suite"
      // Keyed on the parent so opening the dialog from a different node resets the form.
      defaults={{ name: '', description: '' }}
      isPending={create.isPending}
      testId={`${TestIdPrefix}.Create`}
      onSubmit={async (values, setError) => {
        try {
          const created = await create.mutateAsync({
            name: values.name.trim(),
            description: values.description?.trim() || null,
            parentId: parent?.id ?? null,
          });
          onCreated(created);
          onClose();
        } catch (error) {
          applyServerError(error, setError);
        }
      }}
    />
  );
}

export function RenameSuiteDialog({
  open,
  onClose,
  projectId,
  suite,
  onRenamed,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  suite: Suite;
  onRenamed: (suite: Suite) => void;
}) {
  const rename = useRenameSuite(projectId, suite.id);

  return (
    <SuiteFormDialog
      open={open}
      onClose={onClose}
      title={`Rename ${suite.name}`}
      submitLabel="Save changes"
      defaults={{ name: suite.name, description: suite.description ?? '' }}
      isPending={rename.isPending}
      testId={`${TestIdPrefix}.Rename`}
      onSubmit={async (values, setError) => {
        try {
          const renamed = await rename.mutateAsync({
            name: values.name.trim(),
            description: values.description?.trim() || null,
            // parentId is deliberately NOT sent. The server accepts it only when it repeats the
            // stored parent and refuses a differing one with a field error — so sending it buys
            // nothing here and would make an ordinary rename fail the day moving is implemented
            // and this form still echoes a stale value.
            lockVersion: suite.lockVersion,
          });
          onRenamed(renamed);
          onClose();
        } catch (error) {
          applyServerError(error, setError);
        }
      }}
    />
  );
}

/**
 * The delete confirmation.
 *
 * A blocking modal is right here for the one reason blocking modals are right: the interruption
 * *is* the value. It **names the folder** rather than asking "are you sure", and its confirm
 * button is verb-labeled and danger-styled — a warning title over a neutral "OK" sends a mixed
 * signal about what is about to happen.
 *
 * The 409 the server answers when the folder is not empty is rendered **in place**, because its
 * detail names what is in the way ("This suite still holds 2 cases"). That is the actionable
 * sentence, and it belongs beside the folder it is about rather than in a toast at the corner.
 */
export function DeleteSuiteDialog({
  open,
  onClose,
  projectId,
  suite,
  onDeleted,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  suite: Suite;
  onDeleted: () => void;
}) {
  const remove = useDeleteSuite(projectId);

  // Reopening on another folder must not show the previous refusal.
  useEffect(() => {
    if (open) remove.reset();
    // `remove` is a stable mutation object; depending on it would reset on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, suite.id]);

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="xs"
      data-testid={`${TestIdPrefix}.Delete`}
    >
      <DialogTitle>Delete this suite?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <DialogContentText>
            <Identifier>{suite.key}</Identifier> {suite.name} will be removed from the tree.
          </DialogContentText>
          {remove.isError && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Delete.Error`}>
              {describeError(remove.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestIdPrefix}.Delete.Cancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          data-testid={`${TestIdPrefix}.Delete.Confirm`}
          onClick={() => {
            remove.mutate(suite.id, {
              onSuccess: () => {
                onDeleted();
                onClose();
              },
              // The failure stays on the dialog: a 409 naming what is still inside is the whole
              // point of refusing, and closing on it would hide the only useful sentence.
            });
          }}
        >
          {remove.isPending ? 'Deleting…' : 'Delete suite'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
