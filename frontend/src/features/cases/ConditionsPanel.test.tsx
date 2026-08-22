import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { CaseConditionRef, Condition } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { ConditionsPanel } from './ConditionsPanel'

/**
 * The case editor's Preconditions / Postconditions panel (§95, §187, §194) — the repo's largest
 * component with no observer until §250.
 *
 * The arms are the invariants this file's own comments call load-bearing, chosen because each one
 * is invisible to `tsc` and survives a browser pass that does not happen to have the right data:
 *
 *   1. **The inherited group renders in the order it was given.** The server resolves §6f's order
 *      over the suite chain; re-sorting here is the second implementation the header refuses, and
 *      the failure is silent — a correct-looking list in the wrong order.
 *   2. **A declined row stays, greyed, and does not move.** It is the only control that can put the
 *      rule back, so removing it on decline makes the action one-way from this screen.
 *   3. **Inherited rows carry no remove, no reorder, no drag handle** — they are not this case's to
 *      move, and the form binds only the case's own ids.
 *   4. **`leavingFolder` makes every toggle inert**, because `normalizeDeclines` bounds the
 *      submitted set by the DESTINATION folder and a suite is flat since §98, so a decline switched
 *      on here would be accepted by validation and then dropped by the same request.
 *   5. **A suite's panel has no toggle at all** — `declined`/`onToggleDecline` are optional
 *      precisely so `SuiteDialogs` need not pass a function that can never be called.
 */

function aCondition(over: Partial<Condition> = {}): Condition {
  return {
    id: 'lib-1',
    title: 'Log in as standard user',
    type: 'PRE',
    body: ['Sign in with a seeded account.'],
    usageCount: 0,
    suiteUsageCount: 0,
    lockVersion: 0,
    ...over,
  }
}

function aRef(over: Partial<CaseConditionRef> = {}): CaseConditionRef {
  return {
    id: 'att-1',
    position: 0,
    conditionId: 'lib-1',
    title: 'Log in as standard user',
    body: ['Sign in with a seeded account.'],
    inline: false,
    inherited: true,
    inheritedFrom: 'Billing',
    declineCount: 0,
    ...over,
  }
}

/**
 * The server's §6f order, deliberately NOT alphabetical and NOT by position — a panel that re-sorted
 * on either would pass an assertion written against a list that agreed with its own sort.
 */
const INHERITED = [
  aRef({ id: 'att-3', position: 7, title: 'Zulu rule' }),
  aRef({ id: 'att-1', position: 2, title: 'Alpha rule' }),
  aRef({ id: 'att-2', position: 9, title: 'Mike rule' }),
]

function mockLibrary(entries: Condition[] = [aCondition()]) {
  vi.spyOn(api, 'get').mockImplementation((async () => entries) as never)
}

function renderPanel(props: Partial<Parameters<typeof ConditionsPanel>[0]> = {}) {
  const onChange = vi.fn()
  const onToggleDecline = vi.fn()
  render(
    <TestProviders client={createTestQueryClient()}>
      <ConditionsPanel
        kind="PRE"
        projectId="p1"
        testId="Conditions"
        value={[]}
        inherited={INHERITED}
        onToggleDecline={onToggleDecline}
        onChange={onChange}
        onCreate={vi.fn()}
        onEdit={vi.fn()}
        {...props}
      />
    </TestProviders>,
  )
  return { onChange, onToggleDecline }
}

function inheritedTitles() {
  return screen
    .getAllByTestId('Conditions.Inherited.Item')
    .map((row) => row.textContent ?? '')
}

beforeEach(() => {
  mockLibrary()
})

describe('ConditionsPanel — the inherited group', () => {
  it('renders the server’s order rather than re-deriving one', () => {
    renderPanel()

    const rendered = inheritedTitles()
    expect(rendered).toHaveLength(3)
    expect(rendered[0]).toContain('Zulu rule')
    expect(rendered[1]).toContain('Alpha rule')
    expect(rendered[2]).toContain('Mike rule')
  })

  it('names the folder each rule came from, so the author is not left hunting for it', () => {
    renderPanel()

    expect(screen.getAllByText('From Billing')).toHaveLength(3)
  })

  it('keeps a declined row in place, marked rather than removed', () => {
    renderPanel({ declined: ['att-1'] })

    const rows = screen.getAllByTestId('Conditions.Inherited.Item')
    expect(rows.map((row) => row.dataset.declined)).toEqual(['false', 'true', 'false'])
    expect(rows[1].textContent).toContain('Alpha rule')
  })

  it('offers no remove, reorder or drag handle on an inherited row', () => {
    renderPanel()

    for (const row of screen.getAllByTestId('Conditions.Inherited.Item')) {
      expect(within(row).queryByTestId('Conditions.Remove')).toBeNull()
      expect(within(row).queryByTestId('Conditions.Up')).toBeNull()
      expect(within(row).queryByTestId('Conditions.Down')).toBeNull()
      expect(within(row).queryByTestId('Conditions.Drag')).toBeNull()
    }
  })
})

describe('ConditionsPanel — declining', () => {
  it('reports the attachment id and the direction to the form', async () => {
    const user = userEvent.setup()
    const { onToggleDecline } = renderPanel()

    await user.click(screen.getAllByTestId('Conditions.DeclineToggle')[1])

    expect(onToggleDecline).toHaveBeenCalledWith('att-1', true)
  })

  it('turns the exception back on when the row is already declined', async () => {
    const user = userEvent.setup()
    const { onToggleDecline } = renderPanel({ declined: ['att-1'] })

    await user.click(screen.getAllByTestId('Conditions.DeclineToggle')[1])

    expect(onToggleDecline).toHaveBeenCalledWith('att-1', false)
  })

  it('is inert while the case is leaving the folder, because the save would discard it', async () => {
    const user = userEvent.setup()
    const { onToggleDecline } = renderPanel({ leavingFolder: true })

    const toggles = screen.getAllByTestId('Conditions.DeclineToggle')
    for (const toggle of toggles) {
      await user.click(toggle)
    }

    expect(onToggleDecline).not.toHaveBeenCalled()
  })

  it('renders no toggle for a caller that inherits from nothing', () => {
    renderPanel({ inherited: [], declined: undefined, onToggleDecline: undefined })

    expect(screen.queryByTestId('Conditions.Inherited')).toBeNull()
    expect(screen.queryByTestId('Conditions.DeclineToggle')).toBeNull()
  })
})
