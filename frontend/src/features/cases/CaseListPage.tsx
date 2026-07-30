import { Box, Button, Stack, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useSnackbar } from 'notistack';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useCases, useClassification, useProject, useSuites } from '../../api/queries';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatCount } from '../../lib/format';
import { BulkActionDialog } from './BulkActionDialog';
import { CaseFilterBar } from './CaseFilterBar';
import { CaseGrid } from './CaseGrid';
import { SuiteTree } from './SuiteTree';
import { useCaseFilters } from './useCaseFilters';

/**
 * The repository screen: suite tree beside a filtered case grid.
 *
 * Composition matters here. The suite tree and the case grid are **separate** data regions,
 * so a failure in one doesn't take the other down — losing the tree still leaves a usable,
 * filterable list, and losing the list still leaves the tree to navigate with. Both sit
 * below a filter bar that is outside either region and therefore survives both.
 *
 * The classification lookup is an *optional* call: the grid is meaningful without the
 * feature/type dropdowns, so its failure degrades those selects rather than the page.
 */
export function CaseListPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const { enqueueSnackbar } = useSnackbar();

  const filterState = useCaseFilters();
  const { filters, sort, hasActiveFilters, setFilter, setPage, setSize, setSort, clearFilters } = filterState;

  const project = useProject(projectId);
  const suites = useSuites(projectId);
  const dimensions = useClassification(projectId);
  const cases = useCases(projectId, filters, sort);

  const [selection, setSelection] = useState<string[]>([]);
  const [bulkOpen, setBulkOpen] = useState(false);

  const selectedSuite = suites.data?.find((s) => s.id === filters.suiteId);

  return (
    <Box>
      <PageHeader
        testId="Cases.Header"
        title="Test cases"
        documentTitle={project.data ? `Test cases · ${project.data.name}` : 'Test cases'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test cases' },
        ]}
        subtitle={
          selectedSuite
            ? `Scoped to ${selectedSuite.name} and everything beneath it.`
            : 'Everything in this project. Pick a suite to narrow it, or filter below.'
        }
        actions={
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => navigate(`/projects/${projectId}/cases/new`)}
            data-testid="Cases.NewCaseButton"
          >
            New case
          </Button>
        }
      />

      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ gap: 2, alignItems: 'flex-start' }}>
        {/* The tree is its own region — its failure must not empty the grid beside it. */}
        <Box sx={{ width: { xs: '100%', md: 260 }, flexShrink: 0 }}>
          <Panel title="Suites" testId="Cases.SuitePanel" dense>
            <Box sx={{ p: 1, maxHeight: { md: '70vh' }, overflowY: 'auto' }}>
              <DataRegion
                testId="Cases.SuiteTree"
                state={{ ...suites, refetch: () => void suites.refetch() }}
                isEmpty={(rows) => rows.length === 0}
                empty={{ kind: 'no-records', title: 'No suites', body: 'Create a suite to organize cases.' }}
              >
                {(rows) => (
                  <SuiteTree
                    suites={rows}
                    selectedSuiteId={filters.suiteId}
                    onSelect={(suiteId) => setFilter('suiteId', suiteId)}
                  />
                )}
              </DataRegion>
            </Box>
          </Panel>
        </Box>

        <Box sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          {/* Filters live above the region, so a failed load leaves them usable. */}
          <CaseFilterBar
            state={filterState}
            facets={cases.data?.facets}
            dimensions={dimensions.data}
            total={cases.data?.total}
          />

          {selection.length > 0 && (
            <Stack
              direction="row"
              sx={{
                alignItems: 'center',
                gap: 1.5,
                mb: 1,
                px: 1.5,
                py: 1,
                border: '1px solid',
                borderColor: 'primary.main',
                borderRadius: 1,
                bgcolor: 'action.selected',
              }}
              data-testid="Cases.SelectionBar"
            >
              <Typography variant="body2" sx={{ fontWeight: 600 }} data-testid="Cases.SelectionCount">
                {formatCount(selection.length)} selected
              </Typography>
              <Button size="small" variant="contained" onClick={() => setBulkOpen(true)} data-testid="Cases.BulkEditButton">
                Edit selected
              </Button>
              <Button size="small" onClick={() => setSelection([])} data-testid="Cases.ClearSelection">
                Clear selection
              </Button>
              {/* Select-all only ever reaches the rows in view; saying so prevents the
                  assumption that it spans the whole filtered result set. */}
              <Typography variant="caption" sx={{ color: 'text.secondary', ml: 'auto' }}>
                Selection covers the rows on this page.
              </Typography>
            </Stack>
          )}

          <Panel testId="Cases.GridPanel" dense>
            <DataRegion
              testId="Cases.List"
              state={{ ...cases, refetch: () => void cases.refetch() }}
              isEmpty={(paged) => paged.items.length === 0}
              skeleton={<GridSkeleton />}
              empty={
                hasActiveFilters
                  ? {
                      kind: 'no-filter-match',
                      title: 'No cases match',
                      body: 'Nothing in this project matches the current filters.',
                      onClearFilters: clearFilters,
                    }
                  : {
                      kind: 'no-records',
                      title: 'No cases yet',
                      body: 'Create the first test case for this project.',
                      action: (
                        <Button
                          variant="contained"
                          size="small"
                          onClick={() => navigate(`/projects/${projectId}/cases/new`)}
                          data-testid="Cases.List.Empty.NewCaseButton"
                        >
                          New case
                        </Button>
                      ),
                    }
              }
            >
              {(paged) => (
                <CaseGrid
                  rows={paged.items}
                  total={paged.total}
                  page={filters.page ?? 0}
                  size={filters.size ?? 25}
                  sort={sort}
                  selection={selection}
                  onSelectionChange={setSelection}
                  onPageChange={setPage}
                  onSizeChange={setSize}
                  onSortChange={setSort}
                  projectId={projectId}
                  loading={cases.isFetching}
                />
              )}
            </DataRegion>
          </Panel>
        </Box>
      </Stack>

      <BulkActionDialog
        open={bulkOpen}
        onClose={() => setBulkOpen(false)}
        projectId={projectId}
        selectedIds={selection}
        onCommitted={(result) => {
          // Reported per outcome, not as a blanket success — the user needs to know that
          // 2 of their 44 didn't go through.
          const parts = [`${formatCount(result.changed.length)} updated`];
          if (result.unchanged.length) parts.push(`${formatCount(result.unchanged.length)} already matched`);
          if (result.skipped.length) parts.push(`${formatCount(result.skipped.length)} skipped`);
          enqueueSnackbar(parts.join(' · '), {
            variant: result.skipped.length ? 'warning' : 'success',
          });
          setSelection([]);
        }}
      />
    </Box>
  );
}
