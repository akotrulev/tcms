import { Box, Stack, Tooltip } from '@mui/material';
import {
  DataGrid,
  type GridColDef,
  type GridRowSelectionModel,
  type GridSortModel,
} from '@mui/x-data-grid';
import { Link as RouterLink } from 'react-router-dom';
import type { TestCaseSummary } from '../../api/types';
import { AutomationLabel, HealthChip, LifecycleChip, PriorityLabel, StatusDot } from '../../components/StatusChip';
import { Identifier } from '../../components/Identifier';
import { formatAge, formatDateTime } from '../../lib/format';
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
 */

const TestIdPrefix = 'Cases';

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
  const [sortField, sortDir] = sort.split(',');

  const columns: GridColDef<TestCaseSummary>[] = [
    {
      field: 'key',
      headerName: 'Key',
      width: 104,
      renderCell: ({ row }) => (
        // The row → detail affordance is a real link, so middle-click, ⌘-click and
        // "copy link" all work, and it's keyboard-reachable without the grid's help.
        <RouterLink
          to={`/projects/${projectId}/cases/${row.id}`}
          data-testid={`${TestIdPrefix}.Row.KeyLink`}
          style={{ textDecoration: 'none' }}
        >
          <Identifier sx={{ color: 'primary.main' }}>{row.key}</Identifier>
        </RouterLink>
      ),
    },
    {
      field: 'title',
      headerName: 'Title',
      flex: 1,
      minWidth: 260,
      renderCell: ({ row }) => (
        <Tooltip title={row.title} enterDelay={600}>
          {/*
            The title is a link too, not just the key. It's the largest target in the row and
            the thing people actually aim at — a grid where only a 60px key cell navigates
            reads as broken even though the affordance technically exists.
          */}
          <Box
            component={RouterLink}
            to={`/projects/${projectId}/cases/${row.id}`}
            data-testid={`${TestIdPrefix}.Row.Title`}
            sx={{
              display: 'block',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              color: 'text.primary',
              textDecoration: 'none',
              '&:hover': { textDecoration: 'underline' },
            }}
          >
            {row.title}
          </Box>
        </Tooltip>
      ),
    },
    {
      field: 'suiteName',
      headerName: 'Suite',
      width: 150,
      renderCell: ({ row }) => (
        <Tooltip title={row.suiteName} enterDelay={600}>
          <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-testid={`${TestIdPrefix}.Row.Suite`}>
            {row.suiteName}
          </Box>
        </Tooltip>
      ),
    },
    {
      field: 'lifecycleStatus',
      headerName: 'Status',
      width: 118,
      renderCell: ({ row }) => (
        <LifecycleChip status={row.lifecycleStatus} testId={`${TestIdPrefix}.Row.Lifecycle`} />
      ),
    },
    {
      field: 'priority',
      headerName: 'Priority',
      width: 106,
      renderCell: ({ row }) => <PriorityLabel priority={row.priority} testId={`${TestIdPrefix}.Row.Priority`} />,
    },
    {
      field: 'automationStatus',
      headerName: 'Automation',
      width: 132,
      renderCell: ({ row }) => (
        <AutomationLabel status={row.automationStatus} testId={`${TestIdPrefix}.Row.Automation`} />
      ),
    },
    {
      field: 'lastResult',
      headerName: 'Last result',
      width: 168,
      // Composite cell — no single server field orders it meaningfully, and a half-sorted
      // column reads as broken. Sort by the fields that do (see `sortable: false`).
      sortable: false,
      renderCell: ({ row }) =>
        row.lastResult?.statusKind ? (
          <Tooltip
            title={`${formatDateTime(row.lastResult.executedAt)}${
              row.lastResult.releaseVersion ? ` · release ${row.lastResult.releaseVersion}` : ''
            }`}
          >
            <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }} data-testid={`${TestIdPrefix}.Row.LastResult`}>
              <StatusDot kind={row.lastResult.statusKind} />
              <Box component="span" sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>
                {formatAge(row.lastResult.executedAt)}
              </Box>
            </Stack>
          </Tooltip>
        ) : (
          <Box sx={{ color: 'text.secondary' }} data-testid={`${TestIdPrefix}.Row.LastResult`}>
            Never run
          </Box>
        ),
    },
    {
      field: 'healthSignals',
      headerName: 'Health',
      width: 200,
      sortable: false,
      renderCell: ({ row }) =>
        row.healthSignals.length === 0 ? (
          <Box sx={{ color: 'text.secondary' }}>—</Box>
        ) : (
          <Stack direction="row" sx={{ gap: 0.5, overflow: 'hidden' }} data-testid={`${TestIdPrefix}.Row.Health`}>
            {/* One chip plus an overflow count: three chips per row turns the column into
                noise, and the detail page carries the full list. */}
            <HealthChip signal={row.healthSignals[0]} />
            {row.healthSignals.length > 1 && (
              <Tooltip title={row.healthSignals.slice(1).join(', ')}>
                <Box component="span" sx={{ color: 'text.secondary', fontSize: '0.75rem', alignSelf: 'center' }}>
                  +{row.healthSignals.length - 1}
                </Box>
              </Tooltip>
            )}
          </Stack>
        ),
    },
    {
      field: 'owner',
      headerName: 'Owner',
      width: 140,
      // Names, never the owner's uuid. Nullable because test_case.owner_id is — an imported
      // case, or one whose owner has been offboarded from this company, genuinely has none.
      // "Unassigned" rather than an empty cell, which reads as a rendering failure.
      valueGetter: (_value, row) => row.owner?.displayName ?? 'Unassigned',
      renderCell: ({ row }) => (
        <Tooltip title={row.owner?.email ?? 'No owner assigned'}>
          <Box
            sx={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              color: row.owner ? 'text.primary' : 'text.secondary',
            }}
            data-testid={`${TestIdPrefix}.Row.Owner`}
          >
            {row.owner?.displayName ?? 'Unassigned'}
          </Box>
        </Tooltip>
      ),
    },
    {
      field: 'updatedAt',
      headerName: 'Updated',
      width: 116,
      renderCell: ({ row }) => (
        <Tooltip title={formatDateTime(row.updatedAt)}>
          <Box sx={{ color: 'text.secondary' }} data-testid={`${TestIdPrefix}.Row.Updated`}>
            {formatAge(row.updatedAt)}
          </Box>
        </Tooltip>
      ),
    },
  ];

  /**
   * The grid may only be told to sort by a column it actually has.
   *
   * The default sort is `keySeq,asc` — the server's stable insertion order, which has no column
   * here because "TC-7 before TC-12" is what the *Key* column already shows. Handing that field
   * to `sortModel` looked harmless: the rows arrive in the right order either way, the URL is
   * clean, and every gate stayed green. What it actually did was make the grid sanitize an
   * unknown field out of its model **during its own render** and echo the correction back through
   * `onSortModelChange` → `setSort` → `setSearchParams`, i.e. a router state update while a
   * different component was rendering. React logged "Cannot update a component (`BrowserRouter`)
   * while rendering a different component (`DataGridRaw`)" on every single load of this screen,
   * and nothing else went wrong — which is why it survived three browser passes.
   *
   * Filtering to known fields makes the model the grid is given equal to the model it would
   * compute, so there is nothing to echo. A sort the grid can't display simply shows no arrow,
   * which is the truth.
   */
  const knownFields = new Set(columns.map((column) => column.field));
  const sortModel: GridSortModel =
    sortField && knownFields.has(sortField)
      ? [{ field: sortField, sort: sortDir as GridSortModel[0]['sort'] }]
      : [];

  return (
    <DataGrid<TestCaseSummary>
      rows={rows}
      columns={columns}
      getRowId={(row) => row.id}
      data-testid={`${TestIdPrefix}.Grid`}
      // Server-side everything: the grid is a view over a query, not an owner of data.
      paginationMode="server"
      sortingMode="server"
      filterMode="server"
      rowCount={total}
      paginationModel={{ page, pageSize: size }}
      onPaginationModelChange={(model) => {
        if (model.pageSize !== size) onSizeChange(model.pageSize);
        if (model.page !== page) onPageChange(model.page);
      }}
      pageSizeOptions={[25, 50, 100]}
      sortModel={sortModel}
      onSortModelChange={(model) => {
        const next = model[0];
        const value = next ? `${next.field},${next.sort ?? 'asc'}` : DEFAULT_SORT;
        // Guard the echo as well as the input: an emission that restates the sort we are already
        // on must not write to the URL, whatever else changes above.
        if (value !== sort) onSortChange(value);
      }}
      // A leading checkbox column: row-click-only selection is undiscoverable, and bulk
      // editing is the workflow this screen exists to make survivable.
      checkboxSelection
      disableRowSelectionOnClick
      keepNonExistentRowsSelected
      rowSelectionModel={{ type: 'include', ids: new Set(selection) } as GridRowSelectionModel}
      onRowSelectionModelChange={(model) => onSelectionChange([...model.ids].map(String))}
      loading={loading}
      density="compact"
      disableColumnMenu
      sx={{
        border: 'none',
        '& .MuiDataGrid-columnHeaders': { bgcolor: 'background.default' },
        '& .MuiDataGrid-cell:focus, & .MuiDataGrid-cell:focus-within': { outlineOffset: -2 },
      }}
    />
  );
}
