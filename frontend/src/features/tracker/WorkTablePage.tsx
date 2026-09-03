import { useMemo } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { useIssues, useProject, useProjectWorkflow } from '../../api/queries';
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
import { formatCount, plural } from '../../lib/format';
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
 */
const VIEWS = [
  { id: 'board', label: 'Board' },
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

  const current = VIEWS.find((entry) => entry.id === view) ?? VIEWS[2];

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
        {VIEWS.map((entry) => {
          const to = new URLSearchParams(params);
          to.set('view', entry.id);
          return (
            <RouterLink
              key={entry.id}
              to={{ search: `?${to.toString()}` }}
              aria-current={entry.id === current.id ? 'page' : undefined}
              data-testid={`${TEST_ID}.View.${entry.id}`}
              className={cn(
                'rounded-md px-[11px] py-1 text-[12px] font-medium no-underline',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                entry.id === current.id
                  ? 'bg-panel text-ink shadow-e1'
                  : 'bg-transparent text-ink-3 hover:text-ink-2',
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
      {current.id === 'board' ? (
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
                  **The `⚙ Columns` picker offers only what this build HAS** (D-f). No Release, no
                  Logged, no labels — not greyed, absent. The mockup's `AI-eligible` filter chip is
                  gone for the same reason and from the same rule.
                */}
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button size="sm" data-testid={`${TEST_ID}.Columns`}>
                      Columns
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start">
                    <DropdownMenuLabel>Columns</DropdownMenuLabel>
                    {COLUMNS.map((column) => (
                      <DropdownMenuCheckboxItem
                        key={column.id}
                        checked={visible.includes(column.id)}
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
                        {COLUMNS.filter((column) => visible.includes(column.id)).map((column) => (
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
                        <IssueRow key={issue.key} issue={issue} visible={visible} />
                      ))}
                    </TableBody>
                  </Table>
                </ScrollX>

                <TableFoot
                  data-testid={`${TEST_ID}.Foot`}
                  summary={`${plural(rows.length, 'issue')}`}
                  sort={`sorted by ${COLUMNS.find((column) => column.sort === sort)?.label ?? 'key'}`}
                  keys={[`Est ${formatCount(estimateTotal(rows))}h`]}
                />
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

function IssueRow({ issue, visible }: { issue: IssueSummary; visible: readonly ColumnId[] }) {
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
            <Identifier variant="muted">{`${issue.estimateHours}h`}</Identifier>
          ) : (
            <span className="text-ink-4">&mdash;</span>
          )}
        </TableCell>
      )}
    </TableRow>
  );
}
