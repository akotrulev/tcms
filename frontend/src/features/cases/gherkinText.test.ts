import { describe, expect, it } from 'vitest'
import {
  caretPositionOf,
  completeStepAt,
  scan,
  sectionLine,
  splitFeatureLine,
  stepFragmentAt,
} from './gherkinText'

/**
 * The colouring scanner, per construct (003 Phase 2's *Tests* list).
 *
 * Pure, so it is asked directly rather than through the surface that draws it. What is pinned here
 * is only what the module claims: which spans a line has, which line a section opens on, and what
 * sits under the caret. **Nothing here asserts that a document is valid Gherkin** — the module's
 * header says it has no authority over that, and a test that checked it would be the second parser
 * `featureFiles.ts` forbids, written as an assertion instead of as code.
 */

const kinds = (line: { spans: { kind: string }[] }) => line.spans.map((span) => span.kind)
const textOf = (line: { spans: { text: string }[] }) => line.spans.map((span) => span.text).join('')

describe('scan — the constructs', () => {
  it('gives every line a 1-based number and reproduces it byte for byte', () => {
    const text = 'Feature: Refunds\n\n  Scenario: Double refund\n    Given a payment'
    const lines = scan(text)

    expect(lines.map((line) => line.number)).toEqual([1, 2, 3, 4])
    // The mirror sits behind a transparent textarea, so a span set that does not reassemble into
    // the original line puts the colour a character off the text it is colouring.
    expect(lines.map(textOf).join('\n')).toBe(text)
  })

  it('scans empty text as one line, not as none', () => {
    // The caret has to have a line to sit on and a refusal has to have a line 1 to name.
    expect(scan('')).toHaveLength(1)
  })

  it('splits a step into keyword and sentence', () => {
    const [line] = scan('    Given a refundable payment')
    expect(kinds(line)).toEqual(['plain', 'keyword', 'plain', 'sentence'])
    expect(line.spans[1].text).toBe('Given')
    expect(line.spans[3].text).toBe('a refundable payment')
  })

  it('treats * as a keyword — Gherkin’s own spelling for a step that has none', () => {
    const [line] = scan('  * the payment is settled')
    expect(kinds(line)).toContain('keyword')
    expect(line.spans.find((span) => span.kind === 'keyword')?.text).toBe('*')
  })

  it('reads a section keyword up to its colon, sentence after it', () => {
    const [line] = scan('Feature: Refunds')
    expect(line.spans[0]).toEqual({ kind: 'keyword', text: 'Feature:' })
    expect(line.spans[1]).toEqual({ kind: 'sentence', text: ' Refunds' })
  })

  it('paints a doc string block, fences included, and stops at the closing fence', () => {
    const lines = scan('Given a body\n  """\n  Given this is not a step\n  """\nThen it works')

    expect(kinds(lines[1])).toEqual(['docstring'])
    // The whole point of carrying the fence state between lines: a step sentence inside a block is
    // content, and painting it as a keyword tells the author it will be executed.
    expect(kinds(lines[2])).toEqual(['docstring'])
    expect(kinds(lines[3])).toEqual(['docstring'])
    expect(kinds(lines[4])).toContain('keyword')
  })

  it('paints a table row and a comment', () => {
    expect(kinds(scan('  | amount | currency |')[0])).toEqual(['plain', 'table'])
    // `# data:` is what the renderer emits for a step's test data, so it must not read as prose.
    expect(kinds(scan('    # data: 120.00 EUR')[0])).toEqual(['plain', 'comment'])
  })

  it('paints a tag line', () => {
    expect(kinds(scan('@smoke @regression')[0])).toEqual(['tag'])
  })

  it('leaves free text under a Scenario as plain', () => {
    expect(kinds(scan('  Why this case exists.')[0])).toEqual(['plain'])
  })
})

describe('sectionLine', () => {
  it('finds the line a section opens on, 1-based', () => {
    const text = 'Feature: Refunds\n\n  Background:\n    Given a login\n\n  Scenario: X'
    expect(sectionLine(text, 'Feature')).toBe(1)
    expect(sectionLine(text, 'Background')).toBe(3)
    expect(sectionLine(text, 'Scenario')).toBe(6)
  })

  it('answers null when the section is absent, rather than 0 or 1', () => {
    // Null is what the caller branches on to decide there is nothing to anchor a refusal to.
    expect(sectionLine('Given a payment', 'Background')).toBeNull()
  })
})

describe('the caret', () => {
  it('reports the line and column an offset lands on', () => {
    expect(caretPositionOf('ab\ncde', 4)).toEqual({ line: 2, column: 1 })
    expect(caretPositionOf('ab\ncde', 0)).toEqual({ line: 1, column: 0 })
  })

  it('gives the sentence under the caret, without its keyword', () => {
    const text = '  Given a refund'
    expect(stepFragmentAt(text, text.length)).toBe('a refund')
  })

  it('stops at the caret rather than reading the rest of the line', () => {
    const text = '  Given a refund of everything'
    expect(stepFragmentAt(text, '  Given a refund'.length)).toBe('a refund')
  })

  it('searches for nothing on a line that is not a step being written', () => {
    expect(stepFragmentAt('# a comment', 5)).toBeNull()
    expect(stepFragmentAt('  | a | b |', 5)).toBeNull()
    expect(stepFragmentAt('Feature: Refunds', 12)).toBeNull()
    // Inside a doc string a sentence is content, and completing it would paste a step into a body.
    expect(stepFragmentAt('"""\nGiven a payment\n"""', 10)).toBeNull()
  })

  it('searches for nothing on an empty sentence — the corpus gate, restated where the fragment is made', () => {
    expect(stepFragmentAt('  Given ', 8)).toBeNull()
  })

  it('replaces the typed fragment rather than inserting after it, and keeps the keyword', () => {
    const text = '  Given a ref\n  Then it is refunded'
    const completed = completeStepAt(text, '  Given a ref'.length, 'a refundable payment')

    expect(completed.text).toBe('  Given a refundable payment\n  Then it is refunded')
    expect(completed.caret).toBe('  Given a refundable payment'.length)
  })

  it('changes nothing when the caret is not in a step sentence', () => {
    const text = 'Feature: Refunds'
    expect(completeStepAt(text, 4, 'anything')).toEqual({ text, caret: 4 })
  })
})

describe('splitFeatureLine', () => {
  it('lifts the Feature: line and the blank line under it out of the body', () => {
    const { featureName, body } = splitFeatureLine('Feature: Refunds\n\n  Scenario: X\n    Given a payment')

    expect(featureName).toBe('Refunds')
    // Left in, the document would grow a blank line on every render → commit → render round trip.
    expect(body).toBe('  Scenario: X\n    Given a payment')
  })

  it('leaves the text alone when there is no header, rather than inventing one', () => {
    expect(splitFeatureLine('Given a payment')).toEqual({ featureName: null, body: 'Given a payment' })
  })

  it('leaves a SECOND Feature: line in the body, for the server to refuse', () => {
    const { body } = splitFeatureLine('Feature: One\nGiven a step\nFeature: Two')
    expect(body).toBe('Given a step\nFeature: Two')
  })
})
