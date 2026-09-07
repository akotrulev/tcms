import { DndContext } from '@dnd-kit/core'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { BoardIssueCard } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { aBoardIssueCard, aLinkedCasesSummary } from '../../test/fixtures'
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
function renderFace(card: BoardIssueCard) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      {/*
        `BoardCardFace` registers a draggable, so it needs a `DndContext` above it. The boards
        supply one; a bare render would exercise a code path no screen has.
      */}
      <DndContext>
        <BoardCardFace
          card={card}
          columns={[]}
          onMove={() => {}}
          canMove
          moveRefusal={undefined}
          testId="Card"
        />
      </DndContext>
    </TestProviders>,
  )
}

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
