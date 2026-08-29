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
import { GripVertical, X } from 'lucide-react';
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { ApiError } from '../../api/client';
import { useMoveWorkItem, useProject, useWorkItems } from '../../api/queries';
import type { WorkItem, WorkItemStatus } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import { Button, Tooltip, TooltipContent, TooltipTrigger } from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatCount } from '../../lib/format';
import { healthLabels, priorityLabels, workItemColumns, workItemStatusLabels } from '../../lib/labels';
import { toast } from '../../notifications/toast';
import { boardKeyboardCoordinates } from './boardKeyboard';
import { refusalFor, resolveCardMove } from './cardMove';
import { TaskCreateDialog } from './TaskCreateDialog';

/**
 * The kanban board (plan §6l).
 *
 * The decision that makes this board trustworthy: **derived items are never materialized.** An
 * assigned execution, a pending review and a stale case all appear as cards, but their status
 * is computed from the source on every read — so a card physically cannot say "Done" while its
 * execution is still unexecuted. The cost is that a derived card can't be dragged freely; it
 * only moves where its source legally allows, and the board says so rather than accepting the
 * drop and silently reverting it on the next refresh.
 *
 * <h2>The look is `renders/04-board-and-people.html` frame A (007 T13)</h2>
 *
 * Five columns of `.col` panels on `--panel`, cards on `--paper`, and three things the render
 * makes rules rather than decoration:
 *
 *   - **Blocked earns colour and Done loses it.** One column is tinted because one column is a
 *     question for a human; Done drops to 62% opacity — present for counting, quiet for scanning.
 *     Five identical columns made the eye read all five to find the trouble.
 *   - **Overdue is a word in red, not a red card.** Colour alone fails a greyscale print and a
 *     colour-blind reader, and a whole red card drowns the card's own priority (UX#37).
 *   - **The keyboard twin is on screen while a card is lifted**, in the footer strip, so the
 *     single-pointer alternative WCAG 2.2 requires is discoverable rather than documented.
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

  /*
    ~~`PageHeader` raised the arrival announcement and the tab title.~~ → superseded by 007 T13:
    the top strip is the render's `.top`, so the announcement is raised here, the way `HelpPage`
    does it. The sentence is the same one the shared header produced, because a screen-reader user
    learning a new phrasing per screen is the cost of moving it.
  */
  const announce = useAnnounce();
  useEffect(() => {
    announce('Board page loaded');
  }, [announce]);

  return (
    <div>
      <title>{project.data ? `Board · ${project.data.name} · TCMS` : 'Board · TCMS'}</title>

      <header
        data-testid="Board.Header"
        className="mb-3 flex flex-wrap items-start gap-3 border-b border-white/8 pb-3"
      >
        <div className="min-w-0 flex-1">
          <nav aria-label="Breadcrumb" className="mb-1 text-[12.5px] text-ink-3">
            <RouterLink
              to={`/projects/${projectId}`}
              data-testid="Board.Header.Breadcrumb"
              className="text-ink-3 no-underline hover:text-ink-2"
            >
              {project.data?.name ?? 'Project'}
            </RouterLink>
            <span className="px-1.5 text-ink-4">/</span>
            <b className="font-medium text-ink-2">Board</b>
          </nav>
          <h1
            data-testid="Board.Header.Title"
            className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink"
          >
            Board
          </h1>
          <p className="m-0 mt-1 max-w-[74ch] text-[13px] text-ink-3">
            Tasks alongside the work the repository already knows about — assigned executions,
            pending reviews, cases that need attention and tests somebody has quarantined.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/*
            `.lens` — the render's segmented scope switch, not two chips. The two options are one
            choice with two states, and a pair of independent-looking chips says otherwise.
          */}
          <div className="inline-flex gap-0.5 rounded-lg bg-white/4 p-0.5">
            {[
              { value: false, label: 'Everyone' },
              { value: true, label: 'Assigned to me' },
            ].map((option) => (
              <button
                key={String(option.value)}
                type="button"
                aria-pressed={mine === option.value}
                data-testid={`Board.Filter.${option.value ? 'Mine' : 'Everyone'}`}
                onClick={() => {
                  const next = new URLSearchParams(params);
                  if (option.value) next.set('mine', 'true');
                  else next.delete('mine');
                  setParams(next, { replace: true });
                }}
                className={cn(
                  'flex cursor-pointer items-center gap-1.5 rounded-md border-0 px-[11px] py-1 text-xs font-medium',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                  mine === option.value
                    ? 'bg-panel text-ink shadow-[0_1px_2px_rgb(0_0_0/40%)]'
                    : 'bg-transparent text-ink-3 hover:text-ink-2',
                )}
              >
                {option.label}
              </button>
            ))}
          </div>

          {/*
            Disable-don't-hide (§31): a Viewer sees the button greyed with a reason, which teaches
            that the capability exists and is worth asking for. Hiding it would teach that the
            product cannot do it, which is a lie.
          */}
          <PermissionGate projectId={projectId} permission="workitem:manage">
            {({ allowed }) => (
              <Button
                variant="primary"
                size="sm"
                disabled={!allowed}
                onClick={() => setCreating(true)}
                data-testid="Board.NewTask"
              >
                New task
              </Button>
            )}
          </PermissionGate>
        </div>
      </header>

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
          onCreated={(task) => toast(`Added “${task.title}”`, { variant: 'success' })}
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
    </div>
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
 * So the labelling happens here, through `healthLabels` — ~~the **same map the dashboard's "Needs
 * attention" panel uses**~~ → ~~**false, and measured in §179: that panel renders `tile.label`, which
 * is `HealthSignal.label()` on the server, and six of the ten disagree with this map.**~~ →
 * **true again since §180, and it is worth reading the two struck sentences in order.** The original
 * claim was aspiration written as fact; §179 measured it and found the panel on the server's words,
 * six of ten of them different, so a tile reading "Outdated — spec changed" drilled into a grid
 * saying "Linked spec changed" while this card said the second; §180 deleted `DashboardTile.label`
 * and `HealthSignal.label()` outright, so the panel reads this map through `labelForKey` and the
 * sentence describes the code. **A comment that says two surfaces agree is a claim about a
 * mechanism, and it stayed wrong for as long as nobody could see both stacks at once.**
 * An unrecognised code falls through as itself rather than being dropped:
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
      // The banner is inside `role="alert"`, but a refusal at the end of a *keyboard* drag arrives
      // while focus is still on the card — announcing it puts the sentence where the user is.
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
        purpose: the banner persists (a toast about a card that has visibly snapped back is gone
        before the eye returns to it) and the announcement reaches a screen reader, for whom the
        snap-back is otherwise completely silent.
      */
      const reason =
        error instanceof ApiError ? error.detail : "Couldn't move that card. Check your connection and try again.";
      setRejected(reason);
      announce(`${item_.title} did not move. ${reason}`);
      toast("Couldn't move that card", { variant: 'error' });
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
      <div className="flex flex-col gap-3">
        {rejected && (
          <div role="alert">
            <Banner tone="warn" data-testid="Board.RejectedMove" className="items-center">
              <span className="flex-1">{rejected}</span>
              <button
                type="button"
                onClick={() => setRejected(null)}
                aria-label="Dismiss"
                className="cursor-pointer border-0 bg-transparent p-0 text-ink-4 hover:text-ink focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]"
              >
                <X aria-hidden className="size-3.5" />
              </button>
            </Banner>
          </div>
        )}

        {/*
          The column count is `COLUMNS.length`, not the literal 5: a status added to the contract
          gets a column here the moment it is labelled. Tailwind cannot name a dynamic class, so
          the count travels as a custom property and the arbitrary `grid-template-columns` reads
          it — which keeps the breakpoint in the class list rather than in an inline media query
          nothing can express.
        */}
        <div
          className={
            'grid items-start gap-3 sm:grid-cols-2 ' +
            'lg:[grid-template-columns:repeat(var(--board-columns),minmax(0,1fr))]'
          }
          style={{ '--board-columns': COLUMNS.length } as CSSProperties}
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
                  <p className="m-0 px-1 py-2.5 text-xs text-ink-4">Nothing here</p>
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
        </div>

        {/*
          The render's `.foot`, and it is the WCAG 2.2 half of the drag rather than chrome: the
          keyboard path exists whether or not it is drawn, and a path nobody can discover is one
          nobody uses. It appears only while a card is lifted, which is the moment the words are
          about something.
        */}
        {dragging && (
          <div className="flex flex-wrap items-center gap-3 rounded-lg border border-white/8 bg-[rgb(14_19_26/92%)] px-4 py-2.5">
            <span className="flex-1 text-xs text-ink-4">
              Dragging <b className="font-medium text-ink-3">{dragging.title}</b> · drop to move, or
              press <b className="font-medium text-ink-3">Esc</b> to cancel
            </span>
            <span className="font-mono text-[11px] text-ink-4">
              Space lift · ←→ column · ↑↓ position · Space drop
            </span>
          </div>
        )}
      </div>

      {/*
        The card follows the cursor instead of the browser's ghost image. It is not decoration:
        with `useDraggable` alone the card stays in its column while an invisible drag happens, so
        the gesture gives no feedback at all until the drop.

        `overlay` on the copy — the overlay is not the draggable, and passing the live card would
        register a second draggable with the same id.
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
 *
 * `BLOCKED` is the one column with a hue and `DONE` the one that gives its colour up (render note
 * *"Blocked earns colour; Done loses it"*). Neither is decoration: they are the two columns a
 * reader is looking for and looking past.
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
    <section
      ref={setNodeRef}
      data-testid={`Board.Column.${column}`}
      data-over={isOver ? 'true' : undefined}
      className={cn(
        'flex min-h-0 flex-col rounded-lg border border-white/8 bg-panel',
        'transition-colors duration-150 motion-reduce:transition-none',
        isOver && canAccept && 'border-[color:var(--color-steel)]',
        // Not colour alone: a column that cannot accept the card also dims, and the card's own
        // buttons carry the reason in words.
        dragging && !canAccept && 'opacity-50',
      )}
    >
      <h2
        className={cn(
          'm-0 flex items-center gap-2 border-b border-white/8 px-3 py-2.5',
          'text-[11px] font-semibold uppercase tracking-[.08em]',
          column === 'BLOCKED' ? 'text-[color:var(--color-blocked)]' : 'text-ink-3',
        )}
      >
        {/* The label is its own element: the count sits in the same heading, and a test — or a
            reader — asking for the column by name must not have to know that. */}
        <span>{workItemStatusLabels[column]}</span>
        <span className="ml-auto font-mono tracking-normal text-ink-4">{formatCount(count)}</span>
      </h2>
      <div
        className={cn(
          'flex min-h-[120px] flex-col gap-2 p-2.5',
          column === 'DONE' && 'opacity-[.62]',
          dragging && canAccept && 'bg-white/3',
        )}
      >
        {children}
      </div>
    </section>
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

  const movable = item.allowedTransitions.length > 0;

  return (
    <article
      ref={overlay ? undefined : setNodeRef}
      data-testid="Board.Card"
      className={cn(
        'rounded-[5px] border border-white/8 bg-paper px-[11px] py-2.5 text-[13px] leading-[1.45]',
        'hover:border-white/14',
        item.status === 'BLOCKED' && 'border-[color:color-mix(in_oklab,var(--color-blocked)_26%,transparent)]',
        // The original stays in place and fades while the overlay copy follows the cursor;
        // removing it would collapse the column under the pointer mid-drag.
        isDragging && 'opacity-40',
        overlay && 'shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]',
      )}
    >
      <div className="mb-1.5 flex flex-wrap items-center gap-[7px]">
        {/*
          A handle rather than a draggable card, and for the same reason §94 gave the step rows
          one: the card carries a link and six buttons, and making the whole card draggable turns
          every attempt to press one into the start of a drag.
        */}
        {movable && !overlay && (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                {...attributes}
                {...listeners}
                aria-label={`Move ${item.title}`}
                data-testid="Board.Card.DragHandle"
                className={cn(
                  '-ml-1 flex cursor-grab touch-none items-center rounded-[4px] border-0 bg-transparent p-0.5 text-ink-4',
                  'hover:text-ink-2 focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
                )}
              >
                <GripVertical aria-hidden className="size-3.5" />
              </button>
            </TooltipTrigger>
            <TooltipContent>Drag to another column. The → buttons below do the same thing.</TooltipContent>
          </Tooltip>
        )}

        <Badge tone="plain" data-testid="Board.Card.Kind">
          {kindLabels[item.kind]}
        </Badge>

        {/*
          Which project this card is from — the field §83 finally reads.

          The card's `projectKey` rather than its `projectId`: `ui-rules` §1, a human key where a
          name exists and never a uuid. It is the one thing a cross-project board has to say that a
          project board does not — two cards titled "Full refund to card" from two products are
          indistinguishable without it, and the reader has no rail heading to fall back on because
          this screen sits outside the project frame.
        */}
        {crossProject && (
          <span data-testid="Board.Card.Project" className="font-mono text-[11px] text-ink-4">
            {item.projectKey}
          </span>
        )}

        {/*
          Derived cards are marked. A user who knows a card mirrors an execution understands
          why it won't drag, and where to go instead.
        */}
        {item.derived && (
          <Tooltip>
            <TooltipTrigger asChild>
              <span data-testid="Board.Card.DerivedMarker">
                <Badge tone="steel">linked</Badge>
              </span>
            </TooltipTrigger>
            <TooltipContent>
              This card mirrors an execution, review or case. Its status comes from the source, so
              update the source to move it.
            </TooltipContent>
          </Tooltip>
        )}

        {/* A word in red, never a red card (render note, UX#37). */}
        {item.overdue && (
          <Badge tone="failed" data-testid="Board.Card.Overdue">
            overdue
          </Badge>
        )}

        <span className="flex-1" />
        <Badge tone="plain" className="text-[10.5px]">
          {priorityLabels[item.priority]}
        </Badge>
      </div>

      <span data-testid="Board.Card.Title" className="mb-2 block font-[450] text-ink">
        {item.title}
      </span>

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

        A blocked card puts its reason under a hairline rule, which is the render's *"a blocked card
        says why, on the card"*: the reason is the only thing a blocked item is for.
      */}
      {item.description && (
        <p
          data-testid="Board.Card.Reason"
          className={cn(
            'm-0 mb-1 text-[11.5px] leading-[1.5] text-ink-4',
            item.status === 'BLOCKED' && 'mt-[7px] border-t border-white/8 pt-[7px]',
          )}
        >
          {describeCard(item)}
        </p>
      )}

      {item.linkedKey && (
        <RouterLink
          to={linkFor(item)}
          data-testid="Board.Card.LinkedKey"
          className="font-mono text-[12.5px] text-ink-3 no-underline hover:text-steel"
        >
          {item.linkedKey}
        </RouterLink>
      )}

      <div className="mt-1.5 flex flex-wrap items-center gap-[7px] text-[11px] text-ink-4">
        {item.assignee && <span>{item.assignee.displayName}</span>}
        {item.assignee && item.dueDate && <span aria-hidden>·</span>}
        {item.dueDate && (
          <span className={cn('font-mono', item.overdue && 'text-[color:var(--color-failed)]')}>
            due {item.dueDate}
          </span>
        )}
      </div>

      {/*
        Dragging is not the only way to move a card. A keyboard user gets the same capability
        from these buttons, and they carry the disabled reason rather than disappearing.

        **The reason has to be reachable, and for a disabled control it isn't by default.** ~~MUI
        sets `pointer-events: none` on a disabled button~~ → the kit's own `buttonVariants` sets
        `disabled:pointer-events-none` for the same reason MUI did, so the trap survived the port:
        no hover ever reaches a tooltip wrapped straight around a disabled control — and on this
        board 14 of the 24 move buttons are disabled, because a derived card follows its source.
        Every one of them greyed out and explained nothing, which is the worse half of
        disable-don't-hide: the affordance says "no" and refuses to say why. `PermissionGate`
        already documents this trap and solves it with a span anchor; the same solution belongs
        here rather than a second invention.
      */}
      <div className="mt-2 flex flex-wrap gap-1">
        {COLUMNS.filter((column) => column !== item.status).map((column) => {
          const allowed = item.allowedTransitions.includes(column);
          const button = (
            <Button
              size="sm"
              disabled={!allowed}
              aria-disabled={!allowed}
              onClick={() => onMove(column)}
              data-testid={`Board.Card.MoveTo.${column}`}
              className="min-h-6 px-2 py-0.5 text-[11px]"
            >
              → {workItemStatusLabels[column]}
            </Button>
          );

          // The same sentence a refused DROP produces (`cardMove.refusalFor`). Two affordances,
          // one rule, one wording — they were two hand-written strings until §106 and had already
          // drifted apart in tense and in what they named.
          const title = allowed ? `Move to ${workItemStatusLabels[column]}` : refusalFor(item, column);

          return (
            <Tooltip key={column}>
              <TooltipTrigger asChild>
                {allowed ? (
                  button
                ) : (
                  // The anchor that actually receives the hover. `role="none"` because it is
                  // scaffolding, and `cursor: not-allowed` states the verdict before the tooltip
                  // has had time to open.
                  <span role="none" className="inline-flex cursor-not-allowed">
                    {button}
                  </span>
                )}
              </TooltipTrigger>
              <TooltipContent>{title}</TooltipContent>
            </Tooltip>
          );
        })}
      </div>
    </article>
  );
}
