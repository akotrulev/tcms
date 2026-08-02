import type { ClassificationValue } from '../api/types';

/**
 * Shaping a classification dimension's values for display — the shared layer's copy of the one
 * piece of logic the picker cannot be trusted to hold privately.
 *
 * It lives here rather than inside `ClassificationPicker.tsx` for the reason `caseSchema.ts`
 * records about itself: a module that exports both a component and a function defeats Fast Refresh
 * and lints as a warning, and the gate is 0 warnings. Exporting it from the component file to get
 * it under test is exactly the trade that left `CaseSchema`'s four refinements untested through
 * Phase 4 — so the function moves instead.
 */

export interface ClassificationValueNode {
  value: ClassificationValue;
  children: ClassificationValueNode[];
}

/**
 * Builds one dimension's value tree, **surfacing orphans at the root rather than dropping them**.
 *
 * The same decision `SuiteTree.buildTree` reached, for a sharper reason. A value whose `parentId`
 * names something absent from the payload has nowhere to hang, and the obvious bucket-and-walk
 * implementation renders it nowhere at all — no empty state, no error. In a *picker* that is worse
 * than in a navigation tree: a case may already **be classified** with that value, so dropping it
 * means the box for a value the case carries is not on screen, and since the editor submits the
 * full set, the first save silently deselects it. Invisible data loss on a form nobody touched.
 *
 * Reachable rather than hypothetical. `classification_value.parent_id` is a plain nullable column
 * with no guarantee the parent is live, and the server serves live values only — so retiring a
 * parent while its children stay live produces exactly this payload.
 *
 * A cycle in `parentId` cannot be drawn as a tree but can certainly be walked forever, and a form
 * is a bad place to hang the tab. `SuiteTree` stops the walk and lets the cycle's members fall out
 * of the tree — acceptable for navigation, **not** acceptable here, because falling out of this
 * tree is the silent deselection above. So the members of a cycle are surfaced at the root too, by
 * the same sweep that catches orphans: after the walk, anything not yet emitted is emitted.
 *
 * The result is that **every input value appears exactly once in the output, whatever the parent
 * links say** — an unconditional property, which is what makes it worth asserting.
 */
export function buildValueTree(values: ClassificationValue[]): ClassificationValueNode[] {
  const present = new Set(values.map((value) => value.id));
  const byParent = new Map<string | null, ClassificationValue[]>();

  for (const value of values) {
    const parent = value.parentId && present.has(value.parentId) ? value.parentId : null;
    const bucket = byParent.get(parent);
    if (bucket) bucket.push(value);
    else byParent.set(parent, [value]);
  }

  const emitted = new Set<string>();
  const build = (parentId: string | null): ClassificationValueNode[] =>
    (byParent.get(parentId) ?? [])
      .filter((value) => !emitted.has(value.id))
      .map((value) => {
        emitted.add(value.id);
        return { value, children: build(value.id) };
      });

  const roots = build(null);

  // The sweep. Only a value in a parentId cycle reaches this — every other value is either a root
  // or reachable from one — and it is drawn at the root, without its children, which is wrong about
  // hierarchy and right about existence. Being wrong visibly beats being wrong invisibly, which is
  // the same call the orphan case makes one paragraph up.
  for (const value of values) {
    if (!emitted.has(value.id)) {
      emitted.add(value.id);
      roots.push({ value, children: build(value.id) });
    }
  }

  return roots;
}
