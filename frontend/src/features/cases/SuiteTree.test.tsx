import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Suite } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { SuiteTree } from './SuiteTree'
import { heldPermissions } from '../../test/permissions'

/**
 * The suite tree's descendant-inclusive counts — the exact site of §32's defect.
 *
 * That defect is worth restating because it is the argument for this file existing at all:
 * deleting ONE case dropped the Payments folder's count by TWO. The cause was pre-existing and
 * visible on every load without being legible — `Suite.caseCount` is the suite's **own** cases
 * (the wire meaning, stated in the contract), the tree rolls the subtree up, and the mock was
 * answering the subtree count, so every parent rendered its descendants twice. Payments read 40
 * in a project with 27 cases.
 *
 * "A number that is merely too large looks like data." Nothing about the screen said it was
 * wrong; only a delete moving it by two made it legible. That is precisely the class of defect a
 * unit test is for, and it is why the assertions below reconcile the tree's totals against the
 * project's own case count rather than checking each node in isolation.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const suite = (
  id: string,
  name: string,
  parentId: string | null,
  ownCaseCount: number,
): Suite => ({
  id,
  key: `TS-${id}`,
  parentId,
  name,
  path: name.toLowerCase(),
  /** The WIRE meaning: this suite's OWN cases, excluding descendants. */
  caseCount: ownCaseCount,
  lifecycleStatus: 'APPROVED',
  conditions: [],
  lockVersion: 1,
})

/*
  The §32 shape, with its real numbers: 19 + 3 + 3 + 2 = 27 cases in the project.
  Payments has three descendants and two own cases.
*/
const suites: Suite[] = [
  suite('1', 'Payments', null, 2),
  suite('2', 'Refunds', '1', 3),
  suite('3', 'Chargebacks', '1', 3),
  suite('4', 'Onboarding', null, 19),
]

/**
 * `selectedSuiteId` matters here beyond selection: expansion starts at the ancestors of the
 * selection, so a node deeper than the first level is not in the DOM until something below it is
 * selected. Passing it is how a three-level assertion reaches the leaf at all.
 */
function renderTree(list: Suite[] = suites, selectedSuiteId?: string) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <SuiteTree
        suites={list}
        selectedSuiteId={selectedSuiteId}
        onSelect={() => {}}
        projectId={PROJECT}
      />
    </TestProviders>,
  )
}

/** The count is rendered beside the name inside the tree item's own label. */
function countOf(name: string): number {
  const item = screen.getByRole('treeitem', { name: new RegExp(`^${name}\\b`) })
  // Read the node's OWN label, not its descendants' — a `textContent` on the treeitem would
  // concatenate the children's rows and make every assertion below accidentally pass.
  const label = within(item).getAllByTitle(name)[0]?.parentElement as HTMLElement
  const digits = label.textContent?.replace(name, '').match(/\d+/)
  return Number(digits?.[0])
}

describe('SuiteTree — the descendant-inclusive count', () => {
  beforeEach(() => {
    // The tree gates its write affordances on `useCan`; a Viewer answer keeps them disabled and
    // out of the way of the arithmetic under test.
    vi.spyOn(api, 'get').mockResolvedValue(
      heldPermissions(['case:read'], {}, PROJECT) as never,
    )
  })

  it('shows a leaf its own count', () => {
    renderTree()
    expect(countOf('Refunds')).toBe(3)
    expect(countOf('Chargebacks')).toBe(3)
    expect(countOf('Onboarding')).toBe(19)
  })

  /**
   * THE §32 assertion. Payments owns 2 and its two children own 3 each, so it must read 8.
   * The defect rendered 14 (2 + 3 + 3, with the parent's own count already containing the
   * children) — or 40 against the real fixture set.
   */
  it('adds a parent’s own cases to its descendants exactly once', () => {
    renderTree()
    expect(countOf('Payments')).toBe(8)
  })

  it('reconciles: the roots sum to the project’s case count, with nothing counted twice', () => {
    renderTree()
    // 8 + 19 = 27, the reconciliation §32 used to prove the double-count was gone. A tree that
    // double-counts anywhere fails here even if every individual number looks plausible.
    expect(countOf('Payments') + countOf('Onboarding')).toBe(27)
  })

  it('rolls up through more than one level', () => {
    // The defect compounds with depth, so one level of nesting is not enough to pin it.
    renderTree(
      [suite('1', 'Root', null, 1), suite('2', 'Middle', '1', 2), suite('3', 'Leaf', '2', 4)],
      '3',
    )
    expect(countOf('Leaf')).toBe(4)
    expect(countOf('Middle')).toBe(6)
    expect(countOf('Root')).toBe(7)
  })

  it('shows a folder holding only descendants their total, not zero', () => {
    // "A parent suite reading 0 cases while its children hold two hundred is technically true
    // and completely useless." — the reason the count is descendant-inclusive at all.
    renderTree([suite('1', 'Empty parent', null, 0), suite('2', 'Child', '1', 200)])
    expect(countOf('Empty parent')).toBe(200)
  })

  /**
   * **Flipped in Phase 5, from characterisation to a requirement.**
   *
   * §36 pinned the old behaviour — an orphan was bucketed under a parent id that is not in the
   * payload, never reached from the root, and therefore rendered nowhere with no empty state and
   * no error — as "documented, not endorsed", on the argument that the endpoint returns a whole
   * project's suite list so the input cannot occur.
   *
   * The review's verdict was that the decision it deferred is not a close call. The condition
   * arrives the first time the list is paginated, filtered, permission-scoped, or served during a
   * parent's soft delete, and it arrives *silently*: a folder and every case under it vanish with
   * nothing on screen to say so. Surfacing at the root is wrong about hierarchy and right about
   * existence, and being wrong visibly beats being wrong invisibly.
   */
  it('surfaces a suite whose parent is absent from the payload at the root, rather than dropping it', () => {
    renderTree([suite('2', 'Orphan', 'missing-parent', 5)])
    expect(screen.getByRole('treeitem', { name: /Orphan/ })).toBeInTheDocument()
    expect(countOf('Orphan')).toBe(5)
  })

  it('still rolls an orphan into no ancestor it cannot see', () => {
    // The orphan sits beside the real root, not inside it — re-parenting to the root must not
    // invent a containment that would inflate somebody else's count.
    renderTree([suite('1', 'Real root', null, 3), suite('2', 'Orphan', 'missing-parent', 5)])
    expect(countOf('Real root')).toBe(3)
    expect(countOf('Orphan')).toBe(5)
  })

  it('still drops a cycle, which cannot be drawn at all', () => {
    // Both nodes name a parent that IS present, so neither is an orphan — and neither is
    // reachable from the root. Dropping is the correct answer, and it must not become an
    // infinite recursion now that unresolvable parents are re-homed.
    renderTree([suite('1', 'A', '2', 1), suite('2', 'B', '1', 1)])
    expect(screen.queryByRole('treeitem', { name: /A/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('treeitem', { name: /B/ })).not.toBeInTheDocument()
  })
})

describe('SuiteTree — sibling order', () => {
  it('sorts siblings by name, so the tree does not reshuffle between loads', () => {
    renderTree([
      suite('1', 'Zebra', null, 1),
      suite('2', 'Alpha', null, 1),
      suite('3', 'Mango', null, 1),
    ])

    const names = screen.getAllByRole('treeitem').map((item) => item.textContent ?? '')
    expect(names[0]).toMatch(/^Alpha/)
    expect(names[1]).toMatch(/^Mango/)
    expect(names[2]).toMatch(/^Zebra/)
  })
})
