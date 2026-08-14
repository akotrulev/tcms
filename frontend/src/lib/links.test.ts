import { describe, expect, it } from 'vitest'
import type { DocLink } from '../api/types'
import { describeLinkTarget, describeRunCode } from './links'

/**
 * The text form of a link's target — what a confirmation dialog names (§173).
 *
 * A pure test rather than an RTL one: nothing here renders, and `render()`ing a component to
 * exercise a string function is the shape `component-testing` calls extracting the wrong thing.
 *
 * The rows that matter are the ones where **another kind's fields are populated**. A row is one
 * kind and the others' properties are null on the wire, but this function must be correct because
 * of `targetKind` and not because of what happened to be null — §169's defect was exactly a
 * renderer that branched on `externalRepo`, and it read as a blank line for seventy sections.
 */

function link(over: Partial<DocLink>): DocLink {
  return {
    id: 'l1',
    targetKind: 'EXTERNAL_GIT',
    relationship: 'VERIFIES',
    stale: false,
    pageId: null,
    pageKey: null,
    pageTitle: null,
    blockId: null,
    blockExcerpt: null,
    externalRepo: null,
    externalPath: null,
    externalLineStart: null,
    externalLineEnd: null,
    externalCommitSha: null,
    externalUrl: null,
    ...over,
  }
}

describe('describeLinkTarget', () => {
  it('names a documentation block by its page, key first', () => {
    expect(
      describeLinkTarget(
        link({ targetKind: 'DOC_BLOCK', pageKey: 'DOC-1', pageTitle: 'Payments' }),
      ),
    ).toBe('DOC-1 — Payments')
  })

  it('falls back to a phrase rather than an empty string when the page carries neither', () => {
    // An empty confirmation names nothing, which is the "are you sure?" ui-rules §4 forbids —
    // so the degenerate row still produces words.
    expect(describeLinkTarget(link({ targetKind: 'DOC_BLOCK' }))).toBe('a documentation block')
  })

  it('is the address for a URL link, even with git fields populated', () => {
    // The guard: a function reading whichever field is non-null would answer the repo here.
    expect(
      describeLinkTarget(
        link({
          targetKind: 'EXTERNAL_URL',
          externalUrl: 'https://specs.example.com/refunds',
          externalRepo: 'acme/checkout',
        }),
      ),
    ).toBe('https://specs.example.com/refunds')
  })

  it('keeps a git link’s path, range and pin together as one name', () => {
    expect(
      describeLinkTarget(
        link({
          externalRepo: 'acme/checkout',
          externalPath: 'src/Refund.java',
          externalLineStart: 40,
          externalLineEnd: 58,
          externalCommitSha: '4f9c2b1',
        }),
      ),
    ).toBe('acme/checkout · src/Refund.java:40–58 at 4f9c2b1')
  })

  it('renders a single-line range as one number', () => {
    expect(
      describeLinkTarget(
        link({
          externalRepo: 'acme/checkout',
          externalPath: 'src/Refund.java',
          externalLineStart: 40,
          externalLineEnd: 40,
          externalCommitSha: '4f9c2b1',
        }),
      ),
    ).toBe('acme/checkout · src/Refund.java:40 at 4f9c2b1')
  })

  it('renders a whole-file link with no range at all', () => {
    expect(
      describeLinkTarget(
        link({
          externalRepo: 'acme/checkout',
          externalPath: 'src/Refund.java',
          externalCommitSha: '4f9c2b1',
        }),
      ),
    ).toBe('acme/checkout · src/Refund.java at 4f9c2b1')
  })
})

/**
 * A run's git coordinate (§177) — the same `at <sha>` convention as the link above, which is the
 * reason both live in one module and are tested in one file. Two spellings of one convention is how
 * a hundred consistent screens become a hundred slightly different ones.
 */
describe('describeRunCode', () => {
  it('names the branch and pins it to the commit', () => {
    expect(describeRunCode({ branch: 'release/4.3', commitSha: '4f9c2b1' })).toBe(
      'release/4.3 at 4f9c2b1',
    )
  })

  it('renders either half alone — a CI job may know one and not the other', () => {
    expect(describeRunCode({ branch: 'main', commitSha: null })).toBe('main')
    expect(describeRunCode({ branch: null, commitSha: '4f9c2b1' })).toBe('at 4f9c2b1')
  })

  it('is null when the run records neither, so the caller renders NOTHING', () => {
    // Not an empty string and not an em dash: most runs are manual passes in projects that record
    // no build, and a permanently empty labelled field is a screen teaching that something is
    // missing (§157's signal, one screen over).
    expect(describeRunCode({ branch: null, commitSha: null })).toBeNull()
  })
})
