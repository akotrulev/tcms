import {
  DndContext,
  DragOverlay,
  PointerSensor,
  pointerWithin,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { ChevronDown, ChevronUp, GripVertical, X } from 'lucide-react';
import { Fragment, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { useCan } from '../../auth/useCan';
import {
  useBacklog,
  useBulkUpdateIssues,
  useIssues,
  useLabels,
  useProjectMembers,
  useRankIssue,
  useReleases,
  type BulkIssueResult,
  type RankPlacement,
} from '../../api/queries';
import type { BacklogRow, Priority, ReleaseSummary } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner, Chip, FilterStrip } from '../../components/kit';
import { NativeSelect } from '../../components/ui/native-select';
import { cn } from '../../lib/cn';
import { formatDate, plural } from '../../lib/format';
import { priorityLabels } from '../../lib/labels';
import { labelFill } from '../../lib/statusColors';
import { StatusBadge } from '../tracker/StatusBadge';
import { TypeIcon } from '../tracker/TypeIcon';
import { useBoardFilterChips, useWorkFilter } from './useWorkFilter';

const TEST_ID = 'Backlog';

/**
 * The `<option>` value that means *take these out of their release*.
 *
 * A sentinel rather than the empty string, which is already taken by the select's own "Move to
 * release…" placeholder — and a placeholder that silently unscheduled five stories the moment
 * somebody re-opened the menu and pressed Escape wrong is the write nobody would report as a bug
 * because nobody would know it happened.
 */
const NO_RELEASE = '__none__';

/**
 * The ranked backlog — story B4, `work-backlog.html`.
 *
 * <h2>The release sections are two slices of ONE order (D-k)</h2>
 *
 * ~~One list, not the mockup's release sections (D-f) — `release` is M3's table.~~ → **superseded
 * 2026-09-01 (plan 013, T6).** `work-backlog.html`'s "2.4 · target 18 Sep" and "Unscheduled"
 * headers are here, and so is **Move to release**.
 *
 * **`issue.rank` is one project-wide sequence and stays one.** The grouping is therefore a
 * *rendering* of that sequence and not a second order: {@link sectionsOf} walks the rows the server
 * sent, in the order it sent them, and marks where a run of one release ends. The headers sit inside
 * the existing `DataRegion` and **above the `<ol>`** — one list, one `<ol>`, headers between runs of
 * `<li>`s — so a drag from one section into another is still one `PUT /issues/{key}/rank` against a
 * neighbour, because the neighbour is the row above or below it in the same sequence.
 *
 * The consequence worth stating: `place(fromIndex, toIndex)` indexes the **flat** row array, and a
 * section header is not a row. Nothing here renumbers or offsets an index, and the test that moves a
 * row across a boundary and asserts the request body names the neighbour rather than the header is
 * what holds that true.
 *
 * <h2>Two ways to reorder and both persist</h2>
 *
 * The mockup states it on the panel: *"drag the ⠿ handle, or use ↑ / ↓ — both persist"*, and every
 * row carries both. The arrows are **not a fallback**: they are the better keyboard path, they are
 * what a touch user gets, and they end in the same mutation the drag does — see {@link BacklogView}
 * ' `place`. An arrow taking a different path would be the keyboard route quietly not getting the
 * rollback, which is exactly the class of defect keyboard parity exists to prevent, arriving through
 * the fix for it.
 *
 * So there is no `KeyboardSensor` here and its absence is the decision, not an omission: the board
 * needed one because a column change had no other keyboard shape until `⋮ Move to…`; a list has two
 * buttons per row that say what they do.
 *
 * <h2>The row's number is the server's</h2>
 *
 * `position` is 1-based over what came back, derived from rank order and **never stored** — a stored
 * position is what the fractional rank exists to avoid (D-d). `reorder` renumbers it inside the
 * optimistic patch so a moved row does not draw its old number beside its new place.
 *
 * <h2>An epic is not a row here (D-r)</h2>
 *
 * `GET /projects/{id}/backlog` returns STORY and TASK levels only: D31 derives an epic's status from
 * its stories, so it has nothing to be ranked against, and a list carrying both an epic and the
 * stories under it double-counts the same work in the one view whose whole job is "what is next".
 * Nothing here filters for that — the server does — and the consequence worth knowing is that
 * `Set epic` is offered over rows that may be either level, which {@link BulkBar} gates.
 *
 * <h2>The two release pickers replace each other rather than accumulating</h2>
 *
 * `releaseId` and `unscheduled` are a 400 when sent together, so choosing one clears the other —
 * enforced at both ends, here in the writes and again in {@link useWorkFilter}'s read. Two
 * parameters rather than `releaseId=null` for `unparented`'s reason: an absent parameter already
 * means *don't filter*, and a client cannot spell "no release" in a value.
 */
export function BacklogView({ projectId }: { projectId: string }) {
  const announce = useAnnounce();
  const { filters, setParam, appendOnce, dropOne, clearFilters } = useWorkFilter();
  /*
    Read directly rather than through `PermissionGate`, and this is the one place in the screen
    where that is right. The gate's denied branch wraps its child in a tooltip-triggering
    `<span role="none">` — correct around one control, and around a whole `<ol>` it is both invalid
    HTML and a tooltip that opens over every row. So the verdict comes down as two props and each
    disabled control carries the reason on its own `title`, which is what the gate would have done
    per control anyway.
  */
  const gate = useCan(projectId);
  const canRank = gate.can('board:rank');
  const rankRefusal = gate.reason('board:rank');

  /*
    `filters.backlog`, not `filters.board` — the backlog declares `releaseId` and `unscheduled` and
    `GET /boards/{id}/cards` does not (013 T6; the reason is in `useWorkFilter`'s `WorkFilters`).
  */
  const backlog = useBacklog(projectId, filters.backlog);
  const rank = useRankIssue(projectId);
  const labels = useLabels(projectId);
  const members = useProjectMembers(projectId, true);
  const releases = useReleases(projectId);
  const applied = useBoardFilterChips(projectId, filters.backlog, { setParam, dropOne });

  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [dragging, setDragging] = useState<BacklogRow | null>(null);

  const rows = backlog.data ?? [];

  const sensors = useSensors(
    // The 5px constraint keeps a click on the handle from being a zero-distance drag, and
    // `PointerSensor` is what brings touch — the board's constant, for the board's reason.
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  );

  /*
    The one place a row's destination becomes a request, and the reason the arrows and the drag
    cannot disagree about where a row lands.

    **Moving up = place BEFORE the row above; moving down = place AFTER the row below.** Not "swap
    ranks": a swap works for adjacent rows and silently does the wrong thing the first time somebody
    adds a multi-row move. Dragging to the top is `beforeKey: <the first row's key>` and never an
    empty body — the server answers 400 for both neighbours and for neither (D-q), and
    `RankPlacement` is a union precisely so neither payload can be constructed here.
  */
  const place = (fromIndex: number, toIndex: number) => {
    if (toIndex < 0 || toIndex >= rows.length || toIndex === fromIndex) return;
    const row = rows[fromIndex];
    const neighbour = rows[toIndex];
    const placement: RankPlacement =
      toIndex < fromIndex
        ? { key: row.key, beforeKey: neighbour.key }
        : { key: row.key, afterKey: neighbour.key };
    rank.mutate(placement);
    announce(
      `${row.key} moved ${toIndex < fromIndex ? 'above' : 'below'} ${neighbour.key}.`,
    );
  };

  const toggle = (key: string) =>
    setSelected((held) => {
      const next = new Set(held);
      if (!next.delete(key)) next.add(key);
      return next;
    });

  const chosen = rows.filter((row) => selected.has(row.key));
  const sections = sectionsOf(rows, releases.data ?? []);

  return (
    <div data-testid={`${TEST_ID}.View`}>
      <FilterStrip
        data-testid={`${TEST_ID}.Filters`}
        applied={applied}
        onClear={applied.length > 0 ? clearFilters : undefined}
        summary={
          applied.length > 0 ? (
            <span data-testid={`${TEST_ID}.Filters.Summary`}>
              any of the values within a filter, and every filter together
            </span>
          ) : undefined
        }
        /*
          The board's three pickers, because the backlog reads the **same** parameter set — one
          endpoint's declaration, not a resemblance. There is no Status picker for D-v's reason and
          no Level picker for B3's: the chips above carry whatever the Table set, so a reader who
          narrowed there can still take it off without a control for applying it here.
        */
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

            {/*
              The mockup's `Release: 2.4` control, and the fourth picker on this strip — the one the
              board does not get. `set` rather than `appendOnce`: `releaseId` is a single value, so a
              second choice replaces the first instead of narrowing to two versions at once, which
              the endpoint could not answer anyway.

              Both writes **drop the other parameter**, because sending `releaseId` and
              `unscheduled` together is a 400. Enforced here as well as in the read, so the URL never
              holds the pair rather than being repaired every time it is parsed.
            */}
            <NativeSelect
              aria-label="Release"
              className="w-auto"
              value={filters.backlog.releaseId ?? ''}
              data-testid={`${TEST_ID}.Filter.Release`}
              onChange={(event) =>
                setParam((next) => {
                  next.delete('unscheduled');
                  if (event.target.value) next.set('release', event.target.value);
                  else next.delete('release');
                })
              }
            >
              <option value="">Any release…</option>
              {(releases.data ?? []).map((release) => (
                <option key={release.id} value={release.id}>
                  {release.version}
                </option>
              ))}
            </NativeSelect>

            {/*
              A `Chip`, not a fifth select: it is one boolean the reader turns on, and `Chip` is the
              type in this kit for a value the reader owns and can take back. `unparented` beside it
              has no picker at all (B3), which is the asymmetry this one does not repeat: "what has no
              release yet" is the question this screen is grouped around, so it earns a control.
            */}
            <Chip
              selected={filters.backlog.unscheduled === true}
              onClick={() =>
                setParam((next) => {
                  next.delete('release');
                  if (filters.backlog.unscheduled) next.delete('unscheduled');
                  else next.set('unscheduled', 'true');
                })
              }
              data-testid={`${TEST_ID}.Filter.Unscheduled`}
            >
              No release
            </Chip>
          </>
        }
      />

      {/*
        A contextual toolbar that arrives with the selection and leaves with it, rather than a row of
        controls disabled most of the time. Mounted rather than hidden, so its own two reads — the
        project's epics and its members — do not run on a screen nobody has selected anything on.
      */}
      {chosen.length > 0 && (
        <BulkBar
          projectId={projectId}
          rows={chosen}
          onDone={(result) => setSelected(new Set(result.failed.map((row) => row.key)))}
          onClear={() => setSelected(new Set())}
        />
      )}

      <section
        data-testid={`${TEST_ID}.Panel`}
        className="rounded-lg border border-line bg-panel"
      >
        <div className="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2.5">
          <h2 className="m-0 text-[13px] font-semibold text-ink">Backlog</h2>
          <span className="text-[11.5px] text-ink-3" data-testid={`${TEST_ID}.Count`}>
            {plural(rows.length, 'issue')} in rank order
          </span>
          <span className="flex-1" />
          {/*
            The mockup's own sentence, and it is not decoration: WCAG 2.2 requires the
            single-pointer alternative to exist, and a path nobody can discover is one nobody uses.
            Rendered only when there is something to reorder — a drag instruction over an empty list
            contradicts the empty state right below it.
          */}
          {rows.length > 0 && (
            <span className="text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Hint`}>
              drag the <span aria-hidden>⠿</span> handle, or use ↑ / ↓ — both persist
            </span>
          )}
        </div>

        <DataRegion
          testId={`${TEST_ID}.Content`}
          state={{ ...backlog, refetch: () => void backlog.refetch() }}
          isEmpty={(list) => list.length === 0}
          /*
            **Empty is a state only under a filter** — the board's rule, ported rather than copied.
            A board with no cards still draws its columns because those are the board's own shape; a
            list's shape *is* its rows, so there is nothing left to draw, and both empties get a
            region. What the rule actually buys here is the pair of sentences: a project with nothing
            left to do is finished work and reads as such, while a list emptied **by** a filter needs
            the way back off it, which is what `no-filter-match` carries.

            The strip and the panel head are above this region rather than inside it, so a failed read
            never unmounts the controls that would let a reader change the query and retry.
          */
          empty={
            applied.length > 0
              ? { kind: 'no-filter-match', onClearFilters: clearFilters }
              : {
                  kind: 'no-records',
                  title: 'Nothing left in the backlog',
                  body:
                    'Every story and task on this project is either done or in progress. An epic '
                    + 'is never a backlog row — its status comes from the stories under it.',
                }
          }
        >
          {(list) => (
            <DndContext
              sensors={sensors}
              // A row is a tall thin rectangle and a dragged row is the same height, so
              // closest-centre lands a release a row off near a boundary. "The pointer is inside
              // it" is the rule the user believes they are following.
              collisionDetection={pointerWithin}
              onDragStart={(event) =>
                setDragging(list.find((row) => row.key === event.active.id) ?? null)
              }
              onDragCancel={() => setDragging(null)}
              onDragEnd={({ active, over }: DragEndEvent) => {
                setDragging(null);
                if (!over) return;
                place(
                  list.findIndex((row) => row.key === active.id),
                  list.findIndex((row) => row.key === over.id),
                );
              }}
            >
              {/*
                ONE `<ol>`, with headers between runs of rows — never two lists. `sectionsOf` marks
                the index each run starts at and the row indices are untouched, so `place` still
                indexes the flat array and a drag across a boundary is one neighbour-relative rank
                write. A second `<ol>` per section would have made that arithmetic per-section, which
                is the second order D-k refuses.
              */}
              <ol className="m-0 list-none p-0" data-testid={`${TEST_ID}.List`}>
                {list.map((row, index) => {
                  const head = sections.get(index);
                  return (
                    <Fragment key={row.key}>
                      {head && <SectionHead head={head} />}
                      <BacklogListRow
                        row={row}
                        selected={selected.has(row.key)}
                        onSelect={() => toggle(row.key)}
                        onUp={() => place(index, index - 1)}
                        onDown={() => place(index, index + 1)}
                        first={index === 0}
                        last={index === list.length - 1}
                        canRank={canRank}
                        rankRefusal={rankRefusal}
                      />
                    </Fragment>
                  );
                })}
              </ol>

              {/*
                The row follows the cursor instead of the browser's ghost image. Without it the row
                stays where it was while an invisible drag happens, so the gesture gives no feedback
                at all until the drop.
              */}
              <DragOverlay>
                {dragging && (
                  <div
                    data-testid={`${TEST_ID}.DragOverlay`}
                    className={cn(
                      'flex items-center gap-2 rounded-md border border-line-strong bg-panel',
                      'px-2.5 py-2 text-[13px] text-ink shadow-e3',
                    )}
                  >
                    <Identifier variant="muted" className="text-[11.5px]">
                      {dragging.key}
                    </Identifier>
                    <span className="truncate">{dragging.title}</span>
                  </div>
                )}
              </DragOverlay>
            </DndContext>
          )}
        </DataRegion>
      </section>
    </div>
  );
}


/**
 * Where a section starts, and what its header says.
 *
 * `releaseId` is `null` for the Unscheduled run. `done` / `open` are the release's own story counts
 * and are absent for Unscheduled, which has no release to count over.
 */
interface SectionHead {
  releaseId: string | null;
  title: string;
  done: number | null;
  open: number | null;
  rows: number;
}

/**
 * The mockup's two section headers, derived from **the order the server sent** (D-k).
 *
 * A run ends where the next row's release differs from this one's, and that is the whole rule —
 * nothing here groups, sorts or partitions. `issue.rank` is one project-wide sequence, so a release
 * that appears twice in the list would legitimately get two headers: the sections are a *rendering*
 * of the order and not a claim that the order is grouped. In practice a backlog is usually ranked
 * release-first, and if it is not, two headers for `2.4` is the truth about where its rows are.
 *
 * A `Map` keyed by the row index rather than an array of `{ rows: [...] }` groups, because the row
 * index **is** the argument `place(fromIndex, toIndex)` takes: an array of groups would mean
 * translating a group-local index back to a flat one at every call site, which is the arithmetic
 * this shape does not have.
 */
function sectionsOf(
  rows: readonly BacklogRow[],
  releases: readonly ReleaseSummary[],
): Map<number, SectionHead> {
  const heads = new Map<number, SectionHead>();

  rows.forEach((row, index) => {
    const previous = index === 0 ? undefined : rows[index - 1];
    const id = row.release?.id ?? null;
    if (previous !== undefined && (previous.release?.id ?? null) === id) return;

    let rowsInRun = 0;
    for (let at = index; at < rows.length && (rows[at].release?.id ?? null) === id; at += 1) {
      rowsInRun += 1;
    }

    /*
      The mockup's `2.4 · target 18 Sep`. The date comes off the row's own `ReleaseRef` rather than
      from the release list, so a header still names its date when the list read has not landed —
      and `21 done · 6 open` comes off the list's inline readiness, which is why it is nullable here
      and rendered only when that read has.
    */
    const summary = id === null ? undefined : releases.find((release) => release.id === id);
    const target = row.release?.targetDate ?? null;

    heads.set(index, {
      releaseId: id,
      title:
        row.release === null || row.release === undefined
          ? 'Unscheduled'
          : target
            ? `${row.release.version} · target ${formatDate(target)}`
            : row.release.version,
      done: summary ? summary.readiness.storiesDone : null,
      open: summary ? summary.readiness.storiesTotal - summary.readiness.storiesDone : null,
      rows: rowsInRun,
    });
  });

  return heads;
}

/**
 * A section header, and it is **not a list item**.
 *
 * `role="presentation"` on the `<li>`: `<ol>` permits only `<li>` children, so a heading between two
 * runs of rows has to be one — and a real list item here would make the list's announced length the
 * row count plus the number of sections, on the one screen whose numbers are its content. The
 * heading inside it is what a reader hears.
 *
 * <h2>`21 done · 6 open` is the release's numbers, not the section's, and says so</h2>
 *
 * They come off `ReleaseSummary.readiness` — the same inline counts the release list and the
 * overview's panel draw, so the three cannot disagree. Two things they are not: they count **stories**
 * (D8's set, spikes excluded) while a section holds stories and tasks, and they are **not narrowed by
 * the filters above**. Both are on the `title`, because a number whose basis is invisible is the
 * two-contradictory-percentages defect waiting for its second reader.
 */
function SectionHead({ head }: { head: SectionHead }) {
  return (
    <li
      role="presentation"
      data-testid={`${TEST_ID}.Section.${head.releaseId ?? 'unscheduled'}`}
      className="flex flex-wrap items-center gap-2 border-b border-line bg-wash-1 px-3 py-1.5"
    >
      <h3 className="m-0 text-[12px] font-semibold text-ink">{head.title}</h3>
      {head.releaseId === null ? (
        <span className="text-[11.5px] text-ink-4">
          {`${plural(head.rows, 'issue')} with no target release`}
        </span>
      ) : (
        <span
          className="text-[11.5px] text-ink-4"
          title={
            head.done === null
              ? undefined
              : 'Counted over every story targeting this release — spikes excluded, and not narrowed by the filters above. The rows below are what the filters left.'
          }
        >
          {head.done !== null && head.open !== null
            ? `${head.done} done · ${head.open} open`
            : plural(head.rows, 'issue')}
        </span>
      )}
    </li>
  );
}

/**
 * One row — the mockup's `.rank-row`, and both of its reorder affordances.
 *
 * The row is a **droppable** and the handle alone is the **draggable**, for `BoardCardFace`'s
 * reason: the row carries a link and a checkbox, and making the whole row draggable turns every
 * attempt to press one into the start of a drag. Both registrations use the issue key, which is what
 * `@dnd-kit` hands back on `onDragEnd` and what the placement is spelled in.
 *
 * `↑` on the first row and `↓` on the last are **disabled with a reason** rather than hidden — the
 * position they would move to does not exist, and a control that vanishes on the first row is one a
 * reader has to discover twice.
 */
function BacklogListRow({
  row,
  selected,
  onSelect,
  onUp,
  onDown,
  first,
  last,
  canRank,
  rankRefusal,
}: {
  row: BacklogRow;
  selected: boolean;
  onSelect: () => void;
  onUp: () => void;
  onDown: () => void;
  first: boolean;
  last: boolean;
  canRank: boolean;
  rankRefusal: string | undefined;
}) {
  const { attributes, listeners, setNodeRef: setDragRef, isDragging } = useDraggable({
    id: row.key,
    disabled: !canRank,
  });
  const { setNodeRef: setDropRef, isOver } = useDroppable({ id: row.key });

  return (
    <li
      ref={setDropRef}
      data-testid={`${TEST_ID}.Row.${row.key}`}
      className={cn(
        'flex flex-wrap items-center gap-2 border-b border-line px-3 py-2 last:border-b-0',
        selected && 'bg-wash-2',
        isOver && 'border-b-[color:var(--color-given)] bg-wash-1',
        isDragging && 'opacity-40',
      )}
    >
      <input
        type="checkbox"
        checked={selected}
        onChange={onSelect}
        aria-label={`Select ${row.key}`}
        data-testid={`${TEST_ID}.Row.${row.key}.Select`}
        className="size-3.5 flex-none accent-[color:var(--color-action-primary)]"
      />

      <button
        ref={setDragRef}
        type="button"
        {...attributes}
        {...listeners}
        disabled={!canRank}
        title={canRank ? undefined : rankRefusal}
        aria-label={`Drag ${row.key}`}
        data-testid={`${TEST_ID}.Row.${row.key}.DragHandle`}
        className={cn(
          'flex size-6 flex-none cursor-grab touch-none items-center justify-center rounded-sm',
          'border-0 bg-transparent p-0 text-ink-4 hover:text-ink-2 disabled:cursor-not-allowed',
          'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
        )}
      >
        <GripVertical aria-hidden className="size-3.5" />
      </button>

      {/*
        `position` as sent. Derived from rank order server-side and never stored, so nothing here
        counts the rows: a client-side index would disagree with the server the moment a filter hid
        a row between two others.
      */}
      <span
        data-testid={`${TEST_ID}.Row.${row.key}.Position`}
        className="w-6 flex-none text-right font-mono text-[11.5px] text-ink-4"
      >
        {row.position}
      </span>

      <TypeIcon
        type={{ name: row.typeName, level: row.typeLevel }}
        data-testid={`${TEST_ID}.Row.${row.key}.Type`}
      />
      <Identifier variant="muted" className="flex-none text-[11.5px] text-ink-3">
        {row.key}
      </Identifier>

      <RouterLink
        to={`/issues/${encodeURIComponent(row.key)}`}
        data-testid={`${TEST_ID}.Row.${row.key}.Title`}
        className="min-w-0 flex-1 truncate text-[13px] text-ink no-underline hover:text-steel hover:underline"
      >
        {row.title}
      </RouterLink>

      {/*
        ~~Plain chips. `Label.color` is a design-token name (`label-1` … `label-10`) and those ten
        tokens are not declared in `index.css`, so a hue here would be invented — the palette is the
        label-creating screen's decision (D-z).~~ → **012 T9 declared the ten**, so the chip is
        filled from `labelFill`, which falls back to the plain wash for a token this stylesheet does
        not know. The caution the old sentence carried is what that fallback is: a chip with the
        wrong colour is worse than one with none, because the word is what is being scanned anyway.
      */}
      {row.labels.map((label) => (
        <span
          key={label.id}
          data-testid={`${TEST_ID}.Row.${row.key}.Label.${label.id}`}
          style={{ background: labelFill(label.color) }}
          className="flex-none rounded-full border border-line px-2 py-0.5 text-[11px] text-ink"
        >
          {label.name}
        </span>
      ))}

      <StatusBadge status={row.status} data-testid={`${TEST_ID}.Row.${row.key}.Status`} />

      {row.estimateHours != null && (
        <Identifier variant="muted" className="flex-none text-[11.5px]">
          {`${row.estimateHours}h`}
        </Identifier>
      )}

      <span className="flex-none text-[11.5px] text-ink-3">
        {row.assignee?.displayName ?? <span className="text-ink-4">Unassigned</span>}
      </span>

      <span className="flex flex-none gap-0.5">
        <MoveButton
          direction="up"
          issueKey={row.key}
          onClick={onUp}
          disabled={!canRank || first}
          reason={canRank ? 'This is already the top of the backlog.' : rankRefusal}
        />
        <MoveButton
          direction="down"
          issueKey={row.key}
          onClick={onDown}
          disabled={!canRank || last}
          reason={canRank ? 'This is already the bottom of the backlog.' : rankRefusal}
        />
      </span>
    </li>
  );
}

function MoveButton({
  direction,
  issueKey,
  onClick,
  disabled,
  reason,
}: {
  direction: 'up' | 'down';
  issueKey: string;
  onClick: () => void;
  disabled: boolean;
  reason: string | undefined;
}) {
  const Glyph = direction === 'up' ? ChevronUp : ChevronDown;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={disabled ? reason : undefined}
      aria-label={`Move ${issueKey} ${direction}`}
      data-testid={`${TEST_ID}.Row.${issueKey}.${direction === 'up' ? 'Up' : 'Down'}`}
      className={cn(
        'flex size-6 items-center justify-center rounded-sm border border-line bg-transparent',
        'cursor-pointer p-0 text-ink-3 hover:text-ink disabled:cursor-not-allowed disabled:text-ink-4',
        'focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
      )}
    >
      <Glyph aria-hidden className="size-3.5" />
    </button>
  );
}

/**
 * The mockup's "N selected" bar — **Move to release**, **Set epic** and **Assign**.
 *
 * ~~No "Move to release" (D-f) — `release` is M3's table.~~ → **superseded 2026-09-01 (plan 013,
 * T6).** It is the mockup's first control and it is here, and it is **N `PATCH`es, not a bulk
 * endpoint** (D-l): a bulk operation over issues was considered and refused for this module, because
 * it is a `be` task with a partial-failure contract of its own and the screen that would justify it —
 * a hundred-row release move — is not a flow anybody has yet. Recorded so the next person finds the
 * decision rather than the limit.
 *
 * <h2>Set epic is refused over a TASK-level row, before the request</h2>
 *
 * The contract's depth rule answers 400 for *"a parent more than one level above the child"*: a
 * Task's parent is a Story, so setting an epic on one is a guaranteed rejection. `typeLevel` is on
 * every row, so the client knows — and `ui-rules`' rule is that an action the backend will always
 * refuse in this state is disabled with its reason, not rendered and rejected.
 *
 * <h2>A mixed outcome is reported per item</h2>
 *
 * There is no bulk endpoint over issues, so this is one `PATCH` per row and some of them can fail
 * while others land. A blanket success toast over that is the worse half of the defect — the reader
 * believes all five went through — so the bar names the ones that failed and **keeps them selected**,
 * which is what makes "try again" mean the three that did not rather than all five.
 */
function BulkBar({
  projectId,
  rows,
  onDone,
  onClear,
}: {
  projectId: string;
  rows: readonly BacklogRow[];
  onDone: (result: BulkIssueResult) => void;
  onClear: () => void;
}) {
  const epics = useIssues(projectId, { level: 'EPIC' }, 'key');
  const members = useProjectMembers(projectId, true);
  const releases = useReleases(projectId);
  const bulk = useBulkUpdateIssues(projectId);
  const [report, setReport] = useState<BulkIssueResult | null>(null);

  const keys = rows.map((row) => row.key);
  const tasks = rows.filter((row) => row.typeLevel === 'TASK');

  const apply = (
    input: { parentKey: string } | { assigneeId: string } | { releaseId: string | null },
  ) => {
    setReport(null);
    bulk.mutate(
      { keys, input },
      {
        onSuccess: (result) => {
          setReport(result);
          onDone(result);
        },
      },
    );
  };

  return (
    <div
      data-testid={`${TEST_ID}.Bulk`}
      className="mb-3 rounded-lg border border-line bg-panel px-3 py-2.5"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="m-0 text-[13px] font-semibold text-ink" data-testid={`${TEST_ID}.Bulk.Count`}>
          {plural(rows.length, 'issue')} selected
        </h2>
        <span className="flex-1" />

        <PermissionGate projectId={projectId} permission="issue:edit">
          {({ allowed, reason }) => (
            <>
              {/*
                The mockup's `Move to release ▾`, and `issue:edit` is its gate rather than
                `release:manage`: what this writes is `issue.releaseId`, a field on the issue, so the
                key is the one that lets somebody edit an issue. A caller who may plan releases and
                not edit issues cannot re-target somebody else's story from a list.

                A `— No release —` option as well as the versions, because taking a story *out* of a
                release is the same field set to null and there is nowhere else on this screen to do
                it. `releaseId` is nullable on `IssueUpdate` precisely for that.
              */}
              <NativeSelect
                aria-label="Move to release"
                className="w-auto"
                value=""
                disabled={!allowed || bulk.isPending}
                title={allowed ? undefined : reason}
                data-testid={`${TEST_ID}.Bulk.Release`}
                onChange={(event) =>
                  apply({ releaseId: event.target.value === NO_RELEASE ? null : event.target.value })
                }
              >
                <option value="">Move to release…</option>
                <option value={NO_RELEASE}>— No release —</option>
                {(releases.data ?? []).map((release) => (
                  <option key={release.id} value={release.id}>
                    {release.version}
                  </option>
                ))}
              </NativeSelect>

              <NativeSelect
                aria-label="Set epic"
                className="w-auto"
                value=""
                disabled={!allowed || tasks.length > 0 || bulk.isPending}
                title={
                  tasks.length > 0
                    ? `A task's parent is a story, not an epic — ${tasks[0].key} is a task.`
                    : allowed
                      ? undefined
                      : reason
                }
                data-testid={`${TEST_ID}.Bulk.Epic`}
                onChange={(event) => apply({ parentKey: event.target.value })}
              >
                <option value="">Set epic…</option>
                {(epics.data ?? []).map((epic) => (
                  <option key={epic.key} value={epic.key}>
                    {epic.title}
                  </option>
                ))}
              </NativeSelect>

              <NativeSelect
                aria-label="Assign"
                className="w-auto"
                value=""
                disabled={!allowed || bulk.isPending}
                title={allowed ? undefined : reason}
                data-testid={`${TEST_ID}.Bulk.Assign`}
                onChange={(event) => apply({ assigneeId: event.target.value })}
              >
                <option value="">Assign…</option>
                {(members.data ?? []).map((member) => (
                  <option key={member.id} value={member.id}>
                    {member.displayName}
                  </option>
                ))}
              </NativeSelect>
            </>
          )}
        </PermissionGate>

        <Chip onClick={onClear} data-testid={`${TEST_ID}.Bulk.Clear`}>
          Clear selection
        </Chip>
      </div>

      {report && report.failed.length > 0 && (
        <div role="alert" className="mt-2">
          <Banner tone="warn" data-testid={`${TEST_ID}.Bulk.Report`} className="items-center">
            <span className="flex-1" data-testid={`${TEST_ID}.Bulk.Report.Text`}>
              {`${report.applied.length} of ${report.applied.length + report.failed.length} updated. `}
              {`${report.failed.map((row) => `${row.key} (${row.reason})`).join(', ')} did not, `}
              {'and are still selected.'}
            </span>
            <button
              type="button"
              onClick={() => setReport(null)}
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
    </div>
  );
}
