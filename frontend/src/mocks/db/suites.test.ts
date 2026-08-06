import { describe, expect, it } from 'vitest'
import { db } from './store'
import { suiteCaseCount, suiteList, suiteMemberCaseIds } from './suites'

/**
 * The mock's suite rules — a new file, for the defect §98 found and fixed.
 *
 * **The count and the grid have been answering different questions since §96.** `Suite.caseCount`
 * has meant the resolved union — filed ∪ added ∪ tagged — while the case grid's `?suiteId=` filter
 * selected the *filed* arm alone. A suite whose whole definition is a tag rule therefore showed a
 * rail count of two over a grid of none, with nothing on either screen to say which was lying.
 *
 * It stayed invisible for a structural reason, and that reason is what this file exists to remove:
 * **every suite in the fixture answered the same number under both rules**, because every suite
 * was a folder people filed into. §96 added one that does not (Smoke), and §98 makes tag-defined
 * suites the ordinary case rather than the exotic one.
 *
 * `workItems.test.ts`'s argument, applied here: `npm run build` cannot catch a generator that
 * implements the wrong rule, and neither can a page test that supplies its own props. What catches
 * it is asserting the rules against the fixture store.
 */
describe('The mock’s suite membership', () => {
  const smoke = () => db.suites.find((s) => s.name === 'Smoke')!

  /**
   * THE non-vacuity assertion, and it comes first because everything below is satisfied by an
   * empty set. A fixture in which no suite claims a case it does not hold makes "the count and the
   * filter agree" true of both the correct implementation and the defective one.
   */
  it('has a suite whose members are claimed by tag and filed somewhere else', () => {
    const members = [...suiteMemberCaseIds(smoke().id)]

    expect(members.length).toBeGreaterThan(0)
    // Not one of them is filed here — so the filed arm alone would answer zero, which is exactly
    // what the grid did before §98.
    expect(db.cases.filter((c) => c.suiteId === smoke().id)).toHaveLength(0)
    expect(suiteCaseCount(smoke().id)).toBe(0)
  })

  it('counts the union, so the rail’s number is the size of the grid’s set', () => {
    for (const suite of suiteList()) {
      expect(suite.caseCount, `${suite.name} disagrees with its own membership`).toBe(
        suiteMemberCaseIds(suite.id).size,
      )
    }
  })

  /**
   * The delete gate is deliberately NOT the union — the one place the three arms are not
   * interchangeable, and the one a careless "make them consistent" pass would break.
   *
   * Deleting a suite that merely *claims* a case by tag takes the claim, not the case; only a
   * filed case is held here, and only a filed case may stand in the way. Smoke proves the split
   * in the direction that matters: two members, and it is deletable.
   */
  it('blocks a delete on FILED cases only, never on claimed ones', () => {
    expect(suiteMemberCaseIds(smoke().id).size).toBeGreaterThan(0)
    expect(suiteCaseCount(smoke().id)).toBe(0)

    const filed = db.suites.find((s) => db.cases.some((c) => c.suiteId === s.id))!
    expect(suiteCaseCount(filed.id)).toBeGreaterThan(0)
  })

  /** Flat since §98: nothing in the payload carries a parent, so nothing can be orphaned. */
  it('serves suites with no parent and no path', () => {
    for (const suite of suiteList()) {
      expect(suite).not.toHaveProperty('parentId')
      expect(suite).not.toHaveProperty('path')
    }
  })
})
