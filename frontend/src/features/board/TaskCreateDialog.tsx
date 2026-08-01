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
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateWorkItem, useProjectMembers } from '../../api/queries';
import type { WorkItem } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { priorityLabels } from '../../lib/labels';

/**
 * Adding a task to the board.
 *
 * ## Why this exists, and what it says about the rest of the board
 *
 * `useCreateWorkItem` has been in the data module since Phase 1 with **zero call sites** — the
 * board could show work and never accept any. It is the oldest dead hook after `useCaseVersions`
 * (§35), and finding it is what the orientation pass is for (§28: *which of these has a consumer*).
 *
 * A board that cannot take a task is not a board. The derived half — executions, flagged cases —
 * arrives on its own from elsewhere in the product, so without this dialog every card on the screen
 * is something the system decided to show you, and the "task" half of §6l's central table does not
 * exist in the UI at all.
 *
 * ## No status field, and no kind field
 *
 * Both are absent from `WorkItemInput` in the contract and the reasons are worth knowing here,
 * because a form is exactly where somebody would add them back:
 *
 * - **status** — a create that chooses its column is a move nobody recorded. Every task starts in
 *   To do, which is also the only honest thing a task nobody has touched can say.
 * - **kind** — a client able to send `EXECUTION` would materialise a derived item, which is the one
 *   thing §6l's whole design exists to prevent. The server ignores it; this form cannot send it.
 *
 * ## The assignee picker, and why its list comes from the server
 *
 * §53 shipped this dialog without one, because `GET /projects/{id}/members` did not exist and the
 * alternative was a free-text UUID box. It exists as of §54, and what matters is *which* list it
 * is: the server defines the project's members as active company members holding a grant on the
 * project, and validates `assigneeId` on the create against **the same predicate**. So this select
 * cannot offer somebody the submit will refuse — which is §31's disable-don't-hide rule applied to
 * a set rather than to a button, and the reason the picker is not simply `GET /users`. The
 * company directory would have listed colleagues who cannot open this project at all, and the
 * only person to ever see the problem would be the assignee, who gets a 404 when they click the
 * card.
 *
 * **"Unassigned" is the default and is a real choice, not an empty state.** §6l's unclaimed pool
 * is how work gets picked up rather than handed out; defaulting to the author makes it
 * unreachable, and the correction is invisible once made because the author's own name looks
 * plausible on the card.
 *
 * ## Mounting, and the idempotency key that depends on it
 *
 * This dialog is rendered only while `open` — `{creating && <TaskCreateDialog … />}` in
 * `BoardPage`, the arrangement `RunCreateDialog` and `PlanCreateDialog` already use. It was
 * mounted unconditionally until §54, which was harmless while the form was stateless and stops
 * being harmless the moment `useCreateWorkItem` holds one `Idempotency-Key` per mount: a
 * persistently mounted dialog would reuse one key for every task typed on that page, and the
 * second create would come back as a **replay of the first** — a save the user watched succeed
 * that stored nothing. Remounting is what makes "one key per user intent" literally true.
 */

/** Mirrors `WorkItemEdit.MAX_TITLE` and the contract's `maxLength`. */
const MAX_TITLE = 300;
/** Mirrors `WorkItemEdit.MAX_DESCRIPTION`. */
const MAX_DESCRIPTION = 2000;

const TaskSchema = z.object({
  title: z
    .string()
    .min(1, 'Enter a title.')
    .max(MAX_TITLE, `Keep the title under ${MAX_TITLE} characters.`),
  description: z.string().max(MAX_DESCRIPTION).optional(),
  priority: z.enum(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']),
  dueDate: z.string().optional(),
  // '' is "unassigned", which is a legitimate answer rather than a missing one — see the header.
  // Not validated against the member list here: the list is asynchronous and the server holds the
  // real rule, so a client-side membership check would be a second authority that is wrong
  // whenever it is out of date.
  assigneeId: z.string().optional(),
});

type TaskFormInput = z.infer<typeof TaskSchema>;

const TestId = 'Board.Create';

type FormField = 'title' | 'description' | 'priority' | 'dueDate' | 'assigneeId';

const FIELDS: readonly FormField[] = ['title', 'description', 'priority', 'dueDate', 'assigneeId'];

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
        // `projectId` and `Idempotency-Key` have no control on this form, so their sentences go
        // to the banner rather than nowhere — the RunCreateDialog rule, and the reason a
        // validation failure can never render as a silent no-op. `assigneeId` left this branch
        // in §54: it has a select now, and a refusal about who was chosen belongs on the control
        // that chose them.
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

export function TaskCreateDialog({
  projectId,
  mine,
  open,
  onClose,
  onCreated,
}: {
  projectId: string;
  /** Carried so the created task lands in the same query key the board is reading. */
  mine: boolean;
  open: boolean;
  onClose: () => void;
  onCreated: (task: WorkItem) => void;
}) {
  const create = useCreateWorkItem(projectId, mine);
  // The dialog only exists while it is open (see the header), so `open` is the honest gate: the
  // query runs once per intent to add a task, and a board nobody is adding to asks nobody.
  const members = useProjectMembers(projectId, open);
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<TaskFormInput>({
    resolver: zodResolver(TaskSchema),
    defaultValues: {
      title: '',
      description: '',
      priority: 'MEDIUM',
      dueDate: '',
      assigneeId: '',
    },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (open) setFocus('title');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync({
        title: values.title.trim(),
        description: values.description?.trim() || null,
        priority: values.priority,
        // An empty date input is '' and the contract's type is `date | null`. Sending '' would be
        // a malformed date rather than an absent one, and the server would answer 400 for a field
        // the user never filled in.
        dueDate: values.dueDate?.trim() || null,
        // '' is the "Unassigned" option, and it has to become `null` rather than travel as an
        // empty string: the contract types this as `uuid | null`, so '' is a malformed id and the
        // server would answer 400 for the choice the form offers by default.
        assigneeId: values.assigneeId?.trim() || null,
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
        <DialogTitle>New task</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
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
              helperText={errors.title?.message ?? 'What needs doing — “Update the refund cases”.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Title`, maxLength: MAX_TITLE } }}
              {...register('title')}
            />
            <TextField
              label="Description"
              multiline
              minRows={2}
              autoComplete="off"
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional.'}
              slotProps={{
                htmlInput: { 'data-testid': `${TestId}.Description`, maxLength: MAX_DESCRIPTION },
              }}
              {...register('description')}
            />
            <Stack direction="row" spacing={2}>
              <TextField
                label="Priority"
                select
                fullWidth
                defaultValue="MEDIUM"
                error={!!errors.priority}
                // Named rather than left to a bare enum: this is board priority, which §6l is
                // explicit is NOT the case's priority — a P1 case may be a low-priority thing to
                // update.
                helperText={errors.priority?.message ?? 'How urgent the work is.'}
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Priority` } }}
                {...register('priority')}
              >
                {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const).map((priority) => (
                  <MenuItem
                    key={priority}
                    value={priority}
                    data-testid={`${TestId}.Priority.${priority}`}
                  >
                    {priorityLabels[priority]}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Due"
                type="date"
                fullWidth
                error={!!errors.dueDate}
                // A date in the past is accepted, by the server too: recording something already
                // late is the most common reason to type a task at all, and the board has an
                // `overdue` flag whose entire job is showing it.
                helperText={errors.dueDate?.message ?? 'Optional.'}
                slotProps={{
                  inputLabel: { shrink: true },
                  htmlInput: { 'data-testid': `${TestId}.DueDate` },
                }}
                {...register('dueDate')}
              />
            </Stack>

            {/*
              The assignee. Disabled while the list is loading rather than rendered empty — an
              enabled select showing only "Unassigned" reads as "there is nobody to assign this
              to", which is a claim, and a wrong one on every project with colleagues on it.

              A failure is not a blocker: the field says so and stays usable at "Unassigned", so a
              members request that 500s costs the assignment and not the task. The alternative —
              failing the whole dialog — would make an optional field able to stop a create.
            */}
            <TextField
              label="Assignee"
              select
              fullWidth
              disabled={members.isPending}
              defaultValue=""
              error={!!errors.assigneeId}
              helperText={
                errors.assigneeId?.message ??
                (members.isError
                  ? "Couldn't load the project's members. The task can still be added unassigned."
                  : members.isPending
                    ? 'Loading the people on this project…'
                    : 'Leave unassigned so anyone on the project can pick it up.')
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Assignee` } }}
              {...register('assigneeId')}
            >
              <MenuItem value="" data-testid={`${TestId}.Assignee.None`}>
                Unassigned
              </MenuItem>
              {(members.data ?? []).map((person) => (
                <MenuItem
                  key={person.id}
                  value={person.id}
                  // Keyed by the person's id, not a shared `.Option` — the test-id audit
                  // requires distinct locators, and selecting a MUI option is a click on the
                  // menu item rather than a value assignment.
                  data-testid={`${TestId}.Assignee.${person.id}`}
                >
                  {person.displayName}
                </MenuItem>
              ))}
            </TextField>
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
            {create.isPending ? 'Adding…' : 'Add task'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
