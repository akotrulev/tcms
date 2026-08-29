import {
  createColumnHelper,
  flexRender,
  rowPaginationFeature,
  rowSelectionFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type OnChangeFn,
  type PaginationState,
  type RowSelectionState,
  type SortingState,
} from '@tanstack/react-table';
import { useMemo, type ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import type { TestCaseSummary } from '../../api/types';
import { AutomationLabel, HealthChip, LifecycleChip, PriorityLabel, StatusDot } from '../../components/StatusChip';
import { Identifier } from '../../components/Identifier';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { Checkbox } from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatAge, formatCount, formatDateTime } from '../../lib/format';
import { healthLabels } from '../../lib/labels';
import { DEFAULT_SORT } from './useCaseFilters';

/**
 * The case grid. Server-side paging, sorting, and filtering throughout — a repository of a
 * thousand-plus cases is not loaded into the browser so one screen can sort it, and having
 * *some* grids page server-side while others load everything is how two pages that look
 * identical behave differently under load.
 *
 * Column choices follow the display rules: names not ids, humanized enums from the shared
 * label maps, capped widths with the full value in a tooltip, and identifiers in the
 * tabular mono face so keys line up down the column.
 *
 * ~~Rendered by MUI X's DataGrid.~~ → **superseded 2026-08-29 (plan 007, T9).** The engine is
 * TanStack Table and the markup is the kit's `Table`, so the grid answers to the design language
 * rather than to a second component library's theme. Nothing about the *contract* moved: the same
 * eleven columns, the same test ids, the same server-side everything. Two things did.
 *
 * The tooltips are now the native `title` attribute. A tooltip component per truncated cell is
 * ~250 mounted components on a full page for text the browser will show for free, and the value
 * being tooltipped here is always the full text of what the cell already truncates.
 *
 * And the *Title* cell carries a quiet second line — design-language §5's Table rule (UX#113 /
 * UX#111): a failing case says what it failed on, in the cell, rather than earning a twelfth
 * column that would be empty on every healthy row. The configuration name is the field that makes
 * the line mean something (005 S1); it was reachable only by hovering the *Last result* cell
 * before, which is not a place anybody hovers while scanning for the red one.
 */

const TestIdPrefix = 'Cases';

/**
 * Only the four features this grid uses, so v9 tree-shakes the rest. `stockFeatures` would
 * restore MUI-style all-features-included and the bundle cost that goes with it.
 */
const features = tableFeatures({
  rowPaginationFeature,
  rowSelectionFeature,
  rowSortingFeature,
  columnMeta: {} as {
    width?: number;
    align?: 'left' | 'right';
    variant?: 'default' | 'title';
    /** The kit's quiet second line, per row — never a column of its own. */
    subtitle?: (row: TestCaseSummary) => ReactNode;
  },
});

const helper = createColumnHelper<typeof features, TestCaseSummary>();

const PAGE_SIZES = [25, 50, 100];

/** The class of truncated cell that keeps its full value in a `title`. */
const truncated = 'block overflow-hidden text-ellipsis whitespace-nowrap';

function caseColumns(projectId: string) {
  return helper.columns([
    helper.display({
      id: 'select',
      meta: { width: 34 },
      // A leading checkbox column: row-click-only selection is undiscoverable, and bulk
      // editing is the workflow this screen exists to make survivable. Select-all is the
      // PAGE's rows, not the query's — a checkbox that silently selected 1,284 unseen rows
      // is the one that turns a bulk edit into an incident.
      header: ({ table }) => (
        <Checkbox
          checked={
            table.getIsAllPageRowsSelected()
              ? true
              : table.getIsSomePageRowsSelected()
                ? 'indeterminate'
                : false
          }
          onCheckedChange={(checked) => table.toggleAllPageRowsSelected(checked === true)}
          aria-label="Select all cases on this page"
          data-testid={`${TestIdPrefix}.SelectAll`}
        />
      ),
      cell: ({ row }) => (
        <Checkbox
          checked={row.getIsSelected()}
          onCheckedChange={(checked) => row.toggleSelected(checked === true)}
          aria-label={`Select ${row.original.key}`}
          data-testid={`${TestIdPrefix}.Row.Select`}
        />
      ),
    }),
    helper.accessor('key', {
      id: 'key',
      header: 'Key',
      meta: { width: 104 },
      cell: ({ row }) => (
        // The row → detail affordance is a real link, so middle-click, ⌘-click and
        // "copy link" all work, and it's keyboard-reachable without the grid's help.
        <RouterLink
          to={`/projects/${projectId}/cases/${row.original.id}`}
          data-testid={`${TestIdPrefix}.Row.KeyLink`}
          style={{ textDecoration: 'none' }}
        >
          <Identifier className="text-steel">{row.original.key}</Identifier>
        </RouterLink>
      ),
    }),
    helper.accessor('title', {
      id: 'title',
      header: 'Title',
      meta: {
        width: 320,
        variant: 'title',
        subtitle: (row) =>
          row.lastResult?.statusKind === 'FAILED'
            ? `Last run failed on ${row.lastResult.configurationName}`
            : null,
      },
      cell: ({ row }) => (
        // The title is a link too, not just the key. It's the largest target in the row and
        // the thing people actually aim at — a grid where only a 60px key cell navigates
        // reads as broken even though the affordance technically exists.
        <RouterLink
          to={`/projects/${projectId}/cases/${row.original.id}`}
          data-testid={`${TestIdPrefix}.Row.Title`}
          className="text-ink no-underline hover:underline"
        >
          {row.original.title}
        </RouterLink>
      ),
    }),
    helper.accessor('suiteName', {
      id: 'suiteName',
      header: 'Suite',
      meta: { width: 150 },
      cell: ({ row }) => (
        // `suiteName` is null for an unfiled case (§96) — a real answer, not a missing value, so
        // the cell says so rather than rendering blank. A blank here reads as a row whose suite
        // failed to load, which is the one reading that would send somebody looking for a bug.
        <span
          title={row.original.suiteName ?? 'Filed in no suite'}
          className={cn(truncated, row.original.suiteName === null && 'italic text-ink-3')}
          data-testid={`${TestIdPrefix}.Row.Suite`}
        >
          {row.original.suiteName ?? 'Not filed'}
        </span>
      ),
    }),
    helper.accessor('lifecycleStatus', {
      id: 'lifecycleStatus',
      header: 'Status',
      meta: { width: 118 },
      cell: ({ row }) => (
        <LifecycleChip status={row.original.lifecycleStatus} testId={`${TestIdPrefix}.Row.Lifecycle`} />
      ),
    }),
    helper.accessor('priority', {
      id: 'priority',
      header: 'Priority',
      meta: { width: 106 },
      cell: ({ row }) => <PriorityLabel priority={row.original.priority} testId={`${TestIdPrefix}.Row.Priority`} />,
    }),
    helper.accessor('automationStatus', {
      id: 'automationStatus',
      header: 'Automation',
      meta: { width: 132 },
      cell: ({ row }) => (
        <AutomationLabel status={row.original.automationStatus} testId={`${TestIdPrefix}.Row.Automation`} />
      ),
    }),
    helper.accessor('lastResult', {
      id: 'lastResult',
      header: 'Last result',
      meta: { width: 168 },
      // Composite cell — no single server field orders it meaningfully, and a half-sorted
      // column reads as broken. Sort by the fields that do (see `enableSorting: false`).
      enableSorting: false,
      cell: ({ row }) =>
        row.original.lastResult?.statusKind ? (
          <span
            /*
              The configuration is named (005 S1), and it is the field that makes this line mean
              something: "Failed 3 days ago" is a fact about a case, "Failed 3 days ago on Chrome"
              is a fact somebody can act on — and the same case may be green on the next
              configuration. Never null on the wire; every run has one. A failure also says it in
              the Title cell's second line now, where a scanning eye is already looking.

              ~~And a count of linked defects.~~ → **dropped (005 §6, item 10).** The count is not
              on this summary and putting it there means a join per row for a number that answers
              nothing on its own: two linked defects on a passing case is not worse than one, and
              the case's own screen has the list.
            */
            title={`${formatDateTime(row.original.lastResult.executedAt)} · ${row.original.lastResult.configurationName}${
              row.original.lastResult.releaseVersion ? ` · release ${row.original.lastResult.releaseVersion}` : ''
            }`}
            className="inline-flex items-center gap-2"
            data-testid={`${TestIdPrefix}.Row.LastResult`}
          >
            <StatusDot kind={row.original.lastResult.statusKind} />
            <span className="text-[12px] text-ink-3">{formatAge(row.original.lastResult.executedAt)}</span>
          </span>
        ) : (
          <span className="text-ink-3" data-testid={`${TestIdPrefix}.Row.LastResult`}>
            Never run
          </span>
        ),
    }),
    helper.accessor('healthSignals', {
      id: 'healthSignals',
      header: 'Health',
      meta: { width: 200 },
      enableSorting: false,
      cell: ({ row }) =>
        row.original.healthSignals.length === 0 ? (
          <span className="text-ink-3">—</span>
        ) : (
          <span className="inline-flex items-center gap-1 overflow-hidden" data-testid={`${TestIdPrefix}.Row.Health`}>
            {/* One chip plus an overflow count: three chips per row turns the column into
                noise, and the detail page carries the full list.

                THE FIRST ONE IS THE WORST ONE, and that is the server's doing rather than
                this component's — `CaseHealth.signals()` sorts by severity (§179). It used
                to arrive in enum-declaration order, which put `UNLINKED` (INFO, and true of
                most cases since §178 gave it a writer) ahead of a case whose automation had
                stopped reporting: the row showed the missing link and hid the failure behind
                a +1. Do not sort here — a client-side sort would be the second copy of a
                severity ranking that §179 deleted from this file. */}
            <HealthChip
              signal={row.original.healthSignals[0].signal}
              severity={row.original.healthSignals[0].severity}
            />
            {row.original.healthSignals.length > 1 && (
              /* The LABELS, not the codes. This tooltip read `STALE_AUTOMATION, UNLINKED`
                 until §179 — the one place in the SPA that showed a reader a raw enum
                 constant, which is what `lib/labels.ts` exists to prevent, and invisible
                 because it needs two signals on one case to appear at all. */
              <span
                className="self-center text-[12px] text-ink-3"
                title={row.original.healthSignals
                  .slice(1)
                  .map((raised) => healthLabels[raised.signal])
                  .join(', ')}
              >
                +{row.original.healthSignals.length - 1}
              </span>
            )}
          </span>
        ),
    }),
    // Names, never the owner's uuid. Nullable because test_case.owner_id is — an imported
    // case, or one whose owner has been offboarded from this company, genuinely has none.
    // "Unassigned" rather than an empty cell, which reads as a rendering failure.
    helper.accessor((row) => row.owner?.displayName ?? 'Unassigned', {
      id: 'owner',
      header: 'Owner',
      meta: { width: 140 },
      cell: ({ row }) => (
        <span
          title={row.original.owner?.email ?? 'No owner assigned'}
          className={cn(truncated, !row.original.owner && 'text-ink-3')}
          data-testid={`${TestIdPrefix}.Row.Owner`}
        >
          {row.original.owner?.displayName ?? 'Unassigned'}
        </span>
      ),
    }),
    helper.accessor('updatedAt', {
      id: 'updatedAt',
      header: 'Updated',
      meta: { width: 116 },
      cell: ({ row }) => (
        <span
          title={formatDateTime(row.original.updatedAt)}
          className="text-ink-3"
          data-testid={`${TestIdPrefix}.Row.Updated`}
        >
          {formatAge(row.original.updatedAt)}
        </span>
      ),
    }),
  ]);
}

export function CaseGrid({
  rows,
  total,
  page,
  size,
  sort,
  selection,
  onSelectionChange,
  onPageChange,
  onSizeChange,
  onSortChange,
  projectId,
  loading,
}: {
  rows: TestCaseSummary[];
  total: number;
  page: number;
  size: number;
  sort: string;
  selection: string[];
  onSelectionChange: (ids: string[]) => void;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
  onSortChange: (sort: string) => void;
  projectId: string;
  loading: boolean;
}) {
  const columns = useMemo(() => caseColumns(projectId), [projectId]);

  const [sortField, sortDir] = sort.split(',');

  /**
   * The grid may only be told to sort by a column it actually has.
   *
   * The default sort is `keySeq,asc` — the server's stable insertion order, and the unique
   * tiebreaker that keeps a row from appearing on two pages — which has no column here because
   * "TC-7 before TC-12" is what the *Key* column already shows.
   *
   * ~~Handing that field to `sortModel` looked harmless: the rows arrive in the right order
   * either way, the URL is clean, and every gate stayed green. What it actually did was make the
   * grid sanitize an unknown field out of its model **during its own render** and echo the
   * correction back through `onSortModelChange` → `setSort` → `setSearchParams`, i.e. a router
   * state update while a different component was rendering. React logged "Cannot update a
   * component (`BrowserRouter`) while rendering a different component (`DataGridRaw`)" on every
   * single load of this screen, and nothing else went wrong — which is why it survived three
   * browser passes.~~ → **superseded with the engine (T9).** TanStack Table does not sanitize
   * sorting state against the column list, so there is nothing to echo and no warning to prevent.
   *
   * The filter stays anyway, for the smaller reason that was always the other half of it: a
   * header must not claim an order it is not carrying. A sort the grid can't display simply
   * shows no arrow, which is the truth.
   */
  const knownFields = new Set(columns.map((column) => column.id));
  const sorting: SortingState =
    sortField && knownFields.has(sortField) ? [{ id: sortField, desc: sortDir === 'desc' }] : [];

  const pagination: PaginationState = { pageIndex: page, pageSize: size };

  const rowSelection: RowSelectionState = useMemo(
    () => Object.fromEntries(selection.map((id) => [id, true])),
    [selection],
  );

  const onSortingChange: OnChangeFn<SortingState> = (updater) => {
    const next = typeof updater === 'function' ? updater(sorting) : updater;
    const first = next[0];
    const value = first ? `${first.id},${first.desc ? 'desc' : 'asc'}` : DEFAULT_SORT;
    // Guard the echo as well as the input: an emission that restates the sort we are already
    // on must not write to the URL, whatever else changes above.
    if (value !== sort) onSortChange(value);
  };

  const onPaginationChange: OnChangeFn<PaginationState> = (updater) => {
    const next = typeof updater === 'function' ? updater(pagination) : updater;
    if (next.pageSize !== size) onSizeChange(next.pageSize);
    if (next.pageIndex !== page) onPageChange(next.pageIndex);
  };

  const onRowSelectionChange: OnChangeFn<RowSelectionState> = (updater) => {
    const next = typeof updater === 'function' ? updater(rowSelection) : updater;
    onSelectionChange(Object.keys(next).filter((id) => next[id]));
  };

  const table = useTable({
    features,
    data: rows,
    columns,
    // A domain id, never the row index: selection and every row locator have to survive paging.
    getRowId: (row) => row.id,
    // Server-side everything: the grid is a view over a query, not an owner of data. The count
    // is the server's too — deriving it from `rows.length` caps the grid at one page.
    manualPagination: true,
    manualSorting: true,
    rowCount: total,
    // There is no `manualFiltering: true` because there is no filtering feature registered to
    // turn off: filtering is the URL's and the server's (`useCaseFilters`), and a table that
    // never learned to filter cannot quietly start doing it in the browser. The flag would be
    // the weaker guarantee of the two.
    // One sort field, because that is what the query parameter carries.
    enableMultiSort: false,
    state: { sorting, pagination, rowSelection },
    onSortingChange,
    onPaginationChange,
    onRowSelectionChange,
  });

  const modelRows = table.getRowModel().rows;
  const first = total === 0 ? 0 : page * size + 1;
  const last = page * size + modelRows.length;

  return (
    <div data-testid={`${TestIdPrefix}.Grid`}>
      <Table className="min-w-[1240px]">
        <TableHead>
          {table.getHeaderGroups().map((group) => (
            <TableRow key={group.id}>
              {group.headers.map((header) => {
                const meta = header.column.columnDef.meta;
                const canSort = header.column.getCanSort();
                return (
                  <TableHeaderCell
                    key={header.id}
                    align={meta?.align}
                    style={{ width: meta?.width }}
                    sortDirection={canSort ? header.column.getIsSorted() : undefined}
                    onSort={canSort ? () => header.column.toggleSorting() : undefined}
                  >
                    {flexRender(header.column.columnDef.header, header.getContext())}
                  </TableHeaderCell>
                );
              })}
            </TableRow>
          ))}
        </TableHead>
        {/*
          A refetch dims the rows in place rather than swapping them for a skeleton: the rows on
          screen are still the answer to the previous query, and replacing a full page of them
          with grey bars on every filter keystroke is a flicker, not a loading state. The initial
          load never reaches here — `DataRegion` holds the skeleton above this component.
        */}
        <TableBody className={loading ? 'opacity-60' : undefined}>
          {modelRows.map((row) => (
            <TableRow key={row.id} selected={row.getIsSelected()}>
              {row.getAllCells().map((cell) => {
                const meta = cell.column.columnDef.meta;
                return (
                  <TableCell
                    key={cell.id}
                    align={meta?.align}
                    variant={meta?.variant}
                    subtitle={meta?.subtitle?.(row.original)}
                    style={{ width: meta?.width }}
                  >
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </TableCell>
                );
              })}
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <div
        className="flex items-center justify-end gap-4 px-3.5 py-2 text-[12px] text-ink-3"
        data-testid={`${TestIdPrefix}.Pager`}
      >
        <label className="flex items-center gap-1.5">
          Rows per page
          <select
            value={size}
            onChange={(event) => table.setPageSize(Number(event.target.value))}
            className="rounded border border-line-strong bg-field px-1.5 py-0.5 text-ink-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-given"
            data-testid={`${TestIdPrefix}.Pager.Size`}
          >
            {PAGE_SIZES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        <span data-testid={`${TestIdPrefix}.Pager.Range`}>
          {first}–{last} of {formatCount(total)}
        </span>
        <button
          type="button"
          onClick={() => table.previousPage()}
          disabled={!table.getCanPreviousPage()}
          className="rounded px-2 py-0.5 hover:text-ink disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-given"
          data-testid={`${TestIdPrefix}.Pager.Previous`}
        >
          Previous
        </button>
        <button
          type="button"
          onClick={() => table.nextPage()}
          disabled={!table.getCanNextPage()}
          className="rounded px-2 py-0.5 hover:text-ink disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-given"
          data-testid={`${TestIdPrefix}.Pager.Next`}
        >
          Next
        </button>
      </div>
    </div>
  );
}
