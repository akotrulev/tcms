import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { TimeEntry, TimesheetPeriod } from '../../api/types'
import { aTimeEntry, aTimesheetPeriod } from '../../test/fixtures'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { TimesheetPage } from './TimesheetPage'

/**
 * `timesheets.html`.
 *
 * The four claims are the module's, not the widget's. **The lock is read, never derived** — a
 * SUBMITTED fortnight renders no Add form and no row controls because the payload says `editable:
 * false`, and a REJECTED one renders both because it says `true`, which is the half a reader
 * expects to be the other way round. **The `Against` select removes the Issue field** rather than
 * disabling it, because the contract refuses an `issueKey` on a category entry. And **three KPI
 * tiles**, because the fourth is capacity and capacity is M7's (016 D-j).
 *
 * The arms are anchored — `/^\/timesheets\/periods\/current/` cannot catch `/timesheets/periods`
 * one segment above it, and the two answer different shapes (an object and an array), so an
 * unanchored arm would serve a list where a period was expected.
 */

function mockReads(period: TimesheetPeriod, history: TimesheetPeriod[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/timesheets/periods/current')) return period
    if (path.startsWith('/timesheets/periods')) return history
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(route = '/timesheets') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route={route}>
      <Routes>
        <Route path="/timesheets" element={<TimesheetPage />} />
      </Routes>
    </TestProviders>,
  )
}

const ISSUE_ENTRY: Partial<TimeEntry> = {
  id: 'te-issue',
  subjectType: 'ISSUE',
  subject: {
    issueId: 'i1',
    issueKey: 'ACME-124',
    title: 'Address validation',
    typeLevel: 'TASK',
    projectId: 'p1',
    projectName: 'Acme Storefront',
  },
  spentOn: '2026-09-01',
  hours: 2,
  note: 'carrier list spike-out',
}

describe('My timesheet', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    /*
      The clock is pinned, and it has to be: the Add form seeds its Date field with today when today
      falls inside the fortnight being looked at, so the `spentOn` the assertions below expect is a
      value the run's own date decides. Midday UTC, so every zone reads it as the 4th.
    */
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-09-04T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('ships three KPI tiles, because period capacity is M7’s and is not invented here', async () => {
    mockReads(aTimesheetPeriod({ loggedHours: 21, entries: [aTimeEntry(ISSUE_ENTRY)] }))
    renderPage()

    expect(await screen.findByTestId('Timesheet.Kpi.Logged')).toHaveTextContent('21.0h')
    expect(screen.getByTestId('Timesheet.Kpi.Approved')).toBeInTheDocument()
    expect(screen.getByTestId('Timesheet.Kpi.Status')).toHaveTextContent('Open')
    // The absence is the assertion. A fourth tile here would be a capacity figure this module has
    // no table to compute from, contradicted by M7's real one the week after.
    expect(screen.queryByText(/capacity/i)).toBeNull()
  })

  it('logs against an issue, a meeting and leave from the one form', async () => {
    mockReads(aTimesheetPeriod())
    const post = vi.spyOn(api, 'post').mockResolvedValue(aTimeEntry() as never)
    renderPage()

    const form = await screen.findByTestId('Timesheet.EntryForm')
    // The Date field is left alone on all three: it arrives seeded with today, which is what a
    // person logging an hour at the end of the day wants, and the pinned clock is what makes the
    // `spentOn` below a fact rather than a reading of the calendar.
    expect(screen.getByTestId('Timesheet.EntryForm.Date')).toHaveValue('2026-09-04')

    await userEvent.type(screen.getByTestId('Timesheet.EntryForm.Issue'), 'ACME-124')
    await userEvent.type(screen.getByTestId('Timesheet.EntryForm.Hours'), '2')
    await userEvent.click(within(form).getByTestId('Timesheet.EntryForm.Save'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/timesheets/entries',
        {
          subjectType: 'ISSUE',
          issueKey: 'ACME-124',
          spentOn: '2026-09-04',
          hours: 2,
          note: null,
        },
        expect.anything(),
      ),
    )

    await userEvent.selectOptions(screen.getByTestId('Timesheet.EntryForm.Subject'), 'MEETING')
    // The field is GONE, not disabled: the contract refuses `issueKey` on a non-ISSUE entry, so a
    // form that kept it would collect a value the server answers 400 for.
    expect(screen.queryByTestId('Timesheet.EntryForm.Issue')).toBeNull()

    await userEvent.type(screen.getByTestId('Timesheet.EntryForm.Hours'), '1')
    await userEvent.click(screen.getByTestId('Timesheet.EntryForm.Save'))

    await waitFor(() =>
      expect(post).toHaveBeenLastCalledWith(
        '/timesheets/entries',
        { subjectType: 'MEETING', spentOn: '2026-09-04', hours: 1, note: null },
        expect.anything(),
      ),
    )

    await userEvent.selectOptions(screen.getByTestId('Timesheet.EntryForm.Subject'), 'LEAVE')
    await userEvent.type(screen.getByTestId('Timesheet.EntryForm.Hours'), '4')
    await userEvent.type(screen.getByTestId('Timesheet.EntryForm.Note'), 'half day')
    await userEvent.click(screen.getByTestId('Timesheet.EntryForm.Save'))

    await waitFor(() =>
      expect(post).toHaveBeenLastCalledWith(
        '/timesheets/entries',
        { subjectType: 'LEAVE', spentOn: '2026-09-04', hours: 4, note: 'half day' },
        expect.anything(),
      ),
    )
  })

  it('refuses an issue entry with no key, before anything reaches the wire', async () => {
    mockReads(aTimesheetPeriod())
    const post = vi.spyOn(api, 'post').mockResolvedValue(aTimeEntry() as never)
    renderPage()

    await userEvent.type(await screen.findByTestId('Timesheet.EntryForm.Hours'), '2')
    await userEvent.click(screen.getByTestId('Timesheet.EntryForm.Save'))

    expect(
      await screen.findByText('Name the issue, or log this against a category instead.'),
    ).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('renders no Add form and no edit buttons on a submitted period', async () => {
    mockReads(
      aTimesheetPeriod({
        state: 'SUBMITTED',
        submittedAt: '2026-09-16T08:00:00Z',
        loggedHours: 21,
        editable: false,
        // The flag is on the ROW as well as the period, because the contract serves it per entry so
        // that one rule has one home. A fixture flipping only the period would describe a body the
        // server cannot send.
        entries: [aTimeEntry({ ...ISSUE_ENTRY, editable: false })],
      }),
    )
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Timesheet.Table.Row')).toBeInTheDocument())
    expect(screen.queryByTestId('Timesheet.EntryForm')).toBeNull()
    expect(screen.queryByTestId('Timesheet.Table.Row.Edit')).toBeNull()
    expect(screen.queryByTestId('Timesheet.Table.Row.Delete')).toBeNull()
    expect(screen.getByTestId('Timesheet.Locked')).toHaveTextContent('nothing in it can be changed')
  })

  it('renders the note and an editable form on a rejected period', async () => {
    mockReads(
      aTimesheetPeriod({
        state: 'REJECTED',
        decidedByName: 'Rae Lindqvist',
        decisionNote: 'the 8h on 24 Aug is against ACME-140 but that epic was on hold that week.',
        loggedHours: 21,
        // A rejection UNLOCKS a period, which is the half that reads backwards. Both flags say so.
        editable: true,
        entries: [aTimeEntry({ ...ISSUE_ENTRY, editable: true })],
      }),
    )
    renderPage()

    const note = await screen.findByTestId('Timesheet.RejectionNote')
    expect(note).toHaveTextContent('Rae Lindqvist sent this period back')
    expect(note).toHaveTextContent('that epic was on hold that week')
    expect(screen.getByTestId('Timesheet.EntryForm')).toBeInTheDocument()
    expect(screen.getByTestId('Timesheet.Table.Row.Edit')).toBeInTheDocument()
    expect(screen.getByTestId('Timesheet.Submit')).toHaveTextContent('Re-submit for approval')
  })

  it('submits the period the read handed back, without opening one first', async () => {
    mockReads(aTimesheetPeriod({ id: 'tp-sep-1', loggedHours: 21 }))
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue(aTimesheetPeriod({ id: 'tp-sep-1', state: 'SUBMITTED' }) as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('Timesheet.Submit'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/timesheets/periods/tp-sep-1/submit',
        undefined,
        expect.anything(),
      ),
    )
  })

  it('counts the Approved tile off the PREVIOUS fortnight, not the one being filled in', async () => {
    mockReads(
      aTimesheetPeriod({ startsOn: '2026-09-01', endsOn: '2026-09-15', loggedHours: 21 }),
      [
        aTimesheetPeriod({ id: 'tp-sep', startsOn: '2026-09-01', endsOn: '2026-09-15' }),
        aTimesheetPeriod({
          id: 'tp-aug',
          startsOn: '2026-08-16',
          endsOn: '2026-08-31',
          state: 'APPROVED',
          loggedHours: 16,
          approvedHours: 16,
        }),
      ],
    )
    renderPage('/timesheets?on=2026-09-04')

    const tile = await screen.findByTestId('Timesheet.Kpi.Approved')
    await waitFor(() => expect(tile).toHaveTextContent('16.0h'))
    expect(tile).toHaveTextContent('from 16–31 Aug')
  })

  it('keeps the “what you never see here” panel, which is the product saying where money is not', async () => {
    mockReads(aTimesheetPeriod())
    renderPage()

    const panel = await screen.findByTestId('Timesheet.NoMoney')
    expect(panel).toHaveTextContent('No rate, no cost, no invoice value')
  })
})
