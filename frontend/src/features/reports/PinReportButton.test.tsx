import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, ReportJob } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { anArtifact } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { PinReportButton } from './PinReportButton'

/**
 * Saving a finished report into the artifact register — audit C6.
 *
 * Three things are locked here and every one is invisible to the build:
 *
 *   1. **The key is the register's, not the report's.** A caller who may read reports and may not
 *      write the register sees the control disabled with a reason, rather than filling the form in
 *      and meeting a 403. `report:view` compiles just as well here and would be wrong for exactly
 *      the population the button exists for.
 *   2. **The control keys on `downloadUrl`, never on `status`.** That field is null for the whole
 *      of PENDING and RUNNING and null for ever on a FAILED job, and the server refuses a pin whose
 *      job produced nothing — so a button offered on a status alone is an affordance for a 400.
 *   3. **The payload names the job.** The register stores the artifact, but only the job carries a
 *      project, so the id sent is the one the server can prove — see `ReportFacts`.
 */

const completed: ReportJob = {
  jobId: 'job-9',
  type: 'run_summary',
  status: 'COMPLETED',
  format: 'MD',
  requestedAt: '2026-08-16T09:00:00Z',
  downloadUrl: '/api/v1/projects/p1/reports/artifacts/ra-1/content',
  modelUrl: '/api/v1/projects/p1/reports/jobs/job-9/model',
  error: null,
}

const running: ReportJob = {
  ...completed,
  status: 'RUNNING',
  downloadUrl: null,
  modelUrl: null,
}

function mockReads(permissions: PermissionKey[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    // The dialog's plan picker and its Replaces read. Both are issued on every open, so leaving
    // either unmocked fails the test on a failed fetch rather than on its own subject (§202).
    if (path.startsWith('/projects/p1/plans')) return []
    if (path.startsWith('/projects/p1/artifacts')) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderButton(job: ReportJob = completed) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <PinReportButton
        job={job}
        projectId="p1"
        reportTitle="Release 1 — run summary"
        testId="ReportView.Pin"
      />
    </TestProviders>,
  )
}

describe('PinReportButton', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockReads(['artifact:edit', 'report:view'])
  })

  it('registers the report as a GENERATED_REPORT artifact naming the job', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(
      anArtifact({ id: 'af-1', kind: 'GENERATED_REPORT', title: 'Release 1 — run summary' }) as never,
    )
    renderButton()

    await waitFor(() => expect(screen.getByTestId('ReportView.Pin')).toBeEnabled())
    await userEvent.click(screen.getByTestId('ReportView.Pin'))
    await userEvent.click(await screen.findByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]).toBe('/projects/p1/artifacts')
    expect(post.mock.calls[0][1]).toMatchObject({
      kind: 'GENERATED_REPORT',
      reportJobId: 'job-9',
      title: 'Release 1 — run summary',
    })
  })

  it('confirms in place and offers the register, rather than closing silently', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(
      anArtifact({ kind: 'GENERATED_REPORT', title: 'Release 1 — run summary' }) as never,
    )
    renderButton()

    await waitFor(() => expect(screen.getByTestId('ReportView.Pin')).toBeEnabled())
    await userEvent.click(screen.getByTestId('ReportView.Pin'))
    await userEvent.click(await screen.findByTestId('Artifacts.Dialog.Submit'))

    // The register is a different screen, so the useful thing to say is not "saved" but "here is
    // where it went" — a message that vanished would leave a fact with no way to act on it.
    const saved = await screen.findByTestId('ReportView.Pin.Saved')
    expect(saved).toHaveTextContent(/Release 1 — run summary/)
    expect(within(saved).getByRole('link')).toHaveAttribute('href', '/projects/p1/artifacts')
  })

  it('disables itself for a reader who may not write the register', async () => {
    // THE test of this file. `report:view` would compile and would enable the button for exactly
    // the persona §6e defines as read-only, who would then fill in the form and be refused.
    mockReads(['report:view', 'report:export'])
    renderButton()

    await waitFor(() => expect(screen.getByTestId('ReportView.Pin')).toBeDisabled())
  })

  it('is not offered at all while the report has no bytes', () => {
    // Keyed on downloadUrl rather than status, `DownloadReportButton`'s rule: the server refuses a
    // pin whose job rendered nothing, and status and URL agree only in the designed system.
    renderButton(running)

    expect(screen.queryByTestId('ReportView.Pin')).not.toBeInTheDocument()
  })
})
