import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { DocPageSummary } from '../../api/types';
import { ApiError } from '../../api/client';
import { Identifier } from '../../components/Identifier';
import { useCreateDocPage, useDeleteDocPage } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Creating and deleting a documentation page.
 *
 * Two dialogs and not three: unlike a suite, a page's *content* is not a dialog — retitling it
 * happens in the editor beside the blocks, because a specification's title is the first line of
 * its content rather than a label on a folder. So the create dialog is the only form here, and it
 * asks for the two things a page cannot be created without.
 *
 * The delete is not a form at all. It follows `CaseDeleteDialog`: it **names the page** rather
 * than asking "are you sure", and the confirm button is verb-labeled and danger-styled so the
 * title and the button agree.
 */

/** Mirrors the server's own rule, so the message lands on the field without a round trip. */
const MAX_TITLE = 200;

const DocPageSchema = z.object({
  title: z
    .string()
    .min(1, 'Enter a title.')
    .max(MAX_TITLE, `Keep the title under ${MAX_TITLE} characters.`),
  parentId: z.string(),
});

type DocPageFormInput = z.infer<typeof DocPageSchema>;

const TestIdPrefix = 'Docs.Dialog';

/**
 * Maps a rejected write onto the form.
 *
 * The server answers RFC-7807 with an `errors` map keyed by the *input schema's* field names, so a
 * `parentId` error lands on the parent picker. A 403 from a Viewer, a 409, or a network failure
 * has no field to land on and goes to the form-level alert through `describeError`, the one place
 * an error becomes user-facing copy.
 */
function applyServerError(
  error: unknown,
  setError: (field: 'title' | 'parentId' | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if (field === 'title' || field === 'parentId') {
        setError(field, messages.join(' '));
        landed = true;
      }
    }
    // A `blocks[…]` key cannot land here — this form has no blocks — and dropping it silently
    // would leave a rejected save with nothing shown at all.
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function CreateDocPageDialog({
  open,
  onClose,
  projectId,
  pages,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  /** The tree, for the parent picker. Titles, never ids — `ui-rules`' names-not-ids rule. */
  pages: DocPageSummary[];
  onCreated: (pageId: string) => void;
}) {
  const create = useCreateDocPage(projectId);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<DocPageFormInput>({
    resolver: zodResolver(DocPageSchema),
    defaultValues: { title: '', parentId: '' },
    mode: 'onBlur',
  });

  /*
    Focus only — no reset. `SuiteDialogs` records why at length: a `reset(defaults)` keyed on an
    object literal re-runs on every render, so a failed submit's field errors are wiped before
    anyone sees them. The dialog is conditionally rendered, so it mounts fresh and
    `defaultValues` already carries the right values.
  */
  useEffect(() => {
    if (open) setFocus('title');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const page = await create.mutateAsync({
        title: values.title,
        // '' is the sentinel for "no parent" in a `<select>`; the wire wants null. Sending ''
        // would be a uuid the server cannot parse, reported as a validation failure on a field
        // the user never filled in.
        parentId: values.parentId === '' ? null : values.parentId,
      });
      onCreated(page.id);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="sm"
      data-testid={`${TestIdPrefix}.Create`}
    >
      <form onSubmit={submit} noValidate aria-busy={create.isPending}>
        <DialogTitle>New page</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
                {errors.root.message}
              </Alert>
            )}
            <TextField
              {...register('title')}
              label="Title"
              fullWidth
              required
              error={!!errors.title}
              helperText={errors.title?.message}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Title`, maxLength: MAX_TITLE } }}
            />
            <TextField
              {...register('parentId')}
              select
              label="Parent page"
              fullWidth
              defaultValue=""
              error={!!errors.parentId}
              helperText={errors.parentId?.message ?? 'Leave empty for a top-level page.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Parent` } }}
            >
              <MenuItem value="">No parent</MenuItem>
              {pages.map((page) => (
                <MenuItem key={page.id} value={page.id}>
                  {page.title}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestIdPrefix}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={create.isPending}
            data-testid={`${TestIdPrefix}.Submit`}
          >
            {create.isPending ? 'Creating…' : 'Create page'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/**
 * The delete confirmation.
 *
 * The 409 it can receive is the interesting one and it is rendered **in place**: "3 links point at
 * content you are removing… archive the page instead". That message names an action the UI
 * genuinely offers — `ui-rules` calls an error naming an action the UI does not offer a design
 * bug, and Archive is right there in the page's own header.
 */
export function DeleteDocPageDialog({
  open,
  onClose,
  projectId,
  page,
  onDeleted,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  page: DocPageSummary;
  onDeleted: () => void;
}) {
  const remove = useDeleteDocPage(projectId);

  const confirm = async () => {
    try {
      await remove.mutateAsync(page.id);
      onDeleted();
      onClose();
    } catch {
      // Rendered below from the mutation's own error, so the dialog stays open on the page it is
      // about rather than closing over a refusal the user never read.
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="sm"
      data-testid={`${TestIdPrefix}.Delete`}
    >
      <DialogTitle>Delete “{page.title}”?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          {remove.error && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Delete.Error`}>
              {describeError(remove.error).body}
            </Alert>
          )}
          <DialogContentText>
            <Identifier variant="muted">{page.key}</Identifier> and everything written on it are
            removed from the space. Past links to its blocks are not rewritten, which is why a page
            anything still points at cannot be deleted — archive it instead.
          </DialogContentText>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestIdPrefix}.Delete.Cancel`}>
          Cancel
        </Button>
        <Button
          onClick={confirm}
          color="error"
          variant="contained"
          disabled={remove.isPending}
          data-testid={`${TestIdPrefix}.Delete.Submit`}
        >
          {remove.isPending ? 'Deleting…' : 'Delete page'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
