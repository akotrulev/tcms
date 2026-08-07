import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import { Alert, Box, Button, Chip, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  pointerWithin,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { useSnackbar } from 'notistack';
import { useState, type ReactNode } from 'react';
import { useAnnounce } from '../../a11y/useAnnounce';
import { PermissionGate } from '../../components/PermissionGate';
import { TaskCreateDialog } from './TaskCreateDialog';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import type { WorkItem, WorkItemStatus } from '../../api/types';
import { useMoveWorkItem, useProject, useWorkItems } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatCount } from '../../lib/format';
import { healthLabels, priorityLabels, workItemColumns, workItemStatusLabels } from '../../lib/labels';
import { boardKeyboardCoordinates } from './boardKeyboard';
import { refusalFor, resolveCardMove } from './cardMove';

/**
 * The kanban board (plan §6l).
 *
 * The decision that makes this board trustworthy: **derived items are never materialized.** An
 * assigned execution, a pending review and a stale case all appear as cards, but their status
 * is computed from the source on every read — so a card physically cannot say "Done" while its
 * execution is still unexecuted. The cost is that a derived card can't be dragged freely; it
 * only moves where its source legally allows, and the board says so rather than accepting the
 * drop and silently reverting it on the next refresh.
 */

/**
 * The columns, left to right, as the contract's status codes.
 *
 * The *vocabulary* is the contract's (`WorkItemStatus`) and the words are
 * `workItemStatusLabels`'. Before §53 all three were one array of English strings written here,
 * which is how the board and the mock came to agree on a spelling no server had ever been asked
 * to produce.
 *
 * The list itself moved to `lib/labels.ts` in §54, because the board is no longer its only
 * consumer: the mock's `POST /work-items` computes `allowedTransitions` from the same set, and it
 * held its own literal — one column behind the server the moment a fifth arrived.
 */
const COLUMNS = workItemColumns;
type Column = WorkItemStatus;

/**
 * Every `WorkItemStatus` has a column — asserted by the compiler, not by a reader.
 *
 * `workItemColumns` is derived from the label map's keys, so this should hold structurally. It is
 * asserted anyway because the derivation ends in a type assertion (`as WorkItemStatus[]`), and an
 * assertion is a claim the compiler stops checking. The thing being claimed is the whole reason
 * §53 refused to add `BLOCKED` to the contract on its own: a status with no column is a card that
 * exists, is returned by the API, and appears **nowhere on screen** — invisible rather than
 * misplaced, and nothing fails.
 *
 * Written as a `Record` rather than the shorter `extends never ? true : never` on purpose — that
 * form errors with "Type 'true' is not assignable to type 'never'", which says nothing, while
 * this one **names the missing status**: *Property 'BLOCKED' is missing*. A guard whose failure
 * message does not identify the cause gets silenced rather than fixed.
 */
const _everyStatusHasAColumn: Record<Exclude<WorkItemStatus, (typeof COLUMNS)[number]>, never> = {};
void _everyStatusHasAColumn;

const kindLabels: Record<WorkItem['kind'], string> = {
  TASK: 'Task',
  EXECUTION: 'Execution',
  REVIEW: 'Review',
  DOC_UPDATE: 'Documentation',
  CASE_MAINTENANCE: 'Maintenance',
  DEFECT: 'Defect',
  QUARANTINE: 'Quarantined',
};

export function BoardPage() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const mine = params.get('mine') === 'true';

  const project = useProject(projectId);
  const items = useWorkItems(projectId, mine);
  const [creating, setCreating] = useState(false);
  const { enqueueSnackbar } = useSnackbar();

  return (
    <Box>
      <PageHeader
        testId="Board.Header"
        title="Board"
        documentTitle={project.data ? `Board · ${project.data.name}` : 'Board'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Board' },
        ]}
        subtitle="Tasks alongside the work the repository already knows about — assigned executions, pending reviews, cases that need attention and tests somebody has quarantined."
        actions={
          <Stack direction="row" sx={{ gap: 0.5, alignItems: 'center' }}>
            {(
              [
                { value: false, label: 'Everyone' },
                { value: true, label: 'Assigned to me' },
              ]
            ).map((option) => (
              <Chip
                key={String(option.value)}
                label={option.label}
                onClick={() => {
                  const next = new URLSearchParams(params);
                  if (option.value) next.set('mine', 'true');
                  else next.delete('mine');
                  setParams(next, { replace: true });
                }}
                variant={mine === option.value ? 'filled' : 'outlined'}
                color={mine === option.value ? 'primary' : 'default'}
                aria-pressed={mine === option.value}
                data-testid={`Board.Filter.${option.value ? 'Mine' : 'Everyone'}`}
                sx={{ cursor: 'pointer' }}
              />
            ))}
            {/*
              Disable-don't-hide (§31): a Viewer sees the button greyed with a reason, which teaches
              that the capability exists and is worth asking for. Hiding it would teach that the
              product cannot do it, which is a lie.
            */}
            <PermissionGate projectId={projectId} permission="workitem:manage">
              {({ allowed }) => (
                <Button
                  variant="contained"
                  size="small"
                  disabled={!allowed}
                  onClick={() => setCreating(true)}
                  data-testid="Board.NewTask"
                  sx={{ ml: 1 }}
                >
                  New task
                </Button>
              )}
            </PermissionGate>
          </Stack>
        }
      />

      {/*
        Conditionally rendered, so the form — and the create hook's per-intent Idempotency-Key —
        mount fresh each time. See the dialog's header: left permanently mounted, one key would
        serve every task typed on this page and the second create would replay the first.
      */}
      {creating && (
        <TaskCreateDialog
          projectId={projectId}
          open
          onClose={() => setCreating(false)}
          onCreated={(task) => enqueueSnackbar(`Added “${task.title}”`, { variant: 'success' })}
        />
      )}

      <DataRegion
        testId="Board.Content"
        state={{ ...items, refetch: () => void items.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: mine ? 'Nothing assigned to you' : 'Nothing on the board',
          body: mine
            ? 'No open executions, reviews or tasks are assigned to you right now.'
            : 'No open work in this project.',
        }}
      >
        {(rows) => <Board items={rows} />}
      </DataRegion>
    </Box>
  );
}

/**
 * A card's reason, in the reader's words.
 *
 * A `CASE_MAINTENANCE` card's `description` is a comma-separated list of raw signal names
 * (`NEVER_EXECUTED, STALE_APPROVAL`) — deliberately, because the words belong to `lib/labels.ts`
 * and `BoardService` says so in its own comment: the `work` module cannot import
 * `repository.HealthSignal`, and inventing a second set of words there is exactly how one value
 * comes to read three different ways across the grid, the dashboard and the board.
 *
 * So the labelling happens here, through the **same `healthLabels` map the dashboard's "Needs
 * attention" panel uses**. An unrecognised code falls through as itself rather than being dropped:
 * a signal the SPA has no word for yet is still a reason, and silently discarding it would put the
 * card back in the state this function exists to fix.
 *
 * **The `kind` check is what a `QUARANTINE` card depends on, and it is why that kind exists.** Its
 * description is a sentence a person wrote — *"Quarantined by Ana — flaky on Firefox, sometimes"* —
 * so filed under `CASE_MAINTENANCE` it would be split on its commas and looked up word by word,
 * rendering as its own fragments joined by a middot with nothing failing anywhere. Anything else
 * passes through untouched, which is the behaviour every future kind gets by default.
 */
function describeCard(item: WorkItem): string {
  if (item.kind !== 'CASE_MAINTENANCE' || !item.description) return item.description ?? '';
  return item.description
    .split(',')
    .map((signal) => signal.trim())
    .filter(Boolean)
    .map((signal) => healthLabels[signal as keyof typeof healthLabels] ?? signal)
    .join(' · ');
}

/**
 * Where a card's key takes you.
 *
 * **Keyed on `sourceType`, not on `kind`, and that is the repair §81 owed.** Every card used to
 * route through the case list — a search for `linkedKey` — which was right while the only linked
 * cards were about cases. The reviewer's inbox draws cards for **suites and plans** too, and a
 * review of `TP-5` sent to a case search finds nothing and says "no cases match", which reads as the
 * plan having been deleted.
 *
 * `sourceType` is what the server puts the *artifact* in for exactly this reason (`BoardService`'s
 * card comment): a review has no URL of its own — §6g's panel is rendered on the artifact's own page
 * — so navigating to the artifact is navigating to the review.
 *
 * **A suite has no detail page**, which is why that arm is the case list filtered to it rather than
 * a route: the suite tree is the case list's left pane, and `SUITE` is the one subject type §6g can
 * review with no screen of its own. An unrecognised `sourceType` falls back to the search, which is
 * where the old behaviour is still correct — a card the SPA has no route for should still land
 * somewhere that mentions the thing. `EXECUTION` is deliberately one of those: its `sourceId` names
 * the execution, whose route needs the run id, and a card does not carry one.
 *
 * **The project comes from the CARD, not from the route (§83), and that is the second repair this
 * function has needed.** §81 fixed *which screen* a card opens and left the project as the board's
 * own — correct while every board was one project's, and silently wrong the moment §6l's
 * cross-project board exists: a review of a case in MOB, read on My Work, would have been sent to
 * `/projects/{OLB}/cases/{a case OLB does not contain}` — a 404 on the one affordance the card
 * exists to offer, and on the project board it is the same value, so nothing would have looked
 * different where anybody was testing. `projectId` has been **required** on every card since §53
 * (`WorkItem.projectId`), so there is no fallback arm and no route parameter to disagree with.
 */
function linkFor(item: WorkItem): string {
  const projectId = item.projectId;
  const search = `/projects/${projectId}/cases?q=${encodeURIComponent(item.linkedKey ?? '')}`;
  if (!item.sourceId) return item.linkedKey ? search : `/projects/${projectId}/cases`;

  switch (item.sourceType) {
    case 'CASE':
      return `/projects/${projectId}/cases/${item.sourceId}`;
    case 'SUITE':
      return `/projects/${projectId}/cases?suiteId=${item.sourceId}`;
    case 'PLAN':
      return `/projects/${projectId}/plans/${item.sourceId}`;
    default:
      return search;
  }
}

/**
 * Exported for its test — the columns and the refusal are the logic worth pinning.
 *
 * **It takes no project (§83).** It had one for two reasons and neither survived the cross-project
 * board: `linkFor` now reads the card's own project, and the move hook keys on nothing, because
 * `PATCH /work-items/{id}` names no project either. What is left is a board that renders whatever
 * cards it is handed — which is exactly what let §6l's My Work screen reuse it rather than grow a
 * second one, and a second one is how two screens come to disagree about what "linked" means.
 *
 * `crossProject` is presentation only: on a board that is already one project's, stamping every
 * card with that project is noise the reader has to filter out on every glance.
 */
export function Board({ items, crossProject = false }: { items: WorkItem[]; crossProject?: boolean }) {
  const { enqueueSnackbar } = useSnackbar();
  const announce = useAnnounce();
  const move = useMoveWorkItem();
  const [dragging, setDragging] = useState<WorkItem | null>(null);
  const [rejected, setRejected] = useState<string | null>(null);

  /*
    Pointer + keyboard, and the pointer one needs the 5px constraint for §94's reason one screen
    over: without it a click on the handle is a zero-distance drag, so the handle swallows the
    click and the card's own links and buttons become unreachable from a pointer that started
    there. `PointerSensor` also brings **touch**, which the native HTML5 drag this replaces never
    had at all — a board is exactly the screen somebody opens on a tablet in a stand-up.
  */
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: boardKeyboardCoordinates(COLUMNS) }),
  );

  /**
   * A finished drag and a pressed "→ column" button end in the same place.
   *
   * The decision — including the three ways a drag ends in nothing and the one way it is refused —
   * is `resolveCardMove`'s, so it can be asked directly rather than through a jsdom pointer.
   */
  const commit = async (item: WorkItem | null, over: string | number | null | undefined) => {
    const outcome = resolveCardMove(item, over, COLUMNS);
    if (outcome.outcome === 'NONE') return;
    if (outcome.outcome === 'REFUSED') {
      setRejected(outcome.reason);
      // The alert is `role="alert"`, but a refusal at the end of a *keyboard* drag arrives while
      // focus is still on the card — announcing it puts the sentence where the user is.
      announce(outcome.reason);
      return;
    }

    setRejected(null);
    const item_ = item as WorkItem;
    try {
      await move.mutateAsync({ id: item_.id, status: outcome.to });
      announce(`Moved ${item_.title} to ${workItemStatusLabels[outcome.to]}`);
    } catch (error) {
      /*
        The card is already back where it was — `useMoveWorkItem` restores its snapshot in
        `onError`. So this says what happened rather than doing the undo, and it says it twice on
        purpose: the alert persists (a toast about a card that has visibly snapped back is gone
        before the eye returns to it) and the announcement reaches a screen reader, for whom the
        snap-back is otherwise completely silent.
      */
      const reason =
        error instanceof ApiError ? error.detail : "Couldn't move that card. Check your connection and try again.";
      setRejected(reason);
      announce(`${item_.title} did not move. ${reason}`);
      enqueueSnackbar("Couldn't move that card", { variant: 'error' });
    }
  };

  return (
    <DndContext
      sensors={sensors}
      /*
        `pointerWithin` rather than `closestCenter`, which is what the step list uses. A column is
        a big rectangle and a card is small: closest-centre picks the column whose *centre* is
        nearest, so releasing near the left edge of a wide column can land in its neighbour. For
        containers, "the pointer is inside it" is the rule the user believes they are following.
      */
      collisionDetection={pointerWithin}
      onDragStart={(event) => setDragging(items.find((item) => item.id === event.active.id) ?? null)}
      onDragCancel={() => setDragging(null)}
      onDragEnd={({ active, over }: DragEndEvent) => {
        const item = items.find((row) => row.id === active.id) ?? null;
        // Cleared BEFORE the await: the column highlighting keys on it, and leaving it set for the
        // length of the request paints the board as mid-drag while the write is in flight.
        setDragging(null);
        void commit(item, over?.id);
      }}
    >
      <Stack sx={{ gap: 2 }}>
        {rejected && (
          <Alert severity="warning" role="alert" onClose={() => setRejected(null)} data-testid="Board.RejectedMove">
            <Typography variant="body2">{rejected}</Typography>
          </Alert>
        )}

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: `repeat(${COLUMNS.length}, 1fr)` },
            gap: 2,
            alignItems: 'start',
          }}
        >
          {COLUMNS.map((column) => {
            const columnItems = items
              .filter((item) => item.status === column)
              // Overdue first, then by priority, with the id as a unique tiebreaker so cards
              // don't reshuffle on every refetch.
              .sort((a, b) => {
                const rank = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
                return (
                  Number(b.overdue) - Number(a.overdue) ||
                  rank[a.priority] - rank[b.priority] ||
                  a.id.localeCompare(b.id)
                );
              });

            return (
              <BoardColumn
                key={column}
                column={column}
                count={columnItems.length}
                // Only a column that can legally accept the dragged card highlights, so the
                // invalid drop is discouraged before it is attempted rather than refused after.
                canAccept={dragging ? dragging.allowedTransitions.includes(column) : false}
                dragging={dragging !== null}
              >
                {columnItems.length === 0 ? (
                  <Typography variant="caption" sx={{ color: 'text.secondary', p: 1 }}>
                    Nothing here
                  </Typography>
                ) : (
                  columnItems.map((item) => (
                    <WorkCard
                      key={item.id}
                      item={item}
                      crossProject={crossProject}
                      onMove={(target) => void commit(item, target)}
                    />
                  ))
                )}
              </BoardColumn>
            );
          })}
        </Box>
      </Stack>

      {/*
        The card follows the cursor instead of the browser's ghost image. It is not decoration:
        with `useDraggable` alone the card stays in its column while an invisible drag happens, so
        the gesture gives no feedback at all until the drop.

        `dragging={false}` on the copy — the overlay is not the draggable, and passing the live
        card would register a second draggable with the same id.
      */}
      <DragOverlay>
        {dragging && <WorkCard item={dragging} crossProject={crossProject} onMove={() => {}} overlay />}
      </DragOverlay>
    </DndContext>
  );
}

/**
 * A column, and the thing a card is dropped **on**.
 *
 * A component rather than a `map` body because `useDroppable` is a hook — the same reason
 * `GridRow` and `StepActionField` exist. Its droppable id is the column's own status code, which
 * is what lets `resolveCardMove` treat an `over` that is not a column as a stale id.
 */
function BoardColumn({
  column,
  count,
  canAccept,
  dragging,
  children,
}: {
  column: Column;
  count: number;
  canAccept: boolean;
  dragging: boolean;
  children: ReactNode;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: column, disabled: dragging && !canAccept });

  return (
    <Box ref={setNodeRef} data-testid={`Board.Column.${column}`} data-over={isOver ? 'true' : undefined}>
      <Panel
        dense
        title={
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
            <Typography variant="h5" component="h2">
              {workItemStatusLabels[column]}
            </Typography>
            <Identifier variant="muted">{formatCount(count)}</Identifier>
          </Stack>
        }
      >
        <Stack
          sx={{
            p: 1,
            gap: 1,
            minHeight: 120,
            bgcolor: dragging ? (canAccept ? 'action.hover' : 'transparent') : 'transparent',
            // Not colour alone: a column that cannot accept the card also dims, and the card's
            // own buttons carry the reason in words.
            opacity: dragging && !canAccept ? 0.5 : 1,
            outline: isOver && canAccept ? '2px solid' : 'none',
            outlineColor: 'primary.main',
            borderRadius: 1,
            transition: 'background-color 120ms ease',
          }}
        >
          {children}
        </Stack>
      </Panel>
    </Box>
  );
}

function WorkCard({
  item,
  crossProject,
  onMove,
  overlay = false,
}: {
  item: WorkItem;
  /** Show which project the card belongs to — true only on the cross-project board (§83). */
  crossProject: boolean;
  onMove: (column: Column) => void;
  /** The copy inside `DragOverlay`: no draggable registration, no hiding of the original. */
  overlay?: boolean;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: item.id,
    // A derived card follows its source and cannot be moved from here at all, so it is not
    // draggable in the first place — `ui-rules`, rather than letting it lift and be refused on
    // release. Its "→ column" buttons are disabled with the same reason, so the refusal is still
    // discoverable; it just is not reachable by a gesture that cannot succeed.
    disabled: overlay || item.allowedTransitions.length === 0,
  });

  const priorityColor =
    item.priority === 'CRITICAL'
      ? 'var(--color-failed)'
      : item.priority === 'HIGH'
        ? 'var(--color-blocked)'
        : 'var(--color-border-strong)';

  const movable = item.allowedTransitions.length > 0;

  return (
    <Box
      ref={overlay ? undefined : setNodeRef}
      data-testid="Board.Card"
      sx={{
        p: 1.25,
        border: '1px solid',
        borderColor: 'divider',
        borderLeft: '3px solid',
        borderLeftColor: priorityColor,
        borderRadius: 1,
        bgcolor: 'background.paper',
        // The original stays in place and fades while the overlay copy follows the cursor;
        // removing it would collapse the column under the pointer mid-drag.
        opacity: isDragging ? 0.4 : 1,
        boxShadow: overlay ? 4 : 'none',
      }}
    >
      <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75, mb: 0.5, flexWrap: 'wrap' }}>
        {/*
          A handle rather than a draggable card, and for the same reason §94 gave the step rows
          one: the card carries a link and six buttons, and making the whole card draggable turns
          every attempt to press one into the start of a drag.
        */}
        {movable && !overlay && (
          <Tooltip title="Drag to another column. The → buttons below do the same thing.">
            <IconButton
              size="small"
              disableRipple
              {...attributes}
              {...listeners}
              aria-label={`Move ${item.title}`}
              data-testid="Board.Card.DragHandle"
              sx={{ cursor: 'grab', touchAction: 'none', ml: -0.5 }}
            >
              <DragIndicatorIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        <Chip size="small" variant="outlined" label={kindLabels[item.kind]} data-testid="Board.Card.Kind" />
        {/*
          Which project this card is from — the field §83 finally reads.

          The card's `projectKey` rather than its `projectId`: `ui-rules` §1, a human key where a
          name exists and never a uuid. It is the one thing a cross-project board has to say that a
          project board does not — two cards titled "Full refund to card" from two products are
          indistinguishable without it, and the reader has no rail heading to fall back on because
          this screen sits outside the project frame.
        */}
        {crossProject && (
          <Identifier variant="muted" testId="Board.Card.Project">
            {item.projectKey}
          </Identifier>
        )}
        {/*
          Derived cards are marked. A user who knows a card mirrors an execution understands
          why it won't drag, and where to go instead.
        */}
        {item.derived && (
          <Tooltip title="This card mirrors an execution, review or case. Its status comes from the source, so update the source to move it.">
            <Chip
              size="small"
              label="linked"
              data-testid="Board.Card.DerivedMarker"
              sx={{
                bgcolor: 'transparent',
                color: 'var(--color-info)',
                border: '1px solid color-mix(in srgb, var(--color-info) 45%, transparent)',
              }}
            />
          </Tooltip>
        )}
        {item.overdue && (
          <Chip
            size="small"
            label="overdue"
            data-testid="Board.Card.Overdue"
            sx={{
              bgcolor: 'color-mix(in srgb, var(--color-failed) 12%, transparent)',
              color: 'var(--color-failed)',
              border: '1px solid color-mix(in srgb, var(--color-failed) 40%, transparent)',
            }}
          />
        )}
      </Stack>

      <Typography variant="body2" sx={{ fontWeight: 500, mb: 0.5 }} data-testid="Board.Card.Title">
        {item.title}
      </Typography>

      {/*
        **The card has to say WHY, and until the live pass it didn't.**

        `description` was not rendered at all. The server has always sent one — a maintenance card
        carries its health signals, an execution card carries the run it belongs to — and the
        backend test asserts it. Nobody ever saw it. On screen a maintenance card read as its case
        title and a chip, sitting directly above an execution card with the *same title*: two cards,
        the same words, different meanings, neither explaining itself.

        That is the failure `BoardService.cardFor` warns about in its own javadoc — "a card whose
        reason is hidden behind a click gets ignored in bulk" — shipped anyway, because the server
        was right and the screen was silent. Only driving it showed that.
      */}
      {item.description && (
        <Typography
          variant="caption"
          sx={{ display: 'block', color: 'text.secondary', mb: 0.5 }}
          data-testid="Board.Card.Reason"
        >
          {describeCard(item)}
        </Typography>
      )}

      {item.linkedKey && (
        <RouterLink
          to={linkFor(item)}
          data-testid="Board.Card.LinkedKey"
        >
          <Identifier variant="muted">{item.linkedKey}</Identifier>
        </RouterLink>
      )}

      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mt: 0.75, flexWrap: 'wrap' }}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {priorityLabels[item.priority]}
        </Typography>
        {item.assignee && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            · {item.assignee.displayName}
          </Typography>
        )}
        {item.dueDate && (
          <Typography variant="caption" sx={{ color: item.overdue ? 'var(--color-failed)' : 'text.secondary' }}>
            · due <Identifier variant="muted">{item.dueDate}</Identifier>
          </Typography>
        )}
      </Stack>

      {/*
        Dragging is not the only way to move a card. A keyboard user gets the same capability
        from these buttons, and they carry the disabled reason rather than disappearing.

        **The reason has to be reachable, and for a disabled control it isn't by default.** MUI
        sets `pointer-events: none` on a disabled button, so no hover ever reaches a `Tooltip`
        wrapped straight around one — and on this board 14 of the 24 move buttons are disabled,
        because a derived card follows its source. Every one of them greyed out and explained
        nothing, which is the worse half of disable-don't-hide: the affordance says "no" and
        refuses to say why. `PermissionGate` already documents this trap and solves it with a
        span anchor; the same solution belongs here rather than a second invention.
      */}
      <Stack direction="row" sx={{ gap: 0.5, mt: 1, flexWrap: 'wrap' }}>
        {COLUMNS.filter((column) => column !== item.status).map((column) => {
          const allowed = item.allowedTransitions.includes(column);
          const button = (
            <Box
              component="button"
              type="button"
              disabled={!allowed}
              aria-disabled={!allowed}
              onClick={() => onMove(column)}
              data-testid={`Board.Card.MoveTo.${column}`}
              sx={{
                font: 'inherit',
                fontSize: '0.6875rem',
                px: 0.75,
                py: 0.25,
                borderRadius: 0.5,
                border: '1px solid',
                borderColor: 'divider',
                bgcolor: 'transparent',
                color: allowed ? 'text.secondary' : 'text.disabled',
                cursor: allowed ? 'pointer' : 'not-allowed',
                '&:hover:not(:disabled)': { borderColor: 'primary.main', color: 'primary.main' },
              }}
            >
              → {workItemStatusLabels[column]}
            </Box>
          );

          // The same sentence a refused DROP produces (`cardMove.refusalFor`). Two affordances,
          // one rule, one wording — they were two hand-written strings until §106 and had already
          // drifted apart in tense and in what they named.
          const title = allowed ? `Move to ${workItemStatusLabels[column]}` : refusalFor(item, column);

          return (
            <Tooltip key={column} title={title}>
              {allowed ? (
                button
              ) : (
                // The anchor that actually receives the hover. `role="none"` because it is
                // scaffolding, and `cursor: not-allowed` states the verdict before the tooltip
                // has had time to open.
                <Box component="span" role="none" sx={{ display: 'inline-flex', cursor: 'not-allowed' }}>
                  {button}
                </Box>
              )}
            </Tooltip>
          );
        })}
      </Stack>
    </Box>
  );
}
