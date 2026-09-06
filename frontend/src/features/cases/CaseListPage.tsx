import { FileUp, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link as RouterLink, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useCases, useClassification, useProject, useSuites } from '../../api/queries';
import { useCan } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PermissionGate } from '../../components/PermissionGate';
import { PageHeader } from '../../components/PageHeader';
import { TabStrip } from '../../components/TabStrip';
import { TABS } from '../../app/nav';
import { Button } from '../../components/ui';
import { formatCount } from '../../lib/format';
import { toast } from '../../notifications/toast';
import { BulkActionDialog } from './BulkActionDialog';
import { CaseFilterBar } from './CaseFilterBar';
import { CaseGrid } from './CaseGrid';
import { SuiteList } from './SuiteList';
import { SuitePanel } from './SuitePanel';
import { useCaseFilters, type CaseFilterState } from './useCaseFilters';
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
 *
 * ~~Both regions sat in a `Panel`.~~ → **superseded** by design language rule 1, *the subject of
 * the page is the page*: `renders/01-index-tables.html` draws the table with nothing around it,
 * and the panel put a card border, a panel border and the table's own head rule between the reader
 * and the first row. The `Cases.SuitePanel` / `Cases.GridPanel` test ids survive on the plain
 * sections that replaced them, because they name the regions, not the chrome.
 */
export function CaseListPage() {
  /*
    `suiteId` is present on `/projects/:projectId/suites/:suiteId` and absent on
    `/projects/:projectId/cases` — **one component, two addresses** (021 §1, brief §8.6). A suite
    is a named collection of cases, so its detail view IS this list scoped to it; a second grid
    over the same rows would be a second place for the filters, the bulk bar and the four empty
    states to drift apart.
  */
  const { projectId = '', suiteId: routeSuiteId } = useParams();
  const navigate = useNavigate();
  const { search } = useLocation();

  const urlFilters = useCaseFilters();
  /*
    On the addressed route the suite comes from the PATH, and picking another one is a navigation
    rather than a filter change — otherwise the address would say one suite while the grid showed
    another. Everything else about the state is unchanged, which is why this wraps the hook instead
    of teaching it about routes: `useCaseFilters` owns filter-state-in-the-URL and knows nothing
    about which screen is mounted, and it should stay that way.

    The pinned suite counts as an active filter. It narrows the result exactly as the query
    parameter did, and the count drives the difference between "this project has no cases" and
    "nothing matches what you asked for" — the wrong one of those on an empty suite reads as a
    broken repository.
  */
  const filterState: CaseFilterState = routeSuiteId
    ? {
        ...urlFilters,
        filters: { ...urlFilters.filters, suiteId: routeSuiteId },
        hasActiveFilters: true,
        activeFilterCount: urlFilters.activeFilterCount + 1,
        setFilter: (key, value) => {
          if (key !== 'suiteId') {
            urlFilters.setFilter(key, value);
            return;
          }
          navigate(
            typeof value === 'string' && value
              ? `/projects/${projectId}/suites/${value}${search}`
              : `/projects/${projectId}/cases${search}`,
          );
        },
      }
    : urlFilters;

  const {
    filters,
    sort,
    hasActiveFilters,
    activeFilterCount,
    setFilter,
    setPage,
    setSize,
    setSort,
    clearFilters,
  } = filterState;

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
    <div>
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
          <div className="flex gap-2">
            {/*
              The `.feature` import (§255). **Two keys, either of which is enough**: a folder of
              feature files plans `CREATE` rows and `UPDATE` rows, and a caller holding one of
              `case:create`/`case:edit` has work the screen can do for them — the server answers
              200 with the rest of the rows refused. Gating on one of the two would grey the entry
              point out for somebody it would have served, which is disable-don't-hide inverted.
            */}
            <PermissionGate projectId={projectId} permission={['case:create', 'case:edit']}>
              {({ allowed }) => (
                allowed ? (
                  <Button asChild variant="ghost" data-testid="Cases.ImportFeaturesButton">
                    <RouterLink to={`/projects/${projectId}/cases/import`}>
                      <FileUp aria-hidden className="size-4" />
                      Import feature files
                    </RouterLink>
                  </Button>
                ) : (
                  <Button variant="ghost" disabled data-testid="Cases.ImportFeaturesButton">
                    <FileUp aria-hidden className="size-4" />
                    Import feature files
                  </Button>
                )
              )}
            </PermissionGate>
            <PermissionGate projectId={projectId} permission="case:create">
              {({ allowed }) => (
                allowed ? (
                  <Button asChild variant="primary" data-testid="Cases.NewCaseButton">
                    <RouterLink to={newCasePath}>
                      <Plus aria-hidden className="size-4" />
                      New case
                    </RouterLink>
                  </Button>
                ) : (
                  <Button variant="primary" disabled data-testid="Cases.NewCaseButton">
                    <Plus aria-hidden className="size-4" />
                    New case
                  </Button>
                )
              )}
            </PermissionGate>
          </div>
        }
      />

      {/*
        Cases · Suites · Proposals (021 §1). Two of the three were rail rows until this task and one
        had no presence at all; a tab keeps its own URL, so the active one is read from the route
        rather than stored and every pasted link still lands.
      */}
      <TabStrip tabs={TABS.cases} projectId={projectId} label="Test case views" testId="Cases.Tabs" />

      <div className="flex flex-col items-start gap-4 md:flex-row">
        {/* The tree is its own region — its failure must not empty the grid beside it. */}
        <div className="w-full shrink-0 md:w-[260px]">
          <section data-testid="Cases.SuiteTree" className="border-b border-line pb-3 md:border-b-0 md:pb-0">
            <h2 className="mb-2 px-1 font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-4">
              Suites
            </h2>
            <div className="overflow-y-auto md:max-h-[70vh]">
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
            </div>
          </section>
        </div>

        <div className="w-full min-w-0 flex-1">
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
            <div
              style={{
                borderColor: 'color-mix(in oklab, var(--color-steel) 22%, transparent)',
                backgroundColor: 'color-mix(in oklab, var(--color-steel) 10%, transparent)',
              }}
              className="mb-2 flex flex-wrap items-center gap-3 rounded-md border px-3 py-2"
              data-testid="Cases.SelectionBar"
            >
              <b
                className="font-mono text-[12.5px] font-medium tabular-nums text-ink"
                data-testid="Cases.SelectionCount"
              >
                {formatCount(selection.length)} selected
              </b>
              <PermissionGate projectId={projectId} permission="case:edit">
                {({ allowed }) => (
                  <Button
                    size="sm"
                    variant="primary"
                    disabled={!allowed}
                    onClick={() => setBulkOpen(true)}
                    data-testid="Cases.BulkEditButton"
                  >
                    Edit selected
                  </Button>
                )}
              </PermissionGate>
              <Button size="sm" onClick={() => setSelection([])} data-testid="Cases.ClearSelection">
                Clear selection
              </Button>
              {/* Select-all only ever reaches the rows in view; saying so prevents the
                  assumption that it spans the whole filtered result set. */}
              <span className="ml-auto text-[11.5px] text-ink-4">
                Selection covers the rows on this page.
              </span>
            </div>
          )}

          <section data-testid="Cases.GridPanel">
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
                      // No `body`: §7 asks this state to COUNT what the filters hid, and
                      // `EmptyState` writes that sentence from `hidden`/`filters`. A hand-written
                      // body here would win over the count and put the screen back where the
                      // audit found it — the removal offered, the number never stated.
                      // The project's own count is the unfiltered total — the cases query cannot
                      // supply it, being the filtered one.
                      hidden: project.data?.caseCount,
                      filters: activeFilterCount,
                      noun: 'case',
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
                            allowed ? (
                              <Button asChild variant="primary" size="sm" data-testid="Cases.List.Empty.NewCaseButton">
                                <RouterLink to={newCasePath}>New case</RouterLink>
                              </Button>
                            ) : (
                              <Button variant="primary" size="sm" disabled data-testid="Cases.List.Empty.NewCaseButton">
                                New case
                              </Button>
                            )
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
          </section>
        </div>
      </div>

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
          toast(parts.join(' · '), {
            variant: result.skipped.length || result.declinesEnded.length ? 'warning' : 'success',
          });
          setSelection([]);
        }}
      />
    </div>
  );
}
