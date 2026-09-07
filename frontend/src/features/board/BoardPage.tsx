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
import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { ApiError } from '../../api/client';
import { useBoard, useProject, useTransitionIssue } from '../../api/queries';
import type { BoardCard } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import { Button, Tooltip, TooltipContent, TooltipTrigger } from '../../components/ui';
import { cn } from '../../lib/cn';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu';
import { formatCount, joinMeta } from '../../lib/format';
import { healthLabels, issueStatusKinds, priorityLabels } from '../../lib/labels';
import { toast } from '../../notifications/toast';
import { boardKeyboardCoordinates } from './boardKeyboard';
import { linkFor } from './cardLink';
import { columnsFor, immobileReason, resolveCardMove, type BoardColumn } from './cardMove';

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
 * ~~The columns, left to right, as the contract's status codes.~~ → **superseded by 011 D-c.**
 *
 * The vocabulary used to be a five-member `WorkItemStatus` enum, and the board's columns were
 * derived from its label map — with a compile-time guard here asserting every member had a column,
 * because a status with no column is a card that exists, is returned by the API, and appears
 * nowhere on screen. That guard is gone with the type it guarded, and the property it protected is
 * now structural rather than asserted: {@link columnsFor} builds the columns **from the cards**, so
 * a card whose status has no column is not a state this board can reach.
 *
 * What the project calls its statuses is a per-project decision now, so there is no constant here
 * at all. `Board` computes its columns per render from the cards it was handed.
 */

const kindLabels: Record<BoardCard['kind'], string> = {
  ISSUE: 'Issue',
  EXECUTION: 'Execution',
  REVIEW: 'Review',
  DOC_UPDATE: 'Documentation',
  CASE_MAINTENANCE: 'Maintenance',
  DEFECT: 'Defect',
  QUARANTINE: 'Quarantined',
};

export function BoardPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const mine = params.get('mine') === 'true';

  const project = useProject(projectId);
  const items = useBoard(projectId, mine);

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
        className="mb-3 flex flex-wrap items-start gap-3 border-b border-line pb-3"
      >
        <div className="min-w-0 flex-1">
          <h1
            data-testid="Board.Header.Title"
            className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink"
          >
            Board
          </h1>
          <p className="m-0 mt-1 max-w-[74ch] text-[13px] text-ink-3">
            Issues alongside the work the repository already knows about — assigned executions,
            pending reviews, cases that need attention and tests somebody has quarantined.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/*
            `.lens` — the render's segmented scope switch, not two chips. The two options are one
            choice with two states, and a pair of independent-looking chips says otherwise.
          */}
          <div className="inline-flex gap-0.5 rounded-[8px] bg-wash-1 p-0.5">
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
                  'flex cursor-pointer items-center gap-1.5 rounded-md border-0 px-[11px] py-1 text-[12px] font-medium',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                  mine === option.value
                    ? 'bg-panel text-ink shadow-e1'
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
          {/*
            ~~A dialog on this page, gated on `workitem:manage`.~~ → **superseded by 011.** That
            permission is retired, the create is `POST /issues`, and the form is a route of its own
            (`/work/new`) because quick add is one field with a full form under it — more than a
            dialog beside a board should carry, and the same form the work table links to.
          */}
          <PermissionGate projectId={projectId} permission="issue:create">
            {({ allowed }) => (
              <Button
                variant="primary"
                size="sm"
                disabled={!allowed}
                onClick={() => navigate(`/projects/${projectId}/work/new`)}
                data-testid="Board.NewIssue"
              >
                Add work
              </Button>
            )}
          </PermissionGate>
        </div>
      </header>

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
function describeCard(item: BoardCard): string {
  if (item.kind !== 'CASE_MAINTENANCE' || !item.description) return item.description ?? '';
  return item.description
    .split(',')
    .map((signal) => signal.trim())
    .filter(Boolean)
    .map((signal) => healthLabels[signal as keyof typeof healthLabels] ?? signal)
    .join(' · ');
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
export function Board({ items, crossProject = false }: { items: BoardCard[]; crossProject?: boolean }) {
  const announce = useAnnounce();
  const move = useTransitionIssue();
  const [dragging, setDragging] = useState<BoardCard | null>(null);
  const [rejected, setRejected] = useState<string | null>(null);

  /*
    The columns are the cards', not a constant (011 D-c) — see `columnsFor`. Memoised because the
    keyboard sensor is constructed from them: a new array each render would rebuild the sensor
    mid-drag, which drops the lift.
  */
  const columns = useMemo(() => columnsFor(items), [items]);
  const columnNames = useMemo(() => columns.map((column) => column.name), [columns]);

  /*
    Pointer + keyboard, and the pointer one needs the 5px constraint for §94's reason one screen
    over: without it a click on the handle is a zero-distance drag, so the handle swallows the
    click and the card's own links and buttons become unreachable from a pointer that started
    there. `PointerSensor` also brings **touch**, which the native HTML5 drag this replaces never
    had at all — a board is exactly the screen somebody opens on a tablet in a stand-up.
  */
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: boardKeyboardCoordinates(columnNames) }),
  );

  /**
   * A finished drag and a pressed "→ column" button end in the same place.
   *
   * The decision — including the three ways a drag ends in nothing and the one way it is refused —
   * is `resolveCardMove`'s, so it can be asked directly rather than through a jsdom pointer.
   */
  const commit = async (item: BoardCard | null, over: string | number | null | undefined) => {
    const outcome = resolveCardMove(item, over, columns);
    if (outcome.outcome === 'NONE') return;
    if (outcome.outcome === 'REFUSED') {
      setRejected(outcome.reason);
      // The banner is inside `role="alert"`, but a refusal at the end of a *keyboard* drag arrives
      // while focus is still on the card — announcing it puts the sentence where the user is.
      announce(outcome.reason);
      return;
    }

    setRejected(null);
    const item_ = item as BoardCard;
    try {
      // The card's `id` is its issue KEY — a derived card never reaches here, because it has no
      // legal transition to resolve into one.
      await move.mutateAsync({ key: item_.id, statusId: outcome.to.id });
      announce(`Moved ${item_.title} to ${outcome.to.name}`);
    } catch (error) {
      /*
        ~~The card is already back where it was — the move hook restores its snapshot.~~ →
        **superseded by 011: the transition is not optimistic**, so the card never left. This says
        what happened, and says it twice on purpose: the banner persists (a toast about a refused
        move is gone before the eye returns to the card) and the announcement reaches a screen
        reader, for whom nothing on screen changed at all.

        The 409 the workflow answers with **names the legal moves**, and `ApiError.detail` is that
        sentence — which is why it is rendered rather than replaced with a generic one, and why
        `tcmsFaults.issue('conflict')` exists to produce it: the picker only ever offers legal
        moves, so no ordinary session can.
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
          The column count is the board's own, not a literal: a project with seven statuses gets
          seven columns and one with three gets three. Tailwind cannot name a dynamic class, so
          the count travels as a custom property and the arbitrary `grid-template-columns` reads
          it — which keeps the breakpoint in the class list rather than in an inline media query
          nothing can express.
        */}
        <div
          className={
            'grid items-start gap-3 sm:grid-cols-2 ' +
            'lg:[grid-template-columns:repeat(var(--board-columns),minmax(0,1fr))]'
          }
          style={{ '--board-columns': columns.length } as CSSProperties}
        >
          {columns.map((column) => {
            const columnItems = items
              .filter((item) => item.status.name === column.name)
              // Overdue first, then by priority, with the id as a unique tiebreaker so cards
              // don't reshuffle on every refetch.
              .sort((a, b) => {
                const rank = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
                // `priority` is optional on the wire, and an absent one sorts as MEDIUM rather
                // than as nothing: a derived card carries none, and dropping those to the bottom
                // would put every execution below every task regardless of its date.
                return (
                  Number(b.overdue) - Number(a.overdue) ||
                  rank[a.priority ?? 'MEDIUM'] - rank[b.priority ?? 'MEDIUM'] ||
                  a.id.localeCompare(b.id)
                );
              });

            return (
              <BoardColumn
                key={column.name}
                column={column}
                count={columnItems.length}
                // Only a column that can legally accept the dragged card highlights, so the
                // invalid drop is discouraged before it is attempted rather than refused after.
                canAccept={
                  dragging
                    ? dragging.allowedTransitions.some((status) => status.name === column.name)
                    : false
                }
                dragging={dragging !== null}
              >
                {columnItems.length === 0 ? (
                  <p className="m-0 px-1 py-2.5 text-[12px] text-ink-4">Nothing here</p>
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
          <div className="flex flex-wrap items-center gap-3 rounded-[8px] border border-line bg-[rgb(14_19_26/92%)] px-4 py-2.5">
            <span className="flex-1 text-[12px] text-ink-4">
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
  column: BoardColumn;
  count: number;
  canAccept: boolean;
  dragging: boolean;
  children: ReactNode;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: column.name,
    disabled: dragging && !canAccept,
  });

  return (
    <section
      ref={setNodeRef}
      data-testid={`Board.Column.${column.name}`}
      data-over={isOver ? 'true' : undefined}
      className={cn(
        'flex min-h-0 flex-col rounded-[8px] border border-line bg-panel',
        'transition-colors duration-150 motion-reduce:transition-none',
        isOver && canAccept && 'border-[color:var(--color-steel)]',
        // Not colour alone: a column that cannot accept the card also dims, and the card's own
        // buttons carry the reason in words.
        dragging && !canAccept && 'opacity-50',
      )}
    >
      <h2
        className={cn(
          'm-0 flex items-center gap-2 border-b border-line px-3 py-2.5',
          'text-[11px] font-semibold uppercase tracking-[.08em]',
          // ~~`BLOCKED` earns the colour.~~ → the render's rule survives D-c, but the test that
          // picks the column out cannot be a status *name* any more: a project may call it
          // anything. `NOT_STARTED` is the kind a blocked column carries, and it is the one a
          // reader is scanning for.
          column.kind === 'NOT_STARTED' ? 'text-[color:var(--color-blocked)]' : 'text-ink-3',
        )}
      >
        {/* The label is its own element: the count sits in the same heading, and a test — or a
            reader — asking for the column by name must not have to know that. */}
        <span>{column.name}</span>
        <span className="ml-auto font-mono tracking-normal text-ink-4">{formatCount(count)}</span>
      </h2>
      <div
        className={cn(
          'flex min-h-[120px] flex-col gap-2 p-2.5',
          // "Done loses its colour" — present for counting, quiet for scanning.
          column.kind === 'DONE' && 'opacity-[.62]',
          dragging && canAccept && 'bg-wash-1',
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
  item: BoardCard;
  /** Show which project the card belongs to — true only on the cross-project board (§83). */
  crossProject: boolean;
  onMove: (columnName: string) => void;
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
        'rounded-[5px] border border-line bg-paper px-[11px] py-2.5 text-[13px] leading-[1.5]',
        'hover:border-line-strong',
        item.status.kind === 'NOT_STARTED'
          && 'border-[color:color-mix(in_oklab,var(--color-blocked)_26%,transparent)]',
        // The original stays in place and fades while the overlay copy follows the cursor;
        // removing it would collapse the column under the pointer mid-drag.
        isDragging && 'opacity-40',
        overlay && 'shadow-e3',
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
                  '-ml-1 flex cursor-grab touch-none items-center rounded-sm border-0 bg-transparent p-0.5 text-ink-4',
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
        {item.priority && (
          <Badge tone="plain" className="text-[10.5px]">
            {priorityLabels[item.priority]}
          </Badge>
        )}
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
            item.status.kind === 'NOT_STARTED' && 'mt-[7px] border-t border-line pt-[7px]',
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

      {/*
        One string, joined — never a separator rendered between a present value and an absent one.
        That was the defect: at 1280 the line wraps AFTER the middot, so a card with an assignee and
        no due date read `Marco Ferreira ·` with nothing following it (renders audit 4.1). The
        overdue colour moved onto the whole line because the date is no longer its own element;
        `overdue` is a property of the card, and the line is the only place it is stated in words.
      */}
      {(item.assignee || item.dueDate) && (
        <p
          data-testid="Board.Card.Meta"
          className={cn(
            'm-0 mt-1.5 text-[11px] text-ink-4',
            item.overdue && item.dueDate && 'text-[color:var(--color-failed)]',
          )}
        >
          {joinMeta(item.assignee?.displayName, item.dueDate && `due ${item.dueDate}`)}
        </p>
      )}

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
      {/*
        Dragging is not the only way to move a card, and one control offers every destination.

        ~~A button per destination, in a row.~~ → **superseded 2026-08-30 (renders audit 4.1).**
        Four buttons stacked three deep at 1280px and turned a one-line task into a ~290px card —
        which is card height traded for nothing, since a menu offers the same destinations.

        **WCAG 2.2's drag rule is the thing that must not regress**, and it does not: every
        destination is still reachable without a pointer gesture, which is what 2.2 requires. The
        audit named this board as the product's only conformant answer to that rule (4.2), so the
        capability is preserved exactly and only its shape changed.

        **The refusal is still reachable, and it is now easier.** The comment this replaces spent a
        paragraph on the disabled-tooltip trap — `disabled:pointer-events-none` means no hover ever
        reaches a tooltip wrapped around a disabled control, so 14 of 24 buttons greyed out and
        explained nothing. A menu item does not need the trick: a disabled item renders its reason
        as its own second line, in the menu, where the reader is already looking.
      */}
      {/*
        A card with no legal move renders its trigger DISABLED WITH THE REASON BESIDE IT, not
        absent — `task.html`'s rule, and the tooltip trick the paragraphs above describe is not
        available on a disabled control (`disabled:pointer-events-none` means no hover reaches
        it). A line of text under the button costs one row and is readable by everybody.
      */}
      {!overlay && !movable && (
        <div className="mt-2">
          <Button
            size="sm"
            disabled
            aria-label={`Move ${item.title}`}
            data-testid="Board.Card.Move"
            className="min-h-6 px-2 py-0.5 text-[11px]"
          >
            Move to…
          </Button>
          <p data-testid="Board.Card.Immobile" className="m-0 mt-1 text-[10.5px] text-ink-4">
            {immobileReason(item)}
          </p>
        </div>
      )}

      {!overlay && movable && (
        <div className="mt-2">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                size="sm"
                aria-label={`Move ${item.title}`}
                data-testid="Board.Card.Move"
                className="min-h-6 px-2 py-0.5 text-[11px]"
              >
                Move to…
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {/*
                **Every destination the CARD states, not every column on the board** (011 D-c).

                Before, the board's five columns were the contract's five statuses, so "every
                column except this one" and "every status this issue could name" were the same
                list. They no longer are: a cross-project board draws the union of several
                workflows, and offering one project's `Ready for QA` on another project's card
                would be a destination the server has never heard of — which `ui-rules` forbids
                more strongly than it forbids hiding one.

                So the disabled-not-hidden rule is served on the ISSUE PAGE, where the workflow is
                known and every status can be listed with its reason (`task.html`). Here the menu
                is the card's own legal set, and a card with none is refused at the trigger with
                the same sentence `refusalFor` produces.
              */}
              {item.allowedTransitions.map((status) => (
                <DropdownMenuItem
                  key={status.id}
                  onSelect={() => onMove(status.name)}
                  data-testid={`Board.Card.MoveTo.${status.name}`}
                >
                  <span className="flex flex-col">
                    <span>{status.name}</span>
                    <span className="text-[10.5px] text-ink-4">{issueStatusKinds[status.kind]}</span>
                  </span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      )}
    </article>
  );
}
