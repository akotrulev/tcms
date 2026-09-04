import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BoardCard, SelfCapacity } from '../../api/types'
import { aSelfCapacity, aTimesheetPeriod } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { Board } from './BoardPage'
import { MyWorkPage } from './MyWorkPage'
import { drivingAFailedRead } from '../../test/renderFailures'

/**
 * §6l's cross-project "My Work" screen (§83), as `my-work.html` renders it: **two tables, not a
 * kanban** (011 T7).
 *
 * The failures this file exists to catch, each of which typechecks perfectly and looks fine on the
 * one project the fixtures are dense in:
 *
 * 1. **The request sends `mine=true` and no `projectId`.** Those are not two independent
 *    parameters. Omitting the project with `mine` unset is a **400** on the server — "Choose a
 *    board" — so a hook that forgot the filter would render an error page on the screen §6l expects
 *    somebody to keep open all day, and a hook that sent a project would quietly render *one*
 *    project's board under a title promising all of them. Neither is visible in a type.
 * 2. **ONE request, split on the client.** Two reads would let the halves disagree about who the
 *    caller is and about when "now" was — a reader would see an issue in one table that the other
 *    half's snapshot says is no longer theirs. Asserted as the whole list of paths requested, which
 *    is the only assertion a second `GET /board` cannot pass.
 * 3. **The split is `kind === 'ISSUE'`.** An execution filed under "Assigned issues" reads as work
 *    somebody typed; an issue filed under "Test executions & reviews" reads as a queue item with no
 *    key.
 * 4. **A card links into ITS OWN project.** This is the §81 repair's second half: `linkFor` read
 *    the route's project, and on a project board that is always the card's project too — so the bug
 *    is unobservable everywhere except here, where it would send a review of a MOB case to the OLB
 *    case detail and 404 on the one affordance the card exists to offer.
 * 5. **Every issue row says which project it is from.** Two issues titled the same from two
 *    products are indistinguishable without it, and this screen has no rail heading to fall back on.
 * 6. **The private note is the VIEWER's.** The endpoint keys the row on the caller, and this screen
 *    is where that has to be visible — a panel that showed a teammate's note would be a leak the
 *    server did not commit.
 * 7. **Empty is not an error and not a 404.** A member holding no project grants is the normal
 *    first state of a new account, and the personal board has no project that could fail to resolve.
 * 8. **No affordance this screen cannot honour.** There is no "Everyone" chip (the server refuses
 *    that request), no "New issue" (a create needs a project), and no "Hide done" (a done card never
 *    arrives).
 */

const OLB = '11111111-1111-4111-8111-111111111111'
const MOB = '11111111-1111-4111-8111-111111111112'

const card = (partial: Partial<BoardCard> & Pick<BoardCard, 'id' | 'title'>): BoardCard => {
  // Typed on its own line and never `as BoardCard` — §79's repair, which §80 measured: a new
  // required field on the contract becomes one build error here rather than a page of runtime ones.
  const base: BoardCard = {
    id: partial.id,
    title: partial.title,
    derived: false,
    sourceType: 'ISSUE',
    sourceId: 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa',
    kind: 'ISSUE',
    description: null,
    status: { name: 'Ready', kind: 'NOT_STARTED' },
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    projectId: OLB,
    projectKey: 'OLB',
    linkedKey: null,
    allowedTransitions: [],
  }
  return { ...base, ...partial }
}

/**
 * Records every **board** path requested, so a test can assert what was actually asked of the
 * server.
 *
 * The timesheet and staffing arms are answered and deliberately **not** recorded: 016 T5 put a
 * "This period" panel on the rail and 017 T6 gave it its capacity half, and the assertion below is
 * about the board — that it is asked for once, with `mine` and no project. Pushing another screen's
 * reads into that list would make the assertion about the whole page instead of about the one
 * property it exists to pin.
 *
 * The staffing arm is answered in **every** test rather than only in the panel's own: the default
 * branch returns `items`, so an unanswered capacity read would hand a `BoardCard[]` to a region
 * expecting a `SelfCapacity` — a drifted body, which trips the region's boundary and fails every
 * test in the file through `renderFailures` for a read none of them is about.
 */
function mockBoard(
  items: BoardCard[],
  paths: string[] = [],
  period = aTimesheetPeriod(),
  capacity: SelfCapacity | Error = aSelfCapacity(),
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/timesheets/periods/current')) return period
    if (path.startsWith('/staffing/capacity/me')) {
      if (capacity instanceof Error) throw capacity
      return capacity
    }
    paths.push(path)
    return items
  }) as unknown as typeof api.get)
  return paths
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1">
      <MyWorkPage />
    </TestProviders>,
  )
}

describe('What My Work asks the server for', () => {
  it('asks for the personal board once, and names no project', async () => {
    const paths = mockBoard([card({ id: 'OLB-1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('MyWork.Issues.Row')

    /*
      Asserted as the whole list of paths, and both halves of that matter. A request carrying BOTH
      `projectId` and `mine` would pass a containment check and be a different endpoint — one
      project's personal board under a title that promises every project. And a SECOND `/board`
      request is what the two-tables port invites: the split must be a client-side partition of one
      response, or the halves can disagree about who the caller is.
    */
    expect(paths).toEqual(['/board?mine=true'])
  })

  it('never sends an empty projectId', async () => {
    // `qs` drops an undefined, and this is the assertion that keeps it dropped: `projectId=` is a
    // present-but-blank parameter, which the server parses as a malformed uuid rather than as the
    // cross-project board.
    const paths = mockBoard([card({ id: 'OLB-1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('MyWork.Issues.Row')

    expect(paths[0]).not.toContain('projectId')
  })
})

describe('The two tables, over one response', () => {
  it('shows issues from two projects beside reviews and executions', async () => {
    // Story B5's first criterion, and 009's done-when for the whole module.
    mockBoard([
      card({ id: 'OLB-124', title: 'Add address validation', projectKey: 'OLB' }),
      card({
        id: 'MOB-77',
        title: 'Swap the report footer',
        projectId: MOB,
        projectKey: 'MOB',
      }),
      card({
        id: 'execution:e1',
        title: 'Checkout regression — 2.4',
        derived: true,
        kind: 'EXECUTION',
        sourceType: 'CASE',
        sourceId: 'case-9',
        linkedKey: 'TC-9',
      }),
      card({
        id: 'review:r1:u1',
        title: 'Card freeze survives a reboot',
        derived: true,
        kind: 'REVIEW',
        sourceType: 'CASE',
        sourceId: 'case-77',
        linkedKey: 'TC-77',
        projectId: MOB,
        projectKey: 'MOB',
      }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('MyWork.Issues.Row')).toHaveLength(2))

    // The split is on `kind`, and it is asserted from both sides: the issue table holds exactly the
    // two issues, and the queue table exactly the two derived cards.
    const issues = screen.getByTestId('MyWork.Issues')
    expect(within(issues).getByText('Add address validation')).toBeTruthy()
    expect(within(issues).getByText('Swap the report footer')).toBeTruthy()
    expect(within(issues).queryByText('Checkout regression — 2.4')).toBeNull()

    const queue = screen.getByTestId('MyWork.Queue')
    expect(within(queue).getAllByTestId('MyWork.Queue.Row')).toHaveLength(2)
    expect(within(queue).getByText('Checkout regression — 2.4')).toBeTruthy()
    expect(within(queue).queryByText('Add address validation')).toBeNull()
  })

  it('stamps each issue row with its project key, so two products are not one list', async () => {
    mockBoard([
      card({ id: 'OLB-1', title: 'Record the refund run', projectId: OLB, projectKey: 'OLB' }),
      card({ id: 'MOB-1', title: 'Record the refund run', projectId: MOB, projectKey: 'MOB' }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('MyWork.Issues.Row')).toHaveLength(2))

    // Both, and by value: a single-row assertion would pass against a component that rendered the
    // first card's project on every row.
    expect(
      screen.getAllByTestId('MyWork.Issues.Row.Project').map((node) => node.textContent),
    ).toEqual(['OLB', 'MOB'])
  })

  it('links a queue row into the project the CARD belongs to, not the board’s', async () => {
    // The §83 finding, stated as an assertion. `linkFor` used to take the board's project; on any
    // project board that equals the card's, so this is the only place the difference is visible.
    mockBoard([
      card({
        id: 'review:r1:u1',
        title: 'Card freeze survives a reboot',
        derived: true,
        kind: 'REVIEW',
        sourceType: 'CASE',
        sourceId: 'case-77',
        linkedKey: 'TC-77',
        projectId: MOB,
        projectKey: 'MOB',
      }),
    ])
    renderPage()

    expect((await screen.findByTestId('MyWork.Queue.Row.Title')).getAttribute('href')).toBe(
      `/projects/${MOB}/cases/case-77`,
    )
  })

  it('sends an issue row to the issue’s own page, by key', async () => {
    mockBoard([card({ id: 'OLB-124', title: 'Add address validation' })])
    renderPage()

    expect((await screen.findByTestId('MyWork.Issues.Row.Title')).getAttribute('href')).toBe(
      '/issues/OLB-124',
    )
  })

  it('explains a half that is empty rather than saying nothing was found', async () => {
    // The response arrived and is not empty — it simply holds nothing of this kind. Saying "no
    // results" in a panel beside a populated one is the reading failure design language §7 is about.
    mockBoard([card({ id: 'OLB-1', title: 'The only thing on my plate' })])
    renderPage()

    const none = await screen.findByTestId('MyWork.Queue.None')
    expect(none.textContent).toContain('Nothing is waiting on you')
    expect(screen.queryByTestId('MyWork.Content.Empty')).toBeNull()
  })
})

describe('The private note', () => {
  it('lists the viewer’s own note with its personal due date', async () => {
    mockBoard([
      card({
        id: 'OLB-124',
        title: 'Add address validation',
        annotation: {
          note: 'ask Mira which postcode set is authoritative',
          personalDueDate: '2026-09-01',
          flagged: false,
        },
      }),
    ])
    renderPage()

    const row = await screen.findByTestId('MyWork.Notes.Row')
    expect(within(row).getByTestId('MyWork.Notes.Row.Note').textContent).toContain('ask Mira')
    expect(within(row).getByTestId('MyWork.Notes.Row.Due').textContent).toContain('My due date')
  })

  it('does not show one user’s private note on another user’s board', async () => {
    /*
      B5's second criterion. Asserted from the SCREEN because the endpoint being right and the screen
      rendering the wrong person's note are separate failures — and this is the shape of the second
      one: the board is keyed on the caller server-side, so a card whose `annotation` is null is a
      card THIS viewer has not annotated, whatever a teammate wrote on it. A panel that fell back to
      some other source, or that rendered a placeholder note, would be the leak.
    */
    mockBoard([
      card({ id: 'OLB-124', title: 'Add address validation', annotation: null }),
      card({ id: 'OLB-131', title: 'Saved card list renders twice', annotation: null }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('MyWork.Issues.Row')).toHaveLength(2))

    expect(screen.queryByTestId('MyWork.Notes.Row')).toBeNull()
    expect(screen.getByTestId('MyWork.Notes.None').textContent).toContain('You have written none')
  })

  it('writes the note against the card’s sourceType and sourceId, never its id', async () => {
    /*
      The two are different by design: an ISSUE card's `id` is its KEY, while the annotation row is
      keyed on the card's kind and its source UUID. A dialog that sent the key would write a row
      nothing ever reads back — a save that succeeds and shows no result.
    */
    mockBoard([card({ id: 'OLB-124', title: 'Add address validation' })])
    const put = vi
      .spyOn(api, 'put')
      .mockResolvedValue({ note: 'later', personalDueDate: null, flagged: false } as never)
    renderPage()

    await userEvent.click(await screen.findByTestId('MyWork.Row.Note'))
    await userEvent.type(await screen.findByTestId('MyWork.Note.Body'), 'later')
    await userEvent.click(screen.getByTestId('MyWork.Note.Save'))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(
        '/board/annotations/ISSUE/aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa',
        { note: 'later', personalDueDate: null },
      ),
    )
  })
})

describe('The states this screen must not confuse', () => {
  it('reads an empty board as a clear queue rather than as a failure', async () => {
    // A member with no project grants gets `200 []` and never a 404 — the personal board names no
    // project, so nothing can fail to resolve. Rendering that as an error would tell somebody on
    // their first day that the product is broken.
    mockBoard([])
    renderPage()

    const empty = await screen.findByTestId('MyWork.Content.Empty')
    expect(empty.textContent).toContain('Nothing assigned to you')
    expect(screen.queryByTestId('MyWork.Content.Error')).toBeNull()
  })

  it('surfaces a failed read as failed, without unmounting the page chrome', async () => {
    // The failed read is the subject: this asserts the region's failed state, not its data.
    drivingAFailedRead()
    vi.spyOn(api, 'get').mockRejectedValue(new Error('network down'))
    renderPage()

    await screen.findByTestId('MyWork.Content.Error')
    // The header survives the failure — the region owns the three states, the page owns the
    // chrome. Without this a failed load leaves a blank screen with no way to tell where you are.
    expect(screen.getByTestId('MyWork.Header')).toBeTruthy()
  })
})

describe('The affordances this screen deliberately lacks', () => {
  it('offers no Everyone/Assigned-to-me filter, because the server refuses one of them', async () => {
    // `GET /board` with no projectId and `mine=false` is a 400. A chip that produced it would
    // be `ui-rules` §4's render-then-reject: a control whose only outcome is an error.
    mockBoard([card({ id: 'OLB-1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('MyWork.Issues.Row')

    expect(screen.queryByTestId('Board.Filter.Everyone')).toBeNull()
    expect(screen.queryByTestId('Board.Filter.Mine')).toBeNull()
  })

  it('offers no New issue, because a create needs a project this screen does not have', async () => {
    mockBoard([card({ id: 'OLB-1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('MyWork.Issues.Row')

    expect(screen.queryByTestId('Board.NewIssue')).toBeNull()
  })

  it('renders no kanban column, because the projects’ statuses are different rows', async () => {
    /*
      The 011 T7 change, asserted as the absence it is. A kanban's columns are ONE project's
      workflow; this screen spans projects whose statuses merely happen to share English words, so
      grouping by them was a claim the data cannot support. The status is still shown — as a cell.
    */
    mockBoard([card({ id: 'OLB-1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('MyWork.Issues.Row')

    expect(screen.queryByTestId('Board.Column.Ready')).toBeNull()
    expect(screen.getByTestId('MyWork.Issues.Row.Status').textContent).toBe('Ready')
  })

  it('offers no Hide-done toggle, because a done card never arrives', async () => {
    // `BoardFacts.assignedIssues` does not return an issue in a DONE-kind status at all — §6l's
    // "card leaves" rule — so a control to hide one would have nothing to act on.
    mockBoard([card({ id: 'OLB-1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('MyWork.Issues.Row')

    expect(screen.queryByLabelText(/hide done/i)).toBeNull()
  })
})

describe('The project board keeps its columns', () => {
  it('does NOT stamp the project on the per-project board', () => {
    // The other half, and it is not cosmetic: on a board that is already one project's, repeating
    // that project on every card is noise the reader filters out on every glance — and a chip that
    // is always the same value teaches that it never varies, which is exactly wrong here.
    render(
      <TestProviders client={createTestQueryClient()}>
        <Board items={[card({ id: 'OLB-1', title: 'One project' })]} />
      </TestProviders>,
    )

    expect(screen.queryByTestId('Board.Card.Project')).toBeNull()
  })

  it('files cross-project cards into the same columns, by the status each one names', () => {
    /*
      The board component is unchanged by 011 T7 — M2 rebuilds it — and this is the property that
      change had to preserve: `columnsFor` keys on the status NAME, so OLB's `In Progress` and MOB's
      are one column. It is asserted through `Board` directly now that My Work no longer mounts it,
      which is exactly the reading this test always wanted: the columns are the board's, not the
      screen's.
    */
    render(
      <TestProviders client={createTestQueryClient()}>
        <Board
          crossProject
          items={[
            card({ id: 'OLB-1', title: 'Waiting', projectKey: 'OLB' }),
            card({
              id: 'MOB-1',
              title: 'Underway',
              status: { name: 'In Progress', kind: 'IN_PROGRESS' },
              projectId: MOB,
              projectKey: 'MOB',
            }),
          ]}
        />
      </TestProviders>,
    )

    expect(within(screen.getByTestId('Board.Column.Ready')).getByText('Waiting')).toBeTruthy()
    expect(
      within(screen.getByTestId('Board.Column.In Progress')).getByText('Underway'),
    ).toBeTruthy()
  })
})

/**
 * `my-work.html`'s "This week" panel, as the fortnight the product actually keeps (016 T5).
 *
 * Two decisions are pinned here rather than left to a reader of the file:
 *
 * 1. **It is a period, not a week** (016 D-a). The timesheet fortnight is semi-monthly and nothing
 *    configures it, so a panel headed "This week" would report a figure over a range the product
 *    does not have. **Unchanged by 017**, and the capacity beside it is asked for over that same
 *    period — the assertion on the requested path is what pins it.
 * 2. ~~**There is no Capacity tile and no meter** (016 D-j). Capacity needs `person_profile`,
 *    `allocation` and `absence`, all three M7's, and a figure this module invented is the
 *    two-contradictory-percentages failure. Absent, not zero — and the absence is what is asserted,
 *    because a `Capacity 0h` tile is the exact thing a well-meaning port adds back.~~ →
 *    **superseded 2026-09-04 (plan 017).** M7 landed all three tables, so the figure exists and the
 *    meter has its denominator. What survives of D-j is the state it refused to invent, and it is
 *    still asserted: a person with no `person_profile` row reads `—` and *no profile recorded*, and
 *    **the meter is absent for them** rather than drawn against a made-up 40.
 * 3. **The capacity read is its own region** (017 D-m). A staffing outage empties that figure and
 *    the meter and leaves the logged hours, which are a different module's, on screen.
 */
describe('This period (016 M6, completed by 017 M7)', () => {
  it('shows Logged beside Capacity, with the meter under them, over the period’s own dates', async () => {
    mockBoard(
      [card({ id: 'OLB-1', title: 'Anything' })],
      [],
      aTimesheetPeriod({ loggedHours: 21, startsOn: '2026-09-01', endsOn: '2026-09-15' }),
      aSelfCapacity({ from: '2026-09-01', to: '2026-09-15', capacityHours: 32 }),
    )
    renderPage()

    expect(await screen.findByTestId('MyWork.Period.Logged')).toHaveTextContent('21h')
    expect(await screen.findByTestId('MyWork.Period.Capacity')).toHaveTextContent('32h')
    expect(screen.getByTestId('MyWork.Period.Meter.Caption')).toHaveTextContent('66% of your capacity')
    expect(screen.getByTestId('MyWork.Period.Open')).toHaveAttribute('href', '/timesheets')
    // The label is the range, not "this week" — the two are different quantities.
    expect(screen.getByTestId('MyWork.Period')).toHaveTextContent('This period')

    /*
      Asked over the PERIOD, off the loaded payload. `periods.ts` owns the fortnight arithmetic for
      navigation and labelling only (016 D-a), and both bounds are required on this operation, so a
      re-derived week here would be a different answer under the same heading.
    */
    expect(vi.mocked(api.get).mock.calls.map((call) => call[0])).toContain(
      '/staffing/capacity/me?from=2026-09-01&to=2026-09-15',
    )
  })

  it('reads an em dash with no meter for a person nobody has profiled', async () => {
    mockBoard(
      [card({ id: 'OLB-1', title: 'Anything' })],
      [],
      aTimesheetPeriod({ loggedHours: 21 }),
      aSelfCapacity({ hasProfile: false, capacityHours: null, absenceHours: null }),
    )
    renderPage()

    const capacity = await screen.findByTestId('MyWork.Period.Capacity')
    expect(capacity).toHaveTextContent('—')
    expect(capacity).toHaveTextContent('no profile recorded')
    // The meter is the assertion. A bar needs a denominator, and there is none — drawing one
    // against a defaulted 40 is the exact figure 016 D-j refused to invent.
    expect(screen.queryByTestId('MyWork.Period.Meter')).toBeNull()
    expect(screen.getByTestId('MyWork.Period.Logged')).toHaveTextContent('21h')
  })

  it('keeps the logged hours when the capacity read fails, and draws no meter', async () => {
    // The subject IS a failed read: capacity is `staffing`'s and the hours are `time`'s (017 D-m),
    // and one region over both would let one module's outage blank the other's number.
    drivingAFailedRead()
    mockBoard(
      [card({ id: 'OLB-1', title: 'Anything' })],
      [],
      aTimesheetPeriod({ loggedHours: 21 }),
      new Error('staffing is down'),
    )
    renderPage()

    await screen.findByTestId('MyWork.Period.Capacity.Region.Error')
    expect(screen.getByTestId('MyWork.Period.Logged')).toHaveTextContent('21h')
    expect(screen.queryByTestId('MyWork.Period.Meter')).toBeNull()
    // Not the person-shaped sentence: what failed is the network, not somebody's profile.
    expect(screen.queryByText('no profile recorded')).toBeNull()
  })
})
