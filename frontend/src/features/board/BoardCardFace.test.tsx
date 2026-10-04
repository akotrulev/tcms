import { DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { BoardIssueCard } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { aBoardIssueCard, aLinkedCasesSummary, aStatusRef } from '../../test/fixtures'
import { BoardCardFace } from './BoardCardFace'

/**
 * The card face's **linked-cases line** (021 §2.7, T8) — the QA half of the retired composite
 * board, landed on the card.
 *
 * The rest of the face is covered through the boards that render it (`ProjectBoardView.test.tsx`,
 * `Swimlane`'s cases); this file is about the one statement whose absence is meaningful. The
 * arithmetic and the wording are pinned in `linkedCases.test.ts`, which needs no renderer; what is
 * asserted here is the half that only exists in the DOM — that a `null` summary draws **no
 * element**, and that the action beside it goes where the cases actually are.
 */
function Harness({ card, canMove }: { card: BoardIssueCard; canMove: boolean }) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )
  return (
    <DndContext sensors={sensors}>
      <Routes>
        <Route
          path="/"
          element={
            <BoardCardFace
              card={card}
              columns={[]}
              onMove={() => {}}
              canMove={canMove}
              moveRefusal={undefined}
              testId="Card"
            />
          }
        />
        <Route path="/issues/:key" element={<p data-testid="IssuePage" />} />
      </Routes>
    </DndContext>
  )
}

function renderFace(card: BoardIssueCard, canMove = true) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <Harness card={card} canMove={canMove} />
    </TestProviders>,
  )
}

const movableCard = (overrides: Partial<BoardIssueCard> = {}) =>
  aBoardIssueCard({ allowedTransitions: [aStatusRef({ id: 's2', name: 'Ready' })], ...overrides })

describe('The linked-cases line', () => {
  it('draws nothing at all when the summary has not been served', () => {
    /*
      **The whole point of the null.** `linkedCases` is `null` both for a card with no linked cases
      and for any instance whose backend predates 021 T4, and the key is present either way — so
      the face cannot tell them apart and must not pretend to. An empty row, a `0/0`, or the words
      "no cases linked" would each be a claim about coverage that this payload does not support.
    */
    renderFace(aBoardIssueCard({ linkedCases: null }))

    expect(screen.queryByTestId('Card.LinkedCases')).toBeNull()
    // And in particular the ACTION is gone with the line: `Run linked cases` on a card whose cases
    // are unknown is an offer the screen cannot keep.
    expect(screen.queryByTestId('Card.LinkedCases.Run')).toBeNull()
  })

  it('shows the ratio, the blocked count and the action once the summary arrives', () => {
    renderFace(aBoardIssueCard({ linkedCases: aLinkedCasesSummary() }))

    expect(screen.getByTestId('Card.LinkedCases.Headline')).toHaveTextContent('4/6 passing')
    expect(screen.getByTestId('Card.LinkedCases.Blocked')).toHaveTextContent('1 blocked')
  })

  it('omits the blocked chip rather than drawing a zero', () => {
    // A `0 blocked` beside every card is three characters that say nothing and train the eye past
    // the place a real number appears — the same rule the door badges follow.
    renderFace(
      aBoardIssueCard({
        linkedCases: aLinkedCasesSummary({ total: 4, passing: 4, failing: 0, blocked: 0, notRun: 0 }),
      }),
    )

    expect(screen.getByTestId('Card.LinkedCases.Headline')).toHaveTextContent('4/4 passing')
    expect(screen.queryByTestId('Card.LinkedCases.Blocked')).toBeNull()
  })

  it('sends “Run linked cases” to the issue, where the cases and their keys actually are', () => {
    /*
      **Not `…/runs?issue=<key>`.** The plan named that address as an existing run-list filter and
      it is not one: `RunListPage` reads no search parameter and `GET /runs` takes a plan, so the
      parameter would be dropped and the reader would land on every run in the project. The issue's
      own *Linked test cases* panel is where the cases, their latest results and the keys this
      five-number summary cannot carry live.
    */
    renderFace(aBoardIssueCard({ key: 'ACME-141', linkedCases: aLinkedCasesSummary() }))

    expect(screen.getByTestId('Card.LinkedCases.Run')).toHaveAttribute('href', '/issues/ACME-141')
  })
})

describe('The three linked-case zeroes and the edge bar', () => {
  it('draws neither a row nor an edge bar when the summary has not been served', () => {
    renderFace(aBoardIssueCard({ linkedCases: null }))

    expect(screen.queryByTestId('Card.LinkedCases')).toBeNull()
    expect(screen.queryByTestId('Card.ResultEdge')).toBeNull()
  })

  it('says “no cases linked” and draws no edge bar when nothing is linked', () => {
    renderFace(
      aBoardIssueCard({
        linkedCases: aLinkedCasesSummary({ total: 0, passing: 0, failing: 0, blocked: 0, notRun: 0 }),
      }),
    )

    expect(screen.getByTestId('Card.LinkedCases.Headline')).toHaveTextContent('no cases linked')
    expect(screen.queryByTestId('Card.ResultEdge')).toBeNull()
  })

  it('reads “0/n run”, never failing, when nothing has been executed — and the bar is all track', () => {
    renderFace(
      aBoardIssueCard({
        linkedCases: aLinkedCasesSummary({ total: 6, passing: 0, failing: 0, blocked: 0, notRun: 6 }),
      }),
    )

    const headline = screen.getByTestId('Card.LinkedCases.Headline')
    expect(headline).toHaveTextContent('0/6 run')
    expect(headline).not.toHaveTextContent(/pass|fail/)
    const edge = screen.getByTestId('Card.ResultEdge')
    expect(edge.querySelectorAll('[data-fill]')).toHaveLength(0)
  })

  it('draws the edge bar with a fill for each verdict present and none for not-run', () => {
    renderFace(
      aBoardIssueCard({
        linkedCases: aLinkedCasesSummary({ total: 8, passing: 4, failing: 1, blocked: 1, notRun: 2 }),
      }),
    )

    const fills = Array.from(
      screen.getByTestId('Card.ResultEdge').querySelectorAll('[data-fill]'),
      (fill) => fill.getAttribute('data-fill'),
    )
    expect(fills).toEqual(['passed', 'failed', 'blocked'])
  })
})

describe('Priority', () => {
  it.each([
    ['CRITICAL', 'Critical'],
    ['HIGH', 'High'],
  ] as const)('draws %s as a word', (priority, word) => {
    renderFace(aBoardIssueCard({ priority }))

    expect(screen.getByTestId('Card.Priority')).toHaveTextContent(word)
  })

  it.each(['MEDIUM', 'LOW', undefined] as const)('draws nothing for %s', (priority) => {
    renderFace(aBoardIssueCard({ priority }))

    expect(screen.queryByTestId('Card.Priority')).toBeNull()
  })
})

describe('The drag', () => {
  it('gives the keyboard a named, focusable grip that starts a drag', async () => {
    const user = userEvent.setup()
    renderFace(movableCard({ key: 'PRJ-7' }))

    const grip = screen.getByRole('button', { name: 'Drag PRJ-7' })
    expect(grip).not.toHaveAttribute('tabindex', '-1')
    await user.tab()
    expect(grip).toHaveFocus()

    await user.keyboard('{Enter}')
    await waitFor(() => expect(grip).toHaveAttribute('aria-pressed', 'true'))
  })

  it('draws no grip on a card nobody may move', () => {
    renderFace(movableCard(), false)

    expect(screen.queryByTestId('Card.DragHandle')).toBeNull()
  })

  it('follows the title link when it is clicked without a drag', () => {
    renderFace(movableCard({ key: 'PRJ-7' }))

    fireEvent.click(screen.getByTestId('Card.Title'))

    expect(screen.getByTestId('IssuePage')).toBeInTheDocument()
  })

  it('does not navigate when the drag began on the title', async () => {
    renderFace(movableCard({ key: 'PRJ-7' }))
    const title = screen.getByTestId('Card.Title')
    const pointer = { button: 0, isPrimary: true, pointerId: 1 }

    fireEvent.pointerDown(title, { ...pointer, clientX: 10, clientY: 10 })
    fireEvent.pointerMove(title, { ...pointer, clientX: 80, clientY: 10 })
    await waitFor(() => expect(screen.getByTestId('Card')).toHaveClass('opacity-40'))
    fireEvent.pointerUp(title, { ...pointer, clientX: 80, clientY: 10 })
    await waitFor(() => expect(screen.getByTestId('Card')).not.toHaveClass('opacity-40'))
    fireEvent.click(title)

    expect(screen.queryByTestId('IssuePage')).toBeNull()
    expect(screen.getByTestId('Card.Title')).toBeInTheDocument()
  })
})
