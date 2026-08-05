import { describe, expect, it } from 'vitest'
import { suggestedVerdict } from './suggestedVerdict'

/**
 * The rules are individually obvious and their interaction is not — which is exactly the shape
 * `stepReorder.test.ts` exists for one directory over.
 *
 * The two that would do real damage if they inverted:
 *
 *   - a **partially marked** checklist must never propose Passed ("all passed so far" is not
 *     "passed"), while a partially marked checklist with one failure must propose Failed —
 *     the completeness rule and the failure rule point opposite ways and both are right;
 *   - a **skipped** step must propose nothing rather than falling through to Passed, because
 *     the case was partly not exercised and there is no honest single verdict for that.
 */
describe('suggestedVerdict', () => {
  it('proposes Failed the moment one step fails, however much is left unmarked', () => {
    expect(suggestedVerdict(['PASSED', 'FAILED', null, null])).toBe('FAILED')
    expect(suggestedVerdict([null, 'FAILED'])).toBe('FAILED')
  })

  it('proposes nothing while any step is unmarked', () => {
    expect(suggestedVerdict(['PASSED', 'PASSED', null])).toBeNull()
    expect(suggestedVerdict([null, null])).toBeNull()
  })

  it('proposes Passed only when every step passed', () => {
    expect(suggestedVerdict(['PASSED', 'PASSED', 'PASSED'])).toBe('PASSED')
  })

  it('proposes Blocked when a step could not be run and none failed', () => {
    expect(suggestedVerdict(['PASSED', 'BLOCKED', 'PASSED'])).toBe('BLOCKED')
  })

  it('lets Failed outrank Blocked — a failure is a statement about the software', () => {
    expect(suggestedVerdict(['BLOCKED', 'FAILED'])).toBe('FAILED')
  })

  it('proposes nothing for a skipped step rather than folding it into Passed', () => {
    expect(suggestedVerdict(['PASSED', 'SKIPPED'])).toBeNull()
    expect(suggestedVerdict(['SKIPPED', 'SKIPPED'])).toBeNull()
  })

  /*
    `[].every(...)` is vacuously true, so the naive implementation proposes PASSED for a case
    with no structured steps — a green verdict derived from no evidence at all, on precisely the
    cases (exploratory, unstructured) where the tester's own judgement is the only input there is.
  */
  it('proposes nothing for a case with no steps', () => {
    expect(suggestedVerdict([])).toBeNull()
  })
})
