import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { AutomationMapping, IngestionJobSummary, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject } from '../../test/fixtures'
import { IngestionsPage } from './IngestionsPage'

/**
 * The ingestion history — and the three things about it that no gate, type or lint rule can see.
 *
 *   1. **`runId: null` and `runKey: null` are different states and must read differently.** A job
 *      with no run matched nothing; a job with a run id and no key had its run *deleted*. Collapsed
 *      into one dash — the obvious rendering — the second tells somebody their report matched
 *      nothing, which is a different problem with a different fix.
 *   2. **A mapping whose case was deleted stays in the list.** It still consumes the test name, so
 *      every build still resolves that test to a case that is gone; filtering the row out would
 *      hide the only thing that can be removed to fix it.
 *   3. **Unbind is disabled without `case:edit`, not hidden** (§31) — while the history itself is
 *      readable by any member, which is the gate split the two panels exist to keep apart.
 */

const MATCHED: IngestionJobSummary = {
  id: 'job-1',
  status: 'COMPLETED',
  format: 'JUNIT',
  runId: 'run-9',
  runKey: 'TR-9',
  matchedCount: 12,
  unmatchedCount: 0,
  createdAt: '2026-08-06T09:00:00Z',
}

/** Matched nothing, so no run was opened — a designed outcome, not a failure. */
const NO_RUN: IngestionJobSummary = {
  ...MATCHED,
  id: 'job-2',
  format: 'CUCUMBER',
  runId: null,
  runKey: null,
  matchedCount: 0,
  unmatchedCount: 7,
  createdAt: '2026-08-05T09:00:00Z',
}

/** Opened a run that has since been deleted — the server withholds the key, never the id. */
const DELETED_RUN: IngestionJobSummary = {
  ...MATCHED,
  id: 'job-3',
  runId: 'run-gone',
  runKey: null,
  createdAt: '2026-08-04T09:00:00Z',
}

const BOUND: AutomationMapping = {
  id: 'map-1',
  testName: 'com.acme.refundtest#refunds in full',
  caseId: 'case-1',
  caseKey: 'TC-42',
  confirmedBy: 'user-1',
  confirmedAt: '2026-08-01T09:00:00Z',
}

const ORPHANED: AutomationMapping = {
  ...BOUND,
  id: 'map-2',
  testName: 'com.acme.oldtest#gone',
  caseKey: null,
}

function mockApi(permissions: PermissionKey[], jobs: IngestionJobSummary[] = [MATCHED, NO_RUN, DELETED_RUN]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/ingestions')) return jobs
    if (path.includes('/automation-mappings')) return [BOUND, ORPHANED]
    if (path.startsWith('/projects/p1')) return aProject({ id: 'p1', key: 'OLB', name: 'Banking' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route="/projects/p1/ingestions"
    >
      <Routes>
        <Route path="/projects/:projectId/ingestions" element={<IngestionsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('IngestionsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the posted reports with their counts and format', async () => {
    mockApi(['case:read', 'case:edit'])
    renderPage()

    const rows = await screen.findAllByTestId('Ingestions.Row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('JUnit XML')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Cucumber JSON')).toBeInTheDocument()
  })

  it('tells "no run opened" apart from "the run was deleted"', async () => {
    // THE HEADLINE. One dash for both would tell somebody whose run was deleted that their report
    // matched nothing.
    mockApi(['case:read', 'case:edit'])
    renderPage()

    const rows = await screen.findAllByTestId('Ingestions.Row')
    expect(within(rows[0]).getByText('TR-9')).toBeInTheDocument()
    expect(within(rows[1]).getByText('No run')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Run deleted')).toBeInTheDocument()
    // And the deleted one offers no link TO A RUN, because the run it names is gone. Scoped to
    // the run href rather than to "any link": every row links to the job itself from its
    // timestamp, so `queryByRole('link')` would pass on a row that did offer a dead run link.
    const runLinks = within(rows[2])
      .queryAllByRole('link')
      .filter((link) => link.getAttribute('href')?.includes('/runs/'))
    expect(runLinks).toHaveLength(0)
  })

  it('keeps a mapping whose case was deleted, and says that is what it is', async () => {
    mockApi(['case:read', 'case:edit'])
    renderPage()

    const rows = await screen.findAllByTestId('Ingestions.MappingRow')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText('TC-42')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Deleted case')).toBeInTheDocument()
  })

  it('disables Remove without case:edit, rather than hiding it', async () => {
    // §31, and the gate split: the history and the mapping list are both readable here, and only
    // the write is refused.
    mockApi(['case:read'])
    renderPage()

    const rows = await screen.findAllByTestId('Ingestions.MappingRow')
    expect(within(rows[0]).getByTestId('Ingestions.Unbind')).toBeDisabled()
    // The read itself still happened — the panels are not gated on the write's permission.
    expect(await screen.findAllByTestId('Ingestions.Row')).toHaveLength(3)
  })

  it('tells a project with no ingestions how to start posting', async () => {
    // The normal state for most of this product's life, and the only place it says how to begin.
    mockApi(['case:read', 'case:edit'], [])
    renderPage()

    expect(await screen.findByText('No reports posted yet')).toBeInTheDocument()
    expect(screen.getByText(/API token/i)).toBeInTheDocument()
  })
})
