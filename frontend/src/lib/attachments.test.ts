import { describe, expect, it } from 'vitest'
import { MAX_UPLOAD_BYTES, uploadRefusal } from './attachments'

/**
 * The one rule every upload control in the product shares.
 *
 * Worth its own unit test for the reason `impactSummary` and `stepOutcome` are: the two refusals it
 * returns are about **different failures with the same appearance**, and both of them are reachable
 * only by picking a file — which no component test does cheaply and no browser pass does at all
 * without a 10MB fixture on disk.
 */
describe('uploadRefusal', () => {
  const file = (name: string, size: number) => ({ name, size })

  it('accepts an ordinary file', () => {
    expect(uploadRefusal(file('screenshot.png', 184_320))).toBeNull()
  })

  it('accepts a file exactly at the limit', () => {
    // The boundary is `>`, not `>=`: the servlet container's `max-file-size` is inclusive, so a
    // client refusing the largest acceptable file would refuse something the server takes — a limit
    // the product enforces more strictly than it documents.
    expect(uploadRefusal(file('exact.bin', MAX_UPLOAD_BYTES))).toBeNull()
  })

  it('refuses one byte over the limit', () => {
    expect(uploadRefusal(file('huge.mov', MAX_UPLOAD_BYTES + 1))).not.toBeNull()
  })

  it('names both numbers, not just the file', () => {
    // Both sides of the comparison, or the user is left halving the file to find the limit. The
    // two are formatted by the same function so they can be compared by reading them.
    const refusal = uploadRefusal(file('demo.mov', 25 * 1024 * 1024))

    expect(refusal).toContain('demo.mov')
    expect(refusal).toContain('26 MB')
    expect(refusal).toContain('10 MB')
  })

  it('refuses an empty file, and not as "too big"', () => {
    // A genuinely different failure. `ArtifactFileController` answers a zero-byte part with a 400
    // naming the field, while the case and page endpoints happily store an attachment whose
    // download is a zero-length file — a row that looks attached and opens to nothing.
    const refusal = uploadRefusal(file('empty.txt', 0))

    expect(refusal).toContain('empty.txt')
    expect(refusal).toMatch(/empty/i)
    expect(refusal).not.toMatch(/up to/i)
  })
})
