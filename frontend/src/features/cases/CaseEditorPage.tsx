import { zodResolver } from '@hookform/resolvers/zod';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { restrictToParentElement, restrictToVerticalAxis } from '@dnd-kit/modifiers';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Chip,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import LibraryAddIcon from '@mui/icons-material/LibraryAddOutlined';
import LinkOffIcon from '@mui/icons-material/LinkOffOutlined';
import { useSnackbar } from 'notistack';
import { useEffect, useState, type ReactNode } from 'react';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { Controller, useFieldArray, useForm, type Control } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { CaseSchema, isBlankStep, liveSteps, type CaseInput } from './caseSchema';
import { ClassificationPicker } from './ClassificationPicker';
import { ConditionEditDialog } from './ConditionEditDialog';
import { ConditionsPanel } from './ConditionsPanel';
import { SharedStepPicker } from './SharedStepPicker';
import { resolveStepMove } from './stepReorder';
import {
  useCase,
  useClassification,
  useCreateCase,
  useStepSuggestions,
  useSuites,
  useUpdateCase,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { automationLabels, priorityLabels, stepFormatLabels } from '../../lib/labels';
import { selectTestId } from '../../lib/muiTestIds';
import type { CaseConditionRef, Condition, StepSuggestion, TestCase } from '../../api/types';

/**
 * Case authoring form.
 *
 * The schema it binds lives in `./caseSchema` rather than here. It used to be module-private in
 * this file, which is exactly why its four cross-field refinements went untested through Phase 4
 * (§36) and were still an open follow-up in §37 — a rule nothing can import is a rule only a human
 * can check. Moving it out, rather than exporting it from here, keeps this file a component module
 * and Fast Refresh working.
 */

const TestIdPrefix = 'CaseEditor';

/**
 * The row the author types into to get the next one.
 *
 * One of these is appended after the last row the moment somebody writes in it, so the list always
 * ends in a blank invitation and adding a step is never a button press. Two consequences that are
 * not obvious and are both handled elsewhere in this slice: the form is submitted with the blank
 * row still on it (so the submit trims and drops it), and "at least one step" can no longer be
 * `steps.length` (so `CaseSchema` counts `liveSteps`).
 */
const BLANK_STEP = { action: '', testData: '', expectedResult: '' };

/**
 * `useFieldArray.append` focuses the new row's first field by default.
 *
 * That is right for a button somebody pressed and wrong for every append in this file: the row is
 * appended *while the author is typing in the row above it*, so the default would move the caret
 * out of the sentence being written on the first keystroke of every step.
 */
const NO_FOCUS = { shouldFocus: false } as const;

const GHERKIN_TEMPLATE = `Feature:

  Scenario:
    Given
    When
    Then `;

/**
 * One draggable step row — the wrapper, not the row's content (§94).
 *
 * A render prop rather than a component holding the row's JSX: the row is three fields, a shared
 * chip, three buttons and their comments, and moving all of it into a second component would have
 * made a reordering change look like a rewrite of the step editor in the diff. This is the whole
 * of what `useSortable` needs, and the row stays where it was.
 *
 * `id` is react-hook-form's field id, not the step's server id: a step created in this session has
 * no server id at all, and `useSortable` needs one for every row from the moment it renders.
 */
function SortableStep({
  id,
  children,
}: {
  id: string;
  children: (handle: {
    attributes: ReturnType<typeof useSortable>['attributes'];
    listeners: ReturnType<typeof useSortable>['listeners'];
  }) => ReactNode;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id });

  return (
    <Box
      ref={setNodeRef}
      data-testid={`${TestIdPrefix}.Steps.Row`}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      sx={{
        p: 1.5,
        border: '1px solid',
        borderColor: isDragging ? 'primary.main' : 'divider',
        borderRadius: 1,
        // The row being dragged is lifted rather than hidden: this list is the content the author
        // is reading, and a gap where the step was makes the drop target ambiguous.
        bgcolor: 'background.paper',
        opacity: isDragging ? 0.85 : 1,
        zIndex: isDragging ? 1 : undefined,
        position: 'relative',
      }}
    >
      {children({ attributes, listeners })}
    </Box>
  );
}

/**
 * One step's action, completing from the project's own step corpus (§94).
 *
 * ## Why this is a component rather than JSX in the row loop
 *
 * It owns a hook. Each row searches for what its own author is typing, and a hook cannot be called
 * inside `steps.fields.map`. The debounce state has to be per row for the same reason — one shared
 * fragment would make every row's popup show the answer to the row being typed in.
 *
 * ## Copy, never reference
 *
 * A pick fills the three fields and creates no link — the opposite of `SharedStepPicker` beside it,
 * which sets `sharedStepId` and makes the step track a library entry. The corpus carries no ids at
 * all, so this cannot accidentally become the other thing.
 */
function StepActionField({
  projectId,
  control,
  index,
  error,
  onType,
  onPick,
}: {
  projectId: string;
  control: Control<CaseInput>;
  index: number;
  error: string | undefined;
  /** Every change to the text, before the debounce — the auto-append hangs off this. */
  onType: (value: string) => void;
  onPick: (suggestion: StepSuggestion) => void;
}) {
  const [typed, setTyped] = useState('');
  const [fragment, setFragment] = useState('');

  /*
    The debounce. A request per keystroke would be one per character of every step in the case,
    against an endpoint that scans the project's whole step corpus — and with a 3s typeahead
    budget, most of them would still be in flight when their answer stopped being wanted.

    250ms rather than the filter bar's 300ms: this popup opens under the caret and a third of a
    second there reads as the field being slow, where the case grid is refilling below the fold.
  */
  useEffect(() => {
    if (typed === fragment) return;
    const id = setTimeout(() => setFragment(typed), 250);
    return () => clearTimeout(id);
  }, [typed, fragment]);

  const suggestions = useStepSuggestions(projectId, fragment);
  /*
    A failed or timed-out corpus read renders as no suggestions and nothing else. Deliberate: the
    field is a plain text input that happens to complete, the author can write the step by hand,
    and an error banner over an editor because an *optional* enrichment failed would be the
    critical-vs-optional rule broken in the direction that costs the most. The query client's
    global handler still reports it.
  */
  const options = suggestions.data ?? [];

  return (
    <Controller
      control={control}
      name={`steps.${index}.action`}
      render={({ field }) => (
        <Autocomplete
          freeSolo
          options={options}
          /*
            `value` is pinned to null and the text lives in the form. Letting MUI hold a value too
            would put the same sentence in two places — the form's copy is what is submitted and
            validated, and the two would drift the moment a pick was followed by an edit.
          */
          value={null}
          inputValue={field.value ?? ''}
          // The server has already narrowed by the fragment; MUI's client-side filter would then
          // narrow the answer AGAIN against the same text and drop every suggestion whose action
          // matches on a word the input does not start with.
          filterOptions={(rows) => rows}
          getOptionLabel={(option) => (typeof option === 'string' ? option : option.action)}
          onInputChange={(_event, value, reason) => {
            // `reset` is MUI's own write-back — on mount, and after a pick. Taking it would
            // overwrite the value we just set with MUI's idea of the label, and on mount would
            // clear the action of every step in an edit form.
            if (reason === 'reset') return;
            field.onChange(value);
            setTyped(value);
            onType(value);
          }}
          onChange={(_event, value) => {
            // A string here is the free-solo commit of what is already in the field (Enter with no
            // highlighted option); only an object is a pick from the corpus.
            if (value && typeof value !== 'string') onPick(value);
          }}
          renderOption={(props, option) => {
            const { key, ...rest } = props as typeof props & { key: string };
            return (
              <li key={key} {...rest} data-testid={`${TestIdPrefix}.Steps.Suggestion`}>
                <Stack sx={{ gap: 0.25, py: 0.5, minWidth: 0 }}>
                  <Stack direction="row" sx={{ gap: 1, alignItems: 'baseline' }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {option.action}
                    </Typography>
                    {/* The ranking signal, shown rather than only sorted by — "used by 41 cases"
                        is what tells an author they are about to write the project's twenty-first
                        wording of a step it already has. */}
                    <Identifier variant="muted">
                      {option.useCount} case{option.useCount === 1 ? '' : 's'}
                    </Identifier>
                  </Stack>
                  {(option.testData || option.expectedResult) && (
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {[option.testData, option.expectedResult].filter(Boolean).join(' → ')}
                    </Typography>
                  )}
                </Stack>
              </li>
            );
          }}
          renderInput={(params) => (
            <TextField
              {...params}
              label="Action"
              required
              fullWidth
              multiline
              error={!!error}
              helperText={error ?? 'Suggestions come from steps this project already uses.'}
              onBlur={field.onBlur}
              slotProps={{
                ...params.slotProps,
                htmlInput: {
                  ...params.slotProps.htmlInput,
                  'data-testid': `${TestIdPrefix}.Steps.Field.Action`,
                },
                formHelperText: { role: error ? 'alert' : undefined },
              }}
            />
          )}
        />
      )}
    />
  );
}

export function CaseEditorPage({ mode }: { mode: 'create' | 'edit' }) {
  const { projectId = '', caseId } = useParams();
  const existing = useCase(projectId, mode === 'edit' ? caseId : undefined);

  if (mode === 'edit') {
    return (
      <DataRegion testId={`${TestIdPrefix}.Load`} state={{ ...existing, refetch: () => void existing.refetch() }}>
        {(data) => <CaseEditorForm mode="edit" projectId={projectId} existing={data} />}
      </DataRegion>
    );
  }

  return <CaseEditorForm mode="create" projectId={projectId} existing={undefined} />;
}

/**
 * The case's OWN attached condition ids, in order — what the editor binds.
 *
 * Inherited rows are the folder's and are dropped: submitting one would attach the folder's
 * condition directly to the case. Inline rows carry no `conditionId` and are dropped too, on the
 * server's terms — nothing can render one, so nothing can round-trip one either.
 */
function ownConditionIds(refs: readonly CaseConditionRef[] | undefined): string[] {
  return (refs ?? [])
    .filter((ref) => !ref.inherited && !!ref.conditionId)
    .map((ref) => ref.conditionId as string);
}

function CaseEditorForm({
  mode,
  projectId,
  existing,
}: {
  mode: 'create' | 'edit';
  projectId: string;
  existing: TestCase | undefined;
}) {
  const navigate = useNavigate();
  const { enqueueSnackbar } = useSnackbar();
  const suites = useSuites(projectId);
  const dimensions = useClassification(projectId);

  /*
    Which panel the condition dialog is for, and whether it is creating or editing. One piece of
    state rather than two booleans and a nullable entry: "open for editing X" and "open to create a
    PRE" are the two shapes it has, and separate flags make a third — open, editing nothing,
    for no kind — representable.
  */
  const [conditionDialog, setConditionDialog] = useState<{
    kind: 'PRE' | 'POST';
    condition: Condition | null;
  } | null>(null);

  /*
    Entries created in this editor session, so a row can render the moment it is attached.

    The library lists are cached per search term, so invalidating them does not make the new entry
    resolvable synchronously — and the row in between would render the author's own condition as
    "Unknown condition" on the screen they just created it from. Not server state in a client store:
    these are handed to the panel as a rendering fallback and the query remains the source.
  */
  const [createdConditions, setCreatedConditions] = useState<Condition[]>([]);

  // The folder's conditions, rendered read-only beside the case's own. Filtered here rather than in
  // the panel so the panel takes exactly what it renders and nothing that needs interpreting.
  const inheritedPre = (existing?.preconditions ?? []).filter((ref) => ref.inherited);
  const inheritedPost = (existing?.postconditions ?? []).filter((ref) => ref.inherited);

  const createCase = useCreateCase(projectId);
  const updateCase = useUpdateCase(projectId, existing?.id ?? '');

  const {
    control,
    register,
    handleSubmit,
    getValues,
    watch,
    setValue,
    setError,
    setFocus,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<CaseInput>({
    resolver: zodResolver(CaseSchema),
    mode: 'onBlur',
    defaultValues: {
      title: existing?.title ?? '',
      // '' is UNFILED, not "missing" (§96) — see caseSchema. `?? ''` therefore covers both a new
      // case and an existing one filed nowhere, and the submit maps '' back to null.
      suiteId: existing?.suiteId ?? '',
      objective: existing?.objective ?? '',
      description: existing?.description ?? '',
      expectedOutcome: existing?.expectedOutcome ?? '',
      priority: existing?.priority ?? 'MEDIUM',
      automationStatus: existing?.automationStatus ?? 'MANUAL',
      automationKey: existing?.automationKey ?? '',
      notAutomatableReason: existing?.notAutomatableReason ?? '',
      estimatedDurationSec:
        existing?.estimatedDurationSec === null || existing?.estimatedDurationSec === undefined
          ? ''
          : String(existing.estimatedDurationSec),
      stepFormat: existing?.stepFormat ?? 'STRUCTURED',
      gherkinSource: existing?.gherkinSource ?? '',
      // The case's steps, plus the trailing blank row — on a new case that IS the whole list, which
      // is why the editor no longer opens on nothing at all. Before §94 a new case rendered zero
      // step rows and the only way to get one was the Add button.
      steps: [
        ...(existing?.steps ?? []).map((step) => ({
          id: step.id,
          keyword: step.keyword ?? null,
          action: step.action,
          testData: step.testData ?? '',
          expectedResult: step.expectedResult ?? '',
          sharedStepId: step.sharedStepId ?? null,
        })),
        BLANK_STEP,
      ],
      // The ids the server served, which are the case's live assignments only — a value retired
      // since it was chosen is absent here AND absent from the payload we send back, and the server
      // deliberately does not read that absence as an instruction to delete it. See
      // `TestCaseInput.classificationValueIds` in the contract for the whole rule.
      classificationValueIds: existing?.classificationValueIds ?? [],
      // OWN attachments only — `inherited` rows are the folder's and are shown, not edited, here.
      // Seeding one would attach the folder's condition directly to the case on the first save,
      // permanently, and the two render almost identically so nobody would see it happen. An
      // inline row carries no `conditionId` and is dropped for the same reason the server refuses
      // one: nothing can render it.
      preconditionIds: ownConditionIds(existing?.preconditions),
      postconditionIds: ownConditionIds(existing?.postconditions),
      // Served on TestCaseSummary and therefore on TestCase — the editor could not author them
      // until §96 and the update endpoint refused them, so this is the field's first binding.
      tags: existing?.tags ?? [],
      changeComment: '',
    },
  });

  /*
    Tells the app this surface is holding work that a navigation or reload would destroy.

    Both automatic recoveries consult it: the deploy-skew handler prompts instead of reloading,
    and the session ladder shows the in-place re-auth dialog rather than falling through to a
    redirect. A dirty editor is precisely the case where a well-meaning automatic recovery does
    the most damage.
  */
  useUnsavedWork(isDirty && !isSubmitting, mode === 'edit' ? 'an edited test case' : 'a new test case');

  const steps = useFieldArray({ control, name: 'steps' });

  /**
   * The list always ends in a blank row, and this is the guard that keeps it true.
   *
   * With the Add button gone, the blank last row IS the way to add a step — so an author who
   * removes it has no way to write another one and no control on screen that says so. Every
   * operation that can leave the list without one calls this: the remove button, and the shared
   * step insert below.
   *
   * Read through `getValues` rather than `steps.fields`, which is a render-time snapshot: inside
   * the click handler that just removed a row it still contains the row that is gone.
   */
  const ensureTrailingBlank = () => {
    const rows = getValues('steps');
    const last = rows.at(-1);
    if (!last || !isBlankStep(last)) steps.append(BLANK_STEP, NO_FOCUS);
  };

  /*
    Pointer + keyboard, and the pointer one needs the 5px constraint: without it a click on the
    handle is a zero-distance drag, so the button swallows the click and nothing else on the row
    can be reached by a pointer that started there. The keyboard sensor makes the handle itself
    operable (Space to lift, arrows to move) — the ▲▼ buttons remain the simpler keyboard path, and
    a focusable handle that did nothing on Space would be worse than no handle.
  */
  const dragSensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  /**
   * A finished drag becomes the same `steps.move` the ▲▼ buttons call.
   *
   * The decision of where it lands — including the three ways a drag ends in nothing — is
   * `resolveStepMove`'s, so that it can be tested without driving a pointer through jsdom.
   */
  const onStepDragEnd = ({ active, over }: DragEndEvent) => {
    const move = resolveStepMove(
      steps.fields.map((field) => field.id),
      active.id,
      over?.id,
    );
    if (move) steps.move(move.from, move.to);
  };

  const [pickerOpen, setPickerOpen] = useState(false);
  const stepFormat = watch('stepFormat');
  const automationStatus = watch('automationStatus');

  const onSubmit = handleSubmit(async (data) => {
    /*
      Trim first, then drop the rows that are empty once trimmed.

      Both halves matter and the order is the point. Trimming alone would send a step whose action
      is `"  "` — the trailing blank row after somebody tabbed through it, or a row typed into and
      cleared — as a real step: the API accepts it, it renders as an empty numbered line forever,
      and a run asks a tester to perform it. Dropping alone would keep the leading and trailing
      spaces of every step somebody pasted in, which is what makes two identical steps two entries
      in the corpus this slice added.

      `liveSteps` is `caseSchema`'s, not a second implementation here: the validator's "at least
      one step" and this filter have to be the same question, or the form validates a list the
      submit then empties.
    */
    const steps = liveSteps(
      data.steps.map((step) => ({
        ...step,
        action: step.action.trim(),
        testData: (step.testData ?? '').trim(),
        expectedResult: (step.expectedResult ?? '').trim(),
      })),
    );

    // Only the chosen format's content is sent. Posting both would leave the other one
    // stale in the record, and the next reader can't tell which is authoritative.
    const payload: Record<string, unknown> = {
      title: data.title.trim(),
      // null, not '', for an unfiled case: the field is a uuid on the contract and '' is a 400.
      suiteId: data.suiteId || null,
      objective: data.objective || null,
      description: data.description || null,
      expectedOutcome: data.expectedOutcome || null,
      priority: data.priority,
      severity: data.priority,
      automationStatus: data.automationStatus,
      automationKey: data.automationStatus === 'AUTOMATED' ? data.automationKey || null : null,
      notAutomatableReason:
        data.automationStatus === 'NOT_AUTOMATABLE' ? data.notAutomatableReason || null : null,
      estimatedDurationSec: data.estimatedDurationSec ? Number(data.estimatedDurationSec) : null,
      stepFormat: data.stepFormat,
      gherkinSource: data.stepFormat === 'GHERKIN' ? data.gherkinSource : null,
      steps:
        data.stepFormat === 'STRUCTURED'
          ? steps.map((step) => ({
              id: step.id ?? null,
              keyword: null,
              action: step.action,
              testData: step.testData || null,
              expectedResult: step.expectedResult || null,
              sharedStepId: step.sharedStepId ?? null,
            }))
          : [],
      // Always sent, including as `[]`. Omitting it when empty would mean "leave it alone" to the
      // server, so clearing a case's classification would silently do nothing — the one place
      // where sending the field's natural empty value and omitting the field are different
      // instructions, which is why this is not `|| undefined` like the nullable scalars above.
      classificationValueIds: data.classificationValueIds,
      // Always sent, including as `[]`, for the reason above — the server reads absent as "leave
      // them alone", so removing a case's last tag would silently do nothing. Trimmed and
      // de-duplicated here as well as in the control, because the control is not the only way a
      // value can get into the form (a draft restored from state, a future paste handler).
      tags: [...new Set(data.tags.map((tag) => tag.trim()).filter((tag) => tag !== ''))],
      // Always sent, including as `[]`, for the reason above — the server reads absent as "leave
      // them alone", so detaching the last condition would silently do nothing.
      preconditions: data.preconditionIds.map((conditionId) => ({ conditionId })),
      postconditions: data.postconditionIds.map((conditionId) => ({ conditionId })),
      changeComment: data.changeComment || null,
    };

    try {
      if (mode === 'edit' && existing) {
        // The version we read is sent back, so a concurrent save is rejected rather than
        // silently overwritten.
        const saved = await updateCase.mutateAsync({ ...payload, lockVersion: existing.lockVersion });
        enqueueSnackbar(`Saved ${saved.key} as v${saved.currentVersionNo}`, { variant: 'success' });
        navigate(`/projects/${projectId}/cases/${saved.id}`);
      } else {
        const created = await createCase.mutateAsync(payload);
        enqueueSnackbar(`Created ${created.key}`, { variant: 'success' });
        navigate(`/projects/${projectId}/cases/${created.id}`);
      }
    } catch (error) {
      // Field-keyed server errors land on their fields; only genuinely non-field failures
      // become a banner. A "Something went wrong" message over a form the server told us
      // how to fix is a wasted round trip for the user.
      if (error instanceof ApiError && error.hasFieldErrors) {
        const entries = Object.entries(error.fieldErrors ?? {});
        for (const [field, messages] of entries) {
          setError(field as keyof CaseInput, { type: 'server', message: messages.join(' ') });
        }
        const first = entries[0]?.[0] as keyof CaseInput | undefined;
        if (first) setFocus(first);
        return;
      }
      setError('root', {
        type: 'server',
        message:
          error instanceof ApiError
            ? error.status === 409
              ? error.detail
              : error.detail
            : "Couldn't save the case. Check your connection and try again.",
      });
    }
  });

  return (
    <Box component="form" onSubmit={onSubmit} noValidate aria-busy={isSubmitting} data-testid={`${TestIdPrefix}.Form`}>
      <PageHeader
        testId={`${TestIdPrefix}.Header`}
        title={mode === 'edit' ? `Edit ${existing?.key}` : 'New test case'}
        documentTitle={mode === 'edit' ? `Edit ${existing?.key}` : 'New test case'}
        crumbs={[
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          ...(existing ? [{ label: existing.key, to: `/projects/${projectId}/cases/${existing.id}` }] : []),
          { label: mode === 'edit' ? 'Edit' : 'New' },
        ]}
        meta={
          existing && (
            <Tooltip title="Saving writes a new version. Past runs keep the version they executed.">
              <Box>
                <Identifier variant="muted">
                  v{existing.currentVersionNo} → v{(existing.currentVersionNo ?? 1) + 1}
                </Identifier>
              </Box>
            </Tooltip>
          )
        }
        actions={
          <>
            <Button
              onClick={() =>
                navigate(existing ? `/projects/${projectId}/cases/${existing.id}` : `/projects/${projectId}/cases`)
              }
              data-testid={`${TestIdPrefix}.Cancel`}
            >
              Cancel
            </Button>
            {/*
              Gated as well as its entry points, because the entry points are not the only way in:
              /cases/new and /cases/:id/edit are real URLs, so a bookmark, a shared link or the
              back button lands here without passing the New-case button. Gating only the button
              that navigates here would make the permission look enforced while leaving the form
              fully usable up to the 403.
            */}
            <PermissionGate projectId={projectId} permission={mode === 'edit' ? 'case:edit' : 'case:create'}>
              {({ allowed }) => (
                <Button
                  type="submit"
                  variant="contained"
                  disabled={!allowed || isSubmitting || (mode === 'edit' && !isDirty)}
                  title={allowed && mode === 'edit' && !isDirty ? 'Nothing has changed yet' : undefined}
                  data-testid={`${TestIdPrefix}.Submit`}
                >
                  {isSubmitting ? 'Saving…' : mode === 'edit' ? 'Save changes' : 'Create case'}
                </Button>
              )}
            </PermissionGate>
          </>
        }
      />

      {errors.root && (
        <Alert severity="error" role="alert" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Error`}>
          {errors.root.message}
        </Alert>
      )}

      <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'flex-start' }}>
        <Stack sx={{ gap: 3, flex: 1, minWidth: 0, width: '100%' }}>
          <Panel title="What this case proves" testId={`${TestIdPrefix}.Summary`}>
            <Stack sx={{ gap: 2 }}>
              <TextField
                label="Title"
                required
                fullWidth
                error={!!errors.title}
                helperText={errors.title?.message ?? 'One sentence stating what is verified.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Title` },
                  formHelperText: { role: errors.title ? 'alert' : undefined },
                }}
                {...register('title')}
              />
              <TextField
                label="Objective"
                fullWidth
                multiline
                minRows={2}
                error={!!errors.objective}
                helperText={errors.objective?.message}
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Objective` } }}
                {...register('objective')}
              />
              <TextField
                label="Description"
                fullWidth
                multiline
                minRows={3}
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Description` } }}
                {...register('description')}
              />
              <TextField
                label="Expected outcome"
                fullWidth
                multiline
                minRows={2}
                helperText="The case-level outcome, alongside the per-step expected results."
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.ExpectedOutcome` } }}
                {...register('expectedOutcome')}
              />
            </Stack>
          </Panel>

          <Panel
            title="Steps"
            testId={`${TestIdPrefix}.Steps`}
            actions={
              <Controller
                control={control}
                name="stepFormat"
                render={({ field }) => (
                  <ToggleButtonGroup
                    exclusive
                    size="small"
                    value={field.value}
                    onChange={(_event, value) => {
                      if (!value) return;
                      field.onChange(value);
                      // Seed the editor the user is switching into, so they land on
                      // something writable rather than an empty box.
                      if (value === 'GHERKIN' && !watch('gherkinSource')) {
                        setValue('gherkinSource', GHERKIN_TEMPLATE, { shouldDirty: true });
                      }
                      if (value === 'STRUCTURED' && steps.fields.length === 0) {
                        steps.append(BLANK_STEP, NO_FOCUS);
                      }
                    }}
                    aria-label="Step format"
                  >
                    {(['STRUCTURED', 'GHERKIN'] as const).map((format) => (
                      <ToggleButton
                        key={format}
                        value={format}
                        data-testid={`${TestIdPrefix}.StepFormat.${format}`}
                      >
                        {stepFormatLabels[format]}
                      </ToggleButton>
                    ))}
                  </ToggleButtonGroup>
                )}
              />
            }
          >
            {/*
              A case carries one format or the other, never both — the toggle switches which
              editor is mounted, and only the active one is submitted.
            */}
            {stepFormat === 'GHERKIN' ? (
              <TextField
                label="Scenario"
                fullWidth
                multiline
                minRows={12}
                error={!!errors.gherkinSource}
                helperText={errors.gherkinSource?.message ?? 'Gherkin. Keywords are recognized on the detail view.'}
                slotProps={{
                  htmlInput: {
                    'data-testid': `${TestIdPrefix}.Field.GherkinSource`,
                    style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem', lineHeight: 1.7 },
                  },
                  formHelperText: { role: errors.gherkinSource ? 'alert' : undefined },
                }}
                {...register('gherkinSource')}
              />
            ) : (
              <Stack sx={{ gap: 2 }}>
                {/*
                  `root?.message` as well as `message`, and the first of the two is the one that
                  fires. A refinement whose `path` is a FIELD ARRAY lands on `errors.steps.root` —
                  RHF keeps `errors.steps` itself as the per-row array and reserves `.root` for an
                  error about the list as a whole. Read only as `errors.steps.message`, "add at
                  least one step" was computed, blocked the submit, and rendered NOTHING: the form
                  simply did not respond to the button. Both are read because the server's field
                  errors are mapped onto `steps` by `setError`, which does write `.message`.
                */}
                {(errors.steps?.root?.message ?? errors.steps?.message) && (
                  <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Steps.Error`}>
                    {errors.steps.root?.message ?? errors.steps.message}
                  </Alert>
                )}

                <DndContext
                  sensors={dragSensors}
                  collisionDetection={closestCenter}
                  // Restricted to the vertical axis: the list is one column, so horizontal
                  // movement is a drag drifting away from the drop it means rather than a
                  // gesture with a meaning of its own.
                  modifiers={[restrictToVerticalAxis, restrictToParentElement]}
                  onDragEnd={onStepDragEnd}
                >
                  <SortableContext
                    items={steps.fields.map((field) => field.id)}
                    strategy={verticalListSortingStrategy}
                  >
                    <Stack sx={{ gap: 2 }}>
                {steps.fields.map((field, index) => (
                  <SortableStep key={field.id} id={field.id}>
                    {(handle) => (
                      <>
                    <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mb: 1 }}>
                      {/*
                        The drag handle, and it is a handle rather than the whole row being
                        draggable: every row is three text fields, and a draggable row makes
                        selecting a word inside one of them start a drag instead.
                      */}
                      <Tooltip title="Drag to reorder. The arrows do the same thing from the keyboard.">
                        <IconButton
                          size="small"
                          disableRipple
                          {...handle.attributes}
                          {...handle.listeners}
                          aria-label={`Reorder step ${index + 1}`}
                          data-testid={`${TestIdPrefix}.Steps.DragHandle`}
                          sx={{ cursor: 'grab', touchAction: 'none' }}
                        >
                          <DragIndicatorIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                      <Identifier variant="muted">Step {index + 1}</Identifier>
                      {/*
                        Read off `fields` rather than through `watch('steps')`: sharedStepId is
                        never edited by an input, so it only ever changes through append / update /
                        remove — exactly the operations that refresh `fields`. Watching would
                        re-render the whole step list on every keystroke in any field for a value
                        that cannot have changed.
                      */}
                      {field.sharedStepId && (
                        <>
                          <Tooltip title="This step references the project's step library. Editing the library entry changes every case using it.">
                            <Chip
                              size="small"
                              variant="outlined"
                              label="Shared"
                              data-testid={`${TestIdPrefix}.Steps.SharedChip`}
                            />
                          </Tooltip>
                          <IconButton
                            size="small"
                            onClick={() =>
                              // `update` rather than setValue: it refreshes `fields`, which is
                              // what this row is reading. setValue would clear the reference in
                              // the form values and leave the chip on screen.
                              steps.update(index, { ...steps.fields[index], sharedStepId: null })
                            }
                            aria-label={`Detach step ${index + 1} from the library`}
                            data-testid={`${TestIdPrefix}.Steps.Detach`}
                          >
                            <LinkOffIcon fontSize="small" />
                          </IconButton>
                        </>
                      )}
                      <Box sx={{ flex: 1 }} />
                      {/*
                        Reordering is safe because a step keeps its stable id: results and
                        evidence anchor to the id, not the position, so moving a step never
                        reassigns the evidence attached to it. True of the drag handle above
                        as well — both end in the same `steps.move`.

                        KEPT after §94 added the drag, not superseded by it. These are the
                        keyboard and screen-reader path, they are the only way to move a step by
                        exactly one place deliberately, and they work on a touch screen where a
                        long-press drag inside a scrolling form is a fight.
                      */}
                      <IconButton
                        size="small"
                        disabled={index === 0}
                        onClick={() => steps.move(index, index - 1)}
                        aria-label={`Move step ${index + 1} up`}
                        data-testid={`${TestIdPrefix}.Steps.MoveUp`}
                      >
                        <ArrowUpwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        disabled={index === steps.fields.length - 1}
                        onClick={() => steps.move(index, index + 1)}
                        aria-label={`Move step ${index + 1} down`}
                        data-testid={`${TestIdPrefix}.Steps.MoveDown`}
                      >
                        <ArrowDownwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        onClick={() => {
                          steps.remove(index);
                          // Removing the last row would otherwise leave no blank one, and with the
                          // Add button gone there would be no way back to writing a step.
                          ensureTrailingBlank();
                        }}
                        aria-label={`Remove step ${index + 1}`}
                        data-testid={`${TestIdPrefix}.Steps.Remove`}
                      >
                        <DeleteOutlineIcon fontSize="small" />
                      </IconButton>
                    </Stack>

                    <Stack sx={{ gap: 1.5 }}>
                      <StepActionField
                        projectId={projectId}
                        control={control}
                        index={index}
                        error={errors.steps?.[index]?.action?.message}
                        /*
                          Typing in the LAST row appends the next one, so the list always ends in a
                          blank invitation. Guarded on being last rather than on "this row was
                          empty a moment ago": one keystroke makes this row non-last, so the next
                          keystroke cannot append a second time, and a paste appends exactly once.
                        */
                        onType={(value) => {
                          if (index === steps.fields.length - 1 && value.trim().length > 0) {
                            steps.append(BLANK_STEP, NO_FOCUS);
                          }
                        }}
                        /*
                          A pick fills all three fields, which is the whole point of the corpus
                          carrying the triple: completing the action alone would leave exactly the
                          two fields that distinguish two similar steps blank, and the author would
                          fill them from memory.

                          `shouldDirty` on all three, so picking into an untouched edit form enables
                          Save — without it the change is on screen and the button says nothing has
                          changed yet.
                        */
                        onPick={(suggestion) => {
                          setValue(`steps.${index}.action`, suggestion.action, { shouldDirty: true });
                          setValue(`steps.${index}.testData`, suggestion.testData ?? '', {
                            shouldDirty: true,
                          });
                          setValue(`steps.${index}.expectedResult`, suggestion.expectedResult ?? '', {
                            shouldDirty: true,
                          });
                          if (index === steps.fields.length - 1) steps.append(BLANK_STEP, NO_FOCUS);
                        }}
                      />
                      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5 }}>
                        <TextField
                          label="Test data"
                          fullWidth
                          multiline
                          slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Steps.Field.TestData` } }}
                          {...register(`steps.${index}.testData`)}
                        />
                        <TextField
                          label="Expected result"
                          fullWidth
                          multiline
                          slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Steps.Field.ExpectedResult` } }}
                          {...register(`steps.${index}.expectedResult`)}
                        />
                      </Stack>
                    </Stack>
                      </>
                    )}
                  </SortableStep>
                ))}
                    </Stack>
                  </SortableContext>
                </DndContext>

                {/*
                  There is no "Add step" button any more (§94). The last row is always blank and
                  typing into it appends the next one, so a button that adds an eighth empty row to
                  a list already ending in one is an affordance for something that has already
                  happened. `CaseEditor.Steps.AddButton` is gone with it — a locator, not just a
                  control, so anything driving this screen looks for the blank row instead.
                */}
                <Stack direction="row" sx={{ gap: 1, alignSelf: 'flex-start' }}>
                  {/*
                    Not permission-gated: reading the library needs no permission beyond the
                    project grant this page already required, and `library:manage` gates writing
                    an entry — which the dialog gates on its own button. Gating here would refuse
                    a Viewer the list they are allowed to read.
                  */}
                  <Button
                    startIcon={<LibraryAddIcon />}
                    onClick={() => setPickerOpen(true)}
                    data-testid={`${TestIdPrefix}.Steps.InsertSharedButton`}
                  >
                    Insert shared step
                  </Button>
                </Stack>
              </Stack>
            )}
          </Panel>

          {mode === 'edit' && (
            <Panel title="Change note" testId={`${TestIdPrefix}.ChangeComment`}>
              <TextField
                label="What changed, and why"
                fullWidth
                multiline
                minRows={2}
                // Deliberately optional: mandatory comments produce a thousand entries
                // reading "update", which is worse than none because they look informative.
                helperText="Optional. It appears on the version timeline, so make it worth reading."
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.ChangeComment` } }}
                {...register('changeComment')}
              />
            </Panel>
          )}
        </Stack>

        <Box sx={{ width: { xs: '100%', lg: 320 }, flexShrink: 0 }}>
          <Stack sx={{ gap: 3 }}>
            <Panel title="Placement" testId={`${TestIdPrefix}.Placement`}>
              <Controller
                control={control}
                name="suiteId"
                render={({ field, fieldState }) => (
                  <TextField
                    select
                    label="Suite"
                    fullWidth
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    error={!!fieldState.error}
                    helperText={
                      fieldState.error?.message ??
                      (field.value
                        ? undefined
                        : 'Unfiled. It still appears in the grid, and any suite whose tags it carries.')
                    }
                    slotProps={{
                      select: { native: false, SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.Suite`) },
                      formHelperText: { role: fieldState.error ? 'alert' : undefined },
                    }}
                  >
                    {/*
                      An explicit choice rather than an empty first row (§96). A suite is a
                      resolvable collection now — a case reaches one by being filed, by being added
                      to it, or by carrying its tags — so "nowhere" is a real answer and not a
                      field somebody forgot. A blank option reads as the latter, which is exactly
                      the impression this slice exists to remove.
                    */}
                    <MenuItem value="">
                      <Box component="span" sx={{ color: 'text.secondary' }}>
                        Not filed
                      </Box>
                    </MenuItem>
                    {(suites.data ?? []).map((suite) => (
                      <MenuItem key={suite.id} value={suite.id}>
                        <Box component="span" sx={{ pl: (suite.path.split('.').length - 1) * 1.5 }}>
                          {suite.name}
                        </Box>
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
              <Controller
                control={control}
                name="tags"
                render={({ field, fieldState }) => (
                  <Autocomplete
                    multiple
                    freeSolo
                    // No options list. Tags are free text with no vocabulary anywhere — there is
                    // no endpoint that lists the ones in use, and offering a curated set is
                    // exactly what left `shared_step` empty (authoring-rework decision 2). A
                    // suggestion source is a slice of its own, and inventing one here would be a
                    // second, drifting answer to "what tags does this project use".
                    options={[]}
                    value={field.value}
                    onChange={(_event, value) =>
                      // Trimmed and de-duplicated as they are entered, because `case_tag`'s key
                      // says a tag list is a set: `smoke` typed twice is one chip, not an error
                      // about a chip the author cannot see twice.
                      field.onChange([
                        ...new Set(value.map((tag) => tag.trim()).filter((tag) => tag !== '')),
                      ])
                    }
                    onBlur={field.onBlur}
                    renderValue={(values, getItemProps) =>
                      values.map((tag, index) => {
                        const { key, ...rest } = getItemProps({ index });
                        return <Chip key={key} {...rest} size="small" label={tag} />;
                      })
                    }
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label="Tags"
                        error={!!fieldState.error}
                        helperText={
                          fieldState.error?.message ??
                          'Press Enter after each. A suite claiming one of these holds this case.'
                        }
                        slotProps={{
                          // Merged into the params MUI already built rather than replacing them:
                          // Autocomplete puts its own ref and ARIA wiring on the input, and a
                          // fresh object here silently unbinds the popup from the field.
                          htmlInput: {
                            ...params.slotProps?.htmlInput,
                            'data-testid': `${TestIdPrefix}.Field.Tags`,
                          },
                          formHelperText: { role: fieldState.error ? 'alert' : undefined },
                        }}
                      />
                    )}
                  />
                )}
              />
            </Panel>

            <Panel title="Classification" testId={`${TestIdPrefix}.Classification`}>
              <Stack sx={{ gap: 2 }}>
                <Controller
                  control={control}
                  name="priority"
                  render={({ field }) => (
                    <TextField
                      select
                      label="Priority"
                      fullWidth
                      value={field.value}
                      onChange={field.onChange}
                      slotProps={{
                        select: { SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.Priority`) },
                      }}
                    >
                      {Object.entries(priorityLabels).map(([value, label]) => (
                        <MenuItem key={value} value={value}>
                          {label}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
                <TextField
                  label="Estimated duration (seconds)"
                  type="number"
                  fullWidth
                  error={!!errors.estimatedDurationSec}
                  helperText={errors.estimatedDurationSec?.message}
                  slotProps={{
                    htmlInput: { 'data-testid': `${TestIdPrefix}.Field.EstimatedDuration`, min: 0 },
                    formHelperText: { role: errors.estimatedDurationSec ? 'alert' : undefined },
                  }}
                  {...register('estimatedDurationSec')}
                />
                {/*
                  Every dimension the project offers, editable — Test Type, Test Level, Execution
                  Phase and the Feature tree. This was read-only for the whole life of the module,
                  on the stated grounds that a flat select "would let someone set a parent where a
                  leaf is required": there is no leaf-only rule anywhere in the specs, and the real
                  reason a select is wrong is that it loses the parentage. Hence a tree.

                  The control renders from `cardinality` and `hierarchical` rather than from the
                  dimension key, so a client that adds a fifth dimension gets a usable control
                  without this file changing.
                */}
                <Controller
                  control={control}
                  name="classificationValueIds"
                  render={({ field }) => (
                    <DataRegion
                      state={dimensions}
                      // No `isEmpty`: a project with no dimensions is the picker's own message, not
                      // DataRegion's empty state. The distinction matters because the empty state
                      // offers a call to action and there is nothing here for the author to do.
                      // The region is scoped to this panel rather than the form, so a failed
                      // vocabulary read leaves the rest of the editor saveable — classification is
                      // optional, and blocking the whole editor on it would turn one outage into
                      // "you cannot edit test cases".
                      testId={`${TestIdPrefix}.ClassificationRegion`}
                    >
                      {(loaded) => (
                        <ClassificationPicker
                          dimensions={loaded}
                          selected={field.value}
                          onChange={field.onChange}
                          testIdPrefix={TestIdPrefix}
                        />
                      )}
                    </DataRegion>
                  )}
                />
              </Stack>
            </Panel>

            {/*
              Conditions sit between Classification and Automation deliberately: they are part of
              what the case IS to a person running it — the setup they must have done before the
              first step — where automation is about how it is executed. Placing them below the
              step list would put the setup after the procedure that assumes it.
            */}
            <Panel title="Conditions" testId={`${TestIdPrefix}.Conditions`}>
              <Stack sx={{ gap: 3 }}>
                <Controller
                  control={control}
                  name="preconditionIds"
                  render={({ field }) => (
                    <ConditionsPanel
                      kind="PRE"
                      projectId={projectId}
                      testId={`${TestIdPrefix}.Preconditions`}
                      value={field.value}
                      inherited={inheritedPre}
                      onChange={field.onChange}
                      known={createdConditions}
                      onCreate={() => setConditionDialog({ kind: 'PRE', condition: null })}
                      onEdit={(condition) => setConditionDialog({ kind: 'PRE', condition })}
                    />
                  )}
                />
                <Controller
                  control={control}
                  name="postconditionIds"
                  render={({ field }) => (
                    <ConditionsPanel
                      kind="POST"
                      projectId={projectId}
                      testId={`${TestIdPrefix}.Postconditions`}
                      value={field.value}
                      inherited={inheritedPost}
                      onChange={field.onChange}
                      known={createdConditions}
                      onCreate={() => setConditionDialog({ kind: 'POST', condition: null })}
                      onEdit={(condition) => setConditionDialog({ kind: 'POST', condition })}
                    />
                  )}
                />
              </Stack>
            </Panel>

            <Panel title="Automation" testId={`${TestIdPrefix}.Automation`}>
              <Stack sx={{ gap: 2 }}>
                <Controller
                  control={control}
                  name="automationStatus"
                  render={({ field }) => (
                    <TextField
                      select
                      label="Automation status"
                      fullWidth
                      value={field.value}
                      onChange={field.onChange}
                      slotProps={{
                        select: { SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.AutomationStatus`) },
                      }}
                    >
                      {Object.entries(automationLabels).map(([value, label]) => (
                        <MenuItem key={value} value={value}>
                          {label}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />

                {/* These two fields appear only when their status makes them meaningful, and
                    each is required by the schema in exactly that case. */}
                {automationStatus === 'AUTOMATED' && (
                  <TextField
                    label="Automated test"
                    fullWidth
                    error={!!errors.automationKey}
                    helperText={
                      errors.automationKey?.message ??
                      'Fully-qualified test method. Ingested results match on this.'
                    }
                    slotProps={{
                      htmlInput: {
                        'data-testid': `${TestIdPrefix}.Field.AutomationKey`,
                        style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem' },
                      },
                      formHelperText: { role: errors.automationKey ? 'alert' : undefined },
                    }}
                    {...register('automationKey')}
                  />
                )}

                {automationStatus === 'NOT_AUTOMATABLE' && (
                  <TextField
                    label="Why it can't be automated"
                    fullWidth
                    multiline
                    minRows={2}
                    error={!!errors.notAutomatableReason}
                    helperText={
                      errors.notAutomatableReason?.message ??
                      'Recorded so the automation-coverage figure stays honest.'
                    }
                    slotProps={{
                      htmlInput: { 'data-testid': `${TestIdPrefix}.Field.NotAutomatableReason` },
                      formHelperText: { role: errors.notAutomatableReason ? 'alert' : undefined },
                    }}
                    {...register('notAutomatableReason')}
                  />
                )}
              </Stack>
            </Panel>

            {mode === 'edit' && (
              <Alert severity="info" data-testid={`${TestIdPrefix}.StatusNotice`}>
                <Typography variant="body2">
                  Review status is changed through the review actions, not here — approving a case
                  records who approved it and when.
                </Typography>
              </Alert>
            )}
          </Stack>
        </Box>
      </Stack>

      {/*
        Mounted unconditionally and controlled by `open`, rather than rendered only when open:
        MUI keeps the dialog out of the tree until it opens anyway, and an unmounted component
        would re-run the library query from scratch on every open instead of reading the cache.
      */}
      <SharedStepPicker
        projectId={projectId}
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        onPick={(shared) => {
          // The title, not the body — see SharedStepPicker. `id` is deliberately absent: this is
          // a new step, and sending an id the case does not own is refused by the server.
          //
          // Inserted BEFORE the trailing blank row rather than appended after it (§94): appending
          // would leave the invitation stranded in the middle of the list, reading as an empty
          // step somebody forgot to fill in.
          const rows = getValues('steps');
          const last = rows.at(-1);
          const at = last && isBlankStep(last) ? rows.length - 1 : rows.length;
          steps.insert(
            at,
            { action: shared.title, testData: '', expectedResult: '', sharedStepId: shared.id },
            NO_FOCUS,
          );
          ensureTrailingBlank();
        }}
      />

      {/*
        Keyed on the entry (or 'new'), which is what makes the dialog's own state reset between
        opens: it seeds its fields from props in `useState`, so without a key, editing one
        condition and then another would show the first one's text in the second one's form.
        Rendered only when open for the same reason — there is no cache here to preserve.
      */}
      {conditionDialog && (
        <ConditionEditDialog
          key={conditionDialog.condition?.id ?? 'new'}
          projectId={projectId}
          open
          condition={conditionDialog.condition}
          defaultType={conditionDialog.kind}
          onClose={() => setConditionDialog(null)}
          onCreated={(created) => {
            // Attached to the list it was created from. Creating an entry and leaving the author to
            // find it in the picker they just left is the kind of half-finished flow `ui-rules`
            // calls a workflow defect — they asked for a condition HERE.
            setCreatedConditions((current) => [...current, created]);
            const field = conditionDialog.kind === 'PRE' ? 'preconditionIds' : 'postconditionIds';
            setValue(field, [...getValues(field), created.id], { shouldDirty: true });
          }}
        />
      )}
    </Box>
  );
}
