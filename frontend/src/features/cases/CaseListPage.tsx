import { Box, Button, Stack, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import UploadFileIcon from '@mui/icons-material/UploadFile';
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
import { SuitePanel } from './SuitePanel';
import { useCaseFilters } from './useCaseFilters';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';

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
  useAnnouncedResults(
    cases,
    `${formatCount(cases.data?.total ?? 0)} ${(cases.data?.total ?? 0) === 1 ? 'case' : 'cases'} match the current filters`,
  );
  const gate = useCan(projectId);

  const [selection, setSelection] = useState<string[]>([]);
  const [bulkOpen, setBulkOpen] = useState(false);

  const selectedSuite = suites.data?.find((s) => s.id === filters.suiteId)

  /*
    Where *New case* goes, carrying the suite the reader is standing in — UX#106 *Redundant
    Entry*, and a live defect until 003 Phase 1: both entry points navigated to a bare
    `/cases/new`, so somebody who had just filtered to Refunds opened a blank editor and picked
    Refunds again from a list of forty.

    A query parameter rather than route state, for the reason filter state is in the URL
    everywhere else in this app: the editor is a real address a reader may bookmark or reopen, and
    route state does not survive either. The editor treats it as a **default for the picker**, not
    as a decision — it is prefilled and changeable, which is what makes carrying it safe.
  */
  const newCasePath = `/projects/${projectId}/cases/new${
    filters.suiteId ? `?suiteId=${encodeURIComponent(filters.suiteId)}` : ''
  }`;

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
          <Stack direction="row" sx={{ gap: 1 }}>
            {/*
              The `.feature` import (§255). **Two keys, either of which is enough**: a folder of
              feature files plans `CREATE` rows and `UPDATE` rows, and a caller holding one of
              `case:create`/`case:edit` has work the screen can do for them — the server answers
              200 with the rest of the rows refused. Gating on one of the two would grey the entry
              point out for somebody it would have served, which is disable-don't-hide inverted.
            */}
            <PermissionGate projectId={projectId} permission={['case:create', 'case:edit']}>
              {({ allowed }) => (
                <Button
                  variant="outlined"
                  startIcon={<UploadFileIcon />}
                  disabled={!allowed}
                  onClick={() => navigate(`/projects/${projectId}/cases/import`)}
                  data-testid="Cases.ImportFeaturesButton"
                >
                  Import feature files
                </Button>
              )}
            </PermissionGate>
            <PermissionGate projectId={projectId} permission="case:create">
              {({ allowed }) => (
                <Button
                  variant="contained"
                  startIcon={<AddIcon />}
                  disabled={!allowed}
                  onClick={() => navigate(newCasePath)}
                  data-testid="Cases.NewCaseButton"
                >
                  New case
                </Button>
              )}
            </PermissionGate>
          </Stack>
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
          {/*
            The selected suite's own standing — §227/A7. Above the filters rather than below them
            because it is about the thing the list is scoped TO, not about the narrowing: a reader
            who has picked a folder is looking at that folder, and its approval state is what the
            grid beneath is a view of.

            Absent when no suite is selected, which is the ordinary state of this screen. An empty
            panel saying "no suite selected" would occupy the top of the page permanently to
            describe the absence of a filter.
          */}
          {selectedSuite && <SuitePanel projectId={projectId} suite={selectedSuite} />}

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
                              onClick={() => navigate(newCasePath)}
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
