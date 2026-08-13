import { Box, Button, Stack, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useSnackbar } from 'notistack';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useCases, useClassification, useProject, useSuites } from '../../api/queries';
import { useCan } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PermissionGate } from '../../components/PermissionGate';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatCount } from '../../lib/format';
import { BulkActionDialog } from './BulkActionDialog';
import { CaseFilterBar } from './CaseFilterBar';
import { CaseGrid } from './CaseGrid';
import { SuiteList } from './SuiteList';
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
  const gate = useCan(projectId);

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
            ? `Scoped to ${selectedSuite.name} — cases filed here, added here, or carrying one of its tags.`
            : 'Everything in this project. Pick a suite to narrow it, or filter below.'
        }
        actions={
          <PermissionGate projectId={projectId} permission="case:create">
            {({ allowed }) => (
              <Button
                variant="contained"
                startIcon={<AddIcon />}
                disabled={!allowed}
                onClick={() => navigate(`/projects/${projectId}/cases/new`)}
                data-testid="Cases.NewCaseButton"
              >
                New case
              </Button>
            )}
          </PermissionGate>
        }
      />

      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ gap: 2, alignItems: 'flex-start' }}>
        {/* The tree is its own region — its failure must not empty the grid beside it. */}
        <Box sx={{ width: { xs: '100%', md: 260 }, flexShrink: 0 }}>
          <Panel title="Suites" testId="Cases.SuitePanel" dense>
            <Box sx={{ p: 1, maxHeight: { md: '70vh' }, overflowY: 'auto' }}>
              <DataRegion
                testId="Cases.SuiteList"
                state={{ ...suites, refetch: () => void suites.refetch() }}
                /*
                  No `isEmpty` here, deliberately, and this is the one place a region opts out.
                  DataRegion still owns loading and failed; the EMPTY state is the tree's, because
                  it is the only one that needs an action wired to state the tree holds — the
                  create dialog. The alternative was lifting that state into this page purely to
                  satisfy the shared empty slot, which would put the suite-writing machinery on the
                  case list. Before this slice the region's empty state read "Create a suite to
                  organize cases" with no way to create one, which is the dead end a freshly
                  provisioned project actually landed on.
                */
              >
                {(rows) => (
                  <SuiteList
                    suites={rows}
                    selectedSuiteId={filters.suiteId}
                    onSelect={(suiteId) => setFilter('suiteId', suiteId)}
                    projectId={projectId}
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
            /*
              Resolved from the rows rather than fetched: every row in an owner-filtered list
              has that owner by construction, so the first one names them. There is no user
              lookup to call here and adding one to label a chip would be a request per
              navigation. Undefined when the filter matches nothing — the bar says "one owner"
              instead, which is still more than the silence this replaced.
            */
            ownerName={
              filters.ownerId
                ? cases.data?.items.find((row) => row.owner?.id === filters.ownerId)?.owner
                    ?.displayName
                : undefined
            }
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
              <PermissionGate projectId={projectId} permission="case:edit">
                {({ allowed }) => (
                  <Button
                    size="small"
                    variant="contained"
                    disabled={!allowed}
                    onClick={() => setBulkOpen(true)}
                    data-testid="Cases.BulkEditButton"
                  >
                    Edit selected
                  </Button>
                )}
              </PermissionGate>
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
                      // The body follows the verdict, not the other way round: telling a Viewer to
                      // "create the first test case" points at a button they cannot press.
                      body: gate.can('case:create')
                        ? 'Create the first test case for this project.'
                        : 'Nothing here yet. Ask a test engineer or lead to add the first case.',
                      action: (
                        <PermissionGate projectId={projectId} permission="case:create">
                          {({ allowed }) => (
                            <Button
                              variant="contained"
                              size="small"
                              disabled={!allowed}
                              onClick={() => navigate(`/projects/${projectId}/cases/new`)}
                              data-testid="Cases.List.Empty.NewCaseButton"
                            >
                              New case
                            </Button>
                          )}
                        </PermissionGate>
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
          // Repeated here even though the preview named these cases, because this is the sentence
          // that survives the dialog closing — and it is the only record on this screen that a
          // stored decision ended (§193). Not a success, whatever the rest of the counts say.
          if (result.declinesEnded.length) {
            parts.push(`${formatCount(result.declinesEnded.length)} lost a condition exception`);
          }
          enqueueSnackbar(parts.join(' · '), {
            variant: result.skipped.length || result.declinesEnded.length ? 'warning' : 'success',
          });
          setSelection([]);
        }}
      />
    </Box>
  );
}
