import { describe, expect, it } from 'vitest';
import { resolveReferences, type ReferenceRead } from './planScope';

/**
 * The union a plan group's references resolve to (§103).
 *
 * Every assertion here is about a property that is invisible on a two-case fixture and wrong on a
 * real project: the dedupe, the numeric key order, and the truncation notice.
 */

function read(label: string, cases: { id: string; key: string }[], total?: number): ReferenceRead {
  return {
    label,
    cases: cases.map((c) => ({ ...c, title: `Title of ${c.key}` })),
    total: total ?? cases.length,
  };
}

describe('resolveReferences', () => {
  it('unions the reads and dedupes by case id, naming every reference a case arrived through', () => {
    const { cases } = resolveReferences([
      read('Suite Payments', [{ id: 'a', key: 'TC-1' }, { id: 'b', key: 'TC-2' }]),
      read('Tag smoke', [{ id: 'b', key: 'TC-2' }, { id: 'c', key: 'TC-3' }]),
    ]);

    // Three rows, not four: a case filed in the suite AND carrying the tag is one case.
    expect(cases.map((c) => c.id)).toEqual(['a', 'b', 'c']);
    // Both references, not the first one that happened to win. Picking one would make removing the
    // other look like it should have dropped the row.
    expect(cases.find((c) => c.id === 'b')?.via).toEqual(['Suite Payments', 'Tag smoke']);
    expect(cases.find((c) => c.id === 'a')?.via).toEqual(['Suite Payments']);
  });

  it('orders by display key NUMERICALLY, so TC-9 comes before TC-10', () => {
    const { cases } = resolveReferences([
      read('Tag smoke', [
        { id: 'x', key: 'TC-10' },
        { id: 'y', key: 'TC-9' },
        { id: 'z', key: 'TC-2' },
      ]),
    ]);

    // `localeCompare` gives TC-10, TC-2, TC-9 — invisible on any fixture under ten cases, and the
    // class `compareCaseKeys` exists for.
    expect(cases.map((c) => c.key)).toEqual(['TC-2', 'TC-9', 'TC-10']);
  });

  it('reports a read the page cut short, per reference and with both numbers', () => {
    const { truncated } = resolveReferences([
      read('Suite Payments', [{ id: 'a', key: 'TC-1' }], 640),
      read('Tag smoke', [{ id: 'b', key: 'TC-2' }]),
    ]);

    // A list that silently stops is indistinguishable from a suite that really holds one case —
    // and this list's whole job is to answer "what is actually in here".
    expect(truncated).toEqual([{ label: 'Suite Payments', shown: 1, total: 640 }]);
  });

  it('resolves no references to nothing at all, rather than to an error', () => {
    expect(resolveReferences([])).toEqual({ cases: [], truncated: [] });
  });

  it('counts a label once even when two references share it', () => {
    const { cases } = resolveReferences([
      read('Tag smoke', [{ id: 'a', key: 'TC-1' }]),
      read('Tag smoke', [{ id: 'a', key: 'TC-1' }]),
    ]);

    expect(cases[0].via).toEqual(['Tag smoke']);
  });
});
