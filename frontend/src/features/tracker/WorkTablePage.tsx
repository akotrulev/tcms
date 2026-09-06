import { useMemo } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import {
  useBoardCards,
  useBoardConfig,
  useBoards,
  useIssueTimeRollups,
  useIssues,
  useProject,
  useProjectSettings,
  useProjectWorkflow,
} from '../../api/queries';
import type { IssueSummary } from '../../api/types';
import type { IssueSort } from '../../api/queries';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import {
  FilterStrip,
  Table,
  TableBody,
  TableCell,
  TableFoot,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import {
  Button,
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatCount, formatHours, plural } from '../../lib/format';
import { issueLevels } from '../../lib/labels';
import { NativeSelect } from '../../components/ui/native-select';
import { BacklogView } from '../board/BacklogView';
import { ProjectBoardView } from '../board/ProjectBoardView';
import { useIssueFilterChips, useWorkFilter } from '../board/useWorkFilter';
import { CalendarView } from '../releases/CalendarView';
import { RoadmapView } from '../releases/RoadmapView';
import { StatusBadge } from './StatusBadge';
import { AiEligibleMarker } from './AiEligibleMarker';
import { TypeIcon } from './TypeIcon';

/**
 * `work-table.html` — the project's issues as a table.
 *
 * <h2>Five views, one navigation entry (D34)</h2>
 *
 * Board, Backlog, Table, Timeline and Calendar are one screen with a view switcher, not five rail
 * entries, and the filter is shared across them — which is why it lives in the URL rather than in
 * component state. ~~**Four of the five are not built in M1**~~ → **three, since 012 T7 landed the
 * board** (`features/board/ProjectBoardView.tsx`, which is the *project* board and not
 * `BoardPage.tsx`'s composite one). ~~Backlog arrives in T8~~ → **it landed there**
 * (`features/board/BacklogView.tsx`); Timeline and Calendar are M3's, and each still renders a panel
 * naming the module it arrives in rather than vanishing: a tab that disappears and reappears between
 * releases teaches that the product is unstable, while one that says *when* teaches the roadmap.
 *
 * <h2>The filter is `useWorkFilter`'s, and this page is no longer the only reader (012 T8)</h2>
 *
 * Three views now read one URL. The hook is the only reader and the only writer; this page takes
 * `filters.issue`, the board and the backlog take `filters.board`, and the two are a **mapping**
 * rather than a cast — they differ in both directions (D-v).
 *
 * **The board's panel named the previous board as still reachable and no longer does**, because the
 * absence that sentence covered is gone: `/projects/:id/board` still exists and still works, and it
 * is a different board (D-a) rather than this one's predecessor.
 *
 * <h2>Columns M1 has no data for are ABSENT from the picker, not greyed (D-f)</h2>
 *
 * The mockup shows a **Release** column, a **Logged** column, an `AI-eligible` filter chip and
 * label chips. Those belong to M3, M6, M5 and M2, and none of them is rendered here — not as an
 * empty column, and not as a disabled entry in the `⚙ Columns` picker.
 *
 * That is the opposite of this product's usual disable-don't-hide rule, and the difference is what
 * the absence would *say*. A greyed permission teaches "this exists and you may ask for it"; an
 * empty Release column teaches "this issue has no release" — a statement about the data, in a
 * product that cannot yet hold releases at all. The mockup's closing note (*"Logged hours are here
 * because this project has time tracking on"*) describes M6 and is likewise not rendered.
 */

const TEST_ID = 'WorkTable';

/**
 * The five views, in the mockup's order.
 *
 * ~~`module` null is the one that exists. The rest carry the sentence rendered in their place.~~ →
 * **all five exist as of 013 T5**, so the `module` / `body` / `existing` columns are gone and so is
 * the panel that read them. The shape is kept as a table rather than collapsed into the switcher's
 * JSX because it is what the switcher's order and labels come from, and it is where the sixth view
 * would be declared.
 *
 * ~~Five.~~ → **four, since 021 D-f.** `Board` is gone from this list and is not a view any more: the
 * strip lists **the project's boards, in position order**, and then these four. A project with three
 * boards gets three segments and a project with one gets one, because a board is a thing somebody
 * configured rather than a fixed view of the product — and nothing here spells "PM", "Dev" or "QA",
 * which are the names §2.4's seed happens to use.
 */
const VIEWS = [
  { id: 'backlog', label: 'Backlog' },
  { id: 'table', label: 'Table' },
  { id: 'timeline', label: 'Timeline' },
  { id: 'calendar', label: 'Calendar' },
] as const;

/**
 * Every column this build has data for, in the mockup's order.
 *
 * `sort` is the contract's own enum where the column can be ordered by, and absent where it cannot
 * — a header that offers a sort the server has no parameter for is a control that silently does
 * nothing. `Epic` and `Assignee` are the two: the contract sorts by key, title, status, dueDate
 * and estimateHours, and nothing else.
 */
const COLUMNS = [
  { id: 'type', label: 'Type', sort: undefined, numeric: false },
  { id: 'summary', label: 'Summary', sort: 'title' as const, numeric: false },
  { id: 'epic', label: 'Epic', sort: undefined, numeric: false },
  { id: 'status', label: 'Status', sort: 'status' as const, numeric: false },
  { id: 'assignee', label: 'Assignee', sort: undefined, numeric: false },
  { id: 'due', label: 'Due', sort: 'dueDate' as const, numeric: false },
  { id: 'est', label: 'Est', sort: 'estimateHours' as const, numeric: true },
  /*
    016 M6's column. No `sort`, and that is the contract rather than an omission: `GET /issues`
    orders by key, title, status, dueDate and estimateHours, and the hours are not on the issue
    list at all — they come from a second read (see `rollups` below). A header offering a sort the
    server has no parameter for is a control that silently does nothing.
  */
  { id: 'logged', label: 'Logged', sort: undefined, numeric: true },
] as const;

type ColumnId = (typeof COLUMNS)[number]['id'];

const ALL_COLUMNS = COLUMNS.map((column) => column.id);

export function WorkTablePage() {
  const { projectId = '' } = useParams();
  const [params] = useSearchParams();

  const project = useProject(projectId);
  /*
    Still read here rather than through the chips hook: the Status **picker** below is built from
    `workflow.data.statuses`, which is a control this page offers and the calendar does not.
    ~~`useProjectMembers`~~ moved into `useIssueFilterChips` with the chips that were its only
    reader (013 T5).
  */
  const workflow = useProjectWorkflow(projectId);

  const view = params.get('view') ?? 'table';
  const sort = (params.get('sort') ?? 'key') as IssueSort;

  /*
    ~~The filter, read out of the URL on every render rather than mirrored into state.~~ → **the
    same read, now in `useWorkFilter`** (012 T8). The reasoning is unchanged and moved with it:
    D34's shared filter means two views reading one filter cannot be a `useState` in either of
    them, and a store would be server-state's filter living somewhere the URL cannot restore from a
    pasted link. What changed is that there are now three readers, and `filters.issue` is the subset
    THIS endpoint declares — `IssueFilters`, which differs from the board's `BoardFilters` in both
    directions (D-v).
  */
  const { filters: work, setParam, dropOne, clearFilters } = useWorkFilter();
  const filters = work.issue;

  /*
    ~~Six chip entries spelled here.~~ → **`useIssueFilterChips`**, beside the board's own
    (013 T5). The calendar reads the same six parameters off the same URL, so a second copy would
    have regrown exactly the drift T8's extraction removed — and the first thing to drift between
    two such lists is *which* filters get a chip at all, which is how `epicKey` and `parentKey` came
    to have neither for a release.
  */
  const applied = useIssueFilterChips(projectId, filters, { setParam, dropOne });

  const visible = useMemo<ColumnId[]>(() => {
    const requested = params.get('cols');
    if (!requested) return [...ALL_COLUMNS];
    const chosen = new Set(requested.split(','));
    // Intersected with what this build HAS rather than trusted: a link shared from a later
    // release could name `release`, and a column id with no renderer behind it would be an empty
    // header — the exact thing D-f rules out, arriving through the URL instead of the picker.
    return ALL_COLUMNS.filter((column) => chosen.has(column));
  }, [params]);

  const issues = useIssues(projectId, filters, sort);

  /*
    016 D-g. `Logged` is the one column whose presence is the project's decision rather than the
    reader's, so it is filtered out of `visible` here instead of being offered in the picker and
    then rendering an empty cell — off means this project does not work this way, and a column of
    dashes says the opposite.

    **Withheld until that read answers**, rather than defaulted to on: a column that appears and
    then vanishes reflows the whole table, and the picker beside it would offer an entry it then
    took away. A read that FAILS falls back to on, because D-g's column default is on and losing a
    feature to a second read's failure is the worse of the two errors.
  */
  const settings = useProjectSettings(projectId);
  const tracksTime = !settings.isPending && (settings.data?.trackTime ?? true);
  const columns = useMemo(
    () => (tracksTime ? visible : visible.filter((column) => column !== 'logged')),
    [visible, tracksTime],
  );

  /*
    The whole column in ONE read, keyed on the rows the table is already holding — not one request
    per row, which would be fifty requests refetched on every filter change.

    `IssueSummary` carries `estimateHours` and no logged hours, so there is no way to draw this from
    the list alone; `GET /time/rollup/issues` is 016 T5's own addition for exactly this. It is
    optional: `meta.handled` and a missing row rendered as no figure, so a failed rollup costs the
    column and not the table.
  */
  const rowKeys = useMemo(() => issues.data?.map((issue) => issue.key) ?? [], [issues.data]);
  const rollups = useIssueTimeRollups(rowKeys, columns.includes('logged'));
  const loggedByKey = useMemo(
    () => new Map((rollups.data ?? []).map((row) => [row.issueKey, row.approvedHours])),
    [rollups.data],
  );

  /*
    021 D-f — the strip's first segments are the project's boards. `listBoards` orders by the
    `position` V13 added (it had none, and alphabetical would have put Dev before PM), so the order
    here is the order the server sent and nothing re-sorts it.
  */
  const boards = useBoards(projectId);
  const activeBoardId =
    view === 'board' ? params.get('board') ?? boards.data?.[0]?.id ?? '' : '';

  /*
    §8.3's **door badge**: how many cards are sitting in the board's FIRST column — what is waiting
    at this board's door, never a total. It is read out of the same two queries `ProjectBoardView`
    makes under the same keys, so this is a cache hit rather than a second request, and it stays in
    step with the board below it for free.

    **The ACTIVE segment only.** 021 T1 added no contract for the inactive boards' counts, on
    purpose, and inventing one here would mean a `GET …/cards` per board on every visit to the Table
    view. T8 badges the rest.
  */
  const activeConfig = useBoardConfig(activeBoardId);
  const activeCards = useBoardCards(activeBoardId, work.board);
  const doorCount = useMemo(() => {
    const first = activeConfig.data?.columns[0];
    if (!first || !activeCards.data) return null;
    return activeCards.data.lanes.reduce(
      (total, lane) => total + lane.cards.filter((card) => card.columnId === first.id).length,
      0,
    );
  }, [activeConfig.data, activeCards.data]);

  const current = VIEWS.find((entry) => entry.id === view) ?? VIEWS[1];

  return (
    <div>
      <title>{project.data ? `Work · ${project.data.name} · TCMS` : 'Work · TCMS'}</title>

      <header
        data-testid={`${TEST_ID}.Header`}
        className="mb-3 flex flex-wrap items-start gap-3 border-b border-line pb-3"
      >
        <div className="min-w-0 flex-1">
          <nav aria-label="Breadcrumb" className="mb-1 text-[12.5px] text-ink-3">
            <RouterLink
              to={`/projects/${projectId}`}
              className="text-ink-3 no-underline hover:text-ink-2"
              data-testid={`${TEST_ID}.Header.Breadcrumb`}
            >
              {project.data?.name ?? 'Project'}
            </RouterLink>
            <span className="px-1.5 text-ink-4">/</span>
            <b className="font-medium text-ink-2">Work</b>
          </nav>
          <h1
            data-testid={`${TEST_ID}.Header.Title`}
            className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink"
          >
            Work
          </h1>
          {/*
            ~~"The same issues the board is showing — a different shape, not a different query."~~ →
            that sentence was written when this header belonged to the table alone. The board is now
            one of the views under it, so the subtitle is `work-board.html`'s, which names the whole
            switcher and the one thing every view shares.
          */}
          <p className="m-0 mt-1 max-w-[74ch] text-[13px] text-ink-3">
            Board · Backlog · Table · Timeline · Calendar — one entry, one filter.
          </p>
        </div>

        <PermissionGate projectId={projectId} permission="issue:create">
          {({ allowed }) => (
            <Button
              variant="primary"
              size="sm"
              disabled={!allowed}
              asChild={allowed}
              data-testid={`${TEST_ID}.Add`}
            >
              {allowed ? <RouterLink to={`/projects/${projectId}/work/new`}>+ Add</RouterLink> : <span>+ Add</span>}
            </Button>
          )}
        </PermissionGate>
      </header>

      {/*
        The switcher. Real links, not buttons: a view is an address, and the query string travels
        with it — which is the whole of D34's "switching views never loses the filter".
      */}
      <nav aria-label="Work views" className="mb-3 flex flex-wrap gap-0.5 rounded-[8px] bg-wash-1 p-0.5">
        {(boards.data ?? []).map((board) => {
          const to = new URLSearchParams(params);
          to.set('view', 'board');
          to.set('board', board.id);
          const on = board.id === activeBoardId;
          return (
            <RouterLink
              key={board.id}
              to={{ search: `?${to.toString()}` }}
              aria-current={on ? 'page' : undefined}
              data-testid={`${TEST_ID}.View.Board.${board.name}`}
              className={cn(
                'flex items-center gap-1.5 rounded-md px-[11px] py-1 text-[12px] font-medium no-underline',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                on ? 'bg-panel text-ink shadow-e1' : 'bg-transparent text-ink-3 hover:text-ink-2',
              )}
            >
              {board.name}
              {/*
                The badge is an *awaiting you* count and never a total (§8.3), so it is drawn only
                when there is something at the door: a `0` beside every board is five characters
                that say nothing and train the eye to skip the place a real number will appear.
              */}
              {on && doorCount !== null && doorCount > 0 && (
                <span
                  data-testid={`${TEST_ID}.View.Board.${board.name}.Badge`}
                  className={cn(
                    'min-w-4 rounded-full bg-[color:var(--color-steel)] px-1 text-center',
                    'font-mono text-[10px] font-semibold leading-[15px] text-rail',
                  )}
                >
                  {formatCount(doorCount)}
                </span>
              )}
            </RouterLink>
          );
        })}

        {VIEWS.map((entry) => {
          const to = new URLSearchParams(params);
          to.set('view', entry.id);
          /* A view is not a board, so the board a reader last looked at does not travel with it. */
          to.delete('board');
          const on = view !== 'board' && entry.id === current.id;
          return (
            <RouterLink
              key={entry.id}
              to={{ search: `?${to.toString()}` }}
              aria-current={on ? 'page' : undefined}
              data-testid={`${TEST_ID}.View.${entry.id}`}
              className={cn(
                'rounded-md px-[11px] py-1 text-[12px] font-medium no-underline',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                on ? 'bg-panel text-ink shadow-e1' : 'bg-transparent text-ink-3 hover:text-ink-2',
              )}
            >
              {entry.label}
            </RouterLink>
          );
        })}
      </nav>

      {/*
        The board is a view of this screen, not a screen of its own — D34, and the reason the filter
        lives in the query string: switching to it keeps every chip. `ProjectBoardView` reads the
        same URL and maps it onto `BoardFilters`, which is a different parameter set from the table's
        (D-v) rather than the same one narrowed.
      */}
      {view === 'board' ? (
        <ProjectBoardView projectId={projectId} />
      ) : current.id === 'backlog' ? (
        <BacklogView projectId={projectId} />
      ) : current.id === 'timeline' ? (
        /*
          013 T5. **The roadmap has no filter strip and that is not an omission** — `GET
          /projects/{id}/roadmap` declares no filter parameters, so a strip here would narrow
          nothing. `RoadmapView`'s own header says so; the Calendar beside it does carry the strip,
          because `GET /issues` does take the filter.
        */
        <RoadmapView projectId={projectId} />
      ) : current.id === 'calendar' ? (
        <CalendarView projectId={projectId} />
      ) : (
        <>
          <FilterStrip
            data-testid={`${TEST_ID}.Filters`}
            applied={applied}
            onClear={applied.length > 0 ? clearFilters : undefined}
            pickers={
              <>
                <NativeSelect
                  aria-label="Level"
                  className="w-auto"
                  value={filters.level ?? ''}
                  data-testid={`${TEST_ID}.Filter.Level`}
                  onChange={(event) =>
                    setParam((next) => {
                      if (event.target.value) next.set('level', event.target.value);
                      else next.delete('level');
                    })
                  }
                >
                  <option value="">All levels</option>
                  {(['EPIC', 'STORY', 'TASK'] as const).map((level) => (
                    <option key={level} value={level}>
                      {issueLevels[level]}
                    </option>
                  ))}
                </NativeSelect>

                <NativeSelect
                  aria-label="Status"
                  className="w-auto"
                  value={filters.statusId?.[0] ?? ''}
                  data-testid={`${TEST_ID}.Filter.Status`}
                  onChange={(event) =>
                    setParam((next) => {
                      next.delete('statusId');
                      if (event.target.value) next.append('statusId', event.target.value);
                    })
                  }
                >
                  <option value="">All statuses</option>
                  {(workflow.data?.statuses ?? []).map((status) => (
                    <option key={status.id} value={status.id}>
                      {status.name}
                    </option>
                  ))}
                </NativeSelect>

                {/*
                  **The `⚙ Columns` picker offers only what this build HAS** (D-f). No Release and
                  no labels — not greyed, absent. The mockup's `AI-eligible` filter chip is gone for
                  the same reason and from the same rule.

                  ~~No Logged~~ → **landed 2026-09-04 (016 T5)**, and it is offered here only when
                  the project tracks time: 016 D-g's switch is the project's decision and this menu
                  is the reader's, so a project with tracking off does not list a column whose cells
                  would be dashes.
                */}
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button size="sm" data-testid={`${TEST_ID}.Columns`}>
                      Columns
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start">
                    <DropdownMenuLabel>Columns</DropdownMenuLabel>
                    {COLUMNS.filter(
                      (column) => tracksTime || column.id !== 'logged',
                    ).map((column) => (
                      <DropdownMenuCheckboxItem
                        key={column.id}
                        checked={columns.includes(column.id)}
                        data-testid={`${TEST_ID}.Columns.${column.id}`}
                        onCheckedChange={(checked) =>
                          setParam((next) => {
                            const chosen = checked
                              ? [...visible, column.id]
                              : visible.filter((id) => id !== column.id);
                            next.set('cols', ALL_COLUMNS.filter((id) => chosen.includes(id)).join(','));
                          })
                        }
                      >
                        {column.label}
                      </DropdownMenuCheckboxItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
              </>
            }
          />

          <DataRegion
            testId={`${TEST_ID}.Content`}
            state={{ ...issues, refetch: () => void issues.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            skeleton={<GridSkeleton rows={6} />}
            empty={
              applied.length > 0
                ? { kind: 'no-filter-match', onClearFilters: clearFilters }
                : {
                    kind: 'no-records',
                    title: 'No issues yet',
                    body:
                      'An issue is a thing to do — an epic, a story, a bug, or a task under '
                      + 'one of those. Adding one takes a single field.',
                    action: (
                      <PermissionGate projectId={projectId} permission="issue:create">
                        {({ allowed }) =>
                          allowed ? (
                            <Button variant="primary" size="sm" asChild>
                              <RouterLink
                                to={`/projects/${projectId}/work/new`}
                                data-testid={`${TEST_ID}.Empty.Add`}
                              >
                                Add the first one
                              </RouterLink>
                            </Button>
                          ) : (
                            <span />
                          )
                        }
                      </PermissionGate>
                    ),
                  }
            }
          >
            {(rows) => (
              <>
                <ScrollX>
                  <Table data-testid={`${TEST_ID}.Table`}>
                    <TableHead>
                      <TableRow>
                        <TableHeaderCell
                          sortDirection={sort === 'key' ? 'asc' : false}
                          onSort={() => setParam((next) => next.set('sort', 'key'))}
                        >
                          Key
                        </TableHeaderCell>
                        {COLUMNS.filter((column) => columns.includes(column.id)).map((column) => (
                          <TableHeaderCell
                            key={column.id}
                            align={column.numeric ? 'right' : 'left'}
                            sortDirection={
                              column.sort === undefined
                                ? undefined
                                : sort === column.sort
                                  ? 'asc'
                                  : false
                            }
                            onSort={
                              column.sort === undefined
                                ? undefined
                                : () => setParam((next) => next.set('sort', column.sort))
                            }
                            data-testid={`${TEST_ID}.Header.${column.id}`}
                          >
                            {column.label}
                          </TableHeaderCell>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.map((issue) => (
                        <IssueRow
                          key={issue.key}
                          issue={issue}
                          visible={columns}
                          loggedHours={loggedByKey.get(issue.key)}
                        />
                      ))}
                    </TableBody>
                  </Table>
                </ScrollX>

                <TableFoot
                  data-testid={`${TEST_ID}.Foot`}
                  summary={`${plural(rows.length, 'issue')}`}
                  sort={`sorted by ${COLUMNS.find((column) => column.sort === sort)?.label ?? 'key'}`}
                  keys={
                    columns.includes('logged')
                      ? [
                          `Est ${formatHours(estimateTotal(rows))}`,
                          `Logged ${formatHours(loggedTotal(rows, loggedByKey))} approved`,
                        ]
                      : [`Est ${formatHours(estimateTotal(rows))}`]
                  }
                />

                {/*
                  `work-table.html`'s note, minus its link. There is no Billing screen — M8 — so the
                  half of the sentence that pointed at one would be a promise this build cannot
                  keep. What is kept is the part that is true today and is the whole point of the
                  note: the column is here because the project turned it on, and what an hour costs
                  is on no screen anybody can reach.
                */}
                <p
                  className="mt-3 mb-0 text-[11.5px] leading-[1.55] text-ink-4"
                  data-testid={`${TEST_ID}.MoneyBoundary`}
                >
                  {columns.includes('logged')
                    ? 'Logged hours are here because this project has time tracking on, and they are '
                      + 'the approved ones. What an hour costs is not on this screen for anyone.'
                    : 'Time tracking is off for this project, so there is no Logged column. Hours '
                      + 'already recorded against these issues are kept.'}
                </p>
              </>
            )}
          </DataRegion>
        </>
      )}
    </div>
  );
}

/**
 * The estimate total, in hours.
 *
 * **Summed over the rows on screen, and the footer says "Est" rather than a claim about the
 * project.** Anything more is a number the server owns: D8's rule is one number in one place, and
 * a client-side total that filtering changes is not the same quantity as an epic's completion,
 * however similar it looks.
 */
function estimateTotal(rows: readonly IssueSummary[]): number {
  return rows.reduce((total, issue) => total + (issue.estimateHours ?? 0), 0);
}

/**
 * The approved hours on screen, summed over the rows the rollup answered for.
 *
 * **Approved only, and the footer says so.** 016 D-d: pending hours are a different number and
 * nothing in the product adds them, so a footer reading a mixture would be the one figure on this
 * screen a reader cannot check against anything.
 *
 * A row the rollup has no answer for contributes nothing — it is a key the caller may not read or
 * an issue nobody has logged against, and both are honestly zero *hours*, which is what a sum
 * wants. That is not the same as rendering `0h` in the cell, which claims somebody looked.
 */
function loggedTotal(
  rows: readonly IssueSummary[],
  loggedByKey: ReadonlyMap<string, number>,
): number {
  return rows.reduce((total, issue) => total + (loggedByKey.get(issue.key) ?? 0), 0);
}

function IssueRow({
  issue,
  visible,
  loggedHours,
}: {
  issue: IssueSummary;
  visible: readonly ColumnId[];
  /** Absent when the rollup has no row for this key — rendered as no figure, never as `0h`. */
  loggedHours: number | undefined;
}) {
  return (
    <TableRow data-testid={`${TEST_ID}.Row`}>
      <TableCell>
        <Identifier variant="muted">{issue.key}</Identifier>
      </TableCell>

      {visible.includes('type') && (
        <TableCell>
          <TypeIcon type={issue.type} data-testid={`${TEST_ID}.Row.Type`} />
        </TableCell>
      )}

      {visible.includes('summary') && (
        <TableCell variant="title">
          {/*
            `/issues/:key` — no project segment, because a key is unique in the only scope RLS lets
            a caller see, and it is what a person types into a commit message.
          */}
          <span className="flex min-w-0 items-center gap-2">
            <RouterLink
              to={`/issues/${encodeURIComponent(issue.key)}`}
              data-testid={`${TEST_ID}.Row.Title`}
              className="min-w-0 text-inherit no-underline hover:text-steel hover:underline"
            >
              {issue.title}
            </RouterLink>
            <AiEligibleMarker
              eligible={issue.agentEligible}
              data-testid={`${TEST_ID}.Row.AiEligible`}
            />
          </span>
        </TableCell>
      )}

      {visible.includes('epic') && (
        <TableCell>
          {issue.epicKey ? (
            <RouterLink
              to={`/issues/${encodeURIComponent(issue.epicKey)}`}
              className="text-ink-2 no-underline hover:text-steel hover:underline"
            >
              {issue.epicTitle ?? issue.epicKey}
            </RouterLink>
          ) : (
            // An em dash, not an empty cell: "no epic" is a real and ordinary state for a
            // story-level issue (requiring one would force a junk epic per stray bug), and a blank
            // reads as a value that failed to load.
            <span className="text-ink-4">&mdash;</span>
          )}
        </TableCell>
      )}

      {visible.includes('status') && (
        <TableCell>
          <StatusBadge status={issue.status} data-testid={`${TEST_ID}.Row.Status`} />
        </TableCell>
      )}

      {visible.includes('assignee') && (
        <TableCell>
          {issue.assignee ? (
            issue.assignee.displayName
          ) : (
            <span className="text-ink-4">Unassigned</span>
          )}
        </TableCell>
      )}

      {visible.includes('due') && (
        <TableCell
          className={cn(issue.overdue && issue.dueDate && 'text-[color:var(--color-failed)]')}
        >
          {issue.dueDate ?? <span className="text-ink-4">&mdash;</span>}
        </TableCell>
      )}

      {visible.includes('est') && (
        <TableCell align="right">
          {issue.estimateHours != null ? (
            <Identifier variant="muted">{formatHours(issue.estimateHours)}</Identifier>
          ) : (
            <span className="text-ink-4">&mdash;</span>
          )}
        </TableCell>
      )}

      {visible.includes('logged') && (
        <TableCell align="right">
          {loggedHours != null ? (
            <Identifier variant="muted" testId={`${TEST_ID}.Row.Logged`}>
              {formatHours(loggedHours)}
            </Identifier>
          ) : (
            // Not `0h`. Zero is the claim that somebody looked and nobody had logged an hour; an
            // absent row is the rollup not having answered for this key at all.
            <span className="text-ink-4">&mdash;</span>
          )}
        </TableCell>
      )}
    </TableRow>
  );
}
