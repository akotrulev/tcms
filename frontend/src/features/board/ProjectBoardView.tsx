import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  pointerWithin,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { X } from 'lucide-react';
import { useMemo, useState, type CSSProperties } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { ApiError } from '../../api/client';
import {
  useBoardCards,
  useBoardConfig,
  useBoards,
  useLabels,
  useMoveCard,
  useProjectMembers,
  useUpdateBoard,
} from '../../api/queries';
import type {
  BoardConfig,
  BoardFilters,
  BoardIssueCard,
  Priority,
  SwimlaneBy,
} from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner, FilterStrip, type AppliedFilter } from '../../components/kit';
import { Button } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { cn } from '../../lib/cn';
import { issueLevels, priorityLabels, swimlaneByLabels } from '../../lib/labels';
import { plural } from '../../lib/format';
import { BoardCardFace } from './BoardCardFace';
import { ColumnHeader } from './ColumnHeader';
import { Swimlane } from './Swimlane';
import { boardKeyboardCoordinates } from './boardKeyboard';
import { resolveColumnMove, serverColumnsOf } from './cardMove';

/**
 * `work-board.html` — the **project** board, inside the Work switcher (012 M2, story B3).
 *
 * Not `BoardPage.tsx`, one file over, and the difference is D-a: that is the *composite* board over
 * `GET /board`, with four card kinds and columns assembled from the cards. This one reads
 * `GET /boards/{id}` and `GET /boards/{id}/cards`, its cards are all issues, and its columns are
 * `board_column` rows that know about statuses no card currently holds. The two share the one thing
 * that can be *wrong* — the move decision, in `cardMove.ts` — and nothing else.
 *
 * <h2>Two reads, because they have two lifetimes (D-h/D-u)</h2>
 *
 * Configuration and cards are separate queries under separate key roots. A column edit must not
 * evict the cards, or the layout relayouts under the reader's hand; a move must refresh every
 * board's cards without touching any board's configuration. One key gives neither property.
 *
 * <h2>The drop is refused here, before a request (§12.4, D-c)</h2>
 *
 * Every card carries its own `allowedTransitions`, so the legal set is known client-side and an
 * illegal drop is refused *at the drag* with the reason on screen — never accepted optimistically
 * and reverted, which reads as a bug every time and teaches people to distrust the board. The rank
 * drag in the backlog is the one that earns an optimistic patch, because there the legality is the
 * server's neighbours and the client cannot know it.
 *
 * <h2>The WIP breach reaches the reader twice, from two different facts (D-w)</h2>
 *
 * `useMoveCard` already raises the warning **toast** from `CardMoveResult.breach` — *your drop put
 * this column over* — and nothing here raises a second one. This screen owns the other half: the
 * **column header**, fed from `BoardContents.breaches`, which is counted over the whole project and
 * does not shrink under a filter. Two surfaces, two sentences, two questions.
 *
 * <h2>Three disagreements with the mockup, each recorded in the plan</h2>
 *
 * **The column headers are drawn once, not once per lane.** The mockup nests a full `.col-head` row
 * inside each lane, and only ever draws one expanded lane, so it never shows what two would look
 * like. Repeating the header would repeat one project-wide breach per lane and put a WIP numerator
 * beside a lane's own card count, which is not the quantity a WIP limit is about. With
 * `swimlaneBy=NONE` the result is the mockup exactly.
 *
 * **"Configure boards" is disabled with its reason.** That screen is T9's; a link to a route that
 * does not exist is worse than a control that says when it arrives (§31, and the release panel's
 * precedent one module over).
 *
 * **A collapsed lane does not survive a reload.** `board_swimlane` exists in `V5` and carries
 * exactly this per-viewer state, and **no endpoint on the contract reaches it** — so collapse is
 * component state here, and persisting it is a contract change rather than a screen decision.
 */

const TEST_ID = 'ProjectBoard';

/**
 * The catch-all lane's stand-in identity.
 *
 * `BoardLane.key` is null for it — under every axis, not only EPIC — and null cannot key a droppable
 * id or a collapse set. The pipe is the reason it cannot collide with a real key; see {@link cellId}.
 */
const NO_LANE = '|none';

/**
 * A cell's droppable id, and the one place the scheme is spelled.
 *
 * A drop target on a swimlane board is a **(lane, column) cell**: a column has one rect per lane, so
 * it cannot be one droppable, and `resolveColumnMove` wants a column **id** rather than whatever the
 * pointer landed on. So the id carries both halves and {@link columnIdOf} takes it apart, in this
 * file, beside the registration — two files spelling one scheme is how the halves come to disagree.
 *
 * **The separator is `|` because a column id is a uuid and a lane key is not free text.** A lane key
 * is an epic key, a uuid, a label id or a `Priority` member, none of which can contain a pipe; a
 * separator that a key *could* contain would produce a column id matching nothing, which is D-x's
 * failure mode exactly — a board on which nothing can be dragged, with no error anywhere.
 */
function cellId(laneId: string, columnId: string): string {
  return `${columnId}|${laneId}`;
}

function columnIdOf(over: string | number | null | undefined): string | null {
  if (over === null || over === undefined) return null;
  const [columnId] = String(over).split('|');
  return columnId ?? null;
}

export function ProjectBoardView({ projectId }: { projectId: string }) {
  const [params, setParams] = useSearchParams();
  const announce = useAnnounce();

  const boards = useBoards(projectId);
  const boardId = params.get('board') ?? boards.data?.[0]?.id ?? '';
  const config = useBoardConfig(boardId);

  /*
    The filter, read out of the URL on every render rather than mirrored into state — D34's shared
    filter, the way `WorkTablePage` already reads it. **`BoardFilters`, not `IssueFilters`** (D-v):
    the two differ in both directions, and a `statusId` over a board would empty the columns the
    board is drawn from. T8 extracts the shared hook and owns the mapping between them.
  */
  const filters = useMemo<BoardFilters>(() => {
    const level = params.get('level');
    const epicKey = params.get('epicKey');
    const labelId = params.getAll('labelId');
    const assigneeId = params.getAll('assigneeId');
    const priority = params.getAll('priority').filter(isPriority);
    return {
      labelId: labelId.length > 0 ? labelId : undefined,
      assigneeId: assigneeId.length > 0 ? assigneeId : undefined,
      epicKey: epicKey || undefined,
      level: level === 'EPIC' || level === 'STORY' || level === 'TASK' ? level : undefined,
      priority: priority.length > 0 ? priority : undefined,
      unparented: params.get('unparented') === 'true' ? true : undefined,
    };
  }, [params]);

  const cards = useBoardCards(boardId, filters);
  const labels = useLabels(projectId);
  const members = useProjectMembers(projectId, true);
  const move = useMoveCard(boardId, projectId);
  const updateBoard = useUpdateBoard(projectId);

  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [dragging, setDragging] = useState<BoardIssueCard | null>(null);
  const [rejected, setRejected] = useState<string | null>(null);

  /*
    `ServerColumn[]` via `serverColumnsOf` and never a hand-written map — D-x, and the one mapping in
    this slice the compiler cannot check: `statusIds` is `readonly string[]` and a status *name* is a
    string too, so mapping names typechecks, lints clean, and then refuses every legal drop on every
    board with no error anywhere.

    Memoised because the keyboard sensor is constructed from it: a new array each render rebuilds the
    sensor mid-drag, which drops the lift.
  */
  const columns = useMemo(() => serverColumnsOf(config.data?.columns ?? []), [config.data]);

  const lanes = useMemo(() => {
    const contents = cards.data;
    if (!contents) return [];
    return contents.lanes.map((lane) => ({
      lane,
      id: lane.key ?? NO_LANE,
      cards: visibleCards(lane, contents.swimlaneBy, contents.lanes),
    }));
  }, [cards.data]);

  const open = useMemo(() => lanes.filter((entry) => !collapsed.has(entry.id)), [lanes, collapsed]);

  /*
    The two axes the keyboard drag steps along, and both come out of the ONE flat set of cell
    droppables — there is no second registration for geometry.

      * `columnOrder` is the first EXPANDED lane's cells: a column's x is the same in every lane, and
        `stepToAdjacentColumn` reads only `left` and `width`.
      * `laneOrder` is the first column's cell down the expanded lanes, top to bottom, which is the y
        `stepToAdjacentLane` reads. A collapsed lane registers no cell and is therefore not a stop —
        stepping into a band a card cannot land in is a dead end the reader has to learn.

    An empty `laneOrder` IS the one-lane case, which is what makes up/down fall through to
    `@dnd-kit`'s own 25px nudge with nothing here special-casing it.
  */
  const columnOrder = useMemo(
    () => (open.length === 0 ? [] : columns.map((column) => cellId(open[0].id, column.id))),
    [columns, open],
  );
  const laneOrder = useMemo(
    () =>
      open.length < 2 || columns.length === 0
        ? []
        : open.map((entry) => cellId(entry.id, columns[0].id)),
    [columns, open],
  );

  const sensors = useSensors(
    // The 5px constraint is what keeps a click on the handle from being a zero-distance drag, and
    // `PointerSensor` is also what brings **touch** — a board is exactly the screen somebody opens
    // on a tablet in a stand-up.
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: boardKeyboardCoordinates(columnOrder, laneOrder),
    }),
  );

  /**
   * A finished drag and a chosen `⋮ Move to…` entry end in the same place.
   *
   * The decision is `resolveColumnMove`'s so it can be asked directly — driving a real pointer drag
   * through jsdom exercises `@dnd-kit`, not this. **Not optimistic**, which is §12.4 verbatim: the
   * legal set is on the card, so an illegal drop is refused here before a request, and there is
   * nothing an optimistic patch buys except the accept-then-revert that sentence rules out.
   */
  const commit = async (card: BoardIssueCard | null, over: string | number | null | undefined) => {
    const outcome = resolveColumnMove(card, columnIdOf(over), columns);
    if (outcome.outcome === 'NONE') return;
    if (outcome.outcome === 'REFUSED') {
      setRejected(outcome.reason);
      // The banner is inside `role="alert"`, but a refusal at the end of a *keyboard* drag arrives
      // while focus is still on the card — announcing it puts the sentence where the user is.
      announce(outcome.reason);
      return;
    }

    setRejected(null);
    const target = columns.find((column) => column.id === outcome.toColumnId);
    const moving = card as BoardIssueCard;
    try {
      await move.mutateAsync({ key: moving.key, toColumnId: outcome.toColumnId });
      announce(`Moved ${moving.key} to ${target?.name ?? 'the target column'}`);
    } catch (error) {
      /*
        The card never left — the move is not optimistic — so this says what happened, and says it
        twice on purpose: the banner persists (a toast about a refused move is gone before the eye
        returns to the card) and the announcement reaches a screen reader, for whom nothing on screen
        changed at all.

        The 409 the workflow answers with **names the legal moves**, and `ApiError.detail` is that
        sentence; `useMoveCard` sets `meta.handled` so the global "couldn't save" does not overwrite
        it. `tcmsFaults.issue('conflict')` is what produces one, because the menu only ever offers
        legal moves and no ordinary session can.
      */
      const reason =
        error instanceof ApiError
          ? error.detail
          : "Couldn't move that card. Check your connection and try again.";
      setRejected(reason);
      announce(`${moving.key} did not move. ${reason}`);
    }
  };

  const setParam = (mutate: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    mutate(next);
    setParams(next, { replace: true });
  };

  const appendOnce = (name: string, value: string) =>
    setParam((next) => {
      if (value && !next.getAll(name).includes(value)) next.append(name, value);
    });

  const dropOne = (name: string, value: string) =>
    setParam((next) => {
      const kept = next.getAll(name).filter((held) => held !== value);
      next.delete(name);
      for (const held of kept) next.append(name, held);
    });

  const applied: AppliedFilter[] = [
    ...(filters.labelId ?? []).map((id) => ({
      key: `label:${id}`,
      label: `Label · ${labels.data?.find((label) => label.id === id)?.name ?? id}`,
      onRemove: () => dropOne('labelId', id),
    })),
    ...(filters.assigneeId ?? []).map((id) => ({
      key: `assignee:${id}`,
      label: `Assignee · ${members.data?.find((member) => member.id === id)?.displayName ?? id}`,
      onRemove: () => dropOne('assigneeId', id),
    })),
    ...(filters.priority ?? []).map((value) => ({
      key: `priority:${value}`,
      label: `Priority · ${priorityLabels[value]}`,
      onRemove: () => dropOne('priority', value),
    })),
    /*
      `level`, `epicKey` and `unparented` get a chip but no picker. They are D34's other half: a
      reader who narrowed the Table to one epic and switched to Board must be able to see and remove
      that here, and a filter with no chip is one somebody stares at an empty board over. What they
      do not need is a second place to *apply* them — B3's board asks for label, assignee and
      priority, and §12.2's rule is that the strip's height is bounded by the reader's own choices.
    */
    ...(filters.level
      ? [
          {
            key: `level:${filters.level}`,
            label: `Level · ${issueLevels[filters.level]}`,
            onRemove: () => setParam((next) => next.delete('level')),
          },
        ]
      : []),
    ...(filters.epicKey
      ? [
          {
            key: `epic:${filters.epicKey}`,
            label: `Epic · ${filters.epicKey}`,
            onRemove: () => setParam((next) => next.delete('epicKey')),
          },
        ]
      : []),
    ...(filters.unparented
      ? [
          {
            key: 'unparented',
            label: 'No epic',
            onRemove: () => setParam((next) => next.delete('unparented')),
          },
        ]
      : []),
  ];

  /*
    Clearing keeps the view and the chosen board and drops everything else. The board is not a
    filter: clearing a filter set on the stakeholder board and landing on the engineering one is a
    surprise, and re-picking it is a second step nobody asked for.
  */
  const clearFilters = () => {
    const next = new URLSearchParams({ view: 'board' });
    const chosen = params.get('board');
    if (chosen) next.set('board', chosen);
    setParams(next, { replace: true });
  };

  return (
    <div data-testid={`${TEST_ID}.View`}>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        {/*
          The board picker. A project is seeded one board and may hold several over the same statuses
          (D2) — which is the whole of §0's multi-stakeholder ask, so the picker is one of the
          screen's own controls rather than a setting behind a link.
        */}
        <NativeSelect
          aria-label="Board"
          className="w-auto"
          value={boardId}
          data-testid={`${TEST_ID}.Picker`}
          onChange={(event) => setParam((next) => next.set('board', event.target.value))}
        >
          {(boards.data ?? []).map((board) => (
            <option key={board.id} value={board.id}>
              {board.name}
            </option>
          ))}
        </NativeSelect>

        {/*
          Grouping is the **board's** `swimlaneBy`, not a view preference — B3 says the board reloads
          into lanes, and the lanes are the server's. So it costs `board:configure` and is disabled
          with its reason for a caller who lacks it (§31), rather than hidden or silently ignored.

          The PATCH carries the board's existing columns back verbatim, because `BoardInput.columns`
          is a **replacement**: every column keeps its id, so none is created or deleted, and the
          array stays in the order the contract already sends them in.
        */}
        <PermissionGate projectId={projectId} permission="board:configure">
          {({ allowed, reason }) => (
            <NativeSelect
              aria-label="Group by"
              className="w-auto"
              disabled={!allowed || config.data === undefined || updateBoard.isPending}
              title={allowed ? undefined : reason}
              value={config.data?.swimlaneBy ?? 'NONE'}
              data-testid={`${TEST_ID}.GroupBy`}
              onChange={(event) => {
                const board = config.data;
                if (!board) return;
                updateBoard.mutate({
                  boardId: board.id,
                  input: { ...boardInputOf(board), swimlaneBy: event.target.value as SwimlaneBy },
                });
              }}
            >
              {(Object.keys(swimlaneByLabels) as SwimlaneBy[]).map((axis) => (
                <option key={axis} value={axis}>
                  {axis === 'NONE' ? swimlaneByLabels[axis] : `Group by: ${swimlaneByLabels[axis]}`}
                </option>
              ))}
            </NativeSelect>
          )}
        </PermissionGate>

        <span className="flex-1" />

        {/*
          `settings-boards.html` is T9's screen and does not exist yet, so the control says when it
          arrives rather than linking to a route that would 404. Disable-don't-hide, and the reason
          on it — the same answer the release panel gave one module over.
        */}
        <Button
          size="sm"
          disabled
          title="Board configuration — columns, WIP limits and labels — arrives with the board settings screen."
          data-testid={`${TEST_ID}.Configure`}
        >
          Configure boards
        </Button>
      </div>

      <FilterStrip
        data-testid={`${TEST_ID}.Filters`}
        applied={applied}
        onClear={applied.length > 0 ? clearFilters : undefined}
        summary={
          applied.length > 0 ? (
            /*
              Trello's semantics, copied precisely because they are learnable (§12.2): **OR within a
              category, AND across categories.** The sentence is rendered rather than assumed — a
              reader who picks two labels and one assignee and gets fewer cards than either alone has
              no way to tell a filter rule from a bug.
            */
            <span data-testid={`${TEST_ID}.Filters.Summary`}>
              any of the values within a filter, and every filter together
            </span>
          ) : undefined
        }
        pickers={
          <>
            <NativeSelect
              aria-label="Label"
              className="w-auto"
              value=""
              data-testid={`${TEST_ID}.Filter.Label`}
              onChange={(event) => appendOnce('labelId', event.target.value)}
            >
              <option value="">Add a label…</option>
              {(labels.data ?? []).map((label) => (
                <option key={label.id} value={label.id}>
                  {label.name}
                </option>
              ))}
            </NativeSelect>

            <NativeSelect
              aria-label="Assignee"
              className="w-auto"
              value=""
              data-testid={`${TEST_ID}.Filter.Assignee`}
              onChange={(event) => appendOnce('assigneeId', event.target.value)}
            >
              <option value="">Add an assignee…</option>
              {(members.data ?? []).map((member) => (
                <option key={member.id} value={member.id}>
                  {member.displayName}
                </option>
              ))}
            </NativeSelect>

            <NativeSelect
              aria-label="Priority"
              className="w-auto"
              value=""
              data-testid={`${TEST_ID}.Filter.Priority`}
              onChange={(event) => appendOnce('priority', event.target.value)}
            >
              <option value="">Add a priority…</option>
              {(Object.keys(priorityLabels) as Priority[]).map((value) => (
                <option key={value} value={value}>
                  {priorityLabels[value]}
                </option>
              ))}
            </NativeSelect>
          </>
        }
      />

      {/*
        The keyboard note is on the board, in the mockup's own words, and it is not chrome: WCAG 2.2
        requires the single-pointer alternative to exist, and a path nobody can discover is one
        nobody uses. The second sentence names the audience the criterion does not.
      */}
      <div
        data-testid={`${TEST_ID}.KeyboardNote`}
        className={cn(
          'mb-3.5 flex gap-[9px] rounded-[6px] border px-3 py-2.5 text-[12.5px] leading-[1.5] text-ink-2',
          'border-[color:var(--color-steel-line)] bg-[color:var(--color-steel-glow)]',
        )}
      >
        <span aria-hidden>&#9000;</span>
        <p className="m-0">
          <b className="font-semibold text-ink">Dragging is never the only way.</b> Every card carries
          a <span className="identifier">⋮</span> menu with &ldquo;Move to…&rdquo;, so a column change
          is reachable from the keyboard — and the same menu is what a touch user gets.
        </p>
      </div>

      {rejected && (
        <div role="alert" className="mb-3">
          <Banner tone="warn" data-testid={`${TEST_ID}.RejectedMove`} className="items-center">
            <span className="flex-1">{rejected}</span>
            <button
              type="button"
              onClick={() => setRejected(null)}
              aria-label="Dismiss"
              className={cn(
                'cursor-pointer border-0 bg-transparent p-0 text-ink-4 hover:text-ink',
                'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
              )}
            >
              <X aria-hidden className="size-3.5" />
            </button>
          </Banner>
        </div>
      )}

      {/*
        Three regions, nested, because three reads fail differently and each failure has its own
        scope: no boards is no board to draw, no configuration is no columns, and a failed cards read
        keeps the picker and the filter strip above it so the reader can change the query and retry.
      */}
      <DataRegion
        testId={`${TEST_ID}.Boards`}
        state={{ ...boards, refetch: () => void boards.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No boards on this project',
          body: 'A project is seeded a board when it is created, so this is worth reporting.',
        }}
      >
        {() => (
          <DataRegion
            testId={`${TEST_ID}.Config`}
            state={{ ...config, refetch: () => void config.refetch() }}
          >
            {(board) => (
              <DataRegion
                testId={`${TEST_ID}.Content`}
                state={{ ...cards, refetch: () => void cards.refetch() }}
                /*
                  **Empty is a state only under a filter**, and the first cut of this had it the
                  other way round.

                  A board with no cards and no filter still has to draw its columns: that is the
                  board's own shape, it is what a freshly configured one looks like, and replacing it
                  with a sentence hides the thing the reader came to see. The `+ Add` in the page
                  header above is the action, and the count line below says `0 cards shown` — so
                  nothing is unsaid.

                  A board emptied **by a filter** is the opposite case and does need the region: the
                  reader has to get back off it, and `no-filter-match` is the state carrying that way
                  out. Three of this file's own tests found the first cut, each by asking for a column
                  header on a board with no cards.
                */
                isEmpty={(contents) =>
                  applied.length > 0 && contents.lanes.every((lane) => lane.cards.length === 0)
                }
                empty={{ kind: 'no-filter-match', onClearFilters: clearFilters }}
              >
                {(contents) => (
                  <DndContext
                    sensors={sensors}
                    /*
                      `pointerWithin` rather than `closestCenter`: a cell is a big rectangle and a
                      card is small, so closest-centre can land a release near a cell's edge in its
                      neighbour. "The pointer is inside it" is the rule the user believes they are
                      following.
                    */
                    collisionDetection={pointerWithin}
                    onDragStart={(event) => setDragging(cardOf(open, event.active.id))}
                    onDragCancel={() => setDragging(null)}
                    onDragEnd={({ active, over }: DragEndEvent) => {
                      const card = cardOf(open, active.id);
                      // Cleared BEFORE the await: the cell highlighting keys on it, and leaving it
                      // set for the length of the request paints the board as mid-drag while the
                      // write is in flight.
                      setDragging(null);
                      void commit(card, over?.id);
                    }}
                  >
                    {/*
                      The board scrolls **inside itself** — the mockup's `.board`, and the responsive
                      rule: the page body must never scroll horizontally. Columns are the mockup's
                      fixed 272px, which is also what makes the header grid and each lane's grid
                      align without either knowing about the other.
                    */}
                    <div
                      className="max-w-full overflow-x-auto pb-2.5"
                      data-testid={`${TEST_ID}.Scroll`}
                    >
                      <div
                        className="min-w-max"
                        style={{ '--board-columns': columns.length } as CSSProperties}
                      >
                        <div className="mb-2 grid gap-3 [grid-template-columns:repeat(var(--board-columns),272px)]">
                          {columns.map((column) => (
                            <ColumnHeader
                              key={column.id}
                              column={column}
                              // Cards DRAWN in this column, across every expanded lane — see
                              // `ColumnHeader` for why the breach's own count is the other state's.
                              count={countIn(column.id, open)}
                              breach={contents.breaches.find(
                                (breach) => breach.columnId === column.id,
                              )}
                              testId={`${TEST_ID}.Column.${column.name}`}
                            />
                          ))}
                        </div>

                        <PermissionGate projectId={projectId} permission="issue:edit">
                          {({ allowed, reason }) =>
                            lanes.map((entry) => (
                              <Swimlane
                                key={entry.id}
                                lane={{ ...entry.lane, cards: entry.cards }}
                                laneId={entry.id}
                                swimlaneBy={contents.swimlaneBy}
                                columns={columns}
                                cellId={cellId}
                                collapsed={collapsed.has(entry.id)}
                                onToggle={() =>
                                  setCollapsed((held) => {
                                    const next = new Set(held);
                                    if (!next.delete(entry.id)) next.add(entry.id);
                                    return next;
                                  })
                                }
                                onMove={(card, toColumnId) =>
                                  void commit(card, cellId(entry.id, toColumnId))
                                }
                                canMove={allowed}
                                moveRefusal={reason}
                                testId={`${TEST_ID}.Lane.${entry.id === NO_LANE ? 'none' : entry.id}`}
                              />
                            ))
                          }
                        </PermissionGate>
                      </div>
                    </div>

                    <p className="m-0 mt-1 text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Count`}>
                      {`${plural(countIn(null, open), 'card')} shown`}
                      {board.swimlaneBy === 'NONE'
                        ? ''
                        : ` across ${plural(lanes.length, 'swimlane')}`}
                    </p>

                    {/*
                      The card follows the cursor instead of the browser's ghost image. Without it
                      the card stays in its cell while an invisible drag happens, so the gesture
                      gives no feedback at all until the drop.

                      `overlay` on the copy: the overlay is not the draggable, and passing the live
                      card would register a second draggable with the same id.
                    */}
                    <DragOverlay>
                      {dragging && (
                        <BoardCardFace
                          card={dragging}
                          columns={columns}
                          onMove={() => {}}
                          canMove={false}
                          moveRefusal={undefined}
                          overlay
                          testId={`${TEST_ID}.DragOverlay`}
                        />
                      )}
                    </DragOverlay>
                  </DndContext>
                )}
              </DataRegion>
            )}
          </DataRegion>
        )}
      </DataRegion>
    </div>
  );
}

/** A lane's identity plus the cards this board actually draws in it. */
interface OpenLane {
  id: string;
  cards: readonly BoardIssueCard[];
}

/**
 * Which of a lane's cards this board draws.
 *
 * **D-r's open question, answered: an epic's own card is suppressed from the catch-all lane when its
 * lane header is already on screen.** An epic reaches the wire as a card — `allowedTransitions` being
 * *"empty for an Epic"* has no meaning otherwise — and under `swimlaneBy=EPIC` it has no epic above
 * it, so the server files it in the "No epic" lane. Drawing it there as well as drawing it as a
 * band's own header states the same work twice in one view, and the second statement carries a
 * derived status beside a roll-up measuring its children: two facts about one epic, a glance apart,
 * which is the family of defect D8 exists over.
 *
 * **The condition is presence of the lane, not the axis alone**, and that is the part worth reading:
 * an epic with no stories has no lane of its own, so suppressing it unconditionally would delete it
 * from the board entirely. A board that silently omits a kind of work is worse than one showing an
 * unmovable card — which is the contract's own argument for putting the epic on the wire at all.
 */
function visibleCards(
  lane: { key: string | null; cards: readonly BoardIssueCard[] },
  swimlaneBy: SwimlaneBy,
  lanes: readonly { key: string | null }[],
): BoardIssueCard[] {
  if (swimlaneBy !== 'EPIC' || lane.key !== null) return [...lane.cards];
  return lane.cards.filter(
    (card) => card.typeLevel !== 'EPIC' || !lanes.some((other) => other.key === card.key),
  );
}

/** Cards drawn in one column across the expanded lanes, or every drawn card when `columnId` is null. */
function countIn(columnId: string | null, open: readonly OpenLane[]): number {
  return open.reduce(
    (total, entry) =>
      total
      + (columnId === null
        ? entry.cards.length
        : entry.cards.filter((card) => card.columnId === columnId).length),
    0,
  );
}

/**
 * The dragged card, looked up among the **expanded** lanes only.
 *
 * A collapsed lane renders no card, so nothing in one can be lifted — and searching every lane would
 * find a card that is not on screen, whose drop would move work the reader cannot see.
 */
function cardOf(open: readonly OpenLane[], id: string | number): BoardIssueCard | null {
  for (const entry of open) {
    const card = entry.cards.find((row) => row.key === id);
    if (card) return card;
  }
  return null;
}

/**
 * The board's current definition as `BoardInput` — a faithful round-trip, so a grouping change
 * changes only the grouping.
 *
 * `columns` on the update is a **replacement, not a patch**: a column with an `id` is kept, one
 * without is created, and one whose id is absent is deleted. Every column keeps its id here and the
 * array stays in the order the contract sent it, which is `position` order — so this rewrites
 * nothing. `wipLimit ?? null` rather than `|| null` keeps *no limit* and a limit of zero apart, the
 * same distinction `serverColumnsOf` holds one file over.
 */
function boardInputOf(board: BoardConfig) {
  return {
    name: board.name,
    swimlaneBy: board.swimlaneBy,
    columns: board.columns.map((column) => ({
      id: column.id,
      name: column.name,
      wipLimit: column.wipLimit ?? null,
      statusIds: column.statuses.map((status) => status.id),
      defaultStatusId: column.defaultStatusId,
    })),
  };
}

function isPriority(value: string): value is Priority {
  return value === 'CRITICAL' || value === 'HIGH' || value === 'MEDIUM' || value === 'LOW';
}
