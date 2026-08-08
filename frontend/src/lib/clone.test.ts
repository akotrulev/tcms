import { describe, expect, it } from 'vitest'
import { copyOf } from './clone'

/**
 * The suggested name for a clone.
 *
 * Three properties, and the second is the only one a hand-rolled version gets wrong: the result is
 * **never longer than the limit**, because the field it prefills mirrors that limit and a
 * suggestion the form immediately refuses is worse than an empty box. The arithmetic that goes
 * wrong is the ellipsis — it is a character, and a truncation to exactly `max` before appending it
 * lands one over.
 */
describe('copyOf', () => {
  it('prefixes the source name', () => {
    expect(copyOf('Regression 4.3', 200)).toBe('Copy of Regression 4.3')
  })

  it('never exceeds the limit, ellipsis included', () => {
    const long = 'x'.repeat(400)
    const suggested = copyOf(long, 200)
    expect(suggested.length).toBe(200)
    expect(suggested.startsWith('Copy of ')).toBe(true)
    expect(suggested.endsWith('…')).toBe(true)
  })

  it('shortens the NAME and never the prefix — a “Copy o…” says nothing at all', () => {
    // Right at the boundary: one character over is where a version that truncates the whole
    // sentence starts eating the words that carry the meaning.
    expect(copyOf('abcdefghij', 15)).toBe('Copy of abcdef…')
  })

  it('leaves a name that exactly fits alone', () => {
    expect(copyOf('abc', 11)).toBe('Copy of abc')
  })
})
