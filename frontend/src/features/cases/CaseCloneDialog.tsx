import {
  Alert,
  Box,
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
import { ApiError } from '../../api/client';
import { useCloneCase, useSuites } from '../../api/queries';
import type { TestCase } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { copyOf } from '../../lib/clone';
import { describeError } from '../../lib/describeError';

/**
 * Cloning a test case (§129 phase 3) — the first consumer of `POST /cases/{caseId}/clone`, which
 * shipped in phase 1 with `curl` as its only caller.
 *
 * ## The two fields, and why there are only two
 *
 * **A clone is a create.** The server reads the source, builds the input `POST /cases` already
 * takes, and runs it through the same validation, the same key allocator and the same version
 * writer — so everything a form could offer to change here is either already copied or is the
 * editor's job a moment later. What the caller genuinely has to answer is *what is it called* and
 * *where does it go*, and the contract's input is exactly those two.
 *
 * ## The name is suggested here because the server refuses to invent one
 *
 * `title` is required on the wire. `copyOf` fills the field with `Copy of <title>` **in front of a
 * person who can edit it before it becomes a row** — which is a different object from a server-side
 * default: two rows nobody can tell apart in a list is what defaulting ships, and the length limit
 * would have to be decided by whoever wrote the prefix.
 *
 * ## The automation note is the one thing on this dialog that is not obvious
 *
 * `automationKey` is unique per company (§6c), so it is never copied — and dropping it **forces the
 * status down**, because `CaseEdit.validate` refuses `AUTOMATED` without a key. A clone of an
 * automated case is therefore created as `TO_BE_AUTOMATED`. That is the accurate state rather than a
 * compromise, and it is stated *before* the clone is made: a user who reads "Automated" on the
 * source and "To be automated" on the copy, with nothing having said so, reads it as a defect.
 *
 * The note renders **only for a source that is `AUTOMATED`** — for every other status nothing is
 * downgraded, and a permanent sentence about automation on a manual case is noise that teaches
 * readers to skip the box the one time it matters.
 */

/** Mirrors `TestCaseCloneInput.title.maxLength`, itself `CaseEdit.MAX_TITLE`. */
const MAX_TITLE = 300;

const CloneSchema = z.object({
  title: z
    .string()
    .min(1, 'Enter a title for the copy.')
    .max(MAX_TITLE, `Keep the title under ${MAX_TITLE} characters.`),
  /** `''` means "leave it where the source is", which is what the server does with an absent field. */
  suiteId: z.string(),
});

type CloneFormInput = z.infer<typeof CloneSchema>;

const TestId = 'CaseDetail.Clone';

type FormField = 'title' | 'suiteId';

const FIELDS: readonly FormField[] = ['title', 'suiteId'];

/**
 * Maps a refused clone onto the form.
 *
 * Both of this endpoint's field errors have a control, so nothing should reach the banner from a
 * 400 — but the fallthrough stays, because a 403 (the gate below is an affordance over a snapshot,
 * and a grant can narrow between load and click) carries no `errors` map at all.
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

export function CaseCloneDialog({
  projectId,
  testCase,
  open,
  onClose,
  onCloned,
}: {
  projectId: string;
  testCase: TestCase;
  open: boolean;
  onClose: () => void;
  onCloned: (created: TestCase) => void;
}) {
  const clone = useCloneCase(projectId, testCase.id);
  const suites = useSuites(projectId);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<CloneFormInput>({
    resolver: zodResolver(CloneSchema),
    defaultValues: { title: copyOf(testCase.title, MAX_TITLE), suiteId: '' },
    mode: 'onBlur',
  });

  // Focus only, no `reset` — the dialog is conditionally mounted, so it starts fresh each time it
  // opens, and resetting here is what wiped the suite dialog's server errors in §30.
  useEffect(() => {
    if (open) setFocus('title');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await clone.mutateAsync({
        title: values.title.trim(),
        // Omitted rather than sent null when the field is untouched. The two mean the same thing to
        // this endpoint, and omitting keeps an ordinary clone's body identical to the smallest
        // request the contract describes.
        ...(values.suiteId ? { suiteId: values.suiteId } : {}),
      });
      onCloned(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  const sourceFolder = testCase.suiteName ?? 'Not filed';

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={clone.isPending}>
        <DialogTitle>Clone this test case</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <DialogContentText variant="body2">
              <Identifier>{testCase.key}</Identifier> {testCase.title}
            </DialogContentText>

            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}

            <TextField
              label="Title"
              required
              autoComplete="off"
              error={!!errors.title}
              helperText={
                errors.title?.message ??
                'Suggested from the source. Two cases a reader cannot tell apart are worse than one.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Title`, maxLength: MAX_TITLE } }}
              {...register('title')}
            />

            <TextField
              label="Folder"
              select
              defaultValue=""
              error={!!errors.suiteId}
              helperText={errors.suiteId?.message ?? 'Where the copy is filed.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Suite` } }}
              {...register('suiteId')}
            >
              {/*
                An explicit row rather than a blank first option, naming the folder the copy lands
                in — `RunCreateDialog`'s reason: a blank option reads as a field somebody forgot to
                fill in, and here it would hide the whole default.
              */}
              <MenuItem value="" data-testid={`${TestId}.Suite.Source`}>
                <Box component="span" sx={{ color: 'text.secondary' }}>
                  Same as the source — {sourceFolder}
                </Box>
              </MenuItem>
              {(suites.data ?? []).map((suite) => (
                <MenuItem key={suite.id} value={suite.id} data-testid={`${TestId}.Suite.${suite.key}`}>
                  {suite.key} · {suite.name}
                </MenuItem>
              ))}
            </TextField>

            {testCase.automationStatus === 'AUTOMATED' && (
              <Alert severity="info" data-testid={`${TestId}.AutomationNote`}>
                The automation key is not copied — it identifies one case to CI, and two cases
                claiming it would make an ingested result match both. The copy is created as{' '}
                <strong>To be automated</strong>; give it its own key once the test exists.
              </Alert>
            )}

            <DialogContentText variant="caption">
              The steps, classification, conditions, tags and attached files come across. The review
              state starts at draft, and the source keeps its version and execution history.
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
            {clone.isPending ? 'Cloning…' : 'Clone case'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
