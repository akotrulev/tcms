import {
  Alert,
  Autocomplete,
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
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import type { CaseConditionRef, Condition, Suite } from '../../api/types';
import { ApiError } from '../../api/client';
import { Identifier } from '../../components/Identifier';
import { formatCount, plural } from '../../lib/format';
import { useCreateSuite, useDeleteSuite, useProjectTags, useRenameSuite } from '../../api/queries';
import { describeError } from '../../lib/describeError';
import { ConditionEditDialog } from './ConditionEditDialog';
import { ConditionsPanel } from './ConditionsPanel';

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

/** The server's cap on a tag rule, and it must equal a tag's own cap — see below. */
const MAX_TAG = 60;

const SuiteSchema = z.object({
  name: z.string().min(1, 'Enter a name.').max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  description: z.string().max(2000).optional(),
  /**
   * The tags this suite CLAIMS (§96) — its third membership arm, beside the cases filed in it and
   * the cases added to it individually.
   *
   * **Unioned, never intersected**: a suite with `smoke` and `payments` holds every case carrying
   * *either*. That is the opposite of the case grid's `tag` filter, which ANDs, and the difference
   * is the direction of the intent — a collection is being assembled here and a list narrowed
   * there. The helper text says so, because nothing else on screen can.
   *
   * Capped at the same length a tag is capped at: a rule longer than a tag can be is a rule that
   * can never match anything, accepted and silently useless.
   */
  tagRules: z.array(z.string().max(MAX_TAG, `Keep each tag under ${MAX_TAG} characters.`)),
  /**
   * The suite's own conditions (§186), as ids in the author's order — the ids and not the refs,
   * because order is the content here and the panel reorders a list of ids.
   *
   * A suite's conditions are inherited by the cases **filed** in it, and by those only: a case
   * *added* individually or matched by a `tagRule` does not pick them up. The helper text below
   * says so, because there is nowhere else on this form a reader would learn it.
   */
  preconditionIds: z.array(z.string()),
  postconditionIds: z.array(z.string()),
});

type SuiteFormInput = z.infer<typeof SuiteSchema>;

const TestIdPrefix = 'Suites.Dialog';

/**
 * A served condition list as the form holds it.
 *
 * The `?? []` is for the create path and the null filter is for the contract: `conditionId` is
 * nullable on `CaseConditionRef` because an inline attachment has none — and the server excludes
 * inline rows at the query, so the filter drops nothing in practice and is what keeps this honest
 * if that ever changes. Attaching a row whose entry is unknown is not a thing this form can express.
 */
function attachedIds(refs: readonly CaseConditionRef[] | undefined): string[] {
  return (refs ?? []).map((ref) => ref.conditionId).filter((id): id is string => id !== null && id !== undefined);
}

/**
 * The input schema's field names, mapped to this form's.
 *
 * They agree everywhere but the two condition lists, where the server reports under
 * `preconditions` / `postconditions` (the payload's names) and the form holds
 * `preconditionIds` / `postconditionIds` (ids, not refs). Without the mapping a rejected condition
 * would land in the form-level alert instead of on the panel that owns it — the same silent drop
 * the `If-Match` note below is about, one field along.
 */
const SERVER_FIELDS = {
  name: 'name',
  description: 'description',
  tagRules: 'tagRules',
  preconditions: 'preconditionIds',
  postconditions: 'postconditionIds',
} as const satisfies Record<string, keyof SuiteFormInput>;

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
  setError: (field: keyof SuiteFormInput | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      const formField = SERVER_FIELDS[field as keyof typeof SERVER_FIELDS];
      if (formField) {
        setError(formField, messages.join(' '));
        landed = true;
      }
    }
    // `If-Match` is a keyed field with no input on this form — a precondition the server could
    // not parse. Dropping it silently would leave a rejected save with nothing shown at all.
    // (`parentId` was the other one until §98 removed it from the contract.)
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
  projectId,
  title,
  submitLabel,
  defaults,
  onSubmit,
  isPending,
  testId,
  attached,
}: {
  open: boolean;
  onClose: () => void;
  /** Scopes the tag suggestions; a suite's rules are only ever about its own project's tags. */
  projectId: string;
  title: string;
  submitLabel: string;
  defaults: SuiteFormInput;
  /**
   * The suite's conditions **as stored**, for B10's detach warning. Absent on a create, where
   * nothing is attached yet and nothing can have been declined.
   *
   * The stored rows rather than the form's ids, because the number the warning needs —
   * `declineCount` — is a property of the *attachment* and the form holds library ids.
   */
  attached?: readonly CaseConditionRef[];
  onSubmit: (values: SuiteFormInput, setError: Parameters<typeof applyServerError>[1]) => Promise<void>;
  isPending: boolean;
  testId: string;
}) {
  const projectTags = useProjectTags(projectId);

  /*
    The library dialog, opened FROM this dialog — the case editor's arrangement (`CaseEditorPage`),
    kept identical on purpose. An author assembling a suite's standing procedure is exactly as
    likely to find the entry missing as one assembling a case's, and sending them to the case
    editor to write it would be the half-finished flow `ui-rules` calls a workflow defect.

    `createdConditions` is why it is state rather than a fire-and-forget: the library reads are
    keyed by search term and all stale at the moment of creation, so a freshly created entry
    resolves to nothing and the row the author just added renders "Unknown condition".
  */
  const [conditionDialog, setConditionDialog] = useState<{
    kind: 'PRE' | 'POST';
    condition: Condition | null;
  } | null>(null);
  const [createdConditions, setCreatedConditions] = useState<Condition[]>([]);

  const {
    register,
    control,
    handleSubmit,
    setError,
    setFocus,
    setValue,
    getValues,
    watch,
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

  /*
    How many opt-outs this save would destroy (B10).

    Read off the form as it stands — `watch`, not `getValues` — because the warning has to appear
    the moment a chip is removed, which is the only moment it can still be acted on.
  */
  const keptConditionIds = new Set<string>([
    ...watch('preconditionIds'),
    ...watch('postconditionIds'),
  ]);
  const abandonedDeclines = (attached ?? [])
    // `conditionId` is nullable on the wire (an inline attachment, which nothing can create), so
    // a row without one is not a row the form could be keeping or dropping.
    .filter((row) => !!row.conditionId && !keptConditionIds.has(row.conditionId))
    .reduce((total, row) => total + row.declineCount, 0);

  return (
    // `md`, not `sm`: the two condition panels each carry a list, a picker and a create button,
    // and at `sm` the reorder controls wrap under the title they belong to.
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md" data-testid={testId}>
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
            <Controller
              control={control}
              name="tagRules"
              render={({ field, fieldState }) => (
                <Autocomplete
                  multiple
                  freeSolo
                  // The tags in use, most-used first, for the case editor's reason and with its
                  // caveat: derived rather than curated, and `freeSolo` so a rule may still name a
                  // tag no case carries yet — which is how a suite gets written before its cases.
                  options={(projectTags.data ?? []).map((usage) => usage.tag)}
                  filterSelectedOptions
                  value={field.value}
                  onChange={(_event, value) =>
                    field.onChange([
                      ...new Set(value.map((tag) => tag.trim()).filter((tag) => tag !== '')),
                    ])
                  }
                  onBlur={field.onBlur}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Tag rules"
                      error={!!fieldState.error}
                      helperText={
                        fieldState.error?.message ??
                        'Any case carrying ANY of these belongs to this suite, wherever it is filed.'
                      }
                      slotProps={{
                        // `...params.slotProps` FIRST, and this is not tidiness. MUI puts the
                        // selected chips in `slotProps.input.startAdornment` and the field's ref
                        // and ARIA wiring in `slotProps.htmlInput`; an object that merely adds a
                        // testid REPLACES the lot, so every tag is held by the form, sent on save,
                        // and invisible on screen — the author types it, sees nothing, and types
                        // it again. Both gates were green when this was wrong.
                        ...params.slotProps,
                        htmlInput: {
                          ...params.slotProps?.htmlInput,
                          'data-testid': `${TestIdPrefix}.TagRules`,
                        },
                        formHelperText: { role: fieldState.error ? 'alert' : undefined },
                      }}
                    />
                  )}
                />
              )}
            />

            {/*
              §186 — the suite's own conditions, and the first surface in the product that can
              write one. `suite_condition` shipped in M0 with a reader, an index and no writer at
              all; a row could only be created by hand-written SQL until this form existed.

              `inherited={[]}` and not a prop: a suite's conditions ARE the inheritance, running
              downward to the cases filed in it. There is nothing above a suite to inherit from —
              §98 removed the tree — so the panel's inherited section is empty here by construction
              rather than by omission.
            */}
            <Stack sx={{ gap: 1 }}>
              <DialogContentText variant="body2">
                Conditions are inherited by the cases <strong>filed</strong> in this suite. A case
                added individually, or matched by a tag rule, does not pick them up.
              </DialogContentText>
              <Stack sx={{ gap: 3 }}>
                <Controller
                  control={control}
                  name="preconditionIds"
                  render={({ field, fieldState }) => (
                    <Stack sx={{ gap: 0.5 }}>
                      <ConditionsPanel
                        kind="PRE"
                        projectId={projectId}
                        testId={`${TestIdPrefix}.Preconditions`}
                        value={field.value}
                        inherited={[]}
                        onChange={field.onChange}
                        known={createdConditions}
                        onCreate={() => setConditionDialog({ kind: 'PRE', condition: null })}
                        onEdit={(condition) => setConditionDialog({ kind: 'PRE', condition })}
                      />
                      {fieldState.error && (
                        <Alert severity="error" role="alert">
                          {fieldState.error.message}
                        </Alert>
                      )}
                    </Stack>
                  )}
                />
                <Controller
                  control={control}
                  name="postconditionIds"
                  render={({ field, fieldState }) => (
                    <Stack sx={{ gap: 0.5 }}>
                      <ConditionsPanel
                        kind="POST"
                        projectId={projectId}
                        testId={`${TestIdPrefix}.Postconditions`}
                        value={field.value}
                        inherited={[]}
                        onChange={field.onChange}
                        known={createdConditions}
                        onCreate={() => setConditionDialog({ kind: 'POST', condition: null })}
                        onEdit={(condition) => setConditionDialog({ kind: 'POST', condition })}
                      />
                      {fieldState.error && (
                        <Alert severity="error" role="alert">
                          {fieldState.error.message}
                        </Alert>
                      )}
                    </Stack>
                  )}
                />
              </Stack>
            </Stack>
            {/*
              B10 — what detaching a condition costs, said before Save rather than discovered after.

              `case_condition_optout` cascades on `suite_condition`, so removing a condition here
              deletes every case's decision to opt out of it — and re-attaching the same library
              entry does NOT restore them, because the new attachment has a new id. Nothing said
              so, and nothing could: `declineCount` reached the wire with this slice.

              A warning and never a block: detaching is legitimate and only the author can weigh
              what it costs. The same shape as the case editor's move warning (§192) — measured
              from the stored rows rather than predicted from a rule.
            */}
            {abandonedDeclines > 0 && (
              <Alert severity="warning" data-testid={`${TestIdPrefix}.DeclineWarning`}>
                {abandonedDeclines === 1
                  ? 'One case has opted out of a condition you are removing. That opt-out is deleted with it, and re-attaching the condition does not bring it back.'
                  : `${plural(abandonedDeclines, 'case')} have opted out of conditions you are removing. Those opt-outs are deleted with them, and re-attaching the conditions does not bring them back.`}
              </Alert>
            )}
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
      {conditionDialog && (
        <ConditionEditDialog
          key={conditionDialog.condition?.id ?? 'new'}
          projectId={projectId}
          open
          condition={conditionDialog.condition}
          defaultType={conditionDialog.kind}
          onClose={() => setConditionDialog(null)}
          onCreated={(created) => {
            // Attached to the list it was created from — `CaseEditorPage`'s rule: they asked for a
            // condition HERE, and leaving them to find it in the picker they just left is a
            // workflow defect rather than a saving of one click.
            setCreatedConditions((current) => [...current, created]);
            const field =
              conditionDialog.kind === 'PRE' ? 'preconditionIds' : 'postconditionIds';
            setValue(field, [...getValues(field), created.id], { shouldDirty: true });
          }}
        />
      )}
    </Dialog>
  );
}

export function CreateSuiteDialog({
  open,
  onClose,
  projectId,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  onCreated: (suite: Suite) => void;
}) {
  const create = useCreateSuite(projectId);

  return (
    <SuiteFormDialog
      open={open}
      onClose={onClose}
      projectId={projectId}
      // One title, because there is one kind of suite. It was `New suite in <parent>` when a
      // create could land somewhere the user had not chosen deliberately; §98 removed the place.
      title="New suite"
      submitLabel="Create suite"
      defaults={{
        name: '',
        description: '',
        tagRules: [],
        preconditionIds: [],
        postconditionIds: [],
      }}
      isPending={create.isPending}
      testId={`${TestIdPrefix}.Create`}
      onSubmit={async (values, setError) => {
        try {
          const created = await create.mutateAsync({
            name: values.name.trim(),
            description: values.description?.trim() || null,
            tagRules: values.tagRules,
            preconditions: values.preconditionIds.map((conditionId) => ({ conditionId })),
            postconditions: values.postconditionIds.map((conditionId) => ({ conditionId })),
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
      projectId={projectId}
      title={`Rename ${suite.name}`}
      submitLabel="Save changes"
      // The suite's OWN rules, served on every read since §96. Seeding them is what makes a
      // rename able to leave them alone — the field is always sent, so an unmentioned collection
      // could only be preserved by knowing what it was.
      // The suite's OWN conditions, seeded from the read for the same reason the tag rules are:
      // the fields are always sent, so an unmentioned list could only be preserved by knowing what
      // it was. Absent WOULD preserve it server-side — but a form that sometimes omits a field it
      // renders is a form whose Save means two different things.
      defaults={{
        name: suite.name,
        description: suite.description ?? '',
        tagRules: suite.tagRules,
        preconditionIds: attachedIds(suite.preconditions),
        postconditionIds: attachedIds(suite.postconditions),
      }}
      isPending={rename.isPending}
      testId={`${TestIdPrefix}.Rename`}
      onSubmit={async (values, setError) => {
        try {
          const renamed = await rename.mutateAsync({
            name: values.name.trim(),
            description: values.description?.trim() || null,
            // Always sent, including as []: the server reads absent as "leave them alone", so
            // clearing a suite's last rule would otherwise report success and change nothing —
            // and the collection would keep holding cases nobody could see why. The two condition
            // lists are sent on the same terms and it matters more there: detaching the last
            // condition is exactly the save an absent field would report as done and not do.
            tagRules: values.tagRules,
            preconditions: values.preconditionIds.map((conditionId) => ({ conditionId })),
            postconditions: values.postconditionIds.map((conditionId) => ({ conditionId })),
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
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = remove;

  // Reopening on another folder must not show the previous refusal.
  useEffect(() => {
    if (open) reset();
  }, [open, suite.id, reset]);

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
            {/* ~~"removed from the tree"~~ — there has been no tree since §98, and a suite is a
                named collection resolved from three arms. */}
            <Identifier>{suite.key}</Identifier> {suite.name} will be removed from this project.
          </DialogContentText>
          {/*
            B10 — what this costs, before it is pressed rather than after.

            The delete refuses while cases are FILED here (the server's 409, rendered below), and
            says nothing at all about the other two arms: a case ADDED individually or claimed by a
            tag rule is dropped from the collection without a word. The cases themselves survive —
            deleting a suite deletes no case — and the membership does not, which is the sentence
            somebody about to lose a hand-assembled selection of forty cases needs.

            Counted from what the read already serves: `caseCount` is the union of the three arms
            and `caseIds` is the added one. The FILED count is deliberately not inferred by
            subtraction — the arms overlap (a case can be filed here AND tagged in), so
            `caseCount - caseIds.length` is not the filed count and a number that is wrong in a
            confirmation is worse than a number that is absent.
          */}
          {suite.caseCount > 0 && (
            <Alert severity="warning" data-testid={`${TestIdPrefix}.Delete.Membership`}>
              This suite currently resolves to {formatCount(suite.caseCount)}{' '}
              {suite.caseCount === 1 ? 'case' : 'cases'}
              {suite.caseIds.length > 0 &&
                `, ${formatCount(suite.caseIds.length)} of them added individually`}
              {suite.tagRules.length > 0 &&
                `, and it claims everything tagged ${suite.tagRules.join(', ')}`}
              . The cases are not deleted — they stop belonging to this collection. A case{' '}
              <strong>filed</strong> here blocks the delete instead.
            </Alert>
          )}
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
