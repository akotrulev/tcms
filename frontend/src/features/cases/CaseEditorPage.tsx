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
  AlertTitle,
  Autocomplete,
  Box,
  Button,
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
import { useSnackbar } from 'notistack';
import { useEffect, useState, type ReactNode } from 'react';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { Controller, useFieldArray, useForm, type Control } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { CaseSchema, fromCasePayload, isBlankStep, toCasePayload, type CaseInput } from './caseSchema';
import { CaseDraftStatus } from './CaseDraftStatus';
import { useNewCaseDraft } from './useNewCaseDraft';
import type { CaseDraft } from '../../api/types';
import { ClassificationPicker } from './ClassificationPicker';
import { ConditionEditDialog } from './ConditionEditDialog';
import { ConditionsPanel } from './ConditionsPanel';
import { resolveStepMove } from './stepReorder';
import {
  useCase,
  useCaseDraft,
  useClassification,
  useCreateCase,
  useDiscardCaseDraft,
  useProjectTags,
  useSaveCaseDraft,
  useStepSuggestions,
  useSuites,
  useUpdateCase,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { NEW_CASE_DEFAULTS } from '../../lib/caseDefaults';
import { formatAge, plural } from '../../lib/format';
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
 * A pick fills the three fields and creates no link, and the corpus carries no ids at all, so it
 * cannot accidentally become a reference. Since §239 it is the ONLY step-reuse affordance: the
 * shared-step library beside it, which set `sharedStepId` and claimed the step tracked a library
 * entry, was retired because nothing ever resolved the entry — see the module header.
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
    The tags this project's cases already carry — a SUGGESTION source, never a vocabulary.

    Optional by construction: the control is `freeSolo`, so a failed or still-loading read leaves an
    empty option list and the author types the tag exactly as they did before. That is the whole
    reason this is not gated on `isSuccess` — a picker that refused unlisted tags could never accept
    the first use of a new one, which is what `case_tag` being free text means.
  */
  const projectTags = useProjectTags(projectId);

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

  /*
    The folder's conditions, rendered read-only beside the case's own. Filtered here rather than in
    the panel so the panel takes exactly what it renders and nothing that needs interpreting.

    DECLINED ROWS ARE CONCATENATED ON (§187), and the order is deliberate. The server serves the two
    lists separately because `preconditions` is the procedure a run pins, so the union has to be made
    somewhere — and it is made HERE, once per response, rather than inside the panel as the toggle is
    used. Recomputing it on every toggle would reorder the list under the author's cursor: a row they
    just switched off would jump to the end of the group, which reads as the control having deleted
    it. Declined rows sit last because their §6f position is resolved over the suite chain
    server-side, and interleaving them would be the second implementation of that order the shared
    layer exists to prevent.
  */
  const inheritedPre = [
    ...(existing?.preconditions ?? []).filter((ref) => ref.inherited),
    ...(existing?.declinedPreconditions ?? []),
  ];
  const inheritedPost = [
    ...(existing?.postconditions ?? []).filter((ref) => ref.inherited),
    ...(existing?.declinedPostconditions ?? []),
  ];

  const createCase = useCreateCase(projectId);
  const updateCase = useUpdateCase(projectId, existing?.id ?? '');

  const {
    control,
    register,
    handleSubmit,
    getValues,
    reset,
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
      // The three §144 fields default from `NEW_CASE_DEFAULTS`, which the plan tab's title-only
      // create sends verbatim: two screens author a case from nothing, and one answer to what it
      // says before the author does (§199).
      priority: existing?.priority ?? NEW_CASE_DEFAULTS.priority,
      automationStatus: existing?.automationStatus ?? NEW_CASE_DEFAULTS.automationStatus,
      automationKey: existing?.automationKey ?? '',
      notAutomatableReason: existing?.notAutomatableReason ?? '',
      estimatedDurationSec:
        existing?.estimatedDurationSec === null || existing?.estimatedDurationSec === undefined
          ? ''
          : String(existing.estimatedDurationSec),
      stepFormat: existing?.stepFormat ?? NEW_CASE_DEFAULTS.stepFormat,
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
      // The attachment ids the server says this case declines (§187) — BOTH kinds in one array,
      // because an attachment id already determines its kind. Seeded from the two dedicated
      // response fields rather than from `preconditions`, which by construction never contains a
      // declined row: that is the whole reason those fields exist.
      declinedConditionIds: [
        ...(existing?.declinedPreconditions ?? []).map((ref) => ref.id),
        ...(existing?.declinedPostconditions ?? []).map((ref) => ref.id),
      ],
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

  const stepFormat = watch('stepFormat');
  const automationStatus = watch('automationStatus');

  /*
    ================== The move this screen used to make quietly (§194) ==================

    True while the Suite picker names a folder the case is not in yet — the editor's one preview
    moment, and the analogue of the bulk dialog's dry run (§193). Until Save, the case is still
    where it was and the Conditions panel below is still telling the truth; what has already been
    decided is that every exception it holds ends.

    `?? ''` on both sides, matching the picker's own encoding: '' is UNFILED and not "missing"
    (§96), so a case filed nowhere compares equal to itself instead of reading as a move on mount.

    THE PREDICTION THIS MAKES IS BOUNDED BY §98, and that is written down rather than assumed. A
    move ends every stored decline because a decline names a `suite_condition` row, a suite is flat,
    and two folders therefore share no attachment — `CaseConditions#ancestry` says a widening of
    inheritance "is a decision with a slice behind it", and this is one of the places such a slice
    has to come. The snackbar on the way out does NOT share the assumption: it reads what the saved
    case no longer carries. So the record of what happened stays true under a widening while the
    warning becomes the thing to fix, which is the right way round for the two to fail.
  */
  const movingFolder = mode === 'edit' && !!existing && (existing.suiteId ?? '') !== watch('suiteId');

  /*
    ============================ The autosave buffer (§128, D1) ============================

    `test_case_draft` is a real table with a real writer, so the editor stops being a screen that
    loses everything when a tab closes.

    TWO BUFFERS, one per mode, behind one banner and one status line:

      * EDIT — the server's, keyed `(case_id, user_id)` since `V64` (audit D1). It is yours, a
        colleague's save no longer clears it, and what their save does instead is set `stale`.
      * CREATE — the browser's. `test_case_draft.case_id` is a foreign key to `test_case`, so a case
        being created still has nothing to key a server row on; §128 stated that and stopped there,
        leaving the New Case form lossy. `useNewCaseDraft`'s header says why a `localStorage` buffer
        is the right shape here rather than a smaller version of the server one.

    The two share `useCaseAutosave`, the restore banner and `toCasePayload`/`fromCasePayload` — the
    same argument that put the mapping in `caseSchema` in the first place. A second copy of the
    restore flow for create mode is how a field wired into one buffer and forgotten in the other
    comes back empty.
  */
  const draftQuery = useCaseDraft(projectId, mode === 'edit' ? existing?.id : undefined);
  const saveDraft = useSaveCaseDraft(projectId, existing?.id ?? '');
  const discardDraft = useDiscardCaseDraft(projectId, existing?.id ?? '');
  const newCaseDraft = useNewCaseDraft(projectId, mode === 'create');

  /*
    Whether the restore offer has been answered. `false` until the author accepts or dismisses it —
    and it also becomes true on its own the moment the query resolves to no draft, which is the
    ordinary case and must not require a decision nobody was asked for.

    THIS GATES THE AUTOSAVE, and `useCaseAutosave`'s header spells out the five-step sequence that
    happens without it: the first keystroke overwrites the very draft the editor is about to offer,
    so the user is shown a restore prompt for work that no longer exists, and accepting it hands
    back what they had already saved. Nothing errors. Nothing is logged.
  */
  const [restoreDecided, setRestoreDecided] = useState(false);
  /*
    One offer, from whichever buffer this mode has. The create-mode draft carries no `lockVersion`
    and is never `stale` — there is no saved case for it to be stale against, which is the one real
    difference between the two and is expressed here rather than by a second banner.
  */
  const offeredDraft:
    | { updatedAt: string; stale: boolean; content: CaseDraft['content'] }
    | null =
    mode === 'create'
      ? newCaseDraft.offered
        ? {
            updatedAt: newCaseDraft.offered.updatedAt,
            stale: false,
            content: newCaseDraft.offered.content as CaseDraft['content'],
          }
        : null
      : (draftQuery.data ?? null);
  const promptOpen = !restoreDecided && offeredDraft !== null;
  // The browser's buffer is read synchronously on mount, so create mode has nothing to wait for.
  const draftSettled = mode === 'create' || draftQuery.isSuccess || draftQuery.isError;

  /*
    A failed draft read resolves the gate too, and that is a deliberate call rather than an
    oversight. The alternative is an editor whose autosave never starts because one GET failed —
    silently, since there is nothing on screen that could say so — which trades a small risk of
    overwriting a draft we could not read for a certainty of buffering nothing at all for the rest
    of the session. The status line below still reports what the buffer is doing.
  */
  const autosaveEnabled =
    (mode === 'create' || !!existing) &&
    draftSettled &&
    !promptOpen &&
    isDirty &&
    !isSubmitting;

  /*
    The autosave's whole-form subscription lives in `CaseDraftStatus`, NOT here — `const values =
    watch()` on this component re-rendered the entire editor on every keystroke (21 renders for 20
    characters, ~215 ms each), which is what pushed this screen's tests past vitest's per-test
    budget on CI. That file's header has the measurements.
  */
  const saveDraftContent = (content: Record<string, unknown>) =>
    mode === 'create'
      ? newCaseDraft.save(content)
      : saveDraft.mutateAsync({ lockVersion: existing?.lockVersion ?? 0, content });

  /**
   * Puts the buffered draft back into the form.
   *
   * `reset` rather than field-by-field `setValue`, because the draft is the whole form: setting
   * the fields it carries would leave the fields it does not at whatever the saved case said,
   * producing a hybrid that is neither the draft nor the case and that the author cannot tell
   * apart from either.
   *
   * `keepDefaultValues`, and it is NOT `keepDirty` — which was the first draft of this line and is
   * the wrong option in a way that reads as right. `keepDirty` preserves the dirty *flag* as it
   * currently stands, and at this moment it stands at **false**: the offer is answered on mount,
   * before the author has typed anything. So the restored draft would land in a form the editor
   * considers pristine — Save disabled with "Nothing has changed yet" on a form full of unsaved
   * work, and the autosave gated off with it.
   *
   * `keepDefaultValues` keeps the *saved case* as the baseline and re-evaluates dirtiness against
   * it, which is the question actually being asked: the restored draft differs from what the server
   * has stored, so it is dirty, and it stops being dirty if the author edits it back.
   */
  const restoreDraft = () => {
    if (offeredDraft) reset(fromCasePayload(offeredDraft.content), { keepDefaultValues: true });
    setRestoreDecided(true);
  };

  const dismissDraft = () => {
    // Discards the row as well as the offer. Keeping it would re-offer the same draft on the next
    // mount, after the author has already said no — and the autosave is about to overwrite it
    // anyway, so leaving it would be a promise to keep something we are about to replace.
    if (mode === 'create') newCaseDraft.discard();
    else discardDraft.mutate();
    setRestoreDecided(true);
  };

  const onSubmit = handleSubmit(async (data) => {
    // `toCasePayload` is caseSchema's, shared with the autosave below. Two hand-written copies of
    // this mapping is how a field wired into Save and forgotten in the buffer restores as empty —
    // see the function's header.
    const payload = toCasePayload(data);

    try {
      if (mode === 'edit' && existing) {
        // The version we read is sent back, so a concurrent save is rejected rather than
        // silently overwritten.
        const saved = await updateCase.mutateAsync({ ...payload, lockVersion: existing.lockVersion });

        /*
          §194. The declines this save asked to keep that the saved case does not carry — MEASURED
          off the response, not predicted from the move. The form binds the complete set on every
          save, and the response's two declined lists are what survived; the difference is what the
          request ended. Read off `data` rather than `payload`, which is a `Record<string, unknown>`
          — the deduplication `toCasePayload` applies to the same field is a property of the set
          difference here rather than a second copy of the rule.

          Gated on the folder having actually changed, because that is the only operation §192 lets
          end one, and because a response served by anything less than the whole contract shape
          would otherwise read as "the server dropped them all". The gate decides whether the
          question is asked; the response still answers it.

          THE HOLE, stated rather than left to be found: both sides of this subtraction are what a
          list shows, and a decline whose library entry was retired is on no list (§187). Such a
          row ends with the move like any other and is counted by neither term. It is §193's LD-4
          hole arriving on the other path, and closing it needs the server to say what it ended —
          which is exactly the field the bulk result now has and this endpoint does not.
        */
        const kept = new Set(
          [...(saved.declinedPreconditions ?? []), ...(saved.declinedPostconditions ?? [])]
            .map((ref) => ref.id),
        );
        const ended =
          mode === 'edit' && (existing.suiteId ?? '') !== data.suiteId
            ? [...new Set(data.declinedConditionIds)].filter((id) => !kept.has(id))
            : [];

        // One snackbar, composed — `CaseListPage`'s treatment of the same event (§193), and not a
        // success whatever the save otherwise did. It is the only record of it that survives the
        // navigation on the next line.
        enqueueSnackbar(
          ended.length
            ? `Saved ${saved.key} as v${saved.currentVersionNo}`
              + ` · the move ended ${plural(ended.length, 'condition exception')}`
            : `Saved ${saved.key} as v${saved.currentVersionNo}`,
          { variant: ended.length ? 'warning' : 'success' },
        );
        navigate(`/projects/${projectId}/cases/${saved.id}`);
      } else {
        const created = await createCase.mutateAsync(payload);
        // The browser buffer's "and clears the row" (D1) — the same clause V1 writes about the
        // server one, and it fires for the same reason: a buffer that survived its own create would
        // offer, on the next visit to New Case, a copy of a case that now exists. AFTER the await,
        // so a refused create keeps the work rather than clearing it on the way to an error.
        newCaseDraft.discard();
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

      {/*
        The restore offer (§128). Rendered as a banner above the form rather than as a modal,
        deliberately: a modal on mount would block the author from reading the case underneath, and
        the decision it asks for is one that only makes sense next to what is currently on screen —
        "restore this instead of that".

        `severity="info"`, not `warning`: nothing is wrong. There is unsaved work, which is good
        news, and a warning icon on a screen about somebody's own writing reads as a problem with
        it.
      */}
      {promptOpen && offeredDraft && (
        <Alert
          severity="info"
          sx={{ mb: 2 }}
          data-testid={`${TestIdPrefix}.DraftPrompt`}
          action={
            <Stack direction="row" sx={{ gap: 1 }}>
              <Button size="small" onClick={dismissDraft} data-testid={`${TestIdPrefix}.DraftDiscard`}>
                Discard
              </Button>
              <Button
                size="small"
                variant="contained"
                onClick={restoreDraft}
                data-testid={`${TestIdPrefix}.DraftRestore`}
              >
                Restore
              </Button>
            </Stack>
          }
        >
          <AlertTitle>Your unsaved changes from {formatAge(offeredDraft.updatedAt)}</AlertTitle>
          {/*
            ~~WHO typed it, and this is not decoration: there is ONE buffer per case, so the draft
            on offer may be a colleague's.~~ → V64 (audit D1) keys the row on the reader, so it is
            always yours and `updatedBy` left the contract with the hazard it disclosed. What a
            colleague's save produces is the `stale` sentence below — which is the one that was
            doing the work all along.
          */}
          You left work on this case without saving it.
          {offeredDraft.stale && (
            <>
              {' '}
              <strong>
                The case has been saved since this draft was written, so restoring it will discard
                that change.
              </strong>
            </>
          )}
        </Alert>
      )}

      {/* What the buffer is doing. Its own component so the subscription it needs does not
          re-render the editor — see `CaseDraftStatus`. */}
      <CaseDraftStatus
        control={control}
        getValues={getValues}
        enabled={autosaveEnabled}
        save={saveDraftContent}
        commitLabel={mode === 'edit' ? 'Save changes' : 'Create case'}
        testId={`${TestIdPrefix}.DraftStatus`}
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
                  This panel has NO buttons under the list, and both absences are decisions.

                  "Add step" went in §94: the last row is always blank and typing into it appends
                  the next one, so a button that adds an eighth empty row to a list already ending
                  in one is an affordance for something that has already happened.
                  `CaseEditor.Steps.AddButton` is gone with it — a locator, not just a control, so
                  anything driving this screen looks for the blank row instead.

                  "Insert shared step" went in §239, and `CaseEditor.Steps.InsertSharedButton` with
                  it. Step reuse is the corpus in the action field: it needs nobody to populate it,
                  and a pick copies text into THIS case. The library it replaced was the other
                  mechanism half-built — the picker wrote the entry's title into `action` and set a
                  reference nothing on any read path ever resolved.
                */}
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
                    {/*
                      No indentation any more (§98): the depth came from `suite.path`, and a flat
                      suite has no depth to draw. What is left is a plain list of names, sorted the
                      same way the rail sorts them so the picker and the rail read alike.
                    */}
                    {[...(suites.data ?? [])]
                      .sort((a, b) => a.name.localeCompare(b.name) || a.key.localeCompare(b.key))
                      .map((suite) => (
                        <MenuItem key={suite.id} value={suite.id}>
                          {suite.name}
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
                    /*
                      The tags already in use, most-used first — `GET /projects/{id}/tags`.

                      ~~No options list.~~ → **suggested since the tag-reuse slice.** The原 note
                      original note said a curated vocabulary is what left `shared_step` empty, and that is
                      still true and still the reason this list is DERIVED: a tag appears here
                      because a case carries it, so it cannot disagree with the grid, and nobody has
                      to populate it. `freeSolo` stays, so an unlisted tag is still typeable — the
                      endpoint is a picker's list and never a validator's.

                      `filterSelectedOptions` keeps a tag already on this case out of the dropdown;
                      re-picking it is a no-op the `Set` below would swallow silently, which reads
                      as a dead menu row.
                    */
                    options={(projectTags.data ?? []).map((usage) => usage.tag)}
                    filterSelectedOptions
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
                          // `...params.slotProps` FIRST, and this is not tidiness. MUI puts the
                          // selected chips in `slotProps.input.startAdornment` and the field's ref
                          // and ARIA wiring in `slotProps.htmlInput`; an object that merely adds a
                          // testid REPLACES the lot, so every tag is held by the form, sent on
                          // save, and invisible on screen — the author types it, sees nothing, and
                          // types it again. Both gates were green when this was wrong.
                          ...params.slotProps,
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
              {/*
                The decline toggle is bound through its OWN Controller wrapping both panels, rather
                than through each panel's. `declinedConditionIds` is one array carrying both kinds
                (an attachment id determines its kind), so two Controllers over one field would be
                two independent copies of it — and the last panel to fire would overwrite the other
                panel's toggles with a value it read before they happened.
              */}
              <Controller
                control={control}
                name="declinedConditionIds"
                render={({ field: declines }) => {
                  const toggle = (attachmentId: string, next: boolean) =>
                    declines.onChange(
                      next
                        ? [...declines.value, attachmentId]
                        : declines.value.filter((id: string) => id !== attachmentId),
                    );

                  /*
                    The rows the pending move will end (§194) — resolved from what is already on
                    screen, which is why they can be NAMED. `inheritedPre`/`inheritedPost` carry the
                    title the panel renders; a count on its own would leave the author to work out
                    which of four rules they are about to lose.

                    Here rather than in either panel: one array carries both kinds, so a per-panel
                    alert would state the same event twice on a case declining one precondition and
                    one postcondition, and each copy would name half of it.
                  */
                  const ending = movingFolder
                    ? [...inheritedPre, ...inheritedPost].filter((ref) =>
                        declines.value.includes(ref.id),
                      )
                    : [];

                  return (
                    <Stack sx={{ gap: 3 }}>
                      {/*
                        `warning`, on the same grounds §193 raised the bulk preview's alert to it: a
                        skip is something the operation will not do, and this is something it will
                        do that nobody asked for. The consequence sentence is the one the dialog
                        uses, because it is the same consequence.
                      */}
                      {ending.length > 0 && (
                        <Alert
                          severity="warning"
                          data-testid={`${TestIdPrefix}.Conditions.EndingDeclines`}
                        >
                          <AlertTitle>
                            Moving this case ends {plural(ending.length, 'condition exception')}
                          </AlertTitle>
                          <Typography variant="body2">
                            A folder&rsquo;s rule can only be declined by a case filed in that
                            folder, so {ending.length === 1 ? 'this exception' : 'these exceptions'}
                            {' '}end with the move. Moving it back will not bring
                            {ending.length === 1 ? ' it' : ' them'} back.
                          </Typography>
                          <Box component="ul" sx={{ pl: 3, my: 1 }}>
                            {ending.map((ref) => (
                              <Typography component="li" variant="body2" key={ref.id}>
                                {ref.title}
                                {ref.inheritedFrom ? ` — from ${ref.inheritedFrom}` : null}
                              </Typography>
                            ))}
                          </Box>
                        </Alert>
                      )}
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
                            declined={declines.value}
                            leavingFolder={movingFolder}
                            onToggleDecline={toggle}
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
                            declined={declines.value}
                            leavingFolder={movingFolder}
                            onToggleDecline={toggle}
                            onChange={field.onChange}
                            known={createdConditions}
                            onCreate={() => setConditionDialog({ kind: 'POST', condition: null })}
                            onEdit={(condition) => setConditionDialog({ kind: 'POST', condition })}
                          />
                        )}
                      />
                    </Stack>
                  );
                }}
              />
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
