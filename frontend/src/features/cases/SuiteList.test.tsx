import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, Suite } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { aSuite } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { SuiteList } from './SuiteList'
import { heldPermissions } from '../../test/permissions'

/**
 * The suite rail, flat since §98.
 *
 * **What this file used to assert, and why almost none of it survived.** It was built around
 * §32's defect: deleting ONE case dropped the Payments folder's count by TWO, because
 * `Suite.caseCount` is the suite's own cases, the tree rolled the subtree up, and the mock was
 * answering the subtree count — so every parent rendered its descendants twice and Payments read
 * 40 in a project with 27 cases. "A number that is merely too large looks like data": nothing on
 * screen said it was wrong, and only a delete moving it by two made it legible.
 *
 * That whole class is now **structurally unavailable**, which is a stronger statement than a test.
 * There is no rollup because there is no subtree; the number shown is the number sent. The one
 * assertion kept from it is the inverse — the rail must render `caseCount` verbatim — because the
 * cheap way to reintroduce the defect is a helper that "helpfully" adds something.
 *
 * The ladder tests below are untouched in substance and re-pointed at the new locators. They are
 * the load-bearing half of the file: `suiteMoves` is the client's copy of
 * `LifecycleLadder.permissionFor`, so a wrong row is a control that enables for a Test Engineer
 * and 403s when pressed — §31's failure, invisible to `tsc` because both keys are strings.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const suite = (id: string, name: string, caseCount: number): Suite => ({
  id,
  key: `TS-${id}`,
  name,
  tagRules: [],
  caseIds: [],
  /** The wire meaning, and since §98 the only meaning: what this suite resolves to. */
  caseCount,
  lifecycleStatus: 'APPROVED',
  preconditions: [],
  postconditions: [],
  lockVersion: 1,
})

const suites: Suite[] = [
  suite('1', 'Payments', 2),
  suite('2', 'Refunds', 3),
  suite('3', 'Chargebacks', 3),
  suite('4', 'Onboarding', 19),
]

function renderList(list: Suite[] = suites, selectedSuiteId?: string) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <SuiteList
        suites={list}
        selectedSuiteId={selectedSuiteId}
        onSelect={() => {}}
        projectId={PROJECT}
      />
    </TestProviders>,
  )
}

/** The row for one suite — the `<li>`, so the count and the kebab are both inside it. */
function rowFor(name: string): HTMLElement {
  const button = screen.getByRole('button', { name: new RegExp(`^${name}$`) })
  return button.closest('li') as HTMLElement
}

function countOf(name: string): number {
  const digits = rowFor(name).textContent?.replace(name, '').match(/\d+/)
  return Number(digits?.[0])
}

describe('SuiteList — the count is the wire count', () => {
  beforeEach(() => {
    // The rail gates its write affordances on `useCan`; a Viewer answer keeps them disabled and
    // out of the way of the arithmetic under test.
    vi.spyOn(api, 'get').mockResolvedValue(heldPermissions(['case:read'], {}, PROJECT) as never)
  })

  it('renders each suite’s own caseCount, adding nothing to it', () => {
    renderList()
    expect(countOf('Payments')).toBe(2)
    expect(countOf('Refunds')).toBe(3)
    expect(countOf('Chargebacks')).toBe(3)
    expect(countOf('Onboarding')).toBe(19)
  })

  /**
   * §32's inverse. Under the old tree, Payments would have read 8 here — its own 2 plus two
   * children of 3 — and a helper that quietly re-adds anything makes this the first test to fail.
   * The numbers are the same fixture on purpose, so the two behaviours are directly comparable.
   */
  it('does not sum other suites into any row', () => {
    renderList()
    expect(countOf('Payments')).not.toBe(8)
    expect(countOf('Payments') + countOf('Refunds') + countOf('Chargebacks')).toBe(8)
  })

  it('shows a suite holding nothing as 0, rather than borrowing a number', () => {
    // Under the tree this was the argument FOR the rollup — "a parent reading 0 while its children
    // hold two hundred is technically true and completely useless". Flat, 0 is simply true.
    renderList([suite('1', 'Empty', 0), suite('2', 'Full', 200)])
    expect(countOf('Empty')).toBe(0)
    expect(countOf('Full')).toBe(200)
  })

  /**
   * The property §36's orphan rule was reaching for, now held by construction.
   *
   * That rule existed because a suite whose `parentId` named a row absent from the payload was
   * bucketed under an id nothing reached from the root, so it rendered nowhere — silently, with
   * every case under it unreachable. Phase 5 promoted it from characterisation to a requirement
   * by re-homing such a suite at the root. With no parent there is nothing to be orphaned from:
   * every row in the payload is a row on screen, which is what that rule wanted and could only
   * approximate.
   */
  it('renders every suite in the payload, in one flat list', () => {
    renderList()
    expect(screen.getAllByTestId('SuiteList.Item')).toHaveLength(4)
  })
})

describe('SuiteList — order', () => {
  it('sorts by name, so the rail does not reshuffle between loads', () => {
    vi.spyOn(api, 'get').mockResolvedValue(heldPermissions(['case:read'], {}, PROJECT) as never)
    renderList([suite('1', 'Zebra', 1), suite('2', 'Alpha', 1), suite('3', 'Mango', 1)])

    const names = screen.getAllByTestId('SuiteList.Item').map((item) => item.textContent ?? '')
    expect(names[0]).toMatch(/^Alpha/)
    expect(names[1]).toMatch(/^Mango/)
    expect(names[2]).toMatch(/^Zebra/)
  })
})

/**
 * The ladder in the rail — §6g's moves, and the three that a target-decides reading gets wrong.
 */
describe('SuiteList — the status ladder', () => {
  const withPermissions = (keys: PermissionKey[]) => {
    vi.spyOn(api, 'get').mockResolvedValue(heldPermissions(keys, {}, PROJECT) as never)
  }

  /**
   * Wait until the permission answer has landed — §66's barrier, and it is not optional here.
   *
   * `useCan` is fail-closed while pending, so EVERY move item is disabled on the first render.
   * `expect(...).toBeDisabled()` is satisfied immediately by that pending state, and the
   * assertion would then hold against a component with no gate in it at all. "Execution history"
   * is the barrier because it is the one item this file's fixtures always allow — it is ungated.
   */
  const menuFor = async (name: string) => {
    const user = userEvent.setup()
    await user.click(within(rowFor(name)).getByTestId('SuiteList.ItemMenu'))
    await waitFor(() => expect(screen.getByTestId('SuiteList.Menu.History')).toBeInTheDocument())
    return user
  }

  const at = (status: Suite['lifecycleStatus'], name = 'Payments'): Suite[] => [
    { ...suite('1', name, 2), lifecycleStatus: status },
  ]

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers a draft exactly the two edges it has, and no others', async () => {
    withPermissions(['case:read', 'suite:manage', 'suite:approve'])
    renderList(at('DRAFT'))
    await menuFor('Payments')

    expect(screen.getByTestId('SuiteList.Menu.Move.IN_REVIEW')).toBeInTheDocument()
    expect(screen.getByTestId('SuiteList.Menu.Move.DEPRECATED')).toBeInTheDocument()
    // The moves SWAP rather than accumulate: approving a draft is a 409, so it is not offered.
    expect(screen.queryByTestId('SuiteList.Menu.Move.APPROVED')).not.toBeInTheDocument()
  })

  /**
   * §98's one removal from this menu, asserted rather than left to a comment.
   *
   * "New suite inside" is **gone, not disabled**. Disable-don't-hide is `ui-rules`' rule about an
   * action the *state* forbids — one the reader could earn. There is no state in which a suite
   * nests inside another any more, so a greyed row would advertise a capability the product does
   * not have and send somebody hunting for the permission that unlocks it.
   */
  it('no longer offers "New suite inside", in any state and for any holder', async () => {
    withPermissions(['case:read', 'suite:manage', 'suite:approve', 'suite:delete'])
    renderList(at('DRAFT'))
    await menuFor('Payments')

    expect(screen.queryByTestId('SuiteList.Menu.NewChild')).not.toBeInTheDocument()
    // The barrier: the menu really is open and really is this caller's, so the absence above is
    // the item being gone rather than the menu never having rendered.
    expect(screen.getByTestId('SuiteList.Menu.Rename')).toBeInTheDocument()
  })

  it('offers a deprecated suite only the way back to draft', async () => {
    withPermissions(['case:read', 'suite:manage', 'suite:approve'])
    renderList(at('DEPRECATED'))
    await menuFor('Payments')

    expect(screen.getByTestId('SuiteList.Menu.Move.DRAFT')).toBeInTheDocument()
    expect(screen.queryByTestId('SuiteList.Menu.Move.APPROVED')).not.toBeInTheDocument()
    expect(screen.queryByTestId('SuiteList.Menu.Move.IN_REVIEW')).not.toBeInTheDocument()
  })

  it('lets an engineer submit a draft for review — that edge needs only suite:manage', async () => {
    withPermissions(['case:read', 'suite:manage'])
    renderList(at('DRAFT'))
    await menuFor('Payments')

    await waitFor(() =>
      expect(screen.getByTestId('SuiteList.Menu.Move.IN_REVIEW')).not.toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
  })

  /**
   * THE assertion of §75 on the client, and the one the old target-decides rule fails.
   *
   * `DRAFT` is not an approval-shaped target, so a rule reading the target alone leaves this at
   * `suite:manage` — which this caller holds. The move is the unmaking of a lead's sign-off, and
   * the item must be dead in this caller's hands.
   */
  it('greys "Return to draft" on an APPROVED suite for an engineer who cannot approve', async () => {
    withPermissions(['case:read', 'suite:manage'])
    renderList(at('APPROVED'))
    await menuFor('Payments')

    // The barrier first: without it this passes against a component that never gates anything,
    // because every item is disabled while the permission answer is still in flight.
    await waitFor(() =>
      expect(screen.getByTestId('SuiteList.Menu.Move.DRAFT')).toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
    expect(screen.getByTestId('SuiteList.Menu.Move.DEPRECATED')).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('lets a lead take an approval back', async () => {
    withPermissions(['case:read', 'suite:manage', 'suite:approve'])
    renderList(at('APPROVED'))
    await menuFor('Payments')

    await waitFor(() =>
      expect(screen.getByTestId('SuiteList.Menu.Move.DRAFT')).not.toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
  })

  it('sends the target status as an absolute PUT, with no If-Match', async () => {
    withPermissions(['case:read', 'suite:manage'])
    const put = vi.spyOn(api, 'put').mockResolvedValue(aSuite() as never)
    renderList(at('DRAFT'))
    const user = await menuFor('Payments')

    await waitFor(() =>
      expect(screen.getByTestId('SuiteList.Menu.Move.IN_REVIEW')).not.toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
    await user.click(screen.getByTestId('SuiteList.Menu.Move.IN_REVIEW'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    const [path, body] = put.mock.calls[0]!
    expect(path).toBe(`/projects/${PROJECT}/suites/1/status`)
    expect(body).toEqual({ status: 'IN_REVIEW' })
    // A third argument would be headers — and `If-Match` here would be the divergence §74 found
    // on the plan editor, in reverse: an absolute target has no stale read to protect.
    expect(put.mock.calls[0]![2]).toBeUndefined()
  })

  it('renders the refusal in the rail, naming the suite it is about', async () => {
    withPermissions(['case:read', 'suite:manage'])
    vi.spyOn(api, 'put').mockRejectedValue(
      Object.assign(new Error('conflict'), {
        status: 409,
        problem: {
          title: 'Status change refused',
          detail: 'Only a draft suite can be submitted for review. Return this suite to draft first.',
        },
      }),
    )
    renderList(at('DRAFT'))
    const user = await menuFor('Payments')

    await waitFor(() =>
      expect(screen.getByTestId('SuiteList.Menu.Move.IN_REVIEW')).not.toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    )
    await user.click(screen.getByTestId('SuiteList.Menu.Move.IN_REVIEW'))

    // The menu is closed by the time the server answers, so the sentence has to land somewhere
    // that survives it — and it names the suite, because a rail of thirty shows no other clue.
    const alert = await screen.findByTestId('SuiteList.StatusError')
    expect(alert).toHaveTextContent(/Payments:/)
  })

  it('badges a suite that is not a draft, and leaves a draft unbadged', async () => {
    withPermissions(['case:read'])
    const { unmount } = renderList(at('APPROVED'))
    expect(screen.getByTestId('SuiteList.Item.Status')).toHaveTextContent('Approved')
    unmount()

    // DRAFT is where every suite starts and where most stay; a badge on all of them costs the
    // width the name needs and says nothing.
    renderList(at('DRAFT'))
    expect(screen.queryByTestId('SuiteList.Item.Status')).not.toBeInTheDocument()
  })
})
