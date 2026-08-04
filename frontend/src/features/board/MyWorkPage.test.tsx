import { render, screen, waitFor, within } from '@testing-library/react'
import { SnackbarProvider } from 'notistack'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { WorkItem } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { Board } from './BoardPage'
import { MyWorkPage } from './MyWorkPage'

/**
 * §6l's cross-project "My Work" board (§83).
 *
 * The failures this file exists to catch, each of which typechecks perfectly and looks fine on the
 * one project the fixtures are dense in:
 *
 * 1. **The request sends `mine=true` and no `projectId`.** Those are not two independent
 *    parameters. Omitting the project with `mine` unset is a **400** on the server — "Choose a
 *    board" — so a hook that forgot the filter would render an error page on the screen §6l expects
 *    somebody to keep open all day, and a hook that sent a project would quietly render *one*
 *    project's board under a title promising all of them. Neither is visible in a type.
 * 2. **A card links into ITS OWN project.** This is the §81 repair's second half: `linkFor` read
 *    the route's project, and on a project board that is always the card's project too — so the bug
 *    is unobservable everywhere except here, where it would send a review of a MOB case to the OLB
 *    case detail and 404 on the one affordance the card exists to offer.
 * 3. **Every card says which project it is from.** Two cards titled the same from two products are
 *    indistinguishable without it, and this screen has no rail heading to fall back on.
 * 4. **Empty is not an error and not a 404.** A member holding no project grants is the normal
 *    first state of a new account, and the personal board has no project that could fail to
 *    resolve — so it must read as an empty queue, not as a broken screen.
 * 5. **No affordance this screen cannot honour.** There is no "Everyone" chip (the server refuses
 *    that request) and no "New task" (a create needs a project this screen does not have).
 */

const OLB = '11111111-1111-4111-8111-111111111111'
const MOB = '11111111-1111-4111-8111-111111111112'

const card = (partial: Partial<WorkItem> & Pick<WorkItem, 'id' | 'title'>): WorkItem => {
  // Typed on its own line and never `as WorkItem` — §79's repair, which §80 measured: a new
  // required field on the contract becomes one build error here rather than a page of runtime ones.
  const base: WorkItem = {
    id: partial.id,
    title: partial.title,
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'TASK',
    description: null,
    status: 'TODO',
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    projectId: OLB,
    projectKey: 'OLB',
    linkedKey: null,
    allowedTransitions: ['IN_PROGRESS', 'IN_REVIEW', 'DONE'],
  }
  return { ...base, ...partial }
}

/** Records every path requested, so a test can assert what was actually asked of the server. */
function mockBoard(items: WorkItem[], paths: string[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    paths.push(path)
    return items
  }) as unknown as typeof api.get)
  return paths
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <SnackbarProvider>
        <MyWorkPage />
      </SnackbarProvider>
    </TestProviders>,
  )
}

describe('What My Work asks the server for', () => {
  it('asks for the personal board and names no project', async () => {
    const paths = mockBoard([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')

    // Asserted as the whole query string rather than with `toContain('mine=true')`: a request that
    // carried BOTH would pass a containment check and be a different endpoint — one project's
    // personal board, rendered under a title that promises every project.
    expect(paths).toEqual(['/work-items?mine=true'])
  })

  it('never sends an empty projectId', async () => {
    // `qs` drops an undefined, and this is the assertion that keeps it dropped: `projectId=` is a
    // present-but-blank parameter, which the server parses as a malformed uuid rather than as the
    // cross-project board.
    const paths = mockBoard([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')

    expect(paths[0]).not.toContain('projectId')
  })
})

describe('A card on a board that spans projects', () => {
  it('links into the project the CARD belongs to, not the one the board opened on', async () => {
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
        allowedTransitions: [],
      }),
    ])
    renderPage()

    expect((await screen.findByTestId('Board.Card.LinkedKey')).getAttribute('href')).toBe(
      `/projects/${MOB}/cases/case-77`,
    )
  })

  it('stamps each card with its project key, so two products are not one list', async () => {
    mockBoard([
      card({ id: '1', title: 'Record the refund run', projectId: OLB, projectKey: 'OLB' }),
      card({ id: '2', title: 'Record the refund run', projectId: MOB, projectKey: 'MOB' }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Board.Card')).toHaveLength(2))

    // Both, and by value: a single-project assertion would pass against a component that rendered
    // the first card's project on every card.
    expect(screen.getAllByTestId('Board.Card.Project').map((n) => n.textContent)).toEqual([
      'OLB',
      'MOB',
    ])
  })

  it('does NOT stamp the project on the per-project board', () => {
    // The other half, and it is not cosmetic: on a board that is already one project's, repeating
    // that project on every card is noise the reader filters out on every glance — and a chip that
    // is always the same value teaches that it never varies, which is exactly wrong here.
    render(
      <TestProviders client={createTestQueryClient()}>
        <SnackbarProvider>
          <Board items={[card({ id: '1', title: 'One project' })]} />
        </SnackbarProvider>
      </TestProviders>,
    )

    expect(screen.queryByTestId('Board.Card.Project')).toBeNull()
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
    // `GET /work-items` with no projectId and `mine=false` is a 400. A chip that produced it would
    // be `ui-rules` §4's render-then-reject: a control whose only outcome is an error.
    mockBoard([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')

    expect(screen.queryByTestId('Board.Filter.Everyone')).toBeNull()
    expect(screen.queryByTestId('Board.Filter.Mine')).toBeNull()
  })

  it('offers no New task, because a create needs a project this screen does not have', async () => {
    mockBoard([card({ id: '1', title: 'Anything' })])
    renderPage()

    await screen.findByTestId('Board.Card')

    expect(screen.queryByTestId('Board.NewTask')).toBeNull()
  })
})

describe('The board underneath is the project board’s own', () => {
  it('files cross-project cards into the same columns by status code', async () => {
    // The reuse is the point: two implementations of §6l's one board drift on the first change to
    // either. If this ever needs its own column logic, that is the signal the screens diverged.
    mockBoard([
      card({ id: '1', title: 'Waiting', status: 'TODO', projectKey: 'OLB' }),
      card({ id: '2', title: 'Underway', status: 'IN_PROGRESS', projectId: MOB, projectKey: 'MOB' }),
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByTestId('Board.Card')).toHaveLength(2))

    expect(within(screen.getByTestId('Board.Column.TODO')).getByText('Waiting')).toBeTruthy()
    expect(
      within(screen.getByTestId('Board.Column.IN_PROGRESS')).getByText('Underway'),
    ).toBeTruthy()
  })
})
