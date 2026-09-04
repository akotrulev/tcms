import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TimesheetQueueEntry } from '../../api/types'
import { aTimeEntry, aTimesheetPeriod, aTimesheetQueueEntry } from '../../test/fixtures'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ApprovalsPage } from './ApprovalsPage'

/**
 * `timesheet-approvals.html`.
 *
 * Four claims. **Rejecting requires a note before the dialog will submit** — the button is disabled
 * until the box has something in it, and the server refuses one without it independently.
 * **An unsubmitted period offers no approve action**, in the mockup's own words. **`projectTotals`
 * does not add up to `loggedHours`** and the panel labels the difference rather than leaving a
 * reader to find the gap. And **approving posts the whole period**, including entries against
 * projects the approver does not hold (016 D-i), which the panel foot says out loud.
 */

const SUBMITTED: TimesheetQueueEntry = aTimesheetQueueEntry({
  period: aTimesheetPeriod({
    id: 'tp-mira',
    personId: 'u-mira',
    personName: 'Mira Sund',
    startsOn: '2026-08-16',
    endsOn: '2026-08-31',
    state: 'SUBMITTED',
    submittedAt: '2026-09-01T08:00:00Z',
    loggedHours: 72,
    editable: false,
    entries: [
      aTimeEntry({
        id: 'te-1',
        periodId: 'tp-mira',
        subjectType: 'ISSUE',
        subject: {
          issueId: 'i1',
          issueKey: 'ACME-122',
          title: 'Card capture without a redirect',
          typeLevel: 'STORY',
          projectId: 'p1',
          projectName: 'Acme Storefront',
        },
        spentOn: '2026-08-20',
        hours: 38,
        editable: false,
      }),
      aTimeEntry({
        id: 'te-2',
        periodId: 'tp-mira',
        subjectType: 'MEETING',
        spentOn: '2026-08-21',
        hours: 6,
        note: 'planning, retro',
        editable: false,
      }),
    ],
  }),
  // 38 of the 72. The remaining 34 are the project-less meeting and a project this approver cannot
  // see — the gap the panel has to name rather than hide.
  projectTotals: [{ projectName: 'Acme Storefront', hours: 38 }],
})

function mockQueue(rows: TimesheetQueueEntry[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/timesheets/approvals')) return rows
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="u-pm"
      route="/timesheets/approvals?on=2026-08-20"
    >
      <Routes>
        <Route path="/timesheets/approvals" element={<ApprovalsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('The timesheet approvals queue', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('approves the whole period, and says that is what it is doing', async () => {
    mockQueue([SUBMITTED])
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(aTimesheetPeriod({ id: 'tp-mira', state: 'APPROVED' }) as never)
    renderPage()

    const panel = await screen.findByTestId('Approvals.Person')
    expect(panel).toHaveTextContent(
      'It approves the whole period, including any entry against a project you do not approve for.',
    )

    await userEvent.click(screen.getByTestId('Approvals.Person.Approve'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/timesheets/periods/tp-mira/decision',
        { decision: 'APPROVE' },
        expect.anything(),
      ),
    )
  })

  it('will not submit a rejection until there is a note', async () => {
    mockQueue([SUBMITTED])
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(
        aTimesheetPeriod({ id: 'tp-mira', state: 'REJECTED', decisionNote: 'no' }) as never,
      )
    renderPage()

    await userEvent.click(await screen.findByTestId('Approvals.Person.Reject'))

    const confirm = screen.getByTestId('Approvals.Reject.Confirm')
    expect(confirm).toBeDisabled()

    // Whitespace is not a note. A rejection whose whole content is a space is the silent one the
    // server's own 400 exists to refuse.
    await userEvent.type(screen.getByTestId('Approvals.Reject.Note'), '   ')
    expect(confirm).toBeDisabled()

    await userEvent.type(
      screen.getByTestId('Approvals.Reject.Note'),
      'the 8h on 24 Aug is against an epic that was on hold.',
    )
    expect(confirm).toBeEnabled()

    await userEvent.click(confirm)

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/timesheets/periods/tp-mira/decision',
        {
          decision: 'REJECT',
          note: 'the 8h on 24 Aug is against an epic that was on hold.',
        },
        expect.anything(),
      ),
    )
  })

  it('offers a withdrawal, and the note it is withdrawing, on a rejected period', async () => {
    mockQueue([
      aTimesheetQueueEntry({
        period: aTimesheetPeriod({
          id: 'tp-dana',
          personName: 'Dana Petrov',
          startsOn: '2026-08-16',
          endsOn: '2026-08-31',
          state: 'REJECTED',
          decidedAt: '2026-09-01T10:00:00Z',
          decidedByName: 'Rae Lindqvist',
          decisionNote: 'the 8h on 24 Aug is against ACME-140 but that epic was on hold that week.',
          loggedHours: 68,
        }),
      }),
    ])
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(aTimesheetPeriod({ id: 'tp-dana', state: 'SUBMITTED' }) as never)
    renderPage()

    expect(await screen.findByTestId('Approvals.Person.Note')).toHaveTextContent(
      'that epic was on hold that week',
    )
    expect(screen.queryByTestId('Approvals.Person.Approve')).toBeNull()

    await userEvent.click(screen.getByTestId('Approvals.Person.Withdraw'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/timesheets/periods/tp-dana/decision',
        { decision: 'WITHDRAW_REJECTION' },
        expect.anything(),
      ),
    )
  })

  it('shows an unfinished period with its hours so far and no way to approve it', async () => {
    mockQueue([
      aTimesheetQueueEntry({
        period: aTimesheetPeriod({
          id: 'tp-jun',
          personName: 'Jun Park',
          startsOn: '2026-08-16',
          endsOn: '2026-08-31',
          state: 'OPEN',
          loggedHours: 41,
        }),
        projectTotals: [{ projectName: 'Acme Storefront', hours: 41 }],
      }),
    ])
    renderPage()

    const panel = await screen.findByTestId('Approvals.Person')
    expect(screen.getByTestId('Approvals.Person.State')).toHaveTextContent('Not submitted')
    // `so far`, not `logged` — the mockup's own word for a figure that is still moving.
    expect(screen.getByTestId('Approvals.Person.Hours')).toHaveTextContent('41.0h so far')
    expect(panel).toHaveTextContent('an unfinished period is not something to act on')
    expect(screen.queryByTestId('Approvals.Person.Approve')).toBeNull()
    expect(screen.queryByTestId('Approvals.Person.Reject')).toBeNull()
  })

  it('names the hours the project totals cannot account for, rather than leaving a gap', async () => {
    mockQueue([SUBMITTED])
    renderPage()

    const totals = await screen.findByTestId('Approvals.Person.Totals')
    expect(totals).toHaveTextContent('Acme Storefront 38.0h')
    // 72 logged, 38 attributable: the difference is a project-less meeting and a project this
    // approver cannot see, and the panel says so instead of showing two numbers that disagree.
    expect(screen.getByTestId('Approvals.Person.Elsewhere')).toHaveTextContent(
      '34.0h not attributed here',
    )
  })

  it('says what an empty fortnight means, rather than reading as a failed load', async () => {
    mockQueue([])
    renderPage()

    const empty = await screen.findByTestId('Approvals.Queue.Empty')
    expect(empty).toHaveTextContent('16–31 August 2026')
  })
})
