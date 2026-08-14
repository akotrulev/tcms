/**
 * The case editor's Preconditions / Postconditions panel (§95).
 *
 * ## One component, two kinds — and no second implementation
 *
 * `kind` is the only difference between the two panels. A precondition and a postcondition are the
 * same object in a different list, so two components would be `ui-rules` §8's "hundred slightly
 * different pages" in miniature: two attach controls, two reorder implementations, two impact
 * confirms, drifting apart on the first change to either.
 *
 * ## An attachment is a REFERENCE, which is the whole design and the whole hazard
 *
 * Picking an entry stores its **id**. Editing the entry later changes every case and suite
 * attaching it, with no prompt and no fork — the deliberate opposite of §94's step corpus, where a
 * pick copies text and creates no link. That is why the edit affordance here opens a dialog that
 * states the blast radius first (`ConditionEditDialog`), and why the panel reads `usageCount` and
 * `suiteUsageCount` off the entry rather than computing anything.
 *
 * ## Inherited rows are shown and NOT editable here — but they can be DECLINED (§187)
 *
 * A folder's conditions reach every case beneath it. They are rendered — an author needs to know
 * the case already has "Log in as standard user" before attaching a second copy — but they carry no
 * remove, no reorder and no drag handle, because they are not this case's to move. The form binds
 * only the case's own ids; `ownConditionIds` in the editor is where inherited rows are dropped.
 *
 * What they do carry is §6f's opt-out toggle. A standing rule always has the one case it is wrong
 * for — the case that tests the login screen cannot require being logged in — and without the
 * toggle the author's only ways out are moving the case out of the folder it belongs in or deleting
 * the rule for two hundred siblings. Declining writes an attachment id into `declinedConditionIds`;
 * it changes nothing about the folder.
 *
 * **A declined row stays in this list, greyed rather than removed.** It is the only control that
 * can put the rule back, so removing it on decline would make the action one-way from this screen —
 * which is exactly the state the server was in before §187, one layer down. The declined rows the
 * server serves separately are appended at the end of the group rather than interleaved: their
 * §6f order is resolved server-side over the suite chain and re-deriving it here would be the
 * second implementation `CaseDetailPage` refuses to write for the same list.
 *
 * ## `leavingFolder` — the toggle the picker has already decided (§194)
 *
 * This whole group belongs to the folder the case is filed in *now*. Move the Suite picker and the
 * rows go on rendering the folder being left, which is correct until Save — but the switches stop
 * meaning anything, because §192 ends every exception the destination does not impose. See the
 * prop's own comment for why that is a defect and not a nicety.
 *
 * **What this panel deliberately does NOT do is show the destination's rules instead.** They are on
 * the wire — `Suite.preconditions` / `.postconditions` carry the same `suite_condition` ids in the
 * same `CaseConditionRef` shape, on a list the editor already holds — but assembling them into
 * "what this case will inherit" means deduping against the case's own attachments, applying §6f's
 * resolution order and dropping retired entries. That is the second implementation of the case
 * endpoint's resolution the paragraph above refuses for a smaller list, and it would be built out
 * of a bound (the suite list omits retired entries) that is not the one retention uses.
 */
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { restrictToVerticalAxis } from '@dnd-kit/modifiers';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import AddIcon from '@mui/icons-material/Add';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import DeleteIcon from '@mui/icons-material/Delete';
import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import EditIcon from '@mui/icons-material/Edit';
import {
  Alert,
  Autocomplete,
  Box,
  Chip,
  FormControlLabel,
  IconButton,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useConditions } from '../../api/queries';
import type { CaseConditionRef, Condition } from '../../api/types';
import { impactSummary } from '../../lib/conditions';
import { resolveStepMove } from './stepReorder';

export type ConditionKind = 'PRE' | 'POST';

const LABELS = {
  PRE: { title: 'Preconditions', attach: 'Attach a precondition' },
  POST: { title: 'Postconditions', attach: 'Attach a postcondition' },
} satisfies Record<ConditionKind, { title: string; attach: string }>;

function SortableCondition({
  id,
  children,
}: {
  id: string;
  children: (handle: {
    attributes: ReturnType<typeof useSortable>['attributes'];
    listeners: ReturnType<typeof useSortable>['listeners'];
  }) => ReactNode;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
  });

  return (
    <Box
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      sx={{
        p: 1,
        border: '1px solid',
        borderColor: isDragging ? 'primary.main' : 'divider',
        borderRadius: 1,
        // Lifted rather than hidden, for `SortableStep`'s reason: this list is the content the
        // author is reading, and a gap makes the drop target ambiguous.
        bgcolor: 'background.paper',
        opacity: isDragging ? 0.85 : 1,
        position: 'relative',
      }}
    >
      {children({ attributes, listeners })}
    </Box>
  );
}

export function ConditionsPanel({
  kind,
  projectId,
  testId,
  value,
  inherited,
  declined = [],
  leavingFolder = false,
  onToggleDecline,
  onChange,
  onCreate,
  onEdit,
  known,
  disabled = false,
}: {
  kind: ConditionKind;
  projectId: string;
  testId: string;
  /** The case's OWN attached ids, in order. */
  value: string[];
  /**
   * Every attachment the folder reaches this case with, declined or not — the live ones in the
   * server's §6f order, then the declined ones. Computed once in the editor from two response
   * fields and NOT re-derived here as the toggle is used, so a row never moves when it greys out.
   */
  inherited: CaseConditionRef[];
  /**
   * Which of those the form currently declines — attachment ids, form state and not the server's.
   *
   * Optional, and the default is the honest one rather than a convenience: `SuiteDialogs` renders
   * this panel for a SUITE's own conditions, which inherit from nothing at all (`inherited` is
   * `[]` there), so a suite has no attachment it could decline and no toggle to hand it. Requiring
   * the props would make that caller pass an empty array and a function that can never be called.
   */
  declined?: readonly string[];
  /**
   * The form has moved the case to a different folder, so **every toggle here is inert** until the
   * save lands (§194).
   *
   * Separate from `disabled`, which turns off the whole panel. The case's own attachments are
   * unaffected by a move and stay fully editable; what a move invalidates is the *inherited* group
   * and the exceptions taken against it. The rows keep rendering because they are still true — the
   * case is still filed where it was until Save — and greying only the switch says which half of
   * the panel the picker has already decided.
   *
   * It is not a courtesy. `CaseEdit.normalizeDeclines` bounds the submitted set by what the
   * DESTINATION folder imposes, and since §98 a suite is flat, so two folders share no attachment
   * and the intersection is empty: a decline switched on here would be accepted by validation and
   * then dropped by the same request. A control whose effect the save silently discards is worse
   * than no control.
   */
  leavingFolder?: boolean;
  onToggleDecline?: (attachmentId: string, next: boolean) => void;
  onChange: (next: string[]) => void;
  onCreate: () => void;
  onEdit: (condition: Condition) => void;
  /**
   * Entries this panel must be able to render even though the library read has not caught up —
   * today, the one the author just created.
   *
   * Without it there is a real gap, not a theoretical one: a create attaches the new id
   * immediately, while the cached library lists are keyed by search term and are all stale until
   * they refetch. The row in between resolves to nothing and renders "Unknown condition" — the
   * author's own condition, reported as missing, on the screen they created it from.
   */
  known?: readonly Condition[];
  disabled?: boolean;
}) {
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');

  /*
    The same 250ms debounce `StepActionField` uses, inlined for the same reason it is there: this
    project has no shared debounce hook, and extracting one would move §94's code in a slice that
    is not about it. A request per keystroke against the project's library is what it prevents.
  */
  useEffect(() => {
    if (query === debounced) return;
    const id = setTimeout(() => setDebounced(query), 250);
    return () => clearTimeout(id);
  }, [query, debounced]);

  // `type` narrows to the position being filled — and a BOTH entry matches both, because BOTH
  // means "usable in either position" rather than a third role. Narrowing client-side instead
  // would be a second copy of that rule.
  const library = useConditions(projectId, debounced, kind);
  const entries = useMemo(() => library.data ?? [], [library.data]);

  /*
    Resolved for rendering the attached ROWS, and a separate read on purpose: the picker's list is
    narrowed by the query, so it cannot be the source for a row whose entry the current query
    excludes — the row would blank out as soon as the author typed in the box above it.

    Gated on there being a row to resolve. A new case has none, and both panels mount on every
    editor render, so an ungated version fires two reads of the whole library on a screen that has
    not yet asked a question of it.
  */
  const resolved = useConditions(projectId, '', undefined, value.length > 0);
  const byId = useMemo(() => {
    const map = new Map<string, Condition>();
    for (const entry of resolved.data ?? []) map.set(entry.id, entry);
    for (const entry of entries) map.set(entry.id, entry);
    // Last, so a freshly created entry wins over a stale cached copy of the same id.
    for (const entry of known ?? []) map.set(entry.id, entry);
    return map;
  }, [resolved.data, entries, known]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const move = (from: number, to: number) => {
    const next = [...value];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    onChange(next);
  };

  const onDragEnd = (event: DragEndEvent) => {
    // The same resolver the step list uses — the three ways a drag ends in nothing (outside the
    // list, back where it started, on a stale id) are the only part of this that can be WRONG, and
    // they are unreachable from a component test.
    const resolvedMove = resolveStepMove(value, event.active.id, event.over?.id);
    if (resolvedMove) move(resolvedMove.from, resolvedMove.to);
  };

  // Already attached, so it must not be offered again — attaching the same entry twice in one list
  // is a row with no meaning, and the server collapses it silently. Removing it from the picker is
  // the honest version of that: the author never makes the mistake.
  const options = entries.filter((entry) => !value.includes(entry.id));

  return (
    <Stack spacing={1} data-testid={testId}>
      <Typography variant="subtitle2">{LABELS[kind].title}</Typography>

      {inherited.length > 0 && (
        <Stack spacing={0.5} data-testid={`${testId}.Inherited`}>
          {inherited.map((ref) => {
            const isDeclined = declined.includes(ref.id);
            return (
              <Box
                key={ref.id}
                data-testid={`${testId}.Inherited.Item`}
                data-declined={isDeclined ? 'true' : 'false'}
                sx={{ p: 1, border: '1px dashed', borderColor: 'divider', borderRadius: 1 }}
              >
                <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
                  <Typography
                    variant="body2"
                    sx={
                      isDeclined
                        ? { textDecoration: 'line-through', color: 'text.disabled' }
                        : undefined
                    }
                  >
                    {ref.title}
                  </Typography>
                  {/* Named, not just marked: "inherited" alone leaves the author hunting for which
                      folder to change. */}
                  <Chip
                    size="small"
                    variant="outlined"
                    label={ref.inheritedFrom ? `From ${ref.inheritedFrom}` : 'Inherited'}
                  />

                  {/*
                    §6f's opt-out, and it is a switch rather than a Remove button on purpose: this
                    row is not the case's to remove, and a control spelled "remove" beside a
                    "From Billing" chip is one an author reasonably reads as deleting the folder's
                    rule for all two hundred siblings.
                  */}
                  <Tooltip
                    title={
                      leavingFolder
                        ? `This case is moving out of ${ref.inheritedFrom ?? 'this folder'}, so `
                          + 'the rule stops reaching it either way. Put it back where it was to '
                          + 'change this.'
                        : isDeclined
                          ? `This case ignores the ${ref.inheritedFrom ?? 'folder'} rule. `
                            + 'Switch it back on to apply it again.'
                          : `Turn this off for THIS CASE only. The ${ref.inheritedFrom ?? 'folder'} `
                            + 'rule keeps applying to every other case beneath it.'
                    }
                  >
                    <FormControlLabel
                      sx={{ ml: 'auto', mr: 0 }}
                      data-testid={`${testId}.DeclineToggle`}
                      control={
                        <Switch
                          size="small"
                          checked={!isDeclined}
                          disabled={disabled || leavingFolder}
                          // The accessible name IS the handle a test uses — `getByRole('switch',
                          // { name })` — because MUI v7's Switch input slot types reject an
                          // arbitrary data attribute, and a label the screen reader reads is the
                          // better selector anyway.
                          slotProps={{
                            input: { 'aria-label': `Applies to this case: ${ref.title}` },
                          }}
                          onChange={(event) => onToggleDecline?.(ref.id, !event.target.checked)}
                        />
                      }
                      label={
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          {isDeclined ? 'Not applicable' : 'Applies'}
                        </Typography>
                      }
                    />
                  </Tooltip>
                </Stack>
              </Box>
            );
          })}
        </Stack>
      )}

      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        modifiers={[restrictToVerticalAxis]}
        onDragEnd={onDragEnd}
      >
        <SortableContext items={value} strategy={verticalListSortingStrategy}>
          <Stack spacing={0.5}>
            {value.map((conditionId, index) => {
              const entry = byId.get(conditionId);
              return (
                <SortableCondition key={conditionId} id={conditionId}>
                  {({ attributes, listeners }) => (
                    <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
                      <IconButton
                        size="small"
                        aria-label="Reorder"
                        data-testid={`${testId}.Drag`}
                        disabled={disabled}
                        {...attributes}
                        {...listeners}
                        sx={{ cursor: 'grab' }}
                      >
                        <DragIndicatorIcon fontSize="small" />
                      </IconButton>

                      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                        <Typography variant="body2" noWrap>
                          {/* A library read in flight is not an empty condition. Falling back to
                              the id would render a UUID at the author; naming the state is the
                              honest rendering of "we have the attachment, not yet the text". */}
                          {entry?.title ?? (resolved.isPending ? 'Loading…' : 'Unknown condition')}
                        </Typography>
                      </Box>

                      {entry && (
                        <Tooltip title="Edit — this changes every case using it">
                          <span>
                            <IconButton
                              size="small"
                              aria-label={`Edit ${entry.title}`}
                              data-testid={`${testId}.Edit`}
                              disabled={disabled}
                              onClick={() => onEdit(entry)}
                            >
                              <EditIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                      )}

                      {/* KEPT alongside the drag handle — the keyboard, screen-reader and touch
                          path, and the only way to move a row by exactly one place deliberately.
                          §94's rule, and both ends call the same `move`. */}
                      <IconButton
                        size="small"
                        aria-label="Move up"
                        data-testid={`${testId}.Up`}
                        disabled={disabled || index === 0}
                        onClick={() => move(index, index - 1)}
                      >
                        <ArrowUpwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        aria-label="Move down"
                        data-testid={`${testId}.Down`}
                        disabled={disabled || index === value.length - 1}
                        onClick={() => move(index, index + 1)}
                      >
                        <ArrowDownwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        aria-label="Detach"
                        data-testid={`${testId}.Remove`}
                        disabled={disabled}
                        onClick={() => onChange(value.filter((id) => id !== conditionId))}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Stack>
                  )}
                </SortableCondition>
              );
            })}
          </Stack>
        </SortableContext>
      </DndContext>

      {library.isError && (
        // Scoped to this panel: a failed library read must not stop the rest of the case saving.
        <Alert severity="warning" data-testid={`${testId}.Error`}>
          The condition library did not load, so nothing can be attached right now.
        </Alert>
      )}

      <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
        <Autocomplete
          sx={{ flexGrow: 1 }}
          disabled={disabled}
          options={options}
          loading={library.isFetching}
          getOptionLabel={(option) => option.title}
          // Controlled to null so the box empties after a pick — it is an "attach one more"
          // control, not a field holding a value.
          value={null}
          inputValue={query}
          onInputChange={(_, next) => setQuery(next)}
          onChange={(_, picked) => {
            if (!picked) return;
            onChange([...value, picked.id]);
            setQuery('');
          }}
          renderOption={(props, option) => (
            <li {...props} key={option.id}>
              <Stack>
                <Typography variant="body2">{option.title}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {impactSummary(option)}
                </Typography>
              </Stack>
            </li>
          )}
          renderInput={(params) => (
            <TextField
              {...params}
              size="small"
              label={LABELS[kind].attach}
              slotProps={{
                ...params.slotProps,
                htmlInput: {
                  ...params.slotProps?.htmlInput,
                  'data-testid': `${testId}.Attach`,
                },
              }}
            />
          )}
        />
        <Tooltip title="Create a new library condition">
          <span>
            <IconButton
              aria-label="Create a condition"
              data-testid={`${testId}.Create`}
              disabled={disabled}
              onClick={onCreate}
            >
              <AddIcon />
            </IconButton>
          </span>
        </Tooltip>
      </Stack>
    </Stack>
  );
}
