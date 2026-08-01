import { fireEvent, render, screen, within } from '@testing-library/react'
import { SnackbarProvider } from 'notistack'
import { describe, expect, it } from 'vitest'
import type { WorkItem } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { workItemColumns } from '../../lib/labels'
import { Board } from './BoardPage'

/**
 * The board had **zero tests** until §53, and that is why this file leads with the reason.
 *
 * The whole status vocabulary was rewritten in that slice — every column, every comparison, every
 * `data-testid` — and all 327 existing tests stayed green throughout, because not one of them
 * touched this screen. A suite that cannot go red on a change that large is not covering it.
 *
 * What is worth pinning here is the part that is *logic* rather than markup:
 *
 * 1. **Cards are filed by the contract's status code.** Before §53 the board compared `item.status`
 *    against display text (`'To do'`) while the contract typed `status` as an unconstrained
 *    `string`, and the mock served the same display text because it had been written from this
 *    page. Mock mode agreed with itself perfectly; a server answering `TODO` would have rendered
 *    every card into no column at all — an empty board, no error, nothing to see in the console.
 * 2. **A derived card cannot be moved, and the refusal says where to go instead.** This is §6l's
 *    central invariant as the user meets it. A refusal that only says "no" is a wall.
 * 3. **`allowedTransitions` is honoured before the request, not after it.** A card that appears to
 *    move and then snaps back on the next refetch is the failure the pre-check exists to prevent.
 */

const card = (partial: Partial<WorkItem> & Pick<WorkItem, 'id' | 'title'>): WorkItem =>
  ({
    derived: false,
    sourceType: null,
    sourceId: null,
    kind: 'TASK',
    description: null,
    status: 'TODO',
    priority: 'MEDIUM',
    dueDate: null,
    overdue: false,
    projectId: 'p1',
    projectKey: 'PRJ',
    linkedKey: null,
    allowedTransitions: ['IN_PROGRESS', 'IN_REVIEW', 'DONE'],
    ...partial,
  }) as WorkItem

function renderBoard(items: WorkItem[]) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <SnackbarProvider>
        <Board items={items} projectId="p1" mine={false} />
      </SnackbarProvider>
    </TestProviders>,
  )
}

describe('Board columns', () => {
  it('files each card into the column matching its status code', () => {
    renderBoard([
      card({ id: '1', title: 'Waiting', status: 'TODO' }),
      card({ id: '2', title: 'Underway', status: 'IN_PROGRESS' }),
      card({ id: '3', title: 'Stuck', status: 'BLOCKED' }),
      card({ id: '4', title: 'Checking', status: 'IN_REVIEW' }),
      card({ id: '5', title: 'Finished', status: 'DONE' }),
    ])

    // Asserted per column rather than "all four render": a card whose status matches no column
    // renders nowhere at all, and a test that only counted cards would pass on an empty board.
    expect(within(screen.getByTestId('Board.Column.TODO')).getByText('Waiting')).toBeTruthy()
    expect(within(screen.getByTestId('Board.Column.IN_PROGRESS')).getByText('Underway')).toBeTruthy()
    // BLOCKED is §54's fifth column, and it is the one this assertion exists for: the enum member
    // and the column landed together precisely so that a card in this status could never be
    // returned by the API and rendered nowhere.
    expect(within(screen.getByTestId('Board.Column.BLOCKED')).getByText('Stuck')).toBeTruthy()
    expect(within(screen.getByTestId('Board.Column.IN_REVIEW')).getByText('Checking')).toBeTruthy()
    expect(within(screen.getByTestId('Board.Column.DONE')).getByText('Finished')).toBeTruthy()
  })

  it('shows every column even when it is empty', () => {
    // A board is a distribution over a closed set. Dropping empty columns shows three and looks
    // complete — and "nothing is in review" is a useful thing for the screen to say.
    renderBoard([card({ id: '1', title: 'Only one', status: 'TODO' })])

    // Derived from the same list the board renders from, so a status added to the contract is
    // covered here the moment it is labelled — rather than by somebody remembering this array.
    for (const column of workItemColumns) {
      expect(screen.getByTestId(`Board.Column.${column}`)).toBeTruthy()
    }
  })

  it('labels columns in English while filing by code', () => {
    // The two must not collapse back into one another: the code is the contract's, the words are
    // `lib/labels.ts`'s, and the whole §53 defect was those being the same array.
    renderBoard([card({ id: '1', title: 'Waiting' })])

    expect(within(screen.getByTestId('Board.Column.TODO')).getByText('To do')).toBeTruthy()
  })
})

describe('Moving a card', () => {
  it('refuses a dragged derived card and names the action that works', async () => {
    // DRAGGED, not clicked — and writing this test is what showed why. The move buttons for a
    // disallowed column are `disabled`, so `onClick` never fires and the rejection banner is
    // unreachable through them. Dragging is the only path that reaches `handleDrop`'s refusal,
    // which makes it the only path worth asserting: a `user.click` here passes vacuously against
    // a button that cannot be pressed.
    renderBoard([
      card({
        id: 'execution:abc',
        title: 'Run the refund case',
        derived: true,
        kind: 'EXECUTION',
        sourceType: 'EXECUTION',
        linkedKey: 'TC-42',
        status: 'IN_PROGRESS',
        allowedTransitions: [],
      }),
    ])

    fireEvent.dragStart(screen.getByTestId('Board.Card'))
    fireEvent.drop(screen.getByTestId('Board.Column.DONE'))

    const rejection = await screen.findByTestId('Board.RejectedMove')
    expect(rejection.textContent).toContain('TC-42')
    // The sentence has to point somewhere. "You can't do that" leaves the user with a card they
    // believe is broken rather than one that is following its source.
    expect(rejection.textContent).toContain('follows')
  })

  it('disables the move buttons a card is not allowed to make', () => {
    renderBoard([
      card({
        id: 'execution:abc',
        title: 'Run it',
        derived: true,
        kind: 'EXECUTION',
        status: 'IN_PROGRESS',
        allowedTransitions: [],
      }),
    ])

    // Disabled, not absent — §31's disable-don't-hide. An absent button teaches that the board
    // cannot move cards at all.
    expect(screen.getByTestId('Board.Card.MoveTo.DONE').hasAttribute('disabled')).toBe(true)
  })

  it('offers the moves a task IS allowed to make', () => {
    renderBoard([card({ id: '1', title: 'Movable', status: 'TODO' })])

    expect(screen.getByTestId('Board.Card.MoveTo.DONE').hasAttribute('disabled')).toBe(false)
    // Never a button back to the column it is already in — it would do nothing and read as broken.
    expect(screen.queryByTestId('Board.Card.MoveTo.TODO')).toBeNull()
  })
})

describe('Why a card is on the board', () => {
  it('renders a maintenance card’s reason, labelled', () => {
    // The defect the live pass found, and it was invisible to every gate: the server has always
    // sent `description` and the backend test asserts it, but `WorkCard` never rendered the field.
    // On screen a maintenance card was its case title and a chip, directly above an EXECUTION card
    // with the *same title* — two cards, the same words, neither explaining itself.
    renderBoard([
      card({
        id: 'case:abc',
        title: 'Full refund to card',
        derived: true,
        kind: 'CASE_MAINTENANCE',
        description: 'NEVER_EXECUTED',
        linkedKey: 'TC-1',
        allowedTransitions: [],
      }),
    ])

    // Labelled through the same map the dashboard uses, not the raw signal name — one value must
    // not read two ways across two screens.
    expect(screen.getByTestId('Board.Card.Reason').textContent).toBe('Never executed')
  })

  it('labels every signal when a case raises more than one', () => {
    renderBoard([
      card({
        id: 'case:abc',
        title: 'Full refund to card',
        derived: true,
        kind: 'CASE_MAINTENANCE',
        description: 'NEVER_EXECUTED, STALE_APPROVAL',
        allowedTransitions: [],
      }),
    ])

    const reason = screen.getByTestId('Board.Card.Reason').textContent
    expect(reason).toContain('Never executed')
    expect(reason).toContain('Approved before its last edit')
  })

  it('passes an unrecognised signal through rather than dropping it', () => {
    // A signal the SPA has no word for yet is still a reason. Dropping it silently would put the
    // card back in exactly the state this rendering exists to fix.
    renderBoard([
      card({
        id: 'case:abc',
        title: 'Something',
        derived: true,
        kind: 'CASE_MAINTENANCE',
        description: 'A_SIGNAL_FROM_THE_FUTURE',
        allowedTransitions: [],
      }),
    ])

    expect(screen.getByTestId('Board.Card.Reason').textContent).toBe('A_SIGNAL_FROM_THE_FUTURE')
  })

  it('shows an execution card’s run without treating it as a signal', () => {
    renderBoard([
      card({
        id: 'execution:abc',
        title: 'Full refund to card',
        derived: true,
        kind: 'EXECUTION',
        description: 'Execute in Regression 1.0',
        allowedTransitions: [],
      }),
    ])

    expect(screen.getByTestId('Board.Card.Reason').textContent).toBe('Execute in Regression 1.0')
  })
})

describe('Card ordering', () => {
  it('puts overdue cards above higher-priority ones that are not', () => {
    // The sort is overdue-first THEN priority. A critical card that is not yet late must not bury
    // a medium one that already is — which is the ordering people actually act on.
    renderBoard([
      card({ id: '1', title: 'Critical but on time', priority: 'CRITICAL', status: 'TODO' }),
      card({ id: '2', title: 'Medium and late', priority: 'MEDIUM', overdue: true, status: 'TODO' }),
    ])

    const titles = within(screen.getByTestId('Board.Column.TODO'))
      .getAllByTestId('Board.Card.Title')
      .map((node) => node.textContent)

    expect(titles).toEqual(['Medium and late', 'Critical but on time'])
  })
})
