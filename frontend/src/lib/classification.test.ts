import { describe, expect, it } from 'vitest'
import type { ClassificationValue } from '../api/types'
import { buildValueTree } from './classification'

/**
 * The Feature tree's shape, and the one property that is data loss rather than a wrong drawing.
 *
 * A picker builds its checkboxes from this. A value that does not come out of it has no control on
 * screen — and because the editor submits the complete set of ids, a case already carrying that
 * value would have it silently dropped by the first save. So "every input appears somewhere in the
 * output" is asserted directly, not inferred from the happy-path shape.
 */

const value = (id: string, parentId: string | null, name = id): ClassificationValue => ({
  id,
  parentId,
  name,
  path: name.toLowerCase(),
  color: null,
})

/** Every value reachable by walking the built tree, in no particular order. */
function flatten(nodes: ReturnType<typeof buildValueTree>): string[] {
  return nodes.flatMap((node) => [node.value.id, ...flatten(node.children)])
}

describe('buildValueTree', () => {
  it('nests children under their parent', () => {
    const tree = buildValueTree([
      value('payments', null),
      value('refunds', 'payments'),
      value('partial', 'refunds'),
    ])

    expect(tree).toHaveLength(1)
    expect(tree[0].value.id).toBe('payments')
    expect(tree[0].children[0].value.id).toBe('refunds')
    expect(tree[0].children[0].children[0].value.id).toBe('partial')
  })

  it('keeps several roots', () => {
    const tree = buildValueTree([value('payments', null), value('checkout', null)])

    expect(tree.map((node) => node.value.id)).toEqual(['payments', 'checkout'])
  })

  it('surfaces an ORPHAN at the root rather than dropping it', () => {
    // The property that matters. `refunds` names a parent the payload does not contain — which the
    // server produces the moment a parent value is retired while its children stay live, since the
    // vocabulary endpoint serves live values only.
    //
    // The obvious bucket-and-walk implementation renders it NOWHERE: no error, no empty state, and
    // a case classified as Refunds loses that classification on its next save because the box was
    // never on screen to stay ticked.
    const tree = buildValueTree([value('checkout', null), value('refunds', 'retired-parent')])

    expect(flatten(tree)).toContain('refunds')
    expect(tree.map((node) => node.value.id)).toEqual(['checkout', 'refunds'])
  })

  it('emits every input value exactly once, whatever the parent links say', () => {
    const values = [
      value('a', null),
      value('b', 'a'),
      value('c', 'missing'),
      value('d', null),
      value('e', 'b'),
    ]

    const emitted = flatten(buildValueTree(values))

    expect(emitted.toSorted()).toEqual(['a', 'b', 'c', 'd', 'e'])
  })

  it('terminates on a cycle AND still emits its members', () => {
    // A cycle cannot be drawn as a tree but can certainly be walked forever. Terminating is the
    // easy half; the half that matters is that neither value is reachable from a root, so the
    // straightforward walk drops both — and dropping is the silent deselection this whole file is
    // about. The root sweep catches them.
    const tree = buildValueTree([value('a', 'b'), value('b', 'a')])

    expect(flatten(tree).toSorted()).toEqual(['a', 'b'])
  })

  it('emits a value exactly once even when it is reachable twice', () => {
    // The sweep must not re-emit something the walk already placed, or a value gets two checkboxes
    // and unticking one leaves it selected.
    const values = [value('a', null), value('b', 'a')]

    expect(flatten(buildValueTree(values))).toEqual(['a', 'b'])
  })

  it('handles an empty vocabulary — the normal state of a fresh Feature tree', () => {
    expect(buildValueTree([])).toEqual([])
  })
})
