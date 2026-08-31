import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { IssueStatusRef } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { aBoardIssueCard } from '../../test/fixtures'
import { MoveToMenu } from './MoveToMenu'
import { serverColumnsOf } from './cardMove'

/**
 * The `⋮ Move to…` — WCAG 2.2 Dragging Movements (2.5.7), and story B3's own non-functional
 * requirement.
 *
 * Three behaviours, and each is a rule this repository has argued for in prose somewhere else:
 *
 * 1. **Every column is offered and the illegal ones are disabled with the reason on them**, not
 *    hidden (§31). A hidden control reads as "you may not", and a workflow with no In Progress → Done
 *    transition is *configuration*, not a permission.
 * 2. **A card with an empty `allowedTransitions` gets no control at all** — not a disabled trigger
 *    over a menu of every column. That card is an Epic, `columnRefusalFor` answers with one fact
 *    about the epic and deliberately does not name a target, so a menu would repeat one sentence per
 *    column.
 * 3. **The menu and the drop reach the same verdict**, because both ask `resolveColumnMove`. A column
 *    offered here and refused on release would be the accept-then-revert §12.4 exists to forbid,
 *    arriving through the other door.
 *
 * The assertions read `aria-disabled` rather than `toBeDisabled()`: a Radix menu item is a `div` with
 * `role="menuitem"`, and `jest-dom`'s matcher only looks at the `disabled` *attribute* on form
 * elements — it would pass on an item that was never disabled at all. `PermissionGate.test.tsx` makes
 * the same call for the same reason.
 */

const BACKLOG: IssueStatusRef = { id: 'st-backlog', name: 'Backlog', kind: 'NOT_STARTED', position: 0 }
const READY: IssueStatusRef = { id: 'st-ready', name: 'Ready', kind: 'NOT_STARTED', position: 1 }
const PROGRESS: IssueStatusRef = { id: 'st-prog', name: 'In Progress', kind: 'IN_PROGRESS', position: 2 }
const DONE: IssueStatusRef = { id: 'st-done', name: 'Done', kind: 'DONE', position: 3 }

/**
 * The columns through `serverColumnsOf`, and never a hand-written map (D-x).
 *
 * The test does what the screen does deliberately: `statusIds` is `readonly string[]` and a status
 * *name* is a string too, so a hand-written map here would typecheck, pass, and pin a shape the
 * screen does not use.
 */
const COLUMNS = serverColumnsOf([
  { id: 'col-backlog', name: 'Backlog', position: 0, wipLimit: null, statuses: [BACKLOG], defaultStatusId: BACKLOG.id },
  { id: 'col-ready', name: 'Ready', position: 1, wipLimit: null, statuses: [READY], defaultStatusId: READY.id },
  { id: 'col-prog', name: 'In Progress', position: 2, wipLimit: 3, statuses: [PROGRESS], defaultStatusId: PROGRESS.id },
  { id: 'col-done', name: 'Done', position: 3, wipLimit: null, statuses: [DONE], defaultStatusId: DONE.id },
])

function renderMenu(
  card = aBoardIssueCard({
    key: 'PRJ-201',
    title: 'Remember delivery address',
    columnId: 'col-backlog',
    status: BACKLOG,
    allowedTransitions: [READY],
  }),
  onMove = vi.fn(),
) {
  render(
    <TestProviders client={createTestQueryClient()}>
      <MoveToMenu card={card} columns={COLUMNS} onMove={onMove} testId="Card.Move" />
    </TestProviders>,
  )
  return { onMove }
}

describe('the menu a keyboard and a touch user get', () => {
  it('offers every column, disabling the ones the workflow forbids', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: 'Move PRJ-201' }))

    // Four columns, four entries — a workflow rule never removes a destination from the list, it
    // only explains why that one is closed.
    expect(screen.getAllByRole('menuitem')).toHaveLength(4)
    expect(screen.getByTestId('Card.Move.To.Ready')).not.toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByTestId('Card.Move.To.Done')).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByTestId('Card.Move.To.In Progress')).toHaveAttribute('aria-disabled', 'true')
  })

  it('puts the refusal on the disabled entry, where a tooltip cannot reach', async () => {
    /*
      A disabled control carries `disabled:pointer-events-none`, so no hover ever reaches a tooltip
      wrapped around it — the trap the composite board's card spends a paragraph on. A menu item does
      not need the trick: the reason is its own second line, in the menu, where the reader already is.

      The sentence is `columnRefusalFor`'s, so a refusal reads identically however the move was
      attempted.
    */
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: 'Move PRJ-201' }))

    expect(screen.getByTestId('Card.Move.To.Done')).toHaveTextContent(
      /can't move to Done from Backlog.*workflow decides which moves exist/,
    )
  })

  it('says "already here" for the card’s own column rather than inventing a refusal', async () => {
    // `resolveColumnMove` answers `NONE` for the card's own column and for a coarse column already
    // holding its status: the drop would mean nothing, so the entry says nothing rather than naming a
    // rule that is not the reason.
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: 'Move PRJ-201' }))

    expect(screen.getByTestId('Card.Move.To.Backlog')).toHaveTextContent('already here')
  })

  it('hands the target column’s id to its caller, never its name', async () => {
    /*
      D-x's other half. A project board's columns are rows with real ids and two of them may
      legitimately share a name, so the move is posted against the id — and the menu is one of the two
      places that could quietly send the wrong half.
    */
    const user = userEvent.setup()
    const { onMove } = renderMenu()

    await user.click(screen.getByRole('button', { name: 'Move PRJ-201' }))
    await user.click(screen.getByTestId('Card.Move.To.Ready'))

    expect(onMove).toHaveBeenCalledWith('col-ready')
  })

  it('does nothing when a disabled entry is somehow selected', async () => {
    // Belt to the disabled attribute's braces: the handler re-reads the verdict rather than trusting
    // that the control it is attached to was unreachable. `onSelect` firing on a disabled Radix item
    // would otherwise post a move the workflow forbids.
    const user = userEvent.setup()
    const { onMove } = renderMenu()

    await user.click(screen.getByRole('button', { name: 'Move PRJ-201' }))
    await user.click(screen.getByTestId('Card.Move.To.Done'))

    expect(onMove).not.toHaveBeenCalled()
  })
})

describe('an epic', () => {
  const epic = aBoardIssueCard({
    key: 'PRJ-101',
    title: 'Checkout rebuild',
    typeName: 'Epic',
    typeLevel: 'EPIC',
    countsTowardCompletion: false,
    columnId: 'col-prog',
    status: PROGRESS,
    allowedTransitions: [],
  })

  it('gets no control at all, rather than a disabled menu of every column', async () => {
    // The trigger's absence is the point: there is no gesture, keyboard path or pointer path that
    // could succeed, so offering one that cannot is worse than offering none.
    renderMenu(epic)

    expect(screen.queryByRole('button', { name: 'Move PRJ-101' })).not.toBeInTheDocument()
  })

  it('still says why, in one sentence about the epic and not one per column', async () => {
    // D31 derives an epic's status from its stories, so `allowedTransitions` is empty whatever the
    // target — and a sentence naming a column would send the reader after a workflow rule that is not
    // the reason.
    renderMenu(epic)

    expect(screen.getByTestId('Card.Move.Immobile')).toHaveTextContent(
      /An epic's status is derived from its stories/,
    )
  })
})

describe('a caller without issue:edit', () => {
  it('gets the trigger disabled with the permission’s own reason, not a hidden control', async () => {
    /*
      Disable-don't-hide, and the difference from the epic case above is which fact is missing. A
      workflow with no transition is configuration and the menu explains it per column; a missing
      permission is about the caller, so the trigger itself carries the sentence and the menu never
      opens.
    */
    render(
      <TestProviders client={createTestQueryClient()}>
        <MoveToMenu
          card={aBoardIssueCard({
            key: 'PRJ-777',
            columnId: 'col-backlog',
            status: BACKLOG,
            allowedTransitions: [READY],
          })}
          columns={COLUMNS}
          onMove={vi.fn()}
          disabled
          disabledReason="You need Edit issues in this project."
          testId="Denied.Move"
        />
      </TestProviders>,
    )

    const trigger = screen.getByRole('button', { name: 'Move PRJ-777' })
    expect(trigger).toBeDisabled()
    expect(trigger).toHaveAttribute('title', 'You need Edit issues in this project.')
  })
})
