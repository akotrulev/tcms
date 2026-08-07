import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { IngestionJob } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { IngestionJobPage } from './IngestionJobPage'

/**
 * One posted report — the binder's screen.
 *
 * Three things here that nothing else can check:
 *
 *   1. **A capped list says it is capped.** The server serves 50 names beside a true
 *      `unmatchedCount`; a reader who binds all 50 and sees the count unchanged would otherwise
 *      conclude the binding did not work.
 *   2. **A report that opened no run says so, positively.** It is a designed outcome — a run of
 *      zero cases would report itself complete — and it is the shape of the FIRST report most
 *      teams post, so it must not read as an error or as a missing link.
 *   3. **Bind is disabled without `case:edit`, not hidden.** It is the same key that writes a
 *      case's automation key, which Automation Service deliberately does not hold.
 */

const JOB: IngestionJob = {
  id: 'job-1',
  status: 'COMPLETED',
  format: 'JUNIT',
  runId: 'run-9',
  runKey: 'TR-9',
  matchedCount: 3,
  unmatchedCount: 2,
  unmatched: [
    {
      name: 'com.acme.GhostTest#one that failed',
      normalizedName: 'com.acme.ghosttest#one that failed',
      outcome: 'FAILED',
    },
    {
      name: 'com.acme.GhostTest#one that passed',
      normalizedName: 'com.acme.ghosttest#one that passed',
      outcome: 'PASSED',
    },
  ],
  createdAt: '2026-08-06T09:00:00Z',
}

function mockApi(permissions: string[], job: IngestionJob = JOB) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/ingestions/')) return job
    if (path.startsWith('/projects/p1')) return { id: 'p1', key: 'OLB', name: 'Banking' }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route="/projects/p1/ingestions/job-1"
    >
      <Routes>
        <Route path="/projects/:projectId/ingestions/:jobId" element={<IngestionJobPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('IngestionJobPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the unmatched tests with what the runner said about each', async () => {
    mockApi(['case:read', 'case:edit'])
    renderPage()

    const rows = await screen.findAllByTestId('Ingestion.UnmatchedRow')
    expect(rows).toHaveLength(2)
    // The outcome is on the wire because it changes the urgency: an unmatched FAILURE is a red
    // test nobody is being told about, where an unmatched pass is unrecorded coverage.
    expect(within(rows[0]).getByText('Failed')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Passed')).toBeInTheDocument()
  })

  it('says the list was cut when the server served fewer names than it counted', async () => {
    mockApi(['case:read', 'case:edit'], { ...JOB, unmatchedCount: 120 })
    renderPage()

    expect(await screen.findByTestId('Ingestion.Capped')).toHaveTextContent(/first 2 of 120/i)
  })

  it('does not claim the list was cut when it was not', async () => {
    mockApi(['case:read', 'case:edit'])
    renderPage()

    await screen.findAllByTestId('Ingestion.UnmatchedRow')
    expect(screen.queryByTestId('Ingestion.Capped')).not.toBeInTheDocument()
  })

  it('explains a report that opened no run, rather than showing a missing link', async () => {
    mockApi(['case:read', 'case:edit'], { ...JOB, runId: null, runKey: null })
    renderPage()

    expect(await screen.findByTestId('Ingestion.NoRun')).toHaveTextContent(/no run was opened/i)
    expect(screen.queryByTestId('Ingestion.RunLink')).not.toBeInTheDocument()
  })

  it('disables Bind without case:edit, rather than hiding it', async () => {
    mockApi(['case:read'])
    renderPage()

    const rows = await screen.findAllByTestId('Ingestion.UnmatchedRow')
    expect(within(rows[0]).getByTestId('Ingestion.Bind')).toBeDisabled()
  })

  it('says every test found a case when there is nothing to bind', async () => {
    mockApi(['case:read', 'case:edit'], { ...JOB, unmatchedCount: 0, unmatched: [] })
    renderPage()

    expect(await screen.findByText('Every test found a case')).toBeInTheDocument()
  })
})
